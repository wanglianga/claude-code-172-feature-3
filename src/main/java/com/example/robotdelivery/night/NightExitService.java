package com.example.robotdelivery.night;

import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import com.example.robotdelivery.robot.Robot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 限行开始时已在楼内机器人的催办撤离：
 * 巡检发现处于夜间限行时段、机器人仍在楼内（门禁已开通之后的在途状态）的订单，
 * 立即催办尽快完成当前订单，生成撤离计划（预计完成时间 + 撤离路线）供物业端查看；
 * 订单完成（签收/退回）后机器人按路线撤离，登记实际撤离时间。
 */
@Slf4j
@Service
public class NightExitService {

    /** 机器人已进入楼内的在途状态 */
    static final Set<DeliveryStatus> INSIDE_BUILDING_STATUSES = EnumSet.of(
            DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR,
            DeliveryStatus.WAITING_PICKUP, DeliveryStatus.COMPARTMENT_OPEN);

    private final DeliveryOrderRepository orderRepository;
    private final NightExitPlanRepository exitPlanRepository;
    private final NightPolicyService nightPolicyService;
    private final DeliveryEventService eventService;
    private final DeliveryProperties properties;

    public NightExitService(DeliveryOrderRepository orderRepository,
                            NightExitPlanRepository exitPlanRepository,
                            NightPolicyService nightPolicyService,
                            DeliveryEventService eventService,
                            DeliveryProperties properties) {
        this.orderRepository = orderRepository;
        this.exitPlanRepository = exitPlanRepository;
        this.nightPolicyService = nightPolicyService;
        this.eventService = eventService;
        this.properties = properties;
    }

    /** 仅机器人进楼配送且机器人在场的订单才属于"机器人已在楼内"（门岗自提/人工配送无机器人在楼） */
    private boolean isRobotInside(DeliveryOrder order) {
        return order.getRobot() != null
                && order.getFulfillmentMode() == com.example.robotdelivery.order.FulfillmentMode.ROBOT
                && INSIDE_BUILDING_STATUSES.contains(order.getStatus());
    }

    /**
     * 巡检：对每个处于夜间限行时段的小区，催促尚未登记撤离计划的在楼机器人尽快完成订单并撤离。
     * 命中药品临时放行的订单不受催办（放行允许其夜间继续在楼配送）。
     */
    @Transactional
    public int expediteInsideRobots() {
        int planned = 0;
        List<DeliveryOrder> inside = orderRepository.findByStatusIn(INSIDE_BUILDING_STATUSES);
        for (DeliveryOrder order : inside) {
            if (isRobotInside(order)
                    && nightPolicyService.isNightAt(order.getCommunity(), LocalDateTime.now())
                    && createExitPlanIfNeeded(order)) {
                planned++;
            }
        }
        return planned;
    }

    /**
     * 夜间限行规则变更后立即影响在楼机器人：对指定小区当前在楼且未登记撤离计划、
     * 未命中药品放行的订单生成催办撤离计划。返回新生成计划数。
     */
    @Transactional
    public int expediteForCommunity(Long communityId) {
        int planned = 0;
        for (DeliveryOrder order : orderRepository.findByStatusIn(INSIDE_BUILDING_STATUSES)) {
            if (order.getCommunity().getId().equals(communityId)
                    && isRobotInside(order) && createExitPlanIfNeeded(order)) {
                planned++;
            }
        }
        return planned;
    }

    private boolean createExitPlanIfNeeded(DeliveryOrder order) {
        LocalDateTime now = LocalDateTime.now();
        if (exitPlanRepository.findByDeliveryId(order.getId()).isPresent()) {
            return false;
        }
        if (nightPolicyService.findExemption(order.getCommunity(), order.getOrderType(),
                order.getBuilding(), now) != null) {
            return false;
        }
        createExitPlan(order, now);
        return true;
    }

    /** 规则变更（或手动）时为单个在楼订单生成催办撤离计划 */
    @Transactional
    public NightExitPlan createExitPlan(DeliveryOrder order, LocalDateTime now) {
        Robot robot = order.getRobot();
        LocalDateTime due = now.plusSeconds(properties.getNightExitGraceSeconds());

        order.setNightExpedited(true);
        order.setNightExpeditedAt(now);
        order.setExitDueAt(due);
        orderRepository.save(order);

        String route = buildExitRoute(order);

        NightExitPlan plan = new NightExitPlan();
        plan.setDelivery(order);
        plan.setRobot(robot);
        plan.setCommunity(order.getCommunity());
        plan.setBuilding(order.getBuilding());
        plan.setStatus(NightExitPlanStatus.NOTIFIED);
        plan.setExitRoute(route);
        plan.setNotifiedAt(now);
        plan.setExpectedExitAt(due);
        exitPlanRepository.save(plan);

        Map<String, Object> payload = basePayload(order, plan);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.NIGHT_ORDER_EXPEDITED, EventActor.SYSTEM, "NIGHT_LIMIT", null, payload);

