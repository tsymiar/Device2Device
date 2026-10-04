package com.tsymiar.device2device.avatar;

import android.graphics.Bitmap;

/**
 * 头部（颅骨 + 脸 + 颈）的高分辨率参数化网格，以及五官与脸部贴图烘焙。
 *
 * 身体走体素等值面（约 1.1cm 一格），这个分辨率表现不出容貌，所以头部单独按参数化曲面生成：
 * 环向 96 × 纵向 84，纵向剖面给出颅骨 / 颧骨 / 下颌的宽深曲线，
 * 脸型、下颌、颧骨、鼻梁鼻头、唇、下巴都以位移场的形式叠加到基础剖面上；
 * 再把照片裁出的脸部区域按柱面展开烘焙进顶点色，
 * 于是"这张照片的人"会直接长在模型脸上（照片自带的光照会做归一化，避免二次打光）。
 *
 * 坐标约定：面朝 +z，x 向右，y 向上；s 为归一化头高（0=下巴底，1=头顶）。
 */
public final class HeadMesh {

    private static final int NU = 128;  // 环向（正面密、后脑疏）
    private static final int NV = 112;  // 纵向（含下颌以下的颈）

    /**
     * 纵向剖面：{ s, 半宽÷headW, 前半深÷headD, 后半深÷headD }。
     *
     * 前半深这一列原来从下巴的 0.30 一路爬到额头的 0.94 —— 下巴那一圈只剩 3.0cm，比颈
     * 胶囊（neckR·0.85 ≈ 4.7cm，实测颈前在 4.2~5.5cm）还靠后。于是下颌整个埋进脖子里，
     * 纵剖面在 yChin+1cm 处凹出一个最低点（4.30cm，比颈前还退 1.3cm），下巴只剩一个
     * 从凹坑里戳出来的尖 —— 侧面看就是「又尖又缩的下巴」。高分那颗头同一段是单调往前
     * 的（颈 5.35 → 下巴 7.05 → 唇 14.0），中间没有这个坑。
     *
     * 现在把下颌这几档的前半深抬到与颈前相当再往上（0.64 / 0.70 / 0.76），下巴就探到
     * 脖子前面去了（实测 yChin 处 6.2cm，比颈前 4.55 前 1.7cm，与高分那颗头的 1.7cm
     * 一致），纵剖面从脖子到唇单调往前，凹坑没了。
     *
     * 正面看「尖下巴」是另一回事：那是下巴底半宽太窄、整张脸在下巴处收成一个 V 尖点
     * （原剖面 s=0 半宽仅 0.30，约合正面全宽 3.5cm，比颌部 11cm 收去近七成）。把下巴底
     * 两档的半宽放到 0.44 / 0.52（正面全宽约 5.2 / 6.2cm），V 收束变缓、下巴成了圆角，
     * 不再是一个尖。前突本身不动 —— 侧轮廓本来就单调往前、没有尖角。
     */
    private static final float[][] SECTION = {
            {0.00f, 0.44f, 0.70f, 0.32f},
            {0.08f, 0.52f, 0.74f, 0.44f},
            {0.18f, 0.62f, 0.76f, 0.58f},
            {0.28f, 0.76f, 0.81f, 0.72f},
            {0.38f, 0.88f, 0.85f, 0.84f},
            {0.50f, 0.97f, 0.90f, 0.94f},
            {0.60f, 1.00f, 0.94f, 1.00f},
            {0.70f, 0.99f, 0.94f, 1.02f},
            {0.80f, 0.95f, 0.92f, 1.00f},
            {0.88f, 0.88f, 0.86f, 0.92f},
            {0.95f, 0.66f, 0.68f, 0.74f},
            {1.00f, 0.00f, 0.00f, 0.00f},
    };

    /** 脸部贴图覆盖的高度（下巴 → 发际线）与横向范围 */
    private static final float TEX_TOP = 0.90f;
    private static final float TEX_SIN = 0.92f;

    private static final float S_EYE = 0.505f;
    static final float S_BROW = 0.585f;
    private static final float S_LIP = 0.235f;
    /** 鼻尖所在的高度（s）：AnnyModel 拿它当「五官布局 ↔ 真鼻子」的锚点，两边必须用同一个值 */
    static final float S_NOSE_TIP = 0.385f;
    private static final float S_NOSE_BASE = 0.345f;
    private static final float PHI_EYE = 0.4115f;

    private static final float[] FRAME = new float[4];
    private static final float[] DISP = new float[4];
    private static final float[] POS = new float[3];
    private static final float[] NRM = new float[3];

    private HeadMesh() {
    }

    // ------------------------------------------------------------------
    // 脸部贴图（高分引擎复用同一套 (s, φ) → (u, v) 映射）
    // ------------------------------------------------------------------

    /** 一张脸贴图的取样上下文；{@link #open} 返回 null 表示没有贴图 */
    public static final class FaceTex {
        private final int[] px;
        private final int w;
        private final int h;
        private final float[] skin;
        private final float lum;

