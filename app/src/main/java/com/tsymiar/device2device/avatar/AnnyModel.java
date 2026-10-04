package com.tsymiar.device2device.avatar;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Anny：NAVER LABS 的开源参数化人体模型（Apache-2.0，几何资产来自 MakeHuman 社区，CC0）。
 *
 * 它和 SMPL 那一类模型的区别是不靠扫描数据学隐空间，而是把 MakeHuman 社区几十年积累的
 * 人体测量学知识做成一组「原型形变基」：每个表型参数（性别 / 年龄 / 身高 / 体重 / 肌肉…）
 * 在 [0,1] 上连续取值，按分段多线性插值把这些原形混合，得到静息姿态下的人体网格。
 * 因为参数是有语义的，所以可以像页面里的滑块那样直接解释，也能覆盖从婴幼儿到老年人的体型。
 *
 * 手机端跑不动 PyTorch，因此这里的做法是「离线导出 + 端上求解」：
 *   - tools/export_anny_targets.py（推荐）把 MakeHuman 的形变基本身连同它的依赖一起
 *     导出，端上按 Anny 的公式 w = Π c 相乘累加（见 {@link AnnyTargets}），
 *     与官方实现逐顶点一致；
 *   - tools/export_anny.py 是它的一阶近似：只导出「单个表型在若干插值节点上的位移」，
 *     端上按参数独立加权求和，忽略了表型之间的交叉项，体积更小但形状会失真；
 *   两者都写成 app/src/main/assets/anny/anny.mhb，靠文件头的 version 区分。
 *   - 端上随后再做「按人体测量做局部形变」，输出与原生实现同样的
 *     {@link HumanMesh.Result}，于是渲染、导出 OBJ、保存预览图这些框架全部复用。
 *
 * 形变分两层：
 *   1) 表型：性别 / 年龄 / 身高 / 体重 / 肌肉 / 胸型（见 {@link AnnyParams}）；
 *   2) 人体测量形变：Anny 没有直接表型的那些量（腿长、躯干长、颈长、肩 / 胸 / 腰 / 臀围、
 *      腿围、脚长、脸宽脸长），按身高分段做纵向缩放与环向缩放，量纲与页面滑块一致。
 */
public final class AnnyModel {

    private static final String TAG = "AnnyModel";
    /** 模型数据在 assets 下的路径（由 tools/export_anny.py 生成） */
    public static final String ASSET_DIR = "anny";
    public static final String ASSET_NAME = "anny.mhb";

    private static final int MAGIC = 0x594E4E41;          // "ANNY"（小端）
    private static final int VERSION_1 = 1;               // 原型插值（tools/export_anny.py）
    private static final int VERSION_2 = 2;               // 形变基（tools/export_anny_targets.py）
    private static final int FLAG_Q16 = 1;                // 形变基按 int16 量化存储
    private static final int FLAG_TRI32 = 2;              // 索引按 int32 存储

    /** 胸围增量留给胸腔（骨架）的比例，剩下的折算成乳腺体积 */
    private static final float CHEST_RIB_SHARE = 0.30f;
    private static final float CHEST_BUST_SHARE = 0.70f;
    /** 1 单位胸围增量相当于多少单位「丰满度」的乳腺体积（由实测标定） */
    private static final float CHEST_TO_BUST = 5.00f;
    /**
     * 丰满度 1.0 长多少乳腺：0.545 ≈ 一只 B 杯（实测杯差 12.5cm）。
     * Anny 自带的那层胸是平的，基准量为 0 的话 B 杯就是平胸、A 与 AA 无从收起。
     * {@code BUST_SPAN} 是丰满度每 1.0 对应的乳腺量：0.50 使 0.20~2.10 这一段
     * 正好铺得下 AA~G（再往上拉还有余量，但不会一拉就失控）。
     *
     * 这两个数是跟着生长核一起标定的：核从 (1−q²)²(1+q²) 换成 (1−q²)^0.60 之后同样的乳腺
     * 量铺得更开，围度也跟着变 —— 照 0.82 / 0.55 走，AA~G 就全挤在滑块下半段里了。按实测
     * 反解重新配过，丰满度 1.0 才还是一只 B（AA 0.68 ~ G 1.85）。
     *
     * 0.630 → 0.600：乳腺朝体中轴的那一半停掉之后（见 {@link #lateralSpread}），围度不再靠
     * 胸骨前那块被推过去的表皮虚增 —— 同样的乳腺量实测少了一档（丰满度 1.0 从 12.7 掉到
     * 11.0cm）。把铺开的份额补回来（见下面 0.70 → 1.25）之后基准量再降一点，丰满度 1.0
     * 才还是一只 B（半球实测 12.8cm；底盘窄、前突长的胸型同样乳腺量围度更大，圆锥 14.6、
     * 圆盘 13.4、下垂 13.5 —— 罩杯由 bustRForCup 反解，标签不受影响）。
     * 顺带一提：围度改到乳腺最鼓那一圈（见 {@link #bustLevel}）之后，下胸围从 81.7 变成
     * 75.6cm —— 原来那一圈量在乳线偏上，比真人下胸围大了 6cm。
     */
    private static final float BUST_BASE = 0.600f;
    private static final float BUST_SPAN = 0.50f;
    /** 实测头壳表的方位格数（HeadMesh 按「行长 − 1」认规模，不必显式传参） */
    private static final int SKULL_NA = 64;
    /** 实测头壳表的档数：s 从 {@link #SKULL_S0} 到 1.00 */
    private static final int SKULL_ROWS = 49;
    /** 实测头壳表的最低一档（下巴以下一点点，含下颌与颈根） */
    private static final float SKULL_S0 = -0.10f;
    /** 实测头壳的取样余量（米）：邻档之间的线性插值会略微偏内，给发壳留一点富余 */
    private static final float SKULL_MARGIN = 0.0010f;
    /**
     * 臀围标定系数：默认体型（hipR=1）要落在人体测量表上（172/65 男 ≈ 94cm、165/55 女 ≈ 90cm）。
     * 收腿之后再标定过：腿收回体轴后，绕同一圈的皮尺路径会短两厘米出头，系数要相应放大。
     *
     * 女 0.866 → 0.910：0.866 把默认臀部压得比基础网格还小（hip 折算成 −0.134），背视看
     * 臀部是一片浅平的带子、没有圆润的臀峰，不饱满。抬到 0.910 后默认臀部后凸明显、两个
     * 臀峰立起来（默认臀围 90.2 → 92.5cm，仍在 165/55 正常区间里）。
     */
    private static final float[] HIP_CALIB = {0.970f, 0.910f};
    /**
     * 臀围分给「左右两侧」的份额（剩下的给后半深）。
     *
     * 全给后半深的话，正面看髋还是那么宽，臀一大就只是屁股往后翘，腰以下大腿以上那一截
     * （髋侧 / 大转子）跟臀部对不上。给两成给左右，髋侧跟着臀围一起长一起收，前后左右才是
     * 同一个屁股；再多就顶到手臂内界了（髋那一档的可用空间只有一两厘米）。
     */
    private static final float HIP_SIDE_SHARE = 0.12f;

    private static volatile AnnyModel sInstance;
    private static volatile boolean sFailed;

    final int vertexCount;
    final int triangleCount;
    /** 静息姿态、所有表型取 0.5 时的基网格（米，y 向上） */
    final float[] base;
    final short[] tris;
    /** 表型参数名，顺序与形变基一致 */
    final String[] labels;
    /** 每个表型的插值节点（如 0 / 0.5 / 1） */
    final float[][] knots;
    /** basis[参数][节点][3×顶点数]：相对基网格的顶点位移 */
    final float[][][] basis;
    /** v2（形变基）的求解器；v1（原型插值）为 null */
    final AnnyTargets targets;

    private AnnyModel(int vertexCount, int triangleCount, float[] base, short[] tris,
                      String[] labels, float[][] knots, float[][][] basis,
                      AnnyTargets targets) {
        this.vertexCount = vertexCount;
        this.triangleCount = triangleCount;
        this.base = base;
        this.tris = tris;
        this.labels = labels;
        this.knots = knots;
        this.basis = basis;
        this.targets = targets;
    }

    // ------------------------------------------------------------------
    // 载入
    // ------------------------------------------------------------------

    /** 打包的模型数据是否已经就位 */
    public static boolean isAvailable(Context ctx) {
        if (sInstance != null) return true;
        try {
            String[] list = ctx.getAssets().list(ASSET_DIR);
            if (list != null) {
                for (String name : list) {
                    if (ASSET_NAME.equals(name)) return true;
                }
            }
        } catch (IOException ignore) {
            // 目录不存在也是这个分支
        }
        return false;
    }

    /** 取模型（首次调用会读 assets，之后走缓存）；没打包模型数据时返回 null */
    public static AnnyModel get(Context ctx) {
        AnnyModel m = sInstance;
        if (m != null) return m;
        if (sFailed) return null;
        synchronized (AnnyModel.class) {
            if (sInstance != null) return sInstance;
            if (sFailed) return null;
            try (InputStream in = ctx.getAssets().open(ASSET_DIR + "/" + ASSET_NAME)) {
                AnnyModel loaded = read(readAll(in));
                sInstance = loaded;
                return loaded;
            } catch (Throwable t) {
                sFailed = true;
                Log.w(TAG, "anny model unavailable: " + t.getMessage());
                return null;
            }
        }
    }

    /** 也可以从外部文件载入（例如自己导出的一份 anny.mhb） */
    public static AnnyModel load(InputStream in) throws IOException {
        return read(readAll(in));
    }

