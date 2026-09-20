package com.tsymiar.device2device.avatar;

import android.graphics.Bitmap;

import java.util.Arrays;

/**
 * 照片 / 拍照结果的形象推断。
 *
 * 不做人脸识别（端上没有可用的重建模型），而是走两条稳妥的信息通道：
 * 1) 轮廓：以四周边框估计背景色，抠出人像前景，逐行统计宽度，
 *    取肩 / 腰 / 臀三处的宽度比，换算成模型可用的肩宽 / 腰围 / 臀围倍率；
 * 2) 配色：按人像高度分段（发 / 脸 / 上身 / 下身）取前景像素中位色，
 *    得到发色、肤色、上装色、下装色。
 *
 * 结合用户填写的身高体重，即可把「这张照片的人」还原成等身比例的 3D 模型。
 */
public final class PhotoAnalyzer {

    /** 标准骨架的胸 / 肩、腰 / 肩、臀 / 肩宽度比（与 HumanMesh 默认体型一致） */
    private static final float STD_CHEST = 0.90f;
    private static final float STD_WAIST = 0.652f;
    private static final float STD_HIP = 0.765f;

    public static final class Result {
        /** 下列各色为 0xAARRGGBB；-1 表示该区域样本不足，未推断出来 */
        public int hair = -1;
        public int skin = -1;
        public int top = -1;
        public int bottom = -1;
        public float shoulderR = 1f;
        public float chestR = 1f;
        public float waistR = 1f;
        public float hipR = 1f;
        public boolean fullBody;
        public String note = "";

        /** 脸部区域（归一化到原图 0~1）；faceOk=false 表示没定位到脸 */
        public boolean faceOk;
        public float faceX0, faceY0, faceX1, faceY1;
        /** 由脸部区域量出的五官比例，全部相对标准值，量不出来时保持 1.0 */
        public float faceWidthR = 1f;
        public float faceLenR = 1f;
        public float jawR = 1f;
        public float eyeGapR = 1f;
        public float eyeSizeR = 1f;
        public float noseWR = 1f;
        public float lipTR = 1f;
        public float browR = 1f;

        public int applied() {
            int n = 0;
            if (hair != -1) n++;
            if (skin != -1) n++;
            if (top != -1) n++;
            if (bottom != -1) n++;
            return n;
        }
    }

    private PhotoAnalyzer() {
    }

