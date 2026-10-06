package com.example.robotdelivery.night;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.BuildingRepository;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.community.CommunityRepository;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.event.DeliveryEvent;
import com.example.robotdelivery.event.DeliveryEventRepository;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import com.example.robotdelivery.order.FulfillmentMode;
import com.example.robotdelivery.order.OrderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 物业夜间限行核心逻辑：
 * <ul>
 *   <li>接单前校验：预计送达时间落入限行时段则降级为门岗自提/人工配送（临时放行除外）</li>
 *   <li>机器人已在楼内：规则变更或巡检发现进入夜间时段时，要求尽快完成当前订单并退出</li>
 *   <li>物业端视图：在楼机器人的要求结束时间、实际退出时间与行进路线</li>
 *   <li>临时放行：某类订单（如药品）在有效期内、指定楼栋不受限行约束</li>
 * </ul>
 */
@Service
public class NightCurfewService {

    /** 机器人已进楼（未完结）的订单状态 */
    public static final Set<DeliveryStatus> IN_BUILDING = EnumSet.of(
            DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR,
            DeliveryStatus.WAITING_PICKUP, DeliveryStatus.COMPARTMENT_OPEN);

    /** 允许上报"退出楼栋"的订单状态（楼内配送中 / 已签收 / 退回途中） */
    private static final Set<DeliveryStatus> EXIT_ALLOWED = EnumSet.of(
            DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR,
            DeliveryStatus.WAITING_PICKUP, DeliveryStatus.COMPARTMENT_OPEN,
            DeliveryStatus.SIGNED, DeliveryStatus.RETURNING);

    private final CommunityRepository communityRepository;
    private final BuildingRepository buildingRepository;
    private final DeliveryOrderRepository orderRepository;
    private final NightExemptionRepository exemptionRepository;
    private final DeliveryEventRepository eventRepository;
    private final DeliveryEventService eventService;
    private final DeliveryProperties properties;
    private final ObjectMapper objectMapper;

