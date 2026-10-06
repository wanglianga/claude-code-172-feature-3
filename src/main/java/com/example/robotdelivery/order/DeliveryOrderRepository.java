package com.example.robotdelivery.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeliveryOrderRepository
        extends JpaRepository<DeliveryOrder, Long>, JpaSpecificationExecutor<DeliveryOrder> {

    Optional<DeliveryOrder> findByOrderNo(String orderNo);

    Optional<DeliveryOrder> findFirstByRobot_IdAndStatusInOrderByIdDesc(
            Long robotId, Collection<DeliveryStatus> statuses);

    List<DeliveryOrder> findByStatusInAndNotifiedAtBefore(
            Collection<DeliveryStatus> statuses, LocalDateTime notifiedBefore);

    List<DeliveryOrder> findByColdChainTrueAndColdChainTimedOutFalseAndStatusNotInAndColdChainDeadlineBefore(
            Collection<DeliveryStatus> excludedStatuses, LocalDateTime deadlineBefore);

    long countByCommunityId(Long communityId);

    List<DeliveryOrder> findByCommunityIdAndStatusIn(Long communityId, Collection<DeliveryStatus> statuses);

    List<DeliveryOrder> findByCommunityIdAndNightExitRequiredTrue(Long communityId);
}
