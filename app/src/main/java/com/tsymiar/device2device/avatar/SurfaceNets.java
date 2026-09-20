package com.tsymiar.device2device.avatar;

import java.util.Arrays;

/**
 * Surface Nets（对偶等值面提取）：把 {@link SdfModel} 的体素场转成平滑网格。
 *
 * 与 Marching Cubes 相比不需要 256 项的查找表，而且在面心放顶点、按「符号变化的网格边」
 * 连四边形，天然得到较规整的四边形网格；顶点再用牛顿迭代投影到真实等值面上，
 * 法线直接取场的梯度，因此即使体素不算很密，轮廓也是光滑的。
 *
 * 性能：先用 1/4 分辨率的粗场剪掉远离表面的块（剪枝是精确的——被跳过的块内场值一定为正），
 * 只保留表面附近的体素做完整求值。
 */
public final class SurfaceNets {

    public static final class Mesh {
        public float[] positions;   // 每顶点 3 个分量
        public float[] normals;
        public float[] ao;          // 环境光遮蔽
        public int[] materials;     // 每顶点所属材质
        public int[] indices;       // 三角形索引
        public int vertexCount;
        public int triangles;
    }

    /** 立方体 8 个角点（局部坐标 0/1） */
    private static final int[][] CORNERS = {
            {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0},
            {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1},
    };

    /** 立方体 12 条边（角点下标对） */
    private static final int[] EDGES = {
            0, 1, 1, 2, 2, 3, 3, 0,
            4, 5, 5, 6, 6, 7, 7, 4,
            0, 4, 1, 5, 2, 6, 3, 7,
    };

    private static final int BLOCK = 4;

    private SurfaceNets() {
    }

