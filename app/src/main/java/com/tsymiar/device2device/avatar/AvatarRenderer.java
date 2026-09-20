package com.tsymiar.device2device.avatar;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 人体模型的 OpenGL ES 2.0 渲染器。
 *
 * 场景里除模型本身外还绘制 1:1 的地面网格（0.2m 一格）与身高标尺（0.1m 一档 / 0.5m 长刻度），
 * 用来直观核对「等身比例」；相机绕模型中心旋转，双指缩放。
 */
public class AvatarRenderer implements GLSurfaceView.Renderer {

    public interface CaptureCallback {
        void onCaptured(Bitmap bitmap);
    }

    private static final String MESH_VS =
            "uniform mat4 uMVP;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec3 aNormal;\n" +
                    "attribute vec3 aColor;\n" +
                    "attribute float aAO;\n" +
                    "varying vec3 vNormal;\n" +
                    "varying vec3 vColor;\n" +
                    "varying float vAO;\n" +
                    "varying vec3 vWorld;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMVP * aPosition;\n" +
                    "  vNormal = aNormal;\n" +
                    "  vColor = aColor;\n" +
                    "  vAO = aAO;\n" +
                    "  vWorld = aPosition.xyz;\n" +
                    "}\n";

    /**
     * 着色：半兰伯特主光 + 补光 + 地面反弹 + 半球环境光(×烘焙 AO) + Blinn 高光 + 边缘光，
     * 最后走一遍 filmic 曲线压高光抬暗部，避免塑料感。
     */
    private static final String MESH_FS =
            "precision mediump float;\n" +
                    "varying vec3 vNormal;\n" +
                    "varying vec3 vColor;\n" +
                    "varying float vAO;\n" +
                    "varying vec3 vWorld;\n" +
                    "uniform vec3 uLightDir;\n" +
                    "uniform vec3 uCamPos;\n" +
                    "void main() {\n" +
                    "  vec3 n = normalize(vNormal);\n" +
                    "  vec3 l = normalize(uLightDir);\n" +
                    "  vec3 v = normalize(uCamPos - vWorld);\n" +
                    "  float ndl = dot(n, l);\n" +
                    "  float wrap = max(ndl * 0.5 + 0.5, 0.0);\n" +
                    "  float key = mix(max(ndl, 0.0), wrap * wrap, 0.45);\n" +
                    "  float fill = max(dot(n, normalize(vec3(-0.65, 0.25, -0.55))), 0.0) * 0.22;\n" +
                    "  float bounce = max(-n.y, 0.0) * 0.12;\n" +
                    "  vec3 sky = vec3(0.30, 0.36, 0.48);\n" +
                    "  vec3 ground = vec3(0.13, 0.12, 0.11);\n" +
                    "  vec3 amb = mix(ground, sky, 0.5 + 0.5 * n.y) * vAO;\n" +
                    "  vec3 h = normalize(l + v);\n" +
                    "  float spec = pow(max(dot(n, h), 0.0), 24.0) * 0.18;\n" +
                    "  float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.30 * vAO;\n" +
                    "  vec3 c = vColor * (amb + 0.78 * key + fill + bounce)\n" +
                    "          + vec3(spec) + vec3(0.42, 0.55, 0.78) * rim;\n" +
                    "  c = c / (c + vec3(0.9)) * 1.42;\n" +
                    "  gl_FragColor = vec4(c, 1.0);\n" +
                    "}\n";

    private static final String LINE_VS =
            "uniform mat4 uMVP;\n" +
                    "attribute vec4 aPosition;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMVP * aPosition;\n" +
                    "}\n";

    private static final String LINE_FS =
            "precision mediump float;\n" +
                    "uniform vec4 uColor;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = uColor;\n" +
                    "}\n";

    /** 脚下的接触阴影：单位圆盘按椭圆缩放后贴地绘制，中间浓、边缘淡 */
    private static final String SHADOW_VS =
            "uniform mat4 uMVP;\n" +
                    "uniform vec2 uCenter;\n" +
                    "uniform vec2 uRadius;\n" +
                    "attribute vec4 aPosition;\n" +
                    "varying float vR;\n" +
                    "void main() {\n" +
                    "  vec3 p = vec3(uCenter.x + aPosition.x * uRadius.x, 0.002,\n" +
                    "                uCenter.y + aPosition.z * uRadius.y);\n" +
                    "  gl_Position = uMVP * vec4(p, 1.0);\n" +
                    "  vR = length(aPosition.xz);\n" +
                    "}\n";

