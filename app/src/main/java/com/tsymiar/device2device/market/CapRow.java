package com.tsymiar.device2device.market;

/**
 * 市值榜里的一行（桌面热力图小部件用）。
 *
 * 比 {@link Snapshot} 少些字段：热力图只要「代码 / 名称 / 涨跌幅 / 总市值」，
 * 市盈率、换手率用不上，榜单接口也不给；反过来复用 Snapshot 会让「哪些字段一定有」变模糊 ——
 * 榜单与个股快照是两种取数场景，各留一份结构更清楚。
 *
 * 金额单位与 Snapshot 对齐：统一「元」，展示前用 {@link Snapshot#cap(double)} 压成万亿 / 亿。
 */
public final class CapRow {

    /** 纯代码（600519 / 000001） */
    public final String code;
    /** 股票简称（贵州茅台） */
    public final String name;
    /** 涨跌幅 %，拿不到时是 0 */
    public final double changePercent;
    /** 市值（元） */
    public final double totalCap;
    /** 东财 secid（1.600519 / 0.000001）：跳转行情页时省掉一次换算 */
    public final String secid;

    public CapRow(String code, String name, double changePercent, double totalCap, String secid) {
        this.code = code == null ? "" : code;
        this.name = name == null || name.isEmpty() ? this.code : name;
        this.changePercent = changePercent;
        this.totalCap = totalCap;
        this.secid = secid == null ? "" : secid;
    }
}
