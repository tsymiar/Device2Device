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
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.tsymiar.device2device.market.Indicators;
import com.tsymiar.device2device.market.MarketPalette;
import com.tsymiar.device2device.market.Quote;
import com.tsymiar.device2device.market.Snapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * K线（蜡烛图）视图 —— MyAutomatic/toolset/matkline.py 绘图部分的 Android 版。
 *
 * 对应脚本 plot_candles：
 *  - 主图：蜡烛 + 影线（中国习惯红涨绿跌）+ MA5/MA10/MA20
 *  - 最新价虚线 + 价格轴自适应小数位
 *  - 底部副图：成交量 / MACD(12,26,9) / RSI(14)，单击切换（对应脚本单击循环）
 *  - 时间轴智能标签（日内显示时分，日线显示月-日）
 * 交互：左右拖动平移、双指缩放每根K线宽度、点按主图查看该根详情（通过回调外抛）
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
    // 红涨绿跌（与脚本 UP_COLOR/DOWN_COLOR/mMaColors 一致）
    private int mUp = 0xFFD62728;
    private int mDown = 0xFF2CA02C;
    private int mMa5 = 0xFFE8890C;
    private int mMa10 = 0xFF2F6FD0;
    private int mMa20 = 0xFF8A5CD6;
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

    /** 副图标题带指标参数（脚本 PANEL_LABEL_FMT） */
    private static final String[] PANEL_NAMES = {"成交量", "MACD(12,26,9)", "RSI(14)", "KDJ(9,3,3)"};
    private static final int PANEL_VOLUME = 0;
    private static final int PANEL_MACD = 1;
    private static final int PANEL_RSI = 2;
    private static final int PANEL_KDJ = 3;
    private static final int[] MA_WINDOWS = {5, 10, 20};
    private int[] mMaColors = {mMa5, mMa10, mMa20};

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
     * 选过之后刷新数据（自动刷新 / 换周期）都不再把它重置回成交量。
     */
    private int mPanelLocked = -1;
    /** true = 折线（分时）模式：1分钟这种超短周期蜡烛会糊成一片，改画收盘价折线 */
    private boolean mLineMode = false;
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

    private float mTouchDownX, mTouchDownY, mLastX;
    private boolean mMoved;
    private boolean mScaling;
    private final ScaleGestureDetector mScaleDetector;

    public KLineView(Context context) {
        this(context, null);
    }

    public KLineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mSlot = dp(8);
        setBackgroundColor(mBg);
        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                mScaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                int anchor = indexOfX(detector.getFocusX());
                mSlot = clamp(mSlot * detector.getScaleFactor(), dp(3), dp(40));
                computeVisible();
                if (anchor >= 0) {   // 缩放时保持双指中心那根K线不跑
                    int center = mStart + mVisible / 2;
                    shiftWithoutClamp(anchor - center);
                }
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

    /** 主图首行显示的标的名称（股票名/商品中文名）；传空串则只显示标题 */
    /** 整套换色：日间 / 夜间由外层传入（见 MarketPalette），换完立即重绘 */
    public void setPalette(MarketPalette p) {
        if (p == null) return;
        mBg = p.bg;
        mGrid = p.grid;
        mText = p.axis;
        mTextDim = p.textDim;
        mUp = p.up;
        mDown = p.down;
        mMa5 = p.ma5;
        mMa10 = p.ma10;
        mMa20 = p.ma20;
        mMaColors = new int[]{mMa5, mMa10, mMa20};
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
        setBackgroundColor(mBg);
        invalidate();
    }

    /** true = 折线（分时）模式：1分钟这种超短周期蜡烛会糊成一片，改画收盘价折线 */
    public void setLineMode(boolean lineMode) {
        if (mLineMode == lineMode) return;
        mLineMode = lineMode;
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

    /** 装载数据并立即重绘；默认显示最后一段 */
    public void setData(String title, List<Quote> quotes) {
        mTitle = title == null ? "" : title;
        mData = quotes == null ? new ArrayList<Quote>() : new ArrayList<>(quotes);
        mPendingTail = true;
        // 无成交量（如部分外汇源）时默认展示 MACD，而不是空面板（与脚本口径一致）；
        // 用户手动切过副图后就一直用他选的那个，刷新不再覆盖（锁了成交量但该源没量才回落 MACD）
        if (mPanelLocked >= 0) {
            mPanelMode = (mPanelLocked == PANEL_VOLUME && !hasVolume()) ? PANEL_MACD : mPanelLocked;
        } else {
            mPanelMode = hasVolume() ? PANEL_VOLUME : PANEL_MACD;
        }
        mSelected = -1;
        computeVisible();
        publishSelection();
        invalidate();
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

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

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
        // 系统字体放大时 sp(9) 的标签会变高，时间轴跟着长高，免得文字贴着底边被裁掉
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

    private void shiftWithoutClamp(int delta) {
        mStart -= delta;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

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
        drawPanel(canvas);
        drawTimeAxis(canvas);
        drawSelection(canvas, range);
        drawTooltip(canvas, range);
    }

    private void drawHeader(Canvas canvas) {
        float left = dp(10);
        // 第一行：标的名称（股票名 / 商品中文名），未解析到名称时退回标题
        String label = mName.isEmpty() ? mTitle : mName;
        mTextPaint.setColor(mText);
        mTextPaint.setTextSize(sp(15));
        mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label, left, sp(17), mTextPaint);
        mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

        // 第二行：代码 / 数据源 / 周期（有名称时才另起一行，避免重复）+ 最新价与涨跌
        mTextPaint.setTextSize(sp(11));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        if (!mName.isEmpty()) {
            mTextPaint.setColor(mTextDim);
            canvas.drawText(mTitle, left, sp(32), mTextPaint);
        }
        if (!mData.isEmpty()) {
            Quote last = mData.get(mData.size() - 1);
            float base = mData.size() > 1 ? mData.get(mData.size() - 2).close : last.open;
            mTextPaint.setColor(last.isUp() ? mUp : mDown);
            mTextPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.US, "%s  %+.2f%%", fmt(last.close), last.changePercent(base)),
                    getWidth() - dp(10), sp(32), mTextPaint);
        }
    }

    private void drawEmpty(Canvas canvas) {
        mTextPaint.setColor(mTextDim);
        mTextPaint.setTextSize(sp(13));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("暂无行情数据", getWidth() / 2f, getHeight() / 2f, mTextPaint);
    }

    /** 视窗内的 [最低, 最高] */
    private float[] visibleRange() {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
            Quote q = mData.get(i);
            if (q.low < min) min = q.low;
            if (q.high > max) max = q.high;
        }
        if (min == Float.MAX_VALUE) return new float[]{0f, 1f};
        float pad = (max - min) * 0.06f;
        if (pad <= 0) pad = Math.max(Math.abs(max) * 0.01f, 0.5f);
        return new float[]{min - pad, max + pad};
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
    }

    private void drawCandles(Canvas canvas, float[] range) {
        if (mLineMode) {
            drawTrendLine(canvas, range);
            return;
        }
        // 与脚本一致：K线越密实体越宽，避免整片糊在一起
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

    /**
     * 折线（分时）模式：视窗内收盘价连成平滑曲线，下方填一层半透明面积，末端标出最新价。
     *
     * 曲线用单调三次插值（Fritsch–Carlson）而不是普通 Catmull-Rom：后者在拐点处会过冲，
     * 画出数据里根本不存在的高点 / 低点，看着"平滑"了但读数是假的；
     * 单调版本保证段内曲线不会越过相邻两点的值域，极值仍然落在真实数据点上。
     */
    private void drawTrendLine(Canvas canvas, float[] range) {
        int first = Math.max(0, mStart);
        int last = Math.min(mData.size() - 1, mStart + mVisible - 1);
        if (last <= first) return;
        int n = last - first + 1;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = xOf(first + i);
            ys[i] = yOf(mData.get(first + i).close, range);
        }
        float[] tangents = monotoneTangents(xs, ys, n);

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
    }

    /**
     * 单调三次插值的切线斜率（Fritsch–Carlson）：
     * 相邻段异号（局部极值）时切线取 0，同号时取两侧斜率均值并限幅，
     * 这样曲线一定落在相邻两点的值域内 —— 平滑了形状，但不会造出假的高点低点。
     */
    private static float[] monotoneTangents(float[] xs, float[] ys, int n) {
        float[] m = new float[n];
        if (n < 2) return m;
        float[] d = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            d[i] = (ys[i + 1] - ys[i]) / Math.max(1e-6f, xs[i + 1] - xs[i]);
        }
        if (n == 2) {
            m[0] = d[0];
            m[1] = d[0];
            return m;
        }
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            if (d[i - 1] * d[i] <= 0f) {
                m[i] = 0f;                       // 局部极值：切线取平，曲线不会冲出值域
            } else {
                m[i] = (d[i - 1] + d[i]) * 0.5f;
                float limit = 3f * Math.min(Math.abs(d[i - 1]), Math.abs(d[i]));
                if (Math.abs(m[i]) > limit) m[i] = limit * Math.signum(m[i]);
            }
        }
        return m;
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
                        mMainRect.left + m * dp(58) + dp(2), mMainRect.top + sp(10), mTextPaint);
            }
        }
    }

    private void drawLastPrice(Canvas canvas, float[] range) {
        Quote last = mData.get(mData.size() - 1);
        float y = yOf(last.close, range);
        if (y < mMainRect.top || y > mMainRect.bottom) return;
        drawDashedLine(canvas, mMainRect.left, y, mMainRect.right, y,
                last.isUp() ? mUp : mDown);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(last.isUp() ? mUp : mDown);
        canvas.drawRect(mMainRect.right + dp(2), y - sp(7), getWidth() - dp(2), y + sp(7), mPaint);
        mTextPaint.setColor(mBg);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(fmt(last.close), mMainRect.right + dp(4), y + sp(3), mTextPaint);
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

    /**
     * 底部时间轴：先按间距抽稀，再逐个做「贴边内移 + 与前标签防重叠」。
     * 刚上市的股票只有十几根K线时，屏幕宽度摊到每根上的空间很窄，
     * 若只按根数抽稀（老逻辑）会出现 step=1 的密集标签，文字互相压在一起。
     */
    private void drawTimeAxis(Canvas canvas) {
        // 基线在时间轴高度里垂直居中（原来写死 sp(12)，字体放大后会顶出画布底部）
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
        drawDashedLine(canvas, x, mMainRect.top, x, mPanelRect.isEmpty() ? mMainRect.bottom : mPanelRect.bottom,
                mCursor);
        drawDashedLine(canvas, mMainRect.left, yOf(q.close, range), x, yOf(q.close, range), mCursor);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(q.isUp() ? mUp : mDown);
        canvas.drawCircle(x, yOf(q.close, range), Math.max(2.5f, dp(2.5f)), mPaint);
    }

    /**
     * 详情小窗口（浮窗）：主图/副图十字光标处弹出，显示该根K线的时间/开/高/低/收/量/额/振幅/涨跌，
     * 拿得到实时盘口时再补上市值 / 流通市值 / 换手率 / 市盈率。
     * 位置自动避让（光标在右半屏则浮窗放左侧，光标在上半屏则浮窗放下方）。
     */
    private void drawTooltip(Canvas canvas, float[] range) {
        if (mSelected < 0 || mSelected >= mData.size()) return;
        Quote q = mData.get(mSelected);
        float base = mSelected > 0 ? mData.get(mSelected - 1).close : q.open;
        List<String> rows = new ArrayList<>();
        rows.add(q.time);
        rows.add("开 " + fmt(q.open) + "   高 " + fmt(q.high));
        rows.add("低 " + fmt(q.low) + "   收 " + fmt(q.close));
        rows.add("量 " + (hasVolume() ? compact(q.volume) : "-")
                + "   额 " + (q.amount > 0 ? compact(q.amount) : "-"));
        rows.add("振幅 " + String.format(Locale.US, "%.2f%%", q.amplitude(base)));
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

    private void drawDashedLine(Canvas canvas, float x0, float y0, float x1, float y1, int color) {
        mLinePaint.setColor(color);
        mLinePaint.setStrokeWidth(1f);
        mLinePaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0f));
        canvas.drawLine(x0, y0, x1, y1, mLinePaint);
        mLinePaint.setPathEffect(null);
    }

    // ------------------------------------------------------------------
    // 坐标换算
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mData.isEmpty()) return true;
        mScaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchDownX = event.getX();
                mTouchDownY = event.getY();
                mLastX = event.getX();
                mMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mScaling || event.getPointerCount() > 1) return true;
                float dx = event.getX() - mLastX;
                if (Math.abs(dx) > 1f) mMoved = true;
                int shifted = (int) (dx / Math.max(mSlot, 1f));
                if (shifted != 0) {
                    mStart -= shifted;
                    clampStart();
                    mLastX = event.getX();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!mMoved && !mScaling) handleTap(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            default:
                return true;
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
                q.time, fmt(q.open), fmt(q.high), fmt(q.low), fmt(q.close), volume, amount, cap,
                q.changePercent(base)));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

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

    /** 时间轴格式（脚本 time_label_format）：日线按跨度降级，日内同日只显时分 */
    private String timePattern() {
        // 长周期直接按周期定格式：否则跨几十年的季/年线会退化成一堆相同年份标签
        if ("1Y".equals(mInterval)) return "yyyy";
        if ("1Q".equals(mInterval) || "1M".equals(mInterval)) return "yyyy-MM";
        int end = Math.min(mData.size(), mStart + mVisible) - 1;
        if (end < mStart) return "MM-dd";
        Date first = parseStamp(mData.get(mStart).time);
        Date mid = parseStamp(mData.get((mStart + end) / 2).time);
        Date last = parseStamp(mData.get(end).time);
        if (first == null || last == null) return "MM-dd";
        long days = (last.getTime() - first.getTime()) / 86400000L;
        boolean sameYear = first.getYear() == last.getYear();
        boolean intraday = hasTime(first) || (mid != null && hasTime(mid)) || hasTime(last);
        if (!intraday) {
            if (days <= 120 || (days <= 400 && sameYear)) return "MM-dd";
            if (days <= 1200) return "yyyy-MM";
            return "yyyy";
        }
        if (sameDay(first, last)) return "HH:mm";
        if (days <= 3) return "MM-dd HH:mm";
        return sameYear ? "MM-dd" : "yyyy-MM";
    }

    private String formatTime(String time, SimpleDateFormat fmt) {
        Date stamp = parseStamp(time);
        if (stamp == null) return time == null ? "" : time.substring(0, Math.min(10, time.length()));
        // 季线标签写成 2024Q1：SimpleDateFormat 的季度符各版本表现不一致，手拼最稳
        if ("1Q".equals(mInterval)) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(stamp);
            return calendar.get(Calendar.YEAR) + "Q" + (calendar.get(Calendar.MONTH) / 3 + 1);
        }
        return fmt.format(stamp);
    }

    /** 兼容 'YYYY-MM-DD HH:MM:SS' / '... HH:MM' / 'YYYY-MM-DD' 三种写法（脚本 _parse_stamp） */
    private static Date parseStamp(String text) {
        if (text == null) return null;
        String stamp = text.trim().replace('/', '-');
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        int[] lengths = {19, 16, 10};
        for (int i = 0; i < patterns.length; i++) {
            if (stamp.length() < lengths[i]) continue;
            try {
                return new SimpleDateFormat(patterns[i], Locale.US).parse(stamp.substring(0, lengths[i]));
            } catch (Exception ignored) {
                // 继续尝试下一种写法
            }
        }
        return null;
    }

    private static boolean hasTime(Date stamp) {
        return stamp.getHours() != 0 || stamp.getMinutes() != 0 || stamp.getSeconds() != 0;
    }

    private static boolean sameDay(Date a, Date b) {
        return a.getYear() == b.getYear() && a.getMonth() == b.getMonth() && a.getDate() == b.getDate();
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
