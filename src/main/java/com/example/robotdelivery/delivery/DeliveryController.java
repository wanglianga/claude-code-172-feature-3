package com.example.robotdelivery.delivery;

import com.example.robotdelivery.access.AccessRequest;
import com.example.robotdelivery.access.AccessService;
import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import com.example.robotdelivery.elevator.ElevatorRequest;
import com.example.robotdelivery.elevator.ElevatorService;
import com.example.robotdelivery.event.DeliveryEvent;
import com.example.robotdelivery.event.DeliveryEventRepository;
import com.example.robotdelivery.night.NightCurfewService;
import com.example.robotdelivery.order.DeliveryOrder;
import com.example.robotdelivery.order.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "配送流程", description = "配送主流程编排：分配机器人/门禁/电梯/避障/到门/签收/退回/人工接力")
@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final AccessService accessService;
    private final ElevatorService elevatorService;
    private final OrderService orderService;
    private final DeliveryEventRepository eventRepository;
    private final NightCurfewService nightCurfewService;

    public DeliveryController(DeliveryService deliveryService,
                              AccessService accessService,
                              ElevatorService elevatorService,
                              OrderService orderService,
                              DeliveryEventRepository eventRepository,
                              NightCurfewService nightCurfewService) {
        this.deliveryService = deliveryService;
        this.accessService = accessService;
        this.elevatorService = elevatorService;
        this.orderService = orderService;
        this.eventRepository = eventRepository;
        this.nightCurfewService = nightCurfewService;
    }

    @Operation(summary = "配送单详情")
    @GetMapping("/{id}")
    public DeliveryOrder detail(@PathVariable Long id) {
        return orderService.mustGet(id);
    }

    @Operation(summary = "配送单统一事件时间线",
            description = "门禁开通/路线调整/电梯请求/避障停留/签收/退回/客服介入等全部事件")
    @GetMapping("/{id}/events")
    public List<DeliveryEvent> events(@PathVariable Long id) {
        return eventRepository.findByDeliveryIdOrderByOccurredAtAsc(id);
    }

    public record AssignRequest(@NotNull Long robotId) {
    }

    @Operation(summary = "分配机器人与载物仓")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/assign")
    public DeliveryOrder assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return deliveryService.assignRobot(id, request.robotId());
    }

    public record ArriveGateRequest(@NotBlank String gateName) {
    }

    @Operation(summary = "机器人到达小区门口，申请物业门禁",
            description = "物业夜间限制时段将自动拒绝并触发客服介入")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/arrive-gate")
    public AccessRequest arriveGate(@PathVariable Long id, @Valid @RequestBody ArriveGateRequest request) {
        return accessService.requestAccess(id, request.gateName());
    }

    public record ElevatorRequestBody(@NotNull Integer fromFloor, @NotNull Integer toFloor) {
    }

    @Operation(summary = "进入楼栋后与电梯系统协同选择楼层")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/request-elevator")
    public ElevatorRequest requestElevator(@PathVariable Long id,
                                           @Valid @RequestBody ElevatorRequestBody request) {
        return elevatorService.request(id, request.fromFloor(), request.toFloor());
    }

    public record ObstacleRequest(@NotNull ObstacleType obstacleType,
                                  @NotNull Long waitSeconds,
                                  String location) {
    }

    @Operation(summary = "避障停留上报", description = "老人儿童挡路/行人/楼道施工等；施工会同时触发路线调整")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/obstacle")
    public DeliveryOrder obstacle(@PathVariable Long id, @Valid @RequestBody ObstacleRequest request) {
        return deliveryService.reportObstacle(id, request.obstacleType(), request.waitSeconds(), request.location());
    }

    @Operation(summary = "到达用户门口并通知取件（短信+小程序）")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/arrive-door")
    public DeliveryOrder arriveDoor(@PathVariable Long id) {
        return deliveryService.arriveDoor(id);
    }

    @Operation(summary = "用户打开载物仓")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/open-compartment")
    public DeliveryOrder openCompartment(@PathVariable Long id) {
        return deliveryService.openCompartment(id);
    }

    @Operation(summary = "载物仓打不开（设备故障上报，触发客服介入）")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/compartment-fault")
    public DeliveryOrder compartmentFault(@PathVariable Long id) {
        return deliveryService.reportCompartmentFault(id);
    }

    public record SignRequest(@NotBlank String signBy) {
    }

    @Operation(summary = "用户签收")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/sign")
    public DeliveryOrder sign(@PathVariable Long id, @Valid @RequestBody SignRequest request) {
        return deliveryService.sign(id, request.signBy());
    }

    public record ReturnRequest(@NotBlank String reason) {
    }

    @Operation(summary = "开始退回")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/start-return")
    public DeliveryOrder startReturn(@PathVariable Long id, @Valid @RequestBody ReturnRequest request) {
        return deliveryService.startReturn(id, request.reason());
    }

    @Operation(summary = "确认退回完成")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/confirm-return")
    public DeliveryOrder confirmReturn(@PathVariable Long id) {
        return deliveryService.confirmReturn(id);
    }

    public record RelayRequest(@NotBlank String courierName) {
    }

    @Operation(summary = "人工接力", description = "机器人电量不足/故障时交由人工配送员完成配送")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/manual-relay")
    public DeliveryOrder manualRelay(@PathVariable Long id, @Valid @RequestBody RelayRequest request) {
        return deliveryService.manualRelay(id, request.courierName());
    }

    public record RedeliverRequest(@NotNull Long buildingId) {
    }

    @Operation(summary = "送错楼栋后改派楼栋（路线调整）")
    @RequireRole({Role.OPS, Role.CS, Role.ADMIN})
    @PostMapping("/{id}/redeliver")
    public DeliveryOrder redeliver(@PathVariable Long id, @Valid @RequestBody RedeliverRequest request) {
        return deliveryService.redeliver(id, request.buildingId());
    }

    public record CancelRequest(@NotBlank String reason) {
    }

    @Operation(summary = "取消配送")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/cancel")
    public DeliveryOrder cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest request) {
        return deliveryService.cancel(id, request.reason());
    }

    @Operation(summary = "机器人退出楼栋上报",
            description = "夜间限行要求在楼机器人尽快完成当前订单并退出；退出后物业端可见实际退出时间")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping("/{id}/exit-building")
    public DeliveryOrder exitBuilding(@PathVariable Long id) {
        return nightCurfewService.exitBuilding(id);
    }
}
