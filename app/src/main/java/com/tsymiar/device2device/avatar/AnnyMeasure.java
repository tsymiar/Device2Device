package com.tsymiar.device2device.avatar;

import java.util.ArrayList;

/**
 * Anny 网格上的几何与人体测量工具。
 *
 * 三件事：
 *   1) 归一化：脚底落到 y=0、头顶对齐到身高、水平居中、面朝 +z，
 *      于是 Anny 网格和原生网格一样是「等身比例」，可以直接和地面网格 / 身高标尺比对。
 *   2) 法线：三角面法线按顶点累加后归一化（平滑着色），并在整体朝内时统一翻转。
 *   3) 测量：把三角面与水平面相交得到的线段串成闭环，躯干 / 两臂 / 两腿各自成环，
 *      于是能按环心位置挑出该量的那一圈，量出胸围、腰围、臀围、腿围与裆高。
 */
final class AnnyMeasure {

    /** 一个水平切片上的闭合轮廓 */
    static final class Loop {
        float cx;           // 环心 x
        float cz;           // 环心 z
        float perimeter;    // 周长（米）
        float xMin;         // 这一圈在 x 上的范围：用来判断某个顶点是不是在这一圈上
        float xMax;
    }

    private AnnyMeasure() {
    }

    // ------------------------------------------------------------------
    // 归一化
    // ------------------------------------------------------------------

    /**
     * 脚底 y=0、头顶 y=身高、水平居中。
     *
     * 横向 / 前后的「中心」取骨盆那一圈而不是整个包围盒：Anny 的静息姿态是手臂斜张的
     * A 字 pose，手掌在前后方向并不对称，按包围盒居中会把整个人挂到体轴一侧（实测偏
     * 出 13cm），追赶出来就是躯干歪在一边、头与追加的五官都对不上。
     */
    static void normalize(float[] v, float targetH) {
        float[] b = bounds(v);
        float h = b[4] - b[1];
        if (h < 1e-4f) return;
        float s = targetH / h;
        float cx = (b[0] + b[3]) * 0.5f;
        float[] pelvis = pelvis(v, b, h);
        float cz = pelvis != null ? pelvis[0] : (b[2] + b[5]) * 0.5f;
        for (int i = 0; i < v.length; i += 3) {
            v[i] = (v[i] - cx) * s;
            v[i + 1] = (v[i + 1] - b[1]) * s;
            v[i + 2] = (v[i + 2] - cz) * s;
        }
    }

    /**
     * 摆正朝向：让脸朝 +z（与原生引擎一致）。
     *
     * 单看脚是不行的 —— 原来用「脚尖离脚部质心更远」判定，Anny 的赤脚网格上这个关系
     * 与真人的相反，判成不必镜像，于是整个网格保持「背朝 +z」，默认视角看到的就是后背，
     * 追加的五官也跟着落到后脑勺上（脸上 emptiness）。
     *
     * 这里改用两处吊ﾉ却不受赤脚影响的前后不对称，都以骨盆切片的中线为参考，且与原生
     * 引擎逐项对齐过：
     *   1) 头 / 颈的位置：人站立时头略在骨盆中线之前；
     *   2) 胸 / 背：同一高度上胸侧的极点比肩胛一侧更靠外。
     * 两项都反过来才判成「需要镜像」，任一项单独成立就够 —— 平手时才退回脚判据。
     */
    static void faceForward(float[] v) {
        float[] b = bounds(v);
        float h = b[4] - b[1];
        if (h < 1e-4f) return;
        float[] pelvis = pelvis(v, b, h);
        if (pelvis == null) return;              // 退化网格：摆不正就保持原样
        float ref = pelvis[0];
        int vote = 0;
        // ① 头 / 颈：头顶往下一个头高之内，平均 z 应在参考线之前
        float head = meanZ(v, b[4] - h / Math.max(4f, 7.5f), b[4], 0.09f * h) - ref;
        if (Math.abs(head) > 0.004f * h) vote += head > 0f ? 1 : -1;
        // ② 胸 / 背：0.72H 那一层的躯干前极（胸）应比后极（肩胛）更远
        float[] chest = poles(v, b[1] + 0.72f * h, 0.012f * h, 0.09f * h);
        if (chest != null && Math.abs(chest[0] - chest[1]) > 0.004f * h) {
            vote += chest[0] - ref > ref - chest[1] ? 1 : -1;
        }
        if (vote == 0) {
            vote = footFacing(v, b, h);
        }
        if (vote < 0) {
            mirrorZ(v, ref);                     // 整体转过来让脸朝 +z
        }
    }