    public static Result analyze(Bitmap src) {
        Result r = new Result();
        if (src == null) return r;

        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return r;
        float scale = Math.min(1f, 160f / Math.max(w, h));
        int sw = Math.max(8, Math.round(w * scale));
        int sh = Math.max(8, Math.round(h * scale));
        Bitmap bmp = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        bmp.getPixels(px, 0, sw, 0, 0, sw, sh);
        if (bmp != src) bmp.recycle();

        int[] bg = borderColor(px, sw, sh);

        boolean[] fg = null;
        for (int th : new int[]{50, 35, 70, 25, 90}) {
            boolean[] mask = new boolean[sw * sh];
            int count = 0;
            for (int i = 0; i < px.length; i++) {
                if (dist2(px[i], bg) > th * th) {
                    mask[i] = true;
                    count++;
                }
            }
            float ratio = count / (float) px.length;
            if (ratio >= 0.04f && ratio <= 0.85f) {
                fg = mask;
                break;
            }
            if (fg == null) fg = mask;
        }

        int minX = sw, maxX = -1, minY = sh, maxY = -1, count = 0;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                if (fg[y * sw + x]) {
                    count++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (count < sw * sh * 0.02f) {
            Arrays.fill(fg, true);
            minX = 0;
            maxX = sw - 1;
            minY = 0;
            maxY = sh - 1;
        }
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        if (bw <= 2 || bh <= 2) return r;

        r.fullBody = bh > sh * 0.88f;

        // ---- 轮廓宽度：肩 / 胸 / 腰 / 臀 ----
        int[] rowW = new int[sh];
        for (int y = 0; y < sh; y++) {
            int c = 0;
            for (int x = 0; x < sw; x++) {
                if (fg[y * sw + x]) c++;
            }
            rowW[y] = c;
        }
        int shoulderPx = maxIn(rowW, minY + (int) (0.16f * bh), minY + (int) (0.30f * bh));
        int chestPx = maxIn(rowW, minY + (int) (0.26f * bh), minY + (int) (0.40f * bh));
        int waistPx = minIn(rowW, minY + (int) (0.40f * bh), minY + (int) (0.56f * bh));
        int hipPx = maxIn(rowW, minY + (int) (0.58f * bh), minY + (int) (0.72f * bh));
        if (shoulderPx > 2 && chestPx > 2) {
            r.chestR = clamp((chestPx / (float) shoulderPx) / STD_CHEST, 0.70f, 1.60f);
        }
        if (shoulderPx > 2 && waistPx > 2) {
            r.waistR = clamp((waistPx / (float) shoulderPx) / STD_WAIST, 0.70f, 1.60f);
        }
        if (shoulderPx > 2 && hipPx > 2) {
            r.hipR = clamp((hipPx / (float) shoulderPx) / STD_HIP, 0.70f, 1.60f);
        }

        // ---- 分段取色 ----
        r.hair = median(px, sw, fg, minX, minY + (int) (0.02f * bh), minX + bw, minY + (int) (0.13f * bh));
        // 肤色：人脸带里先筛出皮肤像素再掐掉高光 / 阴影；整块取中位会把头发、衣领、背景一起算进来
        r.skin = skinColor(px, sw, fg, minX + (int) (0.18f * bw), minY + (int) (0.13f * bh),
                minX + (int) (0.82f * bw), minY + (int) (0.30f * bh), null);
        if (r.skin == -1) {
            r.skin = median(px, sw, fg, minX + (int) (0.18f * bw), minY + (int) (0.13f * bh),
                    minX + (int) (0.82f * bw), minY + (int) (0.28f * bh));
        }
        r.top = median(px, sw, fg, minX, minY + (int) (0.30f * bh), minX + bw, minY + (int) (0.56f * bh));
        if (r.fullBody) {
            r.bottom = median(px, sw, fg, minX, minY + (int) (0.62f * bh), minX + bw, minY + (int) (0.90f * bh));
        }

        // ---- 脸部区域与五官比例 ----
        analyzeFace(px, sw, sh, fg, minX, maxX, minY, bh, r.skin, r.fullBody, r);

        // 脸框定位出来后，用框内的皮肤像素再精修一次：粗采样区常混着头发与衣领
        if (r.faceOk) {
            int[] rough = r.skin == -1 ? null
                    : new int[]{(r.skin >> 16) & 0xFF, (r.skin >> 8) & 0xFF, r.skin & 0xFF};
            int fine = skinColor(px, sw, fg,
                    Math.round(r.faceX0 * sw) + 1, Math.round(r.faceY0 * sh) + 1,
                    Math.round(r.faceX1 * sw) - 1, Math.round(r.faceY1 * sh) - 1, rough);
            if (fine != -1) r.skin = fine;
        }
        if (r.skin != -1) r.skin = calibrateSkin(r.skin);

        StringBuilder note = new StringBuilder("照片推断：");
        if (r.skin != -1) {
            note.append("肤色 #").append(String.format("%06X", r.skin & 0xFFFFFF)).append(" ");
        }
        if (r.hair != -1) note.append("发色 ");
        if (r.top != -1) note.append("上装 ");
        if (r.bottom != -1) note.append("下装 ");
        if (r.applied() == 0) note.append("（人像占比过低，未取到配色）");
        note.append("｜轮廓：胸×").append(String.format("%.2f", r.chestR))
                .append(" 腰×").append(String.format("%.2f", r.waistR))
                .append(" 臀×").append(String.format("%.2f", r.hipR))
                .append(r.fullBody ? "（全身照）" : "（半身照）");
        if (r.faceOk) note.append("｜脸部已定位，五官按照片对齐");
        r.note = note.toString();
        return r;
    }

    // ------------------------------------------------------------------

    private static int[] borderColor(int[] px, int w, int h) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int x = 0; x < w; x++) {
            for (int k = 0; k < 2; k++) {
                int top = px[k * w + x];
                int bot = px[(h - 1 - k) * w + x];
                r += (top >> 16) & 0xFF; g += (top >> 8) & 0xFF; b += top & 0xFF;
                r += (bot >> 16) & 0xFF; g += (bot >> 8) & 0xFF; b += bot & 0xFF;
                n += 2;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int k = 0; k < 2; k++) {
                int left = px[y * w + k];
                int right = px[y * w + (w - 1 - k)];
                r += (left >> 16) & 0xFF; g += (left >> 8) & 0xFF; b += left & 0xFF;
                r += (right >> 16) & 0xFF; g += (right >> 8) & 0xFF; b += right & 0xFF;
                n += 2;
            }
        }
        if (n == 0) return new int[]{255, 255, 255};
        return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
    }

    private static int dist2(int c, int[] bg) {
        int dr = ((c >> 16) & 0xFF) - bg[0];
        int dg = ((c >> 8) & 0xFF) - bg[1];
        int db = (c & 0xFF) - bg[2];
        return dr * dr + dg * dg + db * db;
    }

    private static int maxIn(int[] rowW, int from, int to) {
        int v = 0;
        for (int i = Math.max(0, from); i <= Math.min(rowW.length - 1, to); i++) {
            if (rowW[i] > v) v = rowW[i];
        }
        return v;
    }

    private static int minIn(int[] rowW, int from, int to) {
        int v = Integer.MAX_VALUE;
        boolean hit = false;
        for (int i = Math.max(0, from); i <= Math.min(rowW.length - 1, to); i++) {
            if (rowW[i] > 0 && rowW[i] < v) {
                v = rowW[i];
                hit = true;
            }
        }
        return hit ? v : 0;
    }

    /** 区域内前景像素的逐通道中位色；样本不足返回 -1 */
    private static int median(int[] px, int w, boolean[] fg, int x0, int y0, int x1, int y1) {
        int h = px.length / w;
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(w, x1);
        y1 = Math.min(h, y1);
        int[] rs = new int[4096];
        int[] gs = new int[4096];
        int[] bs = new int[4096];
        int n = 0;
        for (int y = y0; y < y1 && n < rs.length; y++) {
            for (int x = x0; x < x1 && n < rs.length; x++) {
                int i = y * w + x;
                if (!fg[i]) continue;
                int c = px[i];
                rs[n] = (c >> 16) & 0xFF;
                gs[n] = (c >> 8) & 0xFF;
                bs[n] = c & 0xFF;
                n++;
            }
        }
        if (n < 24) return -1;
        Arrays.sort(rs, 0, n);
        Arrays.sort(gs, 0, n);
        Arrays.sort(bs, 0, n);
        int r = rs[n / 2];
        int g = gs[n / 2];
        int b = bs[n / 2];
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 脸部定位：在人像上部的肤色连通区里找「最宽的一行（颧骨 / 眼高）」，
     * 向上走到发际线、向下走到下巴，得到脸框；再按脸框内的明暗量五官比例。
     * 只做几何 / 亮度统计，不依赖任何识别模型。
     */
    private static void analyzeFace(int[] px, int sw, int sh, boolean[] fg,
                                    int minX, int maxX, int minY, int bh,
                                    int skinColor, boolean fullBody, Result r) {
        int[] sk = skinColor == -1 ? null
                : new int[]{(skinColor >> 16) & 0xFF, (skinColor >> 8) & 0xFF, skinColor & 0xFF};
        int bw = maxX - minX + 1;
        int y0 = Math.max(0, minY);
        int y1 = Math.min(sh - 1, minY + Math.round(0.26f * bh));
        int xlo = minX + Math.round(0.10f * bw);
        int xhi = maxX - Math.round(0.10f * bw);

        int[] rowW = new int[sh];
        for (int y = y0; y <= y1; y++) {
            rowW[y] = skinWidth(px, sw, fg, sk, xlo, xhi, y);
        }
        int yPeak = -1;
        int wPeak = 0;
        for (int y = y0; y <= y1; y++) {
            if (rowW[y] > wPeak) {
                wPeak = rowW[y];
                yPeak = y;
            }
        }
        if (yPeak < 0 || wPeak < 3) return;

        // 上边界：直到肤色行宽掉下去（发际线 / 头发遮挡）
        int yTop = yPeak;
        int upLim = Math.max(2, Math.round(0.30f * wPeak));
        while (yTop > y0 && rowW[yTop - 1] >= upLim) yTop--;
        // 下边界：宽度单调收窄到下巴；颈会重新变宽，故宽度回升即停
        int yBot = yPeak;
        int prev = rowW[yPeak];
        int lowLim = Math.max(2, Math.round(0.22f * wPeak));
        for (int y = yPeak + 1; y <= y1; y++) {
            if (rowW[y] > prev + 1) break;
            if (rowW[y] < lowLim) break;
            yBot = y;
            prev = rowW[y];
        }

        int fx0 = sw;
        int fx1 = -1;
        for (int y = yTop; y <= yBot; y++) {
            for (int x = xlo; x <= xhi; x++) {
                int i = y * sw + x;
                if (fg[i] && isSkin(px[i], sk)) {
                    if (x < fx0) fx0 = x;
                    if (x > fx1) fx1 = x;
                }
            }
        }
        int fw = fx1 - fx0 + 1;
        int fh = yBot - yTop + 1;
        if (fw < 6 || fh < 8) return;
        float aspect = fw / (float) fh;
        if (aspect < 0.42f || aspect > 1.15f) return;

        r.faceOk = true;
        r.faceX0 = fx0 / (float) sw;
        r.faceX1 = (fx1 + 1) / (float) sw;
        r.faceY0 = yTop / (float) sh;
        r.faceY1 = (yBot + 1) / (float) sh;
        // 标准脸：宽 / 长 ≈ 0.74
        r.faceWidthR = clamp(aspect / 0.74f, 0.80f, 1.25f);
        if (fullBody) {
            float stdFace = bh / 7.5f * 0.88f;
            r.faceLenR = clamp(fh / Math.max(4f, stdFace), 0.90f, 1.12f);
        }
        measureFeatures(px, sw, fg, sk, fx0, fx1, yTop, yBot, r);
    }

    /** 在脸框内按明暗统计五官尺寸 */
    private static void measureFeatures(int[] px, int sw, boolean[] fg, int[] sk,
                                        int fx0, int fx1, int yTop, int yBot, Result r) {
        int fw = fx1 - fx0 + 1;
        int fh = yBot - yTop + 1;

        // ---- 下颌宽 / 颧骨宽 ----
        int wCheek = skinWidth(px, sw, fg, sk, fx0, fx1, yTop + Math.round(0.45f * fh));
        int wJaw = skinWidth(px, sw, fg, sk, fx0, fx1, yTop + Math.round(0.72f * fh));
        if (wCheek > 2 && wJaw > 1) {
            float ratio = wJaw / (float) wCheek;
            r.jawR = clamp((ratio / 0.743f - 0.481f) / 0.519f, 0.60f, 1.50f);
        }

        // ---- 眼距 / 眼大小：眼带最暗的一行上找左右两段暗区 ----
        int b0 = yTop + Math.round(0.38f * fh);
        int b1 = yTop + Math.round(0.52f * fh);
        int yEye = darkestRow(px, sw, fg, fx0, fx1, b0, b1);
        if (yEye >= 0) {
            int[] col = colProfile(px, sw, fg, fx0, fw, Math.max(yTop, yEye - 1), Math.min(yBot, yEye + 1));
            if (col != null) {
                int[] sorted = col.clone();
                Arrays.sort(sorted);
                int th = (int) (sorted[sorted.length / 2] * 0.80f);
                float[] le = longestDark(col, 0, fw / 2, th);
                float[] re = longestDark(col, fw / 2, fw, th);
                if (le != null && re != null) {
                    float gap = (re[0] - le[0]) / (float) fw;
                    r.eyeGapR = clamp(gap / 0.43f, 0.72f, 1.35f);
                    float ew = 0.5f * (le[1] + re[1]) / (float) fw;
                    r.eyeSizeR = clamp(ew / 0.095f, 0.70f, 1.40f);
                }
            }
        }

        // ---- 鼻宽：鼻底一带中央的暗区宽度 ----
        int[] colN = colProfile(px, sw, fg, fx0, fw,
                yTop + Math.round(0.52f * fh), yTop + Math.round(0.66f * fh));
        if (colN != null) {
            int[] sorted = colN.clone();
            Arrays.sort(sorted);
            int th = (int) (sorted[sorted.length / 2] * 0.92f);
            int a = Math.max(0, Math.round(fw * 0.20f));
            int b = Math.min(fw, Math.round(fw * 0.80f));
            float[] nr = longestDark(colN, a, b, th);
            if (nr != null) {
                r.noseWR = clamp((nr[1] / (float) fw) / 0.28f, 0.70f, 1.40f);
            }
        }

        float medAll = medianLum(px, sw, fg, fx0, fx1, yTop, yBot);

        // ---- 眉：眉带里偏暗的行数 ----
        int browRows = 0;
        int browTotal = 0;
        for (int y = yTop + Math.round(0.24f * fh); y <= yTop + Math.round(0.40f * fh); y++) {
            float m = rowLum(px, sw, fg, fx0, fx1, y);
            if (m <= 0f) continue;
            browTotal++;
            if (m < medAll * 0.88f) browRows++;
        }
        if (browTotal > 2 && medAll > 0f) {
            r.browR = clamp((browRows / (float) fh) / 0.045f, 0.60f, 1.80f);
        }

        // ---- 唇：唇带里偏红的行数 ----
        int lipRows = 0;
        int lipTotal = 0;
        int lx0 = fx0 + Math.round(0.26f * fw);
        int lx1 = fx1 - Math.round(0.26f * fw);
        for (int y = yTop + Math.round(0.66f * fh); y <= yTop + Math.round(0.84f * fh); y++) {
            lipTotal++;
            if (rowRed(px, sw, fg, lx0, lx1, y)) lipRows++;
        }
        if (lipTotal > 2 && lipRows > 0) {
            r.lipTR = clamp((lipRows / (float) fh) / 0.043f, 0.60f, 1.70f);
        }
    }

    // ------------------------------------------------------------------
    // 脸部统计小工具
    // ------------------------------------------------------------------

    /**
     * 肤色判据（光照不变）：只用归一化色度 r/(r+g+b)、g/(r+g+b) 与 RGB 排序。
     * 绝对阈值（R>95 之类）在暗光 / 逆光下整张脸都会判不上，色度比值则不受明暗影响，
     * 于是深肤色、阴影里的脸颊也能留在样本里。
     */
    private static boolean skinTone(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int sum = r + g + b;
        if (sum < 90 || sum > 730) return false;          // 太暗不看、接近纯白（高光）不看
        float rn = r / (float) sum;
        float gn = g / (float) sum;
        if (rn < 0.330f || rn > 0.560f) return false;     // 0.40 上下：黄种人到深肤色
        if (gn < 0.260f || gn > 0.400f) return false;
        if (!(r >= g && g >= b)) return false;
        // 暗部里头发与深肤色的色度几乎一样（都是棕色），只能靠「够不够红」分开：越暗要求越红
        int lum = (299 * r + 587 * g + 114 * b) / 1000;
        return (r - b) >= (lum < 55 ? 30 : 12);
    }

    private static boolean isSkin(int c, int[] ref) {
        if (!skinTone(c)) return false;
        if (ref == null) return true;
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        float sum = r + g + b;
        float rs = ref[0] / (ref[0] + ref[1] + ref[2] + 1e-4f);
        float gs = ref[1] / (ref[0] + ref[1] + ref[2] + 1e-4f);
        float dr = r / sum - rs;
        float dg = g / sum - gs;
        // 比色度而不是比绝对 RGB：脸颊亮部与下颌暗部本来就该判成同一种皮肤
        return dr * dr + dg * dg < 0.0064f;               // 色度距离 < 0.08
    }

    /**
     * 区域肤色：先用肤色判据把皮肤像素挑出来（ref 非空时以它为参考再收紧），
     * 再按亮度掐掉最暗 15% 与最亮 15% —— 阴影偏冷、高光偏白，都不代表本色，
     * 直接对整块区域取中位会把头发、衣领、背景一起算进去（这正是之前肤色跑偏的原因）。
     */
    private static int skinColor(int[] px, int w, boolean[] fg, int x0, int y0, int x1, int y1, int[] ref) {
        int h = px.length / w;
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(w, x1);
        y1 = Math.min(h, y1);
        if (x1 - x0 < 3 || y1 - y0 < 3) return -1;
        int cap = 8192;
        int[] rs = new int[cap];
        int[] gs = new int[cap];
        int[] bs = new int[cap];
        float[] ls = new float[cap];
        int n = 0;
        for (int y = y0; y < y1 && n < cap; y++) {
            for (int x = x0; x < x1 && n < cap; x++) {
                int i = y * w + x;
                if (!fg[i]) continue;
                int c = px[i];
                if (!isSkin(c, ref)) continue;
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                rs[n] = r;
                gs[n] = g;
                bs[n] = b;
                ls[n] = (299 * r + 587 * g + 114 * b) / 1000f;
                n++;
            }
        }
        if (n < 48) return -1;

        int[] hist = new int[256];
        for (int i = 0; i < n; i++) hist[Math.min(255, (int) ls[i])]++;
        int cut = n * 15 / 100;
        int lo = 0;
        int hi = 255;
        int acc = 0;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc > cut) {
                lo = v;
                break;
            }
        }
        acc = 0;
        for (int v = 255; v >= 0; v--) {
            acc += hist[v];
            if (acc > cut) {
                hi = v;
                break;
            }
        }
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (ls[i] < lo || ls[i] > hi) continue;
            rs[m] = rs[i];
            gs[m] = gs[i];
            bs[m] = bs[i];
            m++;
        }
        if (m < 24) return -1;
        Arrays.sort(rs, 0, m);
        Arrays.sort(gs, 0, m);
        Arrays.sort(bs, 0, m);
        return 0xFF000000 | (rs[m / 2] << 16) | (gs[m / 2] << 8) | bs[m / 2];
    }

    /**
     * 肤色校准：亮度拉回可用区间（保留照片里偏白 / 偏黑的倾向），
     * 色度向典型人脸色靠 25%，抵消白平衡与环境光把脸拍成灰白、发青、发紫的问题。
     */
    private static int calibrateSkin(int c) {
        float r = ((c >> 16) & 0xFF) / 255f;
        float g = ((c >> 8) & 0xFF) / 255f;
        float b = (c & 0xFF) / 255f;
        float lum = 0.30f * r + 0.59f * g + 0.11f * b;
        float target = clamp(lum, 0.32f, 0.88f);           // 模型上不能黑成一团或白到过曝
        float sum = r + g + b;
        if (sum > 1e-4f) {
            float rn = r / sum;
            float gn = g / sum;
            float bn = b / sum;
            // 越灰（白平衡跑偏 / 强闪光）越要往典型人脸色拉，正常肤色只做轻微修正
            float mx = Math.max(r, Math.max(g, b));
            float mn = Math.min(r, Math.min(g, b));
            float sat = mx > 1e-4f ? (mx - mn) / mx : 0f;
            float k = 0.25f + 0.55f * clamp(1f - sat / 0.30f, 0f, 1f);
            rn += (0.400f - rn) * k;
            gn += (0.332f - gn) * k;
            bn = Math.max(0f, 1f - rn - gn);
            float nl = Math.max(0.05f, 0.30f * rn + 0.59f * gn + 0.11f * bn);
            r = clamp(rn * target / nl, 0f, 1f);
            g = clamp(gn * target / nl, 0f, 1f);
            b = clamp(bn * target / nl, 0f, 1f);
        } else {
            r = g = b = target;
        }
        if (g > r) g = r;                                   // 人脸不会出现冷色压过红通道的肤色
        if (b > g) b = g;
        int ri = Math.round(r * 255f);
        int gi = Math.round(g * 255f);
        int bi = Math.round(b * 255f);
        return 0xFF000000 | (iclamp(ri, 0, 255) << 16) | (iclamp(gi, 0, 255) << 8) | iclamp(bi, 0, 255);
    }

    /** 一行里肤色前景像素的个数 */
    private static int skinWidth(int[] px, int w, boolean[] fg, int[] sk, int x0, int x1, int y) {
        int n = 0;
        for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
            int i = y * w + x;
            if (i >= 0 && i < px.length && fg[i] && isSkin(px[i], sk)) n++;
        }
        return n;
    }

    /** 区间内最暗（平均亮度最低）的一行 */
    private static int darkestRow(int[] px, int w, boolean[] fg, int x0, int x1, int y0, int y1) {
        int best = -1;
        float bestLum = Float.MAX_VALUE;
        for (int y = y0; y <= y1; y++) {
            float m = rowLum(px, w, fg, x0, x1, y);
            if (m > 0f && m < bestLum) {
                bestLum = m;
                best = y;
            }
        }
        return best;
    }

    private static float rowLum(int[] px, int w, boolean[] fg, int x0, int x1, int y) {
        long s = 0;
        int n = 0;
        for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
            int i = y * w + x;
            if (i < 0 || i >= px.length || !fg[i]) continue;
            int c = px[i];
            s += (299 * ((c >> 16) & 0xFF) + 587 * ((c >> 8) & 0xFF) + 114 * (c & 0xFF)) / 1000;
            n++;
        }
        return n < 3 ? 0f : s / (float) n;
    }

    /** 一行里「偏红」像素占比是否够高（唇色） */
    private static boolean rowRed(int[] px, int w, boolean[] fg, int x0, int x1, int y) {
        int hit = 0;
        int n = 0;
        for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
            int i = y * w + x;
            if (i < 0 || i >= px.length || !fg[i]) continue;
            int c = px[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            n++;
            if (r > 55 && r - g > 12 && r - b > 10) hit++;
        }
        return n >= 3 && hit / (float) n > 0.16f;
    }

    /** 若干行的逐列平均亮度 */
    private static int[] colProfile(int[] px, int w, boolean[] fg, int x0, int n, int y0, int y1) {
        if (n <= 0 || y1 < y0) return null;
        int[] col = new int[n];
        for (int k = 0; k < n; k++) {
            long s = 0;
            int c = 0;
            for (int y = y0; y <= y1; y++) {
                int i = y * w + (x0 + k);
                if (i < 0 || i >= px.length || !fg[i]) continue;
                int v = px[i];
                s += (299 * ((v >> 16) & 0xFF) + 587 * ((v >> 8) & 0xFF) + 114 * (v & 0xFF)) / 1000;
                c++;
            }
            col[k] = c == 0 ? 255 : (int) (s / c);
        }
        return col;
    }

    /** 区间内最长的一段暗区：{中心, 长度} */
    private static float[] longestDark(int[] col, int a, int b, int th) {
        int best = 0;
        int bestC = -1;
        int run = 0;
        int start = -1;
        for (int x = Math.max(0, a); x < Math.min(col.length, b); x++) {
            if (col[x] <= th) {
                if (run == 0) start = x;
                run++;
                if (run > best) {
                    best = run;
                    bestC = start + run / 2;
                }
            } else {
                run = 0;
            }
        }
        return best < 2 ? null : new float[]{bestC, best};
    }

    private static float medianLum(int[] px, int w, boolean[] fg, int x0, int x1, int y0, int y1) {
        int[] v = new int[2048];
        int n = 0;
        for (int y = y0; y <= y1 && n < v.length; y++) {
            for (int x = x0; x <= x1 && n < v.length; x++) {
                int i = y * w + x;
                if (i < 0 || i >= px.length || !fg[i]) continue;
                int c = px[i];
                v[n++] = (299 * ((c >> 16) & 0xFF) + 587 * ((c >> 8) & 0xFF) + 114 * (c & 0xFF)) / 1000;
            }
        }
        if (n < 16) return 0f;
        Arrays.sort(v, 0, n);
        return v[n / 2];
    }

    /**
     * 从裁好的脸部贴图里再取一次肤色：脸是照片烘焙的，脖子 / 手 / 身体用的是 p.skin，
     * 两处基色不一致就会出现「脸和脖子两个颜色」，所以以脸部贴图的肤色为准。
     */
    public static int faceSkin(Bitmap face, int fallback) {
        if (face == null) return fallback;
        int w = face.getWidth();
        int h = face.getHeight();
        if (w < 8 || h < 8) return fallback;
        int[] px = new int[w * h];
        face.getPixels(px, 0, w, 0, 0, w, h);
        boolean[] fg = new boolean[w * h];
        Arrays.fill(fg, true);
        // 上下各留一段：上方可能还带头发，下方是下巴阴影与衣领
        int c = skinColor(px, w, fg,
                Math.round(0.18f * w), Math.round(0.22f * h),
                Math.round(0.82f * w), Math.round(0.74f * h), null);
        return c == -1 ? fallback : calibrateSkin(c);
    }

    /**
     * 按分析结果裁出脸部贴图：原图 → 脸框 → 统一尺寸。
     * HeadMesh 会把这张图按柱面展开烘焙到脸部顶点色上。
     */
    public static Bitmap cropFace(Bitmap src, Result r) {
        if (src == null || r == null || !r.faceOk) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int x0 = iclamp(Math.round(r.faceX0 * w), 0, w - 2);
        int x1 = iclamp(Math.round(r.faceX1 * w), x0 + 2, w);
        int y0 = iclamp(Math.round(r.faceY0 * h), 0, h - 2);
        int y1 = iclamp(Math.round(r.faceY1 * h), y0 + 2, h);
        int cw = x1 - x0;
        int ch = y1 - y0;
        if (cw < 4 || ch < 4) return null;
        Bitmap crop = Bitmap.createBitmap(src, x0, y0, cw, ch);
        int tw = 160;
        int th = iclamp(Math.round(tw * ch / (float) cw), 96, 360);
        Bitmap out = Bitmap.createScaledBitmap(crop, tw, th, true);
        if (out != crop) crop.recycle();
        return out;
    }

    private static int iclamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
