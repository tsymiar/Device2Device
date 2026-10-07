package com.tsymiar.device2device.avatar;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 按 {@link BodyProfile} 程序化生成等身比例人体网格。
 *
 * 坐标系：脚底 y=0、头顶 y=身高(米)、面朝 +z、x 向右；单位与真实世界一致(米)，
 * 因此配合地面网格与身高标尺即可直观核对等身比例。
 *
 * 生成方式分两层：
 *   1) 躯干 / 四肢 / 头颅：先搭一块隐式曲面（{@link SdfModel}：胶囊 + 椭球 + 圆角盒的平滑并集），
 *      再用 {@link SurfaceNets} 提取等值面。关节处是连续过渡的圆角，不会出现图元穿插的硬边，
 *      法线取自距离场梯度，并顺带烘焙出环境光遮蔽。
 *   2) 五官 / 手 / 脚 / 发型：这些细节尺寸小于体素，直接按解析图元生成后叠在身体上。
 *
 * 体型取自 7.5 头身写实比例（肩 0.82H、腰 0.625H、髋 0.53H、裆 0.475H、膝 0.275H、踝 0.045H），
 * 截面围度由 BMI 推导（四肢≈√BMI，腰臀更陡），脸型作用于头部的宽/深/下颌曲线。
 */
public final class HumanMesh {

    private static final String TAG = "HumanMesh";

    /**
     * 身体等值面的体素密度：身高方向取 176 个采样点（约 0.98cm）。
     * 网格索引仍是 short（上限 65536 顶点），再往上加密会同头部 / 五官一起顶到上限，
     * 所以只到这一档，并给极端体型留了自动降档（见 build()）。
     */
    private static final int GRID_OVER_HEIGHT = 176;

    public static final class Result {
        public float[] positions;
        public float[] normals;
        public float[] colors;
        public float[] ao;
        public short[] indices;
        public MeshBuilder.Part[] parts;
        public int vertices;
        public int triangles;
        public float heightM;
        public float shoulderCm;
        public float chestCm;
        public float underbustCm;   // 女性：下胸围（没有乳腺隆起的一圈），由此算罩杯
        public float waistCm;
        public float hipCm;
        public float inseamCm;
        public float armSpanCm;
        public float thighCm;   // 大腿围
        public float calfCm;    // 小腿围
        public float footCm;    // 脚长
    }

    /** 由 BodyProfile 推导出的骨架尺寸与关键高度 */
    static final class Body {
        boolean male;
        float H, girth, fat, muscle;
        float headH, headW, headD, jaw;
        float shoulderW, chestW, chestD, waistW, waistD, hipW, hipD;
        float neckR, upperArm, foreArm, thighR, calfR, topW;
        float footL, footW;
        float yTop, yChin, yShoulder, yChest, yWaist, yHip, yCrotch, yKnee, yAnkle;
        float yNeckTop, yNeckBot, yElbow, yWrist;
        /** 高分引擎用：从自己的头网格上量出来的径向表 { s, ρ(θ₀), ρ(θ₁), … }；为空则走理想剖面 */
        float[][] skull;
        /** 五官布局整体上移 / 下移的量（s 单位）；没有 faceMap 时作整体平移的兜底 */
        float skullShift;
        /**
         * 高分引擎用：五官布局 → 这颗头自己的 s 的锚点 { 鼻尖, 眉线 }（下巴 = 0、颅顶 = 1）。
         * 见 {@link #mapFaceS}；为 null 时退回 skullShift 的整体平移。
         */
        float[] faceMap;
    }

    /**
     * 五官布局的 s 换成「这颗头自己的 s」。
     *
     * 布局是按理想头型定的（鼻尖 0.385），可 Anny 这颗头实测鼻尖在 0.353 —— 差了 7mm
     * 而已，但只做整体平移怎么都对不上：往下挪够鼻尖，唇（0.235）就顶到鼻尖上；
     * 往上挪够唇，眉眼又跑到额头上。这里改成分段拉伸：鼻尖以下按 sNose/0.385 压、
     * 鼻尖以上按 (1−sNose)/0.615 拉，下巴与颅顶两端仍然严格对住。
     */
    static float mapFaceS(float[] m, float s) {
        float sNose = m[0];
        if (s <= 0f) return s;
        if (s < HeadMesh.S_NOSE_TIP) {
            return sNose * (s / HeadMesh.S_NOSE_TIP);
        }
        return sNose + (1f - sNose)
                * (s - HeadMesh.S_NOSE_TIP) / (1f - HeadMesh.S_NOSE_TIP);
    }

    /** {@link #mapFaceS} 的反函数：这颗头自己的 s → 五官布局的 s（脸部贴图按布局的 s 取样） */
    static float unmapFaceS(float[] m, float s) {
        float sNose = m[0];
        if (s <= 0f) return s;
        if (s < sNose) return HeadMesh.S_NOSE_TIP * (s / Math.max(1e-5f, sNose));
        return HeadMesh.S_NOSE_TIP + (1f - HeadMesh.S_NOSE_TIP)
                * (s - sNose) / Math.max(1e-5f, 1f - sNose);
    }

    private HumanMesh() {
    }

