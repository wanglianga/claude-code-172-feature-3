package com.example.robotdelivery.delivery;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.BuildingRepository;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.cs.CaseType;
import com.example.robotdelivery.cs.CsService;
import com.example.robotdelivery.cs.Responsibility;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.night.NightExitService;
import com.example.robotdelivery.night.NightPolicyService;
import com.example.robotdelivery.notification.NotificationService;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.DeliveryOrderRepository;
import com.example.robotdelivery.order.DeliveryStatus;
import com.example.robotdelivery.order.FulfillmentMode;
import com.example.robotdelivery.order.OrderService;
import com.example.robotdelivery.robot.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 配送主流程编排：分配机器人 -> 门禁 -> 电梯 -> 避障 -> 到门通知 -> 开仓签收，
 * 以及各类异常（低电量/仓门故障/超时未取/送错楼栋/人工接力/退回）。
 */
@Service
public class DeliveryService {

    private static final Set<DeliveryStatus> OBSTACLE_ALLOWED = EnumSet.of(
            DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR);
    private static final Set<DeliveryStatus> RELAY_ALLOWED = EnumSet.of(
            DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR,
            DeliveryStatus.WAITING_PICKUP, DeliveryStatus.EXCEPTION);
    private static final Set<DeliveryStatus> REDELIVER_ALLOWED = EnumSet.of(
            DeliveryStatus.EXCEPTION, DeliveryStatus.ENTERING, DeliveryStatus.ELEVATOR_WAIT,
            DeliveryStatus.ON_FLOOR, DeliveryStatus.WAITING_PICKUP);
    private static final Set<DeliveryStatus> RETURN_ALLOWED = EnumSet.of(
            DeliveryStatus.ASSIGNED, DeliveryStatus.ACCESS_PENDING, DeliveryStatus.ENTERING,
            DeliveryStatus.ELEVATOR_WAIT, DeliveryStatus.ON_FLOOR, DeliveryStatus.WAITING_PICKUP,
            DeliveryStatus.COMPARTMENT_OPEN, DeliveryStatus.EXCEPTION);

    private final DeliveryOrderRepository orderRepository;
    private final RobotRepository robotRepository;
    private final CargoCompartmentRepository compartmentRepository;
    private final BuildingRepository buildingRepository;
    private final DeliveryEventService eventService;
    private final NotificationService notificationService;
    private final CsService csService;
    private final DeliveryProperties properties;
    private final NightPolicyService nightPolicyService;
    private final NightExitService nightExitService;
    private final OrderService orderService;

    public DeliveryService(DeliveryOrderRepository orderRepository,
                           RobotRepository robotRepository,
                           CargoCompartmentRepository compartmentRepository,
                           BuildingRepository buildingRepository,
                           DeliveryEventService eventService,
                           NotificationService notificationService,
                           CsService csService,
                           DeliveryProperties properties,
                           NightPolicyService nightPolicyService,
                           NightExitService nightExitService,
                           OrderService orderService) {
        this.orderRepository = orderRepository;
        this.robotRepository = robotRepository;
        this.compartmentRepository = compartmentRepository;
        this.buildingRepository = buildingRepository;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.csService = csService;
        this.properties = properties;
        this.nightPolicyService = nightPolicyService;
        this.nightExitService = nightExitService;
        this.orderService = orderService;
    }

