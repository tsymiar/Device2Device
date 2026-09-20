package com.tsymiar.device2device.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/**
 * 行情小部件列表的数据源：RemoteViews 里只有 AdapterView 系列能真正滚动，
 * 小部件里放 ScrollView 会直接 inflate 失败，所以标的行交给 ListView + RemoteViewsService。
 *
 * 数据由 Provider 取好后放在进程内缓存（见 {@link MarketWidgetProvider#rowsFor}），
 * 这里只负责「按位置给一行」；缓存过期时顺手补一次取数，
 * 免得进程被回收后重新绑定时列表一直空着。
 */
public class MarketWidgetService extends RemoteViewsService {

    /**
     * 每个部件一个 adapter：用 setData 区分，否则多个部件会复用同一个 Factory 而串数据。
     */
    static Intent adapterIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, MarketWidgetService.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent.setData(Uri.fromParts("market", String.valueOf(appWidgetId), null));
        return intent;
    }

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        int id = intent == null ? AppWidgetManager.INVALID_APPWIDGET_ID
                : intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        return new RowFactory(getApplicationContext(), id);
    }

    private static final class RowFactory implements RemoteViewsService.RemoteViewsFactory {
        private final Context mContext;
        private final int mWidgetId;
        private List<MarketWidgetProvider.Row> mRows = new ArrayList<>();

        RowFactory(Context context, int widgetId) {
            mContext = context;
            mWidgetId = widgetId;
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            mRows = MarketWidgetProvider.rowsFor(mContext, mWidgetId);
            // 进程被回收后重新绑定时缓存是空的：补一次取数，取完 Provider 会再 notify 一次
            MarketWidgetProvider.refreshIfStale(mContext, mWidgetId);
        }

        @Override
        public void onDestroy() {
            mRows.clear();
        }

        @Override
        public int getCount() {
            // 至少一行：配置还没落地时也能看到「加载中」而不是一片空白
            return Math.max(1, mRows.size());
        }

        @Override
        public RemoteViews getViewAt(int position) {
            MarketWidgetProvider.Row row = position < mRows.size() ? mRows.get(position) : null;
            // 只放一个标的时那一行把价格撑大
            return MarketWidgetProvider.rowViews(mContext, row, mRows.size() == 1);
        }

        @Override
        public RemoteViews getLoadingView() {
            return MarketWidgetProvider.rowViews(mContext, null, false);
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
    }
}
