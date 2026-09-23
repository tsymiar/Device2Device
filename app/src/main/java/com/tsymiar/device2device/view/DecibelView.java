package com.tsymiar.device2device.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 录音时的实时分贝图：上方是当前分贝数字，下方是滚动的历史曲线，虚线标出峰值。
 *
 * 输入是标准声压级 dB SPL（安静 ≈ 30 dB，正常说话 60 dB 上下，满刻度 94 dB），
 * 30 dB 贴底、94 dB 到顶，越响的部分按绿 → 黄 → 红上色。
 *
 * 历史区有三种画法（柱状 / 折线 / 面积），点一下卡片就循环切换。
 *
 * 外观跟页面里其它卡片保持一致：直角（不画圆角）、背景/文字取主题色，
 * 所以深色/浅色主题下都不会突兀。峰值是「本次录音」的全程峰值，只在 reset() 时清零。
 */
public class DecibelView extends View {

    private static final int MAX_BARS = 72;
    /** 显示下限：30 dB ≈ 安静的室内，低于它就贴着底边画，不然安静时整片是空的 */
    private static final float MIN_DB = 30f;
    /** 显示上限：0 dBFS（满刻度）按 1 Pa = 94 dB SPL 换算 */
    private static final float MAX_DB = 94f;
    private static final float WARN_DB = 70f;
    private static final float HOT_DB = 85f;

    /** 实时曲线的绘制形态：点一下卡片循环切换 */
    public enum Style {
        BAR("柱状"), LINE("折线"), AREA("面积");
        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    private final Paint mPanelPaint;
    private final Paint mPanelStroke;
    private final Paint mTitlePaint;
    private final Paint mValuePaint;
    private final Paint mBarPaint;
    private final Paint mLinePaint;
    private final Paint mAreaPaint;
    private final Paint mPeakPaint;
    private final Paint mPeakTextPaint;
    private final RectF mRect = new RectF();
    private final Path mPath = new Path();
    private final float mDensity;

    private Style mStyle = Style.BAR;
    private final float[] mHistory = new float[MAX_BARS];
    private int mCount;
    private float mCurrent = MIN_DB;
    private float mPeak = MIN_DB;

    public DecibelView(Context context) {
        this(context, null);
    }

    public DecibelView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DecibelView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        // 颜色全从主题里取：页面是深色主题时卡片跟着变深，不用在这里写死白底
        int bg = resolveColor(context, android.R.attr.colorBackground, 0xFFFFFFFF);
        int titleColor = resolveColor(context, android.R.attr.textColorSecondary, 0xFF90A4AE);
        int valueColor = resolveColor(context, android.R.attr.textColorPrimary, 0xFF37474F);
        int strokeColor = (titleColor & 0x00FFFFFF) | 0x55000000;

        mPanelPaint = fill(bg);
        mPanelStroke = stroke(strokeColor, dp(1));
        mTitlePaint = paint(dp(11f), titleColor, false);
        mValuePaint = paint(dp(20f), valueColor, true);
        mBarPaint = fill(0xFF43A047);
        mLinePaint = stroke(0xFF43A047, dp(1.6f));
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mAreaPaint = fill(0x5543A047);
        mPeakPaint = stroke(0xFFE53935, dp(1));
        mPeakPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
        mPeakTextPaint = paint(dp(10f), 0xFFE53935, false);
        // 点卡片切图形：柱状 → 折线 → 面积
        setOnClickListener(v -> cycleStyle());
    }

    /** 循环切换绘制形态（点卡片用） */
    public void cycleStyle() {
        Style[] all = Style.values();
        mStyle = all[(mStyle.ordinal() + 1) % all.length];
        invalidate();
    }

    public Style getStyle() {
        return mStyle;
    }

    public void setStyle(Style style) {
        if (style == null) return;
        mStyle = style;
        invalidate();
    }

    /** 当前分贝值（标准声压级 dB SPL：安静 ≈ 30 dB，正常说话 60 dB 上下） */
    public void setLevel(double dbSpl) {
        mCurrent = (float) dbSpl;
        System.arraycopy(mHistory, 1, mHistory, 0, MAX_BARS - 1);
        mHistory[MAX_BARS - 1] = mCurrent;
        if (mCount < MAX_BARS) mCount++;
        // 全程峰值：整个过程只涨不落，重新开始录音时由 reset() 清零
        if (mCurrent > mPeak) {
            mPeak = mCurrent;
        }
        invalidate();
    }

    /** 开一次新录音（或停止录音）时清干净：数字回到空闲态，峰值从这一轮重新算 */
    public void reset() {
        mCurrent = MIN_DB;
        mPeak = MIN_DB;
        mCount = 0;
        for (int i = 0; i < mHistory.length; i++) {
            mHistory[i] = MIN_DB;
        }
        invalidate();
    }