    public NightCurfewService(CommunityRepository communityRepository,
                              BuildingRepository buildingRepository,
                              DeliveryOrderRepository orderRepository,
                              NightExemptionRepository exemptionRepository,
                              DeliveryEventRepository eventRepository,
                              DeliveryEventService eventService,
                              DeliveryProperties properties,
                              ObjectMapper objectMapper) {
        this.communityRepository = communityRepository;
        this.buildingRepository = buildingRepository;
        this.orderRepository = orderRepository;
        this.exemptionRepository = exemptionRepository;
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ---------- 接单前校验 ----------

    /** 预计送达时间是否落入社区夜间限行时段 */
    public boolean isNightRestrictedAt(Community community, LocalDateTime dateTime) {
        return community.isNightRestricted()
                && NightWindow.contains(community.getNightStart(), community.getNightEnd(), dateTime);
    }

    /** 当前是否处于社区夜间限行时段 */
    public boolean isNightNow(Community community) {
        return community.isNightRestricted()
                && NightWindow.contains(community.getNightStart(), community.getNightEnd(), LocalTime.now());
    }

    /** 查找指定时刻生效中的临时放行（社区+楼栋+订单类型匹配，未撤销，在有效期内） */
    public Optional<NightExemption> findActiveExemption(Long communityId, Long buildingId,
                                                        OrderType orderType, LocalDateTime at) {
        return exemptionRepository
                .findByCommunityIdAndBuildingIdAndOrderTypeAndStatusAndValidFromLessThanEqualAndValidUntilGreaterThan(
                        communityId, buildingId, orderType, ExemptionStatus.ACTIVE, at, at)
                .stream().findFirst();
    }

    // ---------- 在楼机器人尽快退出 ----------

    /**
     * 若社区当前处于夜间限行时段，标记所有在楼（机器人配送中）订单：
     * 要求尽快完成当前订单并退出，返回新标记数量。幂等：已标记的订单不重复处理。
     */
    @Transactional
    public int markEvacuationForCommunity(Community community) {
        if (!isNightNow(community)) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().plusMinutes(properties.getNightExitGraceMinutes());
        int marked = 0;
        for (DeliveryOrder order : orderRepository.findByCommunityIdAndStatusIn(community.getId(), IN_BUILDING)) {
            if (order.getFulfillmentMode() != FulfillmentMode.ROBOT || order.isNightExitRequired()) {
                continue;
            }
            order.setNightExitRequired(true);
            order.setNightExitDeadline(deadline);
            orderRepository.save(order);

            Map<String, Object> payload = new HashMap<>();
            payload.put("orderNo", order.getOrderNo());
            payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
            payload.put("building", order.getBuilding().getName());
            payload.put("roomNo", order.getRoomNo());
            payload.put("nightStart", community.getNightStart());
            payload.put("nightEnd", community.getNightEnd());
            payload.put("exitDeadline", deadline.toString());
            payload.put("requirement", "夜间限行，请尽快完成当前订单并退出楼栋");
            eventService.emit(order.getId(), community.getId(),
                    EventType.NIGHT_EVACUATION_REQUIRED, EventActor.SYSTEM, "NIGHT_LIMIT", null, payload);
            marked++;
        }
        return marked;
    }

    /** 巡检入口：扫描所有开启夜间限行且当前处于限行时段的社区 */
    @Transactional
    public void scanAndMarkEvacuation() {
        for (Community community : communityRepository.findAll()) {
            if (community.isNightRestricted()) {
                markEvacuationForCommunity(community);
            }
        }
    }

    /** 机器人上报退出楼栋：记录实际退出时间，物业端可见 */
    @Transactional
    public DeliveryOrder exitBuilding(Long orderId) {
        DeliveryOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + orderId));
        if (order.getExitedBuildingAt() != null) {
            throw ApiException.conflict("机器人已退出楼栋: " + order.getExitedBuildingAt());
        }
        if (!EXIT_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许上报退出楼栋");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setExitedBuildingAt(now);
        orderRepository.save(order);

        boolean onTime = order.getNightExitDeadline() == null || !now.isAfter(order.getNightExitDeadline());
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        payload.put("building", order.getBuilding().getName());
        payload.put("nightExitRequired", order.isNightExitRequired());
        payload.put("nightExitDeadline",
                order.getNightExitDeadline() == null ? null : order.getNightExitDeadline().toString());
        payload.put("exitedAt", now.toString());
        payload.put("exitOnTime", onTime);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ROBOT_EXITED_BUILDING, EventActor.ROBOT, payload);
        return order;
    }

    // ---------- 物业端视图：结束时间与路线 ----------

    /**
     * 物业端夜间限行视图：本社区被要求退出的机器人订单 + 当前仍在楼内的机器人订单，
     * 包含要求结束时间（nightExitDeadline）、实际退出时间（exitedBuildingAt）与行进路线。
     */
    public List<Map<String, Object>> curfewView(Long communityId) {
        Map<Long, DeliveryOrder> orders = new LinkedHashMap<>();
        for (DeliveryOrder o : orderRepository.findByCommunityIdAndNightExitRequiredTrue(communityId)) {
            orders.put(o.getId(), o);
        }
        for (DeliveryOrder o : orderRepository.findByCommunityIdAndStatusIn(communityId, IN_BUILDING)) {
            if (o.getFulfillmentMode() == FulfillmentMode.ROBOT) {
                orders.putIfAbsent(o.getId(), o);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (DeliveryOrder order : orders.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", order.getId());
            row.put("orderNo", order.getOrderNo());
            row.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
            row.put("building", order.getBuilding().getName());
            row.put("roomNo", order.getRoomNo());
            row.put("status", order.getStatus().name());
            row.put("nightExitRequired", order.isNightExitRequired());
            row.put("nightExitDeadline", order.getNightExitDeadline());
            row.put("exitedBuildingAt", order.getExitedBuildingAt());
            row.put("exitOnTime", order.getNightExitDeadline() == null || order.getExitedBuildingAt() == null
                    ? null : !order.getExitedBuildingAt().isAfter(order.getNightExitDeadline()));
            row.put("route", buildRoute(order.getId()));
            result.add(row);
        }
        return result;
    }

    /** 从统一事件流组装机器人在楼内的行进路线（门禁→电梯→避障/绕行→到门→签收→退出） */
    private List<Map<String, Object>> buildRoute(Long deliveryId) {
        List<Map<String, Object>> route = new ArrayList<>();
        for (DeliveryEvent event : eventRepository.findByDeliveryIdOrderByOccurredAtAsc(deliveryId)) {
            String description = routeDescription(event);
            if (description == null) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("occurredAt", event.getOccurredAt());
            point.put("eventType", event.getEventType());
            point.put("description", description);
            route.add(point);
        }
        return route;
    }

    private String routeDescription(DeliveryEvent event) {
        JsonNode payload = parsePayload(event.getPayload());
        return switch (event.getEventType()) {
            case "ACCESS_GRANTED" -> text(payload, "gateName", "小区门口") + " 门禁开通，进入楼栋";
            case "ELEVATOR_REQUESTED" ->
                    "请求电梯 " + payload.path("fromFloor").asInt() + "层→" + payload.path("toFloor").asInt() + "层";
            case "ELEVATOR_ASSIGNED" -> "电梯派梯 " + text(payload, "carId", "");
            case "ELEVATOR_COMPLETED" -> "乘梯到达目标楼层";
            case "OBSTACLE_WAIT" -> "避障停留 " + (event.getWaitSeconds() == null ? 0 : event.getWaitSeconds())
                    + "秒（" + text(payload, "location", "楼内") + "）";
            case "ROUTE_ADJUSTED" -> "路线调整（" + text(payload, "detail",
                    event.getReason() == null ? "" : event.getReason()) + "）";
            case "ARRIVED_AT_DOOR" -> "到达门口 " + text(payload, "roomNo", "");
            case "SIGNED" -> "用户签收";
            case "RETURN_STARTED" -> "开始退回（" + text(payload, "reason", "") + "）";
            case "NIGHT_EVACUATION_REQUIRED" -> "系统要求尽快完成并退出楼栋";
            case "ROBOT_EXITED_BUILDING" -> "机器人退出楼栋";
            default -> null;
        };
    }

    private JsonNode parsePayload(String payload) {
        try {
            return payload == null ? objectMapper.createObjectNode() : objectMapper.readTree(payload);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asText();
    }

    // ---------- 临时放行 ----------

    /** 物业创建临时放行：某类订单（如药品）在有效期内、指定楼栋不受夜间限行约束 */
    @Transactional
    public NightExemption createExemption(Long communityId, Long buildingId, OrderType orderType,
                                          LocalDateTime validFrom, LocalDateTime validUntil,
                                          String reason, String createdBy) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> ApiException.notFound("社区不存在: " + communityId));
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> ApiException.notFound("楼栋不存在: " + buildingId));
        if (!building.getCommunity().getId().equals(community.getId())) {
            throw ApiException.badRequest("楼栋不属于该社区: buildingId=" + buildingId);
        }
        if (validFrom == null || validUntil == null || !validFrom.isBefore(validUntil)) {
            throw ApiException.badRequest("有效期非法：validFrom 必须早于 validUntil");
        }

        NightExemption exemption = new NightExemption();
        exemption.setCommunity(community);
        exemption.setBuilding(building);
        exemption.setOrderType(orderType);
        exemption.setValidFrom(validFrom);
        exemption.setValidUntil(validUntil);
        exemption.setReason(reason);
        exemption.setCreatedBy(createdBy);
        exemptionRepository.save(exemption);

        Map<String, Object> payload = new HashMap<>();
        payload.put("exemptionId", exemption.getId());
        payload.put("communityName", community.getName());
        payload.put("building", building.getName());
        payload.put("orderType", orderType.name());
        payload.put("validFrom", validFrom.toString());
        payload.put("validUntil", validUntil.toString());
        payload.put("reason", reason);
        eventService.emit(null, community.getId(),
                EventType.NIGHT_EXEMPTION_CREATED, EventActor.PROPERTY, payload);
        return exemption;
    }

    /** 撤销临时放行（撤销后该社区后续接单恢复夜间限行校验） */
    @Transactional
    public NightExemption revokeExemption(Long exemptionId) {
        NightExemption exemption = exemptionRepository.findById(exemptionId)
                .orElseThrow(() -> ApiException.notFound("临时放行不存在: " + exemptionId));
        if (exemption.getStatus() == ExemptionStatus.REVOKED) {
            throw ApiException.conflict("临时放行已撤销: " + exemptionId);
        }
        exemption.setStatus(ExemptionStatus.REVOKED);
        exemption.setRevokedAt(LocalDateTime.now());
        exemptionRepository.save(exemption);

        Map<String, Object> payload = new HashMap<>();
        payload.put("exemptionId", exemption.getId());
        payload.put("communityName", exemption.getCommunity().getName());
        payload.put("building", exemption.getBuilding().getName());
        payload.put("orderType", exemption.getOrderType().name());
        eventService.emit(null, exemption.getCommunity().getId(),
                EventType.NIGHT_EXEMPTION_REVOKED, EventActor.PROPERTY, payload);
        return exemption;
    }

    /** 列表查询后初始化关联实体（open-in-view 关闭，避免懒加载代理序列化失败） */
    @Transactional(readOnly = true)
    public List<NightExemption> listExemptions(Long communityId) {
        List<NightExemption> exemptions = exemptionRepository.findByCommunityIdOrderByIdDesc(communityId);
        exemptions.forEach(e -> {
            e.getCommunity().getName();
            e.getBuilding().getName();
        });
        return exemptions;
    }
}
