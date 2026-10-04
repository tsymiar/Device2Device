package com.tsymiar.device2device.avatar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Anny 的形变基（MakeHuman target）集合，以及它们的求解方式。
 *
 * Anny 的形状求解（见官方 anny/models/phenotype.py）只有两步：
 *   1) 每个表型维度在 [0,1] 上做分段线性插值，得到该维度各个「原型」的系数 c
 *      （同一维度的系数和为 1；age 的节点是 -1/3 → 1，其余是 0 → 1）；
 *   2) 一个形变基的权重 w 是它依赖的那几个原型系数的**乘积**：w = Π c。
 * 于是静息网格 = 模板 + Σ w·B。
 *
 * 也就是说 Anny 对表型是「多线性」的，而不是「每个表型各叠一个形变」：肌肉量在
 * 男性身上和在女性身上的效果不是同一个位移，体重的变化幅度也会被性别和年龄调制，
 * 这些交叉项全都藏在「乘积」里。tools/export_anny_targets.py 把形变基本身和它的
 * 依赖一起导出，这里按同一条公式相乘累加，结果与官方实现逐顶点一致。
 *
 * 导出时可以塌缩掉用不到的维度（比如只做成年人就把 age 固定），固定维度的系数
 * 会被预先乘进形变基，因此这里的维度列表就是端上还能调的那些。
 */
final class AnnyTargets {

    final int vertexCount;
    /** 端上还能调的维度名，顺序与求解时传入的取值一致 */
    final String[] dims;
    /** 每个维度的分段线性插值节点（升序） */
    final float[][] anchors;
    /** 每个形变基的依赖，编码为 (dimIndex << 16) | variationIndex */
    final int[][] deps;
    /** 量化尺度：位移 = i16 / 32767 * scale */
    final float[] scales;
    /** 量化后的顶点位移（默认） */
    final short[][] qDeltas;
    /** 未量化时的顶点位移（qDeltas 为 null 时用这个） */
    final float[][] fDeltas;

    private AnnyTargets(int vertexCount, String[] dims, float[][] anchors, int[][] deps,
                        float[] scales, short[][] qDeltas, float[][] fDeltas) {
        this.vertexCount = vertexCount;
        this.dims = dims;
        this.anchors = anchors;
        this.deps = deps;
        this.scales = scales;
        this.qDeltas = qDeltas;
        this.fDeltas = fDeltas;
    }

    static AnnyTargets read(ByteBuffer b, int vc, int dimCount, int shapeCount, int flags)
            throws IOException {
        boolean quant = (flags & 1) != 0;
        String[] dims = new String[dimCount];
        float[][] anchors = new float[dimCount][];
        for (int d = 0; d < dimCount; d++) {
            int len = b.getShort() & 0xFFFF;
            byte[] raw = new byte[len];
            b.get(raw);
            dims[d] = new String(raw, StandardCharsets.UTF_8);
            int n = b.getInt();
            if (n < 1) throw new IOException("维度没有插值节点：" + dims[d]);
            float[] a = new float[n];
            for (int i = 0; i < n; i++) a[i] = b.getFloat();
            anchors[d] = a;
        }

        int[][] deps = new int[shapeCount][];
        float[] scales = new float[shapeCount];
        short[][] qDeltas = quant ? new short[shapeCount][] : null;
        float[][] fDeltas = quant ? null : new float[shapeCount][];
        int n3 = vc * 3;
        for (int j = 0; j < shapeCount; j++) {
            int dc = b.getInt();
            if (dc < 0 || dc > dimCount) throw new IOException("形变基依赖数非法：" + dc);
            int[] dep = new int[dc];
            for (int i = 0; i < dc; i++) {
                int code = b.getInt();
                int d = code >>> 16;
                int k = code & 0xFFFF;
                if (d >= dimCount || k >= anchors[d].length) {
                    throw new IOException("形变基依赖越界：" + code);
                }
                dep[i] = code;
            }
            deps[j] = dep;
            scales[j] = b.getFloat();
            if (quant) {
                short[] arr = new short[n3];
                ShortBuffer sb = b.asShortBuffer();
                sb.get(arr);
                b.position(b.position() + n3 * 2);
                qDeltas[j] = arr;
            } else {
                float[] arr = new float[n3];
                FloatBuffer fb = b.asFloatBuffer();
                fb.get(arr);
                b.position(b.position() + n3 * 4);
                fDeltas[j] = arr;
            }
        }
        return new AnnyTargets(vc, dims, anchors, deps, scales, qDeltas, fDeltas);
    }

    /** 把 Σ w·B 累加到 v 上（v 进来时应该是模板顶点） */
    void addTo(float[] v, float[] values) {
        int nd = dims.length;
        float[][] coeff = new float[nd][];
        for (int d = 0; d < nd; d++) {
            coeff[d] = interp(anchors[d], d < values.length ? values[d] : 0.5f);
        }
        for (int j = 0; j < deps.length; j++) {
            int[] dep = deps[j];
            float w = 1f;
            for (int i = 0; i < dep.length; i++) {
                int code = dep[i];
                float c = coeff[code >>> 16][code & 0xFFFF];
                if (c <= 0f) {                 // 依赖的原型没被选中：整条形变基不参与
                    w = 0f;
                    break;
                }
                w *= c;
            }
            if (w == 0f) continue;
            if (fDeltas != null) {
                float[] dj = fDeltas[j];
                for (int i = 0; i < v.length; i++) v[i] += w * dj[i];
            } else {
                short[] dj = qDeltas[j];
                float s = w * scales[j] / 32767f;
                for (int i = 0; i < v.length; i++) v[i] += s * dj[i];
            }
        }
    }

    /** 分段线性插值系数（与 Anny 的 linear_interpolation_coefficients 一致，不外插） */
    static float[] interp(float[] anchors, float value) {
        int n = anchors.length;
        float[] w = new float[n];
        int idx = n;
        for (int i = 0; i < n; i++) {
            if (anchors[i] >= value) {
                idx = i;
                break;
            }
        }
        idx = Math.min(Math.max(idx, 1), n - 1);
        float lo = anchors[idx - 1];
        float hi = anchors[idx];
        float alpha = hi > lo ? (value - lo) / (hi - lo) : 0f;
        alpha = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        w[idx - 1] = 1f - alpha;
        w[idx] = alpha;
        return w;
    }
}