    static Body dims(BodyProfile p) {
        Body b = new Body();
        float H = Math.max(0.8f, p.heightCm / 100f);
        b.H = H;
        b.male = p.gender == 0;
        b.girth = p.girth();
        b.fat = p.fatGirth();
        b.muscle = BodyProfile.clamp(p.muscleR, 0.75f, 1.35f);

        float[] face = BodyProfile.FACE[p.faceShape % BodyProfile.FACE.length];
        b.headH = H / Math.max(4f, p.headRatio) * face[3];
        b.headW = b.headH * 0.35f * face[0];
        b.headD = b.headH * 0.44f * face[1];
        b.jaw = face[2];

        // 各系数为「相对身高的半宽 / 半深」，取值使标准体型还原出常见的成人体围
        float mus = b.muscle;
        b.shoulderW = (b.male ? 0.128f : 0.118f) * H * (1f + (b.girth - 1f) * 0.30f)
                * (0.76f + 0.24f * mus) * p.shoulderR;
        b.chestW = (b.male ? 0.101f : 0.094f) * H * b.girth * (0.84f + 0.16f * mus) * p.chestR;
        // 胸围主要改宽度，厚度只跟着小幅变化，避免前后径失真
        b.chestD = (b.male ? 0.070f : 0.066f) * H * b.girth * (1f + (p.chestR - 1f) * 0.75f)
                * (0.92f + 0.08f * mus);
        // 腰臀协调（见 BodyProfile#waistHip）：原生引擎的腰 / 臀都是绕自己那一段缩放的
        // 椭圆，围度随倍率线性变化，所以协调时按线性响应算
        float[] wh = {p.waistR, p.hipR};
        BodyProfile.waistHip(wh, p.gender, 0);
        // 腰围：加 5%。实测 165/55 女体只有 62.4cm（人体测量约 70cm），腰臀比 0.70 —— 比
        // 常见的 0.72~0.80 还细一档，胸 82 / 臀 89 配这么细的腰就成了掐出来的沙漏，看着
        // 不像真人。补到 65.5 上下，腰臀比回到 0.73。脂肪 / 腰围滑块照旧在这条新基准上缩放
        b.waistW = (b.male ? 0.084f : 0.0756f) * H * b.fat * wh[0];
        b.waistD = (b.male ? 0.060f : 0.0546f) * H * b.fat * wh[0];
        float hipFat = (float) Math.pow(b.fat, 0.75f);
        b.hipW = (b.male ? 0.102f : 0.107f) * H * hipFat * wh[1];
        b.hipD = (b.male ? 0.067f : 0.068f) * H * hipFat * wh[1];
        b.neckR = (b.male ? 0.032f : 0.029f) * H * b.girth;
        b.upperArm = (b.male ? 0.0245f : 0.0225f) * H * b.girth * (0.72f + 0.28f * mus);
        b.foreArm = (b.male ? 0.0210f : 0.0190f) * H * b.girth * (0.74f + 0.26f * mus);
        // 大腿跟着脂肪走、小腿跟着肌肉走；腿脚围度再由滑块单独放大 / 收细
        // 大腿：0.0500 → 0.0465 → 0.0440（女）/ 0.0480 → 0.0446 → 0.0422（男），再收 5%。
        // 原来 165/55 的女体大腿围 50.7cm（半径 8.25cm），比躯干看着粗一档：髋半宽 17.7cm、
        // 腰半宽 11.9cm，同一条腿的半径却到 8.25cm。收到 7.3cm 之后腿才比躯干细一档。
        // 小腿：0.0335 → 0.0310 → 0.0288（女）/ 0.0345 → 0.0320 → 0.0297（男），再收 7%。
        // 腿真正显粗的地方其实在膝以下 —— 实测 165/55 女体膝 43.0 / 小腿肚 38.0 / 踝 25.4cm，
        // 而真人是 35 / 34 / 21cm：大腿那一段比真人还细，膝与踝却粗出一圈，整条腿于是
        // 像一根上下一样粗的管子。围度收掉之外，收口也要跟着加陡（见 buildBodyField）
        b.thighR = (b.male ? 0.0422f : 0.0440f) * H * (float) Math.pow(b.fat, 0.6f)
                * BodyProfile.clamp(p.thighR, 0.70f, 1.45f);
        b.calfR = (b.male ? 0.0297f : 0.0288f) * H * b.girth * (0.76f + 0.24f * mus)
                * BodyProfile.clamp(p.calfR, 0.70f, 1.45f);
        b.footL = 0.070f * H * BodyProfile.clamp(p.footR, 0.85f, 1.20f);
        b.footW = 0.030f * H * b.girth * BodyProfile.clamp(p.footWR, 0.80f, 1.30f);

        // ---- 关键高度：腿长 / 躯干长 / 颈长 / 臂长可分别缩放 ----
        float leg = BodyProfile.clamp(p.legR, 0.90f, 1.10f);
        float torso = BodyProfile.clamp(p.torsoR, 0.92f, 1.08f);
        float neckLen = BodyProfile.clamp(p.neckLenR, 0.70f, 1.40f);
        float armLen = BodyProfile.clamp(p.armR, 0.88f, 1.12f);

        b.yTop = H;
        b.yChin = H - b.headH;
        b.yCrotch = BodyProfile.clamp(0.475f * H * leg, 0.38f * H, 0.55f * H);
        // 肩高 = 裆高 + 躯干长，再用「颈长」约束出合理区间（颈部不会被躯干挤没）
        b.yShoulder = BodyProfile.clamp(b.yCrotch + 0.345f * H * torso,
                b.yChin - 0.55f * b.headH * neckLen,
                b.yChin - 0.12f * b.headH * neckLen);
        float torsoLen = Math.max(0.10f * H, b.yShoulder - b.yCrotch);
        b.yChest = b.yShoulder - 0.246f * torsoLen;
        b.yWaist = b.yShoulder - 0.565f * torsoLen;
        b.yHip = b.yShoulder - 0.841f * torsoLen;
        b.yKnee = b.yCrotch * 0.579f;
        b.yAnkle = 0.045f * H;
        b.yElbow = b.yShoulder - 0.190f * H * armLen;
        b.yWrist = b.yElbow - 0.143f * H * armLen;
        b.yNeckTop = b.yChin + 0.05f * b.headH;
        b.yNeckBot = b.yShoulder + 0.015f * H;
        b.topW = Math.max(0.035f * H, b.shoulderW - 0.030f * H * b.girth);
        return b;
    }

    public static Result build(BodyProfile p) {
        Result r = build(p, GRID_OVER_HEIGHT);
        // 极端体型（很矮又很胖）表面顶点会逼近 short 索引上限，降一档密度重来一次
        if (r.vertices > MeshBuilder.MAX_VERTICES - 4096) {
            r = build(p, Math.max(110, GRID_OVER_HEIGHT * 3 / 4));
        }
        return r;
    }

