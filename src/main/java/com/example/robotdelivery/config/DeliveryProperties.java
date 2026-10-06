package com.example.robotdelivery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "delivery")
public class DeliveryProperties {

    /** 用户超时不取件秒数 */
    private long pickupTimeoutSeconds = 600;

    /** 冷藏订单履约时限秒数 */
    private long coldchainTimeoutSeconds = 2700;

    /** 电梯请求超时秒数 */
    private long elevatorTimeoutSeconds = 180;

    /** 低电量阈值（百分比） */
    private int lowBatteryThreshold = 20;

    /** 超时巡检间隔毫秒 */
    private long schedulerIntervalMs = 10000;

    /** 是否开启超时巡检 */
    private boolean schedulerEnabled = true;

    /** 夜间限行：要求在楼机器人退出楼栋的宽限分钟数 */
    private long nightExitGraceMinutes = 15;
}