        Map<String, Object> routePayload = basePayload(order, plan);
        routePayload.put("detail", "限行已开始，完成当前订单后立即按路线撤离楼栋");
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.NIGHT_EXIT_PLANNED, EventActor.SYSTEM, "NIGHT_LIMIT", null, routePayload);
        log.info("夜间催办在楼机器人: orderNo={} robotCode={} expectedExitAt={}",
                order.getOrderNo(), robot == null ? null : robot.getCode(), due);
        return plan;
    }

    /**
     * 机器人完成当前订单并按路线撤离楼栋（运营/物业确认）。
     * 若订单尚未终结，先记录订单完成时间为当前时刻。
     */
    @Transactional
    public NightExitPlan confirmExit(Long planId) {
        NightExitPlan plan = exitPlanRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("撤离计划不存在: " + planId));
        if (plan.getStatus() == NightExitPlanStatus.EXITED) {
            throw ApiException.conflict("撤离计划已完成: " + planId);
        }
        LocalDateTime now = LocalDateTime.now();
        plan.setStatus(NightExitPlanStatus.EXITED);
        plan.setExitedAt(now);
        DeliveryOrder order = plan.getDelivery();
        if (DeliveryStatus.TERMINAL.contains(order.getStatus())) {
            plan.setFinishedOrderAt(order.getSignedAt() != null ? order.getSignedAt()
                    : order.getReturnedAt() != null ? order.getReturnedAt() : now);
        } else {
            plan.setFinishedOrderAt(now);
        }
        exitPlanRepository.save(plan);

        Map<String, Object> payload = basePayload(order, plan);
        payload.put("onTime", !now.isAfter(plan.getExpectedExitAt()));
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.NIGHT_EXITED, EventActor.ROBOT, null, null, payload);
        return plan;
    }

    /** 订单终结（签收/退回）时联动：存在未完成撤离计划则自动登记订单完成时间（撤离仍需机器人确认） */
    @Transactional
    public void noteOrderFinished(Long deliveryId) {
        Optional<NightExitPlan> opt = exitPlanRepository.findByDeliveryId(deliveryId);
        if (opt.isEmpty()) {
            return;
        }
        NightExitPlan plan = opt.get();
        if (plan.getFinishedOrderAt() == null) {
            plan.setFinishedOrderAt(LocalDateTime.now());
            exitPlanRepository.save(plan);
        }
    }

    /** 撤离路线：当前位置（楼层/房号） -> 电梯/步行梯 -> 一层大堂 -> 小区门岗 -> 楼外夜间等待区 */
    private String buildExitRoute(DeliveryOrder order) {
        String floorHint = order.getRoomNo() == null ? "目标楼层"
                : order.getRoomNo().replaceAll("\\D.*$", "") + "层";
        String unit = order.getUnitNo() == null ? "" : order.getUnitNo() + " ";
        return String.format(
                "%s%s%s %s -> 乘电梯/步行梯下行 -> 一层大堂 -> 出单元门 -> 沿小区主路 -> 小区门岗 -> 楼外夜间等待区",
                unit, order.getBuilding().getName(),
                order.getRoomNo() == null ? "" : " " + order.getRoomNo() + "室", floorHint);
    }

    /** 物业端视图（事务内组装，避免懒加载代理序列化问题） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPlanViews(Long communityId, NightExitPlanStatus status) {
        List<NightExitPlan> plans = status == null
                ? exitPlanRepository.findByCommunityIdOrderByExpectedExitAtAsc(communityId)
                : exitPlanRepository.findByCommunityIdAndStatusOrderByExpectedExitAtAsc(communityId, status);
        return plans.stream().map(this::toView).toList();
    }

    public Map<String, Object> planView(NightExitPlan plan) {
        return toView(plan);
    }

    private Map<String, Object> toView(NightExitPlan plan) {        DeliveryOrder order = plan.getDelivery();
        Map<String, Object> v = new HashMap<>();
        v.put("id", plan.getId());
        v.put("status", plan.getStatus().name());
        v.put("communityId", plan.getCommunity().getId());
        v.put("community", plan.getCommunity().getName());
        v.put("buildingId", plan.getBuilding().getId());
        v.put("building", plan.getBuilding().getName());
        v.put("robotId", plan.getRobot().getId());
        v.put("robotCode", plan.getRobot().getCode());
        v.put("deliveryId", order.getId());
        v.put("orderNo", order.getOrderNo());
        v.put("orderType", order.getOrderType().name());
        v.put("roomNo", order.getRoomNo());
        v.put("exitRoute", plan.getExitRoute());
        v.put("notifiedAt", plan.getNotifiedAt());
        v.put("expectedExitAt", plan.getExpectedExitAt());
        v.put("finishedOrderAt", plan.getFinishedOrderAt());
        v.put("exitedAt", plan.getExitedAt());
        return v;
    }

    private Map<String, Object> basePayload(DeliveryOrder order, NightExitPlan plan) {        Map<String, Object> payload = new HashMap<>();
        payload.put("planId", plan.getId());
        payload.put("orderNo", order.getOrderNo());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        payload.put("building", order.getBuilding().getName());
        payload.put("roomNo", order.getRoomNo());
        payload.put("notifiedAt", plan.getNotifiedAt().toString());
        payload.put("expectedExitAt", plan.getExpectedExitAt().toString());
        payload.put("exitRoute", plan.getExitRoute());
        return payload;
    }
}
