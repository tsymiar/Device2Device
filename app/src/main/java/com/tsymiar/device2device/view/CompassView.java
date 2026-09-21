package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 指南针（方向）：外圈刻度随方位角反向旋转，指针始终指向磁北；
 * 下方给出「方位 + 角度」，磁力计精度偏低时顺带提示校准。
 *
 * setAzimuth() 收的是 SensorManager.getOrientation() 的 azimuth：正北为 0，顺时针增大。
 */
public class CompassView extends View {

    private static final String[] DIRECTIONS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    private final Paint mDialFill;
    private final Paint mDialRing;
    private final Paint mTick;
    private final Paint mTickMajor;
    private final Paint mCardinal;
    private final Paint mNorthPaint;
    private final Paint mSouthPaint;
    private final Paint mHubPaint;
    private final Paint mValuePaint;
    private final Paint mHintPaint;
    private final Path mNeedle = new Path();
    private final float mDensity;

    private float mAzimuth = 0f;   // 0=正北，顺时针为正
    private int mAccuracy = 3;     // 磁力计精度：0 不可靠 / 1 低 / 2 中 / 3 高
    private boolean mAvailable = true;

    public CompassView(Context context) {
        this(context, null);
    }

    public CompassView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CompassView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        mDialFill = fill(0xFFFAFAFA);
        mDialRing = stroke(0xFFB0BEC5, dp(1.5f));
        mTick = stroke(0xFFCFD8DC, dp(1f));
        mTickMajor = stroke(0xFF90A4AE, dp(2f));
        mCardinal = textPaint(dp(11f), 0xFF546E7A, true);
        mNorthPaint = fill(0xFFE53935);
        mSouthPaint = fill(0xFFB0BEC5);
        mHubPaint = fill(0xFF37474F);
        mValuePaint = textPaint(dp(17f), 0xFF37474F, true);
        mHintPaint = textPaint(dp(10f), 0xFF90A4AE, false);
    }

    /** 方位角（度）：0=正北，顺时针为正 */
    public void setAzimuth(float azimuth) {
        mAzimuth = azimuth;
        invalidate();
    }

    /** 磁力计上报的精度（SensorEvent.accuracy） */
    public void setAccuracy(int accuracy) {
        mAccuracy = accuracy;
    }

    /** 设备没有磁力计 / 加速度计时置 false，画占位文案 */
    public void setAvailable(boolean available) {
        mAvailable = available;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (!mAvailable) {
            drawFittedText(canvas, "无磁力计，无法测方向", w / 2f, h / 2f, mHintPaint, w - dp(12));
            return;
        }
        float bottom = dp(30);                       // 底部留给「方位 + 角度」
        float cx = w / 2f;
        float cy = (h - bottom) / 2f;
        float r = Math.min(w, h - bottom) / 2f - dp(6);

        canvas.drawCircle(cx, cy, r, mDialFill);
        canvas.drawCircle(cx, cy, r, mDialRing);

        // 盘面与指针一起反向转：方位角增大＝手机右转，磁北相对手机左移
        canvas.save();
        canvas.rotate(-mAzimuth, cx, cy);
        drawDial(canvas, cx, cy, r);
        drawNeedle(canvas, cx, cy, r);
        canvas.restore();
        canvas.drawCircle(cx, cy, dp(5), mHubPaint);

        float base = cy + r + dp(18);
        drawFittedText(canvas, directionText() + "  " + Math.round(norm360(mAzimuth)) + "\u00b0", cx, base, mValuePaint, w - dp(12));
        if (mAccuracy <= 1) {
            drawFittedText(canvas, "精度低，画 8 字校准", cx, base + dp(13), mHintPaint, w - dp(12));
        }
    }

    /** 刻度 + N/E/S/W：每 15° 一格，90° 的整数倍画长刻度 */
    private void drawDial(Canvas canvas, float cx, float cy, float r) {
        for (int i = 0; i < 24; i++) {
            boolean major = i % 6 == 0;
            canvas.save();
            canvas.rotate(i * 15f, cx, cy);
            canvas.drawLine(cx, cy - r + dp(4), cx, cy - r + dp(major ? 13 : 9),
                    major ? mTickMajor : mTick);
            canvas.restore();
        }
        String[] marks = {"N", "E", "S", "W"};
        for (int i = 0; i < marks.length; i++) {
            canvas.save();
            canvas.rotate(i * 90f, cx, cy);
            canvas.drawText(marks[i], cx, cy - r + dp(26), mCardinal);
            canvas.restore();
        }
    }

    /** 红针指北，灰针指南 */
    private void drawNeedle(Canvas canvas, float cx, float cy, float r) {
        float len = r * 0.62f;
        float half = dp(7);
        mNeedle.reset();
        mNeedle.moveTo(cx, cy - len);
        mNeedle.lineTo(cx - half, cy + dp(8));
        mNeedle.lineTo(cx + half, cy + dp(8));
        mNeedle.close();
        canvas.drawPath(mNeedle, mNorthPaint);

        mNeedle.reset();
        mNeedle.moveTo(cx, cy + len * 0.75f);
        mNeedle.lineTo(cx - half, cy - dp(8));
        mNeedle.lineTo(cx + half, cy - dp(8));
        mNeedle.close();
        canvas.drawPath(mNeedle, mSouthPaint);
    }

    private String directionText() {
        int index = Math.round(norm360(mAzimuth) / 45f) % 8;
        return DIRECTIONS[index];
    }

    private static float norm360(float angle) {
        float a = angle % 360f;
        return a < 0 ? a + 360f : a;
    }

    /**
     * 按可用宽度收缩字号：文字宽度超了就一档档减小，保证数值不画出卡片。
     */
    private void drawFittedText(Canvas canvas, String text, float x, float y, Paint paint, float maxWidth) {
        float size = paint.getTextSize();
        while (size > dp(9f) && paint.measureText(text) > maxWidth) {
            size -= dp(0.5f);
            paint.setTextSize(size);
        }
        canvas.drawText(text, x, y, paint);
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

    private static Paint textPaint(float size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(bold);
        return p;
    }
}
