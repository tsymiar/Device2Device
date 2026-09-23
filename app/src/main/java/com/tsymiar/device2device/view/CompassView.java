package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 指南针（方向）：外圈刻度随方位角反向旋转，指针始终指向磁北；
 * 下方给出「方位 + 角度」，磁力计精度偏低时顺带提示校准。
 *
 * 放大到整屏（setZoomed(true)）后多给两样东西：轮盘上每 5° 一格并标出度数，
 * 圆盘上方空白区显示经纬度。
 *
 * setAzimuth() 收的是 SensorManager.getOrientation() 的 azimuth：正北为 0，顺时针增大。
 */
public class CompassView extends View {

    private static final String[] DIRECTIONS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    private final Paint mDialFill;
    private final Paint mDialRing;
    private final Paint mTick;
    private final Paint mTickMinor;
    private final Paint mTickMajor;
    private final Paint mCardinal;
    private final Paint mDegreePaint;
    private final Paint mCoordPaint;
    private final Paint mMarkerPaint;
    private final Paint mNorthPaint;
    private final Paint mSouthPaint;
    private final Paint mHubPaint;
    private final Paint mValuePaint;
    private final Paint mHintPaint;
    private final Path mNeedle = new Path();
    private final Path mMarker = new Path();
    private final float mDensity;

    private float mAzimuth = 0f;   // 0=正北，顺时针为正
    private int mAccuracy = 3;     // 磁力计精度：0 不可靠 / 1 低 / 2 中 / 3 高
    private boolean mAvailable = true;
    private boolean mZoomed;       // 被搬到整屏容器：多画细刻度 / 度数 / 经纬度
    private boolean mHasLocation;
    private double mLat;
    private double mLon;

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
        mTickMinor = stroke(0xFFE3E8EA, dp(1f));
        mTickMajor = stroke(0xFF90A4AE, dp(2f));
        mCardinal = textPaint(dp(11f), 0xFF546E7A, true);
        mDegreePaint = textPaint(dp(11f), 0xFF90A4AE, false);
        mCoordPaint = textPaint(dp(22f), 0xFF546E7A, false);   // 跟放大后的方位文字差不多大
        mMarkerPaint = fill(0xFF1E88E5);
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

    /** 放大到整屏时置 true：轮盘加细刻度与度数，上方显示经纬度 */
    public void setZoomed(boolean zoomed) {
        if (mZoomed == zoomed) return;
        mZoomed = zoomed;
        invalidate();
    }

    /** 经纬度（WGS84）：放大后显示在轮盘上方 */
    public void setLocation(double latitude, double longitude) {
        mLat = latitude;
        mLon = longitude;
        mHasLocation = true;
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
        float top = mZoomed ? dp(46) : 0f;           // 放大后上方留给经纬度
        float cx = w / 2f;
        float cy = top + (h - top - bottom) / 2f;
        float r = Math.min(w, h - top - bottom) / 2f - dp(6);

        if (mZoomed) {
            // 经纬度放在 view 顶端到倒三角之间（差不多正中），倒三角再指着盘顶＝手机正前方
            float dialTop = cy - r;
            float markerTop = dialTop - dp(9);          // 倒三角的顶边
            Paint.FontMetrics fm = mCoordPaint.getFontMetrics();
            float coordBase = Math.max(markerTop / 2f - (fm.ascent + fm.descent) / 2f,
                    -fm.ascent + dp(2));
            drawFittedText(canvas, locationText(), cx, coordBase, mCoordPaint, w - dp(16));
            drawHeadingMarker(canvas, cx, dialTop - dp(2));
        }

        canvas.drawCircle(cx, cy, r, mDialFill);
        canvas.drawCircle(cx, cy, r, mDialRing);

        // 放大后盘面里的字和指针跟着放大，不然整屏看过去中间一大片留白
        mCardinal.setTextSize(dp(mZoomed ? 18f : 11f));
        mDegreePaint.setTextSize(dp(mZoomed ? 15f : 11f));

        // 盘面与指针一起反向转：方位角增大＝手机右转，磁北相对手机左移
        canvas.save();
        canvas.rotate(-mAzimuth, cx, cy);
        drawDial(canvas, cx, cy, r);
        drawNeedle(canvas, cx, cy, r);
        canvas.restore();
        canvas.drawCircle(cx, cy, mZoomed ? dp(8) : dp(5), mHubPaint);
        if (mZoomed) {
            // 度数画在盘面外圈，跟着方位角转但字保持正立
            drawDegreeLabels(canvas, cx, cy, r);
        }

        // 放大后字大一号并往下挪，跟上方经纬度呼应
        mValuePaint.setTextSize(dp(mZoomed ? 24f : 17f));
        float base = Math.min(cy + r + (mZoomed ? dp(42) : dp(18)), h - dp(12));
        drawFittedText(canvas, directionText() + "  " + Math.round(norm360(mAzimuth)) + "\u00b0", cx, base, mValuePaint, w - dp(12));
        if (mAccuracy <= 1) {
            drawFittedText(canvas, "精度低，画 8 字校准", cx, base + (mZoomed ? dp(24) : dp(13)), mHintPaint, w - dp(12));
        }
    }

