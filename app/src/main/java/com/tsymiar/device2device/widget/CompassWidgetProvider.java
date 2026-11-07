package com.tsymiar.device2device.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.RemoteViews;

import com.tsymiar.device2device.R;

/**
 * 指南针桌面小部件：圆形表盘。平时静态显示（无定时刷新、不注册传感器），
 * 点击拉起 CompassActivity 触发传感器测向，测完由它把方位画回部件 ——
 * 小部件进程拿不到常驻传感器监听，所以「用的时候测一次」正好也省电。
 */
public class CompassWidgetProvider extends AppWidgetProvider {

    private static final int SIZE = 256;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        Bitmap dial = dial(-1f);
        for (int id : appWidgetIds) {
            update(context, manager, id, dial);
        }
    }

    /** RemoteViews 组装：表盘位图 + 点击测向；CompassActivity 测完也走这里回写 */
    static void update(Context context, AppWidgetManager manager, int id, Bitmap dial) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_compass);
        views.setImageViewBitmap(R.id.compass_image, dial);
        views.setOnClickPendingIntent(R.id.compass_image,
                PendingIntent.getActivity(context, 0, new Intent(context, CompassActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(id, views);
    }

    /** 表盘位图。azimuth &lt; 0 = 未测向：指针朝上，盘面提示「点击测向」 */
    static Bitmap dial(float azimuth) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        // 圆盘上移，底部留出独立文字条带：方位字/刻度/指针/状态文字按半径分层，互不重叠
        float cx = SIZE / 2f, cy = SIZE / 2f - 14f;
        float r = 100f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xEE141A22);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(0xFF5A6678);
        canvas.drawCircle(cx, cy, r, paint);

        // 刻度：每 15° 一道，只占最外圈（80~90），不压到方位字
        for (int i = 0; i < 360; i += 15) {
            float rad = (float) Math.toRadians(i - 90);
            boolean major = i % 90 == 0;
            float len = major ? 10f : 6f;
            paint.setStrokeWidth(major ? 3f : 1.5f);
            paint.setColor(major ? 0xFF9AA4B2 : 0xFF4A5568);
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
            canvas.drawLine(cx + (r - 10 - len) * cos, cy + (r - 10 - len) * sin,
                    cx + (r - 10) * cos, cy + (r - 10) * sin, paint);
        }

        // 方位字：北/东/南/西
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(26f);
        paint.setColor(0xFFE8EAED);
        Paint.FontMetrics fm = paint.getFontMetrics();
        String[] names = {"北", "东", "南", "西"};
        for (int i = 0; i < names.length; i++) {
            float rad = (float) Math.toRadians(i * 90 - 90);
            float tx = cx + (r - 34f) * (float) Math.cos(rad);
            float ty = cy + (r - 34f) * (float) Math.sin(rad);
            canvas.drawText(names[i], tx, ty - (fm.ascent + fm.descent) / 2f, paint);
        }

        // 指针：红指北、灰指南，随测得方位逆时针转；针尖半径 46 < 方位字内缘 53，转到任意角度都不压字
        float angle = azimuth < 0f ? 0f : azimuth;
        float len = r - 54f;
        canvas.save();
        canvas.rotate(-angle, cx, cy);
        paint.setColor(0xFFD62728);
        canvas.drawPath(needle(cx, cy, len, true), paint);
        paint.setColor(0xFF8A94A6);
        canvas.drawPath(needle(cx, cy, len, false), paint);
        canvas.restore();
        paint.setColor(0xFFC9D1DC);
        canvas.drawCircle(cx, cy, 5f, paint);

        // 状态文字放到圆盘外的专属条带里（圆盘底 214，条带 220~250），不再和「南」/指针叠在一起
        float bandTop = cy + r + 6f;
        float bandBottom = SIZE - 6f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xEE141A22);
        canvas.drawRoundRect(new RectF(cx - 92f, bandTop, cx + 92f, bandBottom), 8f, 8f, paint);
        paint.setTextSize(18f);
        Paint.FontMetrics tf = paint.getFontMetrics();
        float baseY = (bandTop + bandBottom) / 2f - (tf.ascent + tf.descent) / 2f;
        String text;
        if (azimuth < 0f) {
            paint.setColor(0xFF9AA4B2);
            text = "点击测向";
        } else {
            paint.setColor(0xFFFFD54F);
            text = direction(azimuth) + " " + Math.round(azimuth) + "°";
        }
        canvas.drawText(text, cx, baseY, paint);
        return bmp;
    }

    /** 指针半边路径：north=true 是红色北针，false 是灰色南针 */
    private static Path needle(float cx, float cy, float len, boolean north) {
        Path path = new Path();
        path.moveTo(cx, north ? cy - len : cy + len * 0.7f);
        path.lineTo(cx - 9f, cy);
        path.lineTo(cx + 9f, cy);
        path.close();
        return path;
    }

    /** 0~360° 映射到八个方位名 */
    static String direction(float deg) {
        String[] names = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return names[((int) Math.round(deg / 45f)) % 8];
    }
}