    /** 分配机器人与载物仓（冷藏订单优先冷藏仓） */
    @Transactional
    public DeliveryOrder assignRobot(Long orderId, Long robotId) {
        DeliveryOrder order = mustGet(orderId);
        requireStatus(order, DeliveryStatus.CREATED);

        // 接单策略：夜间限行订单已降级为门岗自提/人工配送，不分配机器人进楼
        if (order.getFulfillmentMode() != FulfillmentMode.ROBOT) {
            throw ApiException.conflict("该订单已按物业夜间限行规则改为"
                    + modeCn(order.getFulfillmentMode())
                    + "，机器人不进楼；请走门岗交接或人工配送流程");
        }
        // 规则可能在下单后收紧：分配前按当前夜间限行规则与预计到达时间再校验一次
        NightPolicyService.PreAcceptResult recheck = nightPolicyService.evaluateOnAccept(
                order.getOrderType(), order.getCommunity(), order.getBuilding(),
                order.getEstimatedDeliveryAt());
        if (recheck.nightBlocked()) {
            order.setFulfillmentMode(recheck.mode());
            orderRepository.save(order);

            Map<String, Object> payload = basePayload(order);
            payload.put("nightStart", order.getCommunity().getNightStart());
            payload.put("nightEnd", order.getCommunity().getNightEnd());
            payload.put("fulfillmentMode", recheck.mode().name());
            payload.put("reason", recheck.reason());
            payload.put("detail", "夜间限行规则在下单后收紧，分配机器人时复检命中");
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.FULFILLMENT_DOWNGRADED, EventActor.SYSTEM, "NIGHT_LIMIT", null, payload);
            throw ApiException.conflict("夜间限行规则已变更，该订单改为"
                    + modeCn(recheck.mode()) + "，机器人不可接单进楼");
        }

        Robot robot = robotRepository.findById(robotId)
                .orElseThrow(() -> ApiException.notFound("机器人不存在: " + robotId));
        if (!robot.getCommunity().getId().equals(order.getCommunity().getId())) {
            throw ApiException.badRequest("机器人不属于订单所在社区");
        }
        if (robot.getStatus() != RobotStatus.IDLE) {
            throw ApiException.conflict("机器人当前状态[" + robot.getStatus() + "]不可接单");
        }
        if (robot.getBattery() < properties.getLowBatteryThreshold()) {
            throw ApiException.conflict(
                    "机器人电量不足(" + robot.getBattery() + "%)，低于阈值" + properties.getLowBatteryThreshold() + "%");
        }
        CargoCompartment compartment = order.isColdChain()
                ? compartmentRepository.findFirstByRobotIdAndStatusAndColdChainCapableOrderByCompartmentNoAsc(
                        robotId, CompartmentStatus.IDLE, true)
                : compartmentRepository.findFirstByRobotIdAndStatusOrderByCompartmentNoAsc(
                        robotId, CompartmentStatus.IDLE);
        if (compartment == null) {
            throw ApiException.conflict("机器人无可用载物仓" + (order.isColdChain() ? "（需冷藏仓）" : ""));
        }

        compartment.setStatus(CompartmentStatus.OCCUPIED);
        compartmentRepository.save(compartment);
        robot.setStatus(RobotStatus.BUSY);
        robotRepository.save(robot);

