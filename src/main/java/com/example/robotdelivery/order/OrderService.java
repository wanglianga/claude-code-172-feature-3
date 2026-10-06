package com.example.robotdelivery.order;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.BuildingRepository;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.community.CommunityRepository;
import com.example.robotdelivery.config.ApiException;
import com.example.robotdelivery.config.DeliveryProperties;
import com.example.robotdelivery.event.DeliveryEventService;
import com.example.robotdelivery.event.EventActor;
import com.example.robotdelivery.event.EventType;
import com.example.robotdelivery.night.NightMedicineExemption;
import com.example.robotdelivery.night.NightPolicyService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private final DeliveryOrderRepository orderRepository;
    private final CommunityRepository communityRepository;
    private final BuildingRepository buildingRepository;
    private final DeliveryEventService eventService;
    private final DeliveryProperties properties;
    private final NightPolicyService nightPolicyService;

    public OrderService(DeliveryOrderRepository orderRepository,
                        CommunityRepository communityRepository,
                        BuildingRepository buildingRepository,
                        DeliveryEventService eventService,
                        DeliveryProperties properties,
                        NightPolicyService nightPolicyService) {
        this.orderRepository = orderRepository;
        this.communityRepository = communityRepository;
        this.buildingRepository = buildingRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.nightPolicyService = nightPolicyService;
    }

    @Transactional
    public DeliveryOrder create(OrderType orderType, Long communityId, Long buildingId,
                                String unitNo, String roomNo, boolean coldChain,
                                LocalDateTime estimatedDeliveryAt, String userName, String userPhone) {
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> ApiException.notFound("社区不存在: " + communityId));
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> ApiException.notFound("楼栋不存在: " + buildingId));
        if (!building.getCommunity().getId().equals(community.getId())) {
            throw ApiException.badRequest("楼栋不属于该社区: buildingId=" + buildingId);
        }

        // 接单前夜间限行校验：预计到达落入限行时段且无有效药品放行时降级履约方式
        NightPolicyService.PreAcceptResult nightCheck = nightPolicyService.evaluateOnAccept(
                orderType, community, building, estimatedDeliveryAt);

        DeliveryOrder order = new DeliveryOrder();
        order.setOrderNo("D" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        order.setOrderType(orderType);
        order.setCommunity(community);
        order.setBuilding(building);
        order.setUnitNo(unitNo);
        order.setRoomNo(roomNo);
        order.setColdChain(coldChain);
        order.setEstimatedDeliveryAt(estimatedDeliveryAt);
        order.setUserName(userName);
        order.setUserPhone(userPhone);
        order.setStatus(DeliveryStatus.CREATED);
        order.setFulfillmentMode(nightCheck.mode());
        order.setNightRechecked(true);
        if (coldChain) {
            order.setColdChainDeadline(LocalDateTime.now().plusSeconds(properties.getColdchainTimeoutSeconds()));
        }
        orderRepository.save(order);

        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", order.getOrderNo());
        payload.put("orderType", orderType.name());
        payload.put("coldChain", coldChain);
        payload.put("community", community.getName());
        payload.put("building", building.getName());
        payload.put("unitNo", unitNo);
        payload.put("roomNo", roomNo);
        payload.put("estimatedDeliveryAt", estimatedDeliveryAt == null ? null : estimatedDeliveryAt.toString());
        payload.put("coldChainDeadline",
                order.getColdChainDeadline() == null ? null : order.getColdChainDeadline().toString());
        payload.put("userName", userName);
        payload.put("userPhone", userPhone);
        payload.put("fulfillmentMode", nightCheck.mode().name());
        eventService.emit(order.getId(), community.getId(), EventType.ORDER_CREATED, EventActor.OPS, payload);

        if (nightCheck.nightBlocked()) {
            Map<String, Object> downgradePayload = new HashMap<>();
            downgradePayload.put("orderNo", order.getOrderNo());
            downgradePayload.put("orderType", orderType.name());
            downgradePayload.put("building", building.getName());
            downgradePayload.put("estimatedDeliveryAt",
                    estimatedDeliveryAt == null ? null : estimatedDeliveryAt.toString());
            downgradePayload.put("nightStart", community.getNightStart());
            downgradePayload.put("nightEnd", community.getNightEnd());
            downgradePayload.put("fulfillmentMode", nightCheck.mode().name());
            downgradePayload.put("reason", nightCheck.reason());
            eventService.emit(order.getId(), community.getId(), EventType.FULFILLMENT_DOWNGRADED,
                    EventActor.SYSTEM, "NIGHT_LIMIT", null, downgradePayload);
        } else if (nightCheck.exemption() != null) {
            NightMedicineExemption exemption = nightCheck.exemption();
            Map<String, Object> exemptionPayload = new HashMap<>();
            exemptionPayload.put("orderNo", order.getOrderNo());
            exemptionPayload.put("exemptionId", exemption.getId());
            exemptionPayload.put("orderType", exemption.getOrderType().name());
            exemptionPayload.put("scopeBuilding", exemption.getBuilding() == null
                    ? "全小区楼栋" : exemption.getBuilding().getName());
            exemptionPayload.put("validFrom", exemption.getValidFrom().toString());
            exemptionPayload.put("validTo", exemption.getValidTo().toString());
            eventService.emit(order.getId(), community.getId(), EventType.NIGHT_EXEMPTION_APPLIED,
                    EventActor.PROPERTY, "MEDICINE_EXEMPTION", null, exemptionPayload);
        }
        return order;
    }

    public List<DeliveryOrder> query(DeliveryStatus status, Long communityId, Long buildingId, OrderType orderType) {
        Specification<DeliveryOrder> spec = (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (communityId != null) {
                predicates.add(cb.equal(root.get("community").get("id"), communityId));
            }
            if (buildingId != null) {
                predicates.add(cb.equal(root.get("building").get("id"), buildingId));
            }
            if (orderType != null) {
                predicates.add(cb.equal(root.get("orderType"), orderType));
            }
            q.orderBy(cb.desc(root.get("id")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return orderRepository.findAll(spec);
    }

    public DeliveryOrder mustGet(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("配送订单不存在: " + id));
    }
}
