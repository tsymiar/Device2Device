package com.tsymiar.device2device.market;

import java.util.List;

/**
 * 技术指标：SMA(简单均线) / EMA / MACD(12,26,9) / RSI(14, Wilder平滑)。
 *
 * 口径与 MyAutomatic/toolset/matkline.py 保持一致（MACD 柱 = 2*(DIF-DEA)），
 * 这样同一段行情在脚本与手机上画出的形态可以互相对照。
 *
 * 前 (window-1) 根不足窗口的均线值填 Float.NaN，绘图时跳过。
 */
public final class Indicators {

    private Indicators() {
    }

    /** 收盘价序列 */
    public static float[] closes(List<Quote> quotes) {
        float[] out = new float[quotes.size()];
        for (int i = 0; i < out.length; i++) out[i] = quotes.get(i).close;
        return out;
    }

    /** 最高价序列（KDJ 用） */
    public static float[] highs(List<Quote> quotes) {
        float[] out = new float[quotes.size()];
        for (int i = 0; i < out.length; i++) out[i] = quotes.get(i).high;
        return out;
    }

    /** 最低价序列（KDJ 用） */
    public static float[] lows(List<Quote> quotes) {
        float[] out = new float[quotes.size()];
        for (int i = 0; i < out.length; i++) out[i] = quotes.get(i).low;
        return out;
    }

    /** 简单移动平均（前 window-1 个为 NaN） */
    public static float[] sma(float[] values, int window) {
        float[] out = new float[values.length];
        if (window <= 0 || values.length < window) {
            fillNaN(out);
            return out;
        }
        double sum = 0.0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= window) sum -= values[i - window];
            out[i] = (i >= window - 1) ? (float) (sum / window) : Float.NaN;
        }
        return out;
    }

    /** 指数移动平均 */
    public static float[] ema(float[] values, int span) {
        float[] out = new float[values.length];
        if (values.length == 0) return out;
        float alpha = 2.0f / (span + 1.0f);
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = alpha * values[i] + (1.0f - alpha) * out[i - 1];
        }
        return out;
    }

    /** MACD 三件套 */
    public static final class Macd {
        public final float[] dif;
        public final float[] dea;
        /** 柱 = 2*(DIF-DEA)，与行情软件口径一致 */
        public final float[] hist;

        Macd(float[] dif, float[] dea, float[] hist) {
            this.dif = dif;
            this.dea = dea;
            this.hist = hist;
        }
    }

    public static Macd macd(float[] values, int fast, int slow, int signal) {
        if (values.length == 0) {
            float[] empty = new float[0];
            return new Macd(empty, empty, empty);
        }
        float[] fastEma = ema(values, fast);
        float[] slowEma = ema(values, slow);
        float[] dif = new float[values.length];
        for (int i = 0; i < values.length; i++) dif[i] = fastEma[i] - slowEma[i];
        float[] dea = ema(dif, signal);
        float[] hist = new float[values.length];
        for (int i = 0; i < values.length; i++) hist[i] = 2.0f * (dif[i] - dea[i]);
        return new Macd(dif, dea, hist);
    }

    /** RSI(Wilder平滑)；数据不足 period+1 根时返回全 NaN */
    public static float[] rsi(float[] values, int period) {
        float[] out = new float[values.length];
        if (values.length <= period) {
            fillNaN(out);
            return out;
        }
        for (int i = 0; i <= period; i++) out[i] = Float.NaN;
        double avgGain = 0.0;
        double avgLoss = 0.0;
        for (int i = 1; i <= period; i++) {
            float delta = values[i] - values[i - 1];
            if (delta > 0) avgGain += delta;
            else avgLoss -= delta;
        }
        avgGain /= period;
        avgLoss /= period;
        for (int i = period; i < values.length; i++) {
            if (i > period) {
                float delta = values[i] - values[i - 1];
                double gain = delta > 0 ? delta : 0.0;
                double loss = delta < 0 ? -delta : 0.0;
                avgGain = (avgGain * (period - 1) + gain) / period;
                avgLoss = (avgLoss * (period - 1) + loss) / period;
            }
            out[i] = avgLoss == 0 ? 100f : (float) (100.0 - 100.0 / (1.0 + avgGain / avgLoss));
        }
        return out;
    }

    /** KDJ 三件套（默认 9,3,3） */
    public static final class Kdj {
        public final float[] k;
        public final float[] d;
        public final float[] j;

        Kdj(float[] k, float[] d, float[] j) {
            this.k = k;
            this.d = d;
            this.j = j;
        }
    }

    /**
     * KDJ：RSV 取 period 日内最高最低，K/D 各做一次平滑，J = 3K - 2D。
     * 与脚本一致：K/D 从 50 起算，区间内高低点相等时 RSV 记 50。
     */
    public static Kdj kdj(float[] high, float[] low, float[] close,
                          int period, int kPeriod, int dPeriod) {
        int count = close.length;
        float[] kOut = new float[count];
        float[] dOut = new float[count];
        float[] jOut = new float[count];
        float kPrev = 50f;
        float dPrev = 50f;
        for (int i = 0; i < count; i++) {
            int start = Math.max(0, i - period + 1);
            float highest = high[start];
            float lowest = low[start];
            for (int m = start + 1; m <= i; m++) {
                if (high[m] > highest) highest = high[m];
                if (low[m] < lowest) lowest = low[m];
            }
            float rsv = highest == lowest ? 50f : (close[i] - lowest) / (highest - lowest) * 100f;
            kPrev = (kPrev * (kPeriod - 1) + rsv) / kPeriod;
            dPrev = (dPrev * (dPeriod - 1) + kPrev) / dPeriod;
            kOut[i] = kPrev;
            dOut[i] = dPrev;
            jOut[i] = 3f * kPrev - 2f * dPrev;
        }
        return new Kdj(kOut, dOut, jOut);
    }

    private static void fillNaN(float[] values) {
        for (int i = 0; i < values.length; i++) values[i] = Float.NaN;
    }
}
