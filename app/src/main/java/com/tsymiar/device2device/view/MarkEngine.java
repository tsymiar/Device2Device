package com.tsymiar.device2device.view;

import com.tsymiar.device2device.market.Indicators;
import com.tsymiar.device2device.market.Quote;

import java.util.List;

/**
 * 多空标记计算：从 {@code KLineView} 拆出的纯函数部分。
 *
 * 同一根 K 线可能同时命中好几个标记（比如 MACD 金叉和 KDJ 低位金叉经常撞在一起），
 * 所以按位存进 int；绘制时同侧按顺序往下 / 往上错开，别叠成一坨。
 */
final class MarkEngine {

    static final int MK_NINE_BUY = 0;    // 神奇九转 买入九转（底）
    static final int MK_NINE_SELL = 1;   // 神奇九转 卖出九转（顶）
    static final int MK_REBOUND = 2;     // RSI 超卖回升
    static final int MK_FALLBACK = 3;    // RSI 超买走弱
    static final int MK_MACD_GOLD = 4;   // MACD 金叉
    static final int MK_MACD_DEAD = 5;   // MACD 死叉
    static final int MK_KDJ_GOLD = 6;    // KDJ 低位金叉
    static final int MK_KDJ_DEAD = 7;    // KDJ 高位死叉
    static final int MK_COUNT = 8;
    /** 标记文字，下标即 MK_* */
    static final String[] MK_TEXT = {"9", "9", "弹", "落", "金", "死", "低", "高"};
    /** true=多头（画在 K 线下方），false=空头（画在上方） */
    static final boolean[] MK_BULL = {true, false, true, false, true, false, true, false};

    private MarkEngine() {
    }

    /**
     * 多空标记：按位写进 marks[i]。
     *
     * 四条来源互相独立，命中条件都取「上/下穿的那一根」而不是整段区间，
     * 这样同一根上叠多个标记时只会多占一行，不会整片糊住。
     */
    static int[] compute(List<Quote> data) {
        int n = data.size();
        int[] marks = new int[n];
        float[] closes = Indicators.closes(data);

        // 神奇九转：只取第 9 根（从 <9 变到 9 的那根），1~8 和重复的 9 不算
        Indicators.NineTurns nine = Indicators.nineTurns(closes);
        for (int i = 0; i < n; i++) {
            if (nine.buy[i] == 9 && (i == 0 || nine.buy[i - 1] < 9)) marks[i] |= 1 << MK_NINE_BUY;
            else if (nine.sell[i] == 9 && (i == 0 || nine.sell[i - 1] < 9)) marks[i] |= 1 << MK_NINE_SELL;
        }

        // RSI：超卖区上穿 30 且收阳 → 反弹；超买区下穿 70 且收阴 → 回落
        float[] rsi = Indicators.rsi(closes, 14);
        for (int i = 1; i < n; i++) {
            if (Float.isNaN(rsi[i]) || Float.isNaN(rsi[i - 1])) continue;
            Quote q = data.get(i);
            if (rsi[i - 1] <= 30f && rsi[i] > 30f && q.close >= q.open) marks[i] |= 1 << MK_REBOUND;
            else if (rsi[i - 1] >= 70f && rsi[i] < 70f && q.close <= q.open) marks[i] |= 1 << MK_FALLBACK;
        }

        // MACD：DIF 上穿 DEA 金叉、下穿死叉
        Indicators.Macd macd = Indicators.macd(closes, 12, 26, 9);
        for (int i = 1; i < n; i++) {
            if (macd.dif[i - 1] <= macd.dea[i - 1] && macd.dif[i] > macd.dea[i]) marks[i] |= 1 << MK_MACD_GOLD;
            else if (macd.dif[i - 1] >= macd.dea[i - 1] && macd.dif[i] < macd.dea[i]) marks[i] |= 1 << MK_MACD_DEAD;
        }

        // KDJ：只在超买超卖区取交叉，否则日线上几乎天天有金叉死叉，标记就没意义了
        Indicators.Kdj kdj = Indicators.kdj(Indicators.highs(data), Indicators.lows(data), closes, 9, 3, 3);
        for (int i = 1; i < n; i++) {
            if (kdj.k[i - 1] <= kdj.d[i - 1] && kdj.k[i] > kdj.d[i] && kdj.k[i] < 20f) marks[i] |= 1 << MK_KDJ_GOLD;
            else if (kdj.k[i - 1] >= kdj.d[i - 1] && kdj.k[i] < kdj.d[i] && kdj.k[i] > 80f) marks[i] |= 1 << MK_KDJ_DEAD;
        }
        return marks;
    }
}
