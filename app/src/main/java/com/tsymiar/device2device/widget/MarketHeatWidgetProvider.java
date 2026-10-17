package com.tsymiar.device2device.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.activity.MarketActivity;
import com.tsymiar.device2device.market.CapRow;
import com.tsymiar.device2device.market.MarketPalette;
import com.tsymiar.device2device.market.QuoteSource;
import com.tsymiar.device2device.market.Snapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行情热力图小部件（4x4）：一屏摆开当天沪深京 A 股市值最大的一批（画几只看部件多大，见 {@code tilesFor}）。
 *
 * 每格面积 = 总市值、颜色 = 涨跌幅（红涨绿跌，越浓涨跌越大），点某一格就跳到该标的的行情页。
 *
 * 形状与顺位：每一刀把一块矩形按市值之比切成两半（见 {@link MarketTreemap}），
 * 切出来的树再加上「大的一半与小的一半交错」的重排，
 * 于是小方块就穿插在大方块之间，而不是照市值排成一列把大的全挤在一角。
 * 每一刀都按市值分账，各格面积之和因此等于这块矩形 —— 不超出边框、整体正好是矩形，
 * 也就不需要滚动：RemoteViews 里能滚的只有 AdapterView 那一套，不用它反而省了一整个
 * RemoteViewsService 的来回。
 *
 * 落到 View 上的办法只有一个：RemoteViews 不支持自定义 View，也没法给一张位图做点击命中
 * 测试，所以「每格点得动」只能靠真的 View —— 树上的一个内部节点是一个嵌套的 LinearLayout
 * （沿宽度切就横排 widget_heat_row、沿高度切就竖排 widget_heat_col），叶子是一片 TextView
 * 格子，宽高用 {@code setInt(id, "setWidth"/"setHeight", px)} 逐个写死
 * （TextView.setWidth/setHeight 同时写死最小与最大那个像素值，量出来正是要的尺寸）。
 *
 * 这个部件没有需要配置的东西（榜单范围就是它自己），所以没有配置页。
 * 所有实例显示同一份榜单，取数结果是进程内共享的；马赛克按每个实例各自的尺寸重新摆。
 */
public class MarketHeatWidgetProvider extends AppWidgetProvider {

    /** 手动刷新（无界面交互的取数入口） */
    public static final String ACTION_REFRESH = "com.tsymiar.device2device.action.MARKET_HEAT_WIDGET_REFRESH";

    /** 点了「刷新」就别再看缓存 */
    private static final String EXTRA_FORCE = "force";

    private static final String TAG = "MarketHeatWidget";

    /**
     * 一次取多少条：也是能画的上限 —— 每摆几格由部件尺寸定（见 {@link #tilesFor}）。
     * 100 是榜单接口一页给得出来的上限（新浪 {@code num} 最多 100），再往上要翻页，
     * 而更大的部件上多出来的格子也就是一片认不出名字的色块。
     */
    public static final int TOP_N = 100;

    /**
     * 画几格的下限：再小的部件也照摆这么多 —— 宁可挤成一片色块，也要把盘面摆全。
     *
     * 代价是 4 行以下这一段上格数不再跟着高度变（2 行、3 行、4 行高都是 30 只），
     * 4 行往上才按面积一路加到 {@link #TOP_N}：4 行 32 只、5 行 43 只、6 行 55 只（5 宽 6 行 71 只）。
     */
    private static final int TILES_MIN = 30;
    /**
     * 每一格的目标面积（dp²）：格数 = 可视面积 / 它，于是格子不跟着部件大小忽大忽小 ——
     * 小部件少摆几只，大部件多摆几只。
     *
     * 1400 是按 4x4 那块（可视约 230x180dp）摆 30 格反推的：约 37dp 见方，
     * 名称与涨跌幅在那样的格子里都还摆得下。固定 30 只的话，3x3 里每格只剩十几 dp，
     * 名字与涨跌幅全排不下，整片只剩颜色；大部件又白白空着。
     */
    private static final float TILE_AREA_DP2 = 1400f;

    /** 多实例共享最近一次结果：同一份榜单没必要每个部件各拉一次 */
    private static volatile List<CapRow> sRows;
    private static volatile String sError;
    private static volatile long sStamp = 0L;
    /** 全市场涨跌家数：与榜单分开取的一趟数，见 {@link #fetchBreadth} */
    private static volatile QuoteSource.Breadth sBreadth;
    /** 缓存多久算过期：榜单一次就是整份几十条，间隔太密只会白白多打请求 */
    private static final long STALE_MS = 120000L;
    /** 同一批取数只发一次请求 */
    private static final AtomicBoolean sPending = new AtomicBoolean(false);

    /**
     * 每个实例当前摆好的排版树 + 摆它时用的 {宽dp, 高dp, 格数}（任一变了就得重摆，见 mapFor）。
     *
     * 格数也要进缓存键：它不只跟着尺寸走，还被「这一趟实际取回几条」夹过一次
     * （见 paint 里 {@code tiles} 的收尾）。只认尺寸的话，先取回 60 条时摆好的那棵树，
     * 在取回 100 条之后还会一直用下去 —— 尺寸没变，缓存就命中了。
     */
    private static final Map<Integer, MarketTreemap.Node> sMaps = new ConcurrentHashMap<>();
    private static final Map<Integer, int[]> sMapSizes = new ConcurrentHashMap<>();

    /**
     * 列表可视高度之外被占掉的高度（dp）：根布局 padding 上下 9 + 马赛克上下 margin 3/2。
     * 标题 / 状态 / 图例那三行文字不在这里拍死 —— 它们跟着系统字体缩放长高，
     * 而且是拿 Paint 真量出来的（见 {@link #chromePx}）。
     */
    private static final int HEAT_CHROME_FRAME_DP = 23;
    /** 标题 12sp（加粗）、状态行 9sp、底部图例 9sp：都只有一行，见 {@link #chromePx} */
    private static final float CHROME_TITLE_SP = 12f;
    private static final float CHROME_LINE_SP = 9f;
    /** 列表左右被占掉的宽度（dp）：根布局 padding 两侧 */
    private static final int HEAT_SIDE_DP = 18;
    /** 拿不到尺寸的兜底：4x4 的常用换算值（70*4-30） */
    private static final int SIZE_FALLBACK_DP = 250;

    /**
     * 摆马赛克时比「列表那块空间」再往里收几 dp。
     *
     * 两条边都要收：高度是量出来的（见 {@link #chromePx}，量少了整片马赛克就会压到
     * 下面的图例上），宽度虽说是算准的，启动器按格子取整时也可能少给几个像素 ——
     * 而每个格子的宽高都是写死的像素值，没有可伸缩的余地。少摆这几像素只是边上留一道
     * 几乎看不见的空当，多摆几像素就得看上去是画歪了，两边权衡下来宁可少。
     */
    private static final float MAP_SAFETY_DP = 1f;
    /**
     * 相邻两格之间那条缝（px）：一条发丝线，固定一个像素而不是跟着 density 走。
     *
     * 缝是要占面积的，而且是从每一层按比例扣出来的 —— 缝越宽，
     * 排在树深处的格子被扣得越多，画出来的面积就越不是那份市值（按同样的数据量：
     * 3px 的缝中位差 4.1%，1px 只差 1.4%）。发丝线照样分得开，绕下来值。
     */
    private static final int SEAM_PX = 1;
    /**
     * 每一格的 PendingIntent 都要有自己的 requestCode（见 {@code stockIntent}），
     * 每个部件实例各占一段编号，免得两个实例互相串出一格连着别的票。
     */
    private static final int TILE_CODE_STRIDE = 2048;
    /**
     * 量一行字有多宽时用的探针字号：宽高都按这个字号量一次，再按比例反解放得下的字号
     * （{@link Paint#measureText} 与字号严格成正比）。
     *
     * 但光靠 measureText 不行，见 {@link #textWidthPx}：它不做字体回退，汉字会被量成缺字宽度。
     */
    private static final float TEXT_PROBE_PX = 100f;
    /** 全角字（汉字 / 假名 / 谚文 / 全角标点）在这个探针字号下占的宽度：字面就是 1em */
    private static final float WIDE_EM = 1f;
    /** 其余（拉丁字母、数字、半角标点）取的宽度上界：Roboto 数字 0.56em，给到 0.6em */
    private static final float NARROW_EM = 0.6f;
    /**
     * 宽度留的余量。留这么多不是为了好看：一旦行宽戳过换行阈值，中文虽然没有词间空格也会被
     * 逐字拆开（两行档 maxLines=2 时涨跌幅会被整个挤掉），那比字小难看得多。
     * 6% 够吸收「桌面的字体和这里不是同一款」的差异，代价只是字小一点点。
     */
    private static final float TEXT_FIT_SAFETY = 0.94f;

