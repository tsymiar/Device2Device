package com.tsymiar.device2device.avatar;

import android.graphics.Bitmap;

/**
 * 人物 3D 模型的输入参数。
 *
 * 一部分来自用户填写（性别 / 身高 / 体重 / 头身比 / 脸型 / 发型 / 配色），
 * 一部分来自照片推断（肩、腰、臀相对比例 + 肤色 / 发色 / 服装配色 + 五官尺寸）。
 *
 * 模型在米制坐标系里按真实身高 1:1 生成（脚底 y=0，头顶 y=身高），
 * 因此预览区的地面网格与身高标尺就是等身比例的参照。
 */
public class BodyProfile {

    public static final String[] GENDERS = {"男", "女"};
    public static final String[] FACE_SHAPES = {"鹅蛋脸", "圆脸", "方脸", "长脸", "心形脸", "菱形脸"};
    public static final String[] HAIR_STYLES = {"光头", "寸头", "短发", "中长发", "长直发", "马尾", "丸子头", "卷发"};
    /** 刘海：在发型之外单独选，下缘随方位变化（齐 / 斜 / 中分） */
    public static final String[] BANGS = {"无", "齐刘海", "斜刘海", "中分", "空气刘海"};

    /**
     * 罩杯：AA ~ G（中国码，胸围 − 下胸围每 2.5cm 一档）。
     *
     * 原先以「丰满度 1.0 = B 杯」为锚点：网格自带的那层乳房拆不出来（不能削平再量），
     * 就假定它正好是一只 B。实测推翻了这个假设 —— 丰满度 1.0 时胸段围度剖面是一条平平
     * 的斜线，根本没有乳房那道隆起（胸围与下胸围实测只差 0.03cm）。于是 B 杯是平的，
     * A / AA 还得在这 0 上再收 2.5 / 5cm，可总共只收得动 0.9cm —— 三个杯位看着一模一样。
     * 现在改成：乳腺全部由丰满度长出来，杯差直接取实测的（胸围 − 下胸围），
     * 丰满度 1.0 给足一只 B 杯的量（基准量见 AnnyModel#BUST_BASE）。
     * 于是 AA→G 是「长多长少」，小杯也是圆润的小丘，不是从平胸往下挖出来的坑。
     */
    public static final String[] BUST_CUP_LABELS = {"AA", "A", "B", "C", "D", "E", "F", "G"};
    public static final float[] BUST_CUP_CM = {7.5f, 10f, 12.5f, 15f, 17.5f, 20f, 22.5f, 25f};

    public static final String[] BUST_SHAPES = {"圆盘", "半球", "水滴", "圆锥", "下垂"};

    /**
     * 胸型系数：{ 中心高度偏移, 横向影响半径, 前后影响半径, 纵向影响半径, 下垂占比 }。
     *
     * 第 0 项是相对「半球」基准的上下偏移（身高比例，负 = 更低），后三项是各方向的影响
     * 半径（身高比例），最后一项是把隆起的一部分转成向下坠的比例。两套引擎共用这张表，
     * 各自再叠一个自己的基准高度，所以切引擎后形状是一致的。
     */
    static final float[][] BUST = {
            { 0.008f, 0.058f, 0.042f, 0.032f, 0.00f},   // 圆盘：底盘大、隆起浅
            { 0.000f, 0.048f, 0.050f, 0.042f, 0.05f},   // 半球：最标准的球形隆起
            {-0.006f, 0.049f, 0.052f, 0.045f, 0.30f},   // 水滴：下极更饱满
            {-0.002f, 0.038f, 0.062f, 0.040f, 0.00f},   // 圆锥：底盘小、前突明显
            {-0.024f, 0.048f, 0.046f, 0.050f, 0.60f},   // 下垂：整体下移、下极重
    };

    /** 脸型系数：{ 脸宽, 脸深, 下颌宽, 脸长 } */
    static final float[][] FACE = {
            {1.00f, 1.00f, 0.66f, 1.00f},   // 鹅蛋脸
            {1.10f, 1.04f, 0.86f, 0.95f},   // 圆脸
            {1.07f, 0.99f, 0.95f, 0.98f},   // 方脸
            {0.93f, 0.94f, 0.60f, 1.10f},   // 长脸
            {1.02f, 1.00f, 0.48f, 1.02f},   // 心形脸
            {0.98f, 0.96f, 0.58f, 1.03f},   // 菱形脸：颧骨最宽而下颌窄下巴尖（表里没有颧骨项，所以压下颌）
    };