        private FaceTex(int[] px, int w, int h, float[] skin, float lum) {
            this.px = px;
            this.w = w;
            this.h = h;
            this.skin = skin;
            this.lum = lum;
        }

        /**
         * 取表面参数 (s, φ) 处的最终颜色，写进 out[0..2]，混合权重写进 out[3]。
         * 权重为 0 表示这一点不该带贴图（保持原本肤色）。
         */
        public void sample(float s, float phi, float[] out) {
            float wgt = texWeight(s, phi);
            if (wgt <= 0.001f) {
                out[0] = skin[0];
                out[1] = skin[1];
                out[2] = skin[2];
                out[3] = 0f;
                return;
            }
            float u = clamp(0.5f + 0.5f * (float) Math.sin(phi) / TEX_SIN, 0f, 1f);
            float v = clamp(1f - s / TEX_TOP, 0f, 1f);
            float[] t = samplePx(px, w, h, u, v);
            // 照片自带光照：按亮度归一化后再以 65% 的对比叠加到肤色上
            float tl = Math.max(0.05f, 0.30f * t[0] + 0.59f * t[1] + 0.11f * t[2]);
            float k = lum / tl;
            out[0] = mix(skin[0], skin[0] * (0.35f + 0.65f * clamp(t[0] * k / lum, 0f, 2f)), wgt);
            out[1] = mix(skin[1], skin[1] * (0.35f + 0.65f * clamp(t[1] * k / lum, 0f, 2f)), wgt);
            out[2] = mix(skin[2], skin[2] * (0.35f + 0.65f * clamp(t[2] * k / lum, 0f, 2f)), wgt);
            out[3] = wgt;
        }
    }

    /** 打开一份脸部贴图；没有贴图（或尺寸异常）时返回 null */
    static FaceTex open(BodyProfile p) {
        int[] size = facePixels(p);
        if (size == null) return null;
        int[] buf = new int[size[0] * size[1]];
        p.faceBmp.getPixels(buf, 0, size[0], 0, 0, size[0], size[1]);
        float[] skin = rgb(p.skin);
        float lum = Math.max(0.08f, 0.30f * skin[0] + 0.59f * skin[1] + 0.11f * skin[2]);
        return new FaceTex(buf, size[0], size[1], skin, lum);
    }

    // ------------------------------------------------------------------
    // 头部曲面 + 五官
    // ------------------------------------------------------------------

