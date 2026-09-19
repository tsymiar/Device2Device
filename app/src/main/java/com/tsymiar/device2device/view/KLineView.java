package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.tsymiar.device2device.market.Indicators;
import com.tsymiar.device2device.market.Quote;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    private static final int COLOR_BG = 0xFF0E1116;
    private static final int COLOR_GRID = 0xFF20262F;
    private static final int COLOR_TEXT = 0xFFB7BDC7;
    private static final int COLOR_TEXT_DIM = 0xFF6B7280;
    // 配色与脚本 UP_COLOR/DOWN_COLOR/MA_COLORS 一致：红涨绿跌
    private static final int COLOR_UP = 0xFFD62728;
    private static final int COLOR_DOWN = 0xFF2CA02C;
    private static final int COLOR_MA5 = 0xFFE8890C;
    private static final int COLOR_MA10 = 0xFF2F6FD0;
    private static final int COLOR_MA20 = 0xFF8A5CD6;
    private static final int COLOR_CURSOR = 0xFF9AA4B2;
    private static final int COLOR_DIF = 0xFFFFA726;
    private static final int COLOR_DEA = 0xFF42A5F5;
    private static final int COLOR_RSI = 0xFFAB47BC;

    /** 副图标题带指标参数（脚本 PANEL_LABEL_FMT） */
    private static final String[] PANEL_NAMES = {"成交量", "MACD(12,26,9)", "RSI(14)", "KDJ(9,3,3)"};
    private static final int PANEL_VOLUME = 0;
    private static final int PANEL_MACD = 1;
    private static final int PANEL_RSI = 2;
    private static final int PANEL_KDJ = 3;
    private static final int[] MA_WINDOWS = {5, 10, 20};
    private static final int[] MA_COLORS = {COLOR_MA5, COLOR_MA10, COLOR_MA20};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private List<Quote> mData = new ArrayList<>();
    private String mTitle = "";
    private String mName = "";      // 标的名称（股票名/商品中文名），显示在主图首行
    private int mPanelMode = PANEL_VOLUME;
    private QuoteInfoListener mListener;

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
        setBackgroundColor(COLOR_BG);
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
    public void setName(String name) {
        mName = name == null ? "" : name;
        invalidate();
    }

    public void setInfoListener(QuoteInfoListener listener) {
        mListener = listener;
    }

    /** 装载数据并立即重绘；默认显示最后一段 */
    public void setData(String title, List<Quote> quotes) {
        mTitle = title == null ? "" : title;
        mData = quotes == null ? new ArrayList<Quote>() : new ArrayList<>(quotes);
        mPendingTail = true;
        // 无成交量（如部分外汇源）时默认展示 MACD，而不是空面板（与脚本口径一致）
        mPanelMode = hasVolume() ? PANEL_VOLUME : PANEL_MACD;
        mSelected = -1;
        computeVisible();
        publishSelection();
        invalidate();
    }

    /** 循环切换底部副图，返回当前副图名 */
    public String cyclePanel() {
        mPanelMode = (mPanelMode + 1) % PANEL_NAMES.length;
        invalidate();
        return PANEL_NAMES[mPanelMode];
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
        float axisH = dp(18);
        float top = mHeaderHeight;
        float rest = h - top - axisH - gap - padB;
        if (rest < dp(60)) {
            mMainRect.set(padL, top, w - padR, Math.max(top + dp(30), h - padB));
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
        mTextPaint.setColor(COLOR_TEXT);
        mTextPaint.setTextSize(sp(15));
        mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(label, left, sp(17), mTextPaint);
        mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

        // 第二行：代码 / 数据源 / 周期（有名称时才另起一行，避免重复）+ 最新价与涨跌
        mTextPaint.setTextSize(sp(11));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        if (!mName.isEmpty()) {
            mTextPaint.setColor(COLOR_TEXT_DIM);
            canvas.drawText(mTitle, left, sp(32), mTextPaint);
        }
        if (!mData.isEmpty()) {
            Quote last = mData.get(mData.size() - 1);
            float base = mData.size() > 1 ? mData.get(mData.size() - 2).close : last.open;
            mTextPaint.setColor(last.isUp() ? COLOR_UP : COLOR_DOWN);
            mTextPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.US, "%s  %+.2f%%", fmt(last.close), last.changePercent(base)),
                    getWidth() - dp(10), sp(32), mTextPaint);
        }
    }

    private void drawEmpty(Canvas canvas) {
        mTextPaint.setColor(COLOR_TEXT_DIM);
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
        mLinePaint.setColor(COLOR_GRID);
        mLinePaint.setPathEffect(null);
        if (!mPanelRect.isEmpty()) {
            canvas.drawLine(mMainRect.left, mMainRect.bottom, mMainRect.right, mMainRect.bottom, mLinePaint);
        }
        // 5 条水平线 + 右侧价格刻度
        mTextPaint.setColor(COLOR_TEXT_DIM);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i <= 4; i++) {
            float y = mMainRect.top + mMainRect.height() * i / 4f;
            drawDashedLine(canvas, mMainRect.left, y, mMainRect.right, y, COLOR_GRID);
            canvas.drawText(fmt(priceAt(y, range)), mMainRect.right + dp(4), y + sp(3), mTextPaint);
        }
    }

    private void drawCandles(Canvas canvas, float[] range) {
        // 与脚本一致：K线越密实体越宽，避免整片糊在一起
        float body = mVisible <= 240 ? 0.62f : (mVisible <= 600 ? 0.76f : 0.9f);
        float halfWidth = Math.max(0.6f, mSlot * body / 2f);
        mPaint.setStyle(Paint.Style.FILL);
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
            Quote q = mData.get(i);
            int color = q.isUp() ? COLOR_UP : COLOR_DOWN;
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

    private void drawMovingAverages(Canvas canvas, float[] range) {
        float[] closes = Indicators.closes(mData);
        for (int m = 0; m < MA_WINDOWS.length; m++) {
            float[] ma = Indicators.sma(closes, MA_WINDOWS[m]);
            mLinePaint.setColor(MA_COLORS[m]);
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
                mTextPaint.setColor(MA_COLORS[m]);
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
                last.isUp() ? COLOR_UP : COLOR_DOWN);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(last.isUp() ? COLOR_UP : COLOR_DOWN);
        canvas.drawRect(mMainRect.right + dp(2), y - sp(7), getWidth() - dp(2), y + sp(7), mPaint);
        mTextPaint.setColor(0xFF101318);
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
                mPaint.setColor(value >= 0 ? COLOR_UP : COLOR_DOWN);
                float y0 = mid - value / max * half;
                canvas.drawRect(x - barWidth, Math.min(y0, mid), x + barWidth, Math.max(y0, mid), mPaint);
            }
            drawSeries(canvas, macd.dif, COLOR_DIF, max, true);
            drawSeries(canvas, macd.dea, COLOR_DEA, max, true);
            mLinePaint.setColor(COLOR_GRID);
            mLinePaint.setPathEffect(null);
            mLinePaint.setStrokeWidth(1f);
            canvas.drawLine(left, mid, mPanelRect.right, mid, mLinePaint);
            drawPanelScale(canvas, String.format(Locale.US, "%.2f", max), top);
            drawPanelLegend(canvas, new String[]{"DIF", "DEA"}, new int[]{COLOR_DIF, COLOR_DEA});
        } else if (mode == PANEL_RSI) {
            float[] rsi = Indicators.rsi(closes, 14);
            drawLevels(canvas, new float[]{30f, 50f, 70f});
            drawSeries(canvas, rsi, COLOR_RSI, 100f, false);
            drawPanelScale(canvas, "100", top);
            drawPanelScale(canvas, "0", bottom);
        } else if (mode == PANEL_KDJ) {
            Indicators.Kdj kdj = Indicators.kdj(Indicators.highs(mData), Indicators.lows(mData),
                    closes, 9, 3, 3);
            drawLevelsScaled(canvas, new float[]{20f, 50f, 80f}, -20f, 120f);
            drawSeriesScaled(canvas, kdj.k, COLOR_DEA, -20f, 120f);   // K
            drawSeriesScaled(canvas, kdj.d, COLOR_DIF, -20f, 120f);   // D
            drawSeriesScaled(canvas, kdj.j, COLOR_RSI, -20f, 120f);   // J
            drawPanelScale(canvas, "100", panelY(100f, -20f, 120f));
            drawPanelScale(canvas, "0", panelY(0f, -20f, 120f));
            drawPanelLegend(canvas, new String[]{"K", "D", "J"},
                    new int[]{COLOR_DEA, COLOR_DIF, COLOR_RSI});
        } else {
            float max = 0f;
            for (int i = mStart; i < mStart + mVisible && i < mData.size(); i++) {
                max = Math.max(max, mData.get(i).volume);
            }
            if (max <= 0f) {
                max = 1f;
                mTextPaint.setColor(COLOR_TEXT_DIM);
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
                mPaint.setColor(q.isUp() ? COLOR_UP : COLOR_DOWN);
                canvas.drawRect(x - barWidth, barTop, x + barWidth, bottom, mPaint);
            }
            drawPanelScale(canvas, compact(max), top);
        }

        mTextPaint.setColor(COLOR_TEXT_DIM);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(PANEL_NAMES[mode], left + dp(2), top + sp(10), mTextPaint);
    }

    /** 副图纵轴：把数值映射到 [min, max] 区间（KDJ 用 -20~120，J 常冲出 0~100） */
    private float panelY(float value, float min, float max) {
        return mPanelRect.bottom - (value - min) / (max - min) * mPanelRect.height();
    }

    private void drawLevelsScaled(Canvas canvas, float[] levels, float min, float max) {
        mLinePaint.setColor(COLOR_GRID);
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
        mLinePaint.setColor(COLOR_GRID);
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
        mTextPaint.setColor(COLOR_TEXT_DIM);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(text, mPanelRect.right + dp(4), Math.min(mPanelRect.bottom, y + sp(9)), mTextPaint);
    }

    private void drawTimeAxis(Canvas canvas) {
        float y = mAxisTop + sp(12);
        mTextPaint.setColor(COLOR_TEXT_DIM);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        int step = Math.max(1, mVisible / 6);
        SimpleDateFormat fmt = new SimpleDateFormat(timePattern(), Locale.US);
        for (int i = mStart; i < mStart + mVisible && i < mData.size(); i += step) {
            String label = formatTime(mData.get(i).time, fmt);
            float x = xOf(i);
            if (x < mMainRect.left || x > getWidth() - dp(4)) continue;
            canvas.drawText(label, x, y, mTextPaint);
        }
    }

    private void drawSelection(Canvas canvas, float[] range) {
        if (mSelected < 0 || mSelected >= mData.size()) return;
        Quote q = mData.get(mSelected);
        float x = xOf(mSelected);
        drawDashedLine(canvas, x, mMainRect.top, x, mPanelRect.isEmpty() ? mMainRect.bottom : mPanelRect.bottom,
                COLOR_CURSOR);
        drawDashedLine(canvas, mMainRect.left, yOf(q.close, range), x, yOf(q.close, range), COLOR_CURSOR);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(q.isUp() ? COLOR_UP : COLOR_DOWN);
        canvas.drawCircle(x, yOf(q.close, range), Math.max(2.5f, dp(2.5f)), mPaint);
    }

    /**
     * 详情小窗口（浮窗）：主图/副图十字光标处弹出，显示该根K线的时间/开/高/低/收/量/涨跌。
     * 位置自动避让（光标在右半屏则浮窗放左侧，光标在上半屏则浮窗放下方）。
     */
    private void drawTooltip(Canvas canvas, float[] range) {
        if (mSelected < 0 || mSelected >= mData.size()) return;
        Quote q = mData.get(mSelected);
        float base = mSelected > 0 ? mData.get(mSelected - 1).close : q.open;
        String[] lines = {
                q.time,
                "开 " + fmt(q.open) + "   高 " + fmt(q.high),
                "低 " + fmt(q.low) + "   收 " + fmt(q.close),
                "量 " + (hasVolume() ? compact(q.volume) : "-"),
                "涨跌 " + String.format(Locale.US, "%+.2f%%", q.changePercent(base))
        };

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
        mPaint.setColor(0xEE151A21);
        canvas.drawRoundRect(new RectF(boxX, boxY, boxX + boxW, boxY + boxH), dp(6), dp(6), mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1f);
        mPaint.setColor(0x664A5568);
        canvas.drawRoundRect(new RectF(boxX, boxY, boxX + boxW, boxY + boxH), dp(6), dp(6), mPaint);

        int save = canvas.save();
        canvas.clipRect(boxX, boxY, boxX + boxW, boxY + boxH);
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < lines.length; i++) {
            mTextPaint.setColor(i == 0 ? COLOR_TEXT_DIM : (i == lines.length - 1
                    ? (q.isUp() ? COLOR_UP : COLOR_DOWN) : COLOR_TEXT));
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
        mListener.onQuoteInfo(String.format(Locale.US,
                "%s  开 %s 高 %s 低 %s 收 %s%s  涨跌 %+.2f%%",
                q.time, fmt(q.open), fmt(q.high), fmt(q.low), fmt(q.close), volume,
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
