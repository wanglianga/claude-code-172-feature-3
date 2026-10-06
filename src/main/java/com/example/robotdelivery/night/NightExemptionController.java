package com.example.robotdelivery.night;

import com.example.robotdelivery.auth.CurrentUserHolder;
import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import com.example.robotdelivery.order.OrderType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "物业夜间限行", description = "夜间限行规则、药品临时放行（有效期+适用楼栋）、在楼机器人撤离计划")
@RestController
@RequestMapping("/api/night/exemptions")
public class NightExemptionController {

    private final NightExemptionService exemptionService;

    public NightExemptionController(NightExemptionService exemptionService) {
        this.exemptionService = exemptionService;
    }

    public record CreateExemptionRequest(
            @NotNull Long communityId,
            @NotNull OrderType orderType,
            Long buildingId,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validFrom,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validTo,
            String reason) {
    }

    @Operation(summary = "物业临时放行夜间药品订单",
            description = "设置有效期(validFrom~validTo)与适用楼栋(buildingId 为空=全小区楼栋)，立即影响该小区后续夜间接单/进楼")
    @RequireRole({Role.PROPERTY, Role.ADMIN})
    @PostMapping
    public java.util.Map<String, Object> create(@Valid @RequestBody CreateExemptionRequest request) {
        return exemptionService.toView(exemptionService.create(request.communityId(), request.orderType(),
                request.buildingId(), request.validFrom(), request.validTo(), request.reason(),
                CurrentUserHolder.get().username()));
    }

    public record UpdateExemptionRequest(
            Long buildingId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime validTo,
            Boolean enabled) {
    }

    @Operation(summary = "修改药品放行（有效期/适用楼栋/停用）")
    @RequireRole({Role.PROPERTY, Role.ADMIN})
    @PutMapping("/{id}")
    public java.util.Map<String, Object> update(@PathVariable Long id,
                                                @RequestBody UpdateExemptionRequest request) {
        return exemptionService.toView(exemptionService.update(id, request.validFrom(), request.validTo(),
                request.buildingId(), request.enabled()));
    }

    @Operation(summary = "药品放行列表（activeOnly=true 仅当前有效）")
    @RequireRole({Role.PROPERTY, Role.OPS, Role.ADMIN})
    @GetMapping
    public List<java.util.Map<String, Object>> list(@RequestParam(required = false) Long communityId,
                                                    @RequestParam(required = false) Boolean activeOnly) {
        return exemptionService.listViews(communityId, activeOnly);
    }
}
