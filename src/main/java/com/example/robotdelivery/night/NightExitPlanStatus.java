package com.example.robotdelivery.night;

/**
 * 夜间撤离计划状态：
 * NOTIFIED 已催办，机器人需尽快完成当前订单并撤离；
 * EXITED   机器人已按路线离开楼栋。
 */
public enum NightExitPlanStatus {
    NOTIFIED,
    EXITED
}