    private static final String SHADOW_FS =
            "precision mediump float;\n" +
                    "varying float vR;\n" +
                    "void main() {\n" +
                    "  float a = pow(max(0.0, 1.0 - vR * vR), 1.6) * 0.55;\n" +
                    "  gl_FragColor = vec4(0.01, 0.01, 0.02, a);\n" +
                    "}\n";

    /** 刻度数字：锚点 + 相机平面内偏移，保证数字始终正对镜头可读 */
    private static final String LABEL_VS =
            "uniform mat4 uMVP;\n" +
                    "uniform vec3 uRight;\n" +
                    "uniform vec3 uUp;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec2 aOffset;\n" +
                    "void main() {\n" +
                    "  vec3 p = aPosition.xyz + uRight * aOffset.x + uUp * aOffset.y;\n" +
                    "  gl_Position = uMVP * vec4(p, 1.0);\n" +
                    "}\n";

    private static final float GRID_HALF = 1.2f;
    private static final float GRID_STEP = 0.2f;

    private final float[] mProj = new float[16];
    private final float[] mView = new float[16];
    private final float[] mMvp = new float[16];

    private int mMeshProgram;
    private int mLineProgram;
    private int mShadowProgram;
    private int mMvpHandle;
    private int mLightHandle;
    private int mCamHandle;
    private int mPosHandle;
    private int mNormalHandle;
    private int mColorHandle;
    private int mAoHandle;
    private int mLineMvpHandle;
    private int mLinePosHandle;
    private int mLineColorHandle;
    private int mShadowMvpHandle;
    private int mShadowPosHandle;
    private int mShadowCenterHandle;
    private int mShadowRadiusHandle;
    private int mLabelProgram;
    private int mLabelMvpHandle;
    private int mLabelRightHandle;
    private int mLabelUpHandle;
    private int mLabelPosHandle;
    private int mLabelOffsetHandle;
    private int mLabelColorHandle;

    private FloatBuffer mVertexBuf;
    private FloatBuffer mNormalBuf;
    private FloatBuffer mColorBuf;
    private FloatBuffer mAoBuf;
    private ShortBuffer mIndexBuf;
    private int mIndexCount;

    private FloatBuffer mGridBuf;
    private int mGridCount;
    private FloatBuffer mShadowBuf;
    private int mShadowCount;
    private FloatBuffer mRulerBuf;
    private int mRulerCount;
    private FloatBuffer mMarkBuf;
    private int mMarkCount;
    /** 标尺刻度数字与身高数字：锚点(3) + 相机平面偏移(2)，按 GL_LINES 画 */
    private FloatBuffer mTickAnchorBuf;
    private FloatBuffer mTickOffsetBuf;
    private int mTickLabelCount;
    private FloatBuffer mTopAnchorBuf;
    private FloatBuffer mTopOffsetBuf;
    private int mTopLabelCount;

    private float mYaw = 24f;
    private float mPitch = 10f;
    private float mZoom = 1f;
    private float mHeight = 1.72f;
    private int mWidth = 1;
    private int mHeightPx = 1;

    private volatile boolean mCapture;
    private CaptureCallback mCaptureCb;

    // ------------------------------------------------------------------
    // 外部接口
    // ------------------------------------------------------------------

    public void setMesh(HumanMesh.Result r) {
        if (r == null) return;
        mVertexBuf = toFloat(r.positions);
        mNormalBuf = toFloat(r.normals);
        mColorBuf = toFloat(r.colors);
        mAoBuf = r.ao == null ? null : toFloat(r.ao);
        mIndexBuf = toShort(r.indices);
        mIndexCount = r.indices.length;
        mHeight = r.heightM;
        buildRuler();
    }

    public void setHeight(float meters) {
        mHeight = meters;
        buildRuler();
    }

    public void rotate(float dYaw, float dPitch) {
        mYaw += dYaw;
        mPitch = Math.max(-15f, Math.min(70f, mPitch + dPitch));
    }

    public void zoom(float factor) {
        mZoom = Math.max(0.45f, Math.min(3.2f, mZoom * factor));
    }

    public void resetView() {
        mYaw = 24f;
        mPitch = 10f;
        mZoom = 1f;
    }

    public void requestCapture(CaptureCallback cb) {
        mCaptureCb = cb;
        mCapture = true;
    }