    /** 发型系数：{ 发量(相对头围), 发际线位置(0=头顶 1=下巴) } */
    static final float[][] HAIR = {
            {1.00f, 0.50f},   // 光头（不使用）
            {1.03f, 0.52f},   // 寸头
            {1.06f, 0.58f},   // 短发
            {1.08f, 0.66f},   // 中长发
            {1.09f, 0.70f},   // 长直发
            {1.06f, 0.58f},   // 马尾
            {1.05f, 0.58f},   // 丸子头
            {1.15f, 0.62f},   // 卷发
    };

    public int gender = 0;                  // 0=男 1=女
    public float heightCm = 172f;
    public float weightKg = 65f;
    public float headRatio = 7.5f;          // 头身比（成人常见 6.5~8）
    public int faceShape = 0;
    public int hairStyle = 2;
    public int bangs = 0;

    /** 相对标准骨架的围度倍率，1.0 为标准；可由照片轮廓推断 */
    public float shoulderR = 1f;
    public float chestR = 1f;
    public float waistR = 1f;
    public float hipR = 1f;

    /** 身材细分：1.0 为标准比例，越大越长 / 越发达 */
    public float legR = 1f;         // 腿长（下裆高）
    public float torsoR = 1f;       // 躯干长（肩到裆）
    public float armR = 1f;         // 臂长
    public float neckLenR = 1f;     // 颈长
    public float muscleR = 1f;      // 肌肉量：肩 / 臂 / 小腿变粗，腰腹不变
    public float bustR = 1f;        // 女性胸部隆起量（男性近似无效）
    public int bustShape = 1;       // 胸型（见 BUST_SHAPES，男性只影响胸肌轮廓）
    public float thighR = 1f;       // 大腿围（在 BMI 推出的围度上再乘这个倍率）
    public float calfR = 1f;        // 小腿围
    public float footR = 1f;        // 脚长（赤脚）
    public float footWR = 1f;       // 脚宽

    /** 五官细分：1.0 为标准；照片推断后仍可手调 */
    public float faceWidthR = 1f;   // 脸宽
    public float faceLenR = 1f;     // 脸长（眉心到下巴）
    public float jawR = 1f;         // 下颌宽
    public float chinR = 1f;        // 下巴长度 / 前突
    public float cheekR = 1f;       // 颧骨
    public float foreheadR = 1f;    // 额头高（发际线）
    public float earSizeR = 1f;     // 耳朵大小
    public float browR = 1f;        // 眉毛浓淡 / 粗细
    public float eyeSizeR = 1f;     // 眼睛大小
    public float eyeGapR = 1f;      // 眼距
    public float noseWR = 1f;       // 鼻宽
    public float noseHR = 1f;       // 鼻长
    public float lipWR = 1f;        // 唇宽
    public float lipTR = 1f;        // 唇厚

    /** 脸部贴图来源（照片裁出的脸部区域，归一化到同一尺寸）；为空则只用肤色 */
    public Bitmap faceBmp;

    public int skin = 0xFFE7BE9C;
    public int hair = 0xFF2A2018;
    public int top = 0xFF3E6DB4;
    public int bottom = 0xFF31394C;

    /** 取胸型系数，越界时退回半球 */
    public static float[] bustShape(BodyProfile p) {
        int i = p.bustShape < 0 ? 1 : (p.bustShape >= BUST.length ? 1 : p.bustShape);
        return BUST[i];
    }

