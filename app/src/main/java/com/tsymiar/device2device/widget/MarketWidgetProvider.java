package com.tsymiar.device2device.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行情桌面小部件：一个部件里可以放多个标的（自选股 / 黄金 / 原油 / 加密货币混着也行）。
 *
 * - 每个小部件实例各自记一份标的列表（未配置时跟随行情页最近查看的标的）；
 * - 标的行装在 ListView 里，超出可视高度可以上下滚动（RemoteViews 里只有 AdapterView
 *   能滚动，ScrollView 会被拒绝加载），数据源是 {@link MarketWidgetService}；
 * - 每行名称下面用小字标出市场代码（sh600519 · 沪 / BTCUSDT · 加密货币）；
 * - 系统每 30 分钟兜底刷新一次（AppWidget 允许的最小间隔），点「刷新」可立即取数；
 * - 点某行进行情页对应标的，点空白处进行情页，行情页取数成功后也会顺带刷新小部件；
 * - 取数是异步的，用 goAsync() 撑住广播生命周期，避免进程被提前回收。
 */
public class MarketWidgetProvider extends AppWidgetProvider {

    /** 手动刷新（无界面交互的取数入口） */
    public static final String ACTION_REFRESH = "com.tsymiar.device2device.action.MARKET_WIDGET_REFRESH";

    private static final String TAG = "MarketWidget";

    /** 每个小部件实例的配置 */
    private static final String PREF_WIDGET = "market_widget_prefs";
    private static final String K_ITEMS = "items_";       // 多标的：一行一条，字段用 \t 分隔
    private static final String K_SOURCE = "source_";     // 旧版单标的（读不到列表时回退用）
    private static final String K_SYMBOL = "symbol_";
    private static final String K_NAME = "name_";

    /** 未单独配置的小部件跟随行情页（MarketActivity 用同一份 prefs） */
    private static final String PREF_APP = "market_prefs";
    private static final String K_APP_SOURCE = "source";
    private static final String K_APP_SYMBOL = "symbol";

    /** 一个部件最多放几个标的：再多也塞不进桌面格子，取数也要翻倍 */
    public static final int MAX_ITEMS = 8;

    /** 只要最新价 + 前收盘；多取几根是为了「未来时间点被剔除」后仍有可比的前收盘 */
    private static final int BARS = 5;
    private static final String INTERVAL = "1d";

    /** 跳转行情页时带的标的（点小部件某一行用） */
    public static final String EXTRA_SOURCE = "market_widget_source";
    public static final String EXTRA_SYMBOL = "market_widget_symbol";
    public static final String EXTRA_NAME = "market_widget_name";

    /** goAsync 的兜底超时：网络异常时不至于一直挂着广播 */
    private static final long TIMEOUT_MS = 30000L;

    /**
     * 取数结果缓存：RemoteViews 的 ListView 只能从本进程的 Factory 取数据，
     * 所以先在这儿取好，再 notifyAppWidgetViewDataChanged 让列表重画。
     */
    private static final Map<Integer, List<Row>> sRows = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> sStamp = new ConcurrentHashMap<>();
    /** 正在取数的部件：列表重新绑定很频繁，别把同一批请求发出去好几遍 */
    private static final Set<Integer> sPending = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    /** 缓存多久算过期：列表重新绑定时据此补一次取数 */
    private static final long STALE_MS = 60000L;

    // 与小部件布局 / 行情页一致：文字色与涨跌色都取 color 资源，日间夜间自动切换

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    /** 一个标的 */
    public static final class Item {
        public final String source;
        public final String symbol;
        public final String name;

        public Item(String source, String symbol, String name) {
            this.source = source == null ? QuoteSource.AUTO : source;
            this.symbol = symbol == null ? "" : symbol;
            this.name = name == null ? "" : name;
        }

        /** 显示名：没搜到名称就显示代码 */
        public String title() {
            return TextUtils.isEmpty(name) ? symbol : name;
        }
    }

