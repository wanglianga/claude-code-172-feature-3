package com.example.robotdelivery.community;

import com.example.robotdelivery.auth.RequireRole;
import com.example.robotdelivery.auth.Role;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.night.NightCurfewService;
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
    private final NightCurfewService nightCurfewService;

    public CommunityController(CommunityRepository communityRepository,
                               BuildingRepository buildingRepository,
                               DeliveryEventService eventService,
                               NightCurfewService nightCurfewService) {
        this.communityRepository = communityRepository;
        this.buildingRepository = buildingRepository;
        this.eventService = eventService;
        this.nightCurfewService = nightCurfewService;
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
            description = "变更后立即影响该小区后续接单策略；若当前已处于限行时段，在楼机器人被要求尽快完成并退出")
    @RequireRole({Role.PROPERTY, Role.ADMIN})
    @PutMapping("/{id}/night-rule")
    @Transactional
    public Community updateNightRule(@PathVariable Long id, @Valid @RequestBody NightRuleRequest request) {
        Community community = communityRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("社区不存在: " + id));
        community.setNightRestricted(request.nightRestricted());
        community.setNightStart(request.nightStart());
        community.setNightEnd(request.nightEnd());
        communityRepository.save(community);

        // 规则变更即时生效：若当前已处于限行时段，立即要求在楼机器人尽快完成并退出
        int evacuationMarked = nightCurfewService.markEvacuationForCommunity(community);

        Map<String, Object> payload = new HashMap<>();
        payload.put("communityName", community.getName());
        payload.put("nightRestricted", community.isNightRestricted());
        payload.put("nightStart", community.getNightStart());
        payload.put("nightEnd", community.getNightEnd());
        payload.put("evacuationMarked", evacuationMarked);
        eventService.emit(null, community.getId(), EventType.RULE_UPDATED, EventActor.PROPERTY, payload);
        return community;
    }
}
