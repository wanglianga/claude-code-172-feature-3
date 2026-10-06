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
import com.example.robotdelivery.night.NightCurfewService;
import com.example.robotdelivery.night.NightExemption;
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
    private final NightCurfewService nightCurfewService;

    public OrderService(DeliveryOrderRepository orderRepository,
                        CommunityRepository communityRepository,
                        BuildingRepository buildingRepository,
                        DeliveryEventService eventService,
                        DeliveryProperties properties,
                        NightCurfewService nightCurfewService) {
        this.orderRepository = orderRepository;
        this.communityRepository = communityRepository;
        this.buildingRepository = buildingRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.nightCurfewService = nightCurfewService;
    }

    @Transactional
    public DeliveryOrder create(OrderType orderType, Long communityId, Long buildingId,
                                String unitNo, String roomNo, boolean coldChain,
                                LocalDateTime estimatedDeliveryAt, String userName, String userPhone,
                                FulfillmentMode nightFallback) {
        if (nightFallback == FulfillmentMode.ROBOT) {
            throw ApiException.badRequest("nightFallback 仅支持 GATE_PICKUP(门岗自提) / MANUAL_DELIVERY(人工配送)");
        }
        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> ApiException.notFound("社区不存在: " + communityId));
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> ApiException.notFound("楼栋不存在: " + buildingId));
        if (!building.getCommunity().getId().equals(community.getId())) {
            throw ApiException.badRequest("楼栋不属于该社区: buildingId=" + buildingId);
        }

        // 接单前校验：预计送达时间落入物业夜间限行时段时，改为门岗自提或人工配送；
        // 命中有效临时放行（如药品单，按有效期+适用楼栋）的订单不受限行约束。
        FulfillmentMode fulfillmentMode = FulfillmentMode.ROBOT;
        NightExemption exemption = null;
        if (estimatedDeliveryAt != null
                && nightCurfewService.isNightRestrictedAt(community, estimatedDeliveryAt)) {
            exemption = nightCurfewService
                    .findActiveExemption(community.getId(), building.getId(), orderType, LocalDateTime.now())
                    .orElse(null);
            if (exemption == null) {
                fulfillmentMode = nightFallback == null ? FulfillmentMode.GATE_PICKUP : nightFallback;
            }
        }

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
        order.setFulfillmentMode(fulfillmentMode);
        order.setNightExemptionId(exemption == null ? null : exemption.getId());
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
        payload.put("fulfillmentMode", fulfillmentMode.name());
        payload.put("nightExemptionId", exemption == null ? null : exemption.getId());
        eventService.emit(order.getId(), community.getId(), EventType.ORDER_CREATED, EventActor.OPS, payload);

        if (fulfillmentMode != FulfillmentMode.ROBOT) {
            Map<String, Object> fallbackPayload = new HashMap<>();
            fallbackPayload.put("orderNo", order.getOrderNo());
            fallbackPayload.put("orderType", orderType.name());
            fallbackPayload.put("community", community.getName());
            fallbackPayload.put("building", building.getName());
            fallbackPayload.put("fulfillmentMode", fulfillmentMode.name());
            fallbackPayload.put("estimatedDeliveryAt", estimatedDeliveryAt.toString());
            fallbackPayload.put("nightStart", community.getNightStart());
            fallbackPayload.put("nightEnd", community.getNightEnd());
            fallbackPayload.put("detail", "预计送达时间落入物业夜间限行时段，"
                    + (fulfillmentMode == FulfillmentMode.GATE_PICKUP ? "改为门岗自提" : "改为人工配送"));
            eventService.emit(order.getId(), community.getId(),
                    EventType.NIGHT_FALLBACK_APPLIED, EventActor.SYSTEM, "NIGHT_LIMIT", null, fallbackPayload);
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