    // ------------------------------------------------------------------
    // Renderer
    // ------------------------------------------------------------------

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.055f, 0.067f, 0.086f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        mMeshProgram = program(MESH_VS, MESH_FS);
        mMvpHandle = GLES20.glGetUniformLocation(mMeshProgram, "uMVP");
        mLightHandle = GLES20.glGetUniformLocation(mMeshProgram, "uLightDir");
        mCamHandle = GLES20.glGetUniformLocation(mMeshProgram, "uCamPos");
        mPosHandle = GLES20.glGetAttribLocation(mMeshProgram, "aPosition");
        mNormalHandle = GLES20.glGetAttribLocation(mMeshProgram, "aNormal");
        mColorHandle = GLES20.glGetAttribLocation(mMeshProgram, "aColor");
        mAoHandle = GLES20.glGetAttribLocation(mMeshProgram, "aAO");

        mLineProgram = program(LINE_VS, LINE_FS);
        mLineMvpHandle = GLES20.glGetUniformLocation(mLineProgram, "uMVP");
        mLinePosHandle = GLES20.glGetAttribLocation(mLineProgram, "aPosition");
        mLineColorHandle = GLES20.glGetUniformLocation(mLineProgram, "uColor");

        mShadowProgram = program(SHADOW_VS, SHADOW_FS);
        mShadowMvpHandle = GLES20.glGetUniformLocation(mShadowProgram, "uMVP");
        mShadowPosHandle = GLES20.glGetAttribLocation(mShadowProgram, "aPosition");
        mShadowCenterHandle = GLES20.glGetUniformLocation(mShadowProgram, "uCenter");
        mShadowRadiusHandle = GLES20.glGetUniformLocation(mShadowProgram, "uRadius");

        mLabelProgram = program(LABEL_VS, LINE_FS);
        mLabelMvpHandle = GLES20.glGetUniformLocation(mLabelProgram, "uMVP");
        mLabelRightHandle = GLES20.glGetUniformLocation(mLabelProgram, "uRight");
        mLabelUpHandle = GLES20.glGetUniformLocation(mLabelProgram, "uUp");
        mLabelPosHandle = GLES20.glGetAttribLocation(mLabelProgram, "aPosition");
        mLabelOffsetHandle = GLES20.glGetAttribLocation(mLabelProgram, "aOffset");
        mLabelColorHandle = GLES20.glGetUniformLocation(mLabelProgram, "uColor");

        buildGrid();
        buildRuler();
        buildShadowFan();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mWidth = width;
        mHeightPx = height;
        GLES20.glViewport(0, 0, width, height);
        float aspect = width / (float) Math.max(1, height);
        Matrix.perspectiveM(mProj, 0, 38f, aspect, 0.05f, 60f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        float targetY = mHeight * 0.5f;
        float dist = Math.max(1.6f, mHeight * 2.15f) / mZoom;
        double rad = Math.toRadians(mYaw);
        double radP = Math.toRadians(mPitch);
        float ey = targetY + (float) (dist * Math.sin(radP));
        float horiz = (float) (dist * Math.cos(radP));
        float ex = (float) (horiz * Math.sin(rad));
        float ez = (float) (horiz * Math.cos(rad));
        Matrix.setLookAtM(mView, 0, ex, ey, ez, 0f, targetY, 0f, 0f, 1f, 0f);
        Matrix.multiplyMM(mMvp, 0, mProj, 0, mView, 0);

        drawLines(mGridBuf, mGridCount, 0.36f, 0.44f, 0.55f, 0.55f);
        drawShadow();
        drawLines(mRulerBuf, mRulerCount, 0.75f, 0.82f, 0.92f, 0.85f);
        drawLines(mMarkBuf, mMarkCount, 1.0f, 0.72f, 0.35f, 0.95f);
        drawLabels(mTickAnchorBuf, mTickOffsetBuf, mTickLabelCount,
                0.78f, 0.86f, 0.95f, 0.9f);
        drawLabels(mTopAnchorBuf, mTopOffsetBuf, mTopLabelCount,
                1.0f, 0.80f, 0.40f, 1.0f);

        if (mVertexBuf == null || mIndexCount == 0) {
            finishCapture();
            return;
        }

        GLES20.glUseProgram(mMeshProgram);
        GLES20.glUniformMatrix4fv(mMvpHandle, 1, false, mMvp, 0);
        GLES20.glUniform3f(mLightHandle, 0.45f, 0.78f, 0.62f);
        GLES20.glUniform3f(mCamHandle, ex, ey, ez);

        GLES20.glEnableVertexAttribArray(mPosHandle);
        mVertexBuf.position(0);
        GLES20.glVertexAttribPointer(mPosHandle, 3, GLES20.GL_FLOAT, false, 0, mVertexBuf);
        GLES20.glEnableVertexAttribArray(mNormalHandle);
        mNormalBuf.position(0);
        GLES20.glVertexAttribPointer(mNormalHandle, 3, GLES20.GL_FLOAT, false, 0, mNormalBuf);
        GLES20.glEnableVertexAttribArray(mColorHandle);
        mColorBuf.position(0);
        GLES20.glVertexAttribPointer(mColorHandle, 3, GLES20.GL_FLOAT, false, 0, mColorBuf);
        if (mAoBuf != null) {
            GLES20.glEnableVertexAttribArray(mAoHandle);
            mAoBuf.position(0);
            GLES20.glVertexAttribPointer(mAoHandle, 1, GLES20.GL_FLOAT, false, 0, mAoBuf);
        }

        mIndexBuf.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mIndexCount, GLES20.GL_UNSIGNED_SHORT, mIndexBuf);

