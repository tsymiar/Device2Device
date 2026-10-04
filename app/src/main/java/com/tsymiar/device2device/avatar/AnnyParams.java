package com.tsymiar.device2device.avatar;

/**
 * Anny 的 phenotype（表型）参数向量，以及它与 {@link BodyProfile} 的映射。
 *
 * Anny（NAVER LABS，Apache-2.0）不是从扫描数据里学出来的隐空间，而是把
 * MakeHuman 社区积累的人体测量学知识做成一组「原型形变基」：每个参数（性别、年龄、
 * 身高、体重、肌肉量…）在 [0,1] 上连续取值，按分段多线性插值把这些原形混合起来。
 * 参数名由模型文件自带（labels；v2 文件里是导出时保留下来的自由维度），
 * 这里按关键字匹配，匹配不到的参数保持 0.5（默认体型）。
 *
 * 只映射 Anny 语义上确实存在的那几项；腿长 / 躯干长 / 颈长 / 围度这类 Anny 没有直接
 * 表型的量，交给 {@link AnnyModel} 用人体测量形变（纵向分段 + 环向缩放）来实现，
 * 这样页面上的滑块在两套引擎下的含义保持一致。
 */
public final class AnnyParams {

    /** 与模型文件一致的参数名 */
    public final String[] labels;
    /** 每个参数的取值，均为 0~1 */
    public final float[] values;

    AnnyParams(String[] labels, float[] values) {
        this.labels = labels;
        this.values = values;
    }

    /**
     * 由页面的 BodyProfile 推出 Anny 表型参数。
     *
     * @param labels 模型文件里读到的参数名
     */
    public static AnnyParams fromProfile(BodyProfile p, String[] labels) {
        float[] v = new float[labels.length];
        for (int i = 0; i < v.length; i++) v[i] = 0.5f;           // 默认：中间体型

        // 性别：Anny 的 gender 节点是 [male, female]（0 → 男，1 → 女），与页面一致
        put(v, labels, new String[]{"gender"}, p.gender == 1 ? 1f : 0f);
        // 年龄：页面只做成人，取 0.70（Anny：1/3 儿童、2/3 青年、1 老年）
        put(v, labels, new String[]{"age"}, 0.70f);
        // 身高：140~195cm 线性铺到 0~1；最终高度还会被归一化到滑块值，这里只影响体型比例
        put(v, labels, new String[]{"height"}, clamp01((p.heightCm - 140f) / 55f));
        // 体重：用 BMI 16~28 铺到 0~1（BMI 22 正好落在中间）
        put(v, labels, new String[]{"weight", "mass", "fat"}, clamp01((p.bmi() - 16f) / 12f));
        // 肌肉量：滑块 0.75~1.35 铺到 0.1~0.9
        put(v, labels, new String[]{"muscle"}, clamp01(0.5f + (p.muscleR - 1f) * 1.6f));
        // 胸型：只有女性模型才有意义，男性直接给 0（平板）
        put(v, labels, new String[]{"breast", "cup"},
                p.gender == 1 ? clamp01((p.bustR - 0.2f) / 1.6f) : 0f);
        return new AnnyParams(labels, v);
    }

    private static void put(float[] v, String[] labels, String[] keywords, float value) {
        for (int i = 0; i < labels.length; i++) {
            if (matches(labels[i], keywords)) {
                v[i] = value;
                return;
            }
        }
    }

    private static boolean matches(String label, String[] keywords) {
        if (label == null) return false;
        String s = label.toLowerCase();
        for (String k : keywords) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }
}
