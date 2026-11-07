package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import com.tsymiar.device2device.market.Indicators;
import com.tsymiar.device2device.market.MarketPalette;
import com.tsymiar.device2device.market.Quote;
import com.tsymiar.device2device.market.Snapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * K线视图 —— MyAutomatic/toolset/matkline.py 绘图部分的 Android 版。
 * 主图蜡烛/分时折线 + MA + 多空标记；副图 成交量/MACD/RSI/KDJ 单击切换；
 * 交互：拖动平移、惯性滚动、双指缩放、长按十字跟随、点按出详情浮窗。
 */
public class KLineView extends View {

    /** 选中的K线详情回调（时间/开高低收/量/涨跌幅），交由外层展示 */
    public interface QuoteInfoListener {
        void onQuoteInfo(String text);
    }

    // 配色：默认是深色盘，外层用 setPalette(MarketPalette) 按日间 / 夜间整套切换
    private int mBg = 0xFF0E1116;
    private int mGrid = 0xFF20262F;
    private int mText = 0xFFB7BDC7;
    private int mTextDim = 0xFF6B7280;
    // 红涨绿跌（与脚本 UP_COLOR/DOWN_COLOR 一致）
    private int mUp = 0xFFD62728;
    private int mDown = 0xFF2CA02C;
    private int mCursor = 0xFF9AA4B2;
    private int mDif = 0xFFFFA726;
    private int mDea = 0xFF42A5F5;
    private int mRsi = 0xFFAB47BC;
    private int mTooltipBg = 0xEE151A21;
    private int mTooltipStroke = 0x664A5568;
    /** 浮窗文字：浮窗底在日间/夜间都是深色，所以单独取色，不能跟着页面正文色走 */
    private int mTooltipText = 0xFFE8EAED;
    private int mTooltipTextDim = 0xFF9AA4B2;
    /** 分时折线（1分钟等超短周期用折线代替蜡烛）：线色与下方半透明面积色 */
    private int mTrend = 0xFF2F6FD0;
    private int mTrendFill = 0x332F6FD0;
    /** 分时均价线（参考 PhoneStock：黄色，随调色板日/夜切换） */
    private int mAvg = 0xFFB45309;

    /** 副图标题带指标参数（脚本 PANEL_LABEL_FMT） */
    private static final String[] PANEL_NAMES = {"成交量", "MACD(12,26,9)", "RSI(14)", "KDJ(9,3,3)"};
    private static final int PANEL_VOLUME = 0;
    private static final int PANEL_MACD = 1;
    private static final int PANEL_RSI = 2;
    private static final int PANEL_KDJ = 3;
    private static final int[] MA_WINDOWS = {5, 10, 20};
    private int[] mMaColors = {0xFFE8890C, 0xFF2F6FD0, 0xFF8A5CD6};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private List<Quote> mData = new ArrayList<>();
    private String mTitle = "";
    private String mName = "";      // 标的名称（股票名/商品中文名），显示在主图首行
    /** 当前周期（1d/1w/1M/1Q/1Y…）：季线/年线的时间轴标签要按周期单独格式化 */
    private String mInterval = "1d";
    /** 底部时间轴高度（随字号缩放变大，保证标签始终完整落在画布内） */
    private float mAxisHeight = 0f;
    private int mPanelMode = PANEL_VOLUME;
    /**
     * 用户手动点选过的副图：-1 = 还没选过，跟随默认；
     * 选定后刷新数据（自动刷新 / 切换周期）不再将其重置为成交量。
     */
    private int mPanelLocked = -1;
    /** true = 折线（分时）模式：1分钟这种超短周期下蜡烛过密，改画收盘价折线 */
    private boolean mLineMode = false;
    /** 分时图的昨收（基准价）：<=0 表示没给，按数据里回溯（见 {@code prevClose}） */
    private float mPrevClose = 0f;
    private QuoteInfoListener mListener;
    /** 实时盘口快照（市值等 K 线里没有的数据），没有时为 null */
    private Snapshot mSnapshot;

    private float mDensity = 2f;
    private final RectF mMainRect = new RectF();
    private final RectF mPanelRect = new RectF();
    private float mHeaderHeight;
    private float mAxisTop;

    /** 每根K线占的宽度(px)，双指缩放改变；视窗由此推出可显示的根数 */
    private float mSlot;
    private int mStart;        // 视窗首根下标
    private int mVisible;      // 视窗根数
    private int mSelected = -1;
    private boolean mPendingTail = true;
    private int mDecimals = 2;
    /** 上一次生效的纵轴量程 [低, 高]：用于迟滞，避免刷新时整幅图上下抖 */
    private float[] mRange = null;
    /** 纵轴收窄的死区（占当前量程的比例）：小于它就不改量程 */
    private static final float RANGE_DEAD_BAND = 0.03f;

    private float mLastX;
    private boolean mMoved;
    private boolean mScaling;
    private final ScaleGestureDetector mScaleDetector;
    /** 长按进入十字光标跟随模式：按住期间手势只移动光标，不滚动（参考 PhoneStock） */
    private boolean mCrosshair;
    private final GestureDetector mGestureDetector;
    /** fling 惯性滚动：拖动抬起后按速度继续滑行（参考 PhoneStock 的 VelocityTracker+Scroller） */
    private final OverScroller mScroller;
    private VelocityTracker mVelocityTracker;

    // ===== 多空标记 =====
    // 同一根 K 线可能同时命中好几个（比如 MACD 金叉和 KDJ 低位金叉经常撞在一起），
    // 所以按位存进 mMarks，绘制时同侧按顺序往下 / 往上错开，别叠成一坨。
    // 位定义与计算在 MarkEngine（从本类拆出的纯函数部分）
    /** 每根 K 线的多空标记，按位存（见 MarkEngine.MK_*），setData 时算好 */
    private int[] mMarks;
    /** 反弹 / 超买回落的颜色（深浅模式自适应），跟九转、金叉死叉用的涨跌色分开 */
    private int mReboundColor = 0xFFC2185B;
    /** 多空标记是否显示（行情页一个开关管全部标记） */
    private boolean mShowSignals = true;

    public KLineView(Context context) {
        this(context, null);
    }

