package com.example.robotdelivery.order;

/**
 * 履约方式：ROBOT 机器人进楼（默认）；预计到达落入物业夜间限行时段且无有效放行时，
 * 接单前自动降级为 GATE_PICKUP（门岗自提）或 MANUAL_DELIVERY（人工配送）。
 */
public enum FulfillmentMode {
    ROBOT,          // 机器人进楼配送
    GATE_PICKUP,    // 门岗自提（机器人不进楼，送至小区门岗交接）
    MANUAL_DELIVERY // 人工配送（夜间值班人员/骑手配送上门）
}
