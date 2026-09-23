package com.tsymiar.device2device.market;

/**
 * 实时盘口快照（市值 / 市盈率 / 换手率这类 K 线里没有的数据）。
 *
 * 走东方财富 push2 的实时接口，只有 A股 / 港股 / 部分期货能换算成 secid 才有值；
 * 加密货币、外盘商品拿不到就整份为 null，行情小窗自动只显示 K 线本身的数据。
 *
 * 金额单位统一是「元」，展示前用 {@link #cap(double)} 压成万亿 / 亿 / 万。
 */
public final class Snapshot {

    public final String name;
    /** 最新价 */
    public final double price;
    /** 涨跌幅 % */
    public final double changePercent;
    /** 总市值（元） */
    public final double totalCap;
    /** 流通市值（元） */
    public final double floatCap;
    /** 市盈率（动态），拿不到时是 0 */
    public final double pe;
    /** 换手率 %，拿不到时是 0 */
    public final double turnoverRate;

    public Snapshot(String name, double price, double changePercent,
                    double totalCap, double floatCap, double pe, double turnoverRate) {
        this.name = name == null ? "" : name;
        this.price = price;
        this.changePercent = changePercent;
        this.totalCap = totalCap;
        this.floatCap = floatCap;
        this.pe = pe;
        this.turnoverRate = turnoverRate;
    }

    /** 金额压缩成中文单位：万亿 / 亿 / 万（元） */
    public static String cap(double value) {
        if (value <= 0) return "-";
        double abs = Math.abs(value);
        if (abs >= 1e12) return String.format(java.util.Locale.US, "%.2f万亿", value / 1e12);
        if (abs >= 1e8) return String.format(java.util.Locale.US, "%.2f亿", value / 1e8);
        if (abs >= 1e4) return String.format(java.util.Locale.US, "%.2f万", value / 1e4);
        return String.format(java.util.Locale.US, "%.0f", value);
    }
}
