package com.example.robotdelivery.night;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 夜间限行巡检：限行时段开始时若机器人已在楼内，催办其尽快完成当前订单并按路线撤离。
 */
@Slf4j
@Component
public class NightScheduler {

    private final NightExitService nightExitService;

    public NightScheduler(NightExitService nightExitService) {
        this.nightExitService = nightExitService;
    }

    @Scheduled(fixedDelayString = "${delivery.scheduler-interval-ms:10000}",
            initialDelayString = "${delivery.scheduler-interval-ms:10000}")
    public void checkNightExit() {
        try {
            int planned = nightExitService.expediteInsideRobots();
            if (planned > 0) {
                log.info("夜间限行巡检：新催办 {} 台在楼机器人完成订单并撤离", planned);
            }
        } catch (Exception e) {
            log.error("夜间限行巡检执行失败", e);
        }
    }
}
