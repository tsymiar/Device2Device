package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 距离（ proximity 传感器）：窄卡片排版——数值居中 + 小字说明。
 * 多数设备的 proximity 只报 0/最大值两档，所以同时给出「贴近/远离」提示。
 */
public class ProximityView extends View {

    private final Paint mPanelPaint;
    private final Paint mPanelStroke;
    private final Paint mValuePaint;
    private final Paint mSubPaint;
    private final Paint mSubCenterPaint;
    private final RectF mRect = new RectF();
    private final float mDensity;

    private float mDistance = -1f;
    private boolean mAvailable = true;

    public ProximityView(Context context) {
        this(context, null);
    }

    public ProximityView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ProximityView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPanelPaint = fill(0xFFFFFFFF);
        mPanelStroke = stroke(0xFFE0E0E0, dp(1f));
        mValuePaint = centerTextPaint(dp(17f), 0xFFF4511E, true);
        mSubPaint = centerTextPaint(dp(10f), 0xFF90A4AE, false);
        mSubCenterPaint = centerTextPaint(dp(10f), 0xFF90A4AE, false);
    }

    /** 距离（cm），proximity 传感器原始值 */
    public void setDistance(float cm) {
        mDistance = cm;
        invalidate();
    }

    public void setAvailable(boolean available) {
        mAvailable = available;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float pad = dp(6);
        mRect.set(pad, pad, w - pad, h - pad);
        canvas.drawRoundRect(mRect, dp(8), dp(8), mPanelPaint);
        canvas.drawRoundRect(mRect, dp(8), dp(8), mPanelStroke);

        float maxW = w - pad * 2 - dp(8);
        if (!mAvailable) {
            drawFittedText(canvas, "无距离传感器", w / 2f, h / 2f + dp(4), mSubCenterPaint, maxW);
            return;
        }

        if (mDistance < 0f) {
            canvas.drawText("--", w / 2f, h * 0.46f, mValuePaint);
            drawFittedText(canvas, "等待读数", w / 2f, h * 0.72f, mSubPaint, maxW);
            return;
        }

        drawFittedText(canvas, String.format("%.1f cm", mDistance), w / 2f, h * 0.46f, mValuePaint, maxW);
        drawFittedText(canvas, mDistance <= 1f ? "贴近" : "距离传感器", w / 2f, h * 0.72f, mSubPaint, maxW);
    }

    /**
     * 按可用宽度收缩字号：文字宽度超了就一档档减小，
     * 保证数值永远画在卡片里面，不会被裁掉。
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

    private static Paint centerTextPaint(float size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(bold);
        return p;
    }
}