    /** @param gridOverHeight 身高方向的体素个数，越大越精细（耗时按立方增长） */
    public static Result build(BodyProfile p, int gridOverHeight) {
        Body b = dims(p);
        float H = b.H;
        MeshBuilder mb = new MeshBuilder();

        // ---------------- 身体：隐式曲面 → 等值面网格 ----------------
        SdfModel sdf = new SdfModel();
        sdf.setBlend(0.018f * H);
        buildBodyField(sdf, p, b);

        float h = H / Math.max(40, gridOverHeight);
        // 包围盒必须留足余量：平滑并集会在关节处外凸，脚也是最靠前 / 最靠后的图元
        float halfX = b.shoulderW + 0.06f * H;
        float halfZ = Math.max(Math.max(Math.max(b.chestD, b.hipD), b.headD), 0.125f * H) + 0.05f * H;
        float ox = -halfX;
        float oz = -halfZ;
        float oy = -0.03f * H;
        int nx = Math.max(8, (int) Math.ceil(2f * halfX / h));
        int ny = Math.max(8, (int) Math.ceil((H + 0.04f * H - oy) / h));
        int nz = Math.max(8, (int) Math.ceil(2f * halfZ / h));

        SurfaceNets.Mesh sm = SurfaceNets.build(sdf, ox, oy, oz, h, nx, ny, nz);
        int[] base = new int[sm.vertexCount];
        for (int i = 0; i < sm.vertexCount; i++) {
            float[] c = rgb(materialColor(sm.materials[i], p));
            base[i] = mb.addVertex(sm.positions[i * 3], sm.positions[i * 3 + 1], sm.positions[i * 3 + 2],
                    sm.normals[i * 3], sm.normals[i * 3 + 1], sm.normals[i * 3 + 2],
                    c[0], c[1], c[2], sm.ao[i]);
        }
        // 模型不穿鞋：脚就是身体的一部分，走肤色并并入 body_skin 的同类材质
        String[] names = {"body_skin", "body_top", "body_bottom"};
        for (int m = 0; m < 3; m++) {
            mb.beginPart(names[m], materialColor(m, p));
            for (int t = 0; t < sm.indices.length; t += 3) {
                int a = sm.indices[t];
                if (sm.materials[a] != m) continue;
                mb.addTriangle(base[a], base[sm.indices[t + 1]], base[sm.indices[t + 2]]);
            }
            mb.endPart();
        }

        // ---------------- 头（含五官与脸部贴图）/ 手 / 发型 ----------------
        HeadMesh.build(mb, p, b);
        buildHands(mb, p, b);
        buildHair(mb, p, b);

        // ---------------- 汇总 ----------------
        Result r = new Result();
        List<MeshBuilder.Part> parts = mb.parts();
        r.parts = parts.toArray(new MeshBuilder.Part[0]);
        r.positions = mb.positions();
        r.normals = mb.normals();
        r.colors = mb.colors();
        r.ao = mb.ao();
        r.indices = mb.indices();
        r.vertices = mb.vertexCount();
        r.triangles = r.indices.length / 3;
        r.heightM = H;
        r.shoulderCm = 2f * b.shoulderW * 100f;
        r.chestCm = perimeter(b.chestW, b.chestD) * 100f;
        r.waistCm = perimeter(b.waistW, b.waistD) * 100f;
        r.hipCm = perimeter(b.hipW, b.hipD) * 100f;
        r.inseamCm = b.yCrotch * 100f;
        r.thighCm = perimeter(b.thighR, b.thighR) * 100f;
        r.calfCm = perimeter(b.calfR, b.calfR) * 100f;
        r.footCm = 2f * b.footL * 100f;
        // 臂展 = 2 ×（肩关节到指尖）
        float fingerTip = b.yWrist - 0.088f * H;
        r.armSpanCm = 2f * (b.topW + Math.max(0f, b.yShoulder - fingerTip)) * 100f;
        return r;
    }

    private static int materialColor(int mat, BodyProfile p) {
        switch (mat) {
            case SdfModel.MAT_TOP:
                return p.top;
            case SdfModel.MAT_BOTTOM:
                return p.bottom;
            default:
                return p.skin;
        }
    }