    public KLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mSlot = dp(8);
        setBackgroundColor(mBg);
        mScroller = new OverScroller(context);
        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                // 长按后进入十字跟随：之后的 ACTION_MOVE 全部用于移动光标，滚动被挂起
                if (mScaling || e.getPointerCount() > 1) return;
                mCrosshair = true;
                if (mVelocityTracker != null) mVelocityTracker.clear();
                select(indexOfX(e.getX()));
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // 双击复位：回到默认每根宽度并跳到最新一根（PhoneStock 无此交互，行情 App 常见补充）
                mScroller.forceFinished(true);
                mSlot = dp(8);
                mPendingTail = true;
                mSelected = -1;
                computeVisible();
                clampStart();
                publishSelection();
                invalidate();
                return true;
            }
        });
        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                mScaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (mMainRect.width() <= 0) return true;
                // 以手指落点下的那根 K 线为锚：缩放后让它仍停在原来的屏幕位置，
                // 跟手不跳变。原先是把锚点拽到画面中心，双指缩放时整幅图会明显抖动。
                float focusX = detector.getFocusX();
                int anchor = mStart + (int) Math.floor((focusX - mMainRect.left) / mSlot);
                mSlot = clamp(mSlot * detector.getScaleFactor(), minSlot(), dp(40));
                computeVisible();
                // 反解 mStart 使 xOf(anchor) ≈ focusX，锚点始终贴着手指
                float desired = anchor + 0.5f - (focusX - mMainRect.left) / mSlot;
                mStart = (int) Math.floor(desired);
                clampStart();
                invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                mScaling = false;
            }
        });
    }

    /** 整套换色：日间 / 夜间由外层传入（见 MarketPalette），换完立即重绘 */
    public void setPalette(MarketPalette p) {
        if (p == null) return;
        mBg = p.bg;
        mGrid = p.grid;
        mText = p.axis;
        mTextDim = p.textDim;
        mUp = p.up;
        mDown = p.down;
        mMaColors = new int[]{p.ma5, p.ma10, p.ma20};
        mCursor = p.cursor;
        mDif = p.dif;
        mDea = p.dea;
        mRsi = p.rsi;
        mTooltipBg = p.tooltipBg;
        mTooltipStroke = p.tooltipStroke;
        mTooltipText = p.tooltipText;
        mTooltipTextDim = p.tooltipTextDim;
        mTrend = p.trend;
        mTrendFill = (p.trend & 0x00FFFFFF) | 0x33000000;    // 同色 20% 透明做面积
        mAvg = p.avg;
        mReboundColor = p.rebound;
        setBackgroundColor(mBg);
        invalidate();
    }

    /** 九转与反弹信号的总开关（行情页上两者共用一个按钮） */
    public void setShowSignals(boolean show) {
        mShowSignals = show;
        invalidate();
    }

    /** true = 折线（分时）模式：1分钟这种超短周期下蜡烛过密，改画收盘价折线 */
    public void setLineMode(boolean lineMode) {
        if (mLineMode == lineMode) return;
        mLineMode = lineMode;
        invalidate();
    }

    /**
     * 分时图那条基准线 —— 昨收（见 {@code prevClose}）。
     *
     * 分时数据只含当天这一段，昨收不在其中，「回溯上一个交易日的最后一根」推不出来；
     * 换标的时由 caller 直接给，没给（或给了 0）才回退到推算。
     */
    public void setPrevClose(float prevClose) {
        mPrevClose = prevClose;
        invalidate();
    }

    /** 设置当前周期：季/年线的时间轴按周期出标签，避免长跨度下退化成年份刷屏 */
    public void setInterval(String interval) {
        mInterval = interval == null || interval.isEmpty() ? "1d" : interval;
    }

    public void setName(String name) {
        mName = name == null ? "" : name;
        invalidate();
    }

    public void setInfoListener(QuoteInfoListener listener) {
        mListener = listener;
    }

    /**
     * 实时盘口（市值 / 市盈 / 换手）：换标的时先传 null 清掉，取到再传进来。
     * 只有部分标的（A股 / 港股）拿得到，没有的时候详情小窗只显示 K 线自带的数据。
     */
    public void setSnapshot(Snapshot snapshot) {
        mSnapshot = snapshot;
        invalidate();
    }

    /**
     * 装载数据并立即重绘。自动刷新也走这里，故做"就地更新"：
     * 贴尾时继续跟随最新；用户在看历史/选中某根时保持不动；量程迟滞防抖。
     */
    public void setData(String title, List<Quote> quotes) {
        mTitle = title == null ? "" : title;
        int oldSize = mData.size();
        boolean atTail = oldSize == 0 || mPendingTail || mStart + mVisible >= oldSize;
        int keepStart = mStart;
        int keepSelected = mSelected;

        mData = sanitize(quotes);
        mMarks = MarkEngine.compute(mData);
        mPendingTail = atTail;
        // 无成交量（如部分外汇源）时默认展示 MACD，而不是空面板（与脚本口径一致）；
        // 用户手动切过副图后就一直用他选的那个，刷新不再覆盖（锁了成交量但该源没量才回落 MACD）
        if (mPanelLocked >= 0) {
            mPanelMode = (mPanelLocked == PANEL_VOLUME && !hasVolume()) ? PANEL_MACD : mPanelLocked;
        } else {
            mPanelMode = hasVolume() ? PANEL_VOLUME : PANEL_MACD;
        }
        if (!atTail) {
            mStart = Math.max(0, Math.min(keepStart, Math.max(0, mData.size() - 1)));
        }
        mSelected = (keepSelected >= 0 && keepSelected < mData.size()) ? keepSelected : -1;
        computeVisible();
        clampStart();
        publishSelection();
        invalidate();
    }

    /** 入库前滤掉画不出/画了会毁图的行：无时间戳、四价非有限值、四价全 0（负油价等真实负值不误伤） */
    private static List<Quote> sanitize(List<Quote> quotes) {
        List<Quote> out = new ArrayList<>();
        if (quotes == null) return out;
        for (Quote q : quotes) {
            if (q == null || q.time == null) continue;
            if (!isFinite(q.open) || !isFinite(q.high) || !isFinite(q.low) || !isFinite(q.close)) continue;
            if (q.open == 0f && q.high == 0f && q.low == 0f && q.close == 0f) continue;
            out.add(q);
        }
        return out;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /** 循环切换底部副图，返回当前副图名；手动切过之后记下来，刷新不再被重置 */
    public String cyclePanel() {
        mPanelMode = (mPanelMode + 1) % PANEL_NAMES.length;
        mPanelLocked = mPanelMode;
        invalidate();
        return PANEL_NAMES[mPanelMode];
    }

    /** 换数据源时清掉手动选择，重新按该源的默认副图显示 */
    public void resetPanelChoice() {
        mPanelLocked = -1;
        mPanelMode = hasVolume() ? PANEL_VOLUME : PANEL_MACD;
        invalidate();
    }

    public String getPanelName() {
        return PANEL_NAMES[mPanelMode];
    }

    // ===== 布局 =====
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeLayout();
        computeVisible();
        clampStart();
    }

    private void computeLayout() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float padL = dp(4);
        float padR = dp(56);          // 右侧价格轴
        float padB = dp(4);
        float gap = dp(6);
        mHeaderHeight = dp(40);
        // 系统字体放大时 sp(9) 标签高度增加，时间轴随之增高，以免文字贴底被裁切
        float axisH = Math.max(dp(18), sp(14));
        mAxisHeight = axisH;
        float top = mHeaderHeight;
        float rest = h - top - axisH - gap - padB;
        if (rest < dp(60)) {
            mMainRect.set(padL, top, w - padR, Math.max(top + dp(30), h - padB - axisH));
            mPanelRect.setEmpty();
            mAxisTop = mMainRect.bottom;
            return;
        }
        float mainH = Math.max(dp(70), rest * 0.70f);
        float panelH = Math.max(dp(34), Math.min(mainH * 0.34f, rest - mainH));
        if (mainH + panelH > rest) mainH = rest - panelH;
        mMainRect.set(padL, top, w - padR, top + mainH);
        mPanelRect.set(padL, top + mainH + gap, w - padR, top + mainH + gap + panelH);
        mAxisTop = mPanelRect.bottom;
    }

    private void computeVisible() {
        float width = Math.max(0f, mMainRect.width());
        int count = mData.size();
        int visible = mSlot <= 0 ? count : (int) Math.floor(width / mSlot);
        visible = Math.min(count, Math.max(5, visible));
        mVisible = visible;
        if (mPendingTail) {
            mStart = Math.max(0, count - visible);
            mPendingTail = false;
        }
    }

    private void clampStart() {
        int maxStart = Math.max(0, mData.size() - mVisible);
        if (mStart < 0) mStart = 0;
        if (mStart > maxStart) mStart = maxStart;
    }

    /** 缩放下限：蜡烛 2dp（再小看不清影线）；分时放宽到 1dp 并允许整日缩进同屏，兜底 0.5dp */
    private float minSlot() {
        if (!mLineMode) return dp(2);
        float fitAll = mData.isEmpty() ? dp(1) : mMainRect.width() / mData.size();
        return Math.min(dp(1), Math.max(fitAll, dp(0.5f)));
    }

    // ===== 绘制 =====
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        computeVisible();
        clampStart();
        if (getWidth() <= 0 || getHeight() <= 0) return;

        drawHeader(canvas);
        if (mData.isEmpty() || mVisible <= 0) {
            drawEmpty(canvas);
            return;
        }
        float[] range = visibleRange();
        updateDecimals(range[0], range[1]);
        drawGrid(canvas, range);
        drawCandles(canvas, range);
        drawMovingAverages(canvas, range);
        drawLastPrice(canvas, range);
        drawMarks(canvas, range);
        drawPanel(canvas);
        drawTimeAxis(canvas);
        drawSelection(canvas, range);
        drawTooltip(canvas, range);
    }

    private void drawHeader(Canvas canvas) {
        float left = dp(10);
        float right = getWidth() - dp(10);
        // 总市值挂在代码行：它是「这只票有多大」的口径，K 线本身没有这个字段，
        // 只有 A股 / 港股那类标的拿得到（见 Snapshot），拿不到就不占位
        String cap = mSnapshot != null && mSnapshot.totalCap > 0d
                ? "  市值 " + Snapshot.cap(mSnapshot.totalCap) : "";
        // 第一行：标的名称（股票名 / 商品中文名）；没解析到名称时第一行就用代码行
        String label = mName.isEmpty() ? mTitle + cap : mName;
        mTextPaint.setColor(mText);
        mTextPaint.setTextSize(sp(15));
        mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(fit(label, right - left), left, sp(17), mTextPaint);
        mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

        // 第二行：代码 / 周期 + 总市值（有名称时才另起一行，避免重复）+ 最新价与涨跌
        mTextPaint.setTextSize(sp(11));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        String code = mName.isEmpty() ? "" : mTitle + cap;
        if (!mData.isEmpty()) {
            Quote last = mData.get(mData.size() - 1);
            float base = mData.size() > 1 ? mData.get(mData.size() - 2).close : last.open;
            String price = String.format(Locale.US, "%s  %+.2f%%",
                    fmt(last.close), last.changePercent(base));
            if (!code.isEmpty()) {
                mTextPaint.setColor(mTextDim);
                // 右侧价格为右对齐，代码行仅使用其左侧空间，避免与市值重叠
                canvas.drawText(fit(code, right - left - mTextPaint.measureText(price) - dp(8)),
                        left, sp(32), mTextPaint);
            }
            mTextPaint.setColor(last.isUp() ? mUp : mDown);
            mTextPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(price, right, sp(32), mTextPaint);
        } else if (!code.isEmpty()) {
            mTextPaint.setColor(mTextDim);
            canvas.drawText(fit(code, right - left), left, sp(32), mTextPaint);
        }
    }

    /** 把该行截到 availPx 之内，截了就在末尾补个省略号：加长过的名称不能撞到右边的价 */
    private String fit(String text, float availPx) {
        if (text.isEmpty() || availPx <= 0f) return "";
        if (mTextPaint.measureText(text) <= availPx) return text;
        String ell = "…";
        float ellWidth = mTextPaint.measureText(ell);
        int end = text.length();
        while (end > 1 && mTextPaint.measureText(text.substring(0, end)) + ellWidth > availPx) {
            end--;
        }
        return end > 1 ? text.substring(0, end) + ell : "";
    }

    private void drawEmpty(Canvas canvas) {
        mTextPaint.setColor(mTextDim);
        mTextPaint.setTextSize(sp(13));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("暂无行情数据", getWidth() / 2f, getHeight() / 2f, mTextPaint);
    }

    /** 视窗 [最低, 最高]，量程带迟滞：扩立刻扩、收窄幅度在死区内不动，防刷新抖动 */
    private float[] visibleRange() {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
            Quote q = mData.get(i);
            if (q.low < min) min = q.low;
            if (q.high > max) max = q.high;
        }
        if (min == Float.MAX_VALUE) {
            mRange = null;
            return new float[]{0f, 1f};
        }
        if (mLineMode) {
            // 分时图要保证昨收这条基准线落在可视范围内，否则它会被画到框外
            float base = prevClose();
            if (base > 0f) {
                min = Math.min(min, base);
                max = Math.max(max, base);
            }
        }
        float pad = (max - min) * 0.06f;
        if (pad <= 0) pad = Math.max(Math.abs(max) * 0.01f, 0.5f);
        float lo = min - pad;
        float hi = max + pad;
        if (mRange != null) {
            float cLo = mRange[0];
            float cHi = mRange[1];
            float span = Math.max(1e-6f, cHi - cLo);
            // 量程迟滞（双向）：新极值落在旧量程内一个死区内就不动，避免每个 Tick 整幅图上下抖
            float dead = span * RANGE_DEAD_BAND;
            if (Math.abs(lo - cLo) < dead) lo = cLo;
            if (Math.abs(hi - cHi) < dead) hi = cHi;
        }
        mRange = new float[]{lo, hi};
        return mRange;
    }

    private void drawGrid(Canvas canvas, float[] range) {
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(1f);
        mLinePaint.setColor(mGrid);
        mLinePaint.setPathEffect(null);
        if (!mPanelRect.isEmpty()) {
            canvas.drawLine(mMainRect.left, mMainRect.bottom, mMainRect.right, mMainRect.bottom, mLinePaint);
        }
        // 5 条水平线 + 右侧价格刻度
        mTextPaint.setColor(mTextDim);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i <= 4; i++) {
            float y = mMainRect.top + mMainRect.height() * i / 4f;
            drawDashedLine(canvas, mMainRect.left, y, mMainRect.right, y, mGrid);
            canvas.drawText(fmt(priceAt(y, range)), mMainRect.right + dp(4), y + sp(3), mTextPaint);
        }
        // 垂直网格线：与时间轴刻度同位（参考 PhoneStock 的经线），首条贴左边框不画
        int step = Math.max(1, mVisible / 6);
        int end = Math.min(mData.size(), mStart + mVisible);
        float gridBottom = mPanelRect.isEmpty() ? mMainRect.bottom : mPanelRect.bottom;
        for (int i = mStart + step; i < end; i += step) {
            float x = xOf(i);
            drawDashedLine(canvas, x, mMainRect.top, x, gridBottom, mGrid);
        }
    }

    private void drawCandles(Canvas canvas, float[] range) {
        // 四价同值的序列（美元指数日线由汇率反算，没有真实 OHLC）画蜡烛只会得到一串断开的横杠，
        // 看起来就是「不连续」，改画折线才读得出走势
        if (mLineMode || !hasIntradayRange()) {
            drawTrendLine(canvas, range);
            return;
        }
        // 与脚本一致：K线越密实体越宽，避免密集处连成整片
        float body = mVisible <= 240 ? 0.62f : (mVisible <= 600 ? 0.76f : 0.9f);
        float halfWidth = Math.max(0.6f, mSlot * body / 2f);
        mPaint.setStyle(Paint.Style.FILL);
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
            Quote q = mData.get(i);
            int color = q.isUp() ? mUp : mDown;
            float cx = xOf(i);
            // 影线
            mLinePaint.setColor(color);
            mLinePaint.setStrokeWidth(Math.max(1f, halfWidth * 0.28f));
            mLinePaint.setPathEffect(null);
            canvas.drawLine(cx, yOf(q.high, range), cx, yOf(q.low, range), mLinePaint);
            // 实体
            float top = yOf(Math.max(q.open, q.close), range);
            float bottom = yOf(Math.min(q.open, q.close), range);
            mPaint.setColor(color);
            canvas.drawRect(cx - halfWidth, top, cx + halfWidth, Math.max(bottom, top + 1f), mPaint);
        }
    }

    /** 视窗内有没有任何一根带真实振幅：全都没有就说明这份数据没有 OHLC */
    private boolean hasIntradayRange() {
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
            Quote q = mData.get(i);
            if (q.high > q.low) return true;
        }
        return false;
    }

    /** 折线（分时）模式：收盘价平滑曲线 + 半透明面积 + 末端最新价；插值用单调三次（不过冲） */
    private void drawTrendLine(Canvas canvas, float[] range) {
        int first = Math.max(0, mStart);
        int last = Math.min(mData.size() - 1, mStart + mVisible - 1);
        int n = last - first + 1;
        if (n < 1) return;
        // 只有一笔最新价（美元指数分时源挂掉、只剩实时快照时就是这样）：画一条水平线，
        // 别让绘图区空着——空的折线区会被当成「加载失败」
        if (n == 1) {
            float y = yOf(mData.get(first).close, range);
            mLinePaint.setStyle(Paint.Style.STROKE);
            mLinePaint.setStrokeWidth(dp(1.4f));
            mLinePaint.setPathEffect(null);
            mLinePaint.setColor(mTrend);
            canvas.drawLine(mMainRect.left, y, mMainRect.right, y, mLinePaint);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mTrend);
            canvas.drawCircle(mMainRect.right, y, dp(2.2f), mPaint);
            drawPrevCloseAxis(canvas, range);
            return;
        }
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = xOf(first + i);
            ys[i] = yOf(mData.get(first + i).close, range);
        }
        float[] tangents = ChartMath.monotoneTangents(xs, ys, n);

        Path line = new Path();
        Path area = new Path();
        float baseY = mMainRect.bottom;
        line.moveTo(xs[0], ys[0]);
        area.moveTo(xs[0], baseY);
        area.lineTo(xs[0], ys[0]);
        for (int i = 0; i < n - 1; i++) {
            float dx = xs[i + 1] - xs[i];
            float c1x = xs[i] + dx / 3f;
            float c1y = ys[i] + tangents[i] * dx / 3f;
            float c2x = xs[i + 1] - dx / 3f;
            float c2y = ys[i + 1] - tangents[i + 1] * dx / 3f;
            line.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
            area.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
        }
        area.lineTo(xs[n - 1], baseY);
        area.close();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mTrendFill);
        canvas.drawPath(area, mPaint);

        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(dp(1.4f));
        mLinePaint.setPathEffect(null);
        mLinePaint.setColor(mTrend);
        canvas.drawPath(line, mLinePaint);

        mPaint.setColor(mTrend);
        canvas.drawCircle(xs[n - 1], ys[n - 1], dp(2.2f), mPaint);
        drawAverageLine(canvas, range, first, n, xs);
        drawPrevCloseAxis(canvas, range);
    }

    /** 分时均价线（当日累计均价，平均持仓成本）；仅在真分时（有昨收）时画，日线降级折线不画 */
    private void drawAverageLine(Canvas canvas, float[] range, int first, int n, float[] xs) {
        if (mPrevClose <= 0f) return;
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(dp(1));
        mLinePaint.setPathEffect(null);
        mLinePaint.setColor(mAvg);
        float sum = 0f;
        float lastX = 0f, lastY = 0f;
        boolean hasLast = false;
        for (int i = 0; i < n; i++) {
            sum += mData.get(first + i).close;
            float value = sum / (i + 1);
            float y = yOf(value, range);
            if (hasLast) canvas.drawLine(lastX, lastY, xs[i], y, mLinePaint);
            lastX = xs[i];
            lastY = y;
            hasLast = true;
        }
        // 图例跟 MA 一行排：均价值画在最后
        mTextPaint.setColor(mAvg);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("均价 " + fmt(sum / n), mMainRect.left + MA_WINDOWS.length * dp(72) + dp(2),
                mMainRect.top + sp(10), mTextPaint);
    }

    /** 分时骨架：昨收基准虚线 + 区内上/中/下三档百分比刻度 */
    private void drawPrevCloseAxis(Canvas canvas, float[] range) {
        float base = prevClose();
        if (base <= 0f) return;
        float y = yOf(base, range);
        drawDashedLine(canvas, mMainRect.left, y, mMainRect.right, y, mGrid);
        mTextPaint.setTextSize(sp(8));
        mTextPaint.setTextAlign(Paint.Align.RIGHT);
        mTextPaint.setColor(mTextDim);
        float right = mMainRect.right - dp(2);
        canvas.drawText(percentAt(base, base), right, y - dp(2), mTextPaint);
        canvas.drawText(percentAt(priceAt(mMainRect.top, range), base),
                right, mMainRect.top + sp(9), mTextPaint);
        canvas.drawText(percentAt(priceAt(mMainRect.bottom, range), base),
                right, mMainRect.bottom - dp(2), mTextPaint);
    }

    /** 相对昨收的涨跌幅文本 */
    private static String percentAt(float price, float base) {
        if (base <= 0f) return "";
        return String.format(Locale.US, "%+.2f%%", (price - base) / base * 100f);
    }

    /** 昨收：优先用外部给的；否则回溯到前一天最后一根取收盘（即「今天从哪儿起步」的价格） */
    private float prevClose() {
        if (mPrevClose > 0f) return mPrevClose;
        if (mData.isEmpty()) return 0f;
        String lastDay = ChartTime.dayOf(mData.get(mData.size() - 1).time);
        if (lastDay == null) return 0f;
        int i = mData.size() - 1;
        while (i > 0 && lastDay.equals(ChartTime.dayOf(mData.get(i - 1).time))) i--;
        return i > 0 ? mData.get(i - 1).close : mData.get(i).open;
    }

    private void drawMovingAverages(Canvas canvas, float[] range) {
        float[] closes = Indicators.closes(mData);
        for (int m = 0; m < MA_WINDOWS.length; m++) {
            float[] ma = Indicators.sma(closes, MA_WINDOWS[m]);
            mLinePaint.setColor(mMaColors[m]);
            mLinePaint.setStrokeWidth(dp(1));
            mLinePaint.setPathEffect(null);
            float lastX = 0f;
            float lastY = 0f;
            boolean hasLast = false;
            for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
                float value = ma[i];
                if (Float.isNaN(value)) continue;
                float x = xOf(i);
                float y = yOf(value, range);
                if (hasLast) canvas.drawLine(lastX, lastY, x, y, mLinePaint);
                lastX = x;
                lastY = y;
                hasLast = true;
            }
            // 图例：MA5/10/20 最新值
            if (!mData.isEmpty()) {
                float lastValue = ma[mData.size() - 1];
                mTextPaint.setColor(mMaColors[m]);
                mTextPaint.setTextSize(sp(9));
                mTextPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("MA" + MA_WINDOWS[m] + " " + (Float.isNaN(lastValue) ? "-" : fmt(lastValue)),
                        mMainRect.left + m * dp(72) + dp(2), mMainRect.top + sp(10), mTextPaint);
            }
        }
    }

    private void drawLastPrice(Canvas canvas, float[] range) {
        if (mData.isEmpty()) return;
        Quote last = mData.get(mData.size() - 1);
        float y = yOf(last.close, range);
        if (y < mMainRect.top || y > mMainRect.bottom) return;
        int color = last.isUp() ? mUp : mDown;
        drawDashedLine(canvas, mMainRect.left, y, mMainRect.right, y, color);
        if (mLineMode) {
            // 分时图：最新价直接标在曲线末端（最新点）上，跟随曲线、不被右侧窄边裁掉
            drawPriceBubble(canvas, fmt(last.close), xOf(mData.size() - 1), y, color);
        } else {
            // 蜡烛图：右侧贴边价签（现有逻辑，右侧有价格刻度留白）
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(color);
            canvas.drawRect(mMainRect.right + dp(2), y - sp(7), getWidth() - dp(2), y + sp(7), mPaint);
            mTextPaint.setColor(mBg);
            mTextPaint.setTextSize(sp(9));
            mTextPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(fmt(last.close), mMainRect.right + dp(4), y + sp(3), mTextPaint);
        }
    }

    /** 圆角价签：以 (cx, cy) 为锚，夹在绘图区内，用于分时图末端标注最新价 */
    private void drawPriceBubble(Canvas canvas, String text, float cx, float cy, int color) {
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        float tw = mTextPaint.measureText(text);
        float padX = sp(4), bh = sp(14);
        float bw = tw + padX * 2;
        float bx = Math.max(mMainRect.left + bw / 2f, Math.min(cx, mMainRect.right - bw / 2f));
        float by = Math.max(mMainRect.top + bh / 2f, Math.min(cy, mMainRect.bottom - bh / 2f));
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
        canvas.drawRoundRect(bx - bw / 2f, by - bh / 2f, bx + bw / 2f, by + bh / 2f, dp(3), dp(3), mPaint);
        mTextPaint.setColor(mBg);
        canvas.drawText(text, bx, by + sp(3), mTextPaint);
    }

    /**
     * 多空标记：多头画在 K 线下方（箭头朝上）、空头画在上方（箭头朝下），
     * 同一根命中多个时按 MK_* 顺序往远离 K 线的方向错开一行。
     */
    private void drawMarks(Canvas canvas, float[] range) {
        if (mMarks == null || !mShowSignals) return;
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        float step = sp(17);
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
            int bits = mMarks[i];
            if (bits == 0) continue;
            Quote q = mData.get(i);
            float cx = xOf(i);
            int bullSlot = 0, bearSlot = 0;
            for (int k = 0; k < MarkEngine.MK_COUNT; k++) {
                if ((bits & (1 << k)) == 0) continue;
                if (MarkEngine.MK_BULL[k]) {
                    float y = Math.min(yOf(q.low, range) + sp(14) + bullSlot * step, mMainRect.bottom - sp(2));
                    drawMarkGlyph(canvas, cx, y, true, MarkEngine.MK_TEXT[k], markColor(k));
                    bullSlot++;
                } else {
                    float y = Math.max(yOf(q.high, range) - sp(14) - bearSlot * step, mMainRect.top + sp(2));
                    drawMarkGlyph(canvas, cx, y, false, MarkEngine.MK_TEXT[k], markColor(k));
                    bearSlot++;
                }
            }
        }
        mTextPaint.setTypeface(Typeface.DEFAULT);
    }

    private int markColor(int kind) {
        switch (kind) {
            case MarkEngine.MK_NINE_BUY:
                return mDown;
            case MarkEngine.MK_NINE_SELL:
                return mUp;
            case MarkEngine.MK_REBOUND:
            case MarkEngine.MK_FALLBACK:
                return mReboundColor;
            case MarkEngine.MK_MACD_GOLD:
            case MarkEngine.MK_KDJ_GOLD:
                return mUp;
            default:
                return mDown;
        }
    }

    /** 一个标记：三角 + 一字标签。bull 时 y 是三角底边（尖朝上），bear 时尖朝下 */
    private void drawMarkGlyph(Canvas canvas, float cx, float y, boolean bull, String text, int color) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
        mTextPaint.setColor(color);
        Path tri = new Path();
        float h = sp(6);
        if (bull) {
            tri.moveTo(cx, y - h);
            tri.lineTo(cx - sp(4), y);
            tri.lineTo(cx + sp(4), y);
            tri.close();
            canvas.drawPath(tri, mPaint);
            canvas.drawText(text, cx, y + sp(10), mTextPaint);
        } else {
            tri.moveTo(cx, y + h);
            tri.lineTo(cx - sp(4), y);
            tri.lineTo(cx + sp(4), y);
            tri.close();
            canvas.drawPath(tri, mPaint);
            canvas.drawText(text, cx, y - sp(3), mTextPaint);
        }
    }

    private void drawPanel(Canvas canvas) {
        if (mPanelRect.isEmpty() || mPanelRect.height() <= 0) return;
        float[] closes = Indicators.closes(mData);
        float left = mPanelRect.left;
        float top = mPanelRect.top;
        float bottom = mPanelRect.bottom;

        // 指标数据不足时回退到成交量面板（脚本 _draw_panel 口径）
        final int mode = (mPanelMode == PANEL_RSI && mData.size() <= 14) ? PANEL_VOLUME : mPanelMode;
        if (mode == PANEL_MACD) {
            Indicators.Macd macd = Indicators.macd(closes, 12, 26, 9);
            float max = 0f;
            for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
                max = Math.max(max, Math.abs(macd.hist[i]));
                max = Math.max(max, Math.abs(macd.dif[i]));
                max = Math.max(max, Math.abs(macd.dea[i]));
            }
            if (max <= 0f) max = 1f;
            float mid = top + mPanelRect.height() / 2f;
            float half = mPanelRect.height() / 2f * 0.92f;
            float barWidth = Math.max(1f, mSlot * 0.3f);
            mPaint.setStyle(Paint.Style.FILL);
            for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
                float x = xOf(i);
                float value = macd.hist[i];
                mPaint.setColor(value >= 0 ? mUp : mDown);
                float y0 = mid - value / max * half;
                canvas.drawRect(x - barWidth, Math.min(y0, mid), x + barWidth, Math.max(y0, mid), mPaint);
            }
            drawSeries(canvas, macd.dif, mDif, max, true);
            drawSeries(canvas, macd.dea, mDea, max, true);
            mLinePaint.setColor(mGrid);
            mLinePaint.setPathEffect(null);
            mLinePaint.setStrokeWidth(1f);
            canvas.drawLine(left, mid, mPanelRect.right, mid, mLinePaint);
            drawPanelScale(canvas, String.format(Locale.US, "%.2f", max), top);
            drawPanelLegend(canvas, new String[]{"DIF", "DEA"}, new int[]{mDif, mDea});
        } else if (mode == PANEL_RSI) {
            float[] rsi = Indicators.rsi(closes, 14);
            drawLevels(canvas, new float[]{30f, 50f, 70f});
            drawSeries(canvas, rsi, mRsi, 100f, false);
            drawPanelScale(canvas, "100", top);
            drawPanelScale(canvas, "0", bottom);
        } else if (mode == PANEL_KDJ) {
            Indicators.Kdj kdj = Indicators.kdj(Indicators.highs(mData), Indicators.lows(mData),
                    closes, 9, 3, 3);
            drawLevelsScaled(canvas, new float[]{20f, 50f, 80f}, -20f, 120f);
            drawSeriesScaled(canvas, kdj.k, mDea, -20f, 120f);   // K
            drawSeriesScaled(canvas, kdj.d, mDif, -20f, 120f);   // D
            drawSeriesScaled(canvas, kdj.j, mRsi, -20f, 120f);   // J
            drawPanelScale(canvas, "100", panelY(100f, -20f, 120f));
            drawPanelScale(canvas, "0", panelY(0f, -20f, 120f));
            drawPanelLegend(canvas, new String[]{"K", "D", "J"},
                    new int[]{mDea, mDif, mRsi});
        } else {
            float max = 0f;
            for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
                max = Math.max(max, mData.get(i).volume);
            }
            if (max <= 0f) {
                max = 1f;
                mTextPaint.setColor(mTextDim);
                mTextPaint.setTextSize(sp(10));
                mTextPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("该数据源无成交量", (left + mPanelRect.right) / 2f,
                        (top + bottom) / 2f, mTextPaint);
            }
            float barWidth = Math.max(1f, mSlot * 0.3f);
            mPaint.setStyle(Paint.Style.FILL);
            for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
                Quote q = mData.get(i);
                float x = xOf(i);
                float barTop = bottom - q.volume / (max * 1.15f) * mPanelRect.height();
                mPaint.setColor(q.isUp() ? mUp : mDown);
                canvas.drawRect(x - barWidth, barTop, x + barWidth, bottom, mPaint);
            }
            drawPanelScale(canvas, compact(max), top);
        }

        mTextPaint.setColor(mTextDim);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(PANEL_NAMES[mode], left + dp(2), top + sp(10), mTextPaint);
    }

    /** 副图纵轴：把数值映射到 [min, max] 区间（KDJ 用 -20~120，J 常冲出 0~100） */
    private float panelY(float value, float min, float max) {
        return mPanelRect.bottom - (value - min) / (max - min) * mPanelRect.height();
    }

    private void drawLevelsScaled(Canvas canvas, float[] levels, float min, float max) {
        mLinePaint.setColor(mGrid);
        mLinePaint.setStrokeWidth(1f);
        mLinePaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0f));
        for (float level : levels) {
            float y = panelY(level, min, max);
            canvas.drawLine(mPanelRect.left, y, mPanelRect.right, y, mLinePaint);
        }
        mLinePaint.setPathEffect(null);
    }

    private void drawSeriesScaled(Canvas canvas, float[] values, int color, float min, float max) {
        mLinePaint.setColor(color);
        mLinePaint.setStrokeWidth(dp(1));
        mLinePaint.setPathEffect(null);
        boolean first = true;
        float lastX = 0f;
        float lastY = 0f;
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
            float value = values[i];
            if (Float.isNaN(value)) continue;
            float x = xOf(i);
            float y = panelY(value, min, max);
            if (!first) canvas.drawLine(lastX, lastY, x, y, mLinePaint);
            lastX = x;
            lastY = y;
            first = false;
        }
    }

    /** 画一条曲线：value 到 y 的映射由 max 决定（MACD 居中，RSI 用 0~100） */
    private void drawSeries(Canvas canvas, float[] values, int color, float max, boolean centered) {
        mLinePaint.setColor(color);
        mLinePaint.setStrokeWidth(dp(1));
        mLinePaint.setPathEffect(null);
        boolean first = true;
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
            float v = values[i];
            if (Float.isNaN(v)) continue;
            float x = xOf(i);
            float y;
            if (centered) {
                y = mPanelRect.top + mPanelRect.height() / 2f - v / max * (mPanelRect.height() / 2f * 0.92f);
            } else {
                y = mPanelRect.bottom - v / max * mPanelRect.height();
            }
            if (!first) {
                float prevValue = values[i - 1];
                float prevX = xOf(i - 1);
                float prevY;
                if (centered) {
                    prevY = mPanelRect.top + mPanelRect.height() / 2f
                            - prevValue / max * (mPanelRect.height() / 2f * 0.92f);
                } else {
                    prevY = mPanelRect.bottom - prevValue / max * mPanelRect.height();
                }
                if (!Float.isNaN(prevValue)) canvas.drawLine(prevX, prevY, x, y, mLinePaint);
            }
            first = false;
        }
    }

    private void drawLevels(Canvas canvas, float[] levels) {
        mLinePaint.setColor(mGrid);
        mLinePaint.setStrokeWidth(1f);
        mLinePaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0f));
        for (float level : levels) {
            float y = mPanelRect.bottom - level / 100f * mPanelRect.height();
            canvas.drawLine(mPanelRect.left, y, mPanelRect.right, y, mLinePaint);
        }
        mLinePaint.setPathEffect(null);
    }

    /** 副图右上角的曲线图例（脚本 legend） */
    private void drawPanelLegend(Canvas canvas, String[] labels, int[] colors) {
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.RIGHT);
        float x = mPanelRect.right - dp(2);
        for (int i = labels.length - 1; i >= 0; i--) {
            mTextPaint.setColor(colors[i]);
            canvas.drawText(labels[i], x, mPanelRect.top + sp(10), mTextPaint);
            x -= mTextPaint.measureText(labels[i]) + dp(6);
        }
    }

    private void drawPanelScale(Canvas canvas, String text, float y) {
        mTextPaint.setColor(mTextDim);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(text, mPanelRect.right + dp(4), Math.min(mPanelRect.bottom, y + sp(9)), mTextPaint);
    }

    /** 底部时间轴：按间距抽稀 + 贴边内移 + 与前标签防重叠 */
    private void drawTimeAxis(Canvas canvas) {
        // 基线在时间轴高度里垂直居中（写死 sp(12) 时，字体放大后会顶出画布底部）
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float height = mAxisHeight > 0 ? mAxisHeight : sp(14);
        float y = mAxisTop + height / 2f - (fm.ascent + fm.descent) / 2f;
        mTextPaint.setColor(mTextDim);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        int step = Math.max(1, mVisible / 6);
        SimpleDateFormat fmt = new SimpleDateFormat(timePattern(), Locale.US);
        float minGap = dp(8);                       // 两个标签之间至少留这么多空隙
        float left = dp(2);
        float right = getWidth() - dp(4);
        float lastRight = Float.NEGATIVE_INFINITY;  // 上一个已画标签的右边界
        int end = Math.min(mData.size(), mStart + mVisible);
        for (int i = mStart; i < end; i += step) {
            String label = formatTime(mData.get(i).time, fmt);
            float half = mTextPaint.measureText(label) / 2f;
            if (half <= 0f) continue;
            float x = xOf(i);
            // 首尾两个标签贴边时向内收，保证整串文字都在画布内
            x = Math.max(x, left + half);
            x = Math.min(x, right - half);
            if (x - half < lastRight + minGap) continue;   // 会和前一个标签叠上，跳过
            canvas.drawText(label, x, y, mTextPaint);
            lastRight = x + half;
        }
    }

    private void drawSelection(Canvas canvas, float[] range) {
        if (mSelected < 0 || mSelected >= mData.size()) return;
        Quote q = mData.get(mSelected);
        float x = xOf(mSelected);
        float y = yOf(q.close, range);
        float bottom = mPanelRect.isEmpty() ? mMainRect.bottom : mPanelRect.bottom;
        drawDashedLine(canvas, x, mMainRect.top, x, bottom, mCursor);
        drawDashedLine(canvas, mMainRect.left, y, x, y, mCursor);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(q.isUp() ? mUp : mDown);
        canvas.drawCircle(x, y, Math.max(2.5f, dp(2.5f)), mPaint);
        // 光标读数钉在轴上（参考 PhoneStock）：右侧价格签 + 底部时间签
        drawAxisTag(canvas, fmt(q.close), mMainRect.right + dp(1), y - sp(7), y + sp(7), true);
        String label = formatTime(q.time, new SimpleDateFormat(timePattern(), Locale.US));
        float cx = clamp(x, mMainRect.left + dp(20), mMainRect.right - dp(20));
        drawAxisTag(canvas, label, cx - dp(20), mAxisTop, mAxisTop + mAxisHeight, false);
    }

    /** 轴上的小标签块：isPrice=true 时文字左对齐（价格轴），否则居中（时间轴） */
    private void drawAxisTag(Canvas canvas, String text, float left, float top, float bottom, boolean isPrice) {
        if (text == null || text.isEmpty()) return;
        mTextPaint.setTextSize(sp(9));
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mCursor);
        if (isPrice) {
            float width = mTextPaint.measureText(text) + dp(6);
            canvas.drawRect(left, top, left + width, bottom, mPaint);
            mTextPaint.setTextAlign(Paint.Align.LEFT);
            mTextPaint.setColor(mBg);
            canvas.drawText(text, left + dp(3), (top + bottom) / 2f + sp(3), mTextPaint);
        } else {
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawRect(left, top, left + dp(40), bottom, mPaint);
            mTextPaint.setColor(mBg);
            canvas.drawText(text, left + dp(20), (top + bottom) / 2f + sp(3), mTextPaint);
        }
    }

    /** 详情浮窗：该根的时间/OHLC/量额/振幅/涨跌 + 副图指标联动 + 盘口（有则补），位置自动避让光标 */
    private void drawTooltip(Canvas canvas, float[] range) {
        if (mSelected < 0 || mSelected >= mData.size()) return;
        Quote q = mData.get(mSelected);
        float base = mSelected > 0 ? mData.get(mSelected - 1).close : q.open;
        List<String> rows = new ArrayList<>();
        rows.add(ChartTime.briefTime(q.time));
        rows.add("开 " + fmt(q.open) + "   高 " + fmt(q.high));
        rows.add("低 " + fmt(q.low) + "   收 " + fmt(q.close));
        rows.add("量 " + (hasVolume() ? compact(q.volume) : "-")
                + "   额 " + (q.amount > 0 ? compact(q.amount) : "-"));
        rows.add("振幅 " + String.format(Locale.US, "%.2f%%", q.amplitude(base)));
        // 副图指标联动（参考 PhoneStock：光标浮窗里带出指标读数），与当前副图模式对应
        String indicator = panelValueAt(mSelected);
        if (indicator != null) rows.add(indicator);
        // 分时模式下带出该时刻的均价（当日累计均值）
        if (mLineMode && mPrevClose > 0f && mSelected < mData.size()) {
            float sum = 0f;
            for (int i = 0; i <= mSelected; i++) sum += mData.get(i).close;
            rows.add("均价 " + fmt(sum / (mSelected + 1)));
        }
        // 市值这类实时盘口数据只有部分标的拿得到（见 Snapshot），没有就不占行
        if (mSnapshot != null) {
            rows.add("市值 " + Snapshot.cap(mSnapshot.totalCap)
                    + "   流通 " + Snapshot.cap(mSnapshot.floatCap));
            rows.add("换手 " + String.format(Locale.US, "%.2f%%", mSnapshot.turnoverRate)
                    + "   市盈 " + String.format(Locale.US, "%.2f", mSnapshot.pe));
        }
        rows.add("涨跌 " + String.format(Locale.US, "%+.2f%%", q.changePercent(base)));
        String[] lines = rows.toArray(new String[0]);

        float textSize = sp(11);
        float pad = dp(7);
        float lineH = sp(15);
        mTextPaint.setTextSize(textSize);
        float textW = 0f;
        for (String line : lines) textW = Math.max(textW, mTextPaint.measureText(line));
        float boxW = Math.min(textW + pad * 2, Math.min(getWidth() - dp(16), dp(240)));
        float boxH = pad * 2 + lineH * lines.length;

        float x = xOf(mSelected);
        float y = yOf(q.close, range);
        boolean toLeft = x > (mMainRect.left + mMainRect.right) / 2f;
        float boxX = clamp(toLeft ? x - dp(14) - boxW : x + dp(14),
                dp(4), Math.max(dp(4), getWidth() - dp(4) - boxW));
        boolean above = y > (mMainRect.top + mMainRect.bottom) / 2f;
        float boxY = clamp(above ? y - dp(16) - boxH : y + dp(16),
                mHeaderHeight, Math.max(mHeaderHeight, getHeight() - dp(4) - boxH));

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mTooltipBg);
        canvas.drawRoundRect(new RectF(boxX, boxY, boxX + boxW, boxY + boxH), dp(6), dp(6), mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1f);
        mPaint.setColor(mTooltipStroke);
        canvas.drawRoundRect(new RectF(boxX, boxY, boxX + boxW, boxY + boxH), dp(6), dp(6), mPaint);

        int save = canvas.save();
        canvas.clipRect(boxX, boxY, boxX + boxW, boxY + boxH);
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < lines.length; i++) {
            // 浮窗底在两套配色下都是深色：文字用浮窗专用色，涨跌行沿用红涨绿跌
            mTextPaint.setColor(i == 0 ? mTooltipTextDim : (i == lines.length - 1
                    ? (q.isUp() ? mUp : mDown) : mTooltipText));
            canvas.drawText(lines[i], boxX + pad, boxY + pad + lineH * i + sp(11), mTextPaint);
        }
        canvas.restoreToCount(save);
    }

    /** 选中根对应的副图指标读数（无成交量面板返回 null；NaN 显示 -） */
    private String panelValueAt(int index) {
        if (index < 0 || index >= mData.size()) return null;
        float[] closes = Indicators.closes(mData);
        int mode = (mPanelMode == PANEL_RSI && mData.size() <= 14) ? PANEL_VOLUME : mPanelMode;
        switch (mode) {
            case PANEL_MACD: {
                Indicators.Macd macd = Indicators.macd(closes, 12, 26, 9);
                return String.format(Locale.US, "DIF %s  DEA %s  MACD %s",
                        num2(macd.dif[index]), num2(macd.dea[index]), num2(macd.hist[index]));
            }
            case PANEL_RSI: {
                float[] rsi = Indicators.rsi(closes, 14);
                return "RSI " + num2(rsi[index]);
            }
            case PANEL_KDJ: {
                Indicators.Kdj kdj = Indicators.kdj(Indicators.highs(mData), Indicators.lows(mData),
                        closes, 9, 3, 3);
                return String.format(Locale.US, "K %s  D %s  J %s",
                        num2(kdj.k[index]), num2(kdj.d[index]), num2(kdj.j[index]));
            }
            default:
                return null;    // 成交量面板：量/额已在上面单独一行
        }
    }

    /** 两位小数；NaN（窗口不足）显示 - */
    private static String num2(float value) {
        return Float.isNaN(value) ? "-" : String.format(Locale.US, "%.2f", value);
    }

    private void drawDashedLine(Canvas canvas, float x0, float y0, float x1, float y1, int color) {
        mLinePaint.setColor(color);
        mLinePaint.setStrokeWidth(1f);
        mLinePaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0f));
        canvas.drawLine(x0, y0, x1, y1, mLinePaint);
        mLinePaint.setPathEffect(null);
    }

    // ===== 坐标换算 =====
    private float xOf(int index) {
        return mMainRect.left + mSlot * (index - mStart + 0.5f);
    }

    private float yOf(float price, float[] range) {
        float ratio = (range[1] - price) / Math.max(range[1] - range[0], 1e-9f);
        return mMainRect.top + ratio * mMainRect.height();
    }

    private float priceAt(float y, float[] range) {
        float ratio = (y - mMainRect.top) / Math.max(mMainRect.height(), 1e-9f);
        return range[1] - ratio * (range[1] - range[0]);
    }

    private int indexOfX(float x) {
        if (mSlot <= 0) return -1;
        int index = mStart + (int) Math.floor((x - mMainRect.left) / mSlot);
        if (index < mStart) index = mStart;
        if (index >= mStart + mVisible) index = mStart + mVisible - 1;
        if (index < 0 || index >= mData.size()) return -1;
        return index;
    }

    // ===== 交互 =====
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mData.isEmpty()) return true;
        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastX = event.getX();
                mMoved = false;
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                } else {
                    mVelocityTracker.clear();
                }
                mVelocityTracker.addMovement(event);
                mScroller.forceFinished(true);          // 落手即停住惯性
                return true;
            case MotionEvent.ACTION_MOVE:
                mVelocityTracker.addMovement(event);
                if (mCrosshair) {
                    // 长按跟随：光标吸附到最近的K线，滚动被挂起
                    int hit = indexOfX(event.getX());
                    if (hit >= 0 && hit != mSelected) {
                        mSelected = hit;
                        publishSelection();
                        invalidate();
                    }
                    return true;
                }
                if (mScaling || event.getPointerCount() > 1) return true;
                float dx = event.getX() - mLastX;
                if (Math.abs(dx) > 1f) mMoved = true;
                int shifted = (int) (dx / Math.max(mSlot, 1f));
                if (shifted != 0) {
                    mStart -= shifted;
                    mPendingTail = false;               // 用户主动离开最新端，刷新不再跟随
                    clampStart();
                    mLastX = event.getX();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                mVelocityTracker.addMovement(event);
                if (mCrosshair) {
                    // 抬手退出跟随模式；选中的那根保留（浮窗仍在）
                    mCrosshair = false;
                    recycleVelocity();
                    return true;
                }
                if (!mMoved && !mScaling) {
                    handleTap(event.getX(), event.getY());
                } else if (!mScaling) {
                    float vx = mVelocityTracker.getXVelocity();
                    if (Math.abs(vx) > dp(50)) startFling(vx);
                }
                recycleVelocity();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mCrosshair = false;
                    recycleVelocity();
                }
                return true;
            default:
                return true;
        }
    }

    private void recycleVelocity() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /** fling：把当前视窗起点换算到像素域交给 OverScroller，computeScroll 里再换算回根下标 */
    private void startFling(float vx) {
        int maxStart = Math.max(0, mData.size() - mVisible);
        if (maxStart <= 0) return;
        mScroller.fling((int) (mStart * mSlot), 0, (int) -vx, 0,
                0, (int) (maxStart * mSlot), 0, 0);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            int newStart = Math.round(mScroller.getCurrX() / Math.max(mSlot, 1f));
            int maxStart = Math.max(0, mData.size() - mVisible);
            newStart = Math.max(0, Math.min(maxStart, newStart));
            if (newStart != mStart) {
                mStart = newStart;
                mPendingTail = false;
                invalidate();
            }
        }
    }

    private void handleTap(float x, float y) {
        if (!mPanelRect.isEmpty() && y >= mPanelRect.top && y <= mPanelRect.bottom) {
            String name = cyclePanel();
            if (mListener != null) mListener.onQuoteInfo("副图切换为 " + name);
            return;
        }
        select(indexOfX(x));
    }

    private void select(int index) {
        mSelected = index;
        publishSelection();
        invalidate();
    }

    /** 收起选中详情（浮窗 + 十字光标），点标题栏 / 底部信息栏时调用 */
    public void clearSelection() {
        if (mSelected < 0) return;
        mSelected = -1;
        publishSelection();
        invalidate();
    }

    private void publishSelection() {
        if (mListener == null) return;
        if (mSelected < 0 || mSelected >= mData.size()) {
            mListener.onQuoteInfo("");
            return;
        }
        Quote q = mData.get(mSelected);
        float base = mSelected > 0 ? mData.get(mSelected - 1).close : q.open;
        String volume = hasVolume() ? ("  量 " + compact(q.volume)) : "";
        String amount = q.amount > 0 ? ("  额 " + compact(q.amount)) : "";
        String cap = mSnapshot != null ? ("  市值 " + Snapshot.cap(mSnapshot.totalCap)) : "";
        mListener.onQuoteInfo(String.format(Locale.US,
                "%s  开 %s 高 %s 低 %s 收 %s%s%s%s  涨跌 %+.2f%%",
                ChartTime.briefTime(q.time), fmt(q.open), fmt(q.high), fmt(q.low), fmt(q.close),
                volume, amount, cap, q.changePercent(base)));
    }

    // ===== 工具 =====
    private boolean hasVolume() {
        for (Quote q : mData) {
            if (q.volume > 0f) return true;
        }
        return false;
    }

    /** 由价格跨度决定小数位（与脚本 price_decimals 同阈值） */
    private void updateDecimals(float low, float high) {
        float span = Math.abs(high - low);
        if (span >= 500f) mDecimals = 0;
        else if (span >= 20f) mDecimals = 1;
        else if (span >= 1f) mDecimals = 2;
        else if (span >= 0.05f) mDecimals = 3;
        else mDecimals = 4;
    }

    private String fmt(float value) {
        return String.format(Locale.US, "%." + mDecimals + "f", value);
    }

    /** 大数字压缩显示(亿/万)，避免成交量轴出现长串数字 */
    private static String compact(float value) {
        float abs = Math.abs(value);
        if (abs >= 1e8f) return String.format(Locale.US, "%.2f亿", value / 1e8f);
        if (abs >= 1e4f) {
            float scaled = value / 1e4f;
            return String.format(Locale.US, Math.abs(scaled) < 100f ? "%.2f万" : "%.0f万", scaled);
        }
        return String.format(Locale.US, "%.0f", value);
    }

    /** 时间轴格式（脚本 time_label_format）：日线按跨度降级，日内同日只显时分（解析/格式化见 {@link ChartTime}） */
    private String timePattern() {
        int end = Math.min(mData.size(), mStart + mVisible) - 1;
        return ChartTime.pattern(mInterval, mData, mStart, end);
    }

    private String formatTime(String time, SimpleDateFormat fmt) {
        return ChartTime.format(mInterval, time, fmt);
    }

    private float dp(float value) {
        return value * mDensity;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
