package com.example.robotdelivery.night;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.BuildingRepository;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.community.CommunityRepository;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.order.OrderType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物业夜间药品临时放行管理：
 * 放行带有效期（validFrom ~ validTo）与适用楼栋（buildingId 为空=全小区楼栋），
 * 变更立即影响该小区后续夜间接单策略（接单/进楼门禁判定实时读取）。
 */
@Service
public class NightExemptionService {

    private final NightMedicineExemptionRepository repository;
    private final CommunityRepository communityRepository;
    private final BuildingRepository buildingRepository;
    private final DeliveryEventService eventService;

    public NightExemptionService(NightMedicineExemptionRepository repository,
                                 CommunityRepository communityRepository,
                                 BuildingRepository buildingRepository,
                                 DeliveryEventService eventService) {
        this.repository = repository;
        this.communityRepository = communityRepository;
        this.buildingRepository = buildingRepository;
        this.eventService = eventService;
    }

    @Transactional
    public NightMedicineExemption create(Long communityId, OrderType orderType, Long buildingId,
                                         LocalDateTime validFrom, LocalDateTime validTo,
                                         String reason, String operator) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> ApiException.notFound("社区不存在: " + communityId));
        if (orderType != OrderType.MEDICINE) {
            throw ApiException.badRequest("夜间临时放行当前仅适用于药品(MEDICINE)订单");
        }
        if (validFrom == null || validTo == null || !validTo.isAfter(validFrom)) {
            throw ApiException.badRequest("有效期不合法：validTo 必须晚于 validFrom");
        }
        Building building = null;
        if (buildingId != null) {
            building = buildingRepository.findById(buildingId)
                    .orElseThrow(() -> ApiException.notFound("楼栋不存在: " + buildingId));
            if (!building.getCommunity().getId().equals(communityId)) {
                throw ApiException.badRequest("楼栋不属于该社区");
            }
        }
        // 用已加载的受管社区，避免 building 关联的 LAZY 代理污染一级缓存
        if (building != null) {
            building.setCommunity(community);
        }

        NightMedicineExemption exemption = new NightMedicineExemption();
        exemption.setCommunity(community);
        exemption.setOrderType(orderType);
        exemption.setBuilding(building);
        exemption.setValidFrom(validFrom);
        exemption.setValidTo(validTo);
        exemption.setReason(reason);
        exemption.setEnabled(true);
        exemption.setCreatedBy(operator);
        repository.save(exemption);

        Map<String, Object> payload = payload(exemption);
        payload.put("action", "CREATED");
        payload.put("reason", reason);
        eventService.emit(null, communityId, EventType.RULE_UPDATED, EventActor.PROPERTY,
                "MEDICINE_EXEMPTION", null, payload);
        return exemption;
    }

    @Transactional
    public NightMedicineExemption update(Long id, LocalDateTime validFrom, LocalDateTime validTo,
                                         Long buildingId, Boolean enabled) {
        NightMedicineExemption exemption = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("药品放行不存在: " + id));
        if (buildingId != null) {
            Building building = buildingRepository.findById(buildingId)
                    .orElseThrow(() -> ApiException.notFound("楼栋不存在: " + buildingId));
            if (!building.getCommunity().getId().equals(exemption.getCommunity().getId())) {
                throw ApiException.badRequest("楼栋不属于该社区");
            }
            exemption.setBuilding(building);
        }
        if (validFrom != null) {
            exemption.setValidFrom(validFrom);
        }
        if (validTo != null) {
            exemption.setValidTo(validTo);
        }
        if (!exemption.getValidTo().isAfter(exemption.getValidFrom())) {
            throw ApiException.badRequest("有效期不合法：validTo 必须晚于 validFrom");
        }
        if (enabled != null) {
            exemption.setEnabled(enabled);
        }
        repository.save(exemption);

        Map<String, Object> payload = payload(exemption);
        payload.put("action", "UPDATED");
        eventService.emit(null, exemption.getCommunity().getId(), EventType.RULE_UPDATED,
                EventActor.PROPERTY, "MEDICINE_EXEMPTION", null, payload);
        return exemption;
    }

    public List<NightMedicineExemption> list(Long communityId, Boolean activeOnly) {
        List<NightMedicineExemption> all = communityId == null
                ? repository.findAll() : repository.findByCommunityIdOrderByIdDesc(communityId);
        if (Boolean.TRUE.equals(activeOnly)) {
            LocalDateTime now = LocalDateTime.now();
            return all.stream()
                    .filter(e -> e.isEnabled() && !now.isBefore(e.getValidFrom()) && now.isBefore(e.getValidTo()))
                    .toList();
        }
        return all;
    }

    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> listViews(Long communityId, Boolean activeOnly) {
        return list(communityId, activeOnly).stream().map(this::toView).toList();
    }

    public java.util.Map<String, Object> toView(NightMedicineExemption e) {
        java.util.Map<String, Object> v = new java.util.LinkedHashMap<>();
        v.put("id", e.getId());
        v.put("communityId", e.getCommunity().getId());
        v.put("community", e.getCommunity().getName());
        v.put("orderType", e.getOrderType().name());
        v.put("buildingId", e.getBuilding() == null ? null : e.getBuilding().getId());
        v.put("scopeBuilding", e.getBuilding() == null ? "全小区楼栋" : e.getBuilding().getName());
        v.put("validFrom", e.getValidFrom());
        v.put("validTo", e.getValidTo());
        v.put("reason", e.getReason());
        v.put("enabled", e.isEnabled());
        v.put("createdBy", e.getCreatedBy());
        return v;
    }

    private Map<String, Object> payload(NightMedicineExemption e) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("exemptionId", e.getId());
        payload.put("community", e.getCommunity().getName());
        payload.put("orderType", e.getOrderType().name());
        payload.put("scopeBuilding", e.getBuilding() == null ? "全小区楼栋" : e.getBuilding().getName());
        payload.put("validFrom", e.getValidFrom().toString());
        payload.put("validTo", e.getValidTo().toString());
        payload.put("enabled", e.isEnabled());
        return payload;
    }
}
