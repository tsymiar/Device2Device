package com.tsymiar.device2device.avatar;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GLB（二进制 glTF）解析：把 Tripo3D / 其它云端生成的模型载入到预览里。
 *
 * 只取第一组 mesh 的几何（POSITION / NORMAL / indices），不解析贴图与材质；
 * 载入后与 OBJ 导入一样按包围盒归一化：脚底落到 y=0、身高对齐到当前设定。
 * 顶点超过 65000 时放弃（索引用 short，与 OpenGL 端的绘制方式一致）。
 */
public final class GlbLoader {

    private static final String TAG = "GlbLoader";
    private static final int MAX_VERTICES = 65000;

    private static final int GLB_MAGIC = 0x46546C67;      // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;     // "JSON"
    private static final int CHUNK_BIN = 0x004E4942;      // "BIN\0"

    private static final int COMP_UBYTE = 5121;
    private static final int COMP_USHORT = 5123;
    private static final int COMP_UINT = 5125;
    private static final int COMP_FLOAT = 5126;

    public static final class LoadResult {
        public HumanMesh.Result mesh;
        public String error;
    }

    private GlbLoader() {
    }

    public static LoadResult load(File file, float targetHeightM, int color) {
        byte[] bytes;
        try {
            bytes = readAll(new FileInputStream(file), (int) file.length());
        } catch (Exception e) {
            Log.e(TAG, "read glb failed", e);
            LoadResult out = new LoadResult();
            out.error = "读取文件失败：" + e.getMessage();
            return out;
        }
        return load(bytes, targetHeightM, color);
    }

    public static LoadResult load(byte[] glb, float targetHeightM, int color) {
        LoadResult out = new LoadResult();
        try {
            ByteBuffer head = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
            int magic = head.getInt();
            int version = head.getInt();
            head.getInt();                     // 文件总长度
            if (magic != GLB_MAGIC) {
                out.error = "不是 GLB 文件（magic=" + Integer.toHexString(magic) + "）";
                return out;
            }
            if (version != 2) {
                out.error = "暂不支持的 glTF 版本 " + version;
                return out;
            }

            String json = null;
            byte[] bin = null;
            while (head.remaining() >= 8) {
                int len = head.getInt();
                int type = head.getInt();
                if (len < 0 || len > head.remaining()) break;
                byte[] chunk = new byte[len];
                head.get(chunk);
                if (type == CHUNK_JSON) {
                    json = new String(chunk, "UTF-8");
                } else if (type == CHUNK_BIN) {
                    bin = chunk;
                }
                while (head.position() % 4 != 0 && head.hasRemaining()) head.get();   // 4 字节对齐填充
            }
            if (json == null) {
                out.error = "GLB 缺少 JSON 块";
                return out;
            }

            JSONObject root = new JSONObject(json);
            JSONArray meshes = root.optJSONArray("meshes");
            if (meshes == null || meshes.length() == 0) {
                out.error = "GLB 里没有 mesh";
                return out;
            }
            JSONObject prim = meshes.getJSONObject(0).optJSONArray("primitives").getJSONObject(0);
            JSONObject attrs = prim.optJSONObject("attributes");
            if (attrs == null || !attrs.has("POSITION")) {
                out.error = "GLB 缺少 POSITION 属性";
                return out;
            }
            JSONArray accessors = root.getJSONArray("accessors");
            JSONArray views = root.getJSONArray("bufferViews");

            float[] pos = readFloats(bin, accessors, views, attrs.getInt("POSITION"), 3);
            int vertexCount = pos.length / 3;
            if (vertexCount < 3) {
                out.error = "GLB 顶点数不足";
                return out;
            }
            if (vertexCount > MAX_VERTICES) {
                out.error = "顶点过多（" + vertexCount + "），请把面数上限调低再生成";
                return out;
            }

            float[] nrm = null;
            if (attrs.has("NORMAL")) {
                try {
                    nrm = readFloats(bin, accessors, views, attrs.getInt("NORMAL"), 3);
                } catch (Exception ignore) {
                    nrm = null;
                }
            }
            int[] idx = null;
            if (prim.has("indices")) {
                idx = readIndices(bin, accessors, views, prim.getInt("indices"));
            }
            if (idx == null) {          // 无索引：按 0,1,2,3… 顺序连三角
                idx = new int[vertexCount];
                for (int i = 0; i < vertexCount; i++) idx[i] = i;
            }

            out.mesh = compose(pos, nrm, idx, vertexCount, targetHeightM, color);
            return out;
        } catch (Exception e) {
            Log.e(TAG, "parse glb failed", e);
            out.error = "解析失败：" + e.getMessage();
            return out;
        }
    }

