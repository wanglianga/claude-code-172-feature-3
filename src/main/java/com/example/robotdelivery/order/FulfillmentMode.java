package com.example.robotdelivery.order;

/**
 * 订单履约方式：默认机器人进楼配送；预计送达时间落入物业夜间限行时段时，
 * 接单前校验并降级为门岗自提或人工配送。
 */
public enum FulfillmentMode {
    ROBOT,           // 机器人进楼配送（默认）
    GATE_PICKUP,     // 门岗自提：机器人送至小区门岗，用户到门岗取件
    MANUAL_DELIVERY  // 人工配送：转人工配送员完成
}