    /**
     * @param ox/oy/oz 网格原点（体素 (0,0,0) 的角点坐标）
     * @param h        体素边长（米）
     * @param nx/ny/nz 体素数量（角点数为 nx+1 等）
     */
    public static Mesh build(SdfModel sdf, float ox, float oy, float oz, float h,
                             int nx, int ny, int nz) {
        Mesh out = new Mesh();
        int sx = nx + 1;
        int sy = ny + 1;
        float[] f = new float[sx * sy * (nz + 1)];

        pruneFill(sdf, f, ox, oy, oz, h, nx, ny, nz, sx, sy);

        int[] cellVert = new int[nx * ny * nz];
        Arrays.fill(cellVert, -1);

        FBuf pos = new FBuf();
        FBuf nrm = new FBuf();
        FBuf ao = new FBuf();
        IBuf mat = new IBuf();
        IBuf idx = new IBuf();

        float[] v = new float[8];
        float[] g = new float[3];

        // ---- 第一遍：每个「符号混合」的体素生成一个顶点 ----
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    int mask = 0;
                    for (int c = 0; c < 8; c++) {
                        int[] o = CORNERS[c];
                        v[c] = f[((k + o[2]) * sy + (j + o[1])) * sx + (i + o[0])];
                        if (v[c] < 0f) mask |= (1 << c);
                    }
                    if (mask == 0 || mask == 255) continue;

                    float ax = 0f, ay = 0f, az = 0f;
                    int n = 0;
                    for (int e = 0; e < 12; e++) {
                        int c0 = EDGES[e * 2];
                        int c1 = EDGES[e * 2 + 1];
                        if (((mask >> c0) & 1) == ((mask >> c1) & 1)) continue;
                        float f0 = v[c0];
                        float t = f0 / (f0 - v[c1]);          // 端点异号 → t ∈ [0,1]
                        int[] o0 = CORNERS[c0];
                        int[] o1 = CORNERS[c1];
                        ax += o0[0] + (o1[0] - o0[0]) * t;
                        ay += o0[1] + (o1[1] - o0[1]) * t;
                        az += o0[2] + (o1[2] - o0[2]) * t;
                        n++;
                    }
                    if (n == 0) continue;

                    float px = ox + (i + ax / n) * h;
                    float py = oy + (j + ay / n) * h;
                    float pz = oz + (k + az / n) * h;

                    // 牛顿迭代：把顶点拉到真实等值面上，消除体素台阶
                    for (int it = 0; it < 2; it++) {
                        float d = sdf.field(px, py, pz);
                        if (Math.abs(d) < 1e-4f) break;
                        sdf.gradient(px, py, pz, g);
                        px -= g[0] * d;
                        py -= g[1] * d;
                        pz -= g[2] * d;
                    }

                    sdf.gradient(px, py, pz, g);
                    pos.add(px); pos.add(py); pos.add(pz);
                    nrm.add(g[0]); nrm.add(g[1]); nrm.add(g[2]);
                    ao.add(occlusion(sdf, px, py, pz, g));
                    mat.add(sdf.material(px, py, pz));
                    cellVert[(k * ny + j) * nx + i] = pos.n / 3 - 1;
                }
            }
        }

        // ---- 第二遍：穿过等值面的每条网格边，连接相邻 4 个体素的面心成四边形 ----
        int[] qa = new int[3];
        int[] qb = new int[3];
        int[] qc = new int[3];
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    int vD = cellVert[(k * ny + j) * nx + i];
                    if (vD < 0) continue;
                    for (int a = 0; a < 3; a++) {
                        int b = (a + 1) % 3;
                        int c = (a + 2) % 3;
                        qa[0] = i; qa[1] = j; qa[2] = k;
                        qb[0] = i; qb[1] = j; qb[2] = k;
                        qc[0] = i; qc[1] = j; qc[2] = k;
                        if (qa[b] < 1 || qa[c] < 1) continue;
                        int c0 = cornerIndex(i, j, k, sx, sy);
                        int[] e = UNIT[a];
                        int c1 = cornerIndex(i + e[0], j + e[1], k + e[2], sx, sy);
                        if ((f[c0] < 0f) == (f[c1] < 0f)) continue;

                        qa[b] -= 1;
                        qb[b] -= 1;
                        qb[c] -= 1;
                        qc[c] -= 1;
                        int vA = cellVert[(qa[2] * ny + qa[1]) * nx + qa[0]];
                        int vB = cellVert[(qb[2] * ny + qb[1]) * nx + qb[0]];
                        int vC = cellVert[(qc[2] * ny + qc[1]) * nx + qc[0]];
                        if (vA < 0 || vB < 0 || vC < 0) continue;
                        idx.add(vD); idx.add(vA); idx.add(vB);
                        idx.add(vD); idx.add(vB); idx.add(vC);
                    }
                }
            }
        }

        out.vertexCount = pos.n / 3;
        out.triangles = idx.n / 3;
        out.positions = Arrays.copyOf(pos.a, pos.n);
        out.normals = Arrays.copyOf(nrm.a, nrm.n);
        out.ao = Arrays.copyOf(ao.a, ao.n);
        out.materials = Arrays.copyOf(mat.a, mat.n);
        out.indices = Arrays.copyOf(idx.a, idx.n);
        return out;
    }

    private static final int[][] UNIT = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

    private static int cornerIndex(int i, int j, int k, int sx, int sy) {
        return (k * sy + j) * sx + i;
    }

    /**
     * 先用 1/BLOCK 分辨率的粗场剪枝：粗块的 8 个角点场值都大于阈值时，
     * 由 Lipschitz 连续性可知块内任意点场值仍为正，可以整块跳过（不影响等值面）。
     */
    private static void pruneFill(SdfModel sdf, float[] f, float ox, float oy, float oz, float h,
                                  int nx, int ny, int nz, int sx, int sy) {
        int bx = (nx + BLOCK - 1) / BLOCK;
        int by = (ny + BLOCK - 1) / BLOCK;
        int bz = (nz + BLOCK - 1) / BLOCK;
        int gx = bx + 1;
        int gy = by + 1;
        float[] g = new float[gx * gy * (bz + 1)];
        for (int k = 0; k <= bz; k++) {
            for (int j = 0; j <= by; j++) {
                for (int i = 0; i <= bx; i++) {
                    g[(k * gy + j) * gx + i] = sdf.field(
                            ox + Math.min(i * BLOCK, nx) * h,
                            oy + Math.min(j * BLOCK, ny) * h,
                            oz + Math.min(k * BLOCK, nz) * h);
                }
            }
        }
        // 阈值 ≥ 块对角线 + 一个体素：保证被跳过的角点不可能参与等值面插值
        float thresh = (float) (Math.sqrt(3.0) * BLOCK + 1.5) * h;

        boolean[] need = new boolean[sx * sy * (nz + 1)];
        for (int k = 0; k < bz; k++) {
            for (int j = 0; j < by; j++) {
                for (int i = 0; i < bx; i++) {
                    boolean near = false;
                    for (int dk = 0; dk <= 1 && !near; dk++) {
                        for (int dj = 0; dj <= 1 && !near; dj++) {
                            for (int di = 0; di <= 1 && !near; di++) {
                                if (g[((k + dk) * gy + (j + dj)) * gx + (i + di)] < thresh) near = true;
                            }
                        }
                    }
                    if (!near) continue;
                    int i0 = i * BLOCK, i1 = Math.min(nx, (i + 1) * BLOCK);
                    int j0 = j * BLOCK, j1 = Math.min(ny, (j + 1) * BLOCK);
                    int k0 = k * BLOCK, k1 = Math.min(nz, (k + 1) * BLOCK);
                    for (int kk = k0; kk <= k1; kk++) {
                        for (int jj = j0; jj <= j1; jj++) {
                            int base = (kk * sy + jj) * sx;
                            for (int ii = i0; ii <= i1; ii++) need[base + ii] = true;
                        }
                    }
                }
            }
        }
        for (int k = 0; k <= nz; k++) {
            float pz = oz + k * h;
            for (int j = 0; j <= ny; j++) {
                float py = oy + j * h;
                int base = (k * sy + j) * sx;
                for (int i = 0; i <= nx; i++) {
                    f[base + i] = need[base + i] ? sdf.field(ox + i * h, py, pz) : 1f;
                }
            }
        }
    }

    /** 沿法线方向多点采样距离场求环境光遮蔽（iq 的经典近似） */
    private static float occlusion(SdfModel sdf, float x, float y, float z, float[] n) {
        float occ = 0f;
        float sca = 1f;
        for (int i = 0; i < 5; i++) {
            float hr = 0.012f + 0.15f * i / 4f;
            float d = sdf.field(x + n[0] * hr, y + n[1] * hr, z + n[2] * hr);
            occ += (hr - d) * sca;
            sca *= 0.72f;
        }
        float ao = 1f - 1.5f * occ;
        return ao < 0.18f ? 0.18f : (ao > 1f ? 1f : ao);
    }

    private static final class FBuf {
        float[] a = new float[1 << 14];
        int n;

        void add(float v) {
            if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }
    }

    private static final class IBuf {
        int[] a = new int[1 << 12];
        int n;

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }
    }
}