    private static void buildBodyField(SdfModel sdf, BodyProfile p, Body b) {
        float H = b.H;
        float hh = b.headH;

        // 头颅由 HeadMesh 单独生成（体素分辨率表现不出容貌），这里只保留颈部与头部衔接
        sdf.addCapsule(0f, b.yNeckTop, -b.headD * 0.05f,
                0f, b.yNeckBot, 0f,
                b.neckR * 0.85f, b.neckR * 1.15f, SdfModel.MAT_SKIN);

        // 肩部斜方肌（横跨两肩的一条胶囊，撑起肩线）
        sdf.addCapsule(-b.topW * 0.92f, b.yShoulder + 0.005f * H, -b.chestD * 0.10f,
                b.topW * 0.92f, b.yShoulder + 0.005f * H, -b.chestD * 0.10f,
                0.026f * H * b.girth, 0.026f * H * b.girth, SdfModel.MAT_TOP);

        // 躯干：肩 → 胸 → 腰 → 臀 连续插值出的一条截面管道（见 buildTorso）
        buildTorso(sdf, b);

        // 腹部：脂肪越多越前凸（不跟着四肢的肌肉量走）
        if (b.fat > 1.02f) {
            float r = 0.026f * H + 0.080f * H * (b.fat - 1f);
            sdf.addSphere(0f, b.yWaist + 0.010f * H, b.waistD * 0.45f, r, SdfModel.MAT_TOP);
        }
        // 胸部：女性按 bustR 撑起上装轮廓，胸型决定底盘宽窄 / 隆起深浅 / 下坠多少，
        // 男性只保留一点胸肌厚度
        // 这团椭球原来核心放在 chestD*0.55、横向半径 bust：量出来的增量剖面是
        //   x=0(胸骨) 0.1cm → x=6 3.6 → x=12 5.0 → x=14 4.3
        // 乳线处几乎为 0、外侧鼓到 5cm —— 两坨各自往外凸，中间一道深沟，沟边一圈硬折
        // 就是截图里那两块带阴影的凸起。高分引擎同一段是
        //   0.710H：x=0 1.9 → x=6 2.7 → x=12 3.9（一面平缓的胸，乳沟很浅）
        // 差别有三个：核心太靠后（露出的是球的边缘一小片，不是穹顶）、横向半径太小
        // （两团够不着中线，混合带填不平乳沟）、前后半径太大（顺着胸廓的曲率在外侧
        // 鼓得更高）。改成：核心前移到 0.72 个半深、横向放大 1.15 让两团越过中线互相
        // 混合、前后收 0.80 别在外侧跟着胸廓曲率鼓出去、乳点内移到 0.40 个胸半宽
        // （实测剖面 1.9 / 2.1 / 3.0 / 3.3 / 3.9 / 4.1 / 2.4，与高分基本重合）
        float bust = (b.male ? 0.016f : 0.038f) * H * BodyProfile.clamp(p.bustR, 0.2f, 1.8f);
        float[] bs = BodyProfile.bustShape(p);
        for (int s = -1; s <= 1; s += 2) {
            float bx = s * b.chestW * 0.40f;
            float by = b.yChest + (0.006f + bs[0]) * H;
            sdf.addEllipsoid(bx, by, b.chestD * 0.71f,
                    bust * bs[1] / 0.048f * 1.15f, bust * bs[3] / 0.042f, bust * bs[2] / 0.050f * 0.74f,
                    SdfModel.MAT_TOP);
            if (bs[4] > 0f && !b.male) {         // 下垂：下极再补一团，做出沉下来的办法
                sdf.addEllipsoid(bx * 0.96f, by - bust * 0.55f, b.chestD * 0.66f,
                        bust * bs[1] / 0.048f * 0.85f * 1.15f, bust * bs[3] / 0.042f,
                        bust * bs[2] / 0.050f * 0.80f * 0.85f,
                        SdfModel.MAT_TOP);
            }
        }

        // 骨盆（下装）
        sdf.addEllipsoid(0f, b.yHip, -0.005f * H,
                b.hipW * 0.98f, 0.055f * H, b.hipD * 0.98f, SdfModel.MAT_BOTTOM);
        // 臀：两团球把裤装的后侧撑出来
        for (int s = -1; s <= 1; s += 2) {
            sdf.addSphere(s * b.hipW * 0.46f, b.yHip - 0.038f * H, -b.hipD * 0.60f,
                    0.048f * H * (0.72f + 0.28f * b.fat), SdfModel.MAT_BOTTOM);
        }

        for (int s = -1; s <= 1; s += 2) {
            float sx = s * b.topW;

            // 三角肌
            sdf.addSphere(sx, b.yShoulder - 0.010f * H, 0f, 0.030f * H * b.girth, SdfModel.MAT_TOP);

            // 上臂 / 肘 / 前臂：各拆两段，上臂根部最粗、腕部最细（单根锥形胶囊画不出肘的转折）
            float elbowX = sx + s * 0.012f * H;
            float wristX = sx - s * 0.004f * H;
            float armMidY = (b.yShoulder - 0.015f * H + b.yElbow) * 0.5f;
            float armMidX = (sx + elbowX) * 0.5f;
            sdf.addCapsule(sx, b.yShoulder - 0.015f * H, 0f, armMidX, armMidY, -0.002f * H,
                    b.upperArm * 1.06f, b.upperArm * 0.97f, SdfModel.MAT_SKIN);
            sdf.addCapsule(armMidX, armMidY, -0.002f * H, elbowX, b.yElbow, -0.005f * H,
                    b.upperArm * 0.97f, b.upperArm * 0.83f, SdfModel.MAT_SKIN);
            sdf.addSphere(elbowX, b.yElbow, -0.005f * H, b.foreArm * 0.94f, SdfModel.MAT_SKIN);
            float foreMidY = (b.yElbow + b.yWrist) * 0.5f;
            float foreMidX = (elbowX + wristX) * 0.5f;
            sdf.addCapsule(elbowX, b.yElbow, -0.005f * H, foreMidX, foreMidY, 0.002f * H,
                    b.foreArm * 1.04f, b.foreArm * 0.88f, SdfModel.MAT_SKIN);
            sdf.addCapsule(foreMidX, foreMidY, 0.002f * H, wristX, b.yWrist, 0.008f * H,
                    b.foreArm * 0.88f, b.foreArm * 0.72f, SdfModel.MAT_SKIN);
            sdf.addSphere(wristX, b.yWrist, 0.008f * H, b.foreArm * 0.70f, SdfModel.MAT_SKIN);

            // 大腿 / 膝 / 小腿：大腿两段收进膝盖，小腿上段留出腓肠肌再收到踝
            // 收口 0.78 → 0.70（膝）/ 0.62 → 0.54（踝）：膝与踝不能按「大腿 / 小腿围的固定
            // 比例」给，真人的膝比大腿细 1/3、踝只有小腿肚的一半 —— 按固定比例实测膝 43cm、
            // 踝 25.4cm（真人 35 / 21），腿上下一样粗。收陡之后膝 ≈ 37、踝 ≈ 22
            float hipX = s * b.hipW * 0.46f;
            float kneeX = s * b.hipW * 0.44f;
            float ankleX = s * b.hipW * 0.41f;
            float thighMidY = (b.yHip - 0.005f * H + b.yKnee) * 0.5f;
            float thighMidX = (hipX + kneeX) * 0.5f;
            sdf.addCapsule(hipX, b.yHip - 0.005f * H, 0f, thighMidX, thighMidY, 0.003f * H,
                    b.thighR * 1.06f, b.thighR * 0.95f, SdfModel.MAT_BOTTOM);
            sdf.addCapsule(thighMidX, thighMidY, 0.003f * H, kneeX, b.yKnee, 0.006f * H,
                    b.thighR * 0.95f, b.thighR * 0.70f, SdfModel.MAT_BOTTOM);
            sdf.addSphere(kneeX, b.yKnee, 0.006f * H, b.thighR * 0.72f, SdfModel.MAT_BOTTOM);
            float calfY = b.yKnee - 0.28f * (b.yKnee - b.yAnkle);
            float calfX = kneeX + (ankleX - kneeX) * 0.28f;
            sdf.addCapsule(kneeX, b.yKnee, 0.006f * H, calfX, calfY, -0.004f * H,
                    b.calfR * 1.00f, b.calfR * 1.06f, SdfModel.MAT_BOTTOM);
            sdf.addCapsule(calfX, calfY, -0.004f * H, ankleX, b.yAnkle + 0.012f * H, -0.006f * H,
                    b.calfR * 1.06f, b.calfR * 0.54f, SdfModel.MAT_BOTTOM);
            sdf.addSphere(ankleX, b.yAnkle + 0.014f * H, -0.005f * H, b.calfR * 0.56f, SdfModel.MAT_BOTTOM);

            // 脚：脚掌 + 脚背 + 脚趾 + 脚跟 + 踝口过渡（见 buildFoot）
            buildFoot(sdf, b, ankleX);
        }
    }

    /**
     * 赤脚：脚尖朝 +z、底面贴地 y=0，脚踝落在脚长的后 1/3 处。
     *
     * 人物模型现在统一不穿鞋，于是这里做的是脚本身的形状：压扁的脚掌 + 略隆起的脚背 +
     * 圆缓的脚跟 + 五个脚趾，全部走肤色（MAT_SKIN），也不再有鞋底那层厚板。
     */
    private static void buildFoot(SdfModel sdf, Body b, float x) {
        float H = b.H;
        float ankleZ = -0.006f * H;
        float heelZ = ankleZ - b.footL * 0.58f;
        float toeZ = ankleZ + b.footL * 0.98f;
        float len = toeZ - heelZ;
        float cz = (heelZ + toeZ) * 0.5f;
        float w = b.footW * 0.80f;               // 半宽：赤脚，两脚之间留明显缝隙
        // 脚掌：贴地的压扁椭球（底面留余量，免得被平滑并集拉到地面以下）
        sdf.addEllipsoid(x, 0.014f * H, cz + len * 0.05f, w, 0.014f * H, len * 0.44f,
                SdfModel.MAT_SKIN);
        // 脚背：主体椭球（足弓到脚背的体积）
        sdf.addEllipsoid(x, 0.023f * H, cz + len * 0.06f, w, 0.019f * H, len * 0.42f,
                SdfModel.MAT_SKIN);
        // 脚趾：在脚尖排开
        sdf.addEllipsoid(x, 0.019f * H, toeZ - len * 0.18f, w * 0.86f, 0.015f * H, len * 0.26f,
                SdfModel.MAT_SKIN);
        // 脚跟：圆缓的后半掌
        sdf.addEllipsoid(x, 0.028f * H, heelZ + len * 0.14f, w * 0.66f, 0.024f * H, len * 0.20f,
                SdfModel.MAT_SKIN);
        // 踝关节：小腿接到脚背
        sdf.addCapsule(x, 0.044f * H, cz - len * 0.10f, x, b.yAnkle + 0.006f * H, ankleZ,
                w * 0.78f, b.calfR * 0.56f, SdfModel.MAT_SKIN);
    }

