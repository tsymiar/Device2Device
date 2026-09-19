package com.tsymiar.device2device.market;

/**
 * 单根K线。
 *
 * 字段口径与 MyAutomatic/toolset/matkline.py 的统一结构 [时间, 量, 开, 收, 高, 低, 额] 一致，
 * 便于脚本侧与App侧共用同一套解析逻辑与技术指标口径。
 */
public class Quote {

    /** K线时间 "YYYY-MM-DD HH:MM:SS"（日线及以上为 "YYYY-MM-DD 00:00:00"） */
    public final String time;
    public final float open;
    public final float high;
    public final float low;
    public final float close;
    public final float volume;
    public final float amount;

    public Quote(String time, float open, float high, float low,
                 float close, float volume, float amount) {
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.amount = amount;
    }

    /** 涨跌幅%（相对前一根收盘；base<=0 时按 0 处理，避免除零） */
    public float changePercent(float base) {
        if (base <= 0f) return 0f;
        return (close - base) / base * 100f;
    }

    /** 是否为阳线（收盘 >= 开盘），与行情软件"红涨绿跌"判定一致 */
    public boolean isUp() {
        return close >= open;
    }
}
