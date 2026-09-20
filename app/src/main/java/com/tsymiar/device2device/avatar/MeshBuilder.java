package com.tsymiar.device2device.avatar;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简三角网格构建器：支持「放样管体」与（局部）椭球两种图元。
 *
 * 每个顶点自带法线与颜色，顶点数控制在 65536 以内以便用 short 索引；
 * 构建时按部件（头 / 发 / 躯干 / 四肢…）分段记录，导出 OBJ 时按部件分组并生成 mtl 颜色。
 */
public final class MeshBuilder {

    public static final class Part {
        /** 部件名（OBJ 里的 g / usemtl 名，使用 ASCII） */
        public final String name;
        /** 在索引数组中的起始下标 */
        public final int start;
        /** 索引数量（3 的倍数） */
        public final int count;
        /** 该部件颜色 rgb，取值 0~1 */
        public final float[] color;

        Part(String name, int start, int count, float[] color) {
            this.name = name;
            this.start = start;
            this.count = count;
            this.color = color;
        }
    }

    private final ArrayList<Float> positions = new ArrayList<>();
    private final ArrayList<Float> normals = new ArrayList<>();
    private final ArrayList<Float> colors = new ArrayList<>();
    /** 顶点环境光遮蔽系数（1=完全敞开，越小越暗），用于褶皱/腋下/裆部等凹陷处 */
    private final ArrayList<Float> aos = new ArrayList<>();
    private final ArrayList<Short> indices = new ArrayList<>();
    private final ArrayList<Part> parts = new ArrayList<>();

    /** short 索引的上限：再多就要么溢出回绕、要么得换 int 索引（GLES2 不保证支持） */
    public static final int MAX_VERTICES = 65535;

    private float cr = 1f, cg = 1f, cb = 1f;
    private int partStart = -1;
    private String partName = "part";
    private float[] partColor = new float[]{1f, 1f, 1f};

    // ------------------------------------------------------------------
    // 部件
    // ------------------------------------------------------------------

    public void beginPart(String name, int argb) {
        endPart();
        partName = name;
        partStart = indices.size();
        cr = ((argb >> 16) & 0xFF) / 255f;
        cg = ((argb >> 8) & 0xFF) / 255f;
        cb = (argb & 0xFF) / 255f;
        partColor = new float[]{cr, cg, cb};
    }

    public void endPart() {
        if (partStart >= 0 && indices.size() > partStart) {
            parts.add(new Part(partName, partStart, indices.size() - partStart, partColor));
        }
        partStart = -1;
    }

    public int vertexCount() {
        return positions.size() / 3;
    }

    public int indexCount() {
        return indices.size();
    }

    // ------------------------------------------------------------------
    // 图元
    // ------------------------------------------------------------------

    /**
     * 沿 p0→p1 放样一条管体，横截面为椭圆。
     *
     * @param rx 沿轴向各采样点的 x 半轴（长度 = 采样点数）
     * @param rz 沿轴向各采样点的 z 半轴
     */
    public void addTube(float[] p0, float[] p1, float[] rx, float[] rz,
                        int slices, boolean capStart, boolean capEnd) {
        int n = rx.length;
        if (n < 2 || rz.length != n || slices < 3) return;

        float[] d = {p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]};
        normalize(d);
        float[] u = perpendicular(d);
        float[] w = cross(d, u);          // (u, w, d) 右手系：环向角度递增为绕 +d 逆时针

