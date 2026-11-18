/*
 * Copyright (C) 2008 Google Inc. Licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.utils.SoundRecord;

import java.nio.ShortBuffer;
import java.util.ArrayList;

/**
 * 文件波形视图。
 *
 * 数据来源不是按帧平滑过的 frameGains，而是解码后的原始采样：加载时建一套
 * min/max 包络金字塔（块大小 32、128、512……），绘制时每个屏幕像素列取
 * 覆盖采样的 min/max 画竖线 —— 缩小不漏峰值、放大能看到真实采样细节。
 *
 * 交互：单指左右拖动平移；双指捏合连续缩放；双击放大；长按后左右拖动框选区域，
 * 松手放大到该区域。
 */
public class WaveformsView extends View {
    // Colors
    private final Paint mSelectedLinePaint;
    private final Paint mUnselectedLinePaint;
    private final Paint mTimeValuePaint;
    private final Paint circlePaint;
    private final Paint mCenterLinePaint;
    private final Paint mBorderLinePaint;
    private final Paint mRegionFillPaint;
    private final Paint mRegionBorderPaint;
    /** 播放指示线：固定 1 像素，不随缩放变粗 */
    private final Paint mPlayLinePaint;
    private int mLineOffset;
    private int mPlayFinish;

    private SoundRecord mSoundRecord;
    private int[] mLenByZoomLevel;
    private double[] mZoomFactorByZoomLevel;
    private int mZoomLevel;
    private int mNumZoomLevels;
    private int mSampleRate;
    private int mSamplesPerFrame;
    private int mOffset;
    private int mSelectionStart;
    private int mSelectionEnd;
    private int mPlaybackPos;
    private boolean mInitialized;
    private float mDensity;
    private int state = 0;

    /** 连续缩放倍率：1 = 整条波形刚好铺满一屏；双指捏合逐帧更新，画面跟手 */
    private float mZoomFactor = 1f;
    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 32f;
    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mGestureDetector;

    /** 原始采样（只读副本）与 min/max 包络金字塔 */
    private ShortBuffer mRawSamples;
    private int mNumSamples;
    private int mChannels = 1;
    private int mEnvMaxAbs = 1;
    private final ArrayList<short[]> mEnvMin = new ArrayList<>();
    private final ArrayList<short[]> mEnvMax = new ArrayList<>();
    private int[] mEnvBlocks = new int[0];

    /** 显示增益：和文件卡片的音量/增益滑杆联动，直接改变波形高度 */
    private float mDisplayGain = 1f;

    /** 单指平移 / 长按框选 */
    private float mLastTouchX = -1f;
    /** 平移不足一帧的位移留到下一次事件，避免整数截断导致拖动一顿一顿 */
    private float mPanRemainder;
    private boolean mRegionSelecting;
    private int mRegionStartX;
    private int mRegionEndX;

    /** 批量绘制波形线的顶点缓冲（{@code x0,y0,x1,y1} × N），避免逐像素 drawLine */
    private float[] mLines = new float[0];
    private float[] mSelectedLines = new float[0];

    public int getPlayFinish() {
        return mPlayFinish;
    }

    public void setPlayFinish(int playFinish) {
        this.mPlayFinish = playFinish;
    }

    public int getState() {
        return state;
    }

    public void setState(int state) {
        this.state = state;
    }

    public int getLineOffset() {
        return mLineOffset;
    }

    public void setLineOffset(int lineOffset) {
        this.mLineOffset = lineOffset;
    }