        order.setRobot(robot);
        order.setCompartmentNo(compartment.getCompartmentNo());
        order.setStatus(DeliveryStatus.ASSIGNED);
        order.setAssignedAt(LocalDateTime.now());
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("robotCode", robot.getCode());
        payload.put("battery", robot.getBattery());
        payload.put("compartmentNo", compartment.getCompartmentNo());
        payload.put("coldChainCapable", compartment.isColdChainCapable());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ROBOT_ASSIGNED, EventActor.OPS, payload);
        return order;
    }

    /** 避障停留上报：老人儿童挡路/行人/施工等；施工同时触发路线调整 */
    @Transactional
    public DeliveryOrder reportObstacle(Long orderId, ObstacleType obstacleType, Long waitSeconds, String location) {
        DeliveryOrder order = mustGet(orderId);
        if (!OBSTACLE_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许上报避障");
        }
        Map<String, Object> payload = basePayload(order);
        payload.put("obstacleType", obstacleType.name());
        payload.put("waitSeconds", waitSeconds);
        payload.put("location", location);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.OBSTACLE_WAIT, EventActor.ROBOT, obstacleType.name(), waitSeconds, payload);

        if (obstacleType == ObstacleType.CONSTRUCTION) {
            Map<String, Object> adjustPayload = basePayload(order);
            adjustPayload.put("detail", "楼道施工，机器人绕行备用路线");
            adjustPayload.put("location", location);
            eventService.emit(order.getId(), order.getCommunity().getId(),
                    EventType.ROUTE_ADJUSTED, EventActor.ROBOT, "CONSTRUCTION", waitSeconds, adjustPayload);
        }
        return order;
    }

    /** 到达用户门口：短信 + 小程序通知用户取件（机器人单；人工配送请走 manual-arrive-door） */
    @Transactional
    public DeliveryOrder arriveDoor(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getFulfillmentMode() != FulfillmentMode.ROBOT) {
            throw ApiException.badRequest("门岗自提/人工配送订单请走对应履约流程");
        }
        if (order.getStatus() != DeliveryStatus.ENTERING && order.getStatus() != DeliveryStatus.ON_FLOOR) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许上报到门（需先进入楼栋/到达楼层）");
        }
        order.setStatus(DeliveryStatus.WAITING_PICKUP);
        order.setNotifiedAt(LocalDateTime.now());
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ARRIVED_AT_DOOR, EventActor.ROBOT, basePayload(order));
        notificationService.notifyArrival(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("channels", "SMS,MINI_PROGRAM");
        payload.put("userPhone", order.getUserPhone());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.USER_NOTIFIED, EventActor.SYSTEM, payload);
        return order;
    }

    /** 用户通过小程序/按键打开载物仓 */
    @Transactional
    public DeliveryOrder openCompartment(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getFulfillmentMode() != FulfillmentMode.ROBOT) {
            throw ApiException.badRequest("门岗自提/人工配送订单无载物仓开仓环节");
        }
        requireStatus(order, DeliveryStatus.WAITING_PICKUP);
        order.setStatus(DeliveryStatus.COMPARTMENT_OPEN);
        orderRepository.save(order);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COMPARTMENT_OPENED, EventActor.USER, basePayload(order));
        return order;
    }

    /** 载物仓打不开：设备故障，触发客服介入 */
    @Transactional
    public DeliveryOrder reportCompartmentFault(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getStatus() != DeliveryStatus.WAITING_PICKUP
                && order.getStatus() != DeliveryStatus.COMPARTMENT_OPEN) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许上报仓门故障");
        }
        order.setStatus(DeliveryStatus.EXCEPTION);
        orderRepository.save(order);

        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.COMPARTMENT_OPEN_FAILED, EventActor.ROBOT, basePayload(order));
        csService.openCase(orderId, CaseType.COMPARTMENT_FAULT, Responsibility.ROBOT_VENDOR,
                "载物仓" + order.getCompartmentNo() + "号仓打不开，需现场处理或人工接力", null);
        return order;
    }

    /** 门岗自提：机器人不进楼，包裹送至小区门岗，通知用户凭手机号自取 */
    @Transactional
    public DeliveryOrder arriveAtGateForPickup(Long orderId, String gateName) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getFulfillmentMode() != FulfillmentMode.GATE_PICKUP) {
            throw ApiException.badRequest("该订单履约方式不是门岗自提: " + order.getFulfillmentMode());
        }
        if (order.getStatus() != DeliveryStatus.CREATED) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许门岗交接");
        }
        order.setStatus(DeliveryStatus.WAITING_PICKUP);
        order.setNotifiedAt(LocalDateTime.now());
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("gateName", gateName);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ARRIVED_AT_GATE, EventActor.ROBOT, payload);
        notificationService.notifyGatePickup(order, gateName);

        Map<String, Object> notified = basePayload(order);
        notified.put("channels", "SMS,MINI_PROGRAM");
        notified.put("pickupPoint", "GATE:" + gateName);
        notified.put("userPhone", order.getUserPhone());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.USER_NOTIFIED, EventActor.SYSTEM, "GATE_PICKUP", null, notified);
        return order;
    }

    /** 人工配送：夜间限行订单派发值班配送员/骑手配送上门 */
    @Transactional
    public DeliveryOrder dispatchManualDelivery(Long orderId, String courierName) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getFulfillmentMode() != FulfillmentMode.MANUAL_DELIVERY) {
            throw ApiException.badRequest("该订单履约方式不是人工配送: " + order.getFulfillmentMode());
        }
        if (order.getStatus() != DeliveryStatus.CREATED) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许派发人工配送");
        }
        order.setRelayCourier(courierName);
        order.setStatus(DeliveryStatus.ENTERING);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("courier", courierName);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.MANUAL_DISPATCHED, EventActor.OPS, "NIGHT_LIMIT", null, payload);
        return order;
    }

    /** 人工配送员到达用户门口并通知取件 */
    @Transactional
    public DeliveryOrder manualArriveDoor(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        if (order.getFulfillmentMode() != FulfillmentMode.MANUAL_DELIVERY) {
            throw ApiException.badRequest("该订单履约方式不是人工配送: " + order.getFulfillmentMode());
        }
        if (order.getStatus() != DeliveryStatus.ENTERING) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许人工配送到门");
        }
        order.setStatus(DeliveryStatus.WAITING_PICKUP);
        order.setNotifiedAt(LocalDateTime.now());
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("courier", order.getRelayCourier());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ARRIVED_AT_DOOR, EventActor.OPS, "MANUAL_DELIVERY", null, payload);
        notificationService.notifyArrival(order);

        Map<String, Object> notified = basePayload(order);
        notified.put("channels", "SMS,MINI_PROGRAM");
        notified.put("userPhone", order.getUserPhone());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.USER_NOTIFIED, EventActor.SYSTEM, "MANUAL_DELIVERY", null, notified);
        return order;
    }

    /** 用户签收 */
    @Transactional
    public DeliveryOrder sign(Long orderId, String signBy) {
        DeliveryOrder order = mustGet(orderId);
        // 门岗自提/人工配送无载物仓开仓环节，到门待取即可签收；机器人单必须先开仓
        boolean nonRobotReady = order.getFulfillmentMode() != FulfillmentMode.ROBOT
                && order.getStatus() == DeliveryStatus.WAITING_PICKUP;
        if (order.getStatus() != DeliveryStatus.COMPARTMENT_OPEN && !nonRobotReady) {
            throw ApiException.conflict(
                    "当前状态[" + order.getStatus() + "]不允许签收（机器人单需先打开载物仓）");
        }
        order.setStatus(DeliveryStatus.SIGNED);
        order.setSignedAt(LocalDateTime.now());
        orderRepository.save(order);
        releaseRobotAndCompartment(order);
        nightExitService.noteOrderFinished(order.getId());

        Map<String, Object> payload = basePayload(order);
        payload.put("signBy", signBy);
        payload.put("durationSeconds", Duration.between(order.getCreatedAt(), order.getSignedAt()).getSeconds());
        payload.put("coldChainTimedOut", order.isColdChainTimedOut());
        payload.put("onTime", order.getEstimatedDeliveryAt() == null
                || !order.getSignedAt().isAfter(order.getEstimatedDeliveryAt()));
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.SIGNED, EventActor.USER, payload);
        return order;
    }

    /** 开始退回（如用户超时未取、门禁长期被拒后的主动退回） */
    @Transactional
    public DeliveryOrder startReturn(Long orderId, String reason) {
        DeliveryOrder order = mustGet(orderId);
        if (!RETURN_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许退回");
        }
        order.setStatus(DeliveryStatus.RETURNING);
        order.setReturnReason(reason);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("reason", reason);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RETURN_STARTED, EventActor.ROBOT, payload);
        return order;
    }

    /** 确认退回完成，释放机器人与载物仓 */
    @Transactional
    public DeliveryOrder confirmReturn(Long orderId) {
        DeliveryOrder order = mustGet(orderId);
        requireStatus(order, DeliveryStatus.RETURNING);
        order.setStatus(DeliveryStatus.RETURNED);
        order.setReturnedAt(LocalDateTime.now());
        orderRepository.save(order);
        releaseRobotAndCompartment(order);
        nightExitService.noteOrderFinished(order.getId());

        Map<String, Object> payload = basePayload(order);
        payload.put("reason", order.getReturnReason());
        payload.put("durationSeconds", Duration.between(order.getCreatedAt(), order.getReturnedAt()).getSeconds());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.RETURNED, EventActor.ROBOT, payload);
        return order;
    }

    /** 人工接力：机器人无法继续配送时交由人工配送员完成 */
    @Transactional
    public DeliveryOrder manualRelay(Long orderId, String courierName) {
        DeliveryOrder order = mustGet(orderId);
        if (!RELAY_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许人工接力");
        }
        order.setRelayCourier(courierName);
        if (order.getStatus() == DeliveryStatus.EXCEPTION) {
            order.setStatus(DeliveryStatus.ENTERING);
        }
        orderRepository.save(order);
        releaseRobotAndCompartment(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("courier", courierName);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.MANUAL_RELAY, EventActor.OPS, payload);
        return order;
    }

    /** 送错楼栋后的改派：调整收货楼栋并重新进楼配送 */
    @Transactional
    public DeliveryOrder redeliver(Long orderId, Long newBuildingId) {
        DeliveryOrder order = mustGet(orderId);
        if (!REDELIVER_ALLOWED.contains(order.getStatus())) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许改派楼栋");
        }
        Building newBuilding = buildingRepository.findById(newBuildingId)
                .orElseThrow(() -> ApiException.notFound("楼栋不存在: " + newBuildingId));
        if (!newBuilding.getCommunity().getId().equals(order.getCommunity().getId())) {
            throw ApiException.badRequest("新楼栋不属于订单所在社区");
        }
        String fromBuilding = order.getBuilding().getName();
        order.setBuilding(newBuilding);
        order.setStatus(DeliveryStatus.ENTERING);
        orderRepository.save(order);

        Map<String, Object> payload = basePayload(order);
        payload.put("fromBuilding", fromBuilding);
        payload.put("toBuilding", newBuilding.getName());
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.ROUTE_ADJUSTED, EventActor.OPS, "WRONG_BUILDING", null, payload);
        return order;
    }

    /** 取消配送 */
    @Transactional
    public DeliveryOrder cancel(Long orderId, String reason) {
        DeliveryOrder order = mustGet(orderId);
        if (DeliveryStatus.TERMINAL.contains(order.getStatus())) {
            throw ApiException.conflict("订单已终结: " + order.getStatus());
        }
        order.setStatus(DeliveryStatus.CANCELLED);
        orderRepository.save(order);
        releaseRobotAndCompartment(order);
        nightExitService.noteOrderFinished(order.getId());

        Map<String, Object> payload = basePayload(order);
        payload.put("reason", reason);
        eventService.emit(order.getId(), order.getCommunity().getId(),
                EventType.DELIVERY_CANCELLED, EventActor.OPS, payload);
        return order;
    }

    /** 释放机器人与载物仓（若机器人无其他在途订单则置为空闲） */
    private void releaseRobotAndCompartment(DeliveryOrder order) {
        Robot robot = order.getRobot();
        if (robot == null) {
            return;
        }
        if (order.getCompartmentNo() != null) {
            compartmentRepository.findByRobotIdAndCompartmentNo(robot.getId(), order.getCompartmentNo())
                    .ifPresent(c -> {
                        if (c.getStatus() == CompartmentStatus.OCCUPIED) {
                            c.setStatus(CompartmentStatus.IDLE);
                            compartmentRepository.save(c);
                        }
                    });
        }
        if (robot.getStatus() == RobotStatus.BUSY) {
            robot.setStatus(RobotStatus.IDLE);
            robotRepository.save(robot);
        }
    }

    private Map<String, Object> basePayload(DeliveryOrder order) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", order.getOrderType().name());
        payload.put("building", order.getBuilding().getName());
        payload.put("roomNo", order.getRoomNo());
        payload.put("robotCode", order.getRobot() == null ? null : order.getRobot().getCode());
        payload.put("compartmentNo", order.getCompartmentNo());
        if (order.getRelayCourier() != null) {
            payload.put("relayCourier", order.getRelayCourier());
        }
        return payload;
    }

    private void requireStatus(DeliveryOrder order, DeliveryStatus expected) {
        if (order.getStatus() != expected) {
            throw ApiException.conflict("当前状态[" + order.getStatus() + "]不允许该操作（需要状态: " + expected + "）");
        }
    }

    static String modeCn(FulfillmentMode mode) {
        return switch (mode) {
            case ROBOT -> "机器人进楼配送";
            case GATE_PICKUP -> "门岗自提";
            case MANUAL_DELIVERY -> "人工配送";
        };
    }

    private DeliveryOrder mustGet(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + orderId));
    }
}