    /**
     * 躯干：沿「肩 → 胸 → 腰 → 臀」插值出连续的截面轮廓，逐段叠一个扁椭球，接成一条躯干管道。
     *
     * 之前用胸 / 上腹 / 腰三个大椭球硬拼，胸腹之间会鼓出不存在的轮廓、腰线也收不进去；
     * 改成插值后，胸廓收进腰、再张开到骨盆的曲线才是真人正视图的样子。
     */
    private static void buildTorso(SdfModel sdf, Body b) {
        float H = b.H;
        float y0 = b.yShoulder + 0.012f * H;
        float y1 = b.yHip - 0.055f * H;
        float hem = b.yWaist - 0.025f * H;      // 上装下摆：腰线略下，以下归裤子
        int n = 12;
        float ry = 0.62f * (y0 - y1) / n;       // 段半高：相邻段必须重叠，平滑并集才接得上
        float[][] keys = torsoKeys(b);
        for (int i = 0; i <= n; i++) {
            float y = y0 + (y1 - y0) * (i / (float) n);
            float[] s = sectionAt(keys, y);
            sdf.addEllipsoid(0f, y, s[2], s[0], ry, s[1],
                    y > hem ? SdfModel.MAT_TOP : SdfModel.MAT_BOTTOM);
        }
    }

    /** 躯干控制点，自上而下：{ 高度, 半宽, 半深, 前后中心 } */
    private static float[][] torsoKeys(Body b) {
        float H = b.H;
        float yMidUpper = (b.yChest + b.yWaist) * 0.5f;
        float yMidLower = (b.yWaist + b.yHip) * 0.5f;
        return new float[][]{
                {b.yShoulder + 0.012f * H, b.shoulderW * 0.92f, b.chestD * 0.80f, -b.chestD * 0.10f},
                {b.yChest, b.chestW, b.chestD, 0f},
                {yMidUpper, mix(b.chestW, b.waistW, 0.72f), mix(b.chestD, b.waistD, 0.72f), b.waistD * 0.04f},
                {b.yWaist, b.waistW, b.waistD, b.waistD * 0.06f},
                {b.yWaist - 0.030f * H, mix(b.waistW, b.hipW, 0.18f), mix(b.waistD, b.hipD, 0.18f), b.waistD * 0.02f},
                {yMidLower, mix(b.waistW, b.hipW, 0.58f), mix(b.waistD, b.hipD, 0.58f), -0.004f * H},
                {b.yHip, b.hipW * 0.98f, b.hipD * 0.98f, -0.005f * H},
                {b.yHip - 0.055f * H, b.hipW * 0.82f, b.hipD * 0.86f, -0.010f * H},
        };
    }