        int first = vertexCount();
        for (int i = 0; i < n; i++) {
            float t = (float) i / (n - 1);
            float cx = p0[0] + (p1[0] - p0[0]) * t;
            float cy = p0[1] + (p1[1] - p0[1]) * t;
            float cz = p0[2] + (p1[2] - p0[2]) * t;
            float a = Math.max(rx[i], 1e-4f);
            float b = Math.max(rz[i], 1e-4f);
            for (int j = 0; j < slices; j++) {
                double ang = 2.0 * Math.PI * j / slices;
                float ca = (float) Math.cos(ang);
                float sa = (float) Math.sin(ang);
                // 椭圆隐式方程梯度即法线方向
                float nu = ca / a;
                float nv = sa / b;
                float nx = nu * u[0] + nv * w[0];
                float ny = nu * u[1] + nv * w[1];
                float nz = nu * u[2] + nv * w[2];
                float[] nn = {nx, ny, nz};
                normalize(nn);
                addVertex(cx + a * ca * u[0] + b * sa * w[0],
                        cy + a * ca * u[1] + b * sa * w[1],
                        cz + a * ca * u[2] + b * sa * w[2],
                        nn[0], nn[1], nn[2]);
            }
        }
        for (int i = 0; i < n - 1; i++) {
            for (int j = 0; j < slices; j++) {
                int a0 = first + i * slices + j;
                int b0 = first + i * slices + (j + 1) % slices;
                int c0 = first + (i + 1) * slices + (j + 1) % slices;
                int d0 = first + (i + 1) * slices + j;
                tri(a0, b0, c0);
                tri(a0, c0, d0);
            }
        }
        if (capStart) fan(first, slices, p0, -d[0], -d[1], -d[2], true);
        if (capEnd) fan(first + (n - 1) * slices, slices, p1, d[0], d[1], d[2], false);
    }

    /** 完整椭球 */
    public void addEllipsoid(float cx, float cy, float cz, float rx, float ry, float rz,
                             int stacks, int slices) {
        addPartialEllipsoid(cx, cy, cz, rx, ry, rz, stacks, slices, 0f, 1f, 0f, false, false);
    }

    /**
     * 局部椭球：v0/v1 为纵向参数（0=顶 1=底），noise 用于卷发之类的半径扰动。
     */
    public void addPartialEllipsoid(float cx, float cy, float cz, float rx, float ry, float rz,
                                    int stacks, int slices, float v0, float v1,
                                    float noise, boolean capTop, boolean capBottom) {
        if (stacks < 2 || slices < 3) return;
        boolean topPole = v0 <= 0.001f;
        boolean bottomPole = v1 >= 0.999f;
        int[] ringStart = new int[stacks + 1];
        for (int i = 0; i <= stacks; i++) {
            float v = v0 + (v1 - v0) * i / (float) stacks;
            double th = Math.PI * v;
            float st = (float) Math.sin(th);
            float ct = (float) Math.cos(th);
            ringStart[i] = vertexCount();
            if ((i == 0 && topPole) || (i == stacks && bottomPole)) {
                // 极点只放一个顶点：一圈重复顶点会让网格在极点处拓扑断开
                addVertex(cx, cy + ry * ct, cz, 0f, ct >= 0f ? 1f : -1f, 0f);
                continue;
            }
            for (int j = 0; j < slices; j++) {
                double ph = 2.0 * Math.PI * j / slices;
                float cp = (float) Math.cos(ph);
                float sp = (float) Math.sin(ph);
                float k = 1f + noise * (float) (Math.sin(3.0 * ph) * Math.sin(4.0 * Math.PI * v));
                float x = cx + rx * st * cp * k;
                float y = cy + ry * ct;
                float z = cz + rz * st * sp * k;
                float[] nn = {(x - cx) / (rx * rx), (y - cy) / (ry * ry), (z - cz) / (rz * rz)};
                normalize(nn);
                addVertex(x, y, z, nn[0], nn[1], nn[2]);
            }
        }
        for (int i = 0; i < stacks; i++) {
            int ra = ringStart[i];
            int rb = ringStart[i + 1];
            if (i == 0 && topPole) {
                for (int j = 0; j < slices; j++) tri(ra, rb + (j + 1) % slices, rb + j);
            } else if (i == stacks - 1 && bottomPole) {
                for (int j = 0; j < slices; j++) tri(ra + j, ra + (j + 1) % slices, rb);
            } else {
                for (int j = 0; j < slices; j++) {
                    int a0 = ra + j;
                    int b0 = ra + (j + 1) % slices;
                    int c0 = rb + (j + 1) % slices;
                    int d0 = rb + j;
                    tri(a0, b0, c0);
                    tri(a0, c0, d0);
                }
            }
        }
        if (capTop && v0 > 0.001f) {
            float y = cy + ry * (float) Math.cos(Math.PI * v0);
            fan(ringStart[0], slices, new float[]{cx, y, cz}, 0f, 1f, 0f, true);
        }
        if (capBottom && v1 < 0.999f) {
            float y = cy + ry * (float) Math.cos(Math.PI * v1);
            fan(ringStart[stacks], slices, new float[]{cx, y, cz}, 0f, -1f, 0f, false);
        }
    }

    /**
     * 直接写入一个顶点（供隐式曲面等外部网格接入）。
     *
     * @return 顶点下标
     */
    public int addVertex(float x, float y, float z, float nx, float ny, float nz,
                         float r, float g, float b, float ao) {
        if (vertexCount() >= MAX_VERTICES) return MAX_VERTICES - 1;   // 退化索引，相关三角形会被丢掉
        positions.add(x);
        positions.add(y);
        positions.add(z);
        normals.add(nx);
        normals.add(ny);
        normals.add(nz);
        colors.add(r);
        colors.add(g);
        colors.add(b);
        aos.add(ao);
        return vertexCount() - 1;
    }

    /** 直接写入一个三角形（顶点下标来自 {@link #addVertex}） */
    public void addTriangle(int a, int b, int c) {
        tri(a, b, c);
    }

    /** 三角形扇：给管体/椭球的开口补一个平面盖 */
    private void fan(int ringFirst, int slices, float[] c, float nx, float ny, float nz, boolean reverse) {
        int center = addVertex(c[0], c[1], c[2], nx, ny, nz);
        for (int j = 0; j < slices; j++) {
            int a = ringFirst + j;
            int b = ringFirst + (j + 1) % slices;
            if (reverse) {
                tri(center, b, a);
            } else {
                tri(center, a, b);
            }
        }
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    public float[] positions() {
        float[] out = new float[positions.size()];
        for (int i = 0; i < out.length; i++) out[i] = positions.get(i);
        return out;
    }

    public float[] normals() {
        float[] out = new float[normals.size()];
        for (int i = 0; i < out.length; i++) out[i] = normals.get(i);
        return out;
    }

    public float[] colors() {
        float[] out = new float[colors.size()];
        for (int i = 0; i < out.length; i++) out[i] = colors.get(i);
        return out;
    }

    public float[] ao() {
        float[] out = new float[aos.size()];
        for (int i = 0; i < out.length; i++) out[i] = aos.get(i);
        return out;
    }

    public short[] indices() {
        short[] out = new short[indices.size()];
        for (int i = 0; i < out.length; i++) out[i] = indices.get(i);
        return out;
    }

    public List<Part> parts() {
        endPart();
        return new ArrayList<>(parts);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private int addVertex(float x, float y, float z, float nx, float ny, float nz) {
        if (vertexCount() >= MAX_VERTICES) return MAX_VERTICES - 1;
        positions.add(x);
        positions.add(y);
        positions.add(z);
        normals.add(nx);
        normals.add(ny);
        normals.add(nz);
        colors.add(cr);
        colors.add(cg);
        colors.add(cb);
        aos.add(1f);
        return vertexCount() - 1;
    }

    private void tri(int a, int b, int c) {
        if (a < 0 || b < 0 || c < 0) return;
        if (a == b || b == c || a == c) return;
        indices.add((short) a);
        indices.add((short) b);
        indices.add((short) c);
    }

    static void normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len > 1e-8f) {
            v[0] /= len;
            v[1] /= len;
            v[2] /= len;
        }
    }

    private static float[] perpendicular(float[] d) {
        float[] base = Math.abs(d[0]) < 0.9f ? new float[]{1f, 0f, 0f} : new float[]{0f, 1f, 0f};
        float dot = base[0] * d[0] + base[1] * d[1] + base[2] * d[2];
        float[] u = {base[0] - d[0] * dot, base[1] - d[1] * dot, base[2] - d[2] * dot};
        normalize(u);
        return u;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }
}
