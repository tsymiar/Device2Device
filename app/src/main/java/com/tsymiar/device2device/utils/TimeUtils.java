package com.tsymiar.device2device.utils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class TimeUtils {
    /**
     * 判断时间戳是否是今天
     */
    public static boolean isToday(long timestamp) {
        Calendar today = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);

        return today.get(Calendar.YEAR) == target.get(Calendar.YEAR)
                && today.get(Calendar.MONTH) == target.get(Calendar.MONTH)
                && today.get(Calendar.DAY_OF_MONTH) == target.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * 智能时间格式化
     */
    public static String smartTimeFormat(long timestamp) {
        if (isToday(timestamp)) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(timestamp));
        } else if (isYesterday(timestamp)) {
            return "昨天 " + new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(timestamp));
        } else {
            return new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                    .format(new Date(timestamp));
        }
    }

    /**
     * 判断是否是昨天
     */
    private static boolean isYesterday(long timestamp) {
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DATE, -1);

        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);

        return yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR)
                && yesterday.get(Calendar.MONTH) == target.get(Calendar.MONTH)
                && yesterday.get(Calendar.DAY_OF_MONTH) == target.get(Calendar.DAY_OF_MONTH);
    }
}