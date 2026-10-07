package com.tsymiar.device2device.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.util.Log;

import com.tsymiar.device2device.market.MarketPalette;

/**
 * 小部件里的走势曲线：把一串收盘价画成一张位图，交给 {@code RemoteViews.setImageViewBitmap}
 * 贴到 ImageView 上。
 *
 * RemoteViews 不支持自定义 View，要在桌面把曲线画出来只有这一条路：在本进程用 Canvas 画好，
 * 再把位图塞进 RemoteViews（Binder 里过去给桌面画）。
 *
 * 两个必须守住的限制：
 * - **体积**：AppWidget 更新走 Binder 事务，整个更新包有体积上限。位图太大不是慢，
 *   而是整个部件更新失败、桌面直接不给你画（现象就是加了部件一直空白）。
 *   所以这里按 MAX_W / MAX_H 硬性夹住 px 尺寸，且按设备密度换算后还要再夹一次。
 * - **透明**：位图背景要透明（部件是圆角卡片，实色底会把圆角压成直角），
 *   因此必须用 ARGB_8888；RGB_565 没有 alpha 通道，透明区会变成黑块。
 *
 * 配色走 {@link MarketPalette}：曲线色按「最新一段是涨还是跌」取涨跌色（红涨绿跌），
 * 中间那条虚线是基准线（调用方传进来的是当天开盘价），
 * 用来直观看出当前价格在开盘价之上还是之下。
 */
public final class MarketSparkline {

    private static final String TAG = "MarketSparkline";

    /** Binder 事务兜底：超出这两个值之后，一次 AppWidget 更新可能直接失败 */
    private static final int MAX_W = 520;
    private static final int MAX_H = 220;
    private static final int MIN_W = 48;
    private static final int MIN_H = 24;

    private MarketSparkline() {
    }

    /**
     * 画一张走势图。
     *
     * @param closes  收盘价序列（按时间升序），至少 2 个点才画得出来，否则返回 null
     * @param base    基准价（行情曲线部件传的是当天开盘价），画成横向虚线；<=0 表示不画这条线
     * @param widthDp 可用宽度（dp，已减去布局的左右 padding）
     * @param heightDp 可用高度（dp，已减去上下其它行占掉的部分）
     * @param haloColor 末点外圈的底色：要跟部件卡片底色一致，取值必须是 market_widget_bg，
     *                  不能用 MarketPalette.bg —— 夜间两者并不是同一个颜色，直接用会在
     *                  末点周围露出比卡片更深的一圈
     * @return 背景透明的位图；数据不足或绘制失败返回 null，调用方转成「暂无数据」文案
     */
    public static Bitmap render(Context context, float[] closes, float base,
                                int widthDp, int heightDp, int haloColor) {
        if (context == null || closes == null || closes.length < 2) return null;
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm == null ? 1f : dm.density;
        int w = clamp(Math.round(widthDp * density), MIN_W, MAX_W);
        int h = clamp(Math.round(heightDp * density), MIN_H, MAX_H);
        try {
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            if (!draw(new Canvas(bitmap), MarketPalette.of(context), closes, base,
                    w, h, density, haloColor)) {
                bitmap.recycle();
                return null;
            }
            return bitmap;
        } catch (Throwable t) {
            // OOM 之类：宁可不画曲线，也不能把整个部件的更新拖崩
            Log.w(TAG, "sparkline render failed", t);
            return null;
        }
    }

    /** @return false 表示这条曲线没什么可画的（价格全相等且无基准），调用方按空数据处理 */
    private static boolean draw(Canvas canvas, MarketPalette p, float[] closes, float base,
                                int w, int h, float density, int haloColor) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float value : closes) {
            if (value < min) min = value;
            if (value > max) max = value;
        }
        // 基准线也要落在可视范围内，否则可能出现「曲线贴顶但基准线画到框外」
        if (base > 0f) {
            if (base < min) min = base;
            if (base > max) max = base;
        }
        float span = max - min;
        if (span <= 0f) {
            float pad = Math.abs(max) * 0.002f + 0.5f;
            min -= pad;
            max += pad;
            span = max - min;
        }

        float stroke = 1.4f * density;
        float dot = 2.6f * density;
        float padX = Math.max(1f, stroke);
        float padTop = dot + stroke + 1f;
        float padBottom = dot + stroke + 1f;
        float usableH = h - padTop - padBottom;
        if (usableH <= 1f) return false;

        float first = closes[0];
        float last = closes[closes.length - 1];
        float reference = base > 0f ? base : first;
        boolean rising = last >= reference;
        int lineColor = rising ? p.up : p.down;

        float stepX = (w - padX * 2) / (closes.length - 1);
        Path line = new Path();
        Path fill = new Path();
        for (int i = 0; i < closes.length; i++) {
            float x = padX + stepX * i;
            float y = padTop + (max - closes[i]) / span * usableH;
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }

        // 面积填充：折线下方接到底边再闭合，用上深下浅的渐变收尾
        float lastX = padX + stepX * (closes.length - 1);
        fill.lineTo(lastX, h - padBottom + 1f);
        fill.lineTo(padX, h - padBottom + 1f);
        fill.close();

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(new LinearGradient(0f, padTop, 0f, h - padBottom,
                Color.argb(110, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                Color.argb(0, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                Shader.TileMode.CLAMP));
        canvas.drawPath(fill, fillPaint);

        // 开盘基准线：虚线 + 网格色，看一眼就知道当前价在开盘价之上还是之下
        if (base > 0f) {
            float baseY = padTop + (max - base) / span * usableH;
            Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            basePaint.setStyle(Paint.Style.STROKE);
            basePaint.setStrokeWidth(Math.max(1f, density * 0.7f));
            basePaint.setColor(p.grid);
            basePaint.setPathEffect(new DashPathEffect(new float[]{4f * density, 3f * density}, 0f));
            canvas.drawLine(padX, baseY, w - padX, baseY, basePaint);
        }

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(stroke);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(lineColor);
        canvas.drawPath(line, linePaint);

        // 最新价：实心圆点 + 一圈底色描边，把末点从渐变里拎出来
        float lastY = padTop + (max - last) / span * usableH;
        Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint.setStyle(Paint.Style.FILL);
        haloPaint.setColor(haloColor);
        canvas.drawCircle(lastX, lastY, dot + 1.5f * density, haloPaint);
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(lineColor);
        canvas.drawCircle(lastX, lastY, dot, dotPaint);
        return true;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