    /** 字号上下限：低于 SP_MIN 的字糊成一团，不如不写；上限只用来挡住超大格子 */
    private static final float SP_MIN = 6f;
    private static final float SP_MAX = 28f;
    /** 名称那一行的字号下限：为了把完整的名字摆进去，允许比 SP_MIN 再小一档 */
    private static final float SP_NAME_MIN = 4.5f;
    /**
     * 名称能缩到的下限（比 {@link #SP_NAME_MIN} 再低一档）。
     *
     * 名称优先于涨跌幅，也优先于字号：格子小到摆不下全名时，先把字号一路缩到这里，
     * 而不是改去显示涨跌幅、截掉半个名字、或者干脆留一块纯色 —— 涨跌本来就用底色浓度
     * 表达着，认得出是哪只票才是这块格子最要紧的信息。缩到这一档还摆不下才算真放不下。
     * {@link #fitLabel} 那道闸也拿它当底线：按实测缩到摆得下为止，缩过头就整格不写字。
     */
    private static final float SP_NAME_FLOOR = 3f;
    /** 宽高比到这个倍数以上才算「方块比较宽」，名称与涨跌幅排同一行 */
    private static final float SAME_LINE_ASPECT = 1.5f;
    /** 竖排的两种尝试：先带涨跌幅，放不下再只叠名字 */
    private static final boolean[] STACK_TRIES = {true, false};
    /** 宽不到高的一半算「竖长」：这种格子先试竖排，别急着摆横的两行 */
    private static final float STACK_ASPECT = 0.5f;
    /** 面积 → 字号的系数：40dp 见方的格子约 14sp，见 {@link #areaSp} */
    private static final float AREA_SP_GAIN = 2.2f;
    /**
     * 字号再往下压的起点与力度，见 {@link #areaFade}：
     * 摆到这么大（约 50dp 见方）以上的格子，字不再跟着面积一路放大。
     */
    private static final float AREA_FADE_REF_DP2 = 2500f;
    private static final float AREA_FADE = 0.15f;
    /** 格子四周留白（dp）：文字不贴着色块边缘，也不受 setStateListAnimator 之类的挤压 */
    private static final float TILE_PAD_DP = 6f;