    /** 全程峰值（dB SPL），没开始录音时是显示下限 30 dB */
    public float getPeak() {
        return mPeak;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        // 卡片铺满自己这块区域，直角：和页面里 MaterialCardView 那张结果卡片一个路子
        mRect.set(0, 0, w, h);
        canvas.drawRect(mRect, mPanelPaint);
        float inset = dp(1) / 2f;                 // 描边压在边界上会被裁掉一半，往里收半个线宽
        mRect.set(inset, inset, w - inset, h - inset);
        canvas.drawRect(mRect, mPanelStroke);

        float pad = dp(12);
        float left = pad;
        float right = w - pad;
        mTitlePaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("实时分贝 (dB SPL) · " + mStyle.label + "（点击切换）",
                left, pad + dp(12), mTitlePaint);

        mValuePaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(mCurrent <= MIN_DB + 0.5f ? "静音" : String.format("%.0f dB", mCurrent),
                right, pad + dp(22), mValuePaint);

        float top = pad + dp(30);
        float bottom = h - pad - dp(6);
        float areaH = Math.max(dp(10), bottom - top);
        float slot = (right - left) / MAX_BARS;

        // 同一种历史数据、三种画法：右边是最新的一点
        switch (mStyle) {
            case LINE:
                drawHistoryLine(canvas, left, bottom, slot, areaH);
                break;
            case AREA:
                drawHistoryArea(canvas, left, bottom, slot, areaH);
                break;
            case BAR:
            default:
                drawHistoryBars(canvas, left, bottom, slot, areaH);
                break;
        }

        // 全程峰值线：一直在，不再 1.5 秒就过期
        if (mPeak > MIN_DB) {
            float y = bottom - areaH * ratioOf(mPeak);
            canvas.drawLine(left, y, right, y, mPeakPaint);
            mPeakTextPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(String.format("全程峰值 %.0f dB", mPeak),
                    left, Math.max(pad + dp(24), y - dp(4)), mPeakTextPaint);
        }
    }

    /** 柱状：一根一个采样点，按当前电平上色 */
    private void drawHistoryBars(Canvas canvas, float left, float bottom, float slot, float areaH) {
        float barW = Math.max(dp(1.5f), slot * 0.62f);
        for (int i = 0; i < mCount; i++) {
            float db = mHistory[i];
            float barH = areaH * ratioOf(db);
            mBarPaint.setColor(barColor(db));
            float cx = left + slot * (i + 0.5f);
            canvas.drawRect(cx - barW / 2f, bottom - barH, cx + barW / 2f, bottom, mBarPaint);
        }
    }

    /** 折线：把各点连起来，长时间趋势比柱状更好读 */
    private void drawHistoryLine(Canvas canvas, float left, float bottom, float slot, float areaH) {
        buildHistoryPath(left, bottom, slot, areaH, false);
        mLinePaint.setColor(barColor(mCurrent));
        canvas.drawPath(mPath, mLinePaint);
    }

    /** 面积：折线下方填一层半透明同色，视觉上更像声压的「起伏」 */
    private void drawHistoryArea(Canvas canvas, float left, float bottom, float slot, float areaH) {
        buildHistoryPath(left, bottom, slot, areaH, true);
        mAreaPaint.setColor(areaColor(mCurrent));
        canvas.drawPath(mPath, mAreaPaint);
        drawHistoryLine(canvas, left, bottom, slot, areaH);
    }

    private void buildHistoryPath(float left, float bottom, float slot, float areaH, boolean closed) {
        mPath.reset();
        float firstX = left + slot * 0.5f;
        if (closed) mPath.moveTo(firstX, bottom);
        for (int i = 0; i < mCount; i++) {
            float x = left + slot * (i + 0.5f);
            float y = bottom - areaH * ratioOf(mHistory[i]);
            if (i == 0 && !closed) mPath.moveTo(x, y);
            else mPath.lineTo(x, y);
        }
        if (closed) {
            mPath.lineTo(left + slot * (mCount - 0.5f), bottom);
            mPath.close();
        }
    }

    /** 电平 -> 高度比例：MIN_DB 贴底，MAX_DB 到顶 */
    private static float ratioOf(float db) {
        float ratio = (db - MIN_DB) / (MAX_DB - MIN_DB);
        return Math.max(0.02f, Math.min(1f, ratio));
    }

    private static int areaColor(float db) {
        return (barColor(db) & 0x00FFFFFF) | 0x55000000;
    }

    private static int barColor(float db) {
        if (db >= HOT_DB) return 0xFFE53935;
        if (db >= WARN_DB) return 0xFFFBC02D;
        return 0xFF43A047;
    }

    /** 取主题里的颜色，取不到就用 fallback */
    private static int resolveColor(Context context, int attr, int fallback) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    private float dp(float value) {
        return value * mDensity;
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private static Paint stroke(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        return p;
    }

    private static Paint paint(float size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setFakeBoldText(bold);
        p.setTextAlign(Paint.Align.LEFT);
        return p;
    }
}
