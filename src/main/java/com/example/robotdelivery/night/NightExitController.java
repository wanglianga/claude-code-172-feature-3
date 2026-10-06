package com.example.robotdelivery.night;

import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "物业夜间撤离", description = "限行开始后在楼机器人的催办撤离计划：结束时间与撤离路线")
@RestController
@RequestMapping("/api/night/exit-plans")
public class NightExitController {

    private final NightExitService nightExitService;

    public NightExitController(NightExitService nightExitService) {
        this.nightExitService = nightExitService;
    }

    @Operation(summary = "物业端查看夜间撤离计划（结束时间/路线/实际撤离时间）",
            description = "可按小区与状态过滤：NOTIFIED 催办中、EXITED 已撤离")
    @RequireRole({Role.PROPERTY, Role.OPS, Role.ADMIN})
    @GetMapping
    public List<java.util.Map<String, Object>> list(@RequestParam Long communityId,
                                                    @RequestParam(required = false) NightExitPlanStatus status) {
        return nightExitService.listPlanViews(communityId, status);
    }

    @Operation(summary = "手动触发一次夜间催办巡检",
            description = "规则变更后立即催办该小区在楼机器人完成订单并撤离，返回新生成的撤离计划数")
    @RequireRole({Role.PROPERTY, Role.ADMIN})
    @PostMapping("/expedite")
    public java.util.Map<String, Object> expedite() {
        int planned = nightExitService.expediteInsideRobots();
        return java.util.Map.of("newExitPlans", planned);
    }

    @Operation(summary = "确认机器人完成当前订单并已按路线撤离楼栋")
    @RequireRole({Role.OPS, Role.PROPERTY, Role.ADMIN})
    @PostMapping("/{id}/confirm-exit")
    public java.util.Map<String, Object> confirmExit(@PathVariable Long id) {
        return nightExitService.planView(nightExitService.confirmExit(id));
    }
}
