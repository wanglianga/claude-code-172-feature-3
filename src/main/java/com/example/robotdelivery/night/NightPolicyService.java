package com.example.robotdelivery.night;

import com.example.robotdelivery.community.Building;
import com.example.robotdelivery.community.Community;
import com.example.robotdelivery.order.FulfillmentMode;
import com.example.robotdelivery.order.OrderType;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * 物业夜间限行规则判定（无副作用的纯领域服务）：
 * 1. 某时刻是否处于小区夜间限行时段（支持跨零点，如 22:00-07:00）；
 * 2. 药品订单在某时刻/楼栋是否命中物业临时放行（有效期 + 适用楼栋）；
 * 3. 接单前按预计到达时间校验：落入限行时段且无放行时，给出降级履约方式
 *    （门岗自提 / 人工配送）。
 */
@Service
public class NightPolicyService {

    private final NightMedicineExemptionRepository exemptionRepository;

    public NightPolicyService(NightMedicineExemptionRepository exemptionRepository) {
        this.exemptionRepository = exemptionRepository;
    }

    /** 判断给定时刻是否处于夜间限制时段（支持跨零点，如 22:00-07:00；起止相同视为全天） */
    public boolean isNightAt(Community community, LocalDateTime at) {
        if (!community.isNightRestricted()) {
            return false;
        }
        LocalTime t = at.toLocalTime();
        LocalTime start = LocalTime.parse(community.getNightStart());
        LocalTime end = LocalTime.parse(community.getNightEnd());
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        return !t.isBefore(start) || t.isBefore(end);
    }

    public boolean isNightNow(Community community) {
        return isNightAt(community, LocalDateTime.now());
    }

    /**
     * 查询某药品订单在指定时刻、指定楼栋是否有有效放行。
     * 优先返回指定楼栋的放行，其次全小区放行；同为全小区时取最晚到期者。
     */
    public NightMedicineExemption findExemption(Community community, OrderType orderType,
                                               Building building, LocalDateTime at) {
        List<NightMedicineExemption> active = exemptionRepository
                .findByCommunityIdAndEnabledTrueAndValidFromLessThanEqualAndValidToGreaterThanEqual(
                        community.getId(), at, at);
        return active.stream()
                .filter(e -> e.getOrderType() == orderType)
                .filter(e -> e.getBuilding() == null
                        || (building != null && e.getBuilding().getId().equals(building.getId())))
                .min(Comparator
                        // 楼栋级放行优先于全小区放行
                        .comparing((NightMedicineExemption e) -> e.getBuilding() == null ? 1 : 0)
                        // 同类放行取最晚到期
                        .thenComparing(NightMedicineExemption::getValidTo, Comparator.reverseOrder()))
                .orElse(null);
    }

    /** 接单前校验结果 */
    public record PreAcceptResult(boolean nightBlocked, FulfillmentMode mode,
                                 String reason, NightMedicineExemption exemption) {

        static PreAcceptResult allowRobot() {
            return new PreAcceptResult(false, FulfillmentMode.ROBOT, null, null);
        }

        static PreAcceptResult allowByExemption(NightMedicineExemption exemption) {
            return new PreAcceptResult(false, FulfillmentMode.ROBOT, null, exemption);
        }

        static PreAcceptResult downgrade(FulfillmentMode mode, String reason) {
            return new PreAcceptResult(true, mode, reason, null);
        }
    }

    /**
     * 接单前校验预计到达时间：
     * 落入限行时段且无有效药品放行 -> 夜间禁行，按订单类型降级为门岗自提/人工配送。
     * 未填写预计到达时间时按当前时间保守判定。
     */
    public PreAcceptResult evaluateOnAccept(OrderType orderType, Community community, Building building,
                                            LocalDateTime estimatedDeliveryAt) {
        if (!community.isNightRestricted()) {
            return PreAcceptResult.allowRobot();
        }
        LocalDateTime at = estimatedDeliveryAt != null ? estimatedDeliveryAt : LocalDateTime.now();
        if (!isNightAt(community, at)) {
            return PreAcceptResult.allowRobot();
        }
        NightMedicineExemption exemption = findExemption(community, orderType, building, at);
        if (exemption != null) {
            return PreAcceptResult.allowByExemption(exemption);
        }
        // 药品（急用、常需送上门）默认改人工配送；外卖/商超默认改门岗自提
        FulfillmentMode fallback = orderType == OrderType.MEDICINE
                ? FulfillmentMode.MANUAL_DELIVERY : FulfillmentMode.GATE_PICKUP;
        String modeCn = fallback == FulfillmentMode.MANUAL_DELIVERY ? "人工配送" : "门岗自提";
        String reason = String.format(
                "预计到达%s处于物业夜间限行时段 %s-%s，且无有效药品放行，按接单策略改为%s",
                at, community.getNightStart(), community.getNightEnd(), modeCn);
        return PreAcceptResult.downgrade(fallback, reason);
    }
}