    /** 控制点按高度线性插值，返回 { 半宽, 半深, 前后中心 } */
    private static float[] sectionAt(float[][] k, float y) {
        int n = k.length;
        if (y >= k[0][0]) return new float[]{k[0][1], k[0][2], k[0][3]};
        if (y <= k[n - 1][0]) return new float[]{k[n - 1][1], k[n - 1][2], k[n - 1][3]};
        for (int i = 1; i < n; i++) {
            if (y >= k[i][0]) {
                float f = (k[i - 1][0] - y) / Math.max(1e-6f, k[i - 1][0] - k[i][0]);
                return new float[]{
                        mix(k[i - 1][1], k[i][1], f),
                        mix(k[i - 1][2], k[i][2], f),
                        mix(k[i - 1][3], k[i][3], f)};
            }
        }
        return new float[]{k[n - 1][1], k[n - 1][2], k[n - 1][3]};
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 发际线：前额 / 后颈各落在哪个 s（五官布局空间）。
     * 发壳按这条线铺，头皮着色也按同一条线判 —— 两边错开哪怕一厘米，发壳边缘与头皮
     * 之间露出来的那一线就是肤色，看着头发浮在头上、头顶还秃了一块。
     */
    static float[] hairline(BodyProfile p, float vEnd) {
        float forehead = BodyProfile.clamp(p.foreheadR, 0.75f, 1.25f);
        return new float[]{
                Math.min(0.90f, (0.86f - 0.10f * vEnd) * forehead),
                BodyProfile.clamp(0.62f - 0.35f * (vEnd - 0.5f), 0.38f, 0.66f)};
    }

    /**
     * 发壳：沿头部曲面外扩一层薄壳，发际线随方位角变化 ——
     * 前额最高（露出额头）、两侧在太阳穴附近、后颈最低，因此长发也不会糊住脸。
     */
    private static void buildHairShell(MeshBuilder mb, BodyProfile p, Body b, float vol, float vEnd) {
        float hh = b.headH;
        int nu = 60;
        int nv = 28;
        float gap = hh * 0.022f * (0.55f + 0.45f * vol);
        float[] rim = hairline(p, vEnd);
        float sFront = rim[0];
        float sBack = rim[1];
        float hr = ((p.hair >> 16) & 0xFF) / 255f;
        float hg = ((p.hair >> 8) & 0xFF) / 255f;
        float hb = (p.hair & 0xFF) / 255f;

        int stride = nu + 1;
        int base = mb.vertexCount();
        float[] pos = new float[3];
        float[] nrm = new float[3];
        for (int i = 0; i <= nv; i++) {
            float t = i / (float) nv;
            // 最后一排不是「刚好贴到头皮」而是再沉进去一点：只贴到表面的话，发际线那一圈
            // 从侧面看是一条缝（壳的边缘和头皮之间能塞进一条线），沉进头皮就没有这道缝了
            float e = BodyProfile.clamp((t - 0.80f) / 0.20f, 0f, 1f);
            float fold = 1f - 1.95f * e * e * (3f - 2f * e);
            for (int j = 0; j <= nu; j++) {
                float phi = (float) Math.PI * (2f * j / (float) nu - 1f);
                float sRim = sBack + (sFront - sBack) * (0.5f + 0.5f * (float) Math.cos(phi));
                float s = 1f + (sRim - 1f) * t;
                HeadMesh.pointAt(s, phi, p, b, pos);
                HeadMesh.normalAt(s, phi, p, b, nrm);
                float g = gap * fold;
                if (p.hairStyle == 7) {
                    g += hh * 0.012f * (float) Math.sin(7f * phi) * (float) Math.sin(9f * t * Math.PI);
                }
                mb.addVertex(pos[0] + nrm[0] * g, pos[1] + nrm[1] * g, pos[2] + nrm[2] * g,
                        nrm[0], nrm[1], nrm[2], hr, hg, hb, 1f);
            }
        }
        for (int i = 0; i < nv; i++) {
            for (int j = 0; j < nu; j++) {
                int a0 = base + i * stride + j;
                int b0 = a0 + 1;
                int c0 = base + (i + 1) * stride + j + 1;
                int d0 = base + (i + 1) * stride + j;
                mb.addTriangle(a0, c0, b0);
                mb.addTriangle(a0, d0, c0);
            }
        }
        // 颅顶收口：s=1 那一排是个半径两三厘米的圈而不是一个点，光有这一排的话头顶正中
        // 是通的 —— 从上面 / 前面看就是头顶秃了一块。这里按这一排的实际位置补一个穹顶点
        int pole = mb.vertexCount();
        float cx = 0f, cz = 0f, cy = 0f, rr = 0f;
        for (int j = 0; j < nu; j++) {
            float phi = (float) Math.PI * (2f * j / (float) nu - 1f);
            HeadMesh.pointAt(1f, phi, p, b, pos);
            HeadMesh.normalAt(1f, phi, p, b, nrm);
            cx += pos[0] + nrm[0] * gap;
            cz += pos[2] + nrm[2] * gap;
            cy += pos[1] + nrm[1] * gap;
            rr += (float) Math.hypot(pos[0], pos[2]);
        }
        cx /= nu;
        cy /= nu;
        cz /= nu;
        rr /= nu;
        // 穹顶点抬多高按头顶那一圈的实际曲率算，不按固定比例抬：抬 rr*0.55 的话，真实头壳
        // （s=1 那一排的半径就有五厘米上下）上面会顶出一个两三厘米的包 —— 实测发壳的最高点
        // 比头壳高出 24.6mm，看着是头上又扣了一个壳。取 s=1 与 s=1−ds 两排的半径，反推过
        // 这两点的外接圆，圆心到圆顶就是该抬的高度
        float ds = 0.06f;
        float rr2 = 0f;
        for (int j = 0; j < nu; j++) {
            float phi = (float) Math.PI * (2f * j / (float) nu - 1f);
            HeadMesh.pointAt(1f - ds, phi, p, b, pos);
            rr2 += (float) Math.hypot(pos[0], pos[2]);
        }
        rr2 /= nu;
        float dy = ds * hh;
        float y0 = (rr * rr - rr2 * rr2 - dy * dy) / (2f * dy);
        float rc = (float) Math.sqrt(rr * rr + y0 * y0);
        float apex = BodyProfile.clamp(y0 + rc, gap * 0.8f, rr * 1.20f);
        mb.addVertex(cx, cy + apex, cz, 0f, 1f, 0f, hr, hg, hb, 1f);
        for (int j = 0; j < nu; j++) {
            mb.addTriangle(pole, base + j, base + j + 1);
        }
    }

    /** 手：掌 + 四指 + 拇指（解析图元，比体素分辨率更细腻） */
    private static void buildHands(MeshBuilder mb, BodyProfile p, Body b) {
        float H = b.H;
        for (int s = -1; s <= 1; s += 2) {
            String side = s < 0 ? "_l" : "_r";
            float wristX = s * b.topW - s * 0.004f * H;
            float hy = b.yWrist - 0.026f * H;
            mb.beginPart("hand" + side, p.skin);
            mb.addEllipsoid(wristX, hy, 0.006f * H,
                    0.023f * H * b.girth, 0.028f * H, 0.012f * H * b.girth, 12, 18);
            for (int f = 0; f < 4; f++) {
                float fx = wristX + s * (-0.013f * H + f * 0.0095f * H);
                float len = 0.034f * H + (f == 1 || f == 2 ? 0.006f * H : 0f);
                mb.addTube(new float[]{fx, hy - 0.022f * H, 0.004f * H},
                        new float[]{fx, hy - 0.022f * H - len, 0.001f * H},
                        profile(5, new float[]{0f, 0.55f, 1f},
                                new float[]{0.0058f * H, 0.0050f * H, 0.0044f * H}),
                        profile(5, new float[]{0f, 0.55f, 1f},
                                new float[]{0.0058f * H, 0.0050f * H, 0.0044f * H}),
                        8, true, true);
            }
            mb.addTube(new float[]{wristX - s * 0.016f * H, hy - 0.008f * H, 0.012f * H},
                    new float[]{wristX - s * 0.032f * H, hy - 0.040f * H, 0.018f * H},
                    profile(5, new float[]{0f, 0.55f, 1f},
                            new float[]{0.0068f * H, 0.0058f * H, 0.0050f * H}),
                    profile(5, new float[]{0f, 0.55f, 1f},
                            new float[]{0.0068f * H, 0.0058f * H, 0.0050f * H}),
                    8, true, true);
            mb.endPart();
        }
    }

    static void buildHair(MeshBuilder mb, BodyProfile p, Body b) {
        if (p.hairStyle <= 0) return;      // 光头
        float H = b.H;
        float hh = b.headH;
        float hw = b.headW;
        float hd = b.headD;
        float[] hs = BodyProfile.HAIR[p.hairStyle % BodyProfile.HAIR.length];
        float scale = hs[0];
        float vEnd = hs[1];
        float yTop = b.yTop;

        mb.beginPart("hair", p.hair);
        // 发壳贴合头型，发际线随方位变化（前额高、两侧居中、后颈低），不会遮住五官
        buildHairShell(mb, p, b, scale, vEnd);
        // 刘海单独铺在额前，同样贴着发壳的曲面，压到眉毛上方
        float bangOff = hh * 0.022f * (0.55f + 0.45f * scale) + hh * 0.004f;
        if (p.bangs == 4) {
            buildAirBangs(mb, p, b, bangOff);
        } else if (p.bangs > 0) {
            buildBangs(mb, p, b, bangOff);
        }

        // 发壳之外的这几束（鬓发 / 后发 / 马尾）跟发壳走同一条路 —— 拿实测的头部曲面取点
        // （高分走实测头壳表、原生走自己的头壳曲面），再沿法线让开发壳那层厚度，
        // 根部就贴在发壳上。头壳不是椭圆，按「头半宽 × 半深」的椭圆估计落位会差出一两厘米
        float[] root = new float[3];
        float shellGap = hh * 0.022f * (0.55f + 0.45f * scale);
        switch (p.hairStyle) {
            case 3:     // 中长发：两侧鬓发贴着头皮垂到下颌，末端再自由落下
            case 4:     // 长直发：鬓发 + 后发披到背上
                for (int s = -1; s <= 1; s += 2) {
                    addStrand(mb, p, b, 0.64f, s * 1.42f, 0.24f, s * 1.28f,
                            hw * 0.17f, hw * 0.13f, hd * 0.60f, hd * 0.44f, 5, shellGap, 8);
                    rootAt(root, 0.24f, s * 1.28f, p, b, shellGap);
                    mb.addTube(
                            root,
                            new float[]{root[0] * 0.88f,
                                    yTop - (p.hairStyle == 4 ? 0.98f : 0.86f) * hh, root[2] * 0.60f},
                            cone(8, hw * 0.13f, hw * 0.09f),
                            cone(8, hd * 0.44f, hd * 0.32f),
                            10, false, false);
                }
                if (p.hairStyle == 4) {
                    addStrand(mb, p, b, 0.56f, (float) Math.PI, 0.10f, (float) Math.PI,
                            hw * 0.92f, hw * 1.05f, hh * 0.10f, hh * 0.07f, 5, shellGap, 10);
                    rootAt(root, 0.10f, (float) Math.PI, p, b, shellGap);
                    mb.addTube(
                            root,
                            new float[]{0f, 0.72f * H, root[2] - hd * 0.30f},
                            cone(8, hw * 1.05f, hw * 1.28f),
                            cone(8, hh * 0.07f, hh * 0.05f),
                            12, false, false);
                }
                break;
            case 5:     // 马尾：后脑勺收成一束，再甩到背后
                addStrand(mb, p, b, 0.66f, (float) Math.PI, 0.44f, (float) Math.PI,
                        hh * 0.16f, hh * 0.11f, hh * 0.16f, hh * 0.11f, 3, shellGap, 10);
                rootAt(root, 0.44f, (float) Math.PI, p, b, shellGap);
                mb.addTube(
                        root,
                        new float[]{0f, 0.66f * H, root[2] - hd * 0.62f},
                        profile(12, new float[]{0f, 0.5f, 1f},
                                new float[]{hh * 0.11f, hh * 0.075f, hh * 0.035f}),
                        profile(12, new float[]{0f, 0.5f, 1f},
                                new float[]{hh * 0.11f, hh * 0.075f, hh * 0.035f}),
                        14, false, false);
                break;
            case 6:     // 丸子头：坐在后脑上方的发壳上
                rootAt(root, 0.92f, 2.45f, p, b, shellGap * 0.5f);
                mb.addEllipsoid(root[0], root[1], root[2],
                        hh * 0.17f, hh * 0.15f, hh * 0.17f, 12, 16);
                break;
            default:
                break;
        }
        mb.endPart();
    }

    /**
     * 刘海：额前铺一片，跟着发壳的同一套曲面走（高分走实测头壳表），贴着头皮压到眉毛上方。
     * 三种样式只是下缘随方位变：齐的平、斜的一侧长一侧短、中分中间让开两边垂下来。
     */
    private static void buildBangs(MeshBuilder mb, BodyProfile p, Body b, float off) {
        int nu = 26;
        int nv = 7;
        float sTop = 0.92f;
        float spread = 1.30f;                  // 只铺前方 ±75°
        float[] pos = new float[3];
        float[] nrm = new float[3];
        float hr = ((p.hair >> 16) & 0xFF) / 255f;
        float hg = ((p.hair >> 8) & 0xFF) / 255f;
        float hb = (p.hair & 0xFF) / 255f;
        int stride = nu + 1;
        int base = mb.vertexCount();
        for (int i = 0; i <= nv; i++) {
            float t = i / (float) nv;
            for (int j = 0; j <= nu; j++) {
                float phi = spread * (2f * j / (float) nu - 1f);
                float s = lerp(sTop, bangEdge(phi, p.bangs, spread), t);
                HeadMesh.pointAt(s, phi, p, b, pos);
                HeadMesh.normalAt(s, phi, p, b, nrm);
                mb.addVertex(pos[0] + nrm[0] * off, pos[1] + nrm[1] * off, pos[2] + nrm[2] * off,
                        nrm[0], nrm[1], nrm[2], hr, hg, hb, 1f);
            }
        }
        for (int i = 0; i < nv; i++) {
            for (int j = 0; j < nu; j++) {
                int a0 = base + i * stride + j;
                int b0 = a0 + 1;
                int c0 = base + (i + 1) * stride + j + 1;
                int d0 = base + (i + 1) * stride + j;
                mb.addTriangle(a0, c0, b0);
                mb.addTriangle(a0, d0, c0);
            }
        }
    }

    /**
     * 空气刘海：不是一整片，是几绺分开的薄发束 —— 绺与绺之间露出额头，长短还参差不齐。
     * 齐刘海那一片整铺过去太厚实，看着像扣了顶假发；稀几绺才是「空气」。
     */
    private static void buildAirBangs(MeshBuilder mb, BodyProfile p, Body b, float off) {
        float hh = b.headH;
        // 空气刘海要的就是「密而细」：额前铺一整片细丝。13 绺时每绺根 ~1.0cm、绺间 ~1.3cm 还算分离，
        // 但加到 19 绺后绺间掉到 ~0.9cm、已经小于发丝宽度（~1.0cm），十几绺并成了一整片 —— 既不
        // 「密而细」也没了缝隙。这里改成「更多绺 + 每绺更细」：26 绺、每绺根宽收到 ~0.6cm、梢更细，
        // 绺间 ~0.65cm，既比原来密、又保持一根根分得开的细丝；长短 / 走向照旧拉开参差
        int n = 26;
        float spread = 1.24f;                          // 只铺前方 ±71°
        for (int k = 0; k < n; k++) {
            float u = (k + 0.5f) / n;
            float phi = spread * (2f * u - 1f);
            // 长短参差：靠中间的几绺长一点、两侧短一点，再叠一层交错
            float jag = 0.050f * (float) Math.abs(Math.sin(3.1f * u + 0.6f));
            float mid = 1f - Math.abs(2f * u - 1f);
            float sEnd = 0.710f - 0.045f * mid - jag;
            // 每绺略微斜着走，免得二十几绺平行得像梳子齿
            float phiEnd = phi + 0.16f * (u - 0.5f);
            addStrand(mb, p, b, 0.90f, phi, sEnd, phiEnd,
                    hh * 0.0135f, hh * 0.0095f, hh * 0.006f, hh * 0.004f, 5, off, 6);
        }
    }

    /** 刘海下缘的 s（越小越靠下，盖得越多） */
    private static float bangEdge(float phi, int style, float spread) {
        float a = Math.min(1f, Math.abs(phi) / spread);     // 0=正中 1=两侧
        switch (style) {
            case 1:                                         // 齐刘海：一条平线，两侧略高
                return 0.605f + 0.050f * a;
            case 2:                                         // 斜刘海：一侧盖到眉，另一侧收上去
                return 0.585f + 0.115f * (0.5f + 0.5f * phi / spread);
            case 3:                                         // 中分：中间让开额头，两边垂下来
                return 0.590f + 0.105f * (1f - a);
            default:
                return 0.92f;
        }
    }

    /**
     * 沿头皮铺一条发束：(s0, φ0) → (s1, φ1) 之间分几段取点，每段一小截锥形 tube 接起来。
     * 头皮是曲面，一根直 tube 从头顶拉到下颌的话中段必然离开头皮 ——
     * 那就是头发和头之间那道能塞进 finger 的空隙。
     */
    private static void addStrand(MeshBuilder mb, BodyProfile p, Body b,
                                  float s0, float phi0, float s1, float phi1,
                                  float rx0, float rx1, float rz0, float rz1,
                                  int seg, float off, int ring) {
        float[] a = new float[3];
        float[] c = new float[3];
        for (int i = 0; i < seg; i++) {
            float t0 = i / (float) seg;
            float t1 = (i + 1) / (float) seg;
            rootAt(a, lerp(s0, s1, t0), lerp(phi0, phi1, t0), p, b, off);
            rootAt(c, lerp(s0, s1, t1), lerp(phi0, phi1, t1), p, b, off);
            mb.addTube(a, c,
                    cone(ring, lerp(rx0, rx1, t0), lerp(rx0, rx1, t1)),
                    cone(ring, lerp(rz0, rz1, t0), lerp(rz0, rz1, t1)),
                    4, false, false);
        }
    }

    /** 一段由粗到细的半径曲线 */
    private static float[] cone(int n, float r0, float r1) {
        return profile(n, new float[]{0f, 1f}, new float[]{r0, r1});
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 发束根部的落点：头部曲面上 (s, φ) 那一点，再沿法线让开一层发壳的厚度。
     * 高分走实测头壳表、原生走自己的头壳曲面，两种引擎取到的是同一个相对位置。
     */
    private static void rootAt(float[] out, float s, float phi, BodyProfile p, Body b, float off) {
        HeadMesh.pointAt(s, phi, p, b, out);
        HeadMesh.normalAt(s, phi, p, b, ROOT_N);
        out[0] += ROOT_N[0] * off;
        out[1] += ROOT_N[1] * off;
        out[2] += ROOT_N[2] * off;
    }

    // ------------------------------------------------------------------
    // OBJ 导出：按部件分组，另存同名 .mtl 记录部件颜色
    // ------------------------------------------------------------------

    public static boolean writeObj(File objFile, Result r) {
        if (r == null || objFile == null) return false;
        File parent = objFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + parent);
        }
        String mtlName = objFile.getName();
        if (mtlName.toLowerCase(Locale.US).endsWith(".obj")) {
            mtlName = mtlName.substring(0, mtlName.length() - 4) + ".mtl";
        } else {
            mtlName = mtlName + ".mtl";
        }
        try {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(objFile)));
            w.write("# Device2Device Avatar 3D\n");
            w.write("# height(m)=" + fmt(r.heightM) + " vertices=" + r.vertices
                    + " triangles=" + r.triangles + "\n");
            w.write("mtllib " + mtlName + "\n");

