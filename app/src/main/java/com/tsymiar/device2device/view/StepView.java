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
 *
 * 显示的是「今日步数」：系统 TYPE_STEP_COUNTER 给的是自开机以来的累计值且每天不清零，
 * 换算成今日步数由调用方（{@code GraphActivity}）做，这里只负责画。
 */
public class StepView extends View {

    private final Paint mPanelPaint;
    private final Paint mPanelStroke;
    private final Paint mValuePaint;
    private final Paint mSubPaint;
    private final Paint mHintPaint;
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
        mHintPaint = centerTextPaint(dp(8f), 0xFFB0BEC5, false);
        mSubCenterPaint = centerTextPaint(dp(10f), 0xFF90A4AE, false);
    }

    /** 今日步数（调用方已按当天基线换算过） */
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

        float limit = w - pad * 2 - dp(8);
        drawFittedText(canvas, String.valueOf(mSteps), w / 2f, h * 0.44f, mValuePaint, limit);
        drawFittedText(canvas, "今日步数", w / 2f, h * 0.68f, mSubPaint, limit);
        // 再补一行更小的说明：系统计步器是累计值，这里的「今日」是怎么算出来的要交代清楚
        drawFittedText(canvas, "每日 0 点清零", w / 2f, h * 0.87f, mHintPaint, limit);
    }

    /**
     * 按可用宽度收缩字号：文字宽度超了就一档档减小，
     * 保证数值永远画在卡片里面，不会被裁掉。
     */
    private void drawFittedText(Canvas canvas, String text, float x, float y, Paint paint, float maxWidth) {
        float origin = paint.getTextSize();
        float size = origin;
        while (size > dp(7f) && paint.measureText(text) > maxWidth) {
            size -= dp(0.5f);
            paint.setTextSize(size);
        }
        canvas.drawText(text, x, y, paint);
        // 必须还原：paint 是复用的，不还原的话字号会被一次狭窄布局永久压小，
        // 后续几行也会被带着一起变小
        paint.setTextSize(origin);
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
