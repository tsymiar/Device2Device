package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 水平仪：圆形气泡水准器。
 *
 * 气泡位置由倾角算：气泡朝「抬高的一侧」跑（与重力反向），
 * 圆心附近 ±1° 内判定为水平，下方给出 X / Y 两个方向的倾角。
 */
public class BubbleLevelView extends View {

    /** 气泡位移的灵敏度：sin(倾角) 乘上它再截到边界，约 40° 就顶到边 */
    private static final float SENSITIVITY = 1.6f;
    private static final float LEVEL_TOLERANCE = 1f;   // 判定「已水平」的角度容差

    private final Paint mPlatePaint;
    private final Paint mRingPaint;
    private final Paint mGuidePaint;
    private final Paint mTargetPaint;
    private final Paint mBubblePaint;
    private final Paint mBubbleEdge;
    private final Paint mValuePaint;
    private final Paint mHintPaint;
    private final float mDensity;

    private float mTiltX = 0f;   // 左右倾角（度）：右抬为正
    private float mTiltY = 0f;   // 前后倾角（度）：上抬为正
    private boolean mAvailable = true;

    public BubbleLevelView(Context context) {
        this(context, null);
    }

    public BubbleLevelView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleLevelView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPlatePaint = fill(0xFFFAFAFA);
        mRingPaint = stroke(0xFF90A4AE, dp(2f));
        mGuidePaint = stroke(0xFFCFD8DC, dp(1f));
        mTargetPaint = stroke(0xFF00897B, dp(1.5f));
        mBubblePaint = fill(0xCC1E88E5);
        mBubbleEdge = stroke(0xFF0D47A1, dp(1.5f));
        mValuePaint = centerText(dp(11f), 0xFF37474F, true);
        mHintPaint = centerText(dp(10f), 0xFF90A4AE, false);
    }

    /** 两个方向的倾角（度）：x=左右（右抬为正），y=前后（上抬为正） */
    public void setTilt(float tiltX, float tiltY) {
        mTiltX = tiltX;
        mTiltY = tiltY;
        invalidate();
    }

    /** 设备没有加速度计时置 false */
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
            drawFittedText(canvas, "无加速度计，无法测水平", w / 2f, h / 2f, mHintPaint, w - dp(12));
            return;
        }
        float bottom = dp(28);
        float cx = w / 2f;
        float cy = (h - bottom) / 2f;
        float r = Math.min(w, h - bottom) / 2f - dp(6);

        canvas.drawCircle(cx, cy, r, mPlatePaint);
        canvas.drawCircle(cx, cy, r, mRingPaint);
        canvas.drawCircle(cx, cy, r * 0.45f, mGuidePaint);
        canvas.drawLine(cx - r, cy, cx + r, cy, mGuidePaint);
        canvas.drawLine(cx, cy - r, cx, cy + r, mGuidePaint);
        canvas.drawCircle(cx, cy, r * 0.12f, mTargetPaint);

        // 气泡跑向抬高的一侧：sin(倾角) 决定位移，再截到圆盘内
        float maxOffset = r - dp(14);
        float offsetX = clamp((float) Math.sin(Math.toRadians(mTiltX)) * SENSITIVITY, -1f, 1f) * maxOffset;
        float offsetY = -clamp((float) Math.sin(Math.toRadians(mTiltY)) * SENSITIVITY, -1f, 1f) * maxOffset;
        float bubbleR = Math.max(dp(10), r * 0.16f);
        canvas.drawCircle(cx + offsetX, cy + offsetY, bubbleR, mBubblePaint);
        canvas.drawCircle(cx + offsetX, cy + offsetY, bubbleR, mBubbleEdge);

        boolean leveled = Math.abs(mTiltX) <= LEVEL_TOLERANCE && Math.abs(mTiltY) <= LEVEL_TOLERANCE;
        String value = leveled ? "已水平" : String.format("左右 %+.1f\u00b0  前后 %+.1f\u00b0", mTiltX, mTiltY);
        drawFittedText(canvas, value, cx, cy + r + dp(20), mValuePaint, w - dp(12));
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
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

    private static Paint centerText(float size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(bold);
        return p;
    }
}
