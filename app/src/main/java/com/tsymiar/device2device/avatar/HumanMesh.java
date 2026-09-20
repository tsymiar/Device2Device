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
 *   2) 五官 / 手 / 鞋 / 发型：这些细节尺寸小于体素，直接按解析图元生成后叠在身体上。
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
        public float waistCm;
        public float hipCm;
        public float inseamCm;
        public float armSpanCm;
        public float thighCm;   // 大腿围
        public float calfCm;    // 小腿围
        public float footCm;    // 脚长（鞋长）
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
    }

    private HumanMesh() {
    }

    private static Body dims(BodyProfile p) {
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
        b.chestW = (b.male ? 0.096f : 0.090f) * H * b.girth * (0.84f + 0.16f * mus) * p.chestR;
        // 胸围主要改宽度，厚度只跟着小幅变化，避免前后径失真
        b.chestD = (b.male ? 0.070f : 0.066f) * H * b.girth * (1f + (p.chestR - 1f) * 0.75f)
                * (0.92f + 0.08f * mus);
        b.waistW = (b.male ? 0.084f : 0.076f) * H * b.fat * p.waistR;
        b.waistD = (b.male ? 0.061f : 0.056f) * H * b.fat * p.waistR;
        float hipFat = (float) Math.pow(b.fat, 0.75f);
        b.hipW = 0.100f * H * hipFat * p.hipR;
        b.hipD = (b.male ? 0.067f : 0.068f) * H * hipFat * p.hipR;
        b.neckR = (b.male ? 0.032f : 0.029f) * H * b.girth;
        b.upperArm = (b.male ? 0.0245f : 0.0225f) * H * b.girth * (0.72f + 0.28f * mus);
        b.foreArm = (b.male ? 0.0210f : 0.0190f) * H * b.girth * (0.74f + 0.26f * mus);
        // 大腿跟着脂肪走、小腿跟着肌肉走；腿脚围度再由滑块单独放大 / 收细
        b.thighR = (b.male ? 0.0480f : 0.0500f) * H * (float) Math.pow(b.fat, 0.6f)
                * BodyProfile.clamp(p.thighR, 0.70f, 1.45f);
        b.calfR = (b.male ? 0.0320f : 0.0310f) * H * b.girth * (0.76f + 0.24f * mus)
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
        // 包围盒必须留足余量：平滑并集会在关节处外凸，且鞋子是最靠前/后的图元
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
        String[] names = {"body_skin", "body_top", "body_bottom", "body_shoe"};
        for (int m = 0; m < 4; m++) {
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
            case SdfModel.MAT_SHOE:
                return p.shoe;
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
        // 胸部：女性按 bustR 撑起上装轮廓，男性只保留一点胸肌厚度
        float bust = (b.male ? 0.016f : 0.038f) * H * BodyProfile.clamp(p.bustR, 0.2f, 1.8f);
        for (int s = -1; s <= 1; s += 2) {
            sdf.addSphere(s * b.chestW * 0.48f, b.yChest + 0.006f * H, b.chestD * 0.55f,
                    bust, SdfModel.MAT_TOP);
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
            float hipX = s * b.hipW * 0.46f;
            float kneeX = s * b.hipW * 0.44f;
            float ankleX = s * b.hipW * 0.41f;
            float thighMidY = (b.yHip - 0.005f * H + b.yKnee) * 0.5f;
            float thighMidX = (hipX + kneeX) * 0.5f;
            sdf.addCapsule(hipX, b.yHip - 0.005f * H, 0f, thighMidX, thighMidY, 0.003f * H,
                    b.thighR * 1.06f, b.thighR * 0.95f, SdfModel.MAT_BOTTOM);
            sdf.addCapsule(thighMidX, thighMidY, 0.003f * H, kneeX, b.yKnee, 0.006f * H,
                    b.thighR * 0.95f, b.thighR * 0.78f, SdfModel.MAT_BOTTOM);
            sdf.addSphere(kneeX, b.yKnee, 0.006f * H, b.thighR * 0.80f, SdfModel.MAT_BOTTOM);
            float calfY = b.yKnee - 0.28f * (b.yKnee - b.yAnkle);
            float calfX = kneeX + (ankleX - kneeX) * 0.28f;
            sdf.addCapsule(kneeX, b.yKnee, 0.006f * H, calfX, calfY, -0.004f * H,
                    b.calfR * 1.00f, b.calfR * 1.06f, SdfModel.MAT_BOTTOM);
            sdf.addCapsule(calfX, calfY, -0.004f * H, ankleX, b.yAnkle + 0.012f * H, -0.006f * H,
                    b.calfR * 1.06f, b.calfR * 0.62f, SdfModel.MAT_BOTTOM);
            sdf.addSphere(ankleX, b.yAnkle + 0.014f * H, -0.005f * H, b.calfR * 0.64f, SdfModel.MAT_BOTTOM);

            // 脚 / 鞋：鞋底 + 鞋帮 + 鞋头 + 后跟 + 踝口过渡（见 buildFoot）
            buildFoot(sdf, b, ankleX);
        }
    }

    /**
     * 脚 / 鞋：脚尖朝 +z、底面对齐 y=0，脚踝落在鞋长的后 1/3 处。
     *
     * 之前是一个圆角盒，正面看像两块砖；拆成「鞋底薄板 + 鞋帮 + 收窄的鞋头 +
     * 更高的后跟 + 踝口过渡」之后，才有脚背高度、鞋头上翘和后跟的形状，
     * 小腿也不会再和鞋断开一截。
     */
    private static void buildFoot(SdfModel sdf, Body b, float x) {
        float H = b.H;
        float ankleZ = -0.006f * H;
        float heelZ = ankleZ - b.footL * 0.62f;
        float toeZ = ankleZ + b.footL * 1.05f;
        float cz = (heelZ + toeZ) * 0.5f;
        float halfLen = (toeZ - heelZ) * 0.5f;
        // 鞋底：贴地一层薄板
        sdf.addBox(x, 0.013f * H, cz, b.footW * 0.98f, 0.013f * H, halfLen * 0.98f,
                0.010f * H, SdfModel.MAT_SHOE);
        // 鞋帮：包住脚背与脚踝
        sdf.addBox(x, 0.036f * H, cz - halfLen * 0.06f, b.footW * 0.95f, 0.022f * H, halfLen * 0.86f,
                0.014f * H, SdfModel.MAT_SHOE);
        // 鞋头：收窄并压扁
        sdf.addEllipsoid(x, 0.026f * H, cz + halfLen * 0.70f,
                b.footW * 0.80f, 0.020f * H, halfLen * 0.30f, SdfModel.MAT_SHOE);
        // 后跟：比鞋头高一点、略窄
        sdf.addEllipsoid(x, 0.034f * H, cz - halfLen * 0.74f,
                b.footW * 0.86f, 0.026f * H, halfLen * 0.30f, SdfModel.MAT_SHOE);
        // 踝口：小腿末端 → 鞋帮，免得腿与鞋之间断开
        sdf.addCapsule(x, b.yAnkle + 0.012f * H, ankleZ, x, 0.042f * H, cz - halfLen * 0.10f,
                b.calfR * 0.62f, b.footW * 0.90f, SdfModel.MAT_SHOE);
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
                {yMidUpper, mix(b.chestW, b.waistW, 0.62f), mix(b.chestD, b.waistD, 0.62f), b.waistD * 0.04f},
                {b.yWaist, b.waistW, b.waistD, b.waistD * 0.06f},
                {yMidLower, mix(b.waistW, b.hipW, 0.50f), mix(b.waistD, b.hipD, 0.50f), -0.004f * H},
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
     * 发壳：沿头部曲面外扩一层薄壳，发际线随方位角变化 ——
     * 前额最高（露出额头）、两侧在太阳穴附近、后颈最低，因此长发也不会糊住脸。
     */
    private static void buildHairShell(MeshBuilder mb, BodyProfile p, Body b, float vol, float vEnd) {
        float hh = b.headH;
        int nu = 60;
        int nv = 28;
        float gap = hh * 0.022f * (0.55f + 0.45f * vol);
        float forehead = BodyProfile.clamp(p.foreheadR, 0.75f, 1.25f);
        float sFront = Math.min(0.93f, (0.90f - 0.10f * vEnd) * forehead);
        float sBack = BodyProfile.clamp(0.62f - 0.35f * (vEnd - 0.5f), 0.38f, 0.66f);
        float hr = ((p.hair >> 16) & 0xFF) / 255f;
        float hg = ((p.hair >> 8) & 0xFF) / 255f;
        float hb = (p.hair & 0xFF) / 255f;

        int stride = nu + 1;
        int base = mb.vertexCount();
        float[] pos = new float[3];
        float[] nrm = new float[3];
        for (int i = 0; i <= nv; i++) {
            float t = i / (float) nv;
            // 最后一排贴回头皮，形成收口的发际线而不是悬空的壳
            float e = BodyProfile.clamp((t - 0.80f) / 0.20f, 0f, 1f);
            float fold = 1f - e * e * (3f - 2f * e);
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

    private static void buildHair(MeshBuilder mb, BodyProfile p, Body b) {
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
        float cy = b.yChin + hh * 0.54f;

        switch (p.hairStyle) {
            case 3:     // 中长发：两侧鬓发垂到下颌
            case 4:     // 长直发：鬓发 + 后发披到胸口
                for (int s = -1; s <= 1; s += 2) {
                    mb.addTube(
                            new float[]{s * hw * 0.96f, cy + hh * 0.10f, -hd * 0.10f},
                            new float[]{s * hw * 0.82f, yTop - (p.hairStyle == 4 ? 0.98f : 0.86f) * hh, 0f},
                            profile(8, new float[]{0f, 1f}, new float[]{hw * 0.16f, hw * 0.11f}),
                            profile(8, new float[]{0f, 1f}, new float[]{hd * 0.72f, hd * 0.52f}),
                            12, false, false);
                }
                if (p.hairStyle == 4) {
                    mb.addTube(
                            new float[]{0f, cy, -hd * 0.86f},
                            new float[]{0f, 0.72f * H, -hd * 0.95f},
                            profile(8, new float[]{0f, 1f}, new float[]{hw * 0.98f, hw * 1.25f}),
                            profile(8, new float[]{0f, 1f}, new float[]{hh * 0.10f, hh * 0.06f}),
                            14, false, false);
                }
                break;
            case 5:     // 马尾
                mb.addTube(
                        new float[]{0f, yTop - 0.62f * hh, -hd * 0.95f},
                        new float[]{0f, 0.66f * H, -hd * 1.55f},
                        profile(12, new float[]{0f, 1f}, new float[]{hh * 0.11f, hh * 0.035f}),
                        profile(12, new float[]{0f, 1f}, new float[]{hh * 0.11f, hh * 0.035f}),
                        12, false, false);
                break;
            case 6:     // 丸子头
                mb.addEllipsoid(0f, yTop + 0.02f * hh, -hd * 0.45f,
                        hh * 0.17f, hh * 0.15f, hh * 0.17f, 12, 16);
                break;
            default:
                break;
        }
        mb.endPart();
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
