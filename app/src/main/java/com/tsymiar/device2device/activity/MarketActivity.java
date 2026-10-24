package com.tsymiar.device2device.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatSpinner;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.market.Quote;
import com.tsymiar.device2device.market.QuoteSource;
import com.tsymiar.device2device.market.QuoteSource.Symbol;
import com.tsymiar.device2device.market.MarketPalette;
import com.tsymiar.device2device.market.Snapshot;
import com.tsymiar.device2device.view.KLineView;
import com.tsymiar.device2device.widget.MarketChartWidgetProvider;
import com.tsymiar.device2device.widget.MarketHeatWidgetProvider;
import com.tsymiar.device2device.widget.MarketWidgetProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行情(K线)页面。
 *
 * 功能对应 MyAutomatic/toolset/matkline.py：
 *  - --source：数据源下拉（按代码形态分类：A股 / 东方财富 / 沪金 / 伦敦金 / 纽约金 /
 *              原油 / 布伦特 / 天然气 / 美元指数 / 币安 / 自动）
 *  - --interval：周期下拉，列表随所选数据源变化（各源支持的周期不同）
 *  - --symbol：标的输入框，可直接填代码，也可填股票名称/拼音（先搜索再取数，主图显示名称）
 *  - --limit：每屏最多 320 根（可左右拖动查看更早历史）
 *  - 自动更新 ≈ --live + --refresh（默认 15 秒）
 *  - 蜡烛图绘制、MA/成交量/MACD/RSI/KDJ 均在 KLineView 中完成；单击主图弹出详情小窗口
 *  - 数据源/周期/标的/自动更新开关均写入 SharedPreferences，退出再进自动恢复上次选择
 */
public class MarketActivity extends AppCompatActivity {

    private static final int LIMIT = 320;
    private static final long REFRESH_MS = 15000L;
    /** 一轮取数超过这个时长还没回来，就当这次回调丢了：解锁下一次加载，免得永远停在"加载中…" */
    private static final long LOAD_STALE_MS = 60000L;

    /** 上次选择的持久化（退出 Activity 再回来自动恢复） */
    private static final String PREF = "market_prefs";
    private static final String K_SOURCE = "source";
    private static final String K_INTERVAL = "interval";
    private static final String K_SYMBOL = "symbol";
    private static final String K_AUTO = "auto_refresh";
    /**
     * 代码 → 名称的小本本（多行 CATALOG 形式）。
     * 名称只在少数路径拿得到（搜索结果、热力图点击），其余靠网络反查，
     * 反查挂掉标题上就只剩一行代码。查到过的记住，下次进页面先把名字显示出来。
     */
    private static final String K_NAMES = "names";
    private static final int NAMES_MAX = 200;

    // 配色：日间 / 夜间两套，由 MarketPalette 从 color 资源（values / values-night）取，
    // 随系统深浅模式自动切换；下面这些字段在 applyPalette() 里赋值
    private MarketPalette mPalette;
    private int C_BG;                    // 页面 / 图表底色
    private int C_FIELD;                 // 输入框 / 下拉底色
    private int C_FIELD_STROKE;          // 输入框 / 下拉描边
    private int C_BTN;                   // 次要按钮 常态
    private int C_BTN_STROKE;
    private int C_BTN_PRESSED;           // 次要按钮 按下
    private int C_BTN_PRESSED_STROKE;
    private int C_PRIMARY;               // 主按钮 查询
    private int C_PRIMARY_PRESSED;
    private int C_ON_PRIMARY;            // 主按钮上的文字
    private int C_ON;                    // 自动更新 开
    private int C_ON_PRESSED;
    private int C_ON_TEXT;               // 橙底上的文字
    private int C_TEXT;                  // 输入文本
    private int C_TEXT_DIM;              // 状态文字
    private int C_TEXT_HINT;             // 底部提示文字
    private int C_EDIT_HINT;             // 输入框 hint

    private AppCompatSpinner mSourceSpinner;
    private AppCompatSpinner mIntervalSpinner;
    private EditText mSymbolEdit;
    private KLineView mChart;
    private TextView mStatus;
    private TextView mHint;
    private TextView mNineBtn;
    /** 九转 + 反弹信号是否显示（一个开关管两类信号） */
    private boolean mShowNine = true;

