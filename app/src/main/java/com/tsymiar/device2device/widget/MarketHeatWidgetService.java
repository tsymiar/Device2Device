package com.tsymiar.device2device.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.tsymiar.device2device.widget.MarketHeatWidgetProvider.HeatLayout;
import com.tsymiar.device2device.widget.MarketTreemap.Node;

import java.util.Collections;
import java.util.List;

/**
 * 热力图小部件主体那片列表的数据源：一行一行地把摆好的马赛克喂给 {@code widget_market_heat_map}。
 *
 * 为什么是列表：摆 50 格（见 {@code MarketHeatWidgetProvider#TILES_WANT}），格子还是原来那么大，
 * 画布因此比可见区域高 —— 高出来的那些行只有滚过去才看得见。RemoteViews 里只有
 * AdapterView 能滚（普通 View 连 ScrollView 都不支持），所以主体是 ListView，
 * 排版树的顶层就是「一行行横排」的几行（见 {@code MarketTreemap.layout} 的 topRows），
 * 一行正好是一个条目。
 *
 * 排版由 Provider 那边算好、缓存在本进程里（{@code MarketHeatWidgetProvider#layoutFor}），
 * 这里只负责取出来铺成 RemoteViews —— 取数、量尺寸、算字号都还在 Provider 那边，
 * 两半用同一份缓存，不会出现「列表显示的行」与「格子点击」对不上的情况。
 */
public class MarketHeatWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        return new HeatFactory(getApplicationContext(), widgetId);
    }

    /** 一行一个条目：条目本身就是那一行的 RemoteViews（宽 = 列表宽，高 = 那一行的高度） */
    private static final class HeatFactory implements RemoteViewsFactory {
        private final Context context;
        private final int widgetId;
        private List<Node> rows = Collections.emptyList();
        private int[] heights = new int[0];
        private int widthPx = 1;

        HeatFactory(Context context, int widgetId) {
            this.context = context;
            this.widgetId = widgetId;
        }

        @Override
        public void onCreate() {
            reload();
        }

        /** 数据换了 / 排版重摆了：Provider 会调 notifyAppWidgetViewDataChanged，这里跟着重取 */
        @Override
        public void onDataSetChanged() {
            reload();
        }

        @Override
        public void onDestroy() {
            rows = Collections.emptyList();
            heights = new int[0];
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= rows.size()) return null;
            int height = position < heights.length ? heights[position] : 1;
            return MarketHeatWidgetProvider.rowViews(context, widgetId,
                    rows.get(position), widthPx, Math.max(1, height));
        }

        /** 没有加载中的占位：Provider 那边排版是同步算的，拿到就是最终的几行 */
        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        private void reload() {
            HeatLayout layout = MarketHeatWidgetProvider.layoutFor(context, widgetId);
            if (layout == null || layout.rows.isEmpty()) {
                rows = Collections.emptyList();
                heights = new int[0];
                return;
            }
            rows = layout.rows;
            heights = layout.heights;
            widthPx = layout.widthPx;
        }
    }
}