    /** 归一化 + 法线兜底 + 单色顶点色，产出与 ObjLoader 一致的 Result */
    private static HumanMesh.Result compose(float[] pos, float[] srcNrm, int[] idx,
                                            int vertexCount, float targetHeightM, int color) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < vertexCount; i++) {
            float x = pos[i * 3], y = pos[i * 3 + 1], z = pos[i * 3 + 2];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        float scale = targetHeightM / Math.max(1e-4f, maxY - minY);
        float cx = (minX + maxX) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float[] p = new float[vertexCount * 3];
        for (int i = 0; i < vertexCount; i++) {
            p[i * 3] = (pos[i * 3] - cx) * scale;
            p[i * 3 + 1] = (pos[i * 3 + 1] - minY) * scale;
            p[i * 3 + 2] = (pos[i * 3 + 2] - cz) * scale;
        }

        float[] n = new float[vertexCount * 3];
        boolean[] nSet = new boolean[vertexCount];
        if (srcNrm != null && srcNrm.length >= vertexCount * 3) {
            for (int i = 0; i < vertexCount; i++) {
                float nx = srcNrm[i * 3], ny = srcNrm[i * 3 + 1], nz = srcNrm[i * 3 + 2];
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-6f) {
                    n[i * 3] = nx / len;
                    n[i * 3 + 1] = ny / len;
                    n[i * 3 + 2] = nz / len;
                    nSet[i] = true;
                }
            }
        }
        // 缺法线的顶点按面法线累加后归一化
        for (int i = 0; i + 2 < idx.length; i += 3) {
            int a = idx[i], b = idx[i + 1], c = idx[i + 2];
            float ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
            float vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-8f) continue;
            nx /= len; ny /= len; nz /= len;
            for (int k = 0; k < 3; k++) {
                int v = k == 0 ? a : (k == 1 ? b : c);
                if (nSet[v]) continue;
                n[v * 3] += nx;
                n[v * 3 + 1] += ny;
                n[v * 3 + 2] += nz;
            }
        }
        for (int i = 0; i < vertexCount; i++) {
            if (nSet[i]) continue;
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

        short[] ind = new short[idx.length - idx.length % 3];
        for (int i = 0; i < ind.length; i++) ind[i] = (short) idx[i];

        HumanMesh.Result res = new HumanMesh.Result();
        res.positions = p;
        res.normals = n;
        res.colors = col;
        res.ao = ao;
        res.indices = ind;
        res.parts = new MeshBuilder.Part[]{
                new MeshBuilder.Part("tripo", 0, ind.length, new float[]{cr, cg, cb})};
        res.vertices = vertexCount;
        res.triangles = ind.length / 3;
        res.heightM = targetHeightM;
        res.shoulderCm = (maxX - minX) * scale * 100f;
        res.chestCm = 0f;
        res.waistCm = 0f;
        res.hipCm = 0f;
        res.inseamCm = 0f;
        res.armSpanCm = (maxZ - minZ) * scale * 100f;
        return res;
    }

    private static float[] readFloats(byte[] bin, JSONArray accessors, JSONArray views,
                                      int accIdx, int comps) throws Exception {
        JSONObject acc = accessors.getJSONObject(accIdx);
        int count = acc.getInt("count");
        int compType = acc.getInt("componentType");
        if (compType != COMP_FLOAT) {
            throw new Exception("暂不支持的顶点分量类型 " + compType);
        }
        JSONObject view = views.getJSONObject(acc.getInt("bufferView"));
        int offset = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0);
        int stride = view.optInt("byteStride", 0);
        int step = stride > 0 ? stride : comps * 4;
        ByteBuffer bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[count * comps];
        for (int i = 0; i < count; i++) {
            int base = offset + i * step;
            for (int c = 0; c < comps; c++) out[i * comps + c] = bb.getFloat(base + c * 4);
        }
        return out;
    }

    private static int[] readIndices(byte[] bin, JSONArray accessors, JSONArray views,
                                     int accIdx) throws Exception {
        JSONObject acc = accessors.getJSONObject(accIdx);
        int count = acc.getInt("count");
        int compType = acc.getInt("componentType");
        JSONObject view = views.getJSONObject(acc.getInt("bufferView"));
        int offset = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0);
        ByteBuffer bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            switch (compType) {
                case COMP_UBYTE:
                    out[i] = bb.get(offset + i) & 0xFF;
                    break;
                case COMP_USHORT:
                    out[i] = bb.getShort(offset + i * 2) & 0xFFFF;
                    break;
                case COMP_UINT:
                    out[i] = bb.getInt(offset + i * 4);
                    break;
                default:
                    throw new Exception("暂不支持的索引类型 " + compType);
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in, int size) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, size));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