    /** goAsync 的兜底超时：网络异常时不至于一直挂着广播 */
    private static final long TIMEOUT_MS = 30000L;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions);
        // 尺寸变了整套马赛克要重摆：格子的宽高是写死的像素，不重摆就还是旧尺寸那一片
        sMaps.remove(appWidgetId);
        sMapSizes.remove(appWidgetId);
        // 立刻按回调给的这份新尺寸画一遍：那一瞬间这份一定是最新的
        paint(context, manager, appWidgetId, newOptions);
        // 数据还新鲜就到此为止，onUpdate 只会把同一版再画一遍
        if (sRows == null || System.currentTimeMillis() - sStamp >= STALE_MS) {
            onUpdate(context, manager, new int[]{appWidgetId});
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        if (appWidgetIds == null) return;
        for (int id : appWidgetIds) {
            sMaps.remove(id);
            sMapSizes.remove(id);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);      // 系统广播（APPWIDGET_UPDATE 等）按默认分发
        if (intent == null || !ACTION_REFRESH.equals(intent.getAction())) return;
        if (intent.getBooleanExtra(EXTRA_FORCE, false)) sStamp = 0L;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        int[] ids;
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            ids = new int[]{id};
        } else {
            ids = manager.getAppWidgetIds(new ComponentName(context, MarketHeatWidgetProvider.class));
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

        boolean fresh = sRows != null
                && System.currentTimeMillis() - sStamp < STALE_MS;
        if (fresh) {
            // 数据没换也要重画一次：尺寸可能刚变过（见 onAppWidgetOptionsChanged），
            // 而那棵排版树是按尺寸摆的，不重摆就还是旧尺寸下的那一片
            for (int id : appWidgetIds) {
                paint(context, manager, id);
            }
            for (int i = 0; i < appWidgetIds.length; i++) batch.tick();
            return;
        }
        // 手上有旧榜单时不再特意画一遍旧的：取数回来马上又画一遍新的，那一闪反而更晃眼，
        // 而旧榜单本来就还在屏幕上（RemoteViews 不更新它就还在）。只有真是一片空白
        // （刚放上去 / 进程重启）才先画个占位顶着
        if (sRows == null || sRows.isEmpty()) {
            for (int id : appWidgetIds) {
                paint(context, manager, id);
            }
        }
        fetch(context, manager, appWidgetIds, batch);
    }

    // ------------------------------------------------------------------
    // 刷新
    // ------------------------------------------------------------------

    /** 行情页取数成功后调用：让桌面上的这类部件跟着刷新 */
    public static void pushUpdate(Context context) {
        if (context == null) return;
        context.sendBroadcast(new Intent(context, MarketHeatWidgetProvider.class)
                .setAction(ACTION_REFRESH));
    }

    /**
     * 取数并重新画所有实例。
     *
     * 缓存失效才真去取数：已经有新鲜榜单时，重绑 / intent 触发的这次更新直接画缓存就行，
     * 否则一次 lifecycle 里要连着拉好几遍接口，桌面和流量都不划算。
     */
    private static void fetch(final Context context, final AppWidgetManager manager,
                              final int[] ids, final Batch batch) {
        if (!sPending.compareAndSet(false, true)) {
            // 已经在拉了：这次的广播算完事，等那一次回来会把所有实例一起重画
            for (int i = 0; i < ids.length; i++) {
                if (batch != null) batch.tick();
            }
            return;
        }
        QuoteSource.fetchTopCaps(TOP_N, new QuoteSource.CapListCallback() {
            @Override
            public void onResult(List<CapRow> rows) {
                if (rows != null && !rows.isEmpty()) {
                    sRows = new ArrayList<>(rows);
                    sError = null;
                    sStamp = System.currentTimeMillis();
                } else if (sRows == null) {
                    sError = "暂无市值数据";
                }
                settle(context, manager, ids, batch);
                // 家数另走一趟：它要拉全市场，比榜单慢，让它到点了自己再重画一次，
                // 这样热力图先出来，不必陪着等
                fetchBreadth(context, manager, ids);
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "heat fetch failed: " + message);
                // 旧榜单还留着：照旧画出来，只在状态行标一句，别一失败就整块空白
                sError = message;
                settle(context, manager, ids, batch);
            }
        });
    }

    /**
     * 全市场涨跌家数：单独一趟，回来再重画一次把状态行上的家数补上。
     * 失败也不影响热力图 —— 那一行退回「取数中」而已。
     */
    private static void fetchBreadth(final Context context, final AppWidgetManager manager,
                                     final int[] ids) {
        QuoteSource.fetchMarketBreadth(new QuoteSource.BreadthCallback() {
            @Override
            public void onResult(QuoteSource.Breadth breadth) {
                sBreadth = breadth;
                settle(context, manager, ids, null);
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "heat breadth failed: " + message);
            }
        });
    }

    private static void settle(Context context, AppWidgetManager manager, int[] ids, Batch batch) {
        sPending.set(false);
        for (int id : ids) {
            try {
                // 数据换了，这个实例之前摆好的马赛克作废
                sMaps.remove(id);
                sMapSizes.remove(id);
                manager.updateAppWidget(id, views(context, manager, id));
            } catch (Throwable t) {
                Log.w(TAG, "heat widget render failed", t);
            }
            if (batch != null) batch.tick();
        }
    }

    /** 只画一次（骨架 / 已有缓存的场景） */
    private static void paint(Context context, AppWidgetManager manager, int widgetId) {
        paint(context, manager, widgetId, null);
    }

    private static void paint(Context context, AppWidgetManager manager, int widgetId, Bundle opts) {
        try {
            manager.updateAppWidget(widgetId, views(context, manager, widgetId, opts));
        } catch (Throwable t) {
            Log.w(TAG, "heat widget paint failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 排版：按每个实例的尺寸摆一棵树
    // ------------------------------------------------------------------

    /**
     * 这个实例要摆的那棵排版树；没数据就给 null，由空态占位顶上。
     *
     * 缓存要连同「摆它时用的尺寸」一起存：尺寸换了不能拿旧树顶 —— 那时每一刀朝哪边切
     * 是按另一个形状的矩形定的，摆到现在的框里就会歪出去。
     */
    static MarketTreemap.Node mapFor(Context context, int widgetId, int[] size, int tiles) {
        List<CapRow> rows = sRows;
        if (rows == null || rows.isEmpty() || context == null) return null;
        MarketTreemap.Node cached = sMaps.get(widgetId);
        int[] cachedSize = sMapSizes.get(widgetId);
        if (cached != null && cachedSize != null
                && cachedSize[0] == size[0] && cachedSize[1] == size[1]
                && cachedSize[2] == tiles) {
            return cached;
        }
        // 榜单按市值倒序存着，取前 N 只就是「最大的 N 只」（见 fetchTopCaps 收尾那次排序）
        List<CapRow> shown = rows.size() > tiles ? new ArrayList<>(rows.subList(0, tiles)) : rows;
        MarketTreemap.Node node = MarketTreemap.layout(shown,
                mapWidthPx(context, size), mapHeightPx(context, size));
        if (node == null) {
            sMaps.remove(widgetId);
            sMapSizes.remove(widgetId);
            return null;
        }
        sMaps.put(widgetId, node);
        sMapSizes.put(widgetId, new int[]{size[0], size[1], tiles});
        return node;
    }

    /**
     * 摆放用的矩形（px）：整数就够 —— 铺的时候是按整数像素切的，
     * 半个像素传下去只会在每一层的取整里来回漂。
     */
    private static int mapWidthPx(Context context, int[] size) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.max(1, Math.round((size[0] - HEAT_SIDE_DP - MAP_SAFETY_DP) * density));
    }

    /**
     * 摆放用的矩形有多高（px）：列表那块空间再往里收 {@link #MAP_SAFETY_DP}。
     *
     * 少摆这几像素不过底下留一道几乎看不见的空当，多摆几像素就压到下面的图例上去了 ——
     * 而每个格子的高都是写死的像素值，没有可伸缩的余地，两边权衡下来宁可少。
     */
    private static int mapHeightPx(Context context, int[] size) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.max(1, Math.round(
                listHeightPx(context, size) - MAP_SAFETY_DP * metrics.density));
    }

    /**
     * 列表可视高度（px）：部件高度减去 {@link #chromePx} 那圈东西。
     * treemap 是按这个矩形的形状切的，摆几格（{@link #tilesFor}）也用同一个数，
     * 格子数与摆放面积才对得上。
     */
    private static float listHeightPx(Context context, int[] size) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.max(1f, size[1] * metrics.density - chromePx(context, size[1]));
    }

    /**
     * 列表之外那圈还要多高（px）：死的那一圈（{@link #HEAT_CHROME_FRAME_DP}）加上标题那三行。
     *
     * 这三行是拿 Paint 真量出来的，不按字号折算着估 —— 之前折算出 45dp、实测只有 41dp，
     * 那几 dp 的差就是马赛克底下那道白：每个格子的高都是写死的像素值，没有可伸缩的余地，
     * 画矮了就只能空着。
     *
     * 部件被拉矮时按比例少算一点，别把热力图压成一条缝。
     */
    private static float chromePx(Context context, int hdp) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float text = lineHeightPx(paint, CHROME_TITLE_SP * metrics.scaledDensity, true)
                + 2f * lineHeightPx(paint, CHROME_LINE_SP * metrics.scaledDensity, false);
        float chrome = HEAT_CHROME_FRAME_DP * metrics.density + text;
        return Math.min(chrome, Math.max(26f, hdp * 0.42f) * metrics.density);
    }

    /**
     * 一行那么大号的字，在 TextView 里要占多高（px）。
     * 三行都留着默认的 includeFontPadding，所以取 fm.bottom - fm.top，不是 descent - ascent。
     */
    private static float lineHeightPx(Paint paint, float textPx, boolean bold) {
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        paint.setTextSize(Math.max(1f, textPx));
        Paint.FontMetrics fm = paint.getFontMetrics();
        return fm.bottom - fm.top;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /**
     * 整片马赛克：按排版树递归地把 group 与格子一个个 addView 进去，摆在 widthPx × heightPx 里。
     *
     * RemoteViews 没法在 inflate 之后凭空新建 View，但可以把一个 RemoteViews 加进另一个
     * RemoteViews（addView）—— 小块能穿插在大块之间、而不是被拉成一根根条带，靠的就是这一条：
     * 叶子是一片 widget_heat_tile，内部节点按那一刀的方向在 widget_heat_row / widget_heat_col
     * 里二选一，嵌套起来就是一棵子树。
     *
     * @return 可以直接塞进 widget_heat_map 的那片 View；没有东西可摆时给 null
     */
    static RemoteViews mapViews(Context context, int widgetId, MarketTreemap.Node root,
                                int widthPx, int heightPx) {
        if (root == null || context == null || widthPx <= 0 || heightPx <= 0) return null;
        return new MapBrush(context, widgetId * TILE_CODE_STRIDE).draw(root, widthPx, heightPx);
    }

    /**
     * 铺一棵子树时要带着的那些东西。
     *
     * 一部分是为了让递归不必传同一串参数（dp→px、字体缩放、调色板、缝宽），
     * {@code codes} 则是因为每一格的 PendingIntent 都得各不相同：Intent 的 extras 不参与相等比较，
     * 两只票的 Intent 只差 extras，requestCode 再撞上的话三十个格子会全都打开同一只票。
     * 起点带 widgetId，两个实例编号重叠时也不会串。
     */
    private static final class MapBrush {
        private final Context context;
        private final float density;
        private final float fontScale;
        private final int gapPx;
        private final MarketPalette palette;
        private int codes;

        MapBrush(Context context, int firstCode) {
            this.context = context;
            this.codes = firstCode;
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            this.density = metrics.density;
            // 系统字体缩放：SP 到 px 走的是它，不是 density —— 用户放大字体后别再把文字挤出去
            this.fontScale = metrics.scaledDensity;
            this.gapPx = SEAM_PX;
            this.palette = MarketPalette.of(context);
        }

        /** 这一棵子树铺好后的样子：叶子给一片格子，内部节点给一个嵌套的 group */
        RemoteViews draw(MarketTreemap.Node node, int widthPx, int heightPx) {
            widthPx = Math.max(1, widthPx);
            heightPx = Math.max(1, heightPx);
            // 先量一遍缝吃掉多少面积：缝是一层一层从边长里扣的，不先算清总量，
            // 就没法让每个格子只摊同一份折扣（见下面那个 draw）
            Map<MarketTreemap.Node, int[]> geo = new IdentityHashMap<>();
            int seams = measureSeams(node, widthPx, heightPx, geo);
            return draw(node, widthPx, heightPx, (double) widthPx * heightPx - seams, geo);
        }

        /**
         * 铺一棵子树：{@code budget} 是这棵子树里所有格子的面积之和（px²）。
         *
         * 面积要跟市值成比例，难处全在缝上：缝非占地方不可，而且是一层一层从边长里扣的 ——
         * 早先的做法是「先扣掉缝再按市值分剩下的」，于是每一个孩子都要摊一份缝，
         * 排在树深处的格子连摊好几层，实际面积比它那份市值小 3～5%，浅处那只才小 1%
         * （按同样的数据量：中位差 2%，最差 5%）。看着就是「小块的面积对不上它的市值」。
         *
         * 现在先把缝的总量从整块矩形里减掉，余下的这份预算按市值一路分到每片叶子：
         * 每个格子最终都拿到「市值份额 × 同一份预算」，缝的开销变成**所有格子同打一个折**
         * （约 2%），折算之后彼此之比仍严格等于市值之比。分到某一层时，这一段该占多长 =
         * （它那份预算 + 它内部那些缝的面积）/ cross —— 两者相加恰好等于这一层的边长减缝宽，
         * 所以既不溢出，也不会往里缩。
         */
        private RemoteViews draw(MarketTreemap.Node node, int widthPx, int heightPx,
                                 double budget, Map<MarketTreemap.Node, int[]> geo) {
            widthPx = Math.max(1, widthPx);
            heightPx = Math.max(1, heightPx);
            if (node.isLeaf()) return tile(node.row, widthPx, heightPx);

            boolean along = node.alongWidth;
            List<MarketTreemap.Node> kids = node.children;
            RemoteViews views = new RemoteViews(context.getPackageName(),
                    along ? R.layout.widget_heat_row : R.layout.widget_heat_col);
            int rootId = along ? R.id.widget_heat_row : R.id.widget_heat_col;
            int length = along ? widthPx : heightPx;
            // cross 用当前实际的那一维：量缝那次走的是另一套尺寸（按市值分的），
            // 拿它当这一维会有几个像素的出入 —— 最右边那一列就差这几个像素合不上缝
            int cross = along ? heightPx : widthPx;
            int[] g = geo.get(node);
            int seam = g != null ? g[1] : gapPx;
            int gap = seam * Math.max(0, kids.size() - 1);
            // 缝要留，但不能把孩子挤没：塞不下就把缝整条免掉。
            // 只把预算里的 gap 夹小、插缝时还按原样插的话，这一排就会比 length 长出那几个
            // 像素（k 个孩子各 1px + k-1 条缝）—— 量缝那次判得出（见 measureSeams），
            // 这里摆的是另一套尺寸（按面积预算分出来的），得自己再判一次
            if (length - gap < kids.size()) {
                gap = 0;
                seam = 0;
            }
            int spare = Math.max(1, length - gap);
            double[] targets = new double[kids.size()];
            double[] share = new double[kids.size()];
            for (int i = 0; i < kids.size(); i++) {
                share[i] = Math.max(0d, budget) * Math.max(0f, kids.get(i).weight);
                int[] gi = geo.get(kids.get(i));
                targets[i] = (share[i] + (gi == null ? 0d : gi[2])) / Math.max(1, cross);
            }
            int[] sizes = shares(kids, spare, targets);
            for (int i = 0; i < kids.size(); i++) {
                if (i > 0 && seam > 0) views.addView(rootId, seam(along, seam, cross));
                int piece = sizes[i];
                views.addView(rootId, draw(kids.get(i),
                        along ? piece : cross, along ? cross : piece, share[i], geo));
            }
            return views;
        }

        /**
         * 走一遍这棵树，把每个节点的 {@code [cross, seam, 子树内部的缝面积]} 记下来，
         * 并返回这棵子树内部所有缝的面积（px²）。
         *
         * 缝占多大取决于那一层有多宽（cross），不先走一遍就不知道整棵树一共被缝吃掉多少。
         * 这里的各段长度按市值分（与摆放规则同一套 {@link #shares}），只用来定几何 ——
         * 真正摆的时候各段按面积预算分，两者相差不到一像素，缝面积也就差不到一像素。
         */
        private int measureSeams(MarketTreemap.Node node, int widthPx, int heightPx,
                                 Map<MarketTreemap.Node, int[]> geo) {
            if (node.isLeaf()) return 0;
            boolean along = node.alongWidth;
            int length = Math.max(1, along ? widthPx : heightPx);
            int cross = Math.max(1, along ? heightPx : widthPx);
            List<MarketTreemap.Node> kids = node.children;
            int seam = gapPx;
            int spare = length - gapPx * Math.max(0, kids.size() - 1);
            if (spare < kids.size()) {          // 连一格一像素都分不出来了就别留缝
                spare = length;
                seam = 0;
            }
            int[] sizes = shares(kids, spare, null);
            int inside = seam * Math.max(0, kids.size() - 1) * cross;
            for (int i = 0; i < kids.size(); i++) {
                inside += measureSeams(kids.get(i),
                        along ? sizes[i] : cross, along ? cross : sizes[i], geo);
            }
            geo.put(node, new int[]{cross, seam, inside});
            return inside;
        }

        /** 一片格子：面积就靠这两个像素值 —— TextView.setWidth/setHeight 写死最小与最大那个尺寸 */
        private RemoteViews tile(CapRow row, int widthPx, int heightPx) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_heat_tile);
            int id = R.id.widget_heat_tile;
            int fill = fillColor(palette, row.changePercent);
            // 留白得真写进 View：算字号时减掉的那圈若不真留出来，文字就贴着色块边缘、
            // 甚至压到格子之间的缝上 —— 看着就是字跑到方块外面去了
            int pad = tilePadPx(widthPx, heightPx);
            TileLabel label = tileLabel(row, Math.max(1f, widthPx - 2f * pad),
                    Math.max(1f, heightPx - 2f * pad), density, fontScale);
            views.setViewPadding(id, pad, pad, pad, pad);
            views.setInt(id, "setWidth", Math.max(1, widthPx));
            views.setInt(id, "setHeight", Math.max(1, heightPx));
            views.setInt(id, "setBackgroundColor", fill);
            views.setTextViewText(id, label.text);
            views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, label.sp);
            // 行数按内容收住：中文没有词间空格，TextView 会逐字折行，留着宽的 maxLines 就会给你拆成两行
            views.setInt(id, "setMaxLines", label.lines);
            views.setTextColor(id, tileTextColor(palette, Color.alpha(fill)));
            views.setOnClickPendingIntent(id, stockIntent(context, row, codes++));
            return views;
        }

        /**
         * 这一格四周留白多少（px）：文字不贴色块边缘，也不压到格子之间的缝上。
         *
         * 小格子按比例收，而且收得更狠 —— 固定 {@link #TILE_PAD_DP} 的话，扁条那样的格子
         * （二十来像素高）会被上下留白吃掉一半，本来还摆得下一行名字的，就这么空了。
         * 上下（左右）加起来不超过短边的三分之一。
         */
        private int tilePadPx(int widthPx, int heightPx) {
            int want = Math.round(TILE_PAD_DP * density);
            int limit = Math.min(widthPx, heightPx) / 6;
            return Math.max(0, Math.min(want, limit));
        }

        /** 相邻两格之间那条缝：一片没底色也没字的空 View，尺寸按摆放方向给 */
        private RemoteViews seam(boolean alongWidth, int thicknessPx, int crossPx) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_heat_tile);
            int id = R.id.widget_heat_tile;
            views.setInt(id, "setWidth", Math.max(1, alongWidth ? thicknessPx : crossPx));
            views.setInt(id, "setHeight", Math.max(1, alongWidth ? crossPx : thicknessPx));
            return views;
        }

        /**
         * 按 weight 把 lengthPx 切成整数像素：先往下取整，再一像素一像素地把差额配回去。
         *
         * 各段相加必须恰好等于 lengthPx —— 每段各自四舍五入会多出半像素，几十段加起来就把
         * 最右边那格推出边框（以前一个个 setWidth 时踩过）；反过来只往下取整又会越积越多，
         * 右边留一道白缝。
         *
         * 配平是双向的：每段先保底 1px（画不出来的格子不如留一根线），而一堆份额极小的孩子
         * 挤在同一条边上时，这个保底能把总量撑过 lengthPx —— 那时得从最宽的几段里扣回来，
         * 只补不扣的话，这一段就一路戳出右边框。
         *
         * @param targets 各段该占的理想长度；为 null 时按市值 {@code weight} 分。
         *                摆放时传的是由面积预算折算出来的长度（见那个私有的 draw）
         */
        private static int[] shares(List<MarketTreemap.Node> kids, int lengthPx, double[] targets) {
            int n = kids.size();
            int[] out = new int[n];
            if (n == 0 || lengthPx <= 0) return out;
            double total = 0d;
            for (int i = 0; i < n; i++) {
                total += targets != null ? Math.max(0d, targets[i]) : Math.max(0f, kids.get(i).weight);
            }
            float[] frac = new float[n];
            int used = 0;
            for (int i = 0; i < n; i++) {
                double want0 = targets != null ? Math.max(0d, targets[i])
                        : Math.max(0f, kids.get(i).weight);
                double share = total > 0d ? want0 / total : 1d / n;
                float want = (float) (lengthPx * share);
                out[i] = Math.max(1, (int) want);
                frac[i] = want - (int) want;
                used += out[i];
            }
            int left = lengthPx - used;
            for (; left != 0; ) {
                int best = -1;
                for (int i = 0; i < n; i++) {
                    if (frac[i] < 0f) continue;              // 这一段已经配过，不再参与下一轮
                    if (left < 0 && out[i] <= 1) continue;   // 要往回扣，只剩 1px 的段不能动
                    // 补：给余数最大的那段；扣：拿最宽的那段 —— 两边都挑改动最不显眼的那一段
                    if (best < 0 || (left > 0 ? frac[i] > frac[best] : out[i] > out[best])) best = i;
                }
                if (best < 0) break;      // 配不动了：每段都只剩 1px / 都配过一轮
                if (left > 0) {
                    out[best]++;
                    left--;
                } else {
                    out[best]--;
                    left++;
                }
                frac[best] = -1f;
            }
            if (left != 0) {
                // 上面那个循环可能提前收手（每段都配过一轮，差额还没补完）。这时差额不能就这么
                // 留着：各段相加必须恰好等于 lengthPx，短一像素这一排就露一条黑缝、长一像素就
                // 戳出边框（嵌套好几层，一条条黑缝叠起来就是「马赛克不紧凑」）。摊给最宽的那段 ——
                // 它相对误差最小，多一两像素看不出来
                int widest = 0;
                for (int i = 1; i < n; i++) {
                    if (out[i] > out[widest]) widest = i;
                }
                out[widest] = Math.max(1, out[widest] + left);
            }
            return out;
        }
    }

    /** 格子的底色 = 涨跌色 + 涨跌幅对应的浓度（0% 也留一点，全透明会看成空洞） */
    private static int fillColor(MarketPalette palette, double percent) {
        int base = percent > 0.0001 ? palette.up
                : (percent < -0.0001 ? palette.down : palette.flat);
        float magnitude = Math.min(1f, (float) (Math.abs(percent) / 5f));
        int alpha = 48 + Math.round(180 * magnitude);
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base));
    }

    /**
     * 「写什么」和「写多大」必须一次算出来：分开算就会打架 ——
     * 按一套字号决定显示名称，再按另一个函数算字号，结果不是字被裁掉就是留一大片空。
     *
     * 降级顺序由方块形状决定，每档都先用 {@link #tileSp} 判「这个字号下内容还放得下吗」：
     *   0. 名称 / 市值 / 涨跌 三行 —— 只有面积吃得住的方块够格，见 {@link #linesLabel}；
     *   1. 竖长格子（宽不到高的一半）先试名称竖排 —— 它的横行都被宽度卡死；
     *   2. 名称一行、涨跌一行 —— 能拆成两行就两行，这是最清楚的排法；
     *   3. 「名称 涨跌」同一行 —— 只有厚度凑不出两行的扁方块才退到这里；
     *   4. 只名称全名（横排）—— 字号缩到 {@link #SP_NAME_FLOOR} 也要摆下全名；
     *   5. 名称竖排（不够竖长的格子排到这里）；
     *   6. 名字截到摆得下的字数 —— 名字优先，见下；
     *   7. 只涨跌幅；
     *   8. 什么都放不下就留纯色块（还能点，面积本身也是信息）。
     *
     * 名称优先于涨跌幅、也优先于字号：块小到摆不下全名时，先把字号一路缩到
     * {@link #SP_NAME_FLOOR}（见第 4 档），再往下才截字，始终不改去显示涨跌幅 ——
     * 涨跌幅度底色浓度已经表达了，认得出是哪只票才是这块格子最要紧的信息。
     * 名称一律不折行：中文没有词间空格，一旦让 TextView 自己排版，它会逐字给你拆开。
     */
    /**
     * 一格最终写什么：先 {@link #labelFor} 排一版，再 {@link #fitLabel} 收住。
     *
     * @param widthPx / thicknessPx 已经是把留白扣掉之后的那块地方（见 {@code tilePadPx}）
     */
    private static TileLabel tileLabel(CapRow row, float widthPx, float thicknessPx,
                                       float density, float fontScale) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TEXT_PROBE_PX);
        // includeFontPadding=false 时一行就是 ascent+descent，用它当行高
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineRatio = Math.max(0.5f, (fm.descent - fm.ascent) / TEXT_PROBE_PX);
        float availW = Math.max(1f, widthPx);
        float availH = Math.max(1f, thicknessPx);
        TileLabel label = labelFor(row, availW, availH, paint, lineRatio, density, fontScale);
        return fitLabel(label, availW, availH, paint, lineRatio, fontScale);
    }

    /**
     * 收尾那道闸：摆不下就按实测把字号缩到摆得下为止 —— 名称宁可小一点也不许戳出方块。
     *
     * 字号是按 Paint 的字体度量反解的，而格子上真正排版的是桌面的 TextView —— 两者未必是
     * 同一款字体。所以收尾再量一次：宽或高哪头超了就按超出比例缩字号（「可以牺牲字号」），
     * 缩到 {@link #SP_NAME_FLOOR} 还摆不下才整格不写字（面积与颜色照样在，格子也还能点）。
     */
    private static TileLabel fitLabel(TileLabel label, float availW, float availH, Paint paint,
                                      float lineRatio, float fontScale) {
        if (label.text.length() == 0) return label;
        float[] box = labelBox(label, paint, lineRatio, fontScale);
        float scale = Math.min(availW / Math.max(1f, box[0]), availH / Math.max(1f, box[1]));
        float sp = label.sp * Math.min(1f, scale);
        return sp >= SP_NAME_FLOOR ? new TileLabel(label.text, sp, label.lines)
                : new TileLabel("", SP_MIN, 1);
    }

    /**
     * 这几行字按 label 的字号排出来占多大地方（px）： {@code [0]} 最宽那行的宽度、
     * {@code [1]} 各行行高之和。
     *
     * 逐行各按自己的字号量 —— 三行写法（{@link #linesLabel}）里后两行是 RelativeSizeSpan
     * 压过的，全按基准字号算会把本来摆得下的格子误判成放不下、白白缩字号。
     */
    private static float[] labelBox(TileLabel label, Paint paint, float lineRatio, float fontScale) {
        float[] box = new float[]{1f, 0f};
        if (label.text.length() == 0) return box;
        String[] lines = label.text.toString().split("\n", -1);
        int at = 0;
        for (String line : lines) {
            float sizePx = label.sp * lineScale(label.text, at, at + line.length()) * fontScale;
            box[0] = Math.max(box[0], textWidthPx(paint, line) / TEXT_PROBE_PX * sizePx);
            box[1] += lineRatio * sizePx;
            at += line.length() + 1;
        }
        return box;
    }

    /** 这一行相对基准字号放大了多少倍；没挂 span 的行就是基准 */
    private static float lineScale(CharSequence text, int start, int end) {
        if (!(text instanceof Spanned)) return 1f;
        RelativeSizeSpan[] spans = ((Spanned) text).getSpans(start, end, RelativeSizeSpan.class);
        return spans.length > 0 ? spans[0].getSizeChange() : 1f;
    }

    private static TileLabel labelFor(CapRow row, float availW, float availH, Paint paint,
                                      float lineRatio, float density, float fontScale) {
        String percent = percentText(row.changePercent);
        String name = TextUtils.isEmpty(row.name) ? row.code : row.name;
        float sp;

        // 0) 够格的大方块才这样排：名称一行、总市值一行、涨跌幅一行。见 {@link #linesLabel}。
        //    竖长的除外：那种格子的字号是被宽度钉死的，多给它两行只会让它更空，那是竖排的地盘
        String cap = row.totalCap > 0d ? Snapshot.cap(row.totalCap) : "";
        if (!TextUtils.isEmpty(cap) && !"-".equals(cap) && availW >= availH * STACK_ASPECT) {
            TileLabel wide = linesLabel(new String[]{name, cap, percent}, availW, availH, paint,
                    lineRatio, density, fontScale, SP_MIN);
            if (wide != null) return wide;
        }

        // 1) 竖长的格子（宽不到高的一半）先看竖排：这种格子里每一横行都被宽度卡死 ——
        //    30x100dp 两行只能给 5.9sp，同样这只票竖排能给到 20sp，差别就在用不用得上那份高度
        if (availW < availH * STACK_ASPECT) {
            TileLabel stacked = stackedLabel(name, percent, availW, availH, paint, lineRatio,
                    density, fontScale);
            if (stacked != null) return stacked;
        }

        // 2) 能拆成两行就两行：名称一行、涨跌幅一行是最清楚的排法，优先于挤成一行。
        //    两行也各按自己的行宽取字号（见 linesLabel）：「+1.8%」比四个字的名称窄得多，
        //    统一字号时它得跟着名称一起变小
        TileLabel two = linesLabel(new String[]{name, percent}, availW, availH, paint, lineRatio,
                density, fontScale, SP_NAME_MIN);
        if (two != null) return two;

        // 3) 高度实在凑不出两行（极扁的方块）才把名称与涨跌幅挤到同一行
        if (availW >= availH * SAME_LINE_ASPECT) {
            String same = name + " " + percent;
            sp = tileSp(availW, availH, new String[]{same}, paint, lineRatio, density, fontScale);
            if (sp >= SP_MIN) return new TileLabel(same, clampSp(sp, SP_MIN), 1);
        }

        // 4) 5) 只剩名称时，横排与竖排两个都算一遍，取字更大的那个。
        //    字号能缩到 {@link #SP_NAME_FLOOR} 之后，横排几乎总能挤进去，就会抢在竖排前头 ——
        //    而同样这只票竖排常常给得出大得多的字（横排被整个名字占满、竖向却只用掉几分之一）。
        //    谁的字大用谁：可以牺牲字号，但不能白白浪费那一格的高度
        TileLabel flat = null;
        sp = tileSp(availW, availH, new String[]{name}, paint, lineRatio, density, fontScale);
        if (sp >= SP_NAME_FLOOR) flat = new TileLabel(name, clampSp(sp, SP_NAME_FLOOR), 1);
        TileLabel stacked = stackedLabel(name, percent, availW, availH, paint, lineRatio,
                density, fontScale);
        if (flat != null && (stacked == null || flat.sp >= stacked.sp)) return flat;
        if (stacked != null) return stacked;

        // 6) 连竖排都放不下：按最小可读字号摆得下几个字就摆几个（至少两个）。
        //    半截名字也比只剩涨跌幅有用 —— 涨跌本来就用底色浓度表达着，认得出是哪只票更要紧
        // 这一档的字号是被 {@link #SP_NAME_MIN} 钉死的，没有别处可以退 —— 所以宽高都照
        // {@link #TEXT_FIT_SAFETY} 留够余量再判，不能像上面几档那样靠调字号把余量让出来
        if (availH * TEXT_FIT_SAFETY >= SP_NAME_MIN * lineRatio * fontScale) {
            // 按这个名字里最宽的那个字算，而不是拿「名」当样本 —— 名字里夹个全角符号或
            // 大写字母时，按样本算会多截出一个字，那一个字就戳到格子外面去了
            float perCharPx = widestCharPx(paint, name) / TEXT_PROBE_PX
                    * SP_NAME_MIN * fontScale;
            int chars = (int) (availW * TEXT_FIT_SAFETY / Math.max(1f, perCharPx));
            if (chars >= 2) {
                return new TileLabel(name.substring(0, Math.min(chars, name.length())),
                        SP_NAME_MIN, 1);
            }
        }

        // 7) 只剩得下涨跌幅
        sp = tileSp(availW, availH, new String[]{percent}, paint, lineRatio, density, fontScale);
        if (sp >= SP_MIN) return new TileLabel(percent, clampSp(sp, SP_MIN), 1);

        return new TileLabel("", SP_MIN, 1);
    }

    /**
     * 一格里竖着排几行，每行各取自己能到的最大字号（名称 / 市值 / 涨跌幅，或名称 / 涨跌幅）。
     *
     * 不用统一字号：统一的要按最宽那行来，而「3.02万亿」比四个字的名称还宽、「+1.8%」又比它窄
     * 得多 —— 按最宽那行定，其余几行都白白跟着变小。这里名称先拿自己能到的最大字号
     * （受自己的行宽、面积给的上限、整格高度三者约束），剩下的高度由后面几行均分，
     * 每行再受自己的行宽约束。各行都不许压过名称：这块格子的主角是「哪只票」，不是那些数字。
     *
     * 三行的来历：大方块里两行字太单薄 —— 横向已被四个字占满，竖向却只用掉一半多，
     * 补上市值那一行正好填满；面积本身已排出市值大小，这里给出具体数字，
     * 才说得清它比旁边那块大多少。
     *
     * @param minSp 每一行都得达到的字号，任一行挤到读不出就返回 null，交给调用方继续降级
     */
    private static TileLabel linesLabel(String[] lines, float availW, float availH, Paint paint,
                                        float lineRatio, float density, float fontScale,
                                        float minSp) {
        int n = lines.length;
        if (n < 2) return null;
        // 名称：不超过自己的行宽，也不超过面积给的上限与整格高度
        float firstPx = Math.min(lineSpPx(paint, lines[0], availW), availH * TEXT_FIT_SAFETY);
        float areaPx = areaSp(availW / density, availH / density) * fontScale;
        firstPx = Math.min(firstPx, areaPx);
        // 大块上的名字收一点，后面几行的分量也跟着回来（见 areaFade）
        firstPx *= areaFade(availW / density, availH / density);
        // 剩下这点高度由后面几行分，各行的行高之和不能超过它
        float restPx = availH * TEXT_FIT_SAFETY - lineRatio * firstPx;
        float budgetPx = restPx / ((n - 1) * lineRatio);
        if (restPx <= 0f || firstPx / fontScale < minSp) return null;
        float[] px = new float[n];
        px[0] = firstPx;
        for (int i = 1; i < n; i++) {
            px[i] = Math.min(Math.min(lineSpPx(paint, lines[i], availW), budgetPx), firstPx);
            if (px[i] / fontScale < minSp) return null;
        }
        SpannableString text = new SpannableString(TextUtils.join("\n", lines));
        // 基准字号就是名称那一行的，其余各行按 RelativeSizeSpan 相对它缩放：它跟着
        // TextView 的 textSize 走，用户放大系统字体时不会出现某两行没跟上的情况
        int at = 0;
        for (int i = 1; i < n; i++) {
            at += lines[i - 1].length() + 1;
            text.setSpan(new RelativeSizeSpan(px[i] / firstPx), at, at + lines[i].length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return new TileLabel(text, clampSp(firstPx / fontScale, minSp), n);
    }

    /**
     * 这一行要在 availW 里放下，它的字号最多能到多少（px）。
     * 同样要留 {@link #TEXT_FIT_SAFETY} 那点余量：贴着换行阈值摆满的话，
     * 桌面字体稍微宽一点就被拆行或截成省略号。
     */
    private static float lineSpPx(Paint paint, String line, float availW) {
        return availW * TEXT_PROBE_PX / Math.max(1f, textWidthPx(paint, line)) * TEXT_FIT_SAFETY;
    }

    /**
     * 一行字排出来有多宽（{@link #TEXT_PROBE_PX} 字号下，px）—— 量宽度只认这一个口子。
     *
     * 不能只信 {@link Paint#measureText}：它只按 Paint 自己那一个 typeface 量，不做字体回退。
     * 默认的拉丁字体里没有汉字，量出来是缺字（missing glyph）的宽度 ≈0.56em；而桌面上那个
     * TextView 排版时会回退到中文字体，汉字实打实占 1em。按 0.56em 反解出来的字号宽出近
     * 八成，四个字的名字排到第三行还挂个省略号 —— 截图里「招商银/行…」「长鑫科/技…」
     * 「农业银/行」全是这么来的。
     *
     * 所以两个都算、取大的：实测那一个（拉丁名本来就准）+「全角 1em、其余 0.6em」的估算
     * （含汉字时它是上界）。估的那一份只会让字小一点点；算宽了名字就要折行、挂省略号，
     * 那比字小难看得多。
     */
    private static float textWidthPx(Paint paint, CharSequence line) {
        float measured = paint.measureText(line, 0, line.length());
        float estimate = 0f;
        for (int i = 0; i < line.length(); ) {
            int cp = Character.codePointAt(line, i);
            estimate += (isWide(cp) ? WIDE_EM : NARROW_EM) * TEXT_PROBE_PX;
            i += Character.charCount(cp);
        }
        return Math.max(measured, estimate);
    }

    /** 这个码位是不是全角字（汉字、假名、谚文、全角标点）：它们占满一个 em */
    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)          // 谚文字母
                || (cp >= 0x2E80 && cp <= 0xA4CF)      // CJK 部首 / 假名 / 汉字 / 注音
                || (cp >= 0xAC00 && cp <= 0xD7A3)      // 谚文音节
                || (cp >= 0xF900 && cp <= 0xFAFF)      // CJK 兼容汉字
                || (cp >= 0xFE30 && cp <= 0xFE4F)      // CJK 兼容标点
                || (cp >= 0xFF00 && cp <= 0xFF60)      // 全角 ASCII
                || (cp >= 0xFFE0 && cp <= 0xFFE6)      // 全角符号
                || (cp >= 0x20000 && cp <= 0x3FFFD);   // CJK 扩展 B 及以后
    }

    /** 这个名字里最宽的那个字有多宽（{@link #TEXT_PROBE_PX} 字号下，px） */
    private static float widestCharPx(Paint paint, String name) {
        float widest = textWidthPx(paint, "名");
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            widest = Math.max(widest, textWidthPx(paint, new String(Character.toChars(cp))));
            i += Character.charCount(cp);
        }
        return widest;
    }

    /**
     * 名称竖排的候选：一个字一行，末了挂一行涨跌幅；两者取能让字更大的那个。
     *
     * @return 这个格子里摆得下的竖排方案，一行都排不下就给 null，交给调用方继续降级
     */
    private static TileLabel stackedLabel(String name, String percent, float availW, float availH,
                                          Paint paint, float lineRatio, float density,
                                          float fontScale) {
        for (boolean withPercent : STACK_TRIES) {
            String[] stacked = stackLines(name, withPercent ? percent : null);
            float sp = tileSp(availW, availH, stacked, paint, lineRatio, density, fontScale);
            // 带上涨跌幅就要求它自己也读得出：那串百分比比单字宽得多，为塞它把名字压到
            // 4sp 不划算（20x100dp：带它 4.7sp，不带 13.2sp），宁可只叠字。
            // 只叠字那一档则跟着 {@link #SP_NAME_FLOOR} 一缩到底：名字缩得小也比没名字强
            float gate = withPercent ? SP_MIN : SP_NAME_FLOOR;
            if (sp >= gate) {
                return new TileLabel(TextUtils.join("\n", stacked),
                        clampSp(sp, gate), stacked.length);
            }
        }
        return null;
    }

    /**
     * 竖排用的行数组：名称一个字一行，末了可再挂一行涨跌幅。
     * 每行只有一个字，横向只吃一个字的宽度，窄格子就能拿高度换字号。
     * 按 codePoint 切而不是 charAt：名字里夹个符号也不会被劈成乱码。
     */
    private static String[] stackLines(String name, String tail) {
        List<String> out = new ArrayList<>(name.length() + 1);
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            out.add(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        if (tail != null) out.add(tail);
        return out.toArray(new String[0]);
    }

    /**
     * 一个格子最终写上的内容：文字 + 字号 + 几行。
     * 行数要一并给出：渲染时按它收住 maxLines，中文才不会被 TextView 自动折行。
     */
    private static final class TileLabel {
        final CharSequence text;
        final float sp;
        final int lines;

        TileLabel(CharSequence text, float sp, int lines) {
            this.text = text;
            this.sp = sp;
            this.lines = lines;
        }
    }

    /**
     * 面积给的字号（见 {@link #areaSp}）与「这几行文字还放得下」取小。
     * 少了面积项，细长格子会被宽高两项锁死在同一个字号上；少了放得下这一项，字就顶出格子。
     * 返回值可能低于 {@link #SP_MIN}，那意味着「这一档的内容在这么大的格子里放不下」，
     * 交回 {@link #tileLabel} 决定降级。
     */
    private static float tileSp(float availWPx, float availHPx, String[] lines, Paint paint,
                                float lineRatio, float density, float fontScale) {
        float fromArea = areaSp(availWPx / density, availHPx / density);
        float widestPx = 1f;
        for (String line : lines) widestPx = Math.max(widestPx, textWidthPx(paint, line));
        // 宽度与字号成正比：PROBE 字号下最宽那行多宽，就反解出可用宽度对应的字号
        float fromWidth = availWPx * TEXT_PROBE_PX / widestPx;
        float fromHeight = availHPx / (Math.max(1, lines.length) * lineRatio);
        float sp = Math.min(fromArea, Math.min(fromWidth, fromHeight) * TEXT_FIT_SAFETY / fontScale);
        // 大块上的字跟着收一点：面积先把话说完了，字号别再说一遍（见 {@link #areaFade}）
        sp *= areaFade(availWPx / density, availHPx / density);
        return Math.max(0f, sp);
    }

    /**
     * 夹到 [下限, SP_MAX]。下限必须跟着档位走：为了摆下完整名称而算出的字号可能低于
     * {@link #SP_MIN}，这时若还按 SP_MIN 抬回去，字就又顶出格子了 —— 正是要避免的事。
     */
    private static float clampSp(float sp, float min) {
        return Math.max(min, Math.min(SP_MAX, sp));
    }

    /**
     * 格子越大字越大，但取几何平均边长后再开一次方 —— 收益递减。
     *
     * 直接按面积的平方根（只开一次方）时，市值第一那只票的大格子和中等格子会拉开过大差距，
     * 而且常常在 15sp 附近就撞到上限，结果 40dp 和 150dp 的格子字号一模一样：看着就是"字不跟着方块走"。
     * 开两次方后：20dp≈10sp、40dp≈14sp、80dp≈20sp、150dp≈27sp。
     */
    private static float areaSp(float wDp, float hDp) {
        return (float) Math.sqrt(Math.sqrt(Math.max(1f, wDp * hDp))) * AREA_SP_GAIN;
    }

    /**
     * 面积越大的格子，字号往下压越多（返回 ≤ 1 的系数）。
     *
     * 面积已经在说「它有多大」了，字号要是再把同一件事放大一遍，市值第一那一块就成了
     * 一张贴在中间的大字报，边上几十只反倒成了背景 —— 挤在一起的那些才是今天的主角。
     * 所以过了参照面积之后让字号一路照旧往下走但走得更慢：比 50dp 见方小的格子一字不改
     * （那里本来就被四个字的行宽卡着），越往上走的块压得越多 ——
     * 80dp 见方约 -9%、120dp 见方约 -23%，看着就是「大块上的字比小块的大，但没大到抢戏」。
     */
    private static float areaFade(float availWDp, float availHDp) {
        float area = Math.max(1f, availWDp * availHDp);
        return (float) Math.min(1f, Math.pow(AREA_FADE_REF_DP2 / area, AREA_FADE));
    }

    private static String percentText(double percent) {
        return String.format(Locale.US, "%+.1f%%", percent);
    }

    /**
     * 浓色块上白字、淡色块上用主体文字色。
     * 字号跟着面积放大之后，淡底深色字还能看，浓底深色字就糊成一团了。
     */
    private static int tileTextColor(MarketPalette palette, int alpha) {
        return alpha >= 150 ? Color.WHITE : palette.text;
    }

    /**
     * 点某一格：把该标的交给行情页。
     *
     * requestCode 必须每格一个：Intent 的 extras 不参与 PendingIntent 的相等比较，
     * 两只票的 Intent 只差 extras，requestCode 再撞上就会被复用成同一个 —— 三十格会全都开同一只票。
     */
    private static PendingIntent stockIntent(Context context, CapRow row, int requestCode) {
        Intent intent = new Intent(context, MarketActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(MarketWidgetProvider.EXTRA_SOURCE, QuoteSource.AUTO);
        intent.putExtra(MarketWidgetProvider.EXTRA_SYMBOL, row.code.isEmpty() ? row.secid : row.code);
        intent.putExtra(MarketWidgetProvider.EXTRA_NAME, row.name);
        return PendingIntent.getActivity(context, requestCode, intent, flags());
    }

    /** 整块 RemoteViews：标题 → 一整片马赛克 → 图例 */
    private static RemoteViews views(Context context, AppWidgetManager manager, int widgetId) {
        return views(context, manager, widgetId, null);
    }

    /**
     * @param opts 刚收到的 options（尺寸变化回调里给的那份）。为 null 就现读一次 ——
     *             但回调里必须把新的那份传进来：有的启动器这一刻 {@code getAppWidgetOptions}
     *             还返回上一份，拿它摆就等于又按旧尺寸摆了一遍，看着就是「改了大小没反应」。
     */
    private static RemoteViews views(Context context, AppWidgetManager manager, int widgetId,
                                     Bundle opts) {
        List<CapRow> rows = sRows;
        // 尺寸每次都现量：马赛克与格数都按它算，拿旧的就会错一个实例
        int[] size = opts != null ? sizeDp(context, opts) : sizeDp(context, manager, widgetId);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_market_heat);
        views.setTextViewText(R.id.widget_title, context.getString(R.string.market_heat_widget_title));
        // 摆几格由这块部件的大小定（见 tilesFor）；取整份榜单时也不超过实际到手的条数
        int tiles = tilesFor(context, size);
        if (rows != null && rows.size() < tiles) tiles = rows.size();
        // 调试包里把「量到的尺寸 → 算出的格数」写在第二行最前面：
        // 拖大拖小之后这串数动不动，一眼就能分清是启动器没送新尺寸、还是我们这边没跟着算
        String meta = metaText(context, rows).toString();
        if (com.tsymiar.device2device.BuildConfig.DEBUG) {
            meta = size[0] + "x" + size[1] + "dp→" + tiles + "只 · " + meta;
        }
        views.setTextViewText(R.id.widget_meta, meta);
        views.setTextViewText(R.id.widget_legend, legendText(rows, tiles));
        views.setTextViewText(R.id.widget_heat_time, stampText());
        views.setTextColor(R.id.widget_title, color(context, R.color.market_widget_text));
        views.setTextColor(R.id.widget_refresh, color(context, R.color.market_widget_text_dim));
        views.setTextColor(R.id.widget_meta, color(context, R.color.market_widget_text_hint));
        views.setTextColor(R.id.widget_legend, color(context, R.color.market_widget_text_hint));
        views.setTextColor(R.id.widget_heat_time, color(context, R.color.market_widget_text_hint));
        views.setTextColor(R.id.widget_heat_empty, color(context, R.color.market_widget_text_hint));

        int wdp = size[0];
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 20, 9, 13));
        views.setTextViewTextSize(R.id.widget_refresh, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 26, 8, 10));
        views.setTextViewTextSize(R.id.widget_meta, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 30, 7, 9));
        views.setTextViewTextSize(R.id.widget_legend, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 30, 7, 9));
        views.setTextViewTextSize(R.id.widget_heat_time, TypedValue.COMPLEX_UNIT_SP,
                clampSp(wdp / 30, 7, 9));
        views.setTextViewText(R.id.widget_heat_empty, emptyText(context, rows));

        // 主体：摆一棵树，一次 addView 进去（每一格各自的点击在里面挂好）
        paintMap(context, widgetId, size, views, tiles);

        views.setOnClickPendingIntent(R.id.widget_root, openMarket(context, widgetId));
        views.setOnClickPendingIntent(R.id.widget_header, openMarket(context, widgetId));
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context, widgetId));
        return views;
    }

    /**
     * 把一整片马赛克铺进主体那一块。
     * 没有数据时不再有 setEmptyView 替我们切（那是 AdapterView 才有的），这里自己切换空态占位。
     */
    private static void paintMap(Context context, int widgetId, int[] size, RemoteViews views, int tiles) {
        MarketTreemap.Node root = mapFor(context, widgetId, size, tiles);
        RemoteViews map = mapViews(context, widgetId, root,
                mapWidthPx(context, size), mapHeightPx(context, size));
        // 先把上一次摆的那片清掉再铺新的 —— RemoteViews 每次刷新是「贴」到已有视图上的
        // （同一个布局 id 走 reapply），addView 只管往里加，不管里面原来有什么：
        // 不清的话一次刷新就多叠一层马赛克（取数 + 家数 + 行情页推送，一趟能叠三次），
        // 叠出来的那几层也就一并戳出边框。RemoteViews 里能清容器的只有 removeAllViews。
        views.removeAllViews(R.id.widget_heat_map);
        if (map == null) {
            views.setViewVisibility(R.id.widget_heat_map, View.GONE);
            views.setViewVisibility(R.id.widget_heat_empty, View.VISIBLE);
            return;
        }
        views.setViewVisibility(R.id.widget_heat_map, View.VISIBLE);
        views.setViewVisibility(R.id.widget_heat_empty, View.GONE);
        views.addView(R.id.widget_heat_map, map);
    }

    /**
     * 状态行：全市场的涨跌家数，数字按涨跌着色。
     *
     * 家数不能用榜单那几十只来数 —— 那是按市值取的前 N 名，数出来只有几十只在动，
     * 讲不出「今天大盘是不是普涨」。所以它是单独一趟请求拿的（见 fetchBreadth），
     * 还没回来时这一行先显示取数中，等它到家会再重画一次补上。
     * 更新时间不在这里：它挪到底部右侧去了。
     */
    private static CharSequence metaText(Context context, List<CapRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return sError == null ? context.getString(R.string.market_widget_loading) : sError;
        }
        if (sBreadth == null) {
            return sError == null ? context.getString(R.string.market_widget_loading) : sError;
        }
        MarketPalette palette = MarketPalette.of(context);
        int dim = color(context, R.color.market_widget_text_hint);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        tinted(sb, "涨 ", dim);
        tinted(sb, String.valueOf(sBreadth.up), palette.up);
        tinted(sb, "  跌 ", dim);
        tinted(sb, String.valueOf(sBreadth.down), palette.down);
        tinted(sb, "  平 ", dim);
        tinted(sb, String.valueOf(sBreadth.flat), dim);
        if (sError != null) {
            tinted(sb, "  " + sError, dim);
        }
        return sb;
    }

    /** 往一行里追加一段自带颜色的文字：RemoteViews 的 setTextViewText 收得下 Spannable */
    private static void tinted(SpannableStringBuilder sb, String text, int color) {
        int at = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), at, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * 底部右侧的时间：这批数据是几点几分取的。
     * 用 sStamp 而不是渲染时刻 —— 后者每次重画都在跳过一个数，看不出数据是新的还是旧的。
     */
    private static String stampText() {
        if (sStamp <= 0L) return "";
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(sStamp));
    }

    /** 底部说明：面积 / 颜色的含义 + 这个部件实际画了几只（跟着尺寸走，见 {@link #tilesFor}） */
    private static String legendText(List<CapRow> rows, int tiles) {
        if (rows == null || rows.isEmpty()) return "";
        return "面积=市值 · 颜色=涨跌 · 沪深京" + Math.min(tiles, rows.size()) + "强";
    }

    private static String emptyText(Context context, List<CapRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return sError != null && !TextUtils.isEmpty(sError)
                    ? sError : context.getString(R.string.market_widget_loading);
        }
        return context.getString(R.string.market_heat_widget_none);
    }

    /**
     * 部件当前的尺寸（dp）。options 里那两组数（MIN_* / MAX_*）各家启动器的语义并不统一：
     * 有的是「竖屏一套 / 横屏一套」，有的是「当前尺寸的下界 / 上界」。两种语义有一个共同的
     * 交集 —— **竖屏的部件总是又窄又高，横屏总是又宽又矮**。所以按形状取那两头：
     * 竖屏取窄的宽、高的高，横屏取宽的宽、矮的高。
     *
     * 反过来混着取就会出事：竖屏取了「窄宽 + 矮高」，这一片马赛克只铺中间一条、上下各空一大片；
     * 或者取了「宽宽 + 高高」，右边、底下直接戳出格子。treemap 里每个格子的像素都是写死的，
     * 没有可伸缩的余地 —— 尺寸给错就是整片错位，不会自己长回来。
     *
     * 两头只要有一头是 0（有的启动器只报一组），就拿另一头顶上，别掉到兜底尺寸上从头摆一遍。
     */
    private static int[] sizeDp(Context context, Bundle opts) {
        int minW = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int minH = opts == null ? 0 : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        int maxW = opts == null ? minW : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
        int maxH = opts == null ? minH : opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
        int loW = minW > 0 ? minW : maxW;
        int hiW = maxW > 0 ? maxW : minW;
        int loH = minH > 0 ? minH : maxH;
        int hiH = maxH > 0 ? maxH : minH;
        boolean portrait = context.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE;
        int w = portrait ? Math.min(loW, hiW) : Math.max(loW, hiW);
        int h = portrait ? Math.max(loH, hiH) : Math.min(loH, hiH);
        return new int[]{w > 0 ? w : SIZE_FALLBACK_DP, h > 0 ? h : SIZE_FALLBACK_DP};
    }

    private static int[] sizeDp(Context context, AppWidgetManager manager, int widgetId) {
        return sizeDp(context, manager == null ? null : manager.getAppWidgetOptions(widgetId));
    }

    /**
     * 这个尺寸的部件摆几格：可视面积 / {@link #TILE_AREA_DP2}，再夹到 [TILES_MIN, TOP_N]。
     *
     * 高度用 {@link #listHeightPx} 折回 dp（它已经把标题那几行扣掉了），
     * 再扣掉 {@link #MAP_SAFETY_DP} —— 摆的时候用的也是这两个数，格子数与摆放面积才对得上。
     */
    private static int tilesFor(Context context, int[] size) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float wDp = Math.max(0f, size[0] - HEAT_SIDE_DP - MAP_SAFETY_DP);
        float hDp = Math.max(0f, listHeightPx(context, size) / metrics.density - MAP_SAFETY_DP);
        int count = Math.round(wDp * hDp / TILE_AREA_DP2);
        return Math.max(TILES_MIN, Math.min(TOP_N, count));
    }

    /** 点空白进行情页：这里是一整屏的盘面，不带具体标的，进去看行情页本身 */
    private static PendingIntent openMarket(Context context, int widgetId) {
        Intent intent = new Intent(context, MarketActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, widgetId, intent, flags());
    }

    private static PendingIntent refreshIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, MarketHeatWidgetProvider.class)
                .setAction(ACTION_REFRESH);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        intent.putExtra(EXTRA_FORCE, true);
        return PendingIntent.getBroadcast(context, widgetId, intent, flags());
    }

    /** Android 12（targetSdk 31）要求显式声明 PendingIntent 的可变性 */
    private static int flags() {
        int value = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

        /** finish 只能调一次：超时兜底与最后一次取数可能几乎同时到 */
        void force() {
            if (done.compareAndSet(false, true) && pending != null) pending.finish();
        }
    }
}
