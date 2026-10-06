package com.example.robotdelivery.community;

import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.night.NightExitService;
import com.example.robotdelivery.night.NightPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "社区与物业规则", description = "社区、楼栋与物业夜间运行限制规则")
@RestController
@RequestMapping("/api/communities")
public class CommunityController {

    private final CommunityRepository communityRepository;
    private final BuildingRepository buildingRepository;
    private final DeliveryEventService eventService;
    private final NightPolicyService nightPolicyService;
    private final NightExitService nightExitService;

    public CommunityController(CommunityRepository communityRepository,
                               BuildingRepository buildingRepository,
                               DeliveryEventService eventService,
                               NightPolicyService nightPolicyService,
                               NightExitService nightExitService) {
        this.communityRepository = communityRepository;
        this.buildingRepository = buildingRepository;
        this.eventService = eventService;
        this.nightPolicyService = nightPolicyService;
        this.nightExitService = nightExitService;
    }

    @Operation(summary = "社区列表")
    @GetMapping
    public List<Community> list() {
        return communityRepository.findAll();
    }

    @Operation(summary = "社区楼栋列表")
    @GetMapping("/{id}/buildings")
    public List<Building> buildings(@PathVariable Long id) {
        return buildingRepository.findByCommunityId(id);
    }

    public record NightRuleRequest(
            @NotNull Boolean nightRestricted,
            @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "格式须为 HH:mm") String nightStart,
            @NotBlank @Pattern(regexp = "^\\d{2}:\\d{2}$", message = "格式须为 HH:mm") String nightEnd) {
    }

    @Operation(summary = "物业设置夜间运行限制规则",
            description = "规则变更立即影响该小区后续接单策略；若限行即刻生效，同步催办已在楼内的机器人完成订单并撤离")
    @RequireRole({Role.PROPERTY, Role.ADMIN})
    @PutMapping("/{id}/night-rule")
    @Transactional
    public Map<String, Object> updateNightRule(@PathVariable Long id,
                                               @Valid @RequestBody NightRuleRequest request) {
        Community community = communityRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("社区不存在: " + id));
        community.setNightRestricted(request.nightRestricted());
        community.setNightStart(request.nightStart());
        community.setNightEnd(request.nightEnd());
        communityRepository.save(community);

        Map<String, Object> payload = new HashMap<>();
        payload.put("communityId", community.getId());
        payload.put("communityName", community.getName());
        payload.put("nightRestricted", community.isNightRestricted());
        payload.put("nightStart", community.getNightStart());
        payload.put("nightEnd", community.getNightEnd());
        eventService.emit(null, community.getId(), EventType.RULE_UPDATED, EventActor.PROPERTY, payload);

        // 夜间限行规则变更后立即影响该小区：若此刻处于限行时段，催办已在楼内的机器人尽快完成并撤离
        int newExitPlans = 0;
        if (community.isNightRestricted() && nightPolicyService.isNightNow(community)) {
            newExitPlans = nightExitService.expediteForCommunity(id);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("id", community.getId());
        result.put("name", community.getName());
        result.put("nightRestricted", community.isNightRestricted());
        result.put("nightStart", community.getNightStart());
        result.put("nightEnd", community.getNightEnd());
        result.put("newExitPlans", newExitPlans);
        return result;
    }
}
