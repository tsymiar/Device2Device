package com.tsymiar.device2device.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.MarketActivity;
import com.tsymiar.device2device.market.Quote;
import com.tsymiar.device2device.market.QuoteSource;
import com.tsymiar.device2device.market.QuoteSource.Symbol;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行情曲线小部件：桌面小部件列表里和「行情」并列的一个独立部件。
 *
 * 与列表版（{@link MarketWidgetProvider}）是两个不同的 AppWidget 组件：各有自己的 provider 配置、
 * 布局、配置页和广播 Action，互不干扰 —— 桌面上想看一排标的用列表版，想盯单个标的看走势用这个。
 *
 * 这个部件只盯一个标的：标题 / 市场代码 → 走势曲线 → 大字最新价 + 涨跌幅 + 区间高低 → 更新时间。
 * 曲线是把最近 N 根收盘价画成位图（{@link MarketSparkline}）后贴上去的，
 * RemoteViews 不支持自定义 View，桌面那边只认它自己能 inflate 的东西，没有别的路可走。
 *
 * 标的那份配置（{@link MarketWidgetProvider#readItems}）与列表版共用：
 * AppWidget id 全局唯一，按 id 存的 key 天然不会串；只是这里只用列表里的第一个。
 */
public class MarketChartWidgetProvider extends AppWidgetProvider {

    /** 手动刷新（无界面交互的取数入口） */
    public static final String ACTION_REFRESH = "com.tsymiar.device2device.action.MARKET_CHART_WIDGET_REFRESH";

    private static final String TAG = "MarketChartWidget";

    /** 与列表部件共用一份 prefs 文件：widgetId 全局唯一，按 id 存的 key 天然互不覆盖 */
    private static final String PREF_WIDGET = "market_widget_prefs";
    private static final String K_CINTERVAL = "cint_";    // 曲线取哪个周期

    /** 连成曲线的点数：太少看不清动向，太多请求变慢、每次刷新也更费流量 */
    private static final int BARS = 120;
    /** 没配过周期时的默认：日线各数据源都支持，也最能代表"最近这段走势" */
    public static final String DEFAULT_INTERVAL = "1d";

    /** 曲线位图之外被占掉的高度 / 宽度（dp）：根布局 padding + 标题行 + 价格行 + 更新时间 */
    private static final int CHART_CHROME_DP = 88;
    private static final int CHART_SIDE_DP = 18;
    /** 黄金那行人民币标注占掉的高度（dp）：只有显示时才从曲线高度里扣 */
    private static final int NOTE_DP = 12;

    /** goAsync 的兜底超时：网络异常时不至于一直挂着广播 */
    private static final long TIMEOUT_MS = 30000L;

    /** 取数结果缓存（每个部件一行，不需要列表那种按位置取行） */
    private static final Map<Integer, Row> sRow = new ConcurrentHashMap<>();
    private static final Set<Integer> sPending =
            Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    /** 曲线周期；没配过用日线。数据源不支持的周期退回默认，免得每次刷新都报错 */
    public static String readInterval(Context context, int appWidgetId, String source) {
        SharedPreferences w = context.getSharedPreferences(PREF_WIDGET, Context.MODE_PRIVATE);
        String saved = w.getString(K_CINTERVAL + appWidgetId, DEFAULT_INTERVAL);
        String norm = QuoteSource.normalizeInterval(saved);
        List<String> supported = Arrays.asList(QuoteSource.intervals(source));
        return supported.contains(norm) ? norm : DEFAULT_INTERVAL;
    }

    public static void saveInterval(Context context, int appWidgetId, String interval) {
        context.getSharedPreferences(PREF_WIDGET, Context.MODE_PRIVATE).edit()
                .putString(K_CINTERVAL + appWidgetId,
                        TextUtils.isEmpty(interval) ? DEFAULT_INTERVAL : interval)
                .apply();
        // 周期换了，之前那批点数不能再拿来画
        sPending.remove(appWidgetId);
        sRow.remove(appWidgetId);
    }

    private static MarketWidgetProvider.Item target(Context context, int appWidgetId) {
        return MarketWidgetProvider.readItems(context, appWidgetId).get(0);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        // 标的列表那份配置也要跟着清：它存在 MarketWidgetProvider 那份 prefs 里
        MarketWidgetProvider.clearPrefs(context, appWidgetIds);
        if (appWidgetIds == null) return;
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREF_WIDGET, Context.MODE_PRIVATE).edit();
        for (int id : appWidgetIds) {
            editor.remove(K_CINTERVAL + id);
            sRow.remove(id);
            sPending.remove(id);
        }
        editor.apply();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions);
        // 拉伸之后曲线要按新尺寸重画
        onUpdate(context, manager, new int[]{appWidgetId});
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);      // 系统广播（APPWIDGET_UPDATE 等）按默认分发
        if (intent == null || !ACTION_REFRESH.equals(intent.getAction())) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        int[] ids;
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            ids = new int[]{id};
        } else {
            ids = manager.getAppWidgetIds(new ComponentName(context, MarketChartWidgetProvider.class));
        }
        onUpdate(context, manager, ids);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        if (context == null || manager == null || appWidgetIds == null || appWidgetIds.length == 0) {
            return;
        }
        final PendingResult pending = goAsync();
        final Batch batch = new Batch(pending, appWidgetIds.length);
        // 兜底：网络一直没回来也要结束广播
        new Handler(Looper.getMainLooper()).postDelayed(batch::force, TIMEOUT_MS);

        for (int id : appWidgetIds) {
            refreshOne(context, manager, id, batch);
        }
    }

    // ------------------------------------------------------------------
    // 刷新
    // ------------------------------------------------------------------

    /** 配置页 / 行情页直接刷新某个部件：不走广播，部分 ROM 会拦静态广播导致部件一直空着 */
    public static void refreshNow(Context context, int appWidgetId) {
        if (context == null || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        refreshOne(context, AppWidgetManager.getInstance(context), appWidgetId, null);
    }

    /** 行情页取数成功后调用：让桌面上这类部件跟着刷新 */
    public static void pushUpdate(Context context) {
        if (context == null) return;
        context.sendBroadcast(new Intent(context, MarketChartWidgetProvider.class)
                .setAction(ACTION_REFRESH));
    }

    /**
     * 刷新单个部件：先 rendering 骨架（用缓存里的那一行）→ 取数 → 带着曲线位图再刷一次。
     * 任何一步出错都要把原因写在部件上，不能让桌面那边崩掉（崩了的表现就是"加不上小部件"）。
     */
    private static void refreshOne(Context context, AppWidgetManager manager, int widgetId,
                                   Batch batch) {
        try {
            final MarketWidgetProvider.Item item = target(context, widgetId);
            final String interval = readInterval(context, widgetId, item.source);
            Row cached = sRow.get(widgetId);
            Row skeleton = cached != null && cached.item != null
                    && cached.item.symbol.equals(item.symbol) ? cached : Row.loading(item, interval);
            manager.updateAppWidget(widgetId,
                    views(context, manager, widgetId, skeleton,
                            context.getString(R.string.market_widget_loading)));
            // 同一批取数还没回来就别再发一遍
            if (!sPending.add(widgetId)) {
                if (batch != null) batch.tick();
                return;
            }

            fetch(context, item, interval, (quotes, error) -> {
                final Row row = rowOf(item, quotes, error, interval);
                sRow.put(widgetId, row);
                if (row.needFx()) {
                    // 国际金报的是美元/盎司，等汇率回来换成元/克再落一次界面
                    QuoteSource.loadUsdCny(rate -> {
                        sRow.put(widgetId, row.withNote(goldNote(row.item, row.last, rate)));
                        sPending.remove(widgetId);
                        publish(context, manager, widgetId, batch);
                    });
                    return;
                }
                sPending.remove(widgetId);
                publish(context, manager, widgetId, batch);
            });
        } catch (Throwable t) {
            Log.w(TAG, "chart widget update failed", t);
            sPending.remove(widgetId);
            try {
                MarketWidgetProvider.Item item = target(context, widgetId);
                RemoteViews views = new RemoteViews(context.getPackageName(),
                        R.layout.widget_market_chart);
                views.setOnClickPendingIntent(R.id.widget_root, openMarket(context, widgetId, item));
                views.setTextViewText(R.id.widget_meta, "加载失败：" + t.getClass().getSimpleName());
                views.setTextViewText(R.id.widget_title, item.title());
                views.setViewVisibility(R.id.widget_chart_empty, View.VISIBLE);
                views.setTextViewText(R.id.widget_chart_empty,
                        context.getString(R.string.market_widget_chart_none));
                manager.updateAppWidget(widgetId, views);
            } catch (Throwable ignored) {
            }
            if (batch != null) batch.tick();
        }
    }

    /** 把缓存里那一行画到部件上；batch 非空时结尾收一次广播（不等它的话 goAsync 会挂到超时） */
    private static void publish(Context context, AppWidgetManager manager, int widgetId,
                                Batch batch) {
        Row row = sRow.get(widgetId);
        if (row != null) {
            try {
                manager.updateAppWidget(widgetId,
                        views(context, manager, widgetId, row, MarketWidgetProvider.now()));
            } catch (Throwable t) {
                Log.w(TAG, "chart widget render failed", t);
            }
        }
        if (batch != null) batch.tick();
    }

    // ------------------------------------------------------------------
    // 取数
    // ------------------------------------------------------------------

    private interface FetchCallback {
        void onDone(List<Quote> quotes, String error);
    }

    /** 输入的可能是名称/拼音，先搜索成代码再取数 */
    private static void fetch(Context context, final MarketWidgetProvider.Item item,
                              final String interval, final FetchCallback callback) {
        if (QuoteSource.needsSearch(item.symbol)) {
            QuoteSource.searchSymbol(item.symbol, new QuoteSource.SearchCallback() {
                @Override
                public void onResult(List<Symbol> items) {
                    if (items == null || items.isEmpty()) {
                        callback.onDone(null, "未找到标的");
                        return;
                    }
                    load(context, item.source, items.get(0).code, interval, callback);
                }

                @Override
                public void onError(String message) {
                    callback.onDone(null, message);
                }
            });
            return;
        }
        load(context, item.source, item.symbol, interval, callback);
    }

    private static void load(Context context, String source, String code, String interval,
                             final FetchCallback callback) {
        QuoteSource.load(source, code, interval, BARS, new QuoteSource.Callback() {
            @Override
            public void onLoaded(String src, String code, List<Quote> quotes) {
                callback.onDone(quotes, null);
            }

            @Override
            public void onFailed(String message) {
                callback.onDone(null, message);
            }
        });
    }

    /** 一次取数的结果：既要算价格/涨跌幅，也要留下能连成曲线的那串收盘价 */
    private static final class Row {
        final MarketWidgetProvider.Item item;
        final boolean ok;
        final String price;
        final String change;
        final String error;
        final int color;
        final String interval;
        final float[] series;
        final float base;
        final float high;
        final float low;
        final float last;
        /** 黄金那一行的补充标注（人民币价 / 单位），非黄金为 null */
        final String note;

        Row(MarketWidgetProvider.Item item, boolean ok, String price, String change, int color,
            String error, String interval, float[] series, float base, float high, float low,
            float last, String note) {
            this.item = item;
            this.ok = ok;
            this.price = price;
            this.change = change;
            this.color = color;
            this.error = error;
            this.interval = interval;
            this.series = series;
            this.base = base;
            this.high = high;
            this.low = low;
            this.last = last;
            this.note = note;
        }

        static Row loading(MarketWidgetProvider.Item item, String interval) {
            return new Row(item, false, "…", "", R.color.market_flat, null, interval,
                    null, 0f, 0f, 0f, 0f, null);
        }

        /** 汇率回来之后补上人民币标注，其余字段照旧 */
        Row withNote(String value) {
            return new Row(item, ok, price, change, color, error, interval,
                    series, base, high, low, last, value);
        }

        /** 国际金（美元/盎司）要等汇率回来才能算元/克 */
        boolean needFx() {
            return ok && QuoteSource.isGold(item.source, item.symbol)
                    && !QuoteSource.isCnyGold(item.source, item.symbol);
        }
    }

    /**
     * 黄金的人民币标注：沪金报价本来就是元/克，只补个单位；伦敦金 / 纽约金是美元/盎司，
     * 乘汇率再除以盎司克重换成元/克。汇率取不到就返回 null（界面上不显示这一行）。
     */
    private static String goldNote(MarketWidgetProvider.Item item, float last, float rate) {
        if (item == null || !QuoteSource.isGold(item.source, item.symbol)) return null;
        if (QuoteSource.isCnyGold(item.source, item.symbol)) return "单位 元/克";
        if (last <= 0f || rate <= 0f) return null;
        return "≈ " + MarketWidgetProvider.fmtPrice(last * rate / QuoteSource.OUNCE_GRAMS) + " 元/克";
    }

    private static Row rowOf(MarketWidgetProvider.Item item, List<Quote> quotes, String error,
                             String interval) {
        if (quotes == null || quotes.isEmpty()) {
            if (error != null) Log.w(TAG, "chart fetch failed: " + item.symbol + " " + error);
            return new Row(item, false, "--", error == null ? "无数据" : error,
                    R.color.market_flat, error, interval, null, 0f, 0f, 0f, 0f, null);
        }
        Quote last = quotes.get(quotes.size() - 1);
        float base = quotes.size() > 1 ? quotes.get(quotes.size() - 2).close : last.open;
        float percent = last.changePercent(base);
        int color = percent > 0f ? R.color.market_up
                : (percent < 0f ? R.color.market_down : R.color.market_flat);
        float[] series = new float[quotes.size()];
        float high = quotes.get(0).high;
        float low = quotes.get(0).low;
        for (int i = 0; i < quotes.size(); i++) {
            Quote quote = quotes.get(i);
            series[i] = quote.close;
            if (quote.high > high) high = quote.high;
            if (quote.low < low) low = quote.low;
        }
        return new Row(item, true, MarketWidgetProvider.fmtPrice(last.close),
                String.format(java.util.Locale.US, "%+.2f%%", percent),
                color, null, interval, series, base, high, low, last.close,
                goldNote(item, last.close, 0f));
    }

    // ------------------------------------------------------------------
    // 渲染 RemoteViews
    // ------------------------------------------------------------------

    /**
     * 整块 RemoteViews：标题 / 代码 → 占满剩余空间的曲线位图 → 最新价 + 涨跌幅 + 区间高低 → 更新时间。
     *
     * 曲线不挂在 ListView 上，跟着这次 updateAppWidget 一起过去就行，不需要再 notify 什么。
     */
    private static RemoteViews views(Context context, AppWidgetManager manager, int widgetId,
                                     Row row, String meta) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_market_chart);
        views.setTextViewText(R.id.widget_title, row.item.title());
        views.setTextViewText(R.id.widget_symbol, MarketWidgetProvider.marketCode(row.item));
        views.setTextViewText(R.id.widget_price, row.price);
        views.setTextViewText(R.id.widget_change,
                row.error != null && !row.ok ? row.error : row.change);
        views.setTextViewText(R.id.widget_range, rangeText(row));
        // 黄金才多这一行（人民币价 / 单位），非黄金收起来，别白占一格高度
        boolean note = !TextUtils.isEmpty(row.note);
        views.setViewVisibility(R.id.widget_note, note ? View.VISIBLE : View.GONE);
        if (note) views.setTextViewText(R.id.widget_note, row.note);
        views.setTextViewText(R.id.widget_meta, meta == null ? "" : meta);
        // 布局里的默认色随深浅模式，这里按当前模式显式覆盖一次，避免桌面缓存了旧配色
        views.setTextColor(R.id.widget_title, color(context, R.color.market_widget_text));
        views.setTextColor(R.id.widget_symbol, color(context, R.color.market_widget_text_hint));
        views.setTextColor(R.id.widget_refresh, color(context, R.color.market_widget_text_dim));
        views.setTextColor(R.id.widget_meta, color(context, R.color.market_widget_text_hint));
        views.setTextColor(R.id.widget_range, color(context, R.color.market_widget_text_hint));
        views.setTextColor(R.id.widget_chart_empty, color(context, R.color.market_widget_text_hint));
        views.setTextColor(R.id.widget_note, color(context, R.color.market_widget_text_hint));
        // 价格与涨跌幅统一用涨跌色，一眼看红绿
        views.setTextColor(R.id.widget_price, color(context, row.color));
        views.setTextColor(R.id.widget_change, color(context, row.color));

        // 部件尺寸差很大（从 2x2 小格到拉满的 4x3），字号跟着宽度自适应，窄格子里不溢出
        int[] size = sizeDp(manager, widgetId);
        int wdp = size[0];
        views.setTextViewTextSize(R.id.widget_price, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 9, 14, 23));
        views.setTextViewTextSize(R.id.widget_change, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 20, 9, 13));
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 18, 9, 12));
        views.setTextViewTextSize(R.id.widget_symbol, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 26, 7, 9));
        views.setTextViewTextSize(R.id.widget_meta, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 30, 7, 9));
        views.setTextViewTextSize(R.id.widget_range, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 30, 7, 9));
        views.setTextViewTextSize(R.id.widget_note, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 28, 8, 11));

        // 曲线按部件当前实际尺寸画：2x2 的小格子和拉到最大的 4x3 差得远，按一个尺寸画会被
        // fitXY 拉伸到发虚。尺寸变了由 onAppWidgetOptionsChanged 再刷一次。
        // 2x2 高度有限，固定的 CHART_CHROME_DP 会把曲线挤没，这里按高度比例给曲线留地儿
        int chrome = size[1] <= 170 ? Math.round(size[1] * 0.52f) : CHART_CHROME_DP;
        if (note) chrome += NOTE_DP;    // 黄金多出一行标注，别把曲线压掉
        int chartW = Math.max(1, size[0] - CHART_SIDE_DP);
        int chartH = Math.max(1, size[1] - chrome);
        android.graphics.Bitmap bitmap = MarketSparkline.render(context, row.series, row.base,
                chartW, chartH, color(context, R.color.market_widget_bg));
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_chart, bitmap);
            views.setViewVisibility(R.id.widget_chart_empty, View.GONE);
        } else {
            // 没有位图就不给 ImageView 设图（RemoteViews 不接受 null），让空态文案兜住
            views.setViewVisibility(R.id.widget_chart_empty, View.VISIBLE);
            views.setTextViewText(R.id.widget_chart_empty,
                    row.ok ? context.getString(R.string.market_widget_chart_none)
                           : context.getString(R.string.market_widget_chart_wait));
        }

        views.setOnClickPendingIntent(R.id.widget_root, openMarket(context, widgetId, row.item));
        views.setOnClickPendingIntent(R.id.widget_header, openMarket(context, widgetId, row.item));
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context, widgetId));
        return views;
    }

    /** 价格行右侧：周期 + 区间高低（曲线没画出来时不显示高低，免得误导） */
    private static String rangeText(Row row) {
        StringBuilder sb = new StringBuilder(QuoteSource.intervalLabel(row.interval));
        if (row.ok && row.high > 0f && row.low > 0f) {
            sb.append("  高 ").append(MarketWidgetProvider.fmtPrice(row.high))
              .append("  低 ").append(MarketWidgetProvider.fmtPrice(row.low));
        }
        return sb.toString();
    }

    /**
     * 部件当前的尺寸（dp）。min / max 取大的那个：竖屏撑宽的时候才不会按最小尺寸画。
     */
    private static int[] sizeDp(AppWidgetManager manager, int widgetId) {
        Bundle opts = manager == null ? null : manager.getAppWidgetOptions(widgetId);
        int minW = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int minH = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        int maxW = opts == null ? minW : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
        int maxH = opts == null ? minH : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
        int w = Math.max(minW, maxW);
        int h = Math.max(minH, maxH);
        return new int[]{w > 0 ? w : 250, h > 0 ? h : 130};
    }

    /** 点整块进行情页并带上这个标的；requestCode 用 widgetId，多部件互相不该覆盖 extras */
    private static PendingIntent openMarket(Context context, int widgetId,
                                            MarketWidgetProvider.Item item) {
        Intent intent = new Intent(context, MarketActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(MarketWidgetProvider.EXTRA_SOURCE, item.source);
        intent.putExtra(MarketWidgetProvider.EXTRA_SYMBOL, item.symbol);
        intent.putExtra(MarketWidgetProvider.EXTRA_NAME, item.name);
        // 这个部件没有 ListView，不需要 fillInIntent 的可变模板，保持不可变
        return PendingIntent.getActivity(context, widgetId, intent, flags());
    }

    private static PendingIntent refreshIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, MarketChartWidgetProvider.class)
                .setAction(ACTION_REFRESH);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, widgetId, intent, flags());
    }

    /** Android 12（targetSdk 31）要求显式声明 PendingIntent 的可变性 */
    private static int flags() {
        int value = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            value |= PendingIntent.FLAG_IMMUTABLE;
        }
        return value;
    }

    private static int color(Context context, int resId) {
        return ContextCompat.getColor(context, resId);
    }

    /** 把字号夹在 [lo, hi] 之间（sp） */
    private static int clampSp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 多个小部件一起刷新时，等最后一个回来再结束广播；超时兜底 force() */
    private static final class Batch {
        private final PendingResult pending;
        private final AtomicInteger left;
        private final AtomicBoolean done = new AtomicBoolean(false);

        Batch(PendingResult pending, int count) {
            this.pending = pending;
            this.left = new AtomicInteger(count);
        }

        void tick() {
            if (left.decrementAndGet() <= 0) force();
        }

        void force() {
            if (done.compareAndSet(false, true) && pending != null) pending.finish();
        }
    }
}
