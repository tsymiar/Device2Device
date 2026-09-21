package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 步数：窄卡片排版——大数字居中 + 小字说明。无计步传感器时给占位文案。
 */
public class StepView extends View {

    private final Paint mPanelPaint;
    private final Paint mPanelStroke;
    private final Paint mValuePaint;
    private final Paint mSubPaint;
    private final Paint mSubCenterPaint;
    private final RectF mRect = new RectF();
    private final float mDensity;

    private long mSteps = 0L;
    private boolean mAvailable = true;

    public StepView(Context context) {
        this(context, null);
    }

    public StepView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StepView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPanelPaint = fill(0xFFFFFFFF);
        mPanelStroke = stroke(0xFFE0E0E0, dp(1f));
        mValuePaint = centerTextPaint(dp(17f), 0xFF039BE5, true);
        mSubPaint = centerTextPaint(dp(10f), 0xFF90A4AE, false);
        mSubCenterPaint = centerTextPaint(dp(10f), 0xFF90A4AE, false);
    }

    /** 累计步数 */
    public void setSteps(long steps) {
        mSteps = steps;
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

        if (!mAvailable) {
            drawFittedText(canvas, "无计步传感器", w / 2f, h / 2f + dp(4), mSubCenterPaint, w - pad * 2 - dp(8));
            return;
        }

        drawFittedText(canvas, String.valueOf(mSteps), w / 2f, h * 0.46f, mValuePaint, w - pad * 2 - dp(8));
        drawFittedText(canvas, "系统计步", w / 2f, h * 0.72f, mSubPaint, w - pad * 2 - dp(8));
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
