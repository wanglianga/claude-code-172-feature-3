package com.example.robotdelivery.night;

import com.example.robotdelivery.auth.CurrentUserHolder;
import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import com.example.robotdelivery.order.OrderType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "物业夜间限行", description = "夜间限行接单降级、在楼机器人退出跟踪、药品订单临时放行")
@RestController
@RequestMapping("/api")
public class NightCurfewController {

    private final NightCurfewService nightCurfewService;

    public NightCurfewController(NightCurfewService nightCurfewService) {
        this.nightCurfewService = nightCurfewService;
    }

    public record ExemptionRequest(@NotNull OrderType orderType,
                                   @NotNull Long buildingId,
                                   @NotNull LocalDateTime validFrom,
                                   @NotNull LocalDateTime validUntil,
                                   String reason) {
    }

    @Operation(summary = "物业创建临时放行",
            description = "指定某类订单（如药品 MEDICINE）在有效期内、指定楼栋不受夜间限行约束")
    @RequireRole({Role.PROPERTY, Role.ADMIN})
    @PostMapping("/communities/{id}/night-exemptions")
    public NightExemption createExemption(@PathVariable Long id,
                                          @Valid @RequestBody ExemptionRequest request) {
        return nightCurfewService.createExemption(id, request.buildingId(), request.orderType(),
                request.validFrom(), request.validUntil(), request.reason(),
                CurrentUserHolder.get().username());
    }

    @Operation(summary = "临时放行列表")
    @RequireRole({Role.PROPERTY, Role.OPS, Role.ADMIN})
    @GetMapping("/communities/{id}/night-exemptions")
    public List<NightExemption> listExemptions(@PathVariable Long id) {
        return nightCurfewService.listExemptions(id);
    }

    @Operation(summary = "撤销临时放行", description = "撤销后该社区后续接单恢复夜间限行校验")
    @RequireRole({Role.PROPERTY, Role.ADMIN})
    @PostMapping("/night-exemptions/{id}/revoke")
    public NightExemption revokeExemption(@PathVariable Long id) {
        return nightCurfewService.revokeExemption(id);
    }

    @Operation(summary = "物业端夜间限行视图",
            description = "在楼机器人订单的要求结束时间、实际退出时间与行进路线")
    @RequireRole({Role.PROPERTY, Role.ADMIN})
    @GetMapping("/communities/{id}/night-curfew/robots")
    public List<Map<String, Object>> curfewView(@PathVariable Long id) {
        return nightCurfewService.curfewView(id);
    }
}
