package com.example.robotdelivery.night;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NightExitPlanRepository extends JpaRepository<NightExitPlan, Long> {

    Optional<NightExitPlan> findByDeliveryId(Long deliveryId);

    List<NightExitPlan> findByCommunityIdOrderByExpectedExitAtAsc(Long communityId);

    List<NightExitPlan> findByCommunityIdAndStatusOrderByExpectedExitAtAsc(
            Long communityId, NightExitPlanStatus status);

    long countByCommunityIdAndStatus(Long communityId, NightExitPlanStatus status);
}