            HashMap<Integer, Integer> map = new HashMap<>();
            int base = 0;
            for (MeshBuilder.Part part : r.parts) {
                map.clear();
                w.write("g " + part.name + "\n");
                w.write("usemtl " + part.name + "\n");
                for (int k = part.start; k < part.start + part.count; k++) {
                    int vi = r.indices[k] & 0xFFFF;
                    if (!map.containsKey(vi)) {
                        map.put(vi, base + map.size());
                        w.write("v " + fmt(r.positions[vi * 3]) + " "
                                + fmt(r.positions[vi * 3 + 1]) + " "
                                + fmt(r.positions[vi * 3 + 2]) + "\n");
                        w.write("vn " + fmt(r.normals[vi * 3]) + " "
                                + fmt(r.normals[vi * 3 + 1]) + " "
                                + fmt(r.normals[vi * 3 + 2]) + "\n");
                    }
                }
                base += map.size();
                for (int k = part.start; k < part.start + part.count; k += 3) {
                    int a = map.get(r.indices[k] & 0xFFFF);
                    int b = map.get(r.indices[k + 1] & 0xFFFF);
                    int c = map.get(r.indices[k + 2] & 0xFFFF);
                    w.write("f " + a + "//" + a + " " + b + "//" + b + " " + c + "//" + c + "\n");
                }
            }
            w.close();