    /**
     * 脸型预设折算成「相对鹅蛋脸」的倍率 { 脸宽, 脸深, 下颌宽, 脸长 }。
     *
     * {@link #FACE} 里的四个数是相对标准头骨的绝对系数 —— 原生引擎直接拿它们乘出自己的
     * 头宽 / 头深 / 下颌宽 / 头长。高分引擎那一侧已经有一颗具体的头（分数的 MakeHuman 头型），
     * 于是这里以默认档（鹅蛋脸）为 1.0 折算成倍率，与页面上的脸宽 / 脸长滑块叠在一起：
     * 两套引擎调到同一档时，脸的变化方向是一致的。
     */
    public static float[] faceShapeRatio(BodyProfile p) {
        int i = p.faceShape < 0 ? 0 : (p.faceShape >= FACE.length ? 0 : p.faceShape);
        float[] f = FACE[i];
        float[] b = FACE[0];
        return new float[]{f[0] / b[0], f[1] / b[1], f[2] / b[2], f[3] / b[3]};
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---- 腰臀协调 ------------------------------------------------------
    // 腰围和臀围是两个独立的滑块，各拉各的能拉出真人长不出来的体型：实测（女 165/55）
    //   腰 1.60 + 臀 0.70 → 腰 105.6 / 臀 86.4 = 1.22：腰比臀还粗，侧面看没有腰身；
    //   腰 0.70 + 臀 1.60 → 腰 48.1 / 臀 131.2 = 0.37：腰细成一根，接不上那一圈臀。
    // 真人腰臀比：女 0.67~0.85、男 0.80~0.95（偏瘦 / 偏胖到 0.62 / 1.00 也还在人形里）。
    // 所以这里把两者协调进一个区间，而不是任由两个滑块独立取值。

    /**
     * 腰臀比基准：两个滑块都取 1 时实测出来的比值（男 / 女）。
     * 高分 0.83 / 0.73、原生 0.84 / 0.70 —— 两套引擎的标准体型不一样，各用各的，
     * 否则协调会在其中一套上整体偏一格（原生按 0.73 算，细的那头会夹到 0.59）
     */
    private static final float[] WHR_STD_ANNY = {0.83f, 0.73f};
    private static final float[] WHR_STD_NATIVE = {0.84f, 0.70f};
    /** 允许的腰臀比区间（男 / 女）：再瘦、再胖也就到这两头 */
    private static final float[] WHR_LO = {0.72f, 0.62f};
    private static final float[] WHR_HI = {1.00f, 0.95f};
    /**
     * 臀围倍率 → 臀围实际的变化倍率（高分引擎实测，两性别几乎一样）。
     *
     * 臀那一圈里含着大腿根，臀收细到一定程度之后，皮尺那一圈就由大腿定了下限 ——
     * 臀围滑块拉到 0.70，臀围只从 92.3 收到 86.4cm（0.94 而不是 0.70）。按线性算会把
     * 「腰 1.00 + 臀 0.70」误判成腰粗过臀（算 1.04、实测 0.78），于是该协调的没协调、
     * 不该协调的乱协调；而这一头一旦算错，反解出来的臀围会离谱（腰 0.70 配臀 1.60
     * 会被「协调」成臀 0.82 —— 用户拉大臀围，结果臀自己缩了一半）
     */
    private static final float[] HIP_RESP_X = {0.70f, 0.85f, 1.00f, 1.20f, 1.40f, 1.60f};
    private static final float[] HIP_RESP_F = {0.936f, 0.961f, 1.000f, 1.090f, 1.259f, 1.432f};

    /**
     * 腰臀协调：把两个倍率协调进真人的腰臀比区间。
     *
     * @param wh   长度 ≥2 的数组，传入 { 腰围倍率, 臀围倍率 }，回填协调后的取值
     * @param keep &gt;0 保住腰（拖腰围滑块时臀跟着动）、&lt;0 保住臀、0 两边各让一半
     */
    public static void waistHip(float[] wh, int gender, int keep) {
        solveWaistHip(wh, gender, keep, false);
    }

    /** 高分引擎那一版：臀围按实测的响应曲线算（见 {@link #HIP_RESP_F}） */
    public static void waistHipAnny(float[] wh, int gender, int keep) {
        solveWaistHip(wh, gender, keep, true);
    }

    private static void solveWaistHip(float[] wh, int gender, int keep, boolean anny) {
        int g = gender == 1 ? 1 : 0;
        float std = anny ? WHR_STD_ANNY[g] : WHR_STD_NATIVE[g];
        float lo = WHR_LO[g];
        float hi = WHR_HI[g];
        float w = clamp(wh[0], 0.70f, 1.60f);
        float h = clamp(wh[1], 0.70f, 1.60f);
        // 响应曲线不是线性的，一次「各让一半」未必正好落在区间里，迭代几次收敛
        for (int it = 0; it < 6; it++) {
            float hg = anny ? hipResp(h) : h;
            float whr = std * w / hg;
            if (whr >= lo && whr <= hi) break;
            float t = clamp(whr, lo, hi);
            if (keep > 0) {
                h = anny ? hipRespInv(std * w / t) : std * w / t;      // 腰照用户给的算
            } else if (keep < 0) {
                w = hg * t / std;                                      // 臀照用户给的算
            } else {
                // 腰 ×k、臀 ÷k，乘积不变：只动一头的话，拉腰围会看到臀自己胖三圈，
                // 或者腰拉到底却一点不瘦 —— 分成两半，两头都只是微调
                float k = (float) Math.sqrt(t / whr);
                w *= k;
                h /= k;
            }
            w = clamp(w, 0.70f, 1.60f);
            h = clamp(h, 0.70f, 1.60f);
        }
        wh[0] = w;
        wh[1] = h;
    }

    private static float hipResp(float h) {
        return lerp(HIP_RESP_X, HIP_RESP_F, h);
    }

    private static float hipRespInv(float f) {
        return lerp(HIP_RESP_F, HIP_RESP_X, f);
    }

    private static float lerp(float[] x, float[] y, float v) {
        int n = x.length;
        if (v <= x[0]) return y[0];
        if (v >= x[n - 1]) return y[n - 1];
        for (int i = 1; i < n; i++) {
            if (v <= x[i]) {
                float t = (v - x[i - 1]) / (x[i] - x[i - 1]);
                return y[i - 1] + (y[i] - y[i - 1]) * t;
            }
        }
        return y[n - 1];
    }

    /** 快照：建模放到后台线程，避免拖动手感被建模耗时影响 */
    public BodyProfile copy() {
        BodyProfile p = new BodyProfile();
        p.gender = gender;
        p.heightCm = heightCm;
        p.weightKg = weightKg;
        p.headRatio = headRatio;
        p.faceShape = faceShape;
        p.hairStyle = hairStyle;
        p.bangs = bangs;
        p.shoulderR = shoulderR;
        p.chestR = chestR;
        p.waistR = waistR;
        p.hipR = hipR;
        p.legR = legR;
        p.torsoR = torsoR;
        p.armR = armR;
        p.neckLenR = neckLenR;
        p.muscleR = muscleR;
        p.bustR = bustR;
        p.bustShape = bustShape;
        p.thighR = thighR;
        p.calfR = calfR;
        p.footR = footR;
        p.footWR = footWR;
        p.faceWidthR = faceWidthR;
        p.faceLenR = faceLenR;
        p.jawR = jawR;
        p.chinR = chinR;
        p.cheekR = cheekR;
        p.foreheadR = foreheadR;
        p.earSizeR = earSizeR;
        p.browR = browR;
        p.eyeSizeR = eyeSizeR;
        p.eyeGapR = eyeGapR;
        p.noseWR = noseWR;
        p.noseHR = noseHR;
        p.lipWR = lipWR;
        p.lipTR = lipTR;
        p.faceBmp = faceBmp;
        p.skin = skin;
        p.hair = hair;
        p.top = top;
        p.bottom = bottom;
        return p;
    }

    public float bmi() {
        float m = heightCm / 100f;
        if (m <= 0.01f) return 0f;
        return weightKg / (m * m);
    }

    /** 参考 BMI：男 22 / 女 21 */
    public float refBmi() {
        return gender == 0 ? 22f : 21f;
    }

    /** 骨架+四肢围度倍率：BMI 与截面半径近似平方根关系 */
    public float girth() {
        float r = refBmi();
        if (r <= 0f) return 1f;
        return clamp((float) Math.sqrt(bmi() / r), 0.78f, 1.45f);
    }

    /** 腰臀脂肪倍率：脂肪更多堆积在腰腹与臀部，指数取得比四肢更陡 */
    public float fatGirth() {
        float r = refBmi();
        if (r <= 0f) return 1f;
        return clamp((float) Math.pow(bmi() / r, 0.95f), 0.72f, 1.75f);
    }

    /** 中国成人 BMI 分级 */
    public String bmiLabel() {
        float v = bmi();
        if (v <= 0f) return "--";
        if (v < 18.5f) return "偏瘦";
        if (v < 24f) return "标准";
        if (v < 28f) return "偏胖";
        return "肥胖";
    }

    /** 按参考 BMI 反推的标准体重(kg) */
    public float standardWeight() {
        float m = heightCm / 100f;
        return refBmi() * m * m;
    }

}