    public static void build(MeshBuilder mb, BodyProfile p, HumanMesh.Body b) {
        float hh = b.headH;
        float yChin = b.yChin;

        // 颈：略粗于隐式曲面的颈，避免两层面共面闪烁；末端落在颈的中下部，交接处藏在颈里
        float neckR = b.neckR * 1.06f;
        float yEnd = b.yNeckBot + 0.32f * (b.yNeckTop - b.yNeckBot);
        float sEnd = (yEnd - yChin) / Math.max(1e-4f, hh);
        sEnd = Math.min(-0.06f, Math.max(-0.40f, sEnd));

        int nu = NU;
        int nv = NV;
        int stride = nu + 1;
        int n = (nv + 1) * stride;

        float[] px = new float[n];
        float[] py = new float[n];
        float[] pz = new float[n];
        float[] pa = new float[n];
        float[] pc = new float[n * 3];

        int[] face = facePixels(p);
        int fw = face == null ? 0 : face[0];
        int fh = face == null ? 0 : face[1];
        int[] fpx = null;
        if (face != null) {
            fpx = new int[fw * fh];
            if (p.faceBmp != null) {
                p.faceBmp.getPixels(fpx, 0, fw, 0, 0, fw, fh);
            }
        }
        float[] skin = rgb(p.skin);
        float skinLum = Math.max(0.08f, 0.30f * skin[0] + 0.59f * skin[1] + 0.11f * skin[2]);

        for (int i = 0; i <= nv; i++) {
            float s = 1f + (sEnd - 1f) * (i / (float) nv);
            frameAt(s, p, b, neckR, sEnd, FRAME);
            float y0 = FRAME[0];
            float rxw = FRAME[1];
            float rzf = FRAME[2];
            float rzb = FRAME[3];
            for (int j = 0; j <= nu; j++) {
                float phi = azimuth(j / (float) nu);
                float cp = (float) Math.cos(phi);
                float sp = (float) Math.sin(phi);
                float zh = 0.5f * (rzf + rzb) + 0.5f * (rzf - rzb) * cp;

                features(s, phi, p, b, DISP);
                float x = (rxw + DISP[0]) * sp;
                float y = y0 + DISP[1];
                float z = zh * cp + DISP[2] * cp;

                int idx = i * stride + j;
                px[idx] = x;
                py[idx] = y;
                pz[idx] = z;
                pa[idx] = mix(0.82f, 1f, smoothstep(-0.14f, 0.06f, s))
                        * (1f - 0.22f * DISP[3]);

                // ---- 顶点色：肤色 → 照片烘焙 ----
                float r = skin[0];
                float g = skin[1];
                float bl = skin[2];
                float w = 0f;
                if (fpx != null) {
                    w = texWeight(s, phi);
                    if (w > 0.001f) {
                        float u = clamp(0.5f + 0.5f * sp / TEX_SIN, 0f, 1f);
                        float v = clamp(1f - s / TEX_TOP, 0f, 1f);
                        float[] t = samplePx(fpx, fw, fh, u, v);
                        // 照片自带光照：按亮度归一化后再以 65% 的对比叠加到肤色上
                        float lum = Math.max(0.05f, 0.30f * t[0] + 0.59f * t[1] + 0.11f * t[2]);
                        float k = skinLum / lum;
                        r = skin[0] * (0.35f + 0.65f * clamp(t[0] * k / skinLum, 0f, 2f));
                        g = skin[1] * (0.35f + 0.65f * clamp(t[1] * k / skinLum, 0f, 2f));
                        bl = skin[2] * (0.35f + 0.65f * clamp(t[2] * k / skinLum, 0f, 2f));
                    }
                }
                pc[idx * 3] = mix(skin[0], r, w);
                pc[idx * 3 + 1] = mix(skin[1], g, w);
                pc[idx * 3 + 2] = mix(skin[2], bl, w);
            }
        }

        // ---- 法线：网格中心差分 ----
        float[] nx = new float[n];
        float[] ny = new float[n];
        float[] nz = new float[n];
        for (int i = 0; i <= nv; i++) {
            for (int j = 0; j <= nu; j++) {
                int idx = i * stride + j;
                int im = i > 0 ? idx - stride : idx;
                int ip = i < nv ? idx + stride : idx;
                int jm = idx - 1 + (j == 0 ? nu : 0);
                int jp = idx + 1 - (j == nu ? nu : 0);
                float ax = px[ip] - px[im];
                float ay = py[ip] - py[im];
                float az = pz[ip] - pz[im];
                float bx = px[jp] - px[jm];
                float by = py[jp] - py[jm];
                float bz = pz[jp] - pz[jm];
                float cx = ay * bz - az * by;
                float cy = az * bx - ax * bz;
                float cz = ax * by - ay * bx;
                float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (len < 1e-9f) {
                    nx[idx] = 0f;
                    ny[idx] = 1f;
                    nz[idx] = 0f;
                } else {
                    // 参数化本身已保证朝外：n ∝ (r·sinφ, -r·dr/dy, r·cosφ)
                    // （下颌 / 颈部外法线略微朝上，不能用「背离头心」来判方向）
                    nx[idx] = cx / len;
                    ny[idx] = cy / len;
                    nz[idx] = cz / len;
                }
            }
        }

        // ---- 写入网格 ----
        mb.beginPart("head_skin", p.skin);
        int base = mb.vertexCount();
        for (int i = 0; i < n; i++) {
            mb.addVertex(px[i], py[i], pz[i], nx[i], ny[i], nz[i],
                    pc[i * 3], pc[i * 3 + 1], pc[i * 3 + 2], pa[i]);
        }
        for (int i = 0; i < nv; i++) {
            for (int j = 0; j < nu; j++) {
                int a0 = base + i * stride + j;
                int b0 = base + i * stride + j + 1;
                int c0 = base + (i + 1) * stride + j + 1;
                int d0 = base + (i + 1) * stride + j;
                // 绕序：与外法线一致（i 向下、j 环向，需反向缠绕）
                mb.addTriangle(a0, c0, b0);
                mb.addTriangle(a0, d0, c0);
            }
        }

        buildFeatures(mb, p, b, false);
    }

    /**
     * 五官：眉毛 / 眼睛 / 鼻子 / 唇 / 耳。
     *
     * 原生入口是 build（头壳 + 五官）；高分引擎那边的头是 SMPL 网格自带的，只有一层
     * 起伏、没有这些图元，所以另开一个入口按实测的头尺寸补上去（见 AnnyModel.addFaceFeatures）。
     */
    public static void buildFeatures(MeshBuilder mb, BodyProfile p, HumanMesh.Body b,
                                     boolean realFace) {
        buildBrows(mb, p, b);
        buildEyes(mb, p, b);
        // 高分那颗头是自己长出来的真脸，鼻梁 / 鼻翼 / 鼻孔全都在网格上（只是没有颜色）。
        // 再按同一套 s / φ 把鼻孔图元贴上去，等于在鼻头上糊一块深色 —— 看着就是鼻子上的
        // 多余色块。真脸这一路只补网格本身没有的东西：眉毛、眼睛、睫毛、唇色、耳朵
        if (!realFace) buildNose(mb, p, b);
        buildLips(mb, p, b);
        buildEars(mb, p, b);
    }