            File mtlFile = new File(objFile.getParentFile(), mtlName);
            BufferedWriter m = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mtlFile)));
            m.write("# Device2Device Avatar 3D materials\n");
            for (MeshBuilder.Part part : r.parts) {
                m.write("newmtl " + part.name + "\n");
                m.write("Kd " + fmt(part.color[0]) + " " + fmt(part.color[1]) + " " + fmt(part.color[2]) + "\n");
                m.write("Ka 0.05 0.05 0.05\n");
                m.write("Ks 0.15 0.15 0.15\n");
                m.write("Ns 20\n");
                m.write("illum 2\n\n");
            }
            m.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "write obj failed", e);
            return false;
        }
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.5f", v);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** rootAt 用的法线暂存（发束根部要沿法线让开发壳厚度） */
    private static final float[] ROOT_N = new float[3];

    /** 沿轴向采样出一条半径曲线：ts 为控制点位置(0~1)，vs 为控制点取值 */
    private static float[] profile(int n, float[] ts, float[] vs) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = interp(ts, vs, (float) i / (n - 1));
        }
        return out;
    }

    private static float interp(float[] ts, float[] vs, float t) {
        if (t <= ts[0]) return vs[0];
        for (int i = 1; i < ts.length; i++) {
            if (t <= ts[i]) {
                float f = (t - ts[i - 1]) / Math.max(1e-6f, ts[i] - ts[i - 1]);
                return vs[i - 1] + (vs[i] - vs[i - 1]) * f;
            }
        }
        return vs[vs.length - 1];
    }

    private static float[] rgb(int argb) {
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f};
    }

    /** 同色系微调明暗，用于鼻/耳与皮肤的层次 */
    private static int shade(int argb, float k) {
        int r = (int) (((argb >> 16) & 0xFF) * k);
        int g = (int) (((argb >> 8) & 0xFF) * k);
        int b = (int) ((argb & 0xFF) * k);
        return 0xFF000000 | (clampi(r) << 16) | (clampi(g) << 8) | clampi(b);
    }

    private static int clampi(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 椭圆周长（Ramanujan 近似），用于三围估算 */
    private static float perimeter(float a, float b) {
        return (float) (Math.PI * (3 * (a + b) - Math.sqrt((3 * a + b) * (a + 3 * b))));
    }
}
