package com.tsymiar.device2device.view;

import com.tsymiar.device2device.market.Quote;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * K线时间轴工具：从 {@code KLineView} 拆出。
 *
 * 职责：
 *  - 时间字符串解析（兼容 'YYYY-MM-DD HH:MM:SS' / '... HH:MM' / 'YYYY-MM-DD'）；
 *  - 按周期 + 可视跨度智能选时间标签格式（脚本 time_label_format）；
 *  - 标签文本生成（季线写成 2024Q1 —— SimpleDateFormat 的季度符各版本表现不一致，手拼最稳）。
 */
final class ChartTime {

    private ChartTime() {
    }

    /** K线时间的前 10 位就是日期 "YYYY-MM-DD"；格式不对（或数据源没给时间）返回 null */
    static String dayOf(String time) {
        if (time == null || time.length() < 10) return null;
        return time.substring(0, 10);
    }

    /** 详情浮窗展示用：分钟级K线把秒截掉（"… HH:mm:ss" → "… HH:mm"），其余原样 */
    static String briefTime(String time) {
        return time != null && time.length() == 19 && time.charAt(16) == ':'
                ? time.substring(0, 16) : time;
    }

    /**
     * 时间轴标签格式（脚本 time_label_format）：日线按跨度降级，日内同日只显时分。
     *
     * @param interval 当前周期（1d/1w/1M/1Q/1Y…）：长周期直接按周期定格式，
     *                 否则跨几十年的季/年线会退化成一堆相同年份标签
     * @param start    可视窗口首根下标
     * @param end      可视窗口末根下标（含）
     */
    static String pattern(String interval, List<Quote> data, int start, int end) {
        if ("1Y".equals(interval)) return "yyyy";
        if ("1Q".equals(interval) || "1M".equals(interval)) return "yyyy-MM";
        if (data == null || data.isEmpty() || end < start
                || start < 0 || end >= data.size()) return "MM-dd";
        Date first = parseStamp(data.get(start).time);
        Date mid = parseStamp(data.get((start + end) / 2).time);
        Date last = parseStamp(data.get(end).time);
        if (first == null || last == null) return "MM-dd";
        long days = (last.getTime() - first.getTime()) / 86400000L;
        boolean sameYear = first.getYear() == last.getYear();
        boolean intraday = hasTime(first) || (mid != null && hasTime(mid)) || hasTime(last);
        if (!intraday) {
            if (days <= 120 || (days <= 400 && sameYear)) return "MM-dd";
            if (days <= 1200) return "yyyy-MM";
            return "yyyy";
        }
        if (sameDay(first, last)) return "HH:mm";
        if (days <= 3) return "MM-dd HH:mm";
        return sameYear ? "MM-dd" : "yyyy-MM";
    }

    /** 按 {@link #pattern} 选出的格式生成一根K线的标签 */
    static String format(String interval, String time, SimpleDateFormat fmt) {
        Date stamp = parseStamp(time);
        if (stamp == null) return time == null ? "" : time.substring(0, Math.min(10, time.length()));
        if ("1Q".equals(interval)) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(stamp);
            return calendar.get(Calendar.YEAR) + "Q" + (calendar.get(Calendar.MONTH) / 3 + 1);
        }
        return fmt.format(stamp);
    }

    /** 兼容 'YYYY-MM-DD HH:MM:SS' / '... HH:MM' / 'YYYY-MM-DD' 三种写法（脚本 _parse_stamp） */
    static Date parseStamp(String text) {
        if (text == null) return null;
        String stamp = text.trim().replace('/', '-');
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        int[] lengths = {19, 16, 10};
        for (int i = 0; i < patterns.length; i++) {
            if (stamp.length() < lengths[i]) continue;
            try {
                return new SimpleDateFormat(patterns[i], Locale.US).parse(stamp.substring(0, lengths[i]));
            } catch (Exception ignored) {
                // 继续尝试下一种写法
            }
        }
        return null;
    }

    private static boolean hasTime(Date stamp) {
        return stamp.getHours() != 0 || stamp.getMinutes() != 0 || stamp.getSeconds() != 0;
    }

    private static boolean sameDay(Date a, Date b) {
        return a.getYear() == b.getYear() && a.getMonth() == b.getMonth() && a.getDate() == b.getDate();
    }
}