        GLES20.glDisableVertexAttribArray(mPosHandle);
        GLES20.glDisableVertexAttribArray(mNormalHandle);
        GLES20.glDisableVertexAttribArray(mColorHandle);
        if (mAoBuf != null) GLES20.glDisableVertexAttribArray(mAoHandle);

        finishCapture();
    }

    /** 脚下的接触阴影，让模型"踩"在地面上 */
    private void drawShadow() {
        if (mShadowBuf == null || mShadowCount == 0) return;
        GLES20.glUseProgram(mShadowProgram);
        GLES20.glUniformMatrix4fv(mShadowMvpHandle, 1, false, mMvp, 0);
        GLES20.glUniform2f(mShadowCenterHandle, 0f, mHeight * 0.03f);
        GLES20.glUniform2f(mShadowRadiusHandle, mHeight * 0.16f, mHeight * 0.14f);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glEnableVertexAttribArray(mShadowPosHandle);
        mShadowBuf.position(0);
        GLES20.glVertexAttribPointer(mShadowPosHandle, 3, GLES20.GL_FLOAT, false, 0, mShadowBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, mShadowCount);
        GLES20.glDisableVertexAttribArray(mShadowPosHandle);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawLines(FloatBuffer buf, int count, float r, float g, float b, float a) {
        if (buf == null || count == 0) return;
        GLES20.glUseProgram(mLineProgram);
        GLES20.glUniformMatrix4fv(mLineMvpHandle, 1, false, mMvp, 0);
        GLES20.glUniform4f(mLineColorHandle, r, g, b, a);
        GLES20.glEnableVertexAttribArray(mLinePosHandle);
        buf.position(0);
        GLES20.glVertexAttribPointer(mLinePosHandle, 3, GLES20.GL_FLOAT, false, 0, buf);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, count);
        GLES20.glDisableVertexAttribArray(mLinePosHandle);
    }

    /** 标尺数字：锚点 + 相机右/上方向偏移，等价于 billboard 文字 */
    private void drawLabels(FloatBuffer anchor, FloatBuffer offset, int count,
                            float r, float g, float b, float a) {
        if (anchor == null || offset == null || count == 0) return;
        GLES20.glUseProgram(mLabelProgram);
        GLES20.glUniformMatrix4fv(mLabelMvpHandle, 1, false, mMvp, 0);
        // 视图矩阵的第一/第二行即相机的右、上方向
        GLES20.glUniform3f(mLabelRightHandle, mView[0], mView[4], mView[8]);
        GLES20.glUniform3f(mLabelUpHandle, mView[1], mView[5], mView[9]);
        GLES20.glUniform4f(mLabelColorHandle, r, g, b, a);
        GLES20.glEnableVertexAttribArray(mLabelPosHandle);
        anchor.position(0);
        GLES20.glVertexAttribPointer(mLabelPosHandle, 3, GLES20.GL_FLOAT, false, 0, anchor);
        GLES20.glEnableVertexAttribArray(mLabelOffsetHandle);
        offset.position(0);
        GLES20.glVertexAttribPointer(mLabelOffsetHandle, 2, GLES20.GL_FLOAT, false, 0, offset);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, count);
        GLES20.glDisableVertexAttribArray(mLabelPosHandle);
        GLES20.glDisableVertexAttribArray(mLabelOffsetHandle);
    }

    // ------------------------------------------------------------------
    // 场景辅助几何
    // ------------------------------------------------------------------

    private void buildGrid() {
        int steps = Math.round(GRID_HALF / GRID_STEP);
        float[] v = new float[(steps * 2 + 1) * 4 * 3];
        int i = 0;
        for (int s = -steps; s <= steps; s++) {
            float c = s * GRID_STEP;
            v[i++] = c; v[i++] = 0f; v[i++] = -GRID_HALF;
            v[i++] = c; v[i++] = 0f; v[i++] = GRID_HALF;
            v[i++] = -GRID_HALF; v[i++] = 0f; v[i++] = c;
            v[i++] = GRID_HALF; v[i++] = 0f; v[i++] = c;
        }
        mGridBuf = toFloat(v);
        mGridCount = v.length / 3;
    }

    /** 身高标尺：立在右前方，0.1m 一档，0.5m 长刻度；顶端的水平线即当前身高 */
    private void buildRuler() {
        float x = 0.62f;
        float z = 0.42f;
        float top = Math.max(2.2f, mHeight + 0.15f);
        float[] v = new float[(1 + 22 * 2) * 2 * 3];
        int i = 0;
        v[i++] = x; v[i++] = 0f; v[i++] = z;
        v[i++] = x; v[i++] = top; v[i++] = z;
        for (int k = 1; k <= 22; k++) {
            float y = k * 0.1f;
            if (y > top) break;
            float len = (k % 5 == 0) ? 0.12f : 0.05f;
            v[i++] = x; v[i++] = y; v[i++] = z;
            v[i++] = x - len; v[i++] = y; v[i++] = z;
        }
        int used = i / 3;
        float[] vv = new float[used * 3];
        System.arraycopy(v, 0, vv, 0, used * 3);
        mRulerBuf = toFloat(vv);
        mRulerCount = used;

        mMarkBuf = toFloat(new float[]{x - 0.30f, mHeight, z, x + 0.02f, mHeight, z});
        mMarkCount = 2;

        // ---- 刻度数字（0.5m 一档）+ 顶端身高数值 ----
        float size = Math.max(0.040f, 0.034f * mHeight);
        Glyphs ticks = new Glyphs();
        for (int k = 1; k * 0.5f <= top + 1e-4f; k++) {
            float y = k * 0.5f;
            ticks.text(String.format(Locale.US, "%.1f", y),
                    x + 0.035f, y - size * 0.45f, z, size);
        }
        mTickAnchorBuf = toFloat(ticks.anchors());
        mTickOffsetBuf = toFloat(ticks.offsets());
        mTickLabelCount = ticks.count();

        Glyphs topLabel = new Glyphs();
        topLabel.text(String.format(Locale.US, "%.2f", mHeight) + "m",
                x + 0.05f, mHeight + size * 0.30f, z, size * 1.15f);
        mTopAnchorBuf = toFloat(topLabel.anchors());
        mTopOffsetBuf = toFloat(topLabel.offsets());
        mTopLabelCount = topLabel.count();
    }

    /** 接触阴影用的单位圆盘（三角形扇） */
    private void buildShadowFan() {
        int seg = 40;
        float[] v = new float[(seg + 2) * 3];
        int i = 0;
        v[i++] = 0f; v[i++] = 0f; v[i++] = 0f;
        for (int k = 0; k <= seg; k++) {
            double a = 2.0 * Math.PI * k / seg;
            v[i++] = (float) Math.cos(a);
            v[i++] = 0f;
            v[i++] = (float) Math.sin(a);
        }
        mShadowBuf = toFloat(v);
        mShadowCount = seg + 2;
    }

    /**
     * 线段字形集合：用 7 段数码管 + 手写的小写 m 直接画出标尺数字，
     * 省掉文字引擎与贴图，几个数字也几乎不占顶点预算。
     */
    private static final class Glyphs {

        private static final float W = 0.55f;               // 字宽 / 字高
        /** 7 段笔划：(x0, y0, x1, y1)，坐标在 0~W / 0~1 的字身框内 */
        private static final float[][] SEG = {
                {0f, 1f, W, 1f},        // 0 上
                {0f, 0.5f, 0f, 1f},     // 1 左上
                {W, 0.5f, W, 1f},       // 2 右上
                {0f, 0.5f, W, 0.5f},    // 3 中
                {0f, 0f, 0f, 0.5f},     // 4 左下
                {W, 0f, W, 0.5f},       // 5 右下
                {0f, 0f, W, 0f},        // 6 下
        };
        /** 0~9 各点亮哪几段 */
        private static final int[] DIGIT = {119, 36, 93, 109, 46, 107, 123, 37, 127, 111};

        private final ArrayList<Float> anchor = new ArrayList<>();
        private final ArrayList<Float> offset = new ArrayList<>();

        private void seg(float ax, float ay, float az, float x0, float y0, float x1, float y1) {
            anchor.add(ax);
            anchor.add(ay);
            anchor.add(az);
            offset.add(x0);
            offset.add(y0);
            anchor.add(ax);
            anchor.add(ay);
            anchor.add(az);
            offset.add(x1);
            offset.add(y1);
        }

        /** 在锚点处写一串字符；pen 沿镜头右方向推进 */
        void text(String s, float ax, float ay, float az, float size) {
            float pen = 0f;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    int mask = DIGIT[c - '0'];
                    for (int b = 0; b < 7; b++) {
                        if ((mask & (1 << b)) == 0) continue;
                        float[] g = SEG[b];
                        seg(ax, ay, az, pen + g[0] * size, g[1] * size,
                                pen + g[2] * size, g[3] * size);
                    }
                    pen += 0.78f * size;
                } else if (c == '.') {
                    seg(ax, ay, az, pen, 0f, pen + 0.16f * size, 0f);
                    pen += 0.40f * size;
                } else if (c == 'm') {
                    seg(ax, ay, az, pen, 0f, pen, 0.50f * size);
                    seg(ax, ay, az, pen, 0.50f * size, pen + 0.38f * size, 0.50f * size);
                    seg(ax, ay, az, pen + 0.38f * size, 0.50f * size, pen + 0.38f * size, 0f);
                    seg(ax, ay, az, pen + 0.38f * size, 0.50f * size, pen + 0.76f * size, 0.45f * size);
                    seg(ax, ay, az, pen + 0.76f * size, 0.45f * size, pen + 0.76f * size, 0f);
                    pen += 0.95f * size;
                } else {
                    pen += 0.40f * size;
                }
            }
        }

        float[] anchors() {
            float[] out = new float[anchor.size()];
            for (int i = 0; i < out.length; i++) out[i] = anchor.get(i);
            return out;
        }

        float[] offsets() {
            float[] out = new float[offset.size()];
            for (int i = 0; i < out.length; i++) out[i] = offset.get(i);
            return out;
        }

        int count() {
            return offset.size() / 2;
        }
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------

    private void finishCapture() {
        if (!mCapture) return;
        mCapture = false;
        CaptureCallback cb = mCaptureCb;
        mCaptureCb = null;
        if (cb == null) return;
        int w = mWidth;
        int h = mHeightPx;
        if (w <= 0 || h <= 0) {
            cb.onCaptured(null);
            return;
        }
        ByteBuffer raw = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, raw);
        raw.position(0);
        byte[] bytes = new byte[w * h * 4];
        raw.get(bytes);
        int[] argb = new int[w * h];
        for (int p = 0; p < argb.length; p++) {
            argb[p] = 0xFF000000
                    | ((bytes[p * 4] & 0xFF) << 16)
                    | ((bytes[p * 4 + 1] & 0xFF) << 8)
                    | (bytes[p * 4 + 2] & 0xFF);
        }
        int[] flip = new int[argb.length];
        for (int y = 0; y < h; y++) {
            System.arraycopy(argb, (h - 1 - y) * w, flip, y * w, w);
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(flip, 0, w, 0, 0, w, h);
        cb.onCaptured(bmp);
    }

    // ------------------------------------------------------------------
    // GL 小工具
    // ------------------------------------------------------------------

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            throw new IllegalStateException("link failed: " + GLES20.glGetProgramInfoLog(p));
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IllegalStateException("compile failed: " + log);
        }
        return s;
    }

    private static FloatBuffer toFloat(float[] a) {
        ByteBuffer bb = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(a);
        fb.position(0);
        return fb;
    }

    private static ShortBuffer toShort(short[] a) {
        ByteBuffer bb = ByteBuffer.allocateDirect(a.length * 2).order(ByteOrder.nativeOrder());
        ShortBuffer sb = bb.asShortBuffer();
        sb.put(a);
        sb.position(0);
        return sb;
    }
}