    /** 单个小部件的标的配置 */
    public static final class Config {
        public final List<Item> items;
        /** 首项快捷方式（兼容只有单个标的的场景） */
        public final String source;
        public final String symbol;
        public final String name;

        Config(List<Item> items) {
            this.items = items == null || items.isEmpty()
                    ? new ArrayList<>(fallbackItem(null, null))
                    : items;
            Item first = this.items.get(0);
            this.source = first.source;
            this.symbol = first.symbol;
            this.name = first.name;
        }
    }

    private static List<Item> fallbackItem(String source, String symbol) {
        List<Item> items = new ArrayList<>(1);
        if (TextUtils.isEmpty(symbol)) symbol = QuoteSource.defaultSymbol(
                TextUtils.isEmpty(source) ? QuoteSource.AUTO : source);
        items.add(new Item(TextUtils.isEmpty(source) ? QuoteSource.AUTO : source, symbol, ""));
        return items;
    }

    public static void saveItems(Context context, int appWidgetId, List<Item> items) {
        if (items == null || items.isEmpty()) return;
        List<Item> keep = items.size() > MAX_ITEMS ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : items;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep.size(); i++) {
            Item it = keep.get(i);
            if (i > 0) sb.append('\n');
            sb.append(it.source).append('\t').append(it.symbol).append('\t').append(it.name);
        }
        // 配置变了：旧的价格缓存和正在跑的那批取数都作废，下次按新列表来
        sPending.remove(appWidgetId);
        sRows.remove(appWidgetId);
        Item first = keep.get(0);
        // 旧键同步写一份首项：老版本 / 其它读取点仍能拿到一个标的
        context.getSharedPreferences(PREF_WIDGET, Context.MODE_PRIVATE).edit()
                .putString(K_ITEMS + appWidgetId, sb.toString())
                .putString(K_SOURCE + appWidgetId, first.source)
                .putString(K_SYMBOL + appWidgetId, first.symbol)
                .putString(K_NAME + appWidgetId, first.name)
                .apply();
    }

    /** 保留旧调用点：只配一个标的 */
    public static void saveConfig(Context context, int appWidgetId,
                                  String source, String symbol, String name) {
        List<Item> items = new ArrayList<>(1);
        items.add(new Item(source, symbol, name));
        saveItems(context, appWidgetId, items);
    }

    /** 读取标的列表；没配过就跟随行情页当前标的 */
    public static List<Item> readItems(Context context, int appWidgetId) {
        SharedPreferences w = context.getSharedPreferences(PREF_WIDGET, Context.MODE_PRIVATE);
        List<Item> items = parseItems(w.getString(K_ITEMS + appWidgetId, null));
        if (items.isEmpty()) {
            String source = w.getString(K_SOURCE + appWidgetId, null);
            String symbol = w.getString(K_SYMBOL + appWidgetId, null);
            String name = w.getString(K_NAME + appWidgetId, null);
            if (TextUtils.isEmpty(symbol)) {
                SharedPreferences app = context.getSharedPreferences(PREF_APP, Context.MODE_PRIVATE);
                source = app.getString(K_APP_SOURCE, QuoteSource.AUTO);
                symbol = app.getString(K_APP_SYMBOL, QuoteSource.defaultSymbol(source));
                name = "";
            }
            if (!TextUtils.isEmpty(symbol)) {
                items = new ArrayList<>(1);
                items.add(new Item(source, symbol, name));
            }
        }
        if (items.isEmpty()) items = fallbackItem(QuoteSource.AUTO, null);
        return items;
    }

    /** 读取配置；没配过就跟随行情页当前标的 */
    public static Config readConfig(Context context, int appWidgetId) {
        return new Config(readItems(context, appWidgetId));
    }

    private static List<Item> parseItems(String raw) {
        List<Item> items = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return items;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (TextUtils.isEmpty(line)) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 2 || TextUtils.isEmpty(f[1])) continue;
            items.add(new Item(f[0], f[1], f.length > 2 ? f[2] : ""));
            if (items.size() >= MAX_ITEMS) break;
        }
        return items;
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        if (appWidgetIds == null) return;
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREF_WIDGET, Context.MODE_PRIVATE).edit();
        for (int id : appWidgetIds) {
            editor.remove(K_ITEMS + id).remove(K_SOURCE + id).remove(K_SYMBOL + id).remove(K_NAME + id);
        }
        editor.apply();
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions);
        // 用户拉伸部件后行数可能要变，直接走一次刷新（行数按新高度算）
        onUpdate(context, manager, new int[]{appWidgetId});
    }

    // ------------------------------------------------------------------
    // 广播入口
    // ------------------------------------------------------------------

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
            ids = manager.getAppWidgetIds(new ComponentName(context, MarketWidgetProvider.class));
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

    /**
     * 刷新单个部件：先把列表按配置对齐（骨架）→ 取数 → 让列表重画。
     * 任何一步出错都要把原因写在部件上，不能让桌面那边崩掉（崩了的表现就是"加不上小部件"）。
     */
    private static void refreshOne(Context context, AppWidgetManager manager, int widgetId, Batch batch) {
        try {
            final List<Item> items = readItems(context, widgetId);
            rowsFor(context, widgetId);     // 行数先按当前配置对齐，列表才会增删行
            manager.updateAppWidget(widgetId,
                    baseViews(context, widgetId, items, context.getString(R.string.market_widget_loading)));
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list);
            // 同一批取数还没回来就别再发一遍（列表重绑、配置页刷新都会走到这里）
            if (!sPending.add(widgetId)) {
                if (batch != null) batch.tick();
                return;
            }

            fetchAll(context, items, rows -> {
                cacheRows(widgetId, rows);
                sPending.remove(widgetId);
                try {
                    manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list);
                    manager.updateAppWidget(widgetId, baseViews(context, widgetId, items, now()));
                } catch (Throwable t) {
                    Log.w(TAG, "widget render failed", t);
                }
                if (batch != null) batch.tick();
            });
        } catch (Throwable t) {
            Log.w(TAG, "widget update failed", t);
            sPending.remove(widgetId);
            try {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_market);
                views.setOnClickPendingIntent(R.id.widget_root, openMarket(context));
                views.setTextViewText(R.id.widget_meta, "加载失败：" + t.getClass().getSimpleName());
                manager.updateAppWidget(widgetId, views);
            } catch (Throwable ignored) {
            }
            if (batch != null) batch.tick();
        }
    }

    /** 配置页 / 行情页直接刷新某个部件：不走广播，部分 ROM 会拦静态广播导致部件一直空着 */
    public static void refreshNow(Context context, int appWidgetId) {
        if (context == null || appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        refreshOne(context, AppWidgetManager.getInstance(context), appWidgetId, null);
    }

    /** 列表重新绑定时缓存已经凉了才补取一次，避免每次滚动都去请求网络 */
    static void refreshIfStale(Context context, int widgetId) {
        if (context == null || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        Long stamp = sStamp.get(widgetId);
        if (stamp != null && System.currentTimeMillis() - stamp < STALE_MS) return;
        if (sPending.contains(widgetId)) return;      // 已经有一批在跑了
        refreshNow(context, widgetId);
    }

    /**
     * 列表要用的行数据：有缓存就用缓存，没有（进程刚起来）就按标的列表摆「加载中」。
     * 配置改过之后按代码把旧价格对回来，免得顺序一变价格就串到别的标的上。
     */
    static List<Row> rowsFor(Context context, int widgetId) {
        List<Item> items = readItems(context, widgetId);
        List<Row> cached = sRows.get(widgetId);
        if (cached != null && cached.size() == items.size()) {
            boolean same = true;
            for (int i = 0; i < items.size(); i++) {
                if (!cached.get(i).item.symbol.equals(items.get(i).symbol)) {
                    same = false;
                    break;
                }
            }
            if (same) return cached;
        }
        List<Row> fresh = new ArrayList<>(items.size());
        for (Item item : items) {
            Row hit = null;
            if (cached != null) {
                for (Row row : cached) {
                    if (row.item.symbol.equals(item.symbol) && row.item.source.equals(item.source)) {
                        hit = row;
                        break;
                    }
                }
            }
            fresh.add(hit != null ? hit : Row.loading(item));
        }
        sRows.put(widgetId, fresh);
        return fresh;
    }

    private static void cacheRows(int widgetId, Row[] rows) {
        if (rows == null) return;
        sRows.put(widgetId, new ArrayList<>(Arrays.asList(rows)));
        sStamp.put(widgetId, System.currentTimeMillis());
    }

    /**
     * 行情页：若是从小部件某一行跳进来的，就把那个标的设成当前标的。
     * 走 SharedPreferences 中转是因为行情页的「上次选择」也读这份 prefs，
     * 退出再进才会停在刚才看的标的上。
     *
     * @return true 表示这次 intent 里确实带了标的，调用方需要按它刷新界面
     */
    public static boolean applyLaunchSymbol(Context context, Intent intent) {
        if (context == null || intent == null) return false;
        String symbol = intent.getStringExtra(EXTRA_SYMBOL);
        if (TextUtils.isEmpty(symbol)) return false;
        String source = intent.getStringExtra(EXTRA_SOURCE);
        context.getSharedPreferences(PREF_APP, Context.MODE_PRIVATE).edit()
                .putString(K_APP_SOURCE, TextUtils.isEmpty(source) ? QuoteSource.AUTO : source)
                .putString(K_APP_SYMBOL, symbol)
                .apply();
        return true;
    }

    /** 行情页取数成功后调用：让小部件跟着刷新 */
    public static void pushUpdate(Context context) {
        Intent intent = new Intent(context, MarketWidgetProvider.class).setAction(ACTION_REFRESH);
        context.sendBroadcast(intent);
    }

    // ------------------------------------------------------------------
    // 取数
    // ------------------------------------------------------------------

    /** 一个标的的取数结果（包可见：列表 Factory 要按位置取行） */
    static final class Row {
        final Item item;
        final boolean ok;
        final String price;
        final String change;
        final int color;          // 涨跌色资源 id，渲染时再 resolve
        final String error;

        Row(Item item, boolean ok, String price, String change, int color, String error) {
            this.item = item;
            this.ok = ok;
            this.price = price;
            this.change = change;
            this.color = color;
            this.error = error;
        }

        static Row loading(Item item) {
            return new Row(item, false, "…", "", R.color.market_flat, null);
        }

        /** 连标的都还没定下来时的占位行 */
        static Row placeholder(Context context) {
            String symbol = QuoteSource.defaultSymbol(QuoteSource.AUTO);
            return loading(new Item(QuoteSource.AUTO, symbol,
                    context == null ? "" : context.getString(R.string.market_widget_loading)));
        }
    }

    private interface FetchCallback {
        void onDone(List<Quote> quotes, String error);
    }

    private interface RowsCallback {
        void onDone(Row[] rows);
    }

    /** 多个标的并发取数，按原顺序回填 */
    private static void fetchAll(Context context, List<Item> items, final RowsCallback callback) {
        final Row[] rows = new Row[Math.max(1, items.size())];
        final AtomicInteger left = new AtomicInteger(items.size());
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final Item item = items.get(i);
            rows[index] = Row.loading(item);
            fetch(context, item, (quotes, error) -> {
                rows[index] = rowOf(item, quotes, error);
                if (left.decrementAndGet() <= 0) callback.onDone(rows);
            });
        }
    }

    /** 输入的可能是名称/拼音，先搜索成代码再取数 */
    private static void fetch(Context context, final Item item, final FetchCallback callback) {
        if (QuoteSource.needsSearch(item.symbol)) {
            QuoteSource.searchSymbol(item.symbol, new QuoteSource.SearchCallback() {
                @Override
                public void onResult(List<Symbol> items) {
                    if (items == null || items.isEmpty()) {
                        callback.onDone(null, "未找到标的");
                        return;
                    }
                    load(context, item.source, items.get(0).code, callback);
                }

                @Override
                public void onError(String message) {
                    callback.onDone(null, message);
                }
            });
            return;
        }
        load(context, item.source, item.symbol, callback);
    }

    private static void load(Context context, String source, String code,
                             final FetchCallback callback) {
        QuoteSource.load(source, code, INTERVAL, BARS, new QuoteSource.Callback() {
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

    private static Row rowOf(Item item, List<Quote> quotes, String error) {
        if (quotes == null || quotes.isEmpty()) {
            if (error != null) Log.w(TAG, "widget fetch failed: " + item.symbol + " " + error);
            return new Row(item, false, "--", error == null ? "无数据" : error, R.color.market_flat, error);
        }
        Quote last = quotes.get(quotes.size() - 1);
        float base = quotes.size() > 1 ? quotes.get(quotes.size() - 2).close : last.open;
        float percent = last.changePercent(base);
        int color = percent > 0f ? R.color.market_up : (percent < 0f ? R.color.market_down : R.color.market_flat);
        return new Row(item, true, fmtPrice(last.close),
                String.format(Locale.US, "%+.2f%%", percent), color, null);
    }

    // ------------------------------------------------------------------
    // 渲染 RemoteViews
    // ------------------------------------------------------------------

    private static RemoteViews baseViews(Context context, int widgetId, List<Item> items, String meta) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_market);
        String title = context.getString(R.string.market_widget_title)
                + (items.size() > 1 ? " · " + items.size() : "");
        views.setTextViewText(R.id.widget_title, title);
        // 布局里的默认色已随深浅模式，这里按当前模式再显式覆盖一次，避免桌面端缓存旧配色
        views.setTextColor(R.id.widget_title, color(context, R.color.market_widget_text));
        views.setTextColor(R.id.widget_refresh, color(context, R.color.market_widget_text_dim));
        views.setTextColor(R.id.widget_meta, color(context, R.color.market_widget_text_hint));
        views.setTextColor(R.id.widget_empty, color(context, R.color.market_widget_text_hint));
        views.setTextViewText(R.id.widget_meta, meta == null ? "" : meta);
        // 列表：挂上数据源（MarketWidgetService），点击模板负责把某一行的标的带给行情页
        views.setRemoteAdapter(R.id.widget_list, MarketWidgetService.adapterIntent(context, widgetId));
        views.setEmptyView(R.id.widget_list, R.id.widget_empty);
        views.setPendingIntentTemplate(R.id.widget_list, openMarket(context));
        // 点空白进行情页，点「刷新」只取数
        views.setOnClickPendingIntent(R.id.widget_root, openMarket(context));
        views.setOnClickPendingIntent(R.id.widget_header, openMarket(context));
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context, widgetId));
        return views;
    }

    /** 一行一个标的：左名称 + 市场代码小字，右最新价与涨跌幅；row 为 null 时给占位行 */
    static RemoteViews rowViews(Context context, Row row, boolean single) {
        Row data = row != null ? row : Row.placeholder(context);
        RemoteViews rowView = new RemoteViews(context.getPackageName(), R.layout.widget_market_item);
        rowView.setTextViewText(R.id.widget_row_name, data.item.title());
        rowView.setTextViewText(R.id.widget_row_symbol, marketCode(data.item));
        rowView.setTextViewText(R.id.widget_row_price, data.price);
        rowView.setTextViewText(R.id.widget_row_change,
                data.error != null && !data.ok ? data.error : data.change);
        rowView.setTextColor(R.id.widget_row_name, color(context, R.color.market_widget_text));
        rowView.setTextColor(R.id.widget_row_symbol, color(context, R.color.market_widget_text_hint));
        rowView.setTextColor(R.id.widget_row_price, color(context, data.color));
        rowView.setTextColor(R.id.widget_row_change, color(context, data.color));
        // 尺寸跟着内容：只放一个标的就把字号撑大
        rowView.setTextViewTextSize(R.id.widget_row_name, TypedValue.COMPLEX_UNIT_SP, single ? 13f : 12f);
        rowView.setTextViewTextSize(R.id.widget_row_price, TypedValue.COMPLEX_UNIT_SP, single ? 20f : 15f);
        rowView.setTextViewTextSize(R.id.widget_row_change, TypedValue.COMPLEX_UNIT_SP, single ? 12f : 11f);
        rowView.setTextViewTextSize(R.id.widget_row_symbol, TypedValue.COMPLEX_UNIT_SP, 9f);
        // 市场代码常显：标的多的时候也要能认出每个是哪个市场的代码
        rowView.setViewVisibility(R.id.widget_row_symbol, View.VISIBLE);
        // 整行可点：跳行情页并带上这一行的标的
        Intent fill = new Intent();
        fill.putExtra(EXTRA_SOURCE, data.item.source);
        fill.putExtra(EXTRA_SYMBOL, data.item.symbol);
        fill.putExtra(EXTRA_NAME, data.item.name);
        rowView.setOnClickFillInIntent(R.id.widget_row_root, fill);
        return rowView;
    }

    /** 行里那行小字：市场代码 + 市场（sh600519 · 沪 / BTCUSDT · 加密货币） */
    private static String marketCode(Item item) {
        String code = item.symbol == null ? "" : item.symbol.trim();
        String tag = marketTag(code, item.source);
        return TextUtils.isEmpty(tag) ? code : code + " · " + tag;
    }

    private static String marketTag(String code, String source) {
        String c = code.toLowerCase(Locale.US);
        if (c.startsWith("sh")) return "沪";
        if (c.startsWith("sz")) return "深";
        if (c.startsWith("bj")) return "京";
        if (c.startsWith("hk")) return "港";
        if (c.startsWith("us")) return "美";
        if (source != null) {
            switch (source) {
                case QuoteSource.GOLD:
                case QuoteSource.XAU:
                case QuoteSource.GC:
                    return "黄金";
                case QuoteSource.CRUDE:
                case QuoteSource.BRENT:
                case QuoteSource.NG:
                    return "原油";
                case QuoteSource.USD:
                    return "美元";
                case QuoteSource.BINANCE:
                    return "加密货币";
                default:
                    break;
            }
        }
        return "";
    }

    private static int color(Context context, int resId) {
        return ContextCompat.getColor(context, resId);
    }

    private static PendingIntent openMarket(Context context) {
        Intent intent = new Intent(context, MarketActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // 列表行的标的靠 fillInIntent 补进来：Android 12+ 只有可变的模板才会收下这些 extra，
        // 模板若建成不可变，点哪一行都只会打开上次那个标的
        return PendingIntent.getActivity(context, 0, intent, templateFlags());
    }

    /** fillInIntent 用可变模板；其余场景保持不可变 */
    private static int templateFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private static PendingIntent refreshIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, MarketWidgetProvider.class).setAction(ACTION_REFRESH);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, widgetId, intent, flags());
    }

    /** Android 12（targetSdk 31）要求显式声明 PendingIntent 的可变性 */
    private static int flags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    /** 按价位决定小数位：股价两位，小币种/低价标的多给几位 */
    private static String fmtPrice(float price) {
        float abs = Math.abs(price);
        int decimals = abs >= 100f ? 2 : (abs >= 1f ? 3 : 4);
        return String.format(Locale.US, "%." + decimals + "f", price);
    }

    private static String now() {
        return new SimpleDateFormat("更新 HH:mm", Locale.getDefault()).format(new Date());
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
