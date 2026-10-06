package com.example.robotdelivery.night;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NightMedicineExemptionRepository
        extends JpaRepository<NightMedicineExemption, Long> {

    /** 某时刻时间窗覆盖的放行（调用方再按订单类型/楼栋过滤） */
    List<NightMedicineExemption>
            findByCommunityIdAndEnabledTrueAndValidFromLessThanEqualAndValidToGreaterThanEqual(
                    Long communityId, LocalDateTime atFrom, LocalDateTime atTo);

    List<NightMedicineExemption> findByCommunityIdOrderByIdDesc(Long communityId);
}
