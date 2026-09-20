package com.tsymiar.device2device.avatar;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;
import java.util.StringTokenizer;

/**
 * 极简 OBJ 解析器：让页面可以直接载入开源/第三方的人体模型资产。
 *
 * 支持的来源：MakeHuman（开源 CC0 人体生成器）导出的 OBJ、Blender 导出的 OBJ、
 * Ready Player Me / Mixamo / Sketchfab 下载后转成的 OBJ 等。
 *
 * 载入后按包围盒自动归一化：脚底落到 y=0、身高对齐到当前设定的身高、水平居中，
 * 于是导入的模型与参数化生成的一样是「等身比例」，可以直接和地面网格/标尺比对。
 *
 * 只取几何（v / vn / f），不解析 MTL；顶点数超过 65000 时放弃（索引用 short）。
 */
public final class ObjLoader {

    private static final String TAG = "ObjLoader";
    private static final int MAX_VERTICES = 65000;

    public static final class LoadResult {
        public HumanMesh.Result mesh;
        public String error;
    }

    private ObjLoader() {
    }

    public static LoadResult load(InputStream in, float targetHeightM, int color) {
        LoadResult out = new LoadResult();
        try {
            ArrayList<Float> pos = new ArrayList<>(30000);
            ArrayList<Float> nrm = new ArrayList<>(30000);
            ArrayList<Float> idx = new ArrayList<>(30000);      // 三角形：v 下标
            ArrayList<Integer> idxN = new ArrayList<>(30000);   // 对应的 vn 下标（-1 表示无）
            ArrayList<Integer> groupStart = new ArrayList<>();
            ArrayList<String> groupName = new ArrayList<>();
            boolean hasVn = false;

            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            String line;
            String currentGroup = "model";
            int faceCount = 0;
            if (groupStart.isEmpty()) {
                groupStart.add(0);
                groupName.add(currentGroup);
            }
            while ((line = r.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                StringTokenizer st = new StringTokenizer(line);
                if (!st.hasMoreTokens()) continue;
                String tag = st.nextToken();
                if ("v".equals(tag)) {
                    pos.add(Float.parseFloat(st.nextToken()));
                    pos.add(Float.parseFloat(st.nextToken()));
                    pos.add(Float.parseFloat(st.nextToken()));
                } else if ("vn".equals(tag)) {
                    nrm.add(Float.parseFloat(st.nextToken()));
                    nrm.add(Float.parseFloat(st.nextToken()));
                    nrm.add(Float.parseFloat(st.nextToken()));
                    hasVn = true;
                } else if ("g".equals(tag) || "o".equals(tag)) {
                    currentGroup = st.hasMoreTokens() ? st.nextToken() : "part";
                    if (groupStart.get(groupStart.size() - 1) == faceCount) {
                        groupName.set(groupName.size() - 1, currentGroup);
                    } else {
                        groupStart.add(faceCount);
                        groupName.add(currentGroup);
                    }
                } else if ("f".equals(tag)) {
                    // 收集顶点（含 v/vt/vn 三种写法），再扇形三角化
                    ArrayList<Integer> vi = new ArrayList<>(4);
                    ArrayList<Integer> ni = new ArrayList<>(4);
                    while (st.hasMoreTokens()) {
                        String tok = st.nextToken();
                        int i1 = tok.indexOf('/');
                        int vRef;
                        int nRef = -1;
                        if (i1 < 0) {
                            vRef = Integer.parseInt(tok);
                        } else {
                            vRef = Integer.parseInt(tok.substring(0, i1));
                            int i2 = tok.indexOf('/', i1 + 1);
                            if (i2 > i1 + 1 && i2 + 1 < tok.length()) {
                                try {
                                    nRef = Integer.parseInt(tok.substring(i2 + 1));
                                } catch (NumberFormatException ignore) {
                                    nRef = -1;
                                }
                            }
                        }
                        int count = pos.size() / 3;
                        vi.add(vRef > 0 ? vRef - 1 : (vRef < 0 ? count + vRef : -1));
                        ni.add(nRef > 0 ? nRef - 1 : -1);
                    }
                    for (int k = 2; k < vi.size(); k++) {
                        idx.add((float) vi.get(0));
                        idx.add((float) vi.get(k - 1));
                        idx.add((float) vi.get(k));
                        idxN.add(ni.get(0));
                        idxN.add(ni.get(k - 1));
                        idxN.add(ni.get(k));
                        faceCount++;
                    }
                }
            }
            r.close();

            int vertexCount = pos.size() / 3;
            if (vertexCount < 3 || faceCount == 0) {
                out.error = "OBJ 里没有可用的三角面";
                return out;
            }
            if (vertexCount > MAX_VERTICES) {
                out.error = "顶点过多（" + vertexCount + "），请先减面到 6.5 万以内";
                return out;
            }

            // 包围盒归一化
            float[] b = bounds(pos);
            float scale = targetHeightM / Math.max(1e-4f, b[4] - b[1]);
            float cx = (b[0] + b[3]) * 0.5f;
            float cz = (b[2] + b[5]) * 0.5f;
            float[] p = new float[pos.size()];
            for (int i = 0; i < vertexCount; i++) {
                p[i * 3] = (pos.get(i * 3) - cx) * scale;
                p[i * 3 + 1] = (pos.get(i * 3 + 1) - b[1]) * scale;
                p[i * 3 + 2] = (pos.get(i * 3 + 2) - cz) * scale;
            }

            float[] n = new float[vertexCount * 3];
            boolean[] nSet = new boolean[vertexCount];
            if (hasVn) {
                for (int i = 0; i < idx.size(); i++) {
                    int vi = idx.get(i).intValue();
                    int ni = idxN.get(i);
                    if (ni < 0 || nSet[vi]) continue;
                    n[vi * 3] = nrm.get(ni * 3);
                    n[vi * 3 + 1] = nrm.get(ni * 3 + 1);
                    n[vi * 3 + 2] = nrm.get(ni * 3 + 2);
                    nSet[vi] = true;
                }
            }
            // 缺法线的顶点按面法线累加后归一化（平滑着色）
            for (int i = 0; i < idx.size(); i += 3) {
                int a = idx.get(i).intValue();
                int bb = idx.get(i + 1).intValue();
                int c = idx.get(i + 2).intValue();
                float[] fn = faceNormal(p, a, bb, c);
                for (int k = 0; k < 3; k++) {
                    int v = k == 0 ? a : (k == 1 ? bb : c);
                    if (hasVn && nSet[v]) continue;
                    n[v * 3] += fn[0];
                    n[v * 3 + 1] += fn[1];
                    n[v * 3 + 2] += fn[2];
                    nSet[v] = true;
                }
            }
            for (int i = 0; i < vertexCount; i++) {
                float len = (float) Math.sqrt(n[i * 3] * n[i * 3] + n[i * 3 + 1] * n[i * 3 + 1]
                        + n[i * 3 + 2] * n[i * 3 + 2]);
                if (len > 1e-6f) {
                    n[i * 3] /= len;
                    n[i * 3 + 1] /= len;
                    n[i * 3 + 2] /= len;
                } else {
                    n[i * 3 + 1] = 1f;
                }
            }

            float cr = ((color >> 16) & 0xFF) / 255f;
            float cg = ((color >> 8) & 0xFF) / 255f;
            float cb = (color & 0xFF) / 255f;
            float[] col = new float[vertexCount * 3];
            float[] ao = new float[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                col[i * 3] = cr;
                col[i * 3 + 1] = cg;
                col[i * 3 + 2] = cb;
                ao[i] = 1f;
            }

            short[] ind = new short[idx.size()];
            for (int i = 0; i < ind.length; i++) ind[i] = (short) idx.get(i).intValue();

            ArrayList<MeshBuilder.Part> parts = new ArrayList<>();
            for (int g = 0; g < groupStart.size(); g++) {
                int start = groupStart.get(g);
                int end = (g + 1 < groupStart.size()) ? groupStart.get(g + 1) : faceCount;
                if (end <= start) continue;
                parts.add(new MeshBuilder.Part(groupName.get(g), start * 3, (end - start) * 3,
                        new float[]{cr, cg, cb}));
            }
            if (parts.isEmpty()) {
                parts.add(new MeshBuilder.Part("model", 0, ind.length, new float[]{cr, cg, cb}));
            }

            HumanMesh.Result res = new HumanMesh.Result();
            res.positions = p;
            res.normals = n;
            res.colors = col;
            res.ao = ao;
            res.indices = ind;
            res.parts = parts.toArray(new MeshBuilder.Part[0]);
            res.vertices = vertexCount;
            res.triangles = ind.length / 3;
            res.heightM = targetHeightM;
            res.shoulderCm = (b[3] - b[0]) * scale * 100f;
            res.chestCm = 0f;
            res.waistCm = 0f;
            res.hipCm = 0f;
            res.inseamCm = 0f;
            res.armSpanCm = (b[5] - b[2]) * scale * 100f;
            out.mesh = res;
            return out;
        } catch (Exception e) {
            Log.e(TAG, "load obj failed", e);
            out.error = "解析失败：" + e.getMessage();
            return out;
        }
    }

    private static float[] bounds(ArrayList<Float> pos) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < pos.size(); i += 3) {
            b[0] = Math.min(b[0], pos.get(i));
            b[1] = Math.min(b[1], pos.get(i + 1));
            b[2] = Math.min(b[2], pos.get(i + 2));
            b[3] = Math.max(b[3], pos.get(i));
            b[4] = Math.max(b[4], pos.get(i + 1));
            b[5] = Math.max(b[5], pos.get(i + 2));
        }
        return b;
    }

    private static float[] faceNormal(float[] p, int a, int b, int c) {
        float ux = p[b * 3] - p[a * 3];
        float uy = p[b * 3 + 1] - p[a * 3 + 1];
        float uz = p[b * 3 + 2] - p[a * 3 + 2];
        float vx = p[c * 3] - p[a * 3];
        float vy = p[c * 3 + 1] - p[a * 3 + 1];
        float vz = p[c * 3 + 2] - p[a * 3 + 2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-8f) return new float[]{0f, 1f, 0f};
        return new float[]{nx / len, ny / len, nz / len};
    }

    public static String stat(HumanMesh.Result r) {
        return String.format(Locale.US, "导入模型：%d 顶点 / %d 三角面 · 已归一化为 %.0f cm",
                r.vertices, r.triangles, r.heightM * 100f);
    }
}
