package com.example.robotdelivery.night;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 夜间限行时段判断工具（支持跨零点，如 22:00-07:00）。
 */
public final class NightWindow {

    private NightWindow() {
    }

    /** 判断某个时刻是否落入夜间限行时段 */
    public static boolean contains(String nightStart, String nightEnd, LocalTime time) {
        LocalTime start = LocalTime.parse(nightStart);
        LocalTime end = LocalTime.parse(nightEnd);
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    /** 判断某个日期时间是否落入夜间限行时段 */
    public static boolean contains(String nightStart, String nightEnd, LocalDateTime dateTime) {
        return contains(nightStart, nightEnd, dateTime.toLocalTime());
    }
}
