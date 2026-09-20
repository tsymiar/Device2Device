package com.tsymiar.device2device.avatar;

import java.util.ArrayList;

/**
 * 隐式曲面场景：胶囊 / 椭球 / 圆角盒 的「平滑并集」。
 *
 * 身体不再用一堆互相穿插的图元拼出来，而是先定义一块连续的标量场（负值在体内、正值在体外），
 * 关节处用平滑最小值（polynomial smooth-min）做并集，于是肩、髋、膝、腋下都会自然过渡出圆角，
 * 不会出现拼接的硬边与穿插裂缝；等值面再由 {@link SurfaceNets} 提取成三角网格。
 *
 * 场值同时记录「最近图元的材质」，用于给顶点着色（皮肤 / 上装 / 下装 / 鞋）。
 */
public final class SdfModel {

    public static final int MAT_SKIN = 0;
    public static final int MAT_TOP = 1;
    public static final int MAT_BOTTOM = 2;
    public static final int MAT_SHOE = 3;

    private static final int CAPSULE = 0;
    private static final int ELLIPSOID = 1;
    private static final int BOX = 2;

    private final ArrayList<Prim> mPrims = new ArrayList<>(48);
    private float mBlend = 0.02f;

    /** 设置平滑并集的圆角半径（米）：越大关节越圆润，越小越贴合原图元 */
    public void setBlend(float blend) {
        mBlend = Math.max(1e-4f, blend);
    }

    /** 锥形胶囊（r0 → r1 线性过渡），p0 == p1 时退化为球 */
    public void addCapsule(float x0, float y0, float z0, float x1, float y1, float z1,
                           float r0, float r1, int mat) {
        Prim p = new Prim(CAPSULE, mat);
        p.a0 = x0; p.a1 = y0; p.a2 = z0;
        p.b0 = x1; p.b1 = y1; p.b2 = z1;
        p.r0 = r0; p.r1 = r1;
        p.yLo = Math.min(y0, y1) - Math.max(r0, r1);
        p.yHi = Math.max(y0, y1) + Math.max(r0, r1);
        mPrims.add(p);
    }

    public void addSphere(float cx, float cy, float cz, float r, int mat) {
        addCapsule(cx, cy, cz, cx, cy, cz, r, r, mat);
    }

    public void addEllipsoid(float cx, float cy, float cz, float rx, float ry, float rz, int mat) {
        Prim p = new Prim(ELLIPSOID, mat);
        p.a0 = cx; p.a1 = cy; p.a2 = cz;
        p.r0 = Math.max(1e-4f, rx);
        p.r1 = Math.max(1e-4f, ry);
        p.r2 = Math.max(1e-4f, rz);
        p.yLo = cy - p.r1;
        p.yHi = cy + p.r1;
        mPrims.add(p);
    }

    /** 圆角盒（rx/ry/rz 为半尺寸，round 为圆角半径），用来做鞋这类带平面的形状 */
    public void addBox(float cx, float cy, float cz, float rx, float ry, float rz, float round, int mat) {
        Prim p = new Prim(BOX, mat);
        p.a0 = cx; p.a1 = cy; p.a2 = cz;
        p.r0 = rx; p.r1 = ry; p.r2 = rz; p.r3 = round;
        p.yLo = cy - ry - round;
        p.yHi = cy + ry + round;
        mPrims.add(p);
    }

    public int size() {
        return mPrims.size();
    }

    /** 有符号距离：体内为负、体外为正（平滑并集会让并集处略微内凹） */
    public float field(float x, float y, float z) {
        float res = 1e9f;
        float pad = 2f * mBlend;
        for (int i = 0; i < mPrims.size(); i++) {
            Prim p = mPrims.get(i);
            // 高度剔除：够不到这个高度面的图元不必参与求值（躯干细化后图元变多，这一步是关键）
            if (y < p.yLo - pad || y > p.yHi + pad) continue;
            res = smin(res, p.distance(x, y, z), mBlend);
        }
        return res;
    }

    /** 最近图元的材质，用于顶点着色 */
    public int material(float x, float y, float z) {
        float best = 1e9f;
        int mat = MAT_SKIN;
        float pad = 2f * mBlend;
        for (int i = 0; i < mPrims.size(); i++) {
            Prim p = mPrims.get(i);
            if (y < p.yLo - pad || y > p.yHi + pad) continue;
            float d = p.distance(x, y, z);
            if (d < best) {
                best = d;
                mat = p.mat;
            }
        }
        return mat;
    }

    /** 中心差分梯度（场值增大的方向 = 外法线方向） */
    public void gradient(float x, float y, float z, float[] out) {
        float e = 0.0035f;
        out[0] = field(x + e, y, z) - field(x - e, y, z);
        out[1] = field(x, y + e, z) - field(x, y - e, z);
        out[2] = field(x, y, z + e) - field(x, y, z - e);
        float len = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
        if (len > 1e-8f) {
            out[0] /= len;
            out[1] /= len;
            out[2] /= len;
        } else {
            out[0] = 0f;
            out[1] = 1f;
            out[2] = 0f;
        }
    }

    /** 多项式平滑最小值 */
    private static float smin(float a, float b, float k) {
        float h = clamp(0.5f + 0.5f * (b - a) / k, 0f, 1f);
        return b + (a - b) * h - k * h * (1f - h);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static final class Prim {
        final int type;
        final int mat;
        float a0, a1, a2;      // 起点 / 中心
        float b0, b1, b2;      // 终点
        float r0, r1, r2, r3;  // 半径 / 半尺寸 / 圆角
        float yLo, yHi;        // y 方向包围盒，用于 field() 的高度剔除

        Prim(int type, int mat) {
            this.type = type;
            this.mat = mat;
        }

        float distance(float x, float y, float z) {
            switch (type) {
                case CAPSULE: {
                    float pax = x - a0, pay = y - a1, paz = z - a2;
                    float bax = b0 - a0, bay = b1 - a1, baz = b2 - a2;
                    float bb = bax * bax + bay * bay + baz * baz;
                    float h = bb > 1e-12f
                            ? clamp((pax * bax + pay * bay + paz * baz) / bb, 0f, 1f) : 0f;
                    float dx = pax - bax * h, dy = pay - bay * h, dz = paz - baz * h;
                    return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - (r0 + (r1 - r0) * h);
                }
                case ELLIPSOID: {
                    float px = (x - a0) / r0, py = (y - a1) / r1, pz = (z - a2) / r2;
                    float k0 = (float) Math.sqrt(px * px + py * py + pz * pz);
                    float qx = px / r0, qy = py / r1, qz = pz / r2;
                    float k1 = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
                    if (k1 < 1e-9f) return -Math.min(Math.min(r0, r1), r2);
                    return k0 * (k0 - 1f) / k1;
                }
                default: {
                    float qx = Math.abs(x - a0) - r0;
                    float qy = Math.abs(y - a1) - r1;
                    float qz = Math.abs(z - a2) - r2;
                    float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f), oz = Math.max(qz, 0f);
                    float outside = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
                    float inside = Math.min(Math.max(qx, Math.max(qy, qz)), 0f);
                    return outside + inside - r3;
                }
            }
        }
    }
}
