package com.example.robotdelivery.order;

import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "配送订单", description = "外卖/药品/商超订单交给配送机器人的配送单")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public record CreateOrderRequest(
            @NotNull OrderType orderType,
            @NotNull Long communityId,
            @NotNull Long buildingId,
            String unitNo,
            String roomNo,
            boolean coldChain,
            LocalDateTime estimatedDeliveryAt,
            @NotBlank String userName,
            @NotBlank String userPhone,
            FulfillmentMode nightFallback) {
    }

    @Operation(summary = "创建配送订单",
            description = "记录订单类型、收货楼栋、是否冷藏、预计送达与用户联系方式；"
                    + "预计送达时间落入物业夜间限行时段时，接单前校验并自动改为门岗自提(GATE_PICKUP)"
                    + "或人工配送(MANUAL_DELIVERY)，可用 nightFallback 指定降级方式（默认门岗自提）；"
                    + "命中有效临时放行的订单仍机器人进楼")
    @RequireRole({Role.OPS, Role.ADMIN})
    @PostMapping
    public DeliveryOrder create(@Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(request.orderType(), request.communityId(), request.buildingId(),
                request.unitNo(), request.roomNo(), request.coldChain(),
                request.estimatedDeliveryAt(), request.userName(), request.userPhone(),
                request.nightFallback());
    }

    @Operation(summary = "订单查询", description = "按状态/社区/楼栋/订单类型过滤")
    @GetMapping
    public List<DeliveryOrder> query(@RequestParam(required = false) DeliveryStatus status,
                                     @RequestParam(required = false) Long communityId,
                                     @RequestParam(required = false) Long buildingId,
                                     @RequestParam(required = false) OrderType orderType) {
        return orderService.query(status, communityId, buildingId, orderType);
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{id}")
    public DeliveryOrder detail(@PathVariable Long id) {
        return orderService.mustGet(id);
    }
}