    private String mSource = QuoteSource.AUTO;
    private String mInterval = "1d";
    /**
     * 重建周期下拉期间置 true。
     * Spinner.setAdapter() 会补发一次 onItemSelected(position=0)，若不拦住，
     * 恢复/切换数据源时当前周期会被悄悄改回列表第一项（1m），显示的周期就不对了。
     */
    private boolean mIntervalRebuilding = false;
    /** 重建后 expect 的下标：收到它才算重建真正落地，可以放行后续事件 */
    private int mExpectedIntervalIndex = 0;
    /** 当前数据源的周期列表：下拉显示中文名，选中时按下标回取原始周期（如 季K -> 1Q） */
    private String[] mIntervals = QuoteSource.intervals(QuoteSource.AUTO);
    private boolean mAutoRefresh = false;
    private boolean mLoading = false;
    private long mLoadStartAt = 0L;     // 本轮取数发起时刻，配合 LOAD_STALE_MS 兜底
    /**
     * 一轮取数的序号，每次发起都递增。
     * 多源级联最坏要跑上百秒（换 URL、换数据源重试，每跳都是新的连接预算），
     * 慢回来的那次结果会把图表换成上一个标的 / 上一个周期的数据，回调里据此把它作废。
     */
    private int mLoadSeq = 0;
    /** 状态行基准文案：市值到了再按它重拼，避免自动更新把同一段文字反复追加越来越长 */
    private String mStatusBase = "";
    private AlertDialog mPickDialog;    // 选标的对话框，Activity 销毁时要一起收掉
    private String mName = "";          // 当前标的中文名（主图首行）
    private String mNameCode = "";      // mName 属于哪个代码（纯数字），换标的时据此判定这个名字会不会串
    private String mSymbol = "";        // 输入框内容（恢复上次选择时用）

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mAutoRefresh) return;
            load();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.market_quote);

        applyPalette();      // 配色先按当前深浅模式定下来，再搭界面
        // 从小部件某一行跳进来时带着标的，下面 restorePrefs 会把它读成当前标的
        boolean fromWidget = MarketWidgetProvider.applyLaunchSymbol(this, getIntent());
        restorePrefs();      // 先恢复上次选择，再按它初始化下拉
        if (fromWidget) {
            // 名称也一并带过来了：它是这块色块/这一行列表上已经显示过的名字，直接落进标题
            applyName(mSymbol, getIntent().getStringExtra(MarketWidgetProvider.EXTRA_NAME));
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(C_BG));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        root.addView(buildSelectRow());
        root.addView(buildSymbolRow());
        buildChart(root);
        root.addView(buildBottom());

        // 屏幕常亮：自动更新时不希望熄屏中断行情查看
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 数据源下拉选中上次选中的那一档（false 表示不要触发 onItemSelected 里的重复加载）
        int sourceIndex = Arrays.asList(QuoteSource.SOURCES).indexOf(mSource);
        mSourceSpinner.setSelection(Math.max(0, sourceIndex), false);

        setContentView(root);
        refreshIntervals(false);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startAutoRefresh();     // 开关是从 preferences 恢复的，回到前台接着按它走
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePrefs();
        // 页面不可见就停止轮询：后台刷新拿到的结果只会打到看不见的界面上，白白耗流量和电
        stopAutoRefresh();
    }

    /** 起/停都做成幂等的，任何时候重复调用队列里都只留一个定时任务 */
    private void startAutoRefresh() {
        mHandler.removeCallbacks(mRefreshRunnable);
        if (mAutoRefresh && !isFinishing()) mHandler.postDelayed(mRefreshRunnable, REFRESH_MS);
    }

    private void stopAutoRefresh() {
        mHandler.removeCallbacks(mRefreshRunnable);
    }

    /**
     * 小部件某一行点进来时，若行情页已在栈里（CLEAR_TOP 复用 / singleTop），
     * 新 intent 会走这里而不是 onCreate，也要切到点击的那个标的。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (MarketWidgetProvider.applyLaunchSymbol(this, intent)) {
            restorePrefs();
            applyLaunchSymbolToUi(intent.getStringExtra(MarketWidgetProvider.EXTRA_NAME));
        }
    }

    /** 把当前标的写回输入框与数据源下拉，并立刻重新取数 */
    private void applyLaunchSymbolToUi(String launchName) {
        if (mSymbolEdit != null) {
            mSymbolEdit.setText(mSymbol);
            mSymbolEdit.setSelection(mSymbol == null ? 0 : mSymbol.length());
        }
        int sourceIndex = Arrays.asList(QuoteSource.SOURCES).indexOf(mSource);
        if (mSourceSpinner != null) {
            mSourceSpinner.setSelection(Math.max(0, sourceIndex), false);
        }
        if (launchName != null && !launchName.isEmpty()) {
            applyName(mSymbol, launchName);
        } else {
            // 这次没带名称：留在标题上的还是上一只票的名字，先摘掉
            mName = "";
            mNameCode = "";
            if (mChart != null) mChart.setName("");
        }
        refreshIntervals(false);
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        savePrefs();
        // 序号一跳，所有在途回调都因轮次过期被丢弃，不会再打到半销毁的 Activity 上
        mLoadSeq++;
        dismissPickDialog();
        mAutoRefresh = false;
        mHandler.removeCallbacks(mRefreshRunnable);
    }

    // ===== 记忆上次选择 =====
    private void restorePrefs() {
        SharedPreferences prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        mSource = prefs.getString(K_SOURCE, QuoteSource.AUTO);
        // 历史版本里存过已下线的源（如 okx）时回落到 auto
        if (!Arrays.asList(QuoteSource.SOURCES).contains(mSource)) mSource = QuoteSource.AUTO;
        mInterval = prefs.getString(K_INTERVAL, "1d");
        mSymbol = prefs.getString(K_SYMBOL, QuoteSource.defaultSymbol(mSource));
        mAutoRefresh = prefs.getBoolean(K_AUTO, false);
        // 名字本本里有这只票就先把名字顶上，不必等网络反查
        mNameCode = digitsOf(mSymbol);
        String known = nameOf(prefs, mSymbol);
        mName = known == null ? "" : known;
    }

    private void savePrefs() {
        SharedPreferences prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        prefs.edit()
                .putString(K_SOURCE, mSource)
                .putString(K_INTERVAL, mInterval)
                .putString(K_SYMBOL, symbolText())
                .putBoolean(K_AUTO, mAutoRefresh)
                .apply();
    }

    /** 按当前日间 / 夜间模式取整套行情配色；深浅模式切换时 Activity 会重建，这里会重新走一遍 */
    private void applyPalette() {
        mPalette = MarketPalette.of(this);
        C_BG = mPalette.bg;
        C_FIELD = mPalette.panel;
        C_FIELD_STROKE = mPalette.stroke;
        C_BTN = mPalette.panel;
        C_BTN_STROKE = mPalette.stroke;
        C_BTN_PRESSED = mPalette.panelPressed;
        C_BTN_PRESSED_STROKE = mPalette.strokePressed;
        C_PRIMARY = mPalette.accent;
        C_PRIMARY_PRESSED = mPalette.accentPressed;
        C_ON_PRIMARY = mPalette.onAccent;
        C_ON = mPalette.on;
        C_ON_PRESSED = mPalette.onPressed;
        C_ON_TEXT = mPalette.onText;
        C_TEXT = mPalette.text;
        C_TEXT_DIM = mPalette.textDim;
        C_TEXT_HINT = mPalette.textHint;
        C_EDIT_HINT = mPalette.textHint;
    }

    // ===== 界面构建 =====
    /** 1 分钟这种超短周期改画折线（分时）：同屏几百根蜡烛过密，折线更易读趋势 */
    private boolean isMinuteInterval() {
        return "1m".equals(mInterval);
    }

    /** 第一行：数据源下拉 + 周期下拉 */
    private LinearLayout buildSelectRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        mSourceSpinner = spinner(QuoteSource.SOURCES.length);
        String[] sourceLabels = new String[QuoteSource.SOURCES.length];
        for (int i = 0; i < QuoteSource.SOURCES.length; i++) {
            sourceLabels[i] = QuoteSource.label(QuoteSource.SOURCES[i]);
        }
        mSourceSpinner.setAdapter(adapter(sourceLabels));
        mSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String source = QuoteSource.SOURCES[position];
                if (source.equals(mSource)) return;
                mSource = source;
                // 换数据源=换一套指标口径（有无成交量都可能变），副图选择回到该源默认
                mChart.resetPanelChoice();
                // 商品源：默认标的直接进编辑框（可看可改）；其它源清空，只留该源代码写法的提示
                String defaultSym = QuoteSource.isCommodity(mSource)
                        ? QuoteSource.defaultSymbol(mSource) : "";
                mSymbolEdit.setText(defaultSym);
                if (!defaultSym.isEmpty()) {
                    mSymbolEdit.setSelection(defaultSym.length());
                }
                mSymbolEdit.setHint(QuoteSource.symbolHint(mSource));
                mName = "";
                mChart.setName("");
                savePrefs();
                refreshIntervals(true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        mIntervalSpinner = spinner(QuoteSource.SOURCES.length);
        mIntervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 重建下拉时 Spinner 补发的选中事件不算用户操作，直接忽略；
                // 收到期望下标说明重建已落地，立刻放行，避免把用户真正的操作一起吞掉
                if (mIntervalRebuilding) {
                    if (position == mExpectedIntervalIndex) mIntervalRebuilding = false;
                    return;
                }
                // 下拉里是「季K/年K」这类中文名，周期值要按下标从 mIntervals 回取
                if (mIntervals == null || position < 0 || position >= mIntervals.length) return;
                String interval = mIntervals[position];
                if (interval.equals(mInterval)) return;
                mInterval = interval;
                savePrefs();
                load();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(38), 1f);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(38), 0.8f);
        right.setMargins(dp(6), 0, 0, 0);
        row.addView(mSourceSpinner, left);
        row.addView(mIntervalSpinner, right);
        return row;
    }

    /** 第二行：代码输入 + 查询 + 自动更新 */
    private LinearLayout buildSymbolRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        mSymbolEdit = new EditText(this);
        mSymbolEdit.setBackground(rounded(C_FIELD, C_FIELD_STROKE));
        mSymbolEdit.setHint(QuoteSource.symbolHint(mSource));
        mSymbolEdit.setSingleLine();
        mSymbolEdit.setTextColor(C_TEXT);
        mSymbolEdit.setHintTextColor(C_EDIT_HINT);
        mSymbolEdit.setTextSize(13);
        mSymbolEdit.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (!TextUtils.isEmpty(mSymbol)) {
            mSymbolEdit.setText(mSymbol);
            mSymbolEdit.setSelection(mSymbol.length());
        }
        mSymbolEdit.setOnEditorActionListener((v, actionId, event) -> {
            load();
            return true;
        });
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        row.addView(mSymbolEdit, editLp);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        btnLp.setMargins(dp(6), 0, 0, 0);
        row.addView(primaryButton("查询", v -> load()), btnLp);

        // 九转与反弹共用一个开关：两者都是「在K线上标信号」，一起开一起关比各占一个按钮清爽
        mNineBtn = new TextView(this);
        mNineBtn.setGravity(Gravity.CENTER);
        mNineBtn.setTextSize(13);
        mNineBtn.setPadding(dp(10), 0, dp(10), 0);
        mNineBtn.setClickable(true);
        mNineBtn.setFocusable(true);
        mNineBtn.setOnClickListener(v -> {
            mShowNine = !mShowNine;
            mChart.setShowSignals(mShowNine);
            updateNineBtn();
        });
        row.addView(mNineBtn, btnLp);
        updateNineBtn();

        // 自动刷新放最右：它是这一行里最「全局」的一个开关，跟查询、九转这些当次操作分开
        row.addView(refreshButton(), btnLp);
        return row;
    }

    private void buildChart(LinearLayout root) {
        mChart = new KLineView(this);
        mChart.setPalette(mPalette);      // K线坐标/均线/涨跌一整套跟着日间或夜间走
        mChart.setLineMode(isMinuteInterval());
        root.addView(mChart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private LinearLayout buildBottom() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        mStatus = new TextView(this);
        mStatus.setTextColor(C_TEXT_DIM);
        mStatus.setTextSize(11);
        mStatus.setText("加载中…");
        mHint = new TextView(this);
        mHint.setTextColor(C_TEXT_HINT);
        mHint.setTextSize(11);
        mHint.setText("可直接输入名称/拼音（紫金矿业、zjky）或代码 · 拖动查看历史 · 双指缩放 · 单击副图切换 成交量/MACD/RSI/KDJ");
        box.addView(mStatus);
        box.addView(mHint);
        return box;
    }

    private AppCompatSpinner spinner(int items) {
        AppCompatSpinner spinner = new AppCompatSpinner(this);
        spinner.setBackground(rounded(C_FIELD, C_FIELD_STROKE));
        spinner.setPopupBackgroundDrawable(new ColorDrawable(C_FIELD));
        return spinner;
    }

    /** 收起态用带箭头的布局，展开后的列表项用无箭头布局 */
    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.item_market_spinner_selected, items);
        adapter.setDropDownViewResource(R.layout.item_market_spinner);
        return adapter;
    }

    /** 主按钮：青绿实心（项目主强调色） */
    private TextView primaryButton(String text, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(C_ON_PRIMARY);
        button.setTextSize(13);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(buttonBg(C_PRIMARY, 0, C_PRIMARY_PRESSED, 0));
        button.setOnClickListener(listener);
        return button;
    }

    /** 次要按钮：关=描边灰，开=橙色实心 */
    private TextView refreshButton() {
        TextView button = new TextView(this);
        button.setText("自动更新 关");
        button.setGravity(Gravity.CENTER);
        button.setTextSize(12);
        button.setPadding(dp(10), 0, dp(10), 0);
        applyRefreshStyle(button);
        button.setOnClickListener(v -> {
            mAutoRefresh = !mAutoRefresh;
            if (mAutoRefresh) startAutoRefresh();
            else stopAutoRefresh();
            applyRefreshStyle(button);
            savePrefs();
        });
        return button;
    }

    private void applyRefreshStyle(TextView button) {
        button.setText(mAutoRefresh ? "自动更新 开" : "自动更新 关");
        button.setTextColor(mAutoRefresh ? C_ON_TEXT : C_TEXT);
        button.setBackground(mAutoRefresh
                ? buttonBg(C_ON, 0, C_ON_PRESSED, 0)
                : buttonBg(C_BTN, C_BTN_STROKE, C_BTN_PRESSED, C_BTN_PRESSED_STROKE));
    }

    /** 数据源变化后重建周期列表；若当前周期不被支持则回落到该源默认周期 */
    private void updateNineBtn() {
        if (mNineBtn == null) return;
        mNineBtn.setText(mShowNine ? "九转反弹 开" : "九转反弹 关");
        mNineBtn.setTextColor(mShowNine ? C_ON_TEXT : C_TEXT);
        mNineBtn.setBackground(mShowNine
                ? buttonBg(C_ON, 0, C_ON_PRESSED, 0)
                : buttonBg(C_BTN, C_BTN_STROKE, C_BTN_PRESSED, C_BTN_PRESSED_STROKE));
    }

    private void refreshIntervals(boolean reload) {
        String[] intervals = QuoteSource.intervals(mSource);
        mIntervals = intervals;
        String[] labels = new String[intervals.length];
        for (int i = 0; i < intervals.length; i++) labels[i] = QuoteSource.intervalLabel(intervals[i]);
        mIntervalRebuilding = true;
        mExpectedIntervalIndex = 0;
        mIntervalSpinner.setAdapter(adapter(labels));
        int index = 0;
        for (int i = 0; i < intervals.length; i++) {
            if (intervals[i].equals(mInterval)) index = i;
        }
        if (!mInterval.equals(intervals[index])) {
            // 当前周期不被该数据源支持：回落到日线，都不支持就用第一项
            index = -1;
            for (int i = 0; i < intervals.length; i++) {
                if ("1d".equals(intervals[i])) index = i;
            }
            if (index < 0) index = 0;
            mInterval = intervals[index];
        }
        mExpectedIntervalIndex = index;
        mIntervalSpinner.setSelection(index, false);
        // 兜底：万一补发的选中事件迟迟不来（或顺序和预期不同），两帧后也必须放行
        mIntervalSpinner.postDelayed(() -> mIntervalRebuilding = false, 200);
        if (mChart != null) mChart.setInterval(mInterval);
        if (mSymbolEdit != null) mSymbolEdit.setHint(QuoteSource.symbolHint(mSource));
        if (reload) load();
    }

    // ===== 取数 =====
    /** 周期显示文本：1d 之类原始值看不出是什么线，日K/周K 更直观，两者都给出来便于核对 */
    private String intervalText() {
        String label = QuoteSource.intervalLabel(mInterval);
        if (label == null || label.isEmpty()) return mInterval;
        return label;
    }

    /** 输入框内容（为空时按数据源默认标的） */
    private String symbolText() {
        String text = mSymbolEdit == null ? "" : mSymbolEdit.getText().toString().trim();
        return text.isEmpty() ? QuoteSource.defaultSymbol(mSource) : text;
    }

    /** 回调到达时页面是否还活着：旋转屏幕 / 深浅色重建 Activity 时 isFinishing() 仍是 false */
    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    /** 回调所属轮次是否仍是最新一轮 */
    private boolean isCurrent(int seq) {
        return seq == mLoadSeq;
    }

    /** 状态行只走这一个出口，顺带记下基准文案（市值到货时按它重拼） */
    private void setStatus(String text) {
        mStatusBase = text == null ? "" : text;
        if (mStatus != null) mStatus.setText(mStatusBase);
    }

    /** 查询：输入是名称/拼音时先搜索出代码，再按代码取K线 */
    private void load() {
        // 上一次超过一分钟还没回调，多半是接口挂住或回调丢了；解锁，否则从此再也不刷新了
        if (mLoading && System.currentTimeMillis() - mLoadStartAt > LOAD_STALE_MS) mLoading = false;
        if (mLoading) return;
        mLoading = true;
        mLoadStartAt = System.currentTimeMillis();
        final int seq = ++mLoadSeq;
        String input = symbolText();
        if (QuoteSource.needsSearch(input)) {
            setStatus("搜索中…  " + input);
            QuoteSource.searchSymbol(input, new QuoteSource.SearchCallback() {
                @Override
                public void onResult(List<Symbol> items) {
                    if (!isCurrent(seq)) return;      // 期间又换过标的，这次结果作废
                    mLoading = false;
                    if (!alive()) return;
                    if (items == null || items.isEmpty()) {
                        setStatus("未找到匹配的标的：" + input);
                        return;
                    }
                    if (items.size() == 1) {
                        applySymbol(items.get(0));
                        loadQuotes();
                    } else {
                        pickSymbol(items);
                    }
                }

                @Override
                public void onError(String message) {
                    if (!isCurrent(seq)) return;
                    mLoading = false;
                    if (!alive()) return;
                    setStatus("搜索失败：" + message);
                }
            });
            return;
        }
        loadQuotes();
    }

    /** 多个同名/近似标的时让用户选一个 */
    private void pickSymbol(List<Symbol> items) {
        if (!alive()) return;
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).name + "  " + items.get(i).code;
        dismissPickDialog();
        mPickDialog = new AlertDialog.Builder(this)
                .setTitle("选择标的")
                .setItems(labels, (dialog, which) -> {
                    if (!alive()) return;
                    applySymbol(items.get(which));
                    loadQuotes();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 对话框挂在 Activity 的 window 上，Activity 一走它必须跟着走，否则 WindowLeaked */
    private void dismissPickDialog() {
        if (mPickDialog == null) return;
        if (mPickDialog.isShowing()) mPickDialog.dismiss();
        mPickDialog = null;
    }

    private void applySymbol(Symbol symbol) {
        mSymbolEdit.setText(symbol.code);
        mSymbolEdit.setSelection(symbol.code.length());
        applyName(symbol.code, symbol.name);
        // 搜到的是股票而当前数据源是商品/加密分类时，自动切回 A股源，避免查不到
        if (!QuoteSource.accepts(mSource, symbol.code)) {
            mSource = QuoteSource.TENCENT;
            int index = Arrays.asList(QuoteSource.SOURCES).indexOf(mSource);
            mSourceSpinner.setSelection(Math.max(0, index), false);
            mChart.resetPanelChoice();
            refreshIntervals(false);
        }
        savePrefs();
    }

    /**
     * 按代码取数（名称搜索完成后也走这里）。
     * 1 分钟这一档走当日分时：那时画出来的是今天开盘到现在的走势，而不是最近几百根分钟K线。
     */
    private void loadQuotes() {
        String symbol = symbolText();
        final int seq = mLoadSeq;
        mLoading = true;
        mLoadStartAt = System.currentTimeMillis();
        setStatus("加载中…  " + mSource + " / " + intervalText() + " / " + symbol);
        if (isMinuteInterval()) {
            QuoteSource.fetchIntraday(mSource, symbol, new QuoteSource.IntradayCallback() {
                @Override
                public void onResult(List<Quote> quotes, float prevClose) {
                    if (!isCurrent(seq)) return;
                    mLoading = false;
                    if (!alive()) return;
                    if (quotes == null || quotes.isEmpty()) {
                        setStatus("暂无分时数据");
                        return;
                    }
                    applyQuotes("分时", symbol, symbol, quotes, prevClose);
                }

                @Override
                public void onError(String message) {
                    if (!isCurrent(seq)) return;
                    mLoading = false;
                    if (!alive()) return;
                    setStatus("分时取数失败：" + message);
                }
            });
            return;
        }
        QuoteSource.load(mSource, symbol, mInterval, LIMIT, new QuoteSource.Callback() {
            @Override
            public void onLoaded(String source, String code, List<Quote> quotes) {
                if (!isCurrent(seq)) return;      // 期间标的 / 周期已换，这次结果作废
                mLoading = false;
                if (!alive()) return;
                applyQuotes(source, symbol, code, quotes == null ? new ArrayList<>() : quotes, 0f);
            }

            @Override
            public void onFailed(String message) {
                if (!isCurrent(seq)) return;
                mLoading = false;
                if (!alive()) return;
                setStatus("查询失败：" + message);
            }
        });
    }

    /**
     * 取到数据之后的共同流程：代码回填 → 画到图上 → 反查名称 → 顺带刷新桌面那几个部件。
     *
     * 分时与K线唯一的差别是昨收：分时数据只含当天这一段，昨收在其之外，需另行给出
     * （见 {@code KLineView.setPrevClose}）；K线自己带着上一天，传 0 让它照旧回溯。
     */
    private void applyQuotes(String source, String wanted, String code, List<Quote> bars,
                             float prevClose) {
        if (!code.equals(wanted)) {          // 归一化后的代码回填输入框
            mSymbolEdit.setText(code);
            mSymbolEdit.setSelection(code.length());
        }
        mChart.setPrevClose(isMinuteInterval() ? prevClose : 0f);
        mChart.setLineMode(isMinuteInterval());
        mChart.setInterval(mInterval);
        // 标题第二行只留代码与周期：数据源是取数用哪条线路，不是看盘要看的信息
        mChart.setData(code + "  ·  " + intervalText(), bars);
        resolveName(code);
        setStatus(source + "  symbol=" + code + "  interval=" + intervalText()
                + "  " + (isMinuteInterval() ? "点数" : "bars") + "=" + bars.size()
                + "  " + span(bars));
        loadSnapshot(code);
        savePrefs();
        // 顺带刷新桌面上的行情小部件（未单独配置的实例跟随这里的标的）
        MarketWidgetProvider.pushUpdate(MarketActivity.this);
        MarketChartWidgetProvider.pushUpdate(MarketActivity.this);
        MarketHeatWidgetProvider.pushUpdate(MarketActivity.this);
    }

    /**
     * 实时盘口（市值 / 市盈 / 换手）：K线接口不给这些字段，单独问东财一次。
     * 只有能换成 secid 的标的（A股 / 港股）才拿得到，其余回传 null，详情小窗自动少几行。
     */
    private void loadSnapshot(String code) {
        if (mChart == null) return;
        final int seq = mLoadSeq;
        mChart.setSnapshot(null);      // 换标的先把上一只的市值清掉，免得串台
        QuoteSource.fetchSnapshot(code, snapshot -> {
            if (!isCurrent(seq) || snapshot == null) return;
            if (!alive()) return;
            mChart.setSnapshot(snapshot);
            if (snapshot.totalCap > 0 && mStatus != null) {
                // 按基准文案重拼，不再往状态行上追加：自动更新每 15 秒来一次，追加会越加越长
                mStatus.setText(mStatusBase + "  市值 " + Snapshot.cap(snapshot.totalCap));
            }
        });
    }

    /**
     * 主图名称，按「已经知道 → 内置商品名 → 名字本本 → 网络反查」逐级兜底（异步，失败不影响K线）。
     *
     * 直接发一次反查时，反查挂了标题上就只剩一行代码 —— 从热力图、列表点进来时
     * 那些地方本来就有名称，白白可惜；也不能因为代码形态特殊（带后缀、非 A 股）
     * 就把已经知道的名字清掉，那是"有时只显示代码"的来源。
     */
    private void resolveName(String code) {
        if (mChart == null) return;
        final int seq = mLoadSeq;
        String digits = digitsOf(code);
        // 名字就是这个代码的，直接用；不必再去问一轮接口
        if (!digits.isEmpty() && mName != null && !mName.isEmpty() && digits.equals(mNameCode)) {
            mChart.setName(mName);
            return;
        }
        String alias = QuoteSource.aliasName(code);
        if (!alias.isEmpty()) {
            applyName(code, alias);
            return;
        }
        String cached = nameOf(getSharedPreferences(PREF, MODE_PRIVATE), code);
        if (cached != null && !cached.isEmpty()) {
            applyName(code, cached);
            return;
        }
        // 走到这里说明确实不知道它叫什么；若标题上还留着上一只票的名字，先摘掉别串台
        if (mName != null && !mName.isEmpty()) {
            mName = "";
            mNameCode = "";
            mChart.setName("");
        }
        if (digits.length() < 4 || digits.length() > 8) return;   // 认不出这只票，标题留代码本身即可
        QuoteSource.searchSymbol(digits, new QuoteSource.SearchCallback() {
            @Override
            public void onResult(List<Symbol> items) {
                if (!isCurrent(seq)) return;
                if (!alive() || items == null || items.isEmpty()) return;
                applyName(code, items.get(0).name);
            }

            @Override
            public void onError(String message) {
                // 名称反查失败不打扰看盘
            }
        });
    }

    /** 确认这个名字：写进标题，同时记进名字本本，下次不用联网也能显示 */
    private void applyName(String code, String name) {
        if (name == null || name.isEmpty()) return;
        mName = name;
        mNameCode = digitsOf(code);
        rememberName(code, name);
        if (mChart != null) mChart.setName(mName);
    }

    /** 记住这只票叫什么：按纯数字代码归档，sh600519 / 1.600519 / 600519 算同一条 */
    private void rememberName(String code, String name) {
        String digits = digitsOf(code);
        if (digits.isEmpty() || name == null || name.isEmpty()) return;
        SharedPreferences prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        LinkedHashMap<String, String> map = readNames(prefs);
        if (name.equals(map.get(digits))) return;      // 没变化就别写盘
        map.put(digits, name);
        while (map.size() > NAMES_MAX) {
            String oldest = map.keySet().iterator().next();
            map.remove(oldest);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        prefs.edit().putString(K_NAMES, sb.toString()).apply();
    }

    private static String nameOf(SharedPreferences prefs, String code) {
        return readNames(prefs).get(digitsOf(code));
    }

    private static LinkedHashMap<String, String> readNames(SharedPreferences prefs) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        String raw = prefs.getString(K_NAMES, "");
        if (raw == null || raw.isEmpty()) return map;
        for (String line : raw.split("\n")) {
            int at = line.indexOf('=');
            if (at > 0) map.put(line.substring(0, at), line.substring(at + 1));
        }
        return map;
    }

    /** 从 sh600519 / 1.600519 这类代码里取出纯数字部分 */
    private static String digitsOf(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    /** 数据实际跨度描述（对应脚本 span_text） */
    private static String span(List<Quote> quotes) {
        if (quotes == null || quotes.isEmpty()) return "无数据";
        return quotes.get(0).time + " ~ " + quotes.get(quotes.size() - 1).time;
    }

    /** 圆角矩形背景（替代 shape XML）；stroke 传 0 表示不描边 */
    private Drawable rounded(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(6));
        if (stroke != 0) drawable.setStroke(Math.max(1, dp(1)), stroke);
        return drawable;
    }

    /** 按钮背景：按下态 + 常态（替代 selector XML） */
    private Drawable buttonBg(int fill, int stroke, int pressedFill, int pressedStroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(pressedFill, pressedStroke));
        states.addState(new int[0], rounded(fill, stroke));
        return states;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
