package com.example.robotdelivery.night;

import com.example.robotdelivery.order.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NightExemptionRepository extends JpaRepository<NightExemption, Long> {

    List<NightExemption> findByCommunityIdOrderByIdDesc(Long communityId);

    /** 查找指定时刻生效中的临时放行（社区+楼栋+订单类型匹配，未撤销，在有效期内） */
    List<NightExemption> findByCommunityIdAndBuildingIdAndOrderTypeAndStatusAndValidFromLessThanEqualAndValidUntilGreaterThan(
            Long communityId, Long buildingId, OrderType orderType, ExemptionStatus status,
            LocalDateTime fromBound, LocalDateTime untilBound);
}