    /** 圆盘上方的小倒三角：尖端对着盘顶，也就是手机正前方 */
    private void drawHeadingMarker(Canvas canvas, float cx, float tipY) {
        float half = dp(6);
        float height = dp(7);
        mMarker.reset();
        mMarker.moveTo(cx - half, tipY - height);
        mMarker.lineTo(cx + half, tipY - height);
        mMarker.lineTo(cx, tipY);
        mMarker.close();
        canvas.drawPath(mMarker, mMarkerPaint);
    }

    /** 刻度 + N/E/S/W：平时每 15° 一格，放大后每 5° 一格，45° 的整数倍画长刻度 */
    private void drawDial(Canvas canvas, float cx, float cy, float r) {
        int step = mZoomed ? 5 : 15;
        float minor = mZoomed ? dp(7) : dp(5);
        float medium = mZoomed ? dp(12) : dp(9);
        float major = mZoomed ? dp(18) : dp(13);
        for (int a = 0; a < 360; a += step) {
            boolean isMajor = a % 45 == 0;
            boolean isMedium = a % 15 == 0;
            canvas.save();
            canvas.rotate(a, cx, cy);
            float len = isMajor ? major : (isMedium ? medium : minor);
            canvas.drawLine(cx, cy - r + dp(4), cx, cy - r + dp(4) + len,
                    isMajor ? mTickMajor : (isMedium ? mTick : mTickMinor));
            canvas.restore();
        }
        float markOffset = mZoomed ? dp(40) : dp(26);   // N/E/S/W 基线离盘沿：字大了往里让
        String[] marks = {"N", "E", "S", "W"};
        for (int i = 0; i < marks.length; i++) {
            canvas.save();
            canvas.rotate(i * 90f, cx, cy);
            canvas.drawText(marks[i], cx, cy - r + markOffset, mCardinal);
            canvas.restore();
        }
    }

    /** 度数：放大后每 15° 一个（正东南北那四个位置留给 N/E/S/W），随盘面转但字不倒 */
    private void drawDegreeLabels(Canvas canvas, float cx, float cy, float r) {
        // 比 N/E/S/W 再往里一圈，别跟字母挤在一起；盘面特别小时按比例收，不至于挤到中心
        float rr = r - Math.min(dp(46), r * 0.28f);
        int step = mZoomed ? 15 : 30;
        for (int a = 0; a < 360; a += step) {
            if (a % 90 == 0) continue;
            double rad = Math.toRadians(a - mAzimuth - 90);
            canvas.drawText(String.valueOf(a),
                    cx + (float) (rr * Math.cos(rad)),
                    cy + (float) (rr * Math.sin(rad)) + dp(4), mDegreePaint);
        }
    }

    /** 轮盘上方的经纬度：还没定位到就说明在等 */
    private String locationText() {
        if (!mHasLocation) {
            return "定位中…";
        }
        return String.format(Locale.getDefault(), "%.5f°%s  %.5f°%s",
                Math.abs(mLat), mLat >= 0 ? "N" : "S",
                Math.abs(mLon), mLon >= 0 ? "E" : "W");
    }

    /** 红针指北，灰针指南；放大后加长加宽，跟盘面比例才不显得空 */
    private void drawNeedle(Canvas canvas, float cx, float cy, float r) {
        float len = r * (mZoomed ? 0.74f : 0.62f);
        float half = mZoomed ? dp(11) : dp(7);
        float tail = mZoomed ? dp(13) : dp(8);
        mNeedle.reset();
        mNeedle.moveTo(cx, cy - len);
        mNeedle.lineTo(cx - half, cy + tail);
        mNeedle.lineTo(cx + half, cy + tail);
        mNeedle.close();
        canvas.drawPath(mNeedle, mNorthPaint);

        mNeedle.reset();
        mNeedle.moveTo(cx, cy + len * 0.75f);
        mNeedle.lineTo(cx - half, cy - tail);
        mNeedle.lineTo(cx + half, cy - tail);
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
        float fitted = size;
        while (fitted > dp(9f) && paint.measureText(text) > maxWidth) {
            fitted -= dp(0.5f);
            paint.setTextSize(fitted);
        }
        canvas.drawText(text, x, y, paint);
        paint.setTextSize(size);   // 用完还原，别把下一次的字号也压小
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
