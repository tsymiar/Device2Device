package com.tsymiar.device2device.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;

import com.tsymiar.device2device.R;

/**
 * 点「刷新」时的等待动画：图标换成与 ChatBox「思考中…」同一个路子的 indeterminate
 * ProgressBar，由系统自己转，不逐帧推角度。
 *
 * 部件视图位于启动器进程内，无法直接对其 View 使用动画；这里改用 setViewVisibility
 * 在两个视图之间切换。数据回灌时会整张重画，两个视图回到 XML 默认（图标可见、转圈隐藏），
 * 所以动画中途被真实刷新打断也无害。
 */
final class WidgetSpin {
    /** 转圈露出多久：取数回来整张重画会把它收掉，这里只是取数迟迟不回来时的兜底 */
    private static final long SPIN_MS = 3000;

    private WidgetSpin() {
    }

    static void spin(Context context, AppWidgetManager manager, int widgetId, int layoutId) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> setSpinning(context, manager, widgetId, layoutId, true));
        handler.postDelayed(() -> setSpinning(context, manager, widgetId, layoutId, false), SPIN_MS);
    }

    private static void setSpinning(Context context, AppWidgetManager manager, int widgetId,
                                    int layoutId, boolean spinning) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), layoutId);
        rv.setViewVisibility(R.id.widget_refresh, spinning ? View.GONE : View.VISIBLE);
        rv.setViewVisibility(R.id.widget_refresh_spin, spinning ? View.VISIBLE : View.GONE);
        manager.partiallyUpdateAppWidget(widgetId, rv);
    }
}
