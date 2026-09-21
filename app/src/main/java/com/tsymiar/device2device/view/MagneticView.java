package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 磁场强度：窄卡片排版——大数字居中 + 小字说明 + 底部 0~100µT 刻度条。
 * 无磁力计时给占位文案。
 */
public class MagneticView extends View {

    private static final float MAX_FIELD = 100f;   // 刻度条量程（µT）

    private final Paint mPanelPaint;
    private final Paint mPanelStroke;
    private final Paint mValuePaint;
    private final Paint mSubPaint;
    private final Paint mSubCenterPaint;
    private final Paint mTrackPaint;
    private final Paint mFillPaint;
    private final RectF mRect = new RectF();
    private final float mDensity;

    private float mField = 0f;
    private boolean mAvailable = true;

    public MagneticView(Context context) {
        this(context, null);
    }

    public MagneticView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MagneticView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPanelPaint = fill(0xFFFFFFFF);
        mPanelStroke = stroke(0xFFE0E0E0, dp(1f));
        mValuePaint = centerTextPaint(dp(17f), 0xFF5E35B1, true);
        mSubPaint = centerTextPaint(dp(10f), 0xFF90A4AE, false);
        mSubCenterPaint = centerTextPaint(dp(10f), 0xFF90A4AE, false);
        mTrackPaint = fill(0xFFE0E0E0);
        mFillPaint = fill(0xFF5E35B1);
    }

    /** 总磁场强度（µT） */
    public void setField(float microTesla) {
        mField = microTesla;
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
            drawFittedText(canvas, "无磁力计", w / 2f, h / 2f + dp(4), mSubCenterPaint, w - pad * 2 - dp(8));
            return;
        }

        drawFittedText(canvas, String.format("%.1f µT", mField), w / 2f, h * 0.46f, mValuePaint, w - pad * 2 - dp(8));
        drawFittedText(canvas, "三轴合成", w / 2f, h * 0.72f, mSubPaint, w - pad * 2 - dp(8));

        float barH = dp(5);
        float barLeft = pad + dp(8);
        float barRight = w - pad - dp(8);
        float barTop = h - pad - dp(10);
        mRect.set(barLeft, barTop, barRight, barTop + barH);
        canvas.drawRoundRect(mRect, barH / 2f, barH / 2f, mTrackPaint);
        float ratio = Math.max(0f, Math.min(1f, mField / MAX_FIELD));
        if (ratio > 0f) {
            mRect.set(barLeft, barTop, barLeft + (barRight - barLeft) * ratio, barTop + barH);
            canvas.drawRoundRect(mRect, barH / 2f, barH / 2f, mFillPaint);
        }
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