    // ------------------------------------------------------------------
    // 五官
    // ------------------------------------------------------------------

    private static void buildBrows(MeshBuilder mb, BodyProfile p, HumanMesh.Body b) {
        float hh = b.headH;
        float hd = b.headD;
        mb.beginPart("brow", shade(p.hair, 1.05f));
        float rx = hh * 0.020f * (0.55f + 0.45f * p.browR);
        float rz = hd * 0.050f;
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            // 眉毛是贴着眉骨长的一条：以前是两根直 tube，眉骨是弧面，直管子的中段必然
            // 翘在皮肤外面（眉尾悬空）或者埋进肉里。分几段沿曲面取点接起来，整条都贴着走
            float[] ts = {0.18f, 0.42f, 0.66f};
            float[] ss = {S_BROW + 0.020f, S_BROW + 0.038f, S_BROW - 0.010f};
            int seg = 6;
            float[] a = new float[3];
            float[] c = new float[3];
            for (int k = 0; k < seg; k++) {
                float u0 = k / (float) seg;
                float u1 = (k + 1) / (float) seg;
                browAt(ss, ts, sgn, u0, p, b, a);
                browAt(ss, ts, sgn, u1, p, b, c);
                mb.addTube(a, c,
                        new float[]{rx * (1f - 0.38f * u0), rx * (1f - 0.38f * u1)},
                        new float[]{rz * (1f - 0.30f * u0), rz * (1f - 0.30f * u1)},
                        6, k == 0, k == seg - 1);
            }
        }
        mb.endPart();
    }

    /** 眉形：三个控制点 (s, φ) 的二次贝塞尔，u 从眉头走到眉尾 */
    private static void browAt(float[] ss, float[] ts, int sgn, float u,
            BodyProfile p, HumanMesh.Body b, float[] out) {
        float w0 = (1f - u) * (1f - u);
        float w1 = 2f * u * (1f - u);
        float w2 = u * u;
        pointAt(w0 * ss[0] + w1 * ss[1] + w2 * ss[2],
                sgn * (w0 * ts[0] + w1 * ts[1] + w2 * ts[2]), p, b, out);
    }

    private static void buildEyes(MeshBuilder mb, BodyProfile p, HumanMesh.Body b) {
        float hh = b.headH;
        float hw = b.headW;
        float hd = b.headD;
        float size = clamp(p.eyeSizeR, 0.6f, 1.5f);
        float phi = PHI_EYE * clamp(p.eyeGapR, 0.7f, 1.4f);
        int iris = mixColor(p.hair, 0xFF3A2A1C, 0.55f);

        for (int sgn = -1; sgn <= 1; sgn += 2) {
            float ph = sgn * phi;
            pointAt(S_EYE, ph, p, b, POS);
            normalAt(S_EYE, ph, p, b, NRM);
            float cx = POS[0];
            float cy = POS[1];
            float cz = POS[2];
            float rx = hw * 0.115f * size;
            float ry = hh * 0.030f * size;
            float rz = hd * 0.085f * size;
            // 眼球沉进眼窝：沿法线内推约 1/3 半径
            float in = 0.34f * ry;
            cx -= NRM[0] * in;
            cy -= NRM[1] * in;
            cz -= NRM[2] * in;

            // 虹膜 / 瞳孔整体抬高一点点：正视时上眼睑本来就压住虹膜上缘，落在正中会显得
            // 眼睛在往下看。抬高之后竖直半径按同量收一点，免得黑点从眼球上缘冒出去
            float lift = ry * 0.16f;
            mb.beginPart("eye", 0xFFF6F3EE);
            mb.addEllipsoid(cx, cy, cz, rx, ry, rz, 12, 20);
            mb.endPart();
            // 虹膜 / 瞳孔占眼球的比例：0.46 / 0.20 时眼球露在外面的一圈太大，
            // 正视的照片里眼白比黑眼珠还多，人看着发愣。0.70 / 0.34 大致是真人的比例
            // （虹膜直径 12mm、瞳孔 4mm，配 30mm 宽的眼裂），眼白只留眼角那一点
            mb.beginPart("iris", iris);
            mb.addEllipsoid(cx + NRM[0] * ry * 0.55f, cy + NRM[1] * ry * 0.55f + lift,
                    cz + NRM[2] * ry * 0.55f,
                    rx * 0.70f, ry * 1.02f, rz * 0.62f, 10, 14);
            mb.endPart();
            mb.beginPart("pupil", 0xFF0C0C10);
            mb.addEllipsoid(cx + NRM[0] * ry * 0.95f, cy + NRM[1] * ry * 0.95f + lift,
                    cz + NRM[2] * ry * 0.95f,
                    rx * 0.34f, ry * 0.92f, rz * 0.42f, 8, 12);
            mb.endPart();
            // 上睑线：眼上方一道深色细边，让眼睛"睁开"
            mb.beginPart("lash", shade(p.hair, 0.85f));
            mb.addEllipsoid(cx + NRM[0] * ry * 0.30f, cy + ry * 0.92f, cz + NRM[2] * ry * 0.30f,
                    rx * 1.06f, hh * 0.006f, rz * 0.72f, 8, 16);
            mb.endPart();
        }
    }

    private static void buildNose(MeshBuilder mb, BodyProfile p, HumanMesh.Body b) {
        float hh = b.headH;
        float hw = b.headW;
        mb.beginPart("nostril", shade(p.skin, 0.52f));
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            float ph = sgn * 0.20f * clamp(p.noseWR, 0.6f, 1.5f);
            pointAt(S_NOSE_BASE + 0.012f, ph, p, b, POS);
            normalAt(S_NOSE_BASE + 0.012f, ph, p, b, NRM);
            float r = hw * 0.035f * clamp(p.noseWR, 0.6f, 1.5f);
            mb.addEllipsoid(POS[0] + NRM[0] * r * 0.30f,
                    POS[1] + NRM[1] * r * 0.30f,
                    POS[2] + NRM[2] * r * 0.30f,
                    r * 0.55f, hh * 0.010f, r * 0.80f, 8, 12);
        }
        mb.endPart();
    }

    private static void buildLips(MeshBuilder mb, BodyProfile p, HumanMesh.Body b) {
        float hh = b.headH;
        float hw = b.headW;
        float hd = b.headD;
        int lip = mixColor(p.skin, 0xFFB0544E, 0.55f);
        float w = clamp(p.lipWR, 0.6f, 1.5f);
        float t = clamp(p.lipTR, 0.5f, 1.8f);
        float lr = ((lip >> 16) & 0xFF) / 255f, lg = ((lip >> 8) & 0xFF) / 255f, lb = (lip & 0xFF) / 255f;
        mb.beginPart("lips", lip);
        // 唇是一片贴着脸面的薄片，不是摆上去的两个椭球：脸是弯的、椭球是直的，摆上去嘴角就
        // 翘在脸外面（实测嘴角离脸一厘米，正对着看唇是浮着的）。改成沿 (s, φ) 网格取脸面上的
        // 点，横截面是个小椭圆（沿脸面 ±半高、沿法线 ±半厚），沿嘴宽方向扫过去、两端收到 0：
        // 椭圆的上下缘正好落在脸面上（法向偏移为 0），只有中间鼓出来 —— 唇咬在脸上，嘴角也贴着
        for (int k = 0; k < 2; k++) {
            float sC = k == 0 ? S_LIP + 0.021f : S_LIP - 0.025f;
            float halfW = hw * (k == 0 ? 0.250f : 0.275f) * w;
            pointAt(sC, 0f, p, b, POS);
            float rho = Math.max(1e-3f, (float) Math.hypot(POS[0], POS[2]));
            float phiMax = Math.min(1.15f, halfW / rho);
            float hs = (k == 0 ? 0.017f : 0.021f) * t;      // 半高（s 单位，已按头高归一）
            // 半厚照旧按 hd 取：实测唇最前点要在鼻尖（13.9cm）后面一点，别撅着嘴
            float thk = hd * 0.060f * t;
            int nu = 18, nv = 12;
            int base = mb.vertexCount();
            float[] a1 = new float[3];
            float[] a2 = new float[3];
            for (int i = 0; i <= nv; i++) {
                double ang = 2.0 * Math.PI * i / nv;
                float ca = (float) Math.cos(ang);
                float sa = (float) Math.sin(ang);
                for (int j = 0; j <= nu; j++) {
                    float f = 2f * j / (float) nu - 1f;
                    float phi = phiMax * f;
                    float taper = (float) Math.sqrt(Math.max(0f, 1f - f * f));
                    float s = sC + hs * taper * ca;
                    pointAt(s, phi, p, b, POS);
                    normalAt(s, phi, p, b, NRM);
                    float off = thk * taper * sa;
                    // 法线按椭圆的梯度取（半轴 hs 沿脸面的纵向、thk 沿法线），不是直接拿脸的法线
                    pointAt(Math.min(1f, s + 0.008f), phi, p, b, a1);
                    pointAt(Math.max(-0.4f, s - 0.008f), phi, p, b, a2);
                    float tx = a1[0] - a2[0], ty = a1[1] - a2[1], tz = a1[2] - a2[2];
                    float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                    if (tl > 1e-6f) { tx /= tl; ty /= tl; tz /= tl; }
                    float nx = thk * ca * tx + hs * sa * NRM[0];
                    float ny = thk * ca * ty + hs * sa * NRM[1];
                    float nz = thk * ca * tz + hs * sa * NRM[2];
                    float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (nl > 1e-6f) { nx /= nl; ny /= nl; nz /= nl; }
                    mb.addVertex(POS[0] + NRM[0] * off, POS[1] + NRM[1] * off, POS[2] + NRM[2] * off,
                            nx, ny, nz, lr, lg, lb, 1f);
                }
            }
            int stride = nu + 1;
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
        mb.endPart();
    }

    private static void buildEars(MeshBuilder mb, BodyProfile p, HumanMesh.Body b) {
        float hh = b.headH;
        float hd = b.headD;
        mb.beginPart("ear", shade(p.skin, 0.97f));
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            float ph = sgn * 1.50f;
            // 0.50 → 0.47：耳朵是「眉线到鼻基底」这一段，中心在 0.465 左右，挂在 0.50
            // 上就偏高了一厘米，耳垂离下颌角远、耳顶快到额头
            pointAt(0.47f, ph, p, b, POS);
            normalAt(0.47f, ph, p, b, NRM);
            float out = hd * 0.05f;
            float es = clamp(p.earSizeR, 0.70f, 1.45f);
            mb.addEllipsoid(POS[0] + NRM[0] * out, POS[1] + NRM[1] * out, POS[2] + NRM[2] * out,
                    hd * 0.10f * es, hh * 0.125f * es, hd * 0.165f, 10, 18);
        }
        mb.endPart();
    }

    // ------------------------------------------------------------------
    // 剖面 / 位移场
    // ------------------------------------------------------------------

    /** 某一高度上的头部尺寸：out = { y, 半宽, 前半深, 后半深 }（米） */
    private static void frameAt(float s, BodyProfile p, HumanMesh.Body b,
                                float neckR, float sEnd, float[] out) {
        float hw = b.headW * clamp(p.faceWidthR, 0.75f, 1.35f);
        float hd = b.headD;
        float y = b.yChin + s * b.headH;
        // 脸长：以眉线为界纵向缩放下半张脸（颅顶不动），于是下巴 / 五官整体上下移动
        float fl = clamp(p.faceLenR, 0.88f, 1.12f);
        if (Math.abs(fl - 1f) > 0.002f) {
            float yBrow = b.yChin + 0.60f * b.headH;
            float w = smoothstep(0.75f, 0.55f, s);
            y = yBrow + (y - yBrow) * (1f + (fl - 1f) * w);
        }
        float jk = clamp(b.jaw * p.jawR, 0.55f, 1.35f);

        float[] sec = section(s < 0f ? 0f : s);
        float rxw = hw * sec[0];
        float rzf = hd * sec[1];
        float rzb = hd * sec[2];
        if (s >= 0f) {
            float taper = smoothstep(0.10f, 0.40f, s);
            rxw *= mix(jk, 1f, taper);
            rzf *= mix(0.60f + 0.40f * jk, 1f, taper);
            rzb *= mix(0.70f + 0.30f * jk, 1f, taper);
        } else {
            // 下颌以下过渡到颈
            float k = smoothstep(0f, 1f, -s / Math.max(0.02f, -sEnd));
            rxw = mix(hw * sec[0] * jk, neckR, k);
            rzf = mix(hd * sec[1] * (0.60f + 0.40f * jk), neckR, k);
            rzb = mix(hd * sec[2] * (0.70f + 0.30f * jk), neckR, k);
        }
        out[0] = y;
        out[1] = rxw;
        out[2] = rzf;
        out[3] = rzb;
    }

    /**
     * 五官位移场：out = { 半宽增量, 高度增量, 前向增量, 眼窝凹陷量(0~1) }（米 / 归一化）。
     * 位移按「正面高斯衰减 × 纵向高斯」叠加，天然只作用在脸的对应部位。
     */
    private static void features(float s, float phi, BodyProfile p, HumanMesh.Body b, float[] out) {
        float hh = b.headH;
        float cp = (float) Math.cos(phi);
        if (cp <= 0f) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 0f;
            out[3] = 0f;
            return;
        }
        float noseH = clamp(p.noseHR, 0.6f, 1.5f);
        float noseW = clamp(p.noseWR, 0.6f, 1.5f);
        float dx = 0f;
        float dz = 0f;

        // 鼻梁 + 鼻头 + 鼻翼
        float bridge = gauss(s, 0.545f, 0.075f) * gauss(phi, 0f, 0.30f);
        float tip = gauss(s, S_NOSE_TIP, 0.055f) * gauss(phi, 0f, 0.34f * noseW);
        dz += hh * (0.032f * bridge + 0.092f * tip) * noseH;
        dx += hh * 0.028f * noseW * gauss(s, S_NOSE_BASE + 0.015f, 0.040f)
                * (gauss(phi, 0.34f * noseW, 0.16f) + gauss(phi, -0.34f * noseW, 0.16f));

        // 眉骨
        dz += hh * 0.020f * (0.55f + 0.45f * p.browR) * gauss(s, 0.640f, 0.050f)
                * gauss(phi, 0f, 0.62f);

        // 眼窝（内凹）
        float phiE = PHI_EYE * clamp(p.eyeGapR, 0.7f, 1.4f);
        float socket = gauss(s, S_EYE + 0.010f, 0.055f)
                * (gauss(phi, phiE, 0.24f) + gauss(phi, -phiE, 0.24f));
        dz -= hh * 0.020f * socket;

        // 颧骨
        float cheek = gauss(s, 0.470f, 0.075f)
                * (gauss(phi, 0.78f, 0.26f) + gauss(phi, -0.78f, 0.26f));
        dx += hh * 0.016f * p.cheekR * cheek;
        dz += hh * 0.008f * p.cheekR * cheek;

        // 下巴：纵向 σ 0.065 → 0.090 → 0.100、幅度 0.030 → 0.026 → 0.024。高斯峰处的曲率
        // 半径约 σ²/幅度，原来是 (0.065×头高)²/(0.030×头高) ≈ 2.4cm，再叠上脸面自身的曲率
        // 只剩两点几厘米 —— 侧面看下巴是个尖而不是圆角。放到 0.100 / 0.024 后约 9.6cm，
        // 比真人下巴该有的量级还宽裕一点，怎么调都是圆角。
        // 横向 σ 0.58 → 0.66 → 0.74：正面看下巴不再是一颗纽扣。幅度收的那一点由变宽补回来，
        // 下巴的体量基本不变，只是不再是锥形
        dz += hh * 0.024f * clamp(p.chinR, 0.5f, 1.6f) * gauss(s, 0.085f, 0.100f)
                * gauss(phi, 0f, 0.74f);
        // 唇
        dz += hh * 0.014f * clamp(p.lipTR, 0.5f, 1.8f) * gauss(s, S_LIP, 0.045f)
                * gauss(phi, 0f, 0.42f * clamp(p.lipWR, 0.6f, 1.5f));

        out[0] = dx;
        out[1] = 0f;
        out[2] = dz;
        out[3] = clamp(socket, 0f, 1f);
    }

    /**
     * 取曲面上的点（含五官位移）。
     *
     * 高分引擎走的是实测表（{@code b.skull}）：那颗头是自己的网格，形状本来就齐全，
     * 于是 φ 直接用真方位角、半径取自这一档这一方位上量到的表面 —— 点落在自己的脸面上，
     * 眉贴眉骨、鼻子落在鼻尖、耳朵长在真的耳廓上。
     * 这条路不能叠 {@link #features} 的位移场：那是给理想蛋壳「长出」鼻梁 / 眼窝 / 唇 /
     * 下巴用的，叠在已经有鼻子的真脸上等于在鼻子前面再长一个鼻子（足足两三厘米）。
     */
    static void pointAt(float s, float phi, BodyProfile p, HumanMesh.Body b, float[] out) {
        if (b.skull != null && b.skull.length > 1) {
            float ss = b.faceMap != null ? HumanMesh.mapFaceS(b.faceMap, s) : s + b.skullShift;
            float rho = headRadius(b.skull, ss, phi);
            out[0] = rho * (float) Math.sin(phi);
            out[1] = b.yChin + ss * b.headH;
            out[2] = rho * (float) Math.cos(phi);
            return;
        }
        float neckR = b.neckR * 1.06f;
        float sEnd = -0.15f;
        frameAt(s, p, b, neckR, sEnd, FRAME);
        float cp = (float) Math.cos(phi);
        float sp = (float) Math.sin(phi);
        float zh = 0.5f * (FRAME[2] + FRAME[3]) + 0.5f * (FRAME[2] - FRAME[3]) * cp;
        features(s, phi, p, b, DISP);
        out[0] = (FRAME[1] + DISP[0]) * sp;
        out[1] = FRAME[0] + DISP[1];
        out[2] = zh * cp + DISP[2] * cp;
    }

    /** 曲面外法线（中心差分） */
    static void normalAt(float s, float phi, BodyProfile p, HumanMesh.Body b, float[] out) {
        float e = 0.012f;
        float[] a = new float[3];
        float[] c = new float[3];
        float[] d = new float[3];
        float[] e2 = new float[3];
        pointAt(clamp(s + e, -0.4f, 1f), phi, p, b, a);
        pointAt(clamp(s - e, -0.4f, 1f), phi, p, b, c);
        pointAt(s, phi + e, p, b, d);
        pointAt(s, phi - e, p, b, e2);
        float ux = a[0] - c[0];
        float uy = a[1] - c[1];
        float uz = a[2] - c[2];
        float vx = d[0] - e2[0];
        float vy = d[1] - e2[1];
        float vz = d[2] - e2[2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9f) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 1f;
            return;
        }
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
        if (out[0] * Math.sin(phi) + out[2] * Math.cos(phi) < 0f) {
            out[0] = -out[0];
            out[1] = -out[1];
            out[2] = -out[2];
        }
    }

    /**
     * 实测头壳取样：行 { s, ρ(θ₀), ρ(θ₁), … }，θ 从 −π 起每 2π/NA 一格（见 AnnyModel#measureSkull）。
     * 纵向在相邻两档之间线性插值（档距只有 0.023 个头高），环向在相邻两格之间插值，
     * 于是「这一高度、这一方位」上自己的头壳半径就取出来了。
     */
    private static float headRadius(float[][] sk, float s, float phi) {
        int rows = sk.length;
        int na = sk[0].length - 1;
        int r = 0;
        while (r < rows - 2 && s > sk[r + 1][0]) r++;
        float f = clamp((s - sk[r][0]) / Math.max(1e-6f, sk[r + 1][0] - sk[r][0]), 0f, 1f);
        // φ∈[−π,π] 已经映射到 [0,na]，只需要把 +π 折到 −π 那一格；
        // 不能写成 t -= floor(t) —— 那样 t 恰好落在整数格点上时会被拉回第 0 格
        // （等于把后脑的半径按到每一个刚好在这条线上的方位上去）
        float t = (float) ((phi + Math.PI) / (2.0 * Math.PI) * na);
        if (t >= na) t -= na;
        int k = (int) t;
        float g = t - k;
        int k2 = k + 1;
        if (k2 >= na) k2 -= na;
        float a0 = mix(sk[r][1 + k], sk[r][1 + k2], g);
        float a1 = mix(sk[r + 1][1 + k], sk[r + 1][1 + k2], g);
        return mix(a0, a1, f);
    }

    private static float[] section(float s) {
        for (int i = 1; i < SECTION.length; i++) {
            if (s <= SECTION[i][0]) {
                float[] a = SECTION[i - 1];
                float[] b = SECTION[i];
                float f = (s - a[0]) / Math.max(1e-6f, b[0] - a[0]);
                return new float[]{
                        a[1] + (b[1] - a[1]) * f,
                        a[2] + (b[2] - a[2]) * f,
                        a[3] + (b[3] - a[3]) * f};
            }
        }
        float[] last = SECTION[SECTION.length - 1];
        return new float[]{last[1], last[2], last[3]};
    }

    /** 环向参数：正面密、后脑疏（t=0.5 为正前） */
    private static float azimuth(float t) {
        float a = 2f * t - 1f;
        float m = (a < 0f ? -1f : 1f) * (float) Math.pow(Math.abs(a), 1.55f);
        return (float) Math.PI * m;
    }

    /** 脸部贴图的覆盖范围权重：侧面 / 发际线以上 / 下颌以下淡出 */
    private static float texWeight(float s, float phi) {
        float w = 1f - smoothstep(0.72f, 1.18f, Math.abs(phi));
        w *= 1f - smoothstep(0.76f, 0.92f, s);
        w *= smoothstep(-0.06f, 0.05f, s);
        return clamp(w, 0f, 1f);
    }

    /** 贴图尺寸（{w, h} 放在数组前两位，像素另行取出） */
    private static int[] facePixels(BodyProfile p) {
        Bitmap bmp = p.faceBmp;
        if (bmp == null) return null;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 8 || h < 8 || w > 1024 || h > 1024) return null;
        return new int[]{w, h};
    }

    /** 双线性采样；越界取边缘 */
    private static float[] samplePx(int[] px, int w, int h, float u, float v) {
        float x = clamp(u, 0f, 1f) * (w - 1);
        float y = clamp(v, 0f, 1f) * (h - 1);
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        float fx = x - x0;
        float fy = y - y0;
        float[] c00 = rgb(px[y0 * w + x0]);
        float[] c10 = rgb(px[y0 * w + x1]);
        float[] c01 = rgb(px[y1 * w + x0]);
        float[] c11 = rgb(px[y1 * w + x1]);
        return new float[]{
                mix(mix(c00[0], c10[0], fx), mix(c01[0], c11[0], fx), fy),
                mix(mix(c00[1], c10[1], fx), mix(c01[1], c11[1], fx), fy),
                mix(mix(c00[2], c10[2], fx), mix(c01[2], c11[2], fx), fy)};
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static float gauss(float x, float m, float sd) {
        float t = (x - m) / Math.max(1e-4f, sd);
        return (float) Math.exp(-0.5f * t * t);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = clamp((x - a) / Math.max(1e-6f, b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float[] rgb(int argb) {
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f};
    }

    private static int mixColor(int a, int b, float t) {
        int r = Math.round(mix((a >> 16) & 0xFF, (b >> 16) & 0xFF, t));
        int g = Math.round(mix((a >> 8) & 0xFF, (b >> 8) & 0xFF, t));
        int bl = Math.round(mix(a & 0xFF, b & 0xFF, t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int shade(int argb, float k) {
        int r = Math.round(clamp(((argb >> 16) & 0xFF) * k, 0f, 255f));
        int g = Math.round(clamp(((argb >> 8) & 0xFF) * k, 0f, 255f));
        int b = Math.round(clamp((argb & 0xFF) * k, 0f, 255f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
