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

    /** 脸型系数：{ 脸宽, 脸深, 下颌宽, 脸长 } */
    static final float[][] FACE = {
            {1.00f, 1.00f, 0.66f, 1.00f},   // 鹅蛋脸
            {1.10f, 1.04f, 0.86f, 0.95f},   // 圆脸
            {1.07f, 0.99f, 0.95f, 0.98f},   // 方脸
            {0.93f, 0.94f, 0.60f, 1.10f},   // 长脸
            {1.02f, 1.00f, 0.48f, 1.02f},   // 心形脸
            {0.98f, 0.96f, 0.70f, 1.02f},   // 菱形脸
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
    public float bustR = 1f;        // 女性胸部（男性近似无效）
    public float thighR = 1f;       // 大腿围（在 BMI 推出的围度上再乘这个倍率）
    public float calfR = 1f;        // 小腿围
    public float footR = 1f;        // 脚长（鞋码）
    public float footWR = 1f;       // 脚宽 / 鞋楦宽

    /** 五官细分：1.0 为标准；照片推断后仍可手调 */
    public float faceWidthR = 1f;   // 脸宽
    public float faceLenR = 1f;     // 脸长（眉心到下巴）
    public float jawR = 1f;         // 下颌宽
    public float chinR = 1f;        // 下巴长度 / 前突
    public float cheekR = 1f;       // 颧骨
    public float foreheadR = 1f;    // 额头高（发际线）
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
    public int shoe = 0xFF23262C;

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
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
        p.shoe = shoe;
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