    /**
     * 装一份外部导出的模型数据并作为缓存（页面里「载入 Anny 模型数据」用），
     * 这样不必重新打包 APK 也能试用高分引擎。
     */
    public static synchronized boolean install(InputStream in) {
        try {
            AnnyModel m = read(readAll(in));
            sInstance = m;
            sFailed = false;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "install anny failed: " + t.getMessage());
            return false;
        }
    }

    static AnnyModel read(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 28 || b.getInt() != MAGIC) throw new IOException("不是 Anny 模型文件");
        int version = b.getInt();
        if (version != VERSION_1 && version != VERSION_2) {
            throw new IOException("版本不支持：" + version);
        }
        int vc = b.getInt();
        int tc = b.getInt();
        int count = b.getInt();                      // v1：表型个数；v2：形变基个数
        int flags = b.getInt();
        int dimCount = b.getInt();                   // v1 里这个位置是 reserved = 0
        b.getInt();                                  // reserved
        if (vc <= 0 || vc > MeshBuilder.MAX_VERTICES || tc <= 0 || count < 0) {
            throw new IOException("模型尺寸非法：" + vc + " 顶点 / " + tc + " 面");
        }
        float[] base = new float[vc * 3];
        for (int i = 0; i < base.length; i++) base[i] = b.getFloat();
        short[] tris = new short[tc * 3];
        if ((flags & FLAG_TRI32) != 0) {
            for (int i = 0; i < tris.length; i++) {
                int v = b.getInt();
                if (v < 0 || v >= vc) throw new IOException("索引越界");
                tris[i] = (short) v;
            }
        } else {
            for (int i = 0; i < tris.length; i++) {
                int v = b.getShort() & 0xFFFF;
                if (v >= vc) throw new IOException("索引越界");
                tris[i] = (short) v;
            }
        }
        if (version == VERSION_2) {
            // 形变基模式：模板 + Σ (Π c) · B，与官方 Anny 逐顶点一致
            AnnyTargets targets = AnnyTargets.read(b, vc, dimCount, count, flags);
            return new AnnyModel(vc, tc, base, tris, null, null, null, targets);
        }

        String[] labels = new String[count];
        float[][] knots = new float[count][];
        float[][][] basis = new float[count][][];
        for (int p = 0; p < count; p++) {
            int len = b.getShort() & 0xFFFF;
            byte[] raw = new byte[len];
            b.get(raw);
            labels[p] = new String(raw, StandardCharsets.UTF_8);
            int kc = b.getInt();
            if (kc < 1) throw new IOException("表型没有插值节点：" + labels[p]);
            float[] kv = new float[kc];
            for (int i = 0; i < kc; i++) kv[i] = b.getFloat();
            knots[p] = kv;
            float[][] bs = new float[kc][];
            for (int k = 0; k < kc; k++) {
                float scale = b.getFloat();
                float[] arr = new float[vc * 3];
                if ((flags & FLAG_Q16) != 0) {
                    for (int i = 0; i < arr.length; i++) arr[i] = b.getShort() * scale / 32767f;
                } else {
                    for (int i = 0; i < arr.length; i++) arr[i] = b.getFloat();
                }
                bs[k] = arr;
            }
            basis[p] = bs;
        }
        return new AnnyModel(vc, tc, base, tris, labels, knots, basis, null);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // 求解
    // ------------------------------------------------------------------

    /** 按 BodyProfile 解出一个等身比例的人体网格 */
    public HumanMesh.Result build(BodyProfile p) {
        float h = Math.max(0.8f, p.heightCm / 100f);
        boolean female = p.gender == 1;
        float[] par = new float[8];
        // 罩杯要的是「有乳腺」与「没有乳腺」两圈之差，所以同一套形变跑两遍：
        // 后一遍关掉乳腺，量出来就是下胸围；只有这样才不用写死「罩杯 ↔ 参数」对照表
        float[] vNoBust = female ? buildCore(p, h, new float[8], false) : null;
        float[] v = buildCore(p, h, par, true);

        // ---- 3) 法线 / 分区配色 ----
        float[] n = new float[v.length];
        AnnyMeasure.computeNormals(v, tris, n);
        AnnyMeasure.orientNormals(v, n);

        // 头盒 / 五官锚点要在配色之前量好：头皮该不该是发色，得看它在不在这条发际线以上
        float[] hb = headBox(v, h, p.headRatio);
        float[] faceMap = faceLandmarks(v, hb);
        boolean hasHair = p.hairStyle > 0;
        float[] rim = hasHair
                ? HumanMesh.hairline(p, BodyProfile.HAIR[p.hairStyle % BodyProfile.HAIR.length][1])
                : null;

        int[] region = new int[vertexCount];
        float[] col = new float[v.length];
        float[] ao = new float[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            float x = v[i * 3];
            float y = v[i * 3 + 1];
            float z = v[i * 3 + 2];
            int r = classify(x, y, z, n[i * 3], n[i * 3 + 1], n[i * 3 + 2], h,
                    hb, faceMap, rim, hasHair);
            region[i] = r;
            int c = colorOf(r, p);
            col[i * 3] = ((c >> 16) & 0xFF) / 255f;
            col[i * 3 + 1] = ((c >> 8) & 0xFF) / 255f;
            col[i * 3 + 2] = (c & 0xFF) / 255f;
            ao[i] = ambient(x, y, z, n[i * 3], n[i * 3 + 1], n[i * 3 + 2], h);
        }

        // ---- 4) 三角形按区域分组：导出 OBJ 时按部件命名与配色 ----
        short[] idx = new short[triangleCount * 3];
        int[] counts = new int[REGION_COUNT];
        int[] triRegion = new int[triangleCount];
        for (int t = 0; t < triangleCount; t++) {
            int a = tris[t * 3] & 0xFFFF;
            int b = tris[t * 3 + 1] & 0xFFFF;
            int c = tris[t * 3 + 2] & 0xFFFF;
            int r = majority(region[a], region[b], region[c]);
            triRegion[t] = r;
            counts[r] += 3;
        }
        int[] start = new int[REGION_COUNT];
        int[] cursor = new int[REGION_COUNT];
        int at = 0;
        for (int r = 0; r < REGION_COUNT; r++) {
            start[r] = at;
            cursor[r] = at;
            at += counts[r];
        }
        for (int t = 0; t < triangleCount; t++) {
            int r = triRegion[t];
            int w = cursor[r];
            idx[w] = tris[t * 3];
            idx[w + 1] = tris[t * 3 + 1];
            idx[w + 2] = tris[t * 3 + 2];
            cursor[r] = w + 3;
        }
        MeshBuilder.Part[] parts = new MeshBuilder.Part[REGION_COUNT];
        for (int r = 0; r < REGION_COUNT; r++) {
            parts[r] = new MeshBuilder.Part(REGION_NAMES[r], start[r], counts[r], rgb(colorOf(r, p)));
        }

        // ---- 5) 汇总（含实测出来的人体测量值）----
        HumanMesh.Result res = new HumanMesh.Result();
        res.positions = v;
        res.normals = n;
        res.colors = col;
        res.ao = ao;
        res.indices = idx;
        res.parts = parts;
        res.vertices = vertexCount;
        res.triangles = triangleCount;
        res.heightM = h;
        res.shoulderCm = AnnyMeasure.shoulderWidth(v, h) * 100f;
        float crotch = AnnyMeasure.crotchHeight(v, idx, h);
        res.inseamCm = crotch * 100f;
        // 乳腺中心被胸型整体上下挪过，量在固定高度上会漏掉整个乳腺（见 bustLevel）
        float tBust = bustLevel(p);
        res.chestCm = cm(robustGirth(v, idx, mappedY(tBust, h, par), h));
        res.underbustCm = vNoBust != null
                ? cm(robustGirth(vNoBust, tris, mappedY(tBust, h, par), h))
                : res.chestCm;
        res.waistCm = cm(AnnyMeasure.girthAt(v, idx, mappedY(0.62f, h, par), 0f));
        // 臀围要在臀部最宽的那一段里取最粗的一圈，不能固定在 0.53H（纵向比例动过之后
        // hips 会整体上移或下移）。但这一段不能太宽：早先是从 0.50 取到 0.62，实测
        //   · 0.50H 那一圈是「裆上方、两条大腿并在一起」的轮廓，皮尺从大腿外缘绕过去，
        //     臀围滑块拉到 0.70 也降不下去（92.7cm，跟 1.00 的 93.7 几乎一样）；
        //   · 0.62H 就是腰线，腰围拉粗时这一圈最大，臀围读数直接变成了腰围（116cm）。
        // 收到 0.515~0.555：只覆盖臀大肌那一圈，大腿在下、小肚子在上，都进不来
        float hipMax = 0f;
        for (int s2 = 0; s2 <= 4; s2++) {
            float ty = 0.515f + 0.010f * s2;
            hipMax = Math.max(hipMax, AnnyMeasure.girthAt(v, idx, mappedY(ty, h, par), 0f));
        }
        res.hipCm = cm(hipMax > 0f ? hipMax : AnnyMeasure.girthAt(v, idx, mappedY(0.53f, h, par), 0f));
        // 腿围要在裆部以下量：那时切片上刚好是两条腿各成一个环
        res.thighCm = cm(AnnyMeasure.legGirth(v, idx, crotch - 0.03f * h, h));
        res.calfCm = cm(AnnyMeasure.legGirth(v, idx, crotch * 0.38f, h));
        res.footCm = AnnyMeasure.footLength(v, h) * 100f;
        res.armSpanCm = AnnyMeasure.armSpan(v, h) * 100f;
        // 追加部件（发型 / 五官）必须在量头之前别加：发型会往后拖出十厘米，之后量就偏了。
        // 头盒与五官锚点在配色之前已经量过，这里直接复用
        bakeFaceTexture(res.positions, res.colors, p, hb, faceMap);
        float[][] skull = measureSkull(res.positions, hb);
        return addFaceFeatures(addHair(res, p, h, hb, skull, faceMap), p, h, hb, skull, faceMap);
    }

    /**
     * 把脸部照片烤到高分模型的头上：原生引擎是在 HeadMesh.build 里做的，高并发这一路
     * 之前完全没走 —— 于是载入照片没反应，点「清除脸部贴图」自然也毫无变化。
     *
     * 高分这颗头是真网格，没有断层式参数曲面，所以按同一套（下巴为 0、头顶为 1 的 s，
     * 连同绕头一圈的 φ）直接柱面投影过去，映射关系和原生引擎完全一致。
     */
    private static void bakeFaceTexture(float[] pos, float[] col, BodyProfile p, float[] box,
            float[] faceMap) {
        HeadMesh.FaceTex tex = HeadMesh.open(p);
        if (tex == null) return;
        float hh = Math.max(1e-3f, box[0] - box[1]);
        float zc = box[4];
        float[] out = new float[4];
        for (int i = 0; i < col.length; i += 3) {
            float x = pos[i];
            float y = pos[i + 1];
            float z = pos[i + 2] - zc;
            // 贴图是按五官布局那套 s 裁的（下巴 0、颅顶 1、眉线 0.585），这颗头的脸
            // 比布局矮一大截，所以先换算回布局的 s，照片的眉眼才对得上线框的眉眼
            float s = faceMap != null
                    ? HumanMesh.unmapFaceS(faceMap, (y - box[1]) / hh) : (y - box[1]) / hh;
            if (s < -0.04f || s > 0.90f) continue;          // 贴图只覆盖下颌到额头的这一段
            float rho = (float) Math.sqrt(x * x + z * z);
            if (rho < 1e-4f) continue;
            tex.sample(s, (float) Math.atan2(x, z), out);
            if (out[3] <= 0f) continue;
            col[i] = out[0];
            col[i + 1] = out[1];
            col[i + 2] = out[2];
        }
    }

    /**
     * 某一线上的躯干周长：取上下各 8mm 三点里接近的中值。
     *
     * 只切一个平面的话，遇上「三角面几乎躺平」的高度，交点会散成一堆 2cm 的碎环，
     * 或者把腋窝那一段串进躯干环里，读数一下跳 20cm —— 三点取中值就稳了。
     */
    private static float robustGirth(float[] v, short[] idx, float y, float h) {
        float a = AnnyMeasure.girthAt(v, idx, y - 0.008f * h, 0f);
        float b = AnnyMeasure.girthAt(v, idx, y, 0f);
        float c = AnnyMeasure.girthAt(v, idx, y + 0.008f * h, 0f);
        if (a <= 0f && c <= 0f) return Math.max(0f, b);
        if (a <= 0f) return b > 0f ? Math.min(b, c) : c;
        if (c <= 0f) return b > 0f ? Math.min(a, b) : a;
        if (b <= 0f) return Math.min(a, c);
        return Math.min(Math.max(a, b), Math.max(Math.min(a, b), c));
    }

    /**
     * 乳腺最鼓的那一圈在哪个高度（相对身高）。
     *
     * 胸型把乳腺中心整体上下挪了 {@code BUST[i][0]}（下垂挪下 2.4% 身高、圆盘挪上 0.8%），
     * 而胸围原先钉死在 0.72H 上量 —— 下垂那一档的软尺落在乳腺上极的尾巴上，丰满度拉到
     * 2.10 也只读出 9cm，罩杯永远卡在 AA/A 之间，滑块等于没用。
     *
     * 基准取 0.710H 而不是乳点所在的 0.72H：乳腺下极比上极长（ryV 下极 1.00、上极 0.65），
     * 实测最鼓的那一圈比乳点低 1% 身高（圆盘 0.720、半球 / 圆锥 0.710、水滴 0.700、
     * 下垂 0.670~0.680）。下垂再往下补一点。
     *
     * 这里按胸型直接算而不是去扫几档取最大：扫一次要 14 次 robustGirth（每档 3 刀），
     * 反解一次罩杯要算七轮，实测把 bustRForCup 从 154ms 拖到 326ms —— 而这一圈的位置
     * 只由胸型决定，跟丰满度几乎无关，扫出来的结果与下面这个式子差不到半档。
     */
    private static float bustLevel(BodyProfile p) {
        float[] bs = BodyProfile.bustShape(p);
        return 0.710f + bs[0] - 0.012f * bs[4];
    }

    /**
     * build 的前半段：表型 → 纵向比例 → 环向缩放 → 头脸 → 对齐身高。
     *
     * 返回的是「可以直接量围度」的顶点。withBust = false 时跳过乳腺，
     * 量出来的那一圈就是下胸围 —— 罩杯（胸围 − 下胸围）建立在这个差值上。
     */
    private float[] buildCore(BodyProfile p, float h, float[] par, boolean withBust) {
        float[] v = base.clone();

        // ---- 1) 表型 ----
        if (targets != null) {
            // 形变基：w = Π c，与官方 Anny 一致（含表型之间的交叉项）
            AnnyParams params = AnnyParams.fromProfile(p, targets.dims);
            targets.addTo(v, params.values);
        } else {
            // 原型插值：各表型独立加权求和（一阶近似）
            AnnyParams params = AnnyParams.fromProfile(p, labels);
            for (int i = 0; i < labels.length; i++) {
                float[] w = knotWeights(knots[i], params.values[i]);
                for (int k = 0; k < w.length; k++) {
                    float wk = w[k];
                    if (wk > -1e-6f && wk < 1e-6f) continue;
                    float[] bs = basis[i][k];
                    for (int j = 0; j < v.length; j++) v[j] += wk * bs[j];
                }
            }
        }

        AnnyMeasure.normalize(v, h);
        AnnyMeasure.faceForward(v);

        // ---- 2) 人体测量形变：Anny 没有直接表型的那些量 ----
        applyProportions(v, p, h, par);
        // A-pose 的叉腿收回来，女性尤其明显；量出来的腿轴要交给围度形变当缩放轴
        float[][] legAxis = applyStance(v, p, h);
        applyGirth(v, p, h, par, withBust, legAxis);
        healToeSide(v, h);                           // 小趾补回脚掌，填平外侧那道缺口
        smoothToes(v, h);                            // 分开建模的五根脚趾抹成一整只脚
        applyHead(v, p, h);
        applyFace(v, p, h);
        AnnyMeasure.normalize(v, h);                 // 纵向比例动过之后再对齐一次身高
        return v;
    }

    /**
     * 选定罩杯：把「想要的杯差（胸围 − 下胸围，cm）」换算成丰满度 bustR。
     *
     * 两点探测 + 线性外推 —— 乳腺体积到围度的增益随身高 / 胸型 / 胸围而变，
     * 只有实测才准，写死对照表会在换参数之后失真。只跑两遍「表型 + 比例 + 缩放 + 头脸」，
     * 不算法线 / 配色 / AO / 发型，量级小于一次正式 build，但仍然要放在后台线程。
     */
    public float bustRForCup(BodyProfile p0, float targetCm) {
        return bustRForDelta(p0, targetCm);
    }

    /**
     * 界面上展示的杯差（cm）：胸围 − 下胸围，就是实测到的那一点。
     *
     * 以前还要再加一个「网格自带乳房 = B 杯」的锚点，实测那层是平的（详见
     * BodyProfile#BUST_CUP_CM 的说明），锚点纯属凭空多出来的 12.5cm —— 于是把
     * 「平的」报成 B 杯，A / AA 也只能在同一片平胸上打转。乳腺现在全由丰满度长出，
     * 实测差本身就是杯差，不必再假设什么。
     */
    public static float cupCm(HumanMesh.Result r) {
        return r.chestCm - r.underbustCm;
    }

    /**
     * 把「想要的杯差（cm）」换算成丰满度。
     *
     * 体积 → 围度的增益不是严格线性的（体重大、胸型尖的人拉同一个 bustR 长得更快），
     * 所以只用两点做一次线性外推不够准：这里是割线迭代 —— 每算出一个候选就实测一次，
     * 用新的一对点继续逼近，最多三轮。每次迭代要两遍 buildCore（有乳腺 / 没乳腺），
     * 量级远小于一次正式 build，但仍然必须放在后台线程。
     */
    private float bustRForDelta(BodyProfile p0, float deltaCm) {
        float h = Math.max(0.8f, p0.heightCm / 100f);
        float lo = 0.20f;
        float hi = 2.10f;
        // 杯差曲线不是严格单调的：围度是靠「横切一刀取轮廓」量出来的，某一档上轮廓
        // 偶尔会少串一小段（实测丰满度 0.70 那一档反而比 0.65 小 1.9cm）。纯割线迭代
        // 撞进这种凹陷就停错地方 —— 目标 B 杯（12.5cm）会被解到 14.6cm 那一档上。
        // 所以先在整段上粗扫四档、再在旁边做割线，全程记住误差最小的那个候选
        float bestR = lo;
        float bestE = Float.MAX_VALUE;
        float rA = lo;
        float dA = cupDelta(p0, rA, h);
        bestE = Math.abs(dA - deltaCm);
        float rB = hi;
        float dB = cupDelta(p0, rB, h);
        if (Math.abs(dB - deltaCm) < bestE) {
            bestE = Math.abs(dB - deltaCm);
            bestR = rB;
        }
        for (int k = 1; k < 4; k++) {
            float rC = lo + (hi - lo) * k / 4f;
            float dC = cupDelta(p0, rC, h);
            float e = Math.abs(dC - deltaCm);
            if (e < bestE) {
                bestE = e;
                bestR = rC;
            }
            rA = rB;
            dA = dB;
            rB = rC;
            dB = dC;
        }
        for (int k = 0; k < 3; k++) {
            if (Math.abs(dB - dA) < 1e-3f) break;
            float rC = BodyProfile.clamp(rA + (deltaCm - dA) * (rB - rA) / (dB - dA), lo, hi);
            float dC = cupDelta(p0, rC, h);
            float e = Math.abs(dC - deltaCm);
            if (e < bestE) {
                bestE = e;
                bestR = rC;
            }
            rA = rB;
            dA = dB;
            rB = rC;
            dB = dC;
        }
        return bestR;
    }

    /** 某个丰满度下的杯差（cm） */
    private float cupDelta(BodyProfile p0, float bustR, float h) {
        BodyProfile p = p0.copy();
        p.bustR = bustR;
        float[] par = new float[8];
        float[] vb = buildCore(p, h, new float[8], false);
        float[] vw = buildCore(p, h, par, true);
        // 与 build 里报出来的杯差必须是同一个量法：同一条高度（乳腺最鼓那一圈）、同一个
        // robustGirth。以前这里量的是固定 0.72H 的单刀轮廓，反解出来的丰满度套到正式
        // build 上就对不上（G 杯解出来只报 22cm）
        float y = mappedY(bustLevel(p), h, par);
        return cm(robustGirth(vw, tris, y, h)) - cm(robustGirth(vb, tris, y, h));
    }

    /**
     * Anny 头部的实测包围盒：{ yTop, yChin, 半宽, 半深, z 中心 }，用来给发型落位。
     *
     * 半深必须拿头自己的前后中点来算：整颗头是挂在体轴前方的（z 中心几厘米），以前用
     * max(zf, -zb) 当半深，把这个偏移也算进去了 —— procedural 头壳凭空深出 4cm，
     * 发壳与五官于是一起浮在脸外面。
     */
    private static float[] headBox(float[] v, float h, float headRatio) {
        float top = 0f;
        for (int i = 1; i < v.length; i += 3) top = Math.max(top, v[i]);
        float hh = h / Math.max(4f, headRatio);
        float yChin = top - hh;                       // 下巴约在头顶往下一个头高处
        float yLine = yChin + hh * 0.30f;             // 只在下颌以上量头宽
        float browLine = yChin + hh * 0.62f;          // 只在眉以上量头深 / 中心，剃开鼻子与下巴
        float hw = 0f, zf = -9f, zb = 9f;
        for (int i = 0; i < v.length; i += 3) {
            float y = v[i + 1];
            if (y >= yLine) hw = Math.max(hw, Math.abs(v[i]));
            if (y < browLine) continue;
            zf = Math.max(zf, v[i + 2]);
            zb = Math.min(zb, v[i + 2]);
        }
        if (hw <= 1e-4f) hw = 0.075f * h;
        if (zf < zb) { zf = 0.09f * h; zb = -0.09f * h; }
        float zc = (zf + zb) * 0.5f;
        return new float[]{top, yChin, hw * 1.02f, Math.max(1e-4f, zf - zc) * 1.02f, zc};
    }

    /**
     * 实测头壳：把头部按「高度档 × 方位角」量成一张径向半径表。
     *
     * 高分引擎的头是 MakeHuman 的真头型 —— 脸是扁的、后脑是圆的、鼻梁上还鼓出来一块，
     * 拿「半宽 × 前半深 × 后半深」凑出来的椭圆截面去近似它，误差动辄一两厘米，
     * 按比例放大把椭圆包在外面，五官就跟着一起浮到脸外面去了（既有前后偏差、也有左右偏差）。
     * 这里干脆把表面本身量下来：每档高度上按方位角分成 {@link #SKULL_NA} 个格，
     * 取格内的最远点 —— HeadMesh 照这张表取点，取出来的就是自己的头皮。
     *
     * 行格式：{ s, ρ(θ₀), ρ(θ₁), … }，θ 从 −π 起逆时针每 2π/NA 一格（0 = 正前）。
     */
    private static float[][] measureSkull(float[] v, float[] box) {
        float hh = Math.max(1e-3f, box[0] - box[1]);
        float zc = box[4];
        int rows = SKULL_ROWS;
        int na = SKULL_NA;
        float sLo = SKULL_S0;
        float step = (1f - sLo) / (rows - 1);
        float[][] sk = new float[rows][na + 1];
        for (int r = 0; r < rows; r++) {
            sk[r][0] = sLo + step * r;
            for (int k = 1; k <= na; k++) sk[r][k] = -1f;      // -1 = 这一格还没取到点
        }
        // 1) 取样：每个顶点的半径落进「方位格 × 相邻三档」里取最大值。
        //    纵向要多铺一档，是因为单个高度带里常常凑不满一圈，取到的多半是零星几点
        for (int i = 0; i < v.length; i += 3) {
            float s = (v[i + 1] - box[1]) / hh;
            if (s < sLo - step || s > 1f + step) continue;
            float x = v[i];
            float z = v[i + 2] - zc;
            float rho = (float) Math.sqrt(x * x + z * z);
            if (rho < 1e-4f) continue;
            int r0 = (int) Math.round((s - sLo) / step);
            int k = 1 + (int) (((float) Math.atan2(x, z) + (float) Math.PI)
                    / (2f * (float) Math.PI) * na);
            if (k > na) k -= na;
            // 纵向铺开到左右各一档
            for (int r = Math.max(0, r0 - 1); r <= Math.min(rows - 1, r0 + 1); r++) {
                if (sk[r][k] < rho) sk[r][k] = rho;
            }
        }
        // 1.5) 记下「这一格真被顶点踩到过没有」：下面的插值 / 平滑只能顺形状，最后拿它兜底
        float[][] hit = new float[rows][na];
        for (int r = 0; r < rows; r++) {
            for (int k = 0; k < na; k++) hit[r][k] = sk[r][1 + k];
        }
        // 2) 先按环向补齐：同一档上空缺的方位，由左右最近的两个有值方位按弧长线性插值。
        //    相邻方位的形状是接近的，插值出来的值自然落在自己的头皮上；反过来先补高度
        //    就要跨过后脑那个沿高度的凸峰，等于拿切线代替弧线，从里面穿过去（实测差 1.9cm）
        for (int r = 0; r < rows; r++) {
            float[] src = sk[r].clone();
            int have = 0;
            for (int k = 1; k <= na; k++) if (src[k] >= 0f) have++;
            if (have == 0) continue;
            for (int k = 0; k < na; k++) {
                if (src[1 + k] >= 0f) continue;
                int kL = -1;
                int kR = -1;
                for (int d = 1; d < na && (kL < 0 || kR < 0); d++) {
                    int a = (k - d + na) % na;
                    int c = (k + d) % na;
                    if (kL < 0 && src[1 + a] >= 0f) kL = a;
                    if (kR < 0 && src[1 + c] >= 0f) kR = c;
                }
                if (kL < 0 || kR < 0) continue;
                int dL = (k - kL + na) % na;
                int dR = (kR - k + na) % na;
                sk[r][1 + k] = (src[1 + kL] * dR + src[1 + kR] * dL) / (float) (dL + dR);
            }
        }
        // 3) 再沿高度补齐：每个方位自己是一列，空缺的高度由上下两档插值（两端平推）
        for (int k = 1; k <= na; k++) {
            int pr = -1;
            for (int r = 0; r < rows; r++) {
                if (sk[r][k] < 0f) continue;
                if (pr < 0) {
                    for (int q = 0; q < r; q++) sk[q][k] = sk[r][k];
                } else if (r > pr + 1) {
                    for (int q = pr + 1; q < r; q++) {
                        float f = (q - pr) / (float) (r - pr);
                        sk[q][k] = sk[pr][k] + (sk[r][k] - sk[pr][k]) * f;
                    }
                }
                pr = r;
            }
            if (pr < 0) {
                for (int r = 0; r < rows; r++) sk[r][k] = 0.05f * hh;
            } else {
                for (int r = pr + 1; r < rows; r++) sk[r][k] = sk[pr][k];
            }
        }
        // 4) 环向抹一遍：取最大值会把个别突出的顶点带进来，留下的是锯齿
        for (int pass = 0; pass < 2; pass++) {
            for (int r = 0; r < rows; r++) {
                float[] tmp = new float[na];
                for (int k = 0; k < na; k++) {
                    int k2 = k + 1;
                    if (k2 >= na) k2 -= na;
                    int k0 = k - 1;
                    if (k0 < 0) k0 += na;
                    tmp[k] = 0.25f * sk[r][k0 + 1] + 0.5f * sk[r][k + 1] + 0.25f * sk[r][k2 + 1];
                }
                for (int k = 0; k < na; k++) sk[r][k + 1] = tmp[k];
            }
        }
        // 5) 纵向膨胀：每列再把相邻三档的最大值取一遍。
        //    取点是「两档之间线性插值」，等于拿切线代替弧线 —— 头顶曲率最大，吃亏也最大，
        //    弦一带就插到曲面里侧去，于是头皮从这里戳穿发壳（实测 0.8cm）。先膨胀一档，
        //    插值出来的表就一定在自己的头皮外面
        for (int k = 1; k <= na; k++) {
            float[] col = new float[rows];
            for (int r = 0; r < rows; r++) {
                int up = Math.max(0, r - 1);
                int dn = Math.min(rows - 1, r + 1);
                col[r] = Math.max(sk[r][k], Math.max(sk[up][k], sk[dn][k]));
            }
            for (int r = 0; r < rows; r++) sk[r][k] = col[r];
        }
        // 6) 兜底：平滑会把孤立的大值拉到邻居的水平 —— 后脑就靠那几个方位的少数
        //    顶点撑着，一轮平滑能把它从 9.3cm 压到 7.3cm，头皮于是从后脑戳穿发壳。
        //    这里让每一格回到不低于「自己那一格采到的最大值（含相邻两档）」的水平：
        //    既保证包在外面，又不会因为邻居大而被一起抬高（浮空不至于失控）
        for (int k = 0; k < na; k++) {
            for (int r = 0; r < rows; r++) {
                float m = -1f;
                for (int q = Math.max(0, r - 2); q <= Math.min(rows - 1, r + 2); q++) {
                    if (hit[q][k] > m) m = hit[q][k];
                }
                if (m > sk[r][1 + k]) sk[r][1 + k] = m;
            }
        }
        // 7) 取样余量：邻档之间的插值会略微偏内，给发壳留 1mm
        for (int r = 0; r < rows; r++) {
            for (int k = 1; k <= na; k++) sk[r][k] = Math.max(1e-4f, sk[r][k]) + SKULL_MARGIN;
        }
        return sk;
    }

    /**
     * 五官布局的锚点：把这颗头自己的鼻尖量成 s（下巴 0、颅顶 1）。
     *
     * 光做整体平移（以前的 faceShift）是不够的：Anny 这颗头鼻尖在 s=0.353，而布局里是
     * 0.385 —— 平移够鼻尖，唇（0.235）就顶到鼻尖上；平移够唇，眉眼又跑到额头上去。
     * 所以改成以鼻尖为界分段拉伸：下半张脸按 sNose/0.385 压、上半张脸按
     * (1−sNose)/0.615 拉，两端仍然严格对住下巴与颅顶。
     * 只量鼻尖这一个锚点：整颗头最前的那一点，怎么量都稳；眉弓在这颗头上是道几乎看不出
     * 的缓坡（实测纵向剖面从鼻尖往上一直在退），拿它当锚点只会把眼睛压到鼻尖旁边。
     */
    private static float[] faceLandmarks(float[] v, float[] box) {
        float hh = Math.max(1e-3f, box[0] - box[1]);
        float yChin = box[1];
        float mid = 0.055f * hh;                    // 只取正中线附近，避开耳朵与脸侧
        float zTip = -Float.MAX_VALUE;
        float yTip = 0f;
        for (int i = 0; i < v.length; i += 3) {
            float y = v[i + 1];
            if (y < yChin || y > yChin + 0.60f * hh) continue;      // 下巴往上六成，再高是额头
            if (Math.abs(v[i]) > mid) continue;
            if (v[i + 2] > zTip) {
                zTip = v[i + 2];
                yTip = y;
            }
        }
        if (zTip == -Float.MAX_VALUE) return null;
        return new float[]{BodyProfile.clamp((yTip - yChin) / hh, 0.20f, 0.55f)};
    }

    /**
     * 发型 / 五官这类「追加部件」都以头部实测包围盒与实测头壳表为基准落位。
     */
    private static HumanMesh.Body headBody(BodyProfile p, float[] box, float h,
            float[][] skull, float[] faceMap) {
        HumanMesh.Body b = HumanMesh.dims(p);
        b.H = h;
        b.headH = Math.max(1e-3f, box[0] - box[1]);
        b.headW = box[2];
        b.headD = box[3];
        b.yTop = box[0];
        b.yChin = box[1];
        b.skull = skull;
        b.faceMap = faceMap;
        return b;
    }

    /**
     * 发型：Anny 的网格里没有头发，所以把原生引擎那一套发壳 / 鬓发 / 马尾 / 丸子头
     * 按实测出来的头尺寸落位，再并入同一个 Result；光头（0）直接跳过。
     */
    private static HumanMesh.Result addHair(HumanMesh.Result body, BodyProfile p, float h,
            float[] box, float[][] skull, float[] faceMap) {
        if (p.hairStyle <= 0) return body;
        MeshBuilder mb = new MeshBuilder();
        HumanMesh.buildHair(mb, p, headBody(p, box, h, skull, faceMap));
        return appendParts(body, mb, box[4]);
    }

    /**
     * 五官：Anny 只有头壳，眼耳口鼻都是一层起伏，看着五官发平。
     * 这里按同一个头部包围盒把原生那套图元补上去 —— 表情和贴图还是网格自己的。
     */
    private static HumanMesh.Result addFaceFeatures(HumanMesh.Result body, BodyProfile p, float h,
            float[] box, float[][] skull, float[] faceMap) {
        MeshBuilder mb = new MeshBuilder();
        HeadMesh.buildFeatures(mb, p, headBody(p, box, h, skull, faceMap), true);
        return appendParts(body, mb, box[4]);
    }

    /**
     * 把一串追加网格并回 Result：顶点 / 法线 / 颜色 / AO 接在数据末尾，索引按 short 重算，
     * 三角面属于哪个部件也照搬过来（五官自己带着眉 / 眼 / 瞳 / 唇等各自的材质色）。
     */
    private static HumanMesh.Result appendParts(HumanMesh.Result body, MeshBuilder mb, float zShift) {
        int nv = mb.vertexCount();
        int ni = mb.indexCount();
        if (nv <= 0 || ni <= 0 || body.vertices + nv > MeshBuilder.MAX_VERTICES) return body;

        int bv = body.vertices;
        int bt = body.triangles;
        float[] po = Arrays.copyOf(body.positions, (bv + nv) * 3);
        float[] no = Arrays.copyOf(body.normals, (bv + nv) * 3);
        float[] co = Arrays.copyOf(body.colors, (bv + nv) * 3);
        float[] ao = body.ao != null ? Arrays.copyOf(body.ao, bv + nv) : null;
        System.arraycopy(mb.positions(), 0, po, bv * 3, nv * 3);
        // 头部整体挂在体轴前方，追加的部件要跟着过去，否则会跑到后脑勺后面
        if (zShift != 0f) {
            for (int i = 0; i < nv; i++) po[(bv + i) * 3 + 2] += zShift;
        }
        System.arraycopy(mb.normals(), 0, no, bv * 3, nv * 3);
        System.arraycopy(mb.colors(), 0, co, bv * 3, nv * 3);
        float[] ma = mb.ao();
        if (ao != null && ma != null) System.arraycopy(ma, 0, ao, bv, nv);
        short[] mi = mb.indices();
        short[] idx = new short[bt * 3 + ni];
        System.arraycopy(body.indices, 0, idx, 0, bt * 3);
        for (int i = 0; i < ni; i++) idx[bt * 3 + i] = (short) ((mi[i] & 0xFFFF) + bv);

        List<MeshBuilder.Part> src = mb.parts();
        MeshBuilder.Part[] parts = new MeshBuilder.Part[body.parts.length + src.size()];
        System.arraycopy(body.parts, 0, parts, 0, body.parts.length);
        for (int i = 0; i < src.size(); i++) {
            MeshBuilder.Part sp = src.get(i);
            parts[body.parts.length + i] =
                    new MeshBuilder.Part(sp.name, sp.start + bt * 3, sp.count, sp.color);
        }

        HumanMesh.Result r = new HumanMesh.Result();
        r.positions = po;
        r.normals = no;
        r.colors = co;
        r.ao = ao;
        r.indices = idx;
        r.parts = parts;
        r.vertices = bv + nv;
        r.triangles = bt + ni / 3;
        r.heightM = body.heightM;
        r.shoulderCm = body.shoulderCm;
        r.inseamCm = body.inseamCm;
        r.chestCm = body.chestCm;
        r.underbustCm = body.underbustCm;
        r.waistCm = body.waistCm;
        r.hipCm = body.hipCm;
        r.thighCm = body.thighCm;
        r.calfCm = body.calfCm;
        r.footCm = body.footCm;
        r.armSpanCm = body.armSpanCm;
        return r;
    }

    // ------------------------------------------------------------------
    // 形变
    // ------------------------------------------------------------------

    /** applyProportions 的分段参数：{leg, torso, neck, yCrotch, yShoulder, yChin, below, mid} */
    private static final int P_LEG = 0;
    private static final int P_TORSO = 1;
    private static final int P_NECK = 2;
    private static final int P_YC = 3;
    private static final int P_YS = 4;
    private static final int P_YCHIN = 5;
    private static final int P_BELOW = 6;
    private static final int P_MID = 7;

    private static void applyProportions(float[] v, BodyProfile p, float h, float[] par) {
        float leg = BodyProfile.clamp(p.legR, 0.90f, 1.10f);
        float torso = BodyProfile.clamp(p.torsoR, 0.92f, 1.08f);
        float neck = BodyProfile.clamp(p.neckLenR, 0.70f, 1.40f);
        float headH = h / Math.max(4f, p.headRatio);
        float yCrotch = 0.475f * h;
        float yShoulder = 0.82f * h;
        float yChin = h - headH;
        float below = yCrotch * leg;
        float mid = below + (yShoulder - yCrotch) * torso;
        if (par != null) {
            par[P_LEG] = leg;
            par[P_TORSO] = torso;
            par[P_NECK] = neck;
            par[P_YC] = yCrotch;
            par[P_YS] = yShoulder;
            par[P_YCHIN] = yChin;
            par[P_BELOW] = below;
            par[P_MID] = mid;
        }
        for (int i = 0; i < v.length; i += 3) {
            float y = v[i + 1];
            float ny;
            if (y <= yCrotch) {
                ny = y * leg;
            } else if (y <= yShoulder) {
                ny = below + (y - yCrotch) * torso;
            } else if (y <= yChin) {
                ny = mid + (y - yShoulder) * neck;
            } else {
                ny = mid + (yChin - yShoulder) * neck + (y - yChin);
            }
            v[i + 1] = ny;
        }
    }

    /**
     * 脚趾修形：Anny 的脚把五根脚趾各自建模成一截，趾缝之间留着缝、小趾那一侧还单独鼓出一坨 ——
     * 从外面看着就是「脚丫外侧多出一块」。这里在脚踝以下做几轮 Taubin 平滑把轮廓接顺：
     * 只跑一遍 Laplacian 整只脚会缩水，正负成对（λ, μ）才能在抹平趾缝的同时保住体积。
     * 脚底贴着地面的那几个点锁住不动（免得脚底板卷起来站不进地面），强度沿高度渐隐到踝以上为零。
     */
    private void smoothToes(float[] v, float h) {
        int n = vertexCount;
        int[] cnt = new int[n];
        for (int t = 0; t < tris.length; t += 3) {
            int a = tris[t] & 0xFFFF;
            int b = tris[t + 1] & 0xFFFF;
            int c = tris[t + 2] & 0xFFFF;
            if (a != b) { cnt[a]++; cnt[b]++; }
            if (b != c) { cnt[b]++; cnt[c]++; }
            if (c != a) { cnt[c]++; cnt[a]++; }
        }
        int[][] adj = new int[n][];
        for (int i = 0; i < n; i++) adj[i] = new int[cnt[i]];
        int[] cur = new int[n];
        for (int t = 0; t < tris.length; t += 3) {
            int a = tris[t] & 0xFFFF;
            int b = tris[t + 1] & 0xFFFF;
            int c = tris[t + 2] & 0xFFFF;
            if (a != b) { adj[a][cur[a]++] = b; adj[b][cur[b]++] = a; }
            if (b != c) { adj[b][cur[b]++] = c; adj[c][cur[c]++] = b; }
            if (c != a) { adj[c][cur[c]++] = a; adj[a][cur[a]++] = c; }
        }
        float yTop = 0.075f * h;
        float yMid = 0.035f * h;
        float ySole = 0.005f * h;
        float[] ox = new float[n];
        float[] oy = new float[n];
        float[] oz = new float[n];
        for (int pass = 0; pass < 16; pass++) {
            float lambda = (pass % 2 == 0) ? 0.55f : -0.53f;
            for (int i = 0; i < n; i++) {
                ox[i] = v[i * 3];
                oy[i] = v[i * 3 + 1];
                oz[i] = v[i * 3 + 2];
            }
            for (int i = 0; i < n; i++) {
                float y = oy[i];
                if (y > yTop) continue;
                float fade = smoothstep(1f - (y - yMid) / Math.max(1e-5f, yTop - yMid));
                if (fade <= 1e-4f) continue;
                int[] nb = adj[i];
                int m = nb.length;
                if (m == 0) continue;
                double sx = 0;
                double sy = 0;
                double sz = 0;
                for (int k = 0; k < m; k++) {
                    int j = nb[k];
                    sx += ox[j];
                    sy += oy[j];
                    sz += oz[j];
                }
                float step = lambda * fade;
                float px = ox[i] + (float) (sx / m - ox[i]) * step;
                float pz = oz[i] + (float) (sz / m - oz[i]) * step;
                float py = oy[i];
                if (oy[i] >= ySole) {
                    py = oy[i] + (float) (sy / m - oy[i]) * step;
                    if (py < 0f) py = 0f;
                }
                v[i * 3] = px;
                v[i * 3 + 1] = py;
                v[i * 3 + 2] = pz;
            }
        }
    }

    /**
     * 外侧轮廓补齐：小趾是单独建模的一截，趾根与脚掌之间留着一道沿 z 方向的缺口 ——
     * 俯视图里它就成了「浮在外侧的一块」。这里把每只脚沿脚长切片，取每个切片的外边界，
     * 用滑窗最大值把凹口填平，再把切片的外半边按比例撑到填平后的边界。
     * 内半边（大趾侧）与脚长不动，所以只有那道缺口被补起来。
     */
    private void healToeSide(float[] v, float h) {
        float yLim = 0.042f * h;
        float xMin = 0.005f * h;
        int bins = 48;
        for (int side = 0; side < 2; side++) {
            float sign = side == 0 ? 1f : -1f;
            float zmin = Float.MAX_VALUE;
            float zmax = -Float.MAX_VALUE;
            for (int i = 0; i < vertexCount; i++) {
                if (v[i * 3 + 1] > yLim) continue;
                if (v[i * 3] * sign <= xMin) continue;
                zmin = Math.min(zmin, v[i * 3 + 2]);
                zmax = Math.max(zmax, v[i * 3 + 2]);
            }
            if (zmax - zmin < 0.02f * h) continue;
            float[] inner = new float[bins];
            float[] outer = new float[bins];
            float[] has = new float[bins];
            for (int i = 0; i < bins; i++) {
                inner[i] = Float.MAX_VALUE;
                outer[i] = -Float.MAX_VALUE;
                has[i] = 0f;
            }
            for (int i = 0; i < vertexCount; i++) {
                if (v[i * 3 + 1] > yLim) continue;
                float x = Math.abs(v[i * 3]);
                if (v[i * 3] * sign <= xMin) continue;
                int b = (int) ((v[i * 3 + 2] - zmin) / (zmax - zmin) * (bins - 1));
                b = b < 0 ? 0 : (b >= bins ? bins - 1 : b);
                inner[b] = Math.min(inner[b], x);
                outer[b] = Math.max(outer[b], x);
                has[b] = 1f;
            }
            // 空档由左右邻居补齐
            for (int c = 0; c < 2; c++) {
                float[] arr = c == 0 ? inner : outer;
                int prev = -1;
                for (int i = 0; i <= bins; i++) {
                    if (i == bins) {
                        for (int k = prev + 1; k < bins; k++) arr[k] = prev < 0 ? 0f : arr[prev];
                        break;
                    }
                    if (has[i] < 0.5f) continue;
                    if (prev < 0) {
                        for (int k = 0; k < i; k++) arr[k] = arr[i];
                    } else {
                        for (int k = prev + 1; k < i; k++) {
                            float f = (k - prev) / (float) (i - prev);
                            arr[k] = arr[prev] + (arr[i] - arr[prev]) * f;
                        }
                    }
                    prev = i;
                }
            }
            // 滑窗最大值：把凹口抬到两侧的较高处
            float[] fill = new float[bins];
            for (int i = 0; i < bins; i++) {
                float mx = outer[i];
                for (int k = -3; k <= 3; k++) {
                    int j = i + k;
                    if (j < 0 || j >= bins) continue;
                    mx = Math.max(mx, outer[j]);
                }
                fill[i] = mx;
            }
            for (int i = 0; i < vertexCount; i++) {
                if (v[i * 3 + 1] > yLim) continue;
                float x = v[i * 3] * sign;
                if (x <= xMin) continue;
                int b = (int) ((v[i * 3 + 2] - zmin) / (zmax - zmin) * (bins - 1));
                b = b < 0 ? 0 : (b >= bins ? bins - 1 : b);
                float span = outer[b] - inner[b];
                if (span < 1e-4f || fill[b] <= outer[b] + 1e-5f) continue;
                // 按「到内侧边界的比例」重新铺一遍：内侧（大趾、脚心）不动，外侧撑到填平处
                float k = (x - inner[b]) / span;
                if (k < 0f) k = 0f;
                v[i * 3] = sign * (inner[b] + k * (fill[b] - inner[b]));
            }
        }
    }

    /**
     * 站姿：Anny 的静息姿态是两腿叉开的 A-pose，脚踝各在 ±0.13H 上 —— 直接拿来就是一条
     * 往外撇的 V 字，「女的腿不要太外扩」说的就是它（骨盆本来就宽，脚又撇得更开）。
     *
     * 这里把每条腿整体往体轴收，收的量沿「髋 → 踝」线性加大：髋端为 0（骨盆不动），
     * 踝端收到原生引擎那种自然站姿。裆口一带按高度淡出，两条腿的内侧不会穿模。
     * 这一段的 |x| 上限不必设：它只在 y < 0.52H 生效，而手 / 手臂都在 0.52H 以上。
     *
     * 收完之后腿还必须是直的：每条腿是整块平移（内侧外侧同一个量），目标轴取「大腿根 →
     * 踝」的连线，所以收完腿的中轴正好落在这条线上，膝不会留在连线外侧。
     */
    private float[][] applyStance(float[] v, BodyProfile p, float h) {
        float yHip = 0.52f * h;
        float yAnkle = 0.04f * h;
        float ankleTarget = (p.gender == 1 ? 0.044f : 0.042f) * h;
        int levels = 13;
        float[] ys = new float[levels];
        float[] ax = new float[levels];      // 腿中轴（右腿那一环的环心）
        float[] lo = new float[levels];      // 右腿那一环的最内缘
        float[] hi = new float[levels];      // 最外缘
        for (int i = 0; i < levels; i++) {
            ys[i] = yAnkle + (0.46f * h - yAnkle) * i / (float) (levels - 1);
            // 腿 = 这一高度上周长最大的那一环。不能取 |x| 的内外缘：A-pose 的手垂到髋线
            // 以下（腿长拉到 1.2 时垂得更低），手比腿更靠外，内外缘的中点会被手拽出去
            // 好几厘米，收腿就收歪了。两腿已经并成一圈的高度（裆以上）量不到腿，记为无效
            ax[i] = -1f;
            float best = -1f;
            for (AnnyMeasure.Loop l : AnnyMeasure.loopsAt(v, tris, ys[i])) {
                if (l.cx <= 0.02f * h || l.cx >= 0.25f * h) continue;   // 只看右腿那一条
                if (l.perimeter > best) {
                    best = l.perimeter;
                    // 取内外缘的中点，不是环心：环心是环上那一圈点的平均，而顶点在腿的
                    // 内侧 / 外侧疏密不一样，平均下来会偏到点密的那一侧；眼睛看到的、皮尺
                    // 量到的都是内外缘的中点
                    ax[i] = 0.5f * (l.xMin + l.xMax);
                    lo[i] = l.xMin;
                    hi[i] = l.xMax;
                }
            }
        }
        // 最高的「两腿还分得开」的一档才是大腿根：再往上那一圈是两腿共有的，环心在体中轴
        // 上，拿它当目标会把整条腿收到兜底值上（实测矮个子那一条腿被多收了 2cm，成了 X 型）
        int iTop = 0;
        for (int i = levels - 1; i >= 0; i--) {
            if (ax[i] >= 0f) {
                iTop = i;
                break;
            }
        }
        if (ax[0] < 0f) return new float[][]{ys, ax};       // 连踝都没量到，不动
        float yTop = ys[iTop];
        for (int i = iTop + 1; i < levels; i++) {
            ax[i] = ax[iTop];
            lo[i] = lo[iTop];
            hi[i] = hi[iTop];
        }
        smoothSeq(ax);
        smoothSeq(lo);
        smoothSeq(hi);
        for (int i = iTop + 1; i < levels; i++) {
            ax[i] = ax[iTop];
            lo[i] = lo[iTop];
            hi[i] = hi[iTop];
        }
        float topAxis = ax[iTop];
        for (int j = 0; j < v.length; j += 3) {
            float y = v[j + 1];
            if (y > yHip) continue;
            float x = v[j];
            float axs = Math.abs(x);
            if (axs < 1e-5f) continue;
            float yc = BodyProfile.clamp(y, yAnkle, yTop);
            // 只动落在腿那一圈里的点：手垂在腿的外侧，跟腿的横向范围有重叠，按范围卡一道
            // 才不会把手一起拽过来。脚（踝以下）是整只跟着踝走的，不能按腿的宽窄卡 ——
            // 脚掌比腿宽，卡一下就只有一半脚跟着动，脚会被撕开
            if (y >= 0.05f * h) {
                float m = 0.012f * h;
                if (axs < levelAxis(ys, lo, yc) - m) continue;
                if (axs > levelAxis(ys, hi, yc) + m) continue;
            }
            float t = (yTop - yc) / Math.max(1e-5f, yTop - yAnkle);
            float want = topAxis + (ankleTarget - topAxis) * t;
            // 整条腿一起平移，量可正可负（原来只往里拉、拉的量还按点到中轴的距离打折：
            // 外侧收到位了、内侧几乎没动，腿是斜的 —— 实测膝间隙 8.6cm 比踝间隙 6.9cm
            // 还大，正面看就是 O 型腿）
            float shift = levelAxis(ys, ax, yc) - want;
            if (Math.abs(shift) <= 1e-5f) continue;
            // 裆那一带淡出：这里的点已经是两腿共有的，跟着收会把裆口拉开
            float w = 1f - smoothstep((y - yTop) / Math.max(1e-5f, yHip - yTop));
            v[j] = x - (x >= 0f ? 1f : -1f) * shift * w;
        }
        // 交给围度形变的是「收完之后」的腿轴（就是上面那条目标线），不是收之前量到的那一
        // 条：后者还是 A-pose 的位置（踝在 0.13H 上），拿它当缩放轴，腿一粗就被推到一边去
        for (int i = 0; i < levels; i++) {
            float t = i <= iTop ? (yTop - ys[i]) / Math.max(1e-5f, yTop - yAnkle) : 0f;
            ax[i] = topAxis + (ankleTarget - topAxis) * t;
        }
        return new float[][]{ys, ax};
    }

    /** 某一高度上的腿中轴（只对+\:+侧统计，左右对称） */
    private static float levelAxis(float[] ys, float[] ax, float y) {
        int n = ys.length;
        if (y <= ys[0]) return ax[0];
        if (y >= ys[n - 1]) return ax[n - 1];
        int k = 0;
        while (k < n - 2 && y > ys[k + 1]) k++;
        float f = (y - ys[k]) / Math.max(1e-5f, ys[k + 1] - ys[k]);
        return ax[k] + (ax[k + 1] - ax[k]) * f;
    }

    /**
     * 标准比例上的某个部位 → 动过纵向比例之后的真实高度。
     *
     * 调过腿长 / 躯干长之后，胸就不再正好落在 0.72H；缩放带若仍挂在固定比例上，
     * 胸围 / 腰围 / 臀围就会整体错位。挂到真实高度之后，任意高度都能落到正确的解剖位置。
     */
    private static float mappedY(float tStd, float h, float[] par) {
        float y = tStd * h;
        float yC = par[P_YC];
        float yS = par[P_YS];
        float yChin = par[P_YCHIN];
        if (y <= yC) return y * par[P_LEG];
        if (y <= yS) return par[P_BELOW] + (y - yC) * par[P_TORSO];
        if (y <= yChin) return par[P_MID] + (y - yS) * par[P_NECK];
        return par[P_MID] + (yChin - yS) * par[P_NECK] + (y - yChin);
    }

    /** 以任意绝对高度为中心的高斯带（width 也是米） */
    private static float ring(float y, float centerY, float width) {
        float d = (y - centerY) / Math.max(1e-4f, width);
        return (float) Math.exp(-0.5f * d * d);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * 按关键高度（自下而上）插值出该处的倍率。
     *
     * 之前是把每个部位做成一个高斯叠加带，相邻带之间会互相渗透 —— 调胸围连腰一起鼓、
     * 调臀围连腰一起变；改成关键高度插值之后，倍率只看左右两个关键帧，各滑块不再串味。
     * 线性插值虽然连续，斜率却在关键帧上突变，表面就会现出一圈折角；这里用单调三次插值
     * （Catmull-Rom 取切向 + 反号时归零）把相邻部位之间接成一阶连续。
     */
    private static float bandScale(float y, float[] keyY, float[] keyF) {
        int n = keyY.length;
        if (y <= keyY[0]) return keyF[0];
        if (y >= keyY[n - 1]) return keyF[n - 1];
        int k = 0;
        while (k < n - 2 && y > keyY[k + 1]) k++;
        float len = Math.max(1e-5f, keyY[k + 1] - keyY[k]);
        float t = (y - keyY[k]) / len;
        float m0 = tangent(keyY, keyF, k);
        float m1 = tangent(keyY, keyF, k + 1);
        float t2 = t * t;
        float t3 = t2 * t;
        return (2f * t3 - 3f * t2 + 1f) * keyF[k]
                + (t3 - 2f * t2 + t) * len * m0
                + (-2f * t3 + 3f * t2) * keyF[k + 1]
                + (t3 - t2) * len * m1;
    }

    /** 节点切向：内点取左右斜率的中值，左右异号时归零，免得插值本身鼓出一段不存在的胖瘦 */
    private static float tangent(float[] keyY, float[] keyF, int k) {
        int n = keyY.length;
        if (k <= 0) return (keyF[1] - keyF[0]) / Math.max(1e-5f, keyY[1] - keyY[0]);
        if (k >= n - 1) {
            return (keyF[n - 1] - keyF[n - 2]) / Math.max(1e-5f, keyY[n - 1] - keyY[n - 2]);
        }
        float sLo = (keyF[k] - keyF[k - 1]) / Math.max(1e-5f, keyY[k] - keyY[k - 1]);
        float sHi = (keyF[k + 1] - keyF[k]) / Math.max(1e-5f, keyY[k + 1] - keyY[k]);
        return sLo * sHi <= 0f ? 0f : (sLo + sHi) * 0.5f;
    }

    /**
     * 基础网格（形变前）在给定高度上的绝对半宽 / 半深。
     * 分箱取「箱里最远的那个点」会系统性偏小 —— 网点是稀的，薄片里最远的点往往离真实
     * 轮廓还有一两厘米，而且各档偏得不一样多，反解出来的倍率就带着波浪（实测同一条
     * 轮廓相邻两档能差 1.2cm）。这里改成：先取窗口内最外侧那一小片点，再用二次曲线
     * 拟合这条「外包络」，在该高度上取值 —— 包络点本来就长在表面上，偏差只剩几毫米
     */
    private static float[] baseExtent(float[] v, float h, float[] ys, int mode, float yFloor) {
        // 窗口要窄：±5cm 的窗口会跨过「臀 → 腰」这一段（半宽在 7cm 高度里掉 4cm），
        // 二次拟合落在窗口中心的值于是比真实最外点小 2~3cm —— 倍率是「目标 ÷ 基础」，
        // 基础一偏小倍率就偏大，实测 0.58H 那一圈被撑到 17.4cm，比臀峰还宽。窗口收到
        // ±1.3cm（与锚点间距相当）之后，拟合出的就是这一档自己的外包络
        float win = 0.008f * h;
        float band = 0.010f * h;
        float[] out = new float[ys.length];
        for (int a = 0; a < ys.length; a++) {
            float ya = ys[a];
            float mx = 0f;
            for (int i = 0; i < v.length; i += 3) {
                float y = v[i + 1];
                if (Math.abs(y - ya) > win) continue;
                if (y < yFloor - 0.002f * h) continue;
                float val = extentVal(v, i, mode);
                float lim = AnnyMeasure.isArmLimit(y / h, h);
                // 量深度时也得避开手臂：A-pose 的手正好落在臀那一档的高度上，而它离体中轴
                // 有 30cm 前后（比躯干半深的两倍还多），量进去臀那一档的半深就翻了近三倍，
                // 铺出来的 S 会在这附近反解出 0.66 的倍率 —— 侧面就是一道 V 形凹口
                if (lim > 0f && Math.abs(v[i]) > lim * 0.94f) continue;
                if (val > mx) mx = val;
            }
            float s0 = 0f, s1 = 0f, s2 = 0f, s3 = 0f, s4 = 0f;
            float t0 = 0f, t1 = 0f, t2 = 0f;
            for (int i = 0; i < v.length; i += 3) {
                float y = v[i + 1];
                if (Math.abs(y - ya) > win) continue;
                if (y < yFloor - 0.002f * h) continue;
                float val = extentVal(v, i, mode);
                float lim = AnnyMeasure.isArmLimit(y / h, h);
                if (lim > 0f && Math.abs(v[i]) > lim * 0.94f) continue;
                if (val < mx - band) continue;                        // 只取最外侧那一小片
                float u = (y - ya) / win;
                s0 += 1f;
                s1 += u;
                s2 += u * u;
                s3 += s2 * u;
                s4 += s3 * u;
                t0 += val;
                t1 += u * val;
                t2 += u * u * val;
            }
            float det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2)
                    + s2 * (s1 * s3 - s2 * s2);
            float d0 = t0 * (s2 * s4 - s3 * s3) - s1 * (t1 * s4 - s3 * t2)
                    + s2 * (t1 * s3 - s2 * t2);
            float fit = Math.abs(det) > 1e-9f ? d0 / det : mx;
            // 拟合在样本稀的档上会往外飘（实测能把基础后半深高估两成，倍率于是整体偏小，
            // 设计 18.6cm 的后深只长到 14.8cm）。窗口已经很窄了，兜住 mx —— 最外侧那个点
            // 才是这一档真正的外包络，拟合只用来抹掉它自身的抖动
            out[a] = Math.max(0f, Math.min(fit, mx));
        }
        return out;
    }

    /** mode：0 = 半宽 |x|，1 = 前半深 +z，2 = 后半深 −z */
    private static float extentVal(float[] v, int i, int mode) {
        if (mode == 0) return Math.abs(v[i]);
        return mode == 1 ? v[i + 2] : -v[i + 2];
    }

    /**
     * 基础网格各档的外包络是拟合出来的，逐档之间有几个毫米的抖动。锚点稀的时候这点抖动被
     * 摊平看不出来，锚点加密到 1% 身高之后它就成了锯齿 —— 倍率是「目标 ÷ 基础」，除出来的
     * 锯齿又被放大，实测二阶差分能到 4cm。这里做四遍 1-2-1 平滑把抖动抹掉（窗口收窄之后
     * 每档的样本少了，抖动比原来大，两遍不够）。
     */
    private static void smoothSeq(float[] a) {
        if (a.length < 3) return;
        float[] b = a.clone();
        for (int pass = 0; pass < 4; pass++) {
            System.arraycopy(a, 0, b, 0, a.length);
            for (int i = 1; i < a.length - 1; i++) {
                a[i] = 0.25f * b[i - 1] + 0.5f * b[i] + 0.25f * b[i + 1];
            }
        }
    }

    /** 关键高度表：腿三档 + 中段 N 档 + 胸 / 肩两档，拼成一条自下而上的表 */
    private static float[] concatKey(float a, float b, float c, float[] mid, float d, float e) {
        float[] k = new float[mid.length + 5];
        k[0] = a;
        k[1] = b;
        k[2] = c;
        System.arraycopy(mid, 0, k, 3, mid.length);
        k[mid.length + 3] = d;
        k[mid.length + 4] = e;
        return k;
    }

    /** smootherstep：两端一阶、二阶导都为 0，铺出来的轮廓才是没有硬折的 S */
    private static float smootherstep(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float smoothstep(float t) {
        float u = t < 0f ? 0f : (t > 1f ? 1f : t);
        return u * u * (3f - 2f * u);
    }

    /**
     * 半余弦过渡：两端一阶导为 0，二阶导有限且不为 0。
     *
     * smootherstep 的两端二阶导也是 0（一阶、二阶都抹平），铺出来的峰是一小段平台，配上
     * 另一侧的急转就成了「托盘 + 尖角」；sqrt(smootherstep) 反过来，峰点二阶导发散，增量
     * 一大就是个尖。半余弦的峰是有半径的圆顶，上下两侧同形，围度拉到头也只是更圆一点。
     */
    private static float halfCos(float t) {
        float u = t < 0f ? 0f : (t > 1f ? 1f : t);
        return 0.5f * (1f - (float) Math.cos(Math.PI * u));
    }

    /**
     * 半宽加一份，后半深要减多少才能让这一圈的周长不变 —— 椭圆周长对宽、对深的两个偏导之比。
     *
     * 躯干截面本来就是扁的（半宽比半深大），同样一份增量摊到宽度上对周长的贡献更大，所以
     * 这个比值大于 1。按 1:1 去扣的话，臀围拉大时读数会多出两厘米多。
     */
    private static float girthTrade(float a, float b) {
        double f = 3.0 * a * a + 10.0 * a * b + 3.0 * b * b;
        double rt = Math.sqrt(Math.max(1e-6, f));
        double da = 3.0 - (6.0 * a + 10.0 * b) / (2.0 * rt);
        double db = 3.0 - (10.0 * a + 6.0 * b) / (2.0 * rt);
        return db > 1e-3 ? BodyProfile.clamp((float) (da / db), 0.5f, 2f) : 1f;
    }

    /**
     * 把一份增量平滑地挤进剩余空间里：用掉不到一半时原样给足，超过一半开始挤，再多也只到
     * 把空间用完为止（永远挤不爆）。
     *
     * 衔接点（room 的一半）上函数值与一阶导都连续（e^0 = 1），所以不会像硬 clamp 那样削出
     * 一段平台、在平台两端各留一道硬折 —— 只是「越挤越慢」，参数拉到极值也还是连续的。
     * 只对增量用：拿它去压基础尺寸的话，本该不变的地方也会被压掉一截。
     */
    private static float softSat(float inc, float room) {
        if (room <= 1e-5f || inc <= 0f) return 0f;
        float u = inc / room;
        if (u <= 0.5f) return inc;
        return room * (1f - 0.5f * (float) Math.exp(-(u - 0.5f) / 0.5f));
    }

    /**
     * 乳腺的横向位移（{@code amp·qx}）：朝腋下那一半照旧，朝体中轴那一半按到中轴的距离封顶。
     *
     * 乳腺底盘是绕乳点（±bustX）的椭球，横向半径比乳点到中轴的距离还大 —— 底盘横跨了体中轴，
     * 于是左右两只各把自己那半边的顶点往中线推（中线右邻的往左、左邻的往右，方向正好相反）。
     * 这一份是个平移式的场：只要它在中轴附近不为零，靠中轴那一两个顶点就必然被推过中轴与
     * 对面互换。实测五个胸型各有 6~11 个三角面法线整个反向，全部落在 |x| < 1.5cm 的胸骨前；
     * 乳线上沿那几档还被这道错切折出 40°~80° 的硬折（无胸时同一条边只有 1°~14°）——
     * 看着就是乳腺上沿多出来的一块。
     *
     * 真人的乳房往内侧也没有地方可去：内侧边界就停在胸骨上，两只乳房在那里相遇，不是互相
     * 穿过去。所以内侧这一份的幅度按「到中轴的距离」封顶（{@link #softSat}，越挤越慢，最多
     * 挤掉一半）：中轴上的位移自动为 0，且两侧收敛到同一个式子、一阶导也连续，既穿不过去，
     * 也不会在胸骨前留下一道沟或者撕开一道口子。朝外摊向腋下的那一半完全不受影响 ——
     * 乳房本来就是往两侧铺开的。
     *
     * @param nx  缩放后的横坐标（决定这一点属于哪一侧）
     * @param qx  相对乳点的归一化横向坐标（{@code (nx ∓ bustX) / bustRx}）
     * @param amp 位移幅度（米），与 qx 相乘即位移
     */
    private static float lateralSpread(float nx, float qx, float amp) {
        float qo = nx >= 0f ? qx : -qx;                        // > 0 = 在乳点的外侧
        if (qo <= 0f) return 0f;                              // 内侧一律不动（见下）
        // 外侧这份不能是「满额一路推到腋下」：满额外扩把整片侧胸都往两旁推，顶点反而落
        // 在离乳点最远的腋下边缘（实测横向增量从乳线 2.9cm 一路涨到腋缘 3.3cm），大杯时
        // 这圈侧峰比乳头还凸，看着就是「两个尖峰」。改成钟形：从乳点往外渐起到满（约
        // 0.45 个横向半径处），再到腋下边缘收 0 —— 乳腺依旧往两侧摊开（围度够），但最
        // 鼓的点回到乳点，不再在腋缘鼓出一坨
        return amp * qx * smoothstep(qo / 0.45f) * (1f - smoothstep((qo - 0.70f) / 0.30f));
    }

    /**
     * 臀部那一块的钟形权重：峰在臀峰，往下（臀沟 / 大腿上缘）与往上（腰线）各自半余弦降到
     * 0，降到底时一阶导也是 0 —— 所以跟腿、跟腰都是接着的，不会在边界上留一圈折。
     */
    private static float hipBell(float y, float yPeak, float h) {
        // 往下要铺得够宽：臀围现在全落在前后上（左右不跟），一份十几厘米的增量若只在臀峰
        // 上下几厘米里爬完，那一圈就是一个尖。铺到大腿上缘之后坡度降到三分之一。
        // 往上到 0.61H 为止 —— 腰那一圈（0.62H）一点都不吃臀围，腰围读数才跟滑块一致
        float lo = 0.160f * h;      // 往下铺多宽：到臀沟 / 大腿上缘
        float hi = 0.090f * h;      // 往上铺多宽：到腰线
        float u = y <= yPeak ? (yPeak - y) / lo : (y - yPeak) / hi;
        if (u >= 1f) return 0f;
        // 峰顶上下各留一段平顶：实测臀越大峰顶越尖（臀 1.0 的峰顶曲率半径 14.6cm，臀 1.3
        // 掉到 5.8cm、臀 1.6 只剩 5.1cm）—— 剖面上是从大腿那一小截几乎直立地爬到臀峰，峰顶
        // 是个折角，看着是「撅着的一块」而不是圆屁股。平顶把下极那段填满，峰顶就成了圆弧。
        // 平顶与下降段在交界处斜率都是 0，不会另生出折角
        float f = 0.35f;
        float uu = u <= f ? 0f : (u - f) / (1f - f);
        return 0.5f * (1f + (float) Math.cos(Math.PI * uu));
    }

    /**
     * 环向缩放：肩 / 胸 / 腰 / 臀 / 大腿 / 小腿 / 脚，外加女性的胸型（乳腺局部生长）。
     *
     * 每个部位绕自己的轴缩放（躯干绕体中轴、每条腿绕腿轴），不会把手臂一起撑大；
     * 胸型不去动整圈的胸腔围度，只在乳腺位置做球面生长，所以胸围与胸型互不干扰。
     */
    /**
     * withBust = false：不长乳腺，用来量下胸围（罩杯基准）。
     *
     * legAxis 是 applyStance 量出来的腿轴（{高度表, 轴表}）：腿的缩放必须绕它自己的中心，
     * 绕偏了腿一粗就被推歪。
     */
    private static void applyGirth(float[] v, BodyProfile p, float h, float[] par, boolean withBust,
                                   float[][] legAxis) {
        float shoulder = BodyProfile.clamp(p.shoulderR, 0.70f, 1.60f) - 1f;
        float chestRaw = BodyProfile.clamp(p.chestR, 0.70f, 1.60f) - 1f;
        // 腰臀协调（见 BodyProfile#waistHipAnny）：两个滑块各自拉到头会长出「腰比臀粗」
        // 或者「蚂蚁腰」这种真人没有的体型，这里先按实测的腰臀比区间把两者协调好
        float[] wh = {p.waistR, p.hipR};
        BodyProfile.waistHipAnny(wh, p.gender, 0);
        float waist = wh[0] - 1f;
        // 臀围标定：默认体型（hipR=1）按人体测量表收紧，份额仍完全由 hipR 决定
        float hip = BodyProfile.clamp(
                wh[1] * HIP_CALIB[p.gender == 1 ? 1 : 0], 0.70f, 1.60f) - 1f;
        float thigh = BodyProfile.clamp(p.thighR, 0.70f, 1.45f) - 1f;
        float calf = BodyProfile.clamp(p.calfR, 0.70f, 1.45f) - 1f;
        // 上限 2.10：G 杯（25cm）反解出来要 1.90 左右，卡在 1.80 上就只能到 22.6
        float bust = BodyProfile.clamp(p.bustR, 0.20f, 2.10f) - 1f;
        float footL = BodyProfile.clamp(p.footR, 0.85f, 1.20f);
        float footW = BodyProfile.clamp(p.footWR, 0.80f, 1.30f);
        boolean female = p.gender == 1;
        float[] legYs = legAxis != null && legAxis.length > 1 ? legAxis[0] : null;
        float[] legAx = legAxis != null && legAxis.length > 1 ? legAxis[1] : null;
        float zMix = 0.025f * h;        // 前 / 后两条深度曲线的过渡带（体侧那一段）
        float yShoulder = mappedY(0.82f, h, par);
        float yChest = mappedY(0.72f, h, par);
        float yWaist = mappedY(0.62f, h, par);
        float yHip = mappedY(0.52f, h, par);
        float yThigh = mappedY(0.35f, h, par);
        float yCalf = mappedY(0.16f, h, par);
        float yTorsoBot = mappedY(0.50f, h, par);
        float yCrotch = mappedY(0.475f, h, par);    // 裆：腿从这里往下各有各的轴
        float yHipWide = mappedY(0.55f, h, par);    // 臀最宽的一档（比量臀围的 0.52 高一点）
        float yFootTop = mappedY(0.045f, h, par);
        // 躯干 / 腿的倍率在「关键高度」之间插值，而不是把几条高斯带叠加：
        // 叠加式会在相邻部位之间串味（胸围拉到 1.3 能把腰也撑到 1.1），插值之后每个滑块
        // 只作用于自己那一段；臀的一部分仍然延伸到大腿根，免得臀部收细后与腿根脱节
        // 臀围向下能带多少：大腿吃一小份（臀细了腿根不至于脱节），裆部几乎不吃。
        // 裆部这一档是后来加的 —— 少了它，臀宽会顺着插值一路拖到裆以下，那里的点绕
        // 「腿轴」缩放（腿轴在外侧），内侧的点被放大后直接推过体中轴，两腿之间那道裆
        // 缺口就此填平：正面看就是一整片外扩的裙摆，而不是两条腿
        // 份额压到很小：臀围现在只长前后，大腿要是还吃一大份，臀围 1.6 时大腿半宽 17.1cm
        // 会反过来超过髋的 16.0cm —— 正面看就成了「大腿比髋还宽」。留一点点是为了臀部后侧
        // 和那条腿不要断开（那份过渡主要由钟形的下缘负责）
        float hipLeg = hip * 0.03f;
        float hipRoot = hip * 0.02f;
        // 试过把臀围的增量改成「横向多、前后少」想让正面更宽：围度由半宽 + 半深一起决定，
        // 增量又被围度反解自己抵消掉，横向几乎没变宽，反而让不同臀围之间的轮廓不再单调。
        // 所以宽深还是对称给，腰身靠「峰值上移 + 裆部钉住 + 大腿少带」这三条来出
        float thighF = 1f + thigh + hipLeg;
        float rootF = 1f + thigh + hipRoot;
        float calfF = 1f + calf;
        // 胸围：用户拉胸围想看到的是「乳房变大」，所以大部分增量折算成乳腺体积（长成什么样
        // 由胸型决定），只有一小部分留给胸腔 —— 骨架不会跟着胸围滑块一起宽那么多
        float chestRib = chestRaw * (female ? CHEST_RIB_SHARE : 0.75f);
        float chestBust = chestRaw * (female ? CHEST_BUST_SHARE : 0.25f);
        // 宽向 / 厚向分开：胸腔加宽比加厚明显，骨盆则差不多；数组自下而上，配三次插值
        // 腿完全分开是从 yTorsoBot 开始的，所以这一档必须也落在腿的粗细上：0.475 那一档
        // 只挡住了裆下方，裆本身（0.50）仍会被插值抬到接近臀宽，腿内侧那一点绕腿轴一放大
        // 就跨过体中轴，缺口照填。把 0.475 与 0.50 都钉在腿宽上，裆以下一路都是腿该有的粗细。
        //
        // 臀部最宽的一档不放在量臀围的 0.52 而放在 0.55：峰值贴着裆时，「腿宽 → 臀宽」要在
        // 3cm 之内爬完，实测外侧轮廓每下降 1cm 就张开 0.6cm —— 那么陡的外扩看着就是裙摆
        // 忽然撑开。拉开到 0.55 之后这段有 8cm 可以过渡，坡度降到三分之一
        //
        // ---- 中段（臀下缘 → 臀峰 → 腰）：宽 / 深都按「绝对尺寸」铺同一条 S，再反解倍率 ----
        // 侧面以前在 0.585 还单独留了一档「小肚子」，把「臀宽 → 腰」的过渡压在这一档里做完
        // （髂嵴本来也就是这么一道坎，压着它臀围拉大时腰腹才不跟着鼓）；但单独占一档关键帧
        // 就把侧面切成了两截，见下面 keyDF / keyDB 处的说明。坎现在交给 S 上段的收紧速度来给。
        // 倍率乘上去的那个基础网格自己也在收腰，两条曲线各拐各的：乘积在 0.52~0.57 之间
        // 被抹成一段平台、两头各留一道硬边，臀围一大还能长出双峰 —— 实测 女165/55
        // 腰1.00+臀1.60：0.52H 半宽 18.6cm 反而比臀峰 0.55H 的 18.4cm 宽，下面 0.50H 又
        // 掉回 17.1cm，正面看就是一圈带硬边的托盘，不是流线。这里改成：先量基础网格在各
        // 档的绝对半宽，再按 smootherstep 从臀下缘（0.50）到臀峰（0.55）、从臀峰到腰
        // （0.62）铺目标绝对半宽，除以基础半宽反解出倍率。三个锚点上斜率都是 0，整段是
        // 一条连贯的 S
        // 锚点每 1% 身高一档（13 档）。以前只铺 6 档、间距 2.5% 身高：插值插的是「倍率」，
        // 而基础网格自己在这段里也在收腰，两条曲线各拐各的，相乘之后绝对尺寸的曲率就
        // 不是设计出来的那条了 —— 默认体型看不出来，围度一大实测二阶差分能到 2.3cm。
        // 锚点加密一倍之后每段只剩 1.6cm，这项误差跟着降到四分之一
        int midN = 13;
        float[] midY = new float[midN];
        for (int i = 0; i < midN; i++) {
            midY[i] = yTorsoBot + (yWaist - yTorsoBot) * i / (float) (midN - 1);
        }
        int iHip = Math.round((yHipWide - yTorsoBot)
                / Math.max(1e-5f, yWaist - yTorsoBot) * (midN - 1));   // 臀峰那一档
        int iWst = midN - 1;                                            // 腰那一档
        // yFloor：最下面那一档（0.50H）的取样窗口会探到裆底下，把大腿也算进来 —— 收腿之前
        // 大腿在手臂内界之外会被过滤掉，收完之后它进到了窗口里，量出来的「躯干」半宽平白
        // 大了 0.7cm，倍率跟着变，臀围读数多出 3.5cm。取样不许探到躯干底以下
        float[] bW = baseExtent(v, h, midY, 0, yTorsoBot);   // 半宽
        float[] bF = baseExtent(v, h, midY, 1, yTorsoBot);   // 前半深（腹侧）
        float[] bB = baseExtent(v, h, midY, 2, yTorsoBot);   // 后半深（背侧）
        smoothSeq(bW);
        smoothSeq(bF);
        smoothSeq(bB);
        // 横向能长的地方是有上限的：再宽就长进手臂里了（手臂内界 isArmLimit，0.55H 处
        // 只有 19.6cm，而臀围 1.6 想让那一圈长到 20.8cm）。多出来的量如果硬压在外沿那一圈
        // 上，压出来就是一段平台加两道硬折。这里改成：横向先按可用空间封顶，
        // 长不下的量按绝对尺寸转投前后 —— 臀大本来就是往臀部后面长而不是往两侧长，
        // 腰粗同理是往腹前长。围度基本守恒，滑块读数和外形都还是自洽的
        float capHip = AnnyMeasure.isArmLimit(midY[iHip] / h, h);
        capHip = capHip > 0f && bW[iHip] > 0f ? capHip * 0.94f / bW[iHip] : 2f;
        float capWst = AnnyMeasure.isArmLimit(midY[iWst] / h, h);
        capWst = capWst > 0f && bW[iWst] > 0f ? capWst * 0.94f / bW[iWst] : 2f;
        // ---- 左右（宽度）也跟臀围走一份 ----
        // 拉臀围是想让屁股变大，不是把整个人撑宽，所以臀围那一档的半宽基础只跟腰围（腰粗
        // 则髋也跟着粗 —— 真人没有「腰腹比髋还宽」的掐腰），再按手臂内界封顶。实测女165/55
        // 腰1.60+臀0.70：臀圈 13.4cm、腰圈 15.8cm，腰腹会悬在掐细的臀圈外沿上，所以这里顶到
        // 不窄于腰圈的 97%（仍受手臂内界的限制）
        float hipWideF = 1f;
        if (bW[iHip] > 0f && bW[iWst] > 0f) {
            float need = Math.min(1f + waist, capWst) * (bW[iWst] / bW[iHip]) * 0.97f;
            if (hipWideF < need) hipWideF = Math.min(need, capHip);
        }
        float wstWF = Math.min(1f + waist, capWst);
        // 臀部最凸的一圈比最宽的一圈低一点（真人的臀围量在 0.52H 上下，髋最宽在 0.55H 上下），
        // 钟形峰放在 0.52H：往上到腰还有 9% 身高可以收，放在 0.55H 只剩 7%，那份增量就得多
        // 陡三成才收得完
        // 峰值要跟着纵向比例走（mappedY），不能钉死在 0.52H：臀围是在 mappedY(0.515~0.555)
        // 上量的，腿长比例一动，两者就错开 —— 腿长的体型钟形峰落在测量段外头，臀围读数平
        // 白少 4cm（172/60 就是这么比 165/55 还小的）
        float yHipBulge = mappedY(0.52f, h, par);
        // 但腰以下、大腿以上那一截（髋侧 / 大转子）得跟着臀围走：这份增量只在后半深上的话，
        // 正面看髋还是原来那么宽，臀一大就只是屁股往后翘，髋侧跟臀部对不上。左右这份按与
        // 后半深同一条钟形铺（两端斜率都是 0，跟腰、跟腿都接着），份额见 HIP_SIDE_SHARE。
        float sidePeak = hip * HIP_SIDE_SHARE * bW[iHip];        // 钟形峰上想加的绝对量
        // 臀围剩下的一份给「后半深」：宽度那边（含左右这一份）长不下的量按绝对尺寸转投过来
        // （围度守恒），转投过来的量再按钟形铺在臀部那一整块上 —— 全堆在一个高度上就是一
        // 道尖，铺在 0.36H~0.61H 这一块（从大腿上缘到腰线）上才是圆润的屁股
        float wstDF = 1f + waist * 0.85f
                + ((1f + waist) - wstWF) * (bF[iWst] > 0f ? bW[iWst] / bF[iWst] : 0f);
        float wstDB = 1f + waist * 0.85f;                        // 后腰也跟着粗，但不吃转投
        float hipDF = 1f + waist * 0.45f;                        // 下腹：只跟腰，不吃臀围
        float hipDB = 1f + waist * 0.45f;                        // 臀峰后侧的基础，bump 另加
        // 三个锚点上的目标绝对尺寸：臀下缘（腿根）、臀峰、腰。宽 / 前 / 后各铺一份
        float botW = bW[0] * rootF;
        float hipPW = bW[iHip] * hipWideF;
        float wstW = bW[iWst] * wstWF;
        float botF = bF[0] * rootF;
        float hipPF = bF[iHip] * hipDF;
        float wstPF = bF[iWst] * wstDF;
        float botB = bB[0] * rootF;
        float hipPB = bB[iHip] * hipDB;
        float wstPB = bB[iWst] * wstDB;
        float[] midW = new float[midN];
        float[] midF = new float[midN];
        float[] midB = new float[midN];
        float[] aW0 = new float[midN];            // 半宽：纵向那条 S 的目标值（不含左右那一份）
        float[] aF0 = new float[midN];
        float[] aB0 = new float[midN];
        float[] sideEff = new float[midN];        // 左右那一份实际加进去的绝对量
        float[] bellW = new float[midN];          // 左右那一份在各档上的钟形权重
        float roomPeak = Float.MAX_VALUE;         // 峰上最多还能加多少（受手臂内界限制）
        for (int i = 0; i < midN; i++) {
            boolean low = midY[i] <= yHipWide;
            float u = low ? (midY[i] - yTorsoBot) / Math.max(1e-5f, yHipWide - yTorsoBot)
                    : (midY[i] - yHipWide) / Math.max(1e-5f, yWaist - yHipWide);
            // 半余弦：两端斜率 0、曲率有限且不为 0。以前上段用 sqrt(smootherstep) 想让它收
            // 得快一点，可 sqrt 在峰点的二阶导是发散的 —— 峰值增量小的时候看不出来，臀围一
            // 大峰顶就成一个尖点（实测那一档的二阶差分能到 1.8cm）。半余弦铺出来是「有限
            // 半径的圆顶」，增量再大也只是更圆一点，不会变尖
            float s = halfCos(BodyProfile.clamp(u, 0f, 1f));
            aW0[i] = low ? botW + (hipPW - botW) * s : hipPW + (wstW - hipPW) * s;
            aF0[i] = low ? botF + (hipPF - botF) * s : hipPF + (wstPF - hipPF) * s;
            aB0[i] = low ? botB + (hipPB - botB) * s : hipPB + (wstPB - hipPB) * s;
            // 臀围分给左右两侧的那一份，跟后半深走同一条钟形：髋侧 / 大转子那一截跟着臀围
            // 一起长（或一起收），两端斜率都是 0，所以跟腰、跟腿都还是接着的。只剩多少空间
            // 由手臂内界定，加不进去的部分平滑地挤掉（softSat），不能硬 clamp：硬 clamp 会在
            // 那一档削出一段平台、平台两侧各留一道硬折，参数拉到极值时尤其明显
            // 峰放在髋最宽那一档（0.55H），不是臀峰（0.52H）：髋侧本来就是最宽的地方，峰
            // 跟基础那条 S 的峰对上，叠加出来还是一个峰；错开 3% 身高的话两者之间会拱出
            // 一道坎 —— 臀围 1.6 时那一档的二阶差分实测多出 0.23cm
            bellW[i] = hipBell(midY[i], yHipWide, h);
            // 这一档到手臂内界还剩多少空间，按钟形权重折回「峰上还能加多少」。取最紧的那一
            // 档：逐档各自去挤的话，挤得多的档和挤得少的档拼起来钟形就变形了 —— 臀围拉到
            // 1.6 时那一档的二阶差分实测从 0.85cm 涨到 1.19cm，正面看峰值附近多出一道折
            if (bellW[i] > 1e-4f) {
                float room = Math.max(0f, AnnyMeasure.isArmLimit(midY[i] / h, h) * 0.94f - aW0[i]);
                roomPeak = Math.min(roomPeak, room / bellW[i]);
            }
        }
        // 只压幅度、不压形状：整条钟形按同一个系数收，铺出来还是那个圆顶
        float sidePeakEff = sidePeak > 0f ? softSat(sidePeak, roomPeak)
                : Math.max(sidePeak, -0.5f * bW[iHip]);
        for (int i = 0; i < midN; i++) {
            sideEff[i] = sidePeakEff * bellW[i];
        }
        // 左右加进去的那一份要从后半深里扣回来，臀围读数才还是跟着滑块走。扣的是幅度（后
        // 半深仍走自己那一条钟形），不是逐档去减：两条峰位不同的钟形相减会在 0.52H~0.55H
        // 之间拱出一道驼峰 —— 实测「全最小」那一档的二阶差分从 1.28cm 涨到 1.64cm
        // 臀围是在深度钟形峰那一圈上量的，所以按左右那份钟形在此处的权重折成等价的一份
        float trade = girthTrade(bW[iHip], bF[iHip] + bB[iHip]);
        float hipSide = bW[iHip] > 1e-4f
                ? sideEff[iHip] * hipBell(yHipBulge, yHipWide, h) / bW[iHip] : 0f;
        float hipBump = hip + ((1f + hip) - hipWideF - hipSide * trade)
                * (bB[iHip] > 0f ? bW[iHip] / bB[iHip] : 0f);
        for (int i = 0; i < midN; i++) {
            float aW = aW0[i] + sideEff[i];
            float aF = aF0[i];
            // 后半深的关键帧里不带臀围隆起：那一份在 SDF 里按钟形单独叠（见下面的 bumpY）。
            // 留在关键帧里的话，裆那一档是 rootF、本就不带隆起，0.45H→0.50H 之间要把整份
            // 隆起从 0 爬到满，而腿那侧的 bumpY 又再给一份 —— 隆起被算了两次，所有滑块拉
            // 满时那一档的二阶差分能到 3.16cm
            float aB = aB0[i];
            // 上限 3.0：基础网格的后半深只有 9cm 上下，臀围拉到 1.6 那一圈要 19cm，倍率要
            // 到 2.1 —— 卡在 2.0 上会把 0.55~0.57 那三档截成同一个常数，实际后深就跟着
            // 基础网格自己的形状走了，设计出来的 S 在这一段完全失效
            midW[i] = bW[i] > 1e-4f ? BodyProfile.clamp(aW / bW[i], 0.5f, 3.0f) : 1f;
            midF[i] = bF[i] > 1e-4f ? BodyProfile.clamp(aF / bF[i], 0.5f, 3.0f) : 1f;
            midB[i] = bB[i] > 1e-4f ? BodyProfile.clamp(aB / bB[i], 0.5f, 3.0f) : 1f;
        }
        float[] keyY = concatKey(yCalf, yThigh, yCrotch, midY, yChest, yShoulder);
        float[] keyW = concatKey(calfF, thighF, rootF, midW, 1f + chestRib, 1f + shoulder);
        // 前后各走一条 S。以前侧面是「臀下缘 → 臀峰 → 小肚子(0.585) → 腰」四档关键帧，小肚子
        // 那一档几乎全跟腰走：苹果型（腰粗臀细）实测 0.50H 1.00 → 0.55H 0.66 → 0.585H 1.66 →
        // 0.62H 1.51 —— 3.5cm 之内先凹进去再鼓出来，侧面看就是一道硬折加一个突出来的小肚子。
        // 现在前后都用与正面同一条 S（半余弦），只是三条曲线的锚点值不同：后面吃臀围、前面
        // 吃腰围。整段只有一个臀峰、一个腰谷，髂嵴那道坎靠臀块钟形的上缘来给
        float[] keyDF = concatKey(calfF, thighF, rootF, midF, 1f + chestRib * 0.80f,
                1f + shoulder * 0.35f);
        float[] keyDB = concatKey(calfF, thighF, rootF, midB, 1f + chestRib * 0.80f,
                1f + shoulder * 0.35f);

        // 胸型：乳腺是半球状的软组织，所以按球面生长来做（向前 + 向两侧），
        // 男性没有乳腺，只按肌肉量在胸大肌位置留一点厚度
        float muscle = BodyProfile.clamp(p.muscleR, 0.75f, 1.35f);
        // 乳腺全部由丰满度长出来：网格自带那层实测是平的，不给基准量的话 B 杯就是平胸，
        // A / AA 只能在这 0 上再往下收，总共也就收得动 9mm —— 三个杯位看着一模一样。
        // 有了基准量，AA→G 全是「长多长少」，小杯也是个圆润的小丘而不是挖出来的坑
        // 乳腺量要封顶：胸围与丰满度一起拉满时，按线性算能长出前突 10cm 的一大团，再叠到
        // 撑大了的胸廓上，乳房下缘那一档的二阶差分到 3.9cm —— 侧面看就是一道硬折。过了
        // 一只 G 杯（raw ≈ 1.0）之后增速降到三成半：杯位该有的样子还在，只是再往上拉不再
        // 是「更大的一坨」，而是「更饱满一点」
        float raw = withBust
                ? (female ? BUST_BASE : 0f) + (bust + chestBust * CHEST_TO_BUST) * BUST_SPAN
                : 0f;
        float bustUnits = raw <= 1.0f ? raw : 1.0f + (raw - 1.0f) * 0.35f;
        float bustAmp = bustUnits * (female ? 0.042f : 0.017f * muscle) * h;
        float bustX = (female ? 0.034f : 0.040f) * h;
        float[] bs = BodyProfile.bustShape(p);
        // 乳腺中心就放在胸线（0.72H ≈ 乳头线）：胸围也是在这一圈上量的，
        // 长在别的高度的话，软尺那一圈读数不会跟着丰满度走，罩杯也就对不上了
        float bustY = yChest + bs[0] * h;
        // 核的前后中心要跟着胸廓走：钉死在 0.048H 的话，胸围一大胸壁整体前移，乳腺还长在
        // 原来那个位置上，等于把一整只乳房叠到变形过的胸壁上 —— 实测胸围 1.60 那一档的
        // 二阶差分到 4.4cm（原来 2.1cm），乳房下缘被拧出一道硬折。跟着胸壁挪（与 keyDF 那
        // 一条同一个倍率），乳房相对胸壁的形状才不变
        float bustZ = 0.048f * h * (1f + chestRib * 0.80f);
        // 底盘随丰满度一起铺开：影响半径固定不变的话，罩杯一大就只是「更高的一座山」，
        // 底盘还是原来那一小块，看着是两个尖球、盖不住胸廓。铺开之后大杯才是饱满地
        // 覆盖在胸壁上。（只加不放，收小杯时底盘不动，免得小杯缩成一点）
        // 0.22 → 0.32：实测「同样的杯差，我们的乳房比真人高出近两厘米」—— 底盘铺得不够开，
        // 围度全靠往前顶出来，于是每只杯都显得比标称大一档（AA 鼓出 4.8cm，真人 3cm）
        // 试过再往回收（0.10：真人的底盘基本是胸廓给的，不该随杯位撑大）—— 收了之后底盘
        // 是紧凑了，可剖面里核边缘那一截占比变大，面积比反倒从 0.67 掉回 0.64，等于把核
        // 换来的圆润又退回去；E / F / G 还差 0.2~0.7cm 配不平。所以仍按 0.32 铺
        float spread = 1f + 0.32f * Math.max(0f, bustUnits);
        float bustRx = bs[1] * h * spread;
        float bustRz = bs[2] * h * spread;
        // 纵向半径放大 1.35：核的横向支撑本来就大（半球大杯 Rx≈10.6cm），横向外扩又只把
        // 前向的高点往两侧平移、不增加前后厚度，正面看乳房被摊成了「横着的圆盘」—— 实测
        // 正面凸起轮廓（落差 2cm 处）纵横比 B 杯 1.88、G 杯 2.15，比真人的 1.2~1.5 扁得多。
        // 纵向半径只加高、不改围度（杯差是 bustRForCup 反解的，与核高无关），放大后纵横比
        // 回到 1.27 / 1.51，乳房从扁盘立成圆丘
        float bustRy = bs[3] * h * spread * 1.35f;
        float bustDroop = bs[4];
        // 半球的 0.05 当底噪减掉，剩下按 0.52（下垂那一档）归一。纵向半径的分档也要用它，
        // 所以提到顶点循环之外
        float dk = BodyProfile.clamp((bustDroop - 0.08f) / 0.52f, 0f, 1f);
        // 收小杯要有下限：胸壁。乳腺下缘那一档不长乳腺（胸型的上 / 下影响半径之外），
        // 量出来最前的那个点就是胸腔前壁 —— 乳房最多收到这里为止
        float wallZ = 0f;
        if (bustAmp < 0f) {
            float y0 = bustY - bustRy * 1.35f;
            float y1 = bustY - bustRy * 0.90f;
            for (int i = 0; i < v.length; i += 3) {
                float yy = v[i + 1];
                if (yy < y0 || yy > y1) continue;
                float lim = AnnyMeasure.isArmLimit(yy / h, h);
                if (lim > 0f && Math.abs(v[i]) > lim * 0.94f) continue;   // 别量到手臂上
                if (v[i + 2] > wallZ) wallZ = v[i + 2];
            }
        }
        // 乳腺叠加之前先把前胸的台阶抹平（见 smoothChest）
        smoothChest(v, h, 0.85f);
        for (int i = 0; i < v.length; i += 3) {
            float x = v[i];
            float y = v[i + 1];
            float z = v[i + 2];
            float t = y / h;
            // 头以下才归这里管；0.86~0.90 之间把倍率淡出，颈根一圈就不会有硬边
            float keep = 1f - smoothstep((t - 0.86f) / 0.04f);
            if (keep <= 0f) continue;                        // 头交给 applyHead
            // 手臂：之前是硬排除，臂根会现出一圈断层；这里按到「手臂边界」的距离羽化
            float limit = AnnyMeasure.isArmLimit(t, h);
            if (limit > 0f) {
                float d = (Math.abs(x) - limit * 0.94f) / (limit * 0.14f);
                keep *= 1f - smoothstep(d);
                if (keep <= 0f) continue;
            }
            // 裆部：缩放轴以前在 yTorsoBot 处从 0 直接跳到 ±legX，改成在臀 → 裆之间渐变
            float legW = 1f - smoothstep((y - yTorsoBot) / Math.max(1e-5f, yHip - yTorsoBot));
            // 缩放轴取收腿之后量出来的腿中心：以前整条腿共用一个 0.048H，而腿真正的中心
            // 是从踝一路斜到大腿根（矮个子那一份归一化后更大），轴比中心偏内，绕它一放大
            // 腿就被往外推 —— 腿围拉到 1.3 时膝那一档能多外凸 1.3cm，正面看又是 O 型
            float legX = legAx != null ? levelAxis(legYs, legAx, y) : 0.048f * h;
            float axisX = legW * (x >= 0f ? legX : -legX);
            float sw = bandScale(y, keyY, keyW);
            // 前后各按自己的曲线缩放，中间（体侧 z≈0 附近）平滑过渡：硬切会在体侧沿体中线
            // 留一道折痕，过渡带取 2.5% 身高，正好盖住侧腹那一圈
            // 腿 → 躯干的过渡带要比缩放轴那一条宽得多（4% 身高）：腿是圆截面、躯干是前后
            // 两条曲线，共用 2% 那条窄带时，围度一大这两者之间的落差要在 3cm 之内切换完，
            // 实测前深那一档的二阶差分能到 1.6cm —— 侧面看就是臀沟上缘的一圈硬折
            // 放宽到 5.5% 试过：全拉满那一档的 1.93cm 一分没降，另有三个组合反倒变差，倒是
            // 把臀下缘那道沟抹平了 —— 落差不是这里的瓶颈，别动
            float legWd = 1f - smoothstep((y - yTorsoBot) / (0.04f * h));
            // 臀围的隆起在腿这一侧也要有：腿是圆截面（深度 = 宽度倍率），躯干那一侧的深度
            // 才带钟形隆起。只给一侧的话，这份增量要在这条过渡带里从 0 爬到满 —— 实测臀围
            // 1.6 时 0.51H→0.52H 那一档跳了 5.5cm
            // 两侧都给同一份：躯干那侧的关键帧不带隆起（见上面），腿是圆截面也不带，所以
            // 这份隆起在过渡带里不用再「从 0 爬到满」，直接叠在混合结果上就行。分母取该
            // 高度上的基础后半深（腿上是腿根那一档），不再拿 bB[0] 去近似所有档
            float bAtY = bandScale(y, midY, bB);
            float bumpY = bAtY > 1e-4f
                    ? bB[iHip] * hipBump * hipBell(y, yHipBulge, h) / bAtY : 0f;
            float sdB = mix(bandScale(y, keyY, keyDB), sw, legWd) + bumpY;  // 腿保持圆形截面
            float sdF = mix(bandScale(y, keyY, keyDF), sw, legWd);
            float sd = sdB + (sdF - sdB) * smoothstep((z + zMix) / (2f * zMix));
            float nx = x + (x - axisX) * (sw - 1f) * keep;
            float nz = z * (1f + (sd - 1f) * keep);
            if (bustAmp != 0f && nz > -0.02f * h) {
                // 椭球核：横向 / 前后 / 纵向各自的影响半径决定底盘宽窄与隆起深浅
                float dx = nx - (nx >= 0f ? bustX : -bustX);
                float dy = y - bustY;
                float dz = nz - bustZ;
                // 纵向影响半径上下不对称，且按胸型分档：乳点以上只到腋窝下缘（上极是并入胸壁
                // 的一坡），乳点以下要短 —— 水滴的下极该是「短而圆的一兜」，不是拖到上腹的
                // 一条斜边（实测拖到 0.6513H，比乳下褶低了一掌，凸度只有 0.11）。
                // 上极：半球 0.70 倍（上极指数增强见下面的 pw，取 0.60）。纵向半径放大 1.35
                // 之后，上极若按原来的 0.65，乳点以上会一直顶到 0.784H（腋窝在 0.74~0.76H），
                // 上胸 / 腋前就多鼓出一坨 —— 上一版把上极抬到 0.85 正是这个毛病（「乳头上方 /
                // 上胸那一坨多余的凸起」）。但把上极收得过头（0.30 + 指数 1.10）又走到另一个
                // 极端：生长在乳点上方很快收掉，胸面在 0.775H 处被带出一道「凹下去」的走势
                // （落差增量 6.9→2.4→4.3 的局部极小）。0.70 + 指数 0.60 让上极重新成一道平缓
                // 的凸坡（贡献一路平滑衰减、落差增量单调，无局部极小），乳点以上约 3.6cm
                //（落差 2cm 处）；纵向加大的那一份仍主要落到下极（下 5.3cm），纵横比 1.27。
                // 水滴 / 下垂略放开一点（那一坡本来就该缓而长）。
                // 下极：随下垂量收，水滴收到 0.85、下垂收到 0.65
                float ryV = dy > 0f ? bustRy * (0.70f + 0.15f * dk)
                        : bustRy * (1.00f - 0.35f * dk);
                float qx = dx / bustRx, qy = dy / ryV, qz = dz / bustRz;
                float q2 = qx * qx + qy * qy + qz * qz;
                if (q2 < 1f) {
                    // 紧支撑的生长核，椭球外严格为零 —— 以前用高斯，尾巴一直拖到腰线，
                    // 丰满度一变连腰围都跟着动（实测能带出 4cm）；罩杯只该调乳腺，胸廓 /
                    // 腰腹留给胸围 / 腰围滑块。
                    // 形状上过去取「平顶 + 收边」：核心里位移为常数，那一块保留的是胸腔自己
                    // 的曲率（几乎还是平的），形状全靠收边那一圈挤出来 —— 丰满度一大就成了
                    // 「一块平板 + 一圈陡边」，怎么都不圆。这里换成覆盖全域的穹形：顶点自带
                    // 曲率、边缘一阶连续地收到 0。(1−r²)²(1+r²) 的体均值约为旧核的 0.55 倍，
                    // 峰值乘 1.70 正好补回被削掉的那部分 —— 大小不变，形状成了球冠
                    // 核改成 (1−q²)^0.60：原来 (1−q²)²(1+q²) 的剖面面积比只有 0.65，比抛物线
                    // （0.667）还瘦 —— 剖面是「中间鼓、两边急收」，看着是贴在胸壁上的一块
                    // 扁丘，不是球。真人乳房的剖面是半椭圆，面积比 π/4 ≈ 0.785；指数往那一侧
                    // 靠（半圆是 0.5），0.85 只到 0.70、看不出来，0.60 才到 0.75 上下。
                    // 再低下去边缘切线就立起来，底盘会现出一圈墙。
                    // 峰值 1.45：新核铺得更开，光按体均值配平（1.139）前突要掉三成，B 杯只剩
                    // 2.3cm，成了「平铺的一大片」；补到 1.45 前突回到原来的九成，形状还是圆的。
                    // 围度 / 杯差不靠这个系数保证 —— 那个是反解 bustR 得来的（见 BUST_SPAN）
                    // 水滴 / 下垂：核的指数上下不对称，下极给「平顶」、上极给「缓降」。
                    // 水滴的侧面是「下极短而圆、上极长而平」—— 指数小 = 顶上那段几乎不掉、
                    // 到边缘才立起来（饱满），指数大 = 一路缓降（斜坡）。只把下极的影响半径
                    // 收小做不到这一点：实测那样下极成了一条陡直的斜边，凸度反倒从 0.10 掉到
                    // 0.099，乳点附近还留一个折点（剖面上 4.8 4.8 4.4 那个平台）。
                    // 上下之间要平滑过渡：指数在乳点那一档突变的话，剖面在峰上会折出一段平台
                    float uu = smoothstep((qy + 0.30f) / 0.60f);
                    // 上极的指数单独抬一档：指数 < 1 时核在边界上切线是立起来的（剖面到边缘
                    // 一阶不连续），胸前就现出一道坎 —— 真人的上极是平缓并入胸壁的，不该有这道
                    // 坎。抬到 1 以上才是真正相切：上极 +0.60（半球那一档算出 1.60），再叠
                    // 下垂的 +0.20。实测半球 C 杯乳线上沿那一档的折角从 60° 降到网格本身的
                    // 水平（剩下的最大值跑到腋下 53°，那是手臂本来就有的一道）
                    // 基础指数 0.60 → 0.80、峰值 1.45 → 1.70：0.60 时核在边界（q2→1）切线近乎
                    // 竖直，隆起并入胸壁处留一圈折棱、峰顶又被撑成一片平顶，正面看是一块「平顶
                    // + 陡边」的圆盘，既不通透也不圆。指数抬到 0.80，边缘收得更缓、峰顶更圆，
                    // 剖面由平顶盘变成连贯饱满的球冠。指数变大剖面会变瘦（面积比下降），峰值
                    // 相应补到 1.70 把饱满度找回来（实测半球 B 杯杯差 12.7→13.1、G 杯 24.4→26.0，
                    // AA~G 全程仍铺得下）
                    float pw = 1.40f + (0.60f + 0.20f * dk) * uu - 0.20f * dk * (1f - uu);
                    float w = 2.30f * (float) Math.pow(Math.max(0f, 1f - q2), pw);
                    float grow = keep * bustAmp * w;
                    float kz = (bs[2] / 0.050f) * (0.55f + 0.45f * qz);
                    // 横向外推若对紧贴乳点的那块也生效，会把乳点正下方高前向的组织推到外侧，
                    // 结果是每只乳房靠腋下那侧比乳点还凸（大杯时「两个尖峰」）。lateralSpread
                    // 内部已把乳点内侧（qo<=0）整块钉死、外侧用钟形渐起 + 腋缘收零，最鼓点
                    // 始终落在乳点，外推不再堆出侧峰
                    if (bustAmp < 0f) {
                        // 收小杯不是把乳房压平，而是整块朝胸壁按比例缩：核还是那个穹形，
                        // 收完是个矮而圆润的小丘。直接反向生长会在中心压出坑、边缘鼓出一圈
                        // （围度不降反升，丰满度越小乳房反而越大），截断到胸壁又会压成一块
                        // 平板 —— 两种都不是小乳房该有的形状
                        // 分母要跟着核的峰值走：shrink 吃的是归一化的核值（0~1），峰值换了
                        // 不跟着改的话，AA / A 收小杯的比例就跟着核一起变了
                        float shrink = Math.min(0.88f, -bustUnits * 0.95f) * (w / 2.30f);
                        nz -= Math.max(0f, nz - wallZ) * shrink;
                        // 横向也要跟着收：朝中轴那一份要封顶，否则胸骨前会被撕开一道口子
                        nx += lateralSpread(nx, qx, -bustRx * shrink * 0.55f);
                    } else {
                        // 向两侧铺开的份额 0.55 → 0.85 → 1.25 → 0.70 → 1.20。原先 lateralSpread
                        // 对所有点（含乳点内侧）均匀外推，那份才撑得起饱满度；改成钟形 + 乳点
                        // 内区钉死后，同样 0.70 的有效外扩变小，乳房被压扁（杯差掉一大截）。所以
                        // 把系数提回 1.20 补回体积。lateralSpread 内部已把乳点内区整块钉死、外侧
                        // 钟形渐起 + 腋缘收零，最鼓点始终落在乳点、且外扩再大也不会在腋缘堆出
                        // 侧峰（实测 1.40 仍是干净单穹顶），故这里可以放心给足体积
                        nx += lateralSpread(nx, qx, grow * 1.20f * (bs[1] / 0.048f));
                        nz += grow * kz;
                        // 纵向也试过按径向铺开（上缘往上、下缘往下一起撑开）：面积比只多
                        // 0.003，等于没变，可 G 杯那一圈挪得比体素间距还大，乳下缘现出一道
                        // 1.8cm 的陡坎（原来 0.5cm）。乳房这一块的形状是核给的，不是靠把顶点
                        // 沿 y 推开推出来的，所以不加这一项 —— 下垂另算（下面的 bustDroop）
                    }
                    // 整体下坠留一点点：形状由上面那份不对称给，这里只让整只乳房再沉下去一些，
                    // 挪多了又会把上缘拖成平尾巴（实测挪满时水滴的上极凸度掉到 0.00）
                    if (bustDroop > 0f) v[i + 1] -= grow * bustDroop * 0.35f;
                }
            }
            // 脚：脚掌长 / 宽单独缩放，踝部以上逐渐过渡回腿，避免脚踝一圈突变
            float fw = 1f - smoothstep((y - yFootTop) / (0.03f * h));
            if (fw > 0f) {
                float fx = x >= 0f ? legX : -legX;
                nx = mix(nx, fx + (x - fx) * footW, fw);
                nz = mix(nz, z * footL, fw);
            }
            v[i] = nx;
            v[i + 2] = nz;
        }
        // 乳腺长完后再对最终网格轻抹一遍：生长边界处曲率有跳变，正面会现一道明暗硬边，
        // 这里用同一套箱式平均把边界的法线变化摊平（见 smoothChestFinal）
        smoothChestFinal(v, h, 0.12f);
    }


    /**
     * 脸型 / 五官：Anny 的形变基里没有脸这部分，这里按人脸的纵向分带补上。
     * 语义与原生引擎一致：脸宽 / 脸长之外，下颌宽、下巴长度与前突、颧骨、额头
     * 各自作用在自己那条纵向带上，所以两套引擎调到同一档时脸是一致的。
     */
    /**
     * 前胸轻度平滑：基础网格在乳晕与胸大肌那一圈本身有一道台阶，乳腺生长叠上去之后，
     * 交界处法线不连续，正面就现出一条横向的明暗断层。这里先在前胸做一次箱式平均
     * （1cm 网格、5×5 窗）并把每点朝格平均混合 mix，把台阶先抹平，再让光滑的乳腺长上去。
     */
    /**
     * 最终网格的轻度平滑（后处理）：乳腺长完之后，生长边界处曲率仍有跳变，正面会现出一道
     * 横向的明暗硬边。这里在同一片前胸/体侧上再做一次箱式平均（覆盖 z 略小于 0 的体侧），
     * 把边界的法线变化摊平。幅度取很小（mix≈0.12）：箱式平均在乳面上会"摊平"，取大了胸围
     * 会被压掉好几厘米（实测 mix=0.45 时胸围 90.7→76.4cm），0.12 只柔化交界、几乎不改形状。
     */
    private static void smoothChestFinal(float[] v, float h, float mix) {
        int G = 30;
        float xLo = -0.16f, xHi = 0.16f, yLo = 0.58f * h, yHi = 0.82f * h, zLo = -0.06f * h;
        float[] sum = new float[G * G]; int[] cnt = new int[G * G];
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 2] < zLo) continue;
            float x = v[i], y = v[i + 1];
            if (x < xLo || x > xHi || y < yLo || y > yHi) continue;
            int gx = (int) ((x - xLo) / (xHi - xLo) * G), gy = (int) ((y - yLo) / (yHi - yLo) * G);
            if (gx < 0 || gx >= G || gy < 0 || gy >= G) continue;
            sum[gy * G + gx] += v[i + 2]; cnt[gy * G + gx]++;
        }
        float[] bs = new float[G * G]; int[] bc = new int[G * G];
        for (int gy = 0; gy < G; gy++) for (int gx = 0; gx < G; gx++) {
            float sc = 0f; int c = 0;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int a = gx + dx, b = gy + dy;
                if (a < 0 || a >= G || b < 0 || b >= G) continue;
                sc += sum[b * G + a]; c += cnt[b * G + a];
            }
            bs[gy * G + gx] = sc; bc[gy * G + gx] = c;
        }
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 2] < zLo) continue;
            float x = v[i], y = v[i + 1];
            if (x < xLo || x > xHi || y < yLo || y > yHi) continue;
            int gx = (int) ((x - xLo) / (xHi - xLo) * G), gy = (int) ((y - yLo) / (yHi - yLo) * G);
            if (gx < 0 || gx >= G || gy < 0 || gy >= G) continue;
            int k = gy * G + gx; if (bc[k] == 0) continue;
            v[i + 2] += (bs[k] / bc[k] - v[i + 2]) * mix;
        }
    }

    private static void smoothChest(float[] v, float h, float mix) {
        int G = 30;
        float xLo = -0.15f, xHi = 0.15f, yLo = 0.63f * h, yHi = 0.81f * h;
        float[] sum = new float[G * G]; int[] cnt = new int[G * G];
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 2] <= 0f) continue;
            float x = v[i], y = v[i + 1];
            if (x < xLo || x > xHi || y < yLo || y > yHi) continue;
            int gx = (int) ((x - xLo) / (xHi - xLo) * G), gy = (int) ((y - yLo) / (yHi - yLo) * G);
            if (gx < 0 || gx >= G || gy < 0 || gy >= G) continue;
            sum[gy * G + gx] += v[i + 2]; cnt[gy * G + gx]++;
        }
        float[] bs = new float[G * G]; int[] bc = new int[G * G];
        for (int gy = 0; gy < G; gy++) for (int gx = 0; gx < G; gx++) {
            float sc = 0f; int c = 0;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int a = gx + dx, b = gy + dy;
                if (a < 0 || a >= G || b < 0 || b >= G) continue;
                sc += sum[b * G + a]; c += cnt[b * G + a];
            }
            bs[gy * G + gx] = sc; bc[gy * G + gx] = c;
        }
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 2] <= 0f) continue;
            float x = v[i], y = v[i + 1];
            if (x < xLo || x > xHi || y < yLo || y > yHi) continue;
            int gx = (int) ((x - xLo) / (xHi - xLo) * G), gy = (int) ((y - yLo) / (yHi - yLo) * G);
            if (gx < 0 || gx >= G || gy < 0 || gy >= G) continue;
            int k = gy * G + gx; if (bc[k] == 0) continue;
            v[i + 2] += (bs[k] / bc[k] - v[i + 2]) * mix;
        }
    }

    private static void applyFace(float[] v, BodyProfile p, float h) {
        float[] fs = BodyProfile.faceShapeRatio(p);
        float jaw = BodyProfile.clamp(p.jawR * fs[2], 0.70f, 1.35f) - 1f;
        float chin = BodyProfile.clamp(p.chinR, 0.70f, 1.35f) - 1f;
        float cheek = BodyProfile.clamp(p.cheekR, 0.70f, 1.35f) - 1f;
        float forehead = BodyProfile.clamp(p.foreheadR, 0.75f, 1.25f) - 1f;
        if (jaw == 0f && chin == 0f && cheek == 0f && forehead == 0f) return;
        float hh = h / Math.max(4f, p.headRatio);
        float yTop = h;
        float yChin = yTop - hh;
        for (int i = 0; i < v.length; i += 3) {
            float y = v[i + 1];
            if (y < yChin - 0.02f * hh) continue;          // 下巴以下不动
            float x = v[i];
            float z = v[i + 2];
            float u = (y - yChin) / Math.max(1e-4f, hh);   // 0 = 下巴, 1 = 头顶
            float wJaw = ring(y, yChin + 0.34f * hh, 0.16f * hh);
            float wChin = ring(y, yChin + 0.16f * hh, 0.13f * hh);
            float wCheek = ring(y, yChin + 0.55f * hh, 0.13f * hh);
            float wFore = ring(y, yChin + 0.82f * hh, 0.15f * hh);
            float kx = 1f + jaw * 0.45f * wJaw + cheek * 0.40f * wCheek + forehead * 0.22f * wFore;
            float kz = 1f + chin * 0.30f * wChin;
            v[i] = x * kx;
            v[i + 2] = z * kz + chin * 0.020f * hh * wChin * (z > 0f ? 1f : 0.35f);
            if (chin != 0f) v[i + 1] = y + chin * 0.018f * hh * wChin * -1f;
        }
    }

    /**
     * 脸宽 / 脸深 / 脸长：Anny 的头部形变基不覆盖这三项，这里补上。
     *
     * 语义跟原生引擎（HeadMesh.frameAt）同一套：脸长以眉线为界伸缩下半张脸、颅顶不动，
     * 于是下巴与下半张脸整体上下移动 —— 早先这里把脸长乘到了前后方向上（脸的深度），
     * 滑块拉到两端脸既不伸长也不缩短，反倒忽深忽浅。
     * 脸型预设（{@link BodyProfile#faceShapeRatio}）在这里并入：宽 / 深 / 长三项乘率，
     * 下颌那一项交给 applyFace，正好落在它自己那条带的中心 yChin 上。
     */
    private static void applyHead(float[] v, BodyProfile p, float h) {
        float[] fs = BodyProfile.faceShapeRatio(p);
        float fw = fs[0] * (1f + (BodyProfile.clamp(p.faceWidthR, 0.80f, 1.25f) - 1f) * 0.55f) - 1f;
        float fd = fs[1] - 1f;
        float fl = fs[3] * (1f + (BodyProfile.clamp(p.faceLenR, 0.88f, 1.12f) - 1f) * 0.40f) - 1f;
        if (Math.abs(fw) < 1e-4f && Math.abs(fd) < 1e-4f && Math.abs(fl) < 1e-4f) return;
        float hh = h / Math.max(4f, p.headRatio);
        float yTop = h;
        float yChin = yTop - hh;
        float yBrow = yChin + 0.60f * hh;
        double sz = 0;
        int n = 0;
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 1] < yChin) continue;
            sz += v[i + 2];
            n++;
        }
        float cz = n > 0 ? (float) (sz / n) : 0f;
        for (int i = 0; i < v.length; i += 3) {
            float y = v[i + 1];
            float u = (y - yChin) / Math.max(1e-4f, hh);   // 0 = 下巴, 1 = 头顶
            // 颈根以下全部不动（乘率在这里淡出，脖子不会被拉出一道台阶）
            float wNeck = smoothstep((u + 0.14f) / 0.10f);
            if (wNeck <= 0f) continue;
            v[i] *= 1f + fw * wNeck;
            v[i + 2] = cz + (v[i + 2] - cz) * (1f + fd * wNeck);
            if (fl != 0f) {
                // 脸长：眉线以下全额、眉线以上渐隐 —— 于是颅顶不动，下半张脸整体上下移动
                float wUp = 1f - smoothstep((u - 0.55f) / 0.20f);
                v[i + 1] = yBrow + (y - yBrow) * (1f + fl * wUp * wNeck);
            }
        }
    }

    // ------------------------------------------------------------------
    // 分区 / 配色 / 遮蔽
    // ------------------------------------------------------------------

    private static final int R_SKIN = 0;
    private static final int R_HAIR = 1;
    private static final int R_TOP = 2;
    private static final int R_BOTTOM = 3;
    private static final int REGION_COUNT = 4;
    private static final String[] REGION_NAMES = {
            "anny_skin", "anny_hair", "anny_top", "anny_bottom"};

    /**
     * 头皮着色。判据跟发壳铺的那条发际线（{@link HumanMesh#hairline}）完全同一条：
     * 线以上是发色、以下是肤色。以前是拿法线朝上 / 朝后当判据，跟发壳的边界对不上，
     * 于是发壳边缘与头皮之间露出一圈肤色 —— 看着头发浮在头上，头顶还像秃了一块。
     */
    private static int classify(float x, float y, float z, float nx, float ny, float nz, float h,
            float[] box, float[] faceMap, float[] rim, boolean hasHair) {
        float t = y / h;
        float ax = Math.abs(x);
        if (t > 0.85f) {
            float hh = Math.max(1e-3f, box[0] - box[1]);
            float s = (y - box[1]) / hh;
            if (s < 0.02f) return R_SKIN;                      // 下巴以下：脖子
            if (!hasHair) return R_SKIN;                       // 光头就没有发色的头皮
            float phi = (float) Math.atan2(x, z - box[4]);
            float line = rim[1] + (rim[0] - rim[1]) * (0.5f + 0.5f * (float) Math.cos(phi));
            if (faceMap != null) line = HumanMesh.mapFaceS(faceMap, line);
            return s > line ? R_HAIR : R_SKIN;
        }
        if (t < 0.045f) return R_SKIN;
        if (t > 0.855f && ax < 0.045f * h) return R_SKIN;         // 脖子
        if (t > 0.36f && isArm(t, ax, h)) return R_SKIN;          // 手臂与手
        return t >= 0.50f ? R_TOP : R_BOTTOM;                     // 躯干上装 / 腿部下装
    }

    private static int colorOf(int region, BodyProfile p) {
        switch (region) {
            case R_HAIR:
                return p.hair;
            case R_TOP:
                return p.top;
            case R_BOTTOM:
                return p.bottom;
            default:
                return p.skin;
        }
    }

    /** 简易遮蔽：法线与「由体轴向外」越一致越敞亮，腋下 / 裆部这类凹处自然变暗 */
    private static float ambient(float x, float y, float z, float nx, float ny, float nz, float h) {
        float ox = x;
        float oy = y - 0.55f * h;
        float oz = z;
        float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (len < 1e-6f) return 1f;
        float d = (nx * ox + ny * oy + nz * oz) / len;
        float ao = 0.62f + 0.38f * d;
        return ao < 0.45f ? 0.45f : (ao > 1f ? 1f : ao);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static boolean isArm(float t, float ax, float h) {
        return AnnyMeasure.isArm(t, ax, h);
    }

    private static float band(float t, float center, float width) {
        float d = (t - center) / width;
        return (float) Math.exp(-d * d);
    }

    /** 分段线性插值权重：Anny 的原型插值就是各参数独立的分段线性混合 */
    private static float[] knotWeights(float[] knots, float value) {
        float[] w = new float[knots.length];
        if (value <= knots[0]) {
            w[0] = 1f;
            return w;
        }
        if (value >= knots[knots.length - 1]) {
            w[knots.length - 1] = 1f;
            return w;
        }
        for (int i = 0; i + 1 < knots.length; i++) {
            if (value <= knots[i + 1]) {
                float span = knots[i + 1] - knots[i];
                float f = span > 1e-6f ? (value - knots[i]) / span : 0f;
                w[i] = 1f - f;
                w[i + 1] = f;
                return w;
            }
        }
        w[knots.length - 1] = 1f;
        return w;
    }

    private static int majority(int a, int b, int c) {
        return a == b ? a : (b == c ? b : (a == c ? a : b));
    }

    private static float[] rgb(int argb) {
        return new float[]{((argb >> 16) & 0xFF) / 255f,
                ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f};
    }

    private static float cm(float meters) {
        return meters > 0f ? meters * 100f : 0f;
    }
}