    /** 某段高度里所有顶点的平均 z；没有样本返回 NaN */
    private static float meanZ(float[] v, float yLo, float yHi, float xLim) {
        double sz = 0;
        int n = 0;
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 1] < yLo || v[i + 1] > yHi) continue;
            if (Math.abs(v[i]) > xLim) continue;
            sz += v[i + 2];
            n++;
        }
        return n > 0 ? (float) (sz / n) : Float.NaN;
    }

    /** 某个薄切片上躯干的前后极点：{ 最前 z, 最后 z }；没有样本返回 null */
    private static float[] poles(float[] v, float y, float band, float xLim) {
        float zmin = Float.MAX_VALUE;
        float zmax = -Float.MAX_VALUE;
        for (int i = 0; i < v.length; i += 3) {
            if (Math.abs(v[i + 1] - y) > band) continue;
            if (Math.abs(v[i]) > xLim) continue;
            if (v[i + 2] < zmin) zmin = v[i + 2];
            if (v[i + 2] > zmax) zmax = v[i + 2];
        }
        return zmin == Float.MAX_VALUE ? null : new float[]{zmax, zmin};
    }

    /** 脚判据（弱）：脚趾一侧离脚部质心更远就当作正面 */
    private static int footFacing(float[] v, float[] b, float h) {
        float footTop = b[1] + 0.07f * h;
        double sz = 0;
        int n = 0;
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 1] > footTop) continue;
            sz += v[i + 2];
            n++;
        }
        if (n < 8) return 0;
        float cz = (float) (sz / n);
        float plus = 0f;
        float minus = 0f;
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 1] > footTop) continue;
            plus = Math.max(plus, v[i + 2] - cz);
            minus = Math.max(minus, cz - v[i + 2]);
        }
        return plus >= minus ? 1 : -1;
    }

    /**
     * 骨盆那一圈（腰线以下、裆以上，排除两臂）的前后极点：{ 中心 z, 最前 z, 最后 z }；
     * 采样不足返回 null。摆正朝向与水平居中都用这一段，两者的「中心」才是同一个。
     */
    private static float[] pelvis(float[] v, float[] b, float h) {
        float cx = (b[0] + b[3]) * 0.5f;
        float lo = b[1] + 0.44f * h;
        float hi = b[1] + 0.56f * h;
        float xLim = 0.09f * h;
        float zmin = Float.MAX_VALUE;
        float zmax = -Float.MAX_VALUE;
        int n = 0;
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 1] < lo || v[i + 1] > hi) continue;
            if (Math.abs(v[i] - cx) > xLim) continue;      // 排除张开的两臂与手
            float z = v[i + 2];
            if (z < zmin) zmin = z;
            if (z > zmax) zmax = z;
            n++;
        }
        return n < 16 ? null : new float[]{(zmin + zmax) * 0.5f, zmax, zmin};
    }

    private static void mirrorZ(float[] v, float cz) {
        for (int i = 0; i < v.length; i += 3) v[i + 2] = 2f * cz - v[i + 2];
    }

    static float[] bounds(float[] v) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < v.length; i += 3) {
            if (v[i] < b[0]) b[0] = v[i];
            if (v[i + 1] < b[1]) b[1] = v[i + 1];
            if (v[i + 2] < b[2]) b[2] = v[i + 2];
            if (v[i] > b[3]) b[3] = v[i];
            if (v[i + 1] > b[4]) b[4] = v[i + 1];
            if (v[i + 2] > b[5]) b[5] = v[i + 2];
        }
        return b;
    }

    // ------------------------------------------------------------------
    // 法线
    // ------------------------------------------------------------------

    static void computeNormals(float[] v, short[] idx, float[] out) {
        for (int t = 0; t < idx.length; t += 3) {
            int a = idx[t] & 0xFFFF;
            int b = idx[t + 1] & 0xFFFF;
            int c = idx[t + 2] & 0xFFFF;
            float ux = v[b * 3] - v[a * 3];
            float uy = v[b * 3 + 1] - v[a * 3 + 1];
            float uz = v[b * 3 + 2] - v[a * 3 + 2];
            float wx = v[c * 3] - v[a * 3];
            float wy = v[c * 3 + 1] - v[a * 3 + 1];
            float wz = v[c * 3 + 2] - v[a * 3 + 2];
            float nx = uy * wz - uz * wy;
            float ny = uz * wx - ux * wz;
            float nz = ux * wy - uy * wx;
            for (int k = 0; k < 3; k++) {
                int j = k == 0 ? a : (k == 1 ? b : c);
                out[j * 3] += nx;
                out[j * 3 + 1] += ny;
                out[j * 3 + 2] += nz;
            }
        }
        for (int i = 0; i < out.length; i += 3) {
            float len = (float) Math.sqrt(out[i] * out[i] + out[i + 1] * out[i + 1]
                    + out[i + 2] * out[i + 2]);
            if (len > 1e-8f) {
                out[i] /= len;
                out[i + 1] /= len;
                out[i + 2] /= len;
            } else {
                out[i + 1] = 1f;
            }
        }
    }

    /**
     * 网格的三角形绕序不一定一致，这里按「法线应指向体外」统一纠正一次：
     * 与由体轴指向顶点的方向做点积，整体为负说明法线朝内，全部取反。
     */
    static void orientNormals(float[] v, float[] n) {
        float[] b = bounds(v);
        float cy = (b[1] + b[4]) * 0.5f;
        double sum = 0;
        int count = 0;
        for (int i = 0; i < v.length; i += 3) {
            float ox = v[i];
            float oy = v[i + 1] - cy;
            float oz = v[i + 2];
            float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (len < 1e-6f) continue;
            sum += (n[i] * ox + n[i + 1] * oy + n[i + 2] * oz) / len;
            count++;
        }
        if (count > 0 && sum < 0) {
            for (int i = 0; i < n.length; i++) n[i] = -n[i];
        }
    }

    // ------------------------------------------------------------------
    // 测量
    // ------------------------------------------------------------------

    /** 某个高度上的一圈周长（米），取环心 x 最接近 targetX 的那个环；量不到返回 -1 */
    static float girthAt(float[] v, short[] idx, float y, float targetX) {
        ArrayList<Loop> loops = loopsAt(v, idx, y);
        float best = -1f;
        float bestD = Float.MAX_VALUE;
        for (Loop l : loops) {
            if (l.perimeter <= 1e-4f) continue;
            float d = Math.abs(l.cx - targetX);
            if (d < bestD) {
                bestD = d;
                best = l.perimeter;
            }
        }
        return best;
    }

    /**
     * 裆高：自上往下找第一个「左右各有一个环」的高度，也就是两腿分开的位置。
     *
     * 注意 Anny 的静息姿态是 A 字 pose（手臂斜向下张开），肩线以下的切片上可能同时
     * 出现「两臂 + 躯干」三个环，其中的两个手臂环会被误当成两条腿；这里用手臂必然
     * 更靠外这一点把它们排除掉（见 {@link #isArm}）。
     */
    static float crotchHeight(float[] v, short[] idx, float h) {
        float lo = 0.30f * h;
        float hi = 0.58f * h;
        for (int s = 0; s <= 24; s++) {
            float y = hi - (hi - lo) * s / 24f;
            ArrayList<Loop> loops = loopsAt(v, idx, y);
            float lim = isArmLimit(y / h, h);
            boolean left = false;
            boolean right = false;
            for (Loop l : loops) {
                if (l.perimeter <= 1e-4f) continue;
                if (l.cx < -0.02f * h && l.cx > -lim) left = true;
                if (l.cx > 0.02f * h && l.cx < lim) right = true;
            }
            if (left && right) return y;
        }
        return 0.475f * h;                    // 兜底：7.5 头身的下裆高
    }

    /** 裆部以下两条腿的周长平均值（米）：该高度还没分成两条腿时返回 -1 */
    static float legGirth(float[] v, short[] idx, float y, float h) {
        ArrayList<Loop> loops = loopsAt(v, idx, y);
        float lim = isArmLimit(y / h, h);
        float sum = 0f;
        int n = 0;
        for (Loop l : loops) {
            if (l.perimeter <= 1e-4f) continue;
            if (Math.abs(l.cx) < 0.02f * h || Math.abs(l.cx) > lim) continue;
            sum += l.perimeter;
            n++;
        }
        return n > 0 ? sum / n : -1f;
    }

    /** 躯干半宽（相对身高）：用于把手臂从躯干里分出来 */
    static float torsoHalf(float t) {
        if (t <= 0.50f) return 0.104f;
        if (t <= 0.56f) return lerp(0.104f, 0.100f, (t - 0.50f) / 0.06f);
        if (t <= 0.65f) return lerp(0.100f, 0.088f, (t - 0.56f) / 0.09f);
        if (t <= 0.72f) return lerp(0.088f, 0.098f, (t - 0.65f) / 0.07f);
        if (t <= 0.82f) return lerp(0.098f, 0.125f, (t - 0.72f) / 0.10f);
        if (t <= 0.86f) return lerp(0.125f, 0.090f, (t - 0.82f) / 0.04f);
        return lerp(0.090f, 0.040f, clamp01((t - 0.86f) / 0.04f));
    }

    /** 超过这个横向距离的就是手臂（留一点余量） */
    static float isArmLimit(float t, float h) {
        return torsoHalf(t) * 1.18f * h;
    }

    /** 某个高度上一个 2D 点是不是落在手臂 / 手上 */
    static boolean isArm(float t, float ax, float h) {
        return t > 0.36f && t < 0.90f && ax > isArmLimit(t, h);
    }

    /** 肩宽：只看躯干（排除手臂），取肩线附近的最大横向跨度 */
    static float shoulderWidth(float[] v, float h) {
        float lo = 0.76f * h;
        float hi = 0.87f * h;
        float max = 0f;
        for (int i = 0; i < v.length; i += 3) {
            float y = v[i + 1];
            if (y < lo || y > hi) continue;
            float ax = Math.abs(v[i]);
            if (ax <= max) continue;
            if (isArm(y / h, ax, h)) continue;      // A 字 pose 下手臂就在肩线两侧
            max = ax;
        }
        return 2f * max;
    }

    /**
     * 臂展 = 肩峰间距 + 2 × 臂长。Anny 的静息姿态手臂斜向下张开（A 字 pose），所以：
     *   - 臂长：沿手臂外缘从肩线逐行走到指尖累加（{@link #armLength}），比直线距离准，
     *     因为手臂略微前倾、带一点弯曲；
     *   - 肩峰间距：{@link #shoulderWidth} 量到的是三角肌外缘，比骨性肩峰宽一圈，
     *     这里按成年人常见的比例收一点。
     */
    static float armSpan(float[] v, float h) {
        float shoulder = shoulderWidth(v, h);
        float len = armLength(v, h);
        if (len <= 0f) return shoulder;
        return shoulder * 0.88f + 2f * len;
    }

    /**
     * 臂长（米）：肩点（肩线一带最偏外的顶点，约当肩峰）到指尖的距离，量不出来返回 0。
     *
     * 只找右半身的这两个点，避免左右两臂混着取点导致里程虚增。
     */
    static float armLength(float[] v, float h) {
        float tipX = 0f;                        // 指尖：A 字 pose 下体侧最远的点
        float tipY = 0f;
        for (int i = 0; i < v.length; i += 3) {
            float x = v[i];
            float y = v[i + 1];
            float t = y / h;
            if (t > 0.25f && t < 0.95f && x > tipX) {
                tipX = x;
                tipY = y;
            }
        }
        if (tipX <= 0f) return 0f;
        // 肩点：自上往下找到第一处横向明显张开的地方（头部很窄，肩一下就宽出来了）
        float jointX = 0f;
        float jointY = 0f;
        float wide = 0.09f * h;
        float band = h / 200f;
        for (float t = 0.92f; t > 0.70f && jointX <= 0f; t -= 0.005f) {
            float lo = (t - 0.005f) * h;
            float hi = (t + 0.005f) * h;
            float mx = 0f;
            float my = 0f;
            for (int i = 0; i < v.length; i += 3) {
                float y = v[i + 1];
                if (y < lo || y > hi) continue;
                if (v[i] > mx) {
                    mx = v[i];
                    my = y;
                }
            }
            if (mx >= wide) {
                jointX = mx;
                jointY = my;
            }
        }
        if (jointX <= 0f || tipY >= jointY) return 0f;
        return (float) Math.hypot(tipX - jointX, tipY - jointY);
    }

    /** 脚长：脚踝以下那批顶点在 z 方向的跨度 */
    static float footLength(float[] v, float h) {
        float[] b = bounds(v);
        float top = b[1] + 0.035f * h;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < v.length; i += 3) {
            if (v[i + 1] > top) continue;
            min = Math.min(min, v[i + 2]);
            max = Math.max(max, v[i + 2]);
        }
        return (min == Float.MAX_VALUE) ? 0f : max - min;
    }

    /**
     * 水平切片：把三角面与平面相交得到的线段串成闭环。
     * 躯干、两臂、两腿在切片上各自成环，因此可以按环心挑出要量的那一圈。
     */
    static ArrayList<Loop> loopsAt(float[] v, short[] idx, float y) {
        ArrayList<float[]> segs = new ArrayList<>();
        for (int t = 0; t < idx.length; t += 3) {
            int[] tri = {idx[t] & 0xFFFF, idx[t + 1] & 0xFFFF, idx[t + 2] & 0xFFFF};
            float px = 0f, pz = 0f, qx = 0f, qz = 0f;
            int n = 0;
            for (int e = 0; e < 3; e++) {
                int i0 = tri[e];
                int i1 = tri[(e + 1) % 3];
                float y0 = v[i0 * 3 + 1];
                float y1 = v[i1 * 3 + 1];
                if ((y0 <= y && y1 > y) || (y1 <= y && y0 > y)) {
                    float s = (y - y0) / (y1 - y0);
                    float x = v[i0 * 3] + (v[i1 * 3] - v[i0 * 3]) * s;
                    float z = v[i0 * 3 + 2] + (v[i1 * 3 + 2] - v[i0 * 3 + 2]) * s;
                    if (n == 0) {
                        px = x;
                        pz = z;
                    } else if (n == 1) {
                        qx = x;
                        qz = z;
                    }
                    n++;
                }
            }
            if (n == 2) segs.add(new float[]{px, pz, qx, qz});
        }
        return chain(segs);
    }

    private static float d2(float ax, float az, float bx, float bz) {
        float dx = ax - bx;
        float dz = az - bz;
        return dx * dx + dz * dz;
    }

    private static ArrayList<Loop> chain(ArrayList<float[]> segs) {
        ArrayList<Loop> out = new ArrayList<>();
        int count = segs.size();
        if (count < 3) return out;
        float eps = 0f;
        for (float[] s : segs) {
            eps = Math.max(eps, Math.abs(s[0] - s[2]) + Math.abs(s[1] - s[3]));
        }
        eps = Math.max(eps / count, 1e-6f) * 2f + 1e-6f;   // 容差取平均边长量级
        boolean[] used = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (used[i]) continue;
            used[i] = true;
            float[] s = segs.get(i);
            float startX = s[0];
            float startZ = s[1];
            float curX = s[2];
            float curZ = s[3];
            ArrayList<Float> xs = new ArrayList<>();
            ArrayList<Float> zs = new ArrayList<>();
            xs.add(startX);
            zs.add(startZ);
            xs.add(curX);
            zs.add(curZ);
            while (true) {
                // 接下一段要挑「最近的」而不是「先碰上的」：轮廓在腋下 / 乳下这类地方会
                // 自己靠得很近，按先来后到接就会拐到另一条支路上去，闭出一个小圈 ——
                // 实测丰满度 0.70 那一档胸围比 0.65 反而小 2.5cm，就是这么丢的。
                // 取最近的一段，走的就是原来那条轮廓
                int next = -1;
                int end = -1;
                float bestD = Float.MAX_VALUE;
                for (int j = 0; j < count; j++) {
                    if (used[j]) continue;
                    float[] t = segs.get(j);
                    float d0 = d2(t[0], t[1], curX, curZ);
                    if (d0 < bestD && d0 <= eps * eps) {
                        bestD = d0;
                        next = j;
                        end = 2;
                    }
                    float d1 = d2(t[2], t[3], curX, curZ);
                    if (d1 < bestD && d1 <= eps * eps) {
                        bestD = d1;
                        next = j;
                        end = 0;
                    }
                }
                if (next < 0) break;
                used[next] = true;
                float[] t = segs.get(next);
                curX = t[end];
                curZ = t[end + 1];
                if (near(curX, curZ, startX, startZ, eps)) break;   // 回到起点，闭环
                xs.add(curX);
                zs.add(curZ);
            }
            if (xs.size() < 3) continue;
            Loop l = new Loop();
            double cx = 0, cz = 0;
            float per = 0f;
            float xLo = Float.MAX_VALUE;
            float xHi = -Float.MAX_VALUE;
            int n = xs.size();
            for (int k = 0; k < n; k++) {
                cx += xs.get(k);
                cz += zs.get(k);
                float xv = xs.get(k);
                if (xv < xLo) xLo = xv;
                if (xv > xHi) xHi = xv;
                int k2 = (k + 1) % n;
                float dx = xs.get(k2) - xs.get(k);
                float dz = zs.get(k2) - zs.get(k);
                per += (float) Math.sqrt(dx * dx + dz * dz);
            }
            l.cx = (float) (cx / n);
            l.cz = (float) (cz / n);
            l.perimeter = per;
            l.xMin = xLo;
            l.xMax = xHi;
            out.add(l);
        }
        return out;
    }

    private static boolean near(float ax, float az, float bx, float bz, float eps) {
        return Math.abs(ax - bx) <= eps && Math.abs(az - bz) <= eps;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }
}
