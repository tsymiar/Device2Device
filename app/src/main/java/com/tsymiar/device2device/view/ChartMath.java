package com.tsymiar.device2device.view;

/**
 * 图表绘制用的数值算法：从 {@code KLineView} 拆出的纯函数部分。
 */
final class ChartMath {

    private ChartMath() {
    }

    /**
     * 单调三次插值的切线斜率（Fritsch–Carlson）：
     * 相邻段异号（局部极值）时切线取 0，同号时取两侧斜率均值并限幅，
     * 这样曲线一定落在相邻两点的值域内 —— 平滑了形状，但不会造出假的高点低点。
     */
    static float[] monotoneTangents(float[] xs, float[] ys, int n) {
        float[] m = new float[n];
        if (n < 2) return m;
        float[] d = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            d[i] = (ys[i + 1] - ys[i]) / Math.max(1e-6f, xs[i + 1] - xs[i]);
        }
        if (n == 2) {
            m[0] = d[0];
            m[1] = d[0];
            return m;
        }
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            if (d[i - 1] * d[i] <= 0f) {
                m[i] = 0f;                       // 局部极值：切线取平，曲线不会冲出值域
            } else {
                m[i] = (d[i - 1] + d[i]) * 0.5f;
                float limit = 3f * Math.min(Math.abs(d[i - 1]), Math.abs(d[i]));
                if (Math.abs(m[i]) > limit) m[i] = limit * Math.signum(m[i]);
            }
        }
        return m;
    }
}