    public WaveformsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);

        circlePaint = new Paint();
        circlePaint.setColor(Color.rgb(246, 131, 126));
        circlePaint.setAntiAlias(true);
        mPlayLinePaint = new Paint();
        mPlayLinePaint.setColor(Color.rgb(246, 131, 126));
        mPlayLinePaint.setStrokeWidth(1f);
        mSelectedLinePaint = new Paint();
        mSelectedLinePaint.setAntiAlias(false);
        mSelectedLinePaint.setColor(Color.rgb(39, 199, 175));
        mUnselectedLinePaint = new Paint();
        mUnselectedLinePaint.setAntiAlias(false);
        mUnselectedLinePaint.setColor(Color.rgb(39, 199, 175));
        mCenterLinePaint = new Paint();
        mCenterLinePaint.setColor(Color.rgb(39, 199, 175));
        mBorderLinePaint = new Paint();
        mBorderLinePaint.setColor(Color.rgb(169, 169, 169));
        mTimeValuePaint = new Paint();
        mTimeValuePaint.setTextSize(12);
        mTimeValuePaint.setAntiAlias(true);
        mTimeValuePaint.setColor(getResources().getColor(R.color.time_value));
        mTimeValuePaint.setShadowLayer(2, 1, 1, getResources().getColor(R.color.time_value_shadow));
        mRegionFillPaint = new Paint();
        mRegionFillPaint.setColor(Color.argb(56, 39, 199, 175));
        mRegionFillPaint.setStyle(Paint.Style.FILL);
        mRegionBorderPaint = new Paint();
        mRegionBorderPaint.setColor(Color.rgb(39, 199, 175));
        mRegionBorderPaint.setStrokeWidth(1.5f);
        mRegionBorderPaint.setPathEffect(new DashPathEffect(new float[]{4f, 3f}, 0f));

        mSoundRecord = null;
        mLenByZoomLevel = null;
        mOffset = 0;
        mPlaybackPos = -1;
        mDensity = 1.0f;
        mInitialized = false;

        mScaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        // 捏合焦点锁在两指中心，画面实时跟手
                        setZoomFactor(mZoomFactor * detector.getScaleFactor(),
                                detector.getFocusX());
                        return true;
                    }
                });
        mGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(MotionEvent e) {
                        mRegionSelecting = true;
                        mRegionStartX = mRegionEndX = (int) e.getX();
                        invalidate();
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        // 双击就地放大一档
                        setZoomFactor(mZoomFactor * 2f, e.getX());
                        return true;
                    }
                });
    }

    public boolean hasSoundFile() {
        return mSoundRecord != null;
    }

    public void setSoundFile(SoundRecord soundRecord) {
        mSoundRecord = soundRecord;
        mSampleRate = mSoundRecord.getSampleRate();
        mSamplesPerFrame = mSoundRecord.getSamplesPerFrame();
        computeZoomBase();
        buildEnvelope();
        // 换文件时从"整条铺满一屏"开始，别把上一首的缩放/偏移带过来
        mZoomFactor = 1f;
        mOffset = 0;
        mRegionSelecting = false;
        mSelectionStart = mSelectionEnd = 0;
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public int getZoomLevel() {
        return mZoomLevel;
    }

    /** level 0..4 映射到连续倍率 2×/1×/0.5×/0.25×/0.125×（兼容外部按级设置的调用） */
    public void setZoomLevel(int zoomLevel) {
        setZoomFactor((float) Math.pow(2, 1 - zoomLevel), getMeasuredWidth() / 2f);
    }

    public boolean canZoomIn() {
        return mZoomFactor < MAX_ZOOM;
    }

    public boolean canZoomOut() {
        return mZoomFactor > MIN_ZOOM;
    }

    public void zoomIn() {
        setZoomFactor(mZoomFactor * 2f, getMeasuredWidth() / 2f);
    }

    public void zoomOut() {
        setZoomFactor(mZoomFactor / 2f, getMeasuredWidth() / 2f);
    }

    /** 连续设置缩放倍率，并把 (focusX, 焦点帧) 保持在原来的屏幕位置 */
    private void setZoomFactor(float target, float focusX) {
        target = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, target));
        if (Math.abs(target - mZoomFactor) < 0.0005f) return;
        float oldPxf = pixelsPerFrame();
        float focusFrame = mOffset + focusX / oldPxf;
        mZoomFactor = target;
        float newPxf = pixelsPerFrame();
        if (oldPxf > 0 && newPxf > 0) {
            mOffset = Math.round(focusFrame - focusX / newPxf);
            clampOffset();
        }
        invalidate();
    }

    /** 把屏幕 [x0, x1] 这段区域放大到整屏显示 */
    private void zoomToRegion(int x0, int x1) {
        if (x1 - x0 < 12) return;
        float pxf = pixelsPerFrame();
        float startFrame = mOffset + x0 / pxf;
        float target = mZoomFactor * (float) getMeasuredWidth() / (x1 - x0);
        mZoomFactor = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, target));
        mOffset = Math.round(startFrame);
        clampOffset();
        invalidate();
    }

    /** 每帧占多少像素：level-1 整条铺满一屏为基准，再乘用户缩放倍率 */
    private float pixelsPerFrame() {
        if (mLenByZoomLevel == null || mLenByZoomLevel.length < 2) return 1f;
        int normal = Math.max(1, mLenByZoomLevel[1]);
        return (float) getMeasuredWidth() / normal * mZoomFactor;
    }

    /** 当前倍率下一屏能显示的帧数 */
    private int visibleFrames() {
        return Math.max(1, (int) (getMeasuredWidth() / pixelsPerFrame()));
    }

    /** 偏移量约束在有效范围内：最多滚到"最后一屏"，最小 0 */
    private void clampOffset() {
        if (mLenByZoomLevel == null) return;
        int maxOffset = Math.max(0, maxPos() - visibleFrames());
        if (mOffset > maxOffset) mOffset = maxOffset;
        if (mOffset < 0) mOffset = 0;
    }

    public int maxPos() {
        return mLenByZoomLevel[mZoomLevel];
    }

    public int secondsToFrames(double seconds) {
        return (int) (1.0 * seconds * mSampleRate / mSamplesPerFrame + 0.5);
    }

    public int secondsToPixels(double seconds) {
        double z = mZoomFactorByZoomLevel[mZoomLevel];
        return (int) (z * seconds * mSampleRate / mSamplesPerFrame + 0.5);
    }

    public double pixelsToSeconds(int pixels) {
        double z = mZoomFactorByZoomLevel[0];
        return (mSoundRecord.getNumFramesFloat() * 2 * (double) mSamplesPerFrame / (mSampleRate * z));
    }

    public int millisecsToPixels(int msecs) {
        double z = mZoomFactorByZoomLevel[mZoomLevel];
        return (int) ((msecs * 1.0 * mSampleRate * z) / (1000.0 * mSamplesPerFrame) + 0.5);
    }

    public int pixelsToMillisecs(int pixels) {
        double z = mZoomFactorByZoomLevel[mZoomLevel];
        return (int) (pixels * (1000.0 * mSamplesPerFrame) / (mSampleRate * z) + 0.5);
    }

    public int pixelsToMillisecsTotal() {
        return (int) (mSoundRecord.getNumFramesFloat() * 1000.0 * mSamplesPerFrame / mSampleRate + 0.5);
    }

    public void setParameters(int start, int end, int offset) {
        mSelectionStart = start;
        mSelectionEnd = end;
        mOffset = offset;
    }

    public int getStart() {
        return mSelectionStart;
    }

    public int getEnd() {
        return mSelectionEnd;
    }

    public int getOffset() {
        return mOffset;
    }

    public void setPlayback(int pos) {
        mPlaybackPos = pos;
    }

    /** 波形显示增益（0.1× ~ 3×），与文件卡片的音量滑杆联动 */
    public void setDisplayGain(float gain) {
        mDisplayGain = Math.max(0.1f, Math.min(3f, gain));
        invalidate();
    }

    public void recomputeHeights(float density) {
        mDensity = density;
        mTimeValuePaint.setTextSize((int) (12 * density));
        invalidate();
    }

    // ---------- 交互 ----------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        int x = (int) event.getX();
        if (mRegionSelecting) {
            if (action == MotionEvent.ACTION_MOVE) {
                mRegionEndX = x;
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_UP) {
                    zoomToRegion(Math.min(mRegionStartX, mRegionEndX),
                            Math.max(mRegionStartX, mRegionEndX));
                }
                mRegionSelecting = false;
                invalidate();
            }
            mLastTouchX = -1;
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            mLastTouchX = event.getX();
            mPanRemainder = 0;
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!mScaleDetector.isInProgress() && event.getPointerCount() == 1
                    && mLastTouchX >= 0) {
                // 单指拖动：用浮点 X 位移换算帧偏移，不足一帧的部分留到下次事件，
                // 放大后也不会因为每次位移 <1 帧而完全不动
                float dx = event.getX() - mLastTouchX;
                mLastTouchX = event.getX();
                mPanRemainder -= dx / pixelsPerFrame();
                int whole = (int) mPanRemainder;
                if (whole != 0) {
                    int before = mOffset;
                    mOffset += whole;
                    mPanRemainder -= whole;
                    clampOffset();
                    if (mOffset != before + whole) mPanRemainder = 0; // 到边界了，别攒着回弹
                    invalidate();
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || event.getPointerCount() > 1) {
            mLastTouchX = -1;
            mPanRemainder = 0;
        }
        return true;
    }

    // ---------- 波形数据 ----------

    /** 时间/帧换算用的级别表；绘制已不再依赖分级高度数组 */
    private void computeZoomBase() {
        int numFrames = Math.max(1, mSoundRecord.getNumFrames());
        mNumZoomLevels = 5;
        mLenByZoomLevel = new int[5];
        mZoomFactorByZoomLevel = new double[5];
        mLenByZoomLevel[0] = numFrames * 2;
        mZoomFactorByZoomLevel[0] = 2.0;
        mLenByZoomLevel[1] = numFrames;
        mZoomFactorByZoomLevel[1] = 1.0;
        for (int j = 2; j < 5; j++) {
            mLenByZoomLevel[j] = Math.max(1, mLenByZoomLevel[j - 1] / 2);
            mZoomFactorByZoomLevel[j] = mZoomFactorByZoomLevel[j - 1] / 2.0;
        }
        mZoomLevel = 1;
        mInitialized = true;
    }

    /**
     * 一次性从解码采样建 min/max 包络金字塔：
     * 块大小 32、128、512……每级 4 合 1，直到剩不到 512 块。
     * 绘制时按每像素覆盖的采样数选层级，只读 4 个块就得到该列的峰值范围。
     */
    private void buildEnvelope() {
        mEnvMin.clear();
        mEnvMax.clear();
        mEnvBlocks = new int[0];
        ShortBuffer src = mSoundRecord.getSamples();
        mNumSamples = mSoundRecord.getNumSamples();
        mChannels = Math.max(1, mSoundRecord.getChannels());
        mRawSamples = src != null ? src.duplicate() : null;
        if (mRawSamples == null || mNumSamples <= 0) return;

        final int base = 32;
        int len = (mNumSamples + base - 1) / base;
        short[] min = new short[len];
        short[] max = new short[len];
        int peak = 1;
        for (int b = 0; b < len; b++) {
            int from = b * base;
            int to = Math.min(mNumSamples, from + base);
            int lo = Short.MAX_VALUE;
            int hi = 0;
            for (int s = from; s < to; s++) {
                int v = sampleAbs(s);
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            min[b] = (short) lo;
            max[b] = (short) hi;
            if (hi > peak) peak = hi;
        }
        mEnvMaxAbs = peak;
        mEnvMin.add(min);
        mEnvMax.add(max);

        ArrayList<Integer> blocks = new ArrayList<>();
        blocks.add(base);
        while (min.length > 512) {
            int prevLen = min.length;
            int nextLen = (prevLen + 3) / 4;
            short[] nextMin = new short[nextLen];
            short[] nextMax = new short[nextLen];
            for (int i = 0; i < nextLen; i++) {
                int lo = Short.MAX_VALUE;
                int hi = 0;
                for (int j = i * 4; j < Math.min(prevLen, i * 4 + 4); j++) {
                    if (min[j] < lo) lo = min[j];
                    if (max[j] > hi) hi = max[j];
                }
                nextMin[i] = (short) lo;
                nextMax[i] = (short) hi;
            }
            min = nextMin;
            max = nextMax;
            mEnvMin.add(min);
            mEnvMax.add(max);
            blocks.add(blocks.get(blocks.size() - 1) * 4);
        }
        mEnvBlocks = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            mEnvBlocks[i] = blocks.get(i);
        }
    }

    /** 第 s 个采样（单声道）的绝对振幅；Short.MIN_VALUE 取绝对值要特判 */
    private int sampleAbs(int s) {
        int v = mRawSamples.get(s * mChannels) & 0xFFFF;
        if (v >= 0x8000) v = 0x10000 - v;
        return v;
    }

    /** 查询 [s0, s1) 采样的振幅范围：lo = 最小绝对值，hi = 峰值 */
    private void envelopeOf(int s0, int s1, int[] out) {
        int span = s1 - s0;
        int lo = Short.MAX_VALUE;
        int hi = 0;
        if (span <= 64 && mRawSamples != null) {
            for (int s = s0; s < s1; s++) {
                int v = sampleAbs(s);
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
        } else if (mEnvBlocks.length > 0) {
            int level = 0;
            while (level + 1 < mEnvBlocks.length && mEnvBlocks[level + 1] <= span / 4) {
                level++;
            }
            int block = mEnvBlocks[level];
            short[] mins = mEnvMin.get(level);
            short[] maxs = mEnvMax.get(level);
            int b0 = s0 / block;
            int b1 = Math.min(mins.length - 1, (s1 - 1) / block);
            for (int b = b0; b <= b1; b++) {
                if (mins[b] < lo) lo = mins[b];
                if (maxs[b] > hi) hi = maxs[b];
            }
        }
        out[0] = lo == Short.MAX_VALUE ? 0 : lo;
        out[1] = hi;
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int measuredWidth = getMeasuredWidth();
        int measuredHeight = getMeasuredHeight();
        int height = measuredHeight - mLineOffset;
        float ctr = measuredHeight / 2f;

        canvas.drawARGB(255, 239, 239, 239);
        canvas.drawLine(0, height * 0.5f + mLineOffset / 2, measuredWidth,
                height * 0.5f + mLineOffset / 2, mCenterLinePaint);
        canvas.drawLine(0, mLineOffset / 2, measuredWidth, mLineOffset / 2, mBorderLinePaint);
        canvas.drawLine(0, measuredHeight - mLineOffset / 2 - 1, measuredWidth,
                measuredHeight - mLineOffset / 2 - 1, mBorderLinePaint);

        if (state == 1) {
            mSoundRecord = null;
            state = 0;
        }
        if (mSoundRecord == null) return;

        int need = measuredWidth * 8;
        if (mLines.length < need) {
            mLines = new float[need];
            mSelectedLines = new float[need];
        }
        float pxf = pixelsPerFrame();
        int totalFrames = maxPos();
        int samples = Math.min(mNumSamples, mSoundRecord.getNumSamples());
        float scale = (height / 2f - 2f) / mEnvMaxAbs * mDisplayGain;
        int[] env = new int[2];
        int n = 0;
        int ns = 0;
        for (int x = 0; x < measuredWidth; x++) {
            double frameStart = mOffset + x / pxf;
            if (frameStart >= totalFrames) break;
            int frame = (int) frameStart;
            int s0 = (int) (frameStart * mSamplesPerFrame);
            int s1 = Math.min(samples, (int) Math.ceil((frameStart + 1 / pxf) * mSamplesPerFrame));
            if (s0 >= s1) continue;
            envelopeOf(s0, s1, env);
            int maxH = (int) (height / 2f - 1);
            int hi = Math.min(maxH, (int) (env[1] * scale));
            int lo = Math.min(hi, (int) (env[0] * scale));
            if (hi <= 0) continue;

            boolean selected = frame >= mSelectionStart && frame < mSelectionEnd;
            float[] dst = selected ? mSelectedLines : mLines;
            int idx = selected ? ns : n;
            // 上、下各一段：保留该像素列内的峰值范围，缩小也不丢尖峰
            dst[idx] = x; dst[idx + 1] = ctr - hi; dst[idx + 2] = x; dst[idx + 3] = ctr - lo;
            dst[idx + 4] = x; dst[idx + 5] = ctr + lo; dst[idx + 6] = x; dst[idx + 7] = ctr + hi;
            if (selected) ns = idx + 8; else n = idx + 8;

        }
        if (n > 0) canvas.drawLines(mLines, 0, n, mUnselectedLinePaint);
        if (ns > 0) canvas.drawLines(mSelectedLines, 0, ns, mSelectedLinePaint);

        // 播放指示线：按帧→像素的反变换画一次。放大后一个帧可能占多个像素列，
        // 放在循环里会画出一排相邻竖线（看起来变粗）；独立绘制始终 1px，
        // 并且随 mOffset / pxf 一起移动，缩放/平移后仍对齐同一时间点。
        if (mPlaybackPos >= 0 && mPlayFinish != 1 && pxf > 0) {
            float playX = (mPlaybackPos - mOffset) * pxf;
            if (playX >= 0 && playX <= measuredWidth) {
                canvas.drawLine(playX, 0, playX, measuredHeight, mPlayLinePaint);
                canvas.drawCircle(playX, mLineOffset / 4, mLineOffset / 4, circlePaint);
                canvas.drawCircle(playX, measuredHeight - mLineOffset / 4,
                        mLineOffset / 4, circlePaint);
            }
        }

        if (mRegionSelecting) {
            int x0 = Math.min(mRegionStartX, mRegionEndX);
            int x1 = Math.max(mRegionStartX, mRegionEndX);
            canvas.drawRect(x0, mLineOffset / 2f, x1, measuredHeight - mLineOffset / 2f,
                    mRegionFillPaint);
            canvas.drawLine(x0, 0, x0, measuredHeight, mRegionBorderPaint);
            canvas.drawLine(x1, 0, x1, measuredHeight, mRegionBorderPaint);
        }
    }
}
