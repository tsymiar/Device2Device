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
import com.tsymiar.device2device.widget.MarketWidgetProvider;

import java.util.Arrays;
import java.util.List;

/**
 * 行情(K线)页面 —— 原「余音回响」入口改为此页面。
 *
 * 功能对应 MyAutomatic/toolset/matkline.py：
 *  - --source：数据源下拉（按代码形态分类：A股 / 东方财富 / 沪金 / 伦敦金 / 纽约金 /
 *              原油 / 布伦特 / 天然气 / 美元指数 / 币安 / 自动）
 *  - --interval：周期下拉，列表随所选数据源变化（各源支持的周期不同）
 *  - --symbol：标的输入框，可直接填代码，也可填股票名称/拼音（先搜索再取数，主图显示名称）
 *  - --limit：每屏最多 320 根（可左右拖动查看更早历史）
 *  - 自动刷新 ≈ --live + --refresh（默认 15 秒）
 *  - 蜡烛图绘制、MA/成交量/MACD/RSI/KDJ 均在 KLineView 中完成；单击主图弹出详情小窗口
 *  - 数据源/周期/标的/自动刷新开关均写入 SharedPreferences，退出再进自动恢复上次选择
 */
public class MarketActivity extends AppCompatActivity {

    private static final int LIMIT = 320;
    private static final long REFRESH_MS = 15000L;

    /** 上次选择的持久化（退出 Activity 再回来自动恢复） */
    private static final String PREF = "market_prefs";
    private static final String K_SOURCE = "source";
    private static final String K_INTERVAL = "interval";
    private static final String K_SYMBOL = "symbol";
    private static final String K_AUTO = "auto_refresh";

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
    private int C_ON;                    // 自动刷新 开
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

    private String mSource = QuoteSource.AUTO;
    private String mInterval = "1d";
    /** 当前数据源的周期列表：下拉显示中文名，选中时按下标回取原始周期（如 季K -> 1Q） */
    private String[] mIntervals = QuoteSource.intervals(QuoteSource.AUTO);
    private boolean mAutoRefresh = false;
    private boolean mLoading = false;
    private String mName = "";          // 当前标的中文名（主图首行）
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
            // 名称也一并带过来了：先顶上，取数回来会被接口给的全名替换
            String name = getIntent().getStringExtra(MarketWidgetProvider.EXTRA_NAME);
            if (name != null) mName = name;
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

        // 屏幕常亮：自动刷新时不希望熄屏中断行情查看
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 数据源下拉选中上次那一档（false 表示不要触发 onItemSelected 里的重复加载）
        int sourceIndex = Arrays.asList(QuoteSource.SOURCES).indexOf(mSource);
        mSourceSpinner.setSelection(Math.max(0, sourceIndex), false);

        setContentView(root);
        refreshIntervals(false);
        load();
        if (mAutoRefresh) mHandler.postDelayed(mRefreshRunnable, REFRESH_MS);   // 恢复上次开关状态
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePrefs();
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
        // 小部件顺带把名称也给过来了：先填上，取数回来再换成接口给的全名
        mName = launchName == null ? "" : launchName;
        if (mChart != null) mChart.setName(mName);
        refreshIntervals(false);
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        savePrefs();
        mAutoRefresh = false;
        mHandler.removeCallbacks(mRefreshRunnable);
    }

    // ------------------------------------------------------------------
    // 记忆上次选择
    // ------------------------------------------------------------------

    private void restorePrefs() {
        SharedPreferences prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        mSource = prefs.getString(K_SOURCE, QuoteSource.AUTO);
        // 历史版本里存过已下线的源（如 okx）时回落到 auto
        if (!Arrays.asList(QuoteSource.SOURCES).contains(mSource)) mSource = QuoteSource.AUTO;
        mInterval = prefs.getString(K_INTERVAL, "1d");
        mSymbol = prefs.getString(K_SYMBOL, QuoteSource.defaultSymbol(mSource));
        mAutoRefresh = prefs.getBoolean(K_AUTO, false);
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

    // ------------------------------------------------------------------
    // 界面构建
    // ------------------------------------------------------------------

    /** 1 分钟这种超短周期改画折线（分时）：同屏几百根蜡烛会糊成一片，折线更好读趋势 */
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
                // 切换分类=换标的：先清空输入框，让提示语展示该源的代码写法，再加载该源默认标的
                mSymbolEdit.setText("");
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

    /** 第二行：代码输入 + 查询 + 自动刷新 */
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
        mHint.setText("可直接输入名称/拼音（茅台、gzmt）或代码 · 拖动查看历史 · 双指缩放 · 单击副图切换 成交量/MACD/RSI/KDJ");
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
        button.setText("自动刷新 关");
        button.setGravity(Gravity.CENTER);
        button.setTextSize(12);
        button.setPadding(dp(10), 0, dp(10), 0);
        applyRefreshStyle(button);
        button.setOnClickListener(v -> {
            mAutoRefresh = !mAutoRefresh;
            mHandler.removeCallbacks(mRefreshRunnable);
            if (mAutoRefresh) mHandler.postDelayed(mRefreshRunnable, REFRESH_MS);
            applyRefreshStyle(button);
            savePrefs();
        });
        return button;
    }

    private void applyRefreshStyle(TextView button) {
        button.setText(mAutoRefresh ? "自动刷新 开" : "自动刷新 关");
        button.setTextColor(mAutoRefresh ? C_ON_TEXT : C_TEXT);
        button.setBackground(mAutoRefresh
                ? buttonBg(C_ON, 0, C_ON_PRESSED, 0)
                : buttonBg(C_BTN, C_BTN_STROKE, C_BTN_PRESSED, C_BTN_PRESSED_STROKE));
    }

    /** 数据源变化后重建周期列表；若当前周期不被支持则回落到该源默认周期 */
    private void refreshIntervals(boolean reload) {
        String[] intervals = QuoteSource.intervals(mSource);
        mIntervals = intervals;
        String[] labels = new String[intervals.length];
        for (int i = 0; i < intervals.length; i++) labels[i] = QuoteSource.intervalLabel(intervals[i]);
        mIntervalSpinner.setAdapter(adapter(labels));
        int index = 0;
        for (int i = 0; i < intervals.length; i++) {
            if (intervals[i].equals(mInterval)) index = i;
        }
        if (!mInterval.equals(intervals[index])) {
            for (int i = 0; i < intervals.length; i++) {
                if ("1d".equals(intervals[i])) index = i;
            }
            mInterval = intervals[index];
        }
        mIntervalSpinner.setSelection(index, false);
        if (mChart != null) mChart.setInterval(mInterval);
        if (mSymbolEdit != null) mSymbolEdit.setHint(QuoteSource.symbolHint(mSource));
        if (reload) load();
    }

    // ------------------------------------------------------------------
    // 取数
    // ------------------------------------------------------------------

    /** 输入框内容（为空时按数据源默认标的） */
    private String symbolText() {
        String text = mSymbolEdit == null ? "" : mSymbolEdit.getText().toString().trim();
        return text.isEmpty() ? QuoteSource.defaultSymbol(mSource) : text;
    }

    /** 查询：输入是名称/拼音时先搜索出代码，再按代码取K线 */
    private void load() {
        if (mLoading) return;
        String input = symbolText();
        if (QuoteSource.needsSearch(input)) {
            mLoading = true;
            mStatus.setText("搜索中…  " + input);
            QuoteSource.searchSymbol(input, new QuoteSource.SearchCallback() {
                @Override
                public void onResult(List<Symbol> items) {
                    mLoading = false;
                    if (isFinishing() || items == null || items.isEmpty()) {
                        if (!isFinishing()) mStatus.setText("未找到匹配的标的：" + input);
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
                    mLoading = false;
                    if (isFinishing()) return;
                    mStatus.setText("搜索失败：" + message);
                }
            });
            return;
        }
        loadQuotes();
    }

    /** 多个同名/近似标的时让用户选一个 */
    private void pickSymbol(List<Symbol> items) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).name + "  " + items.get(i).code;
        new AlertDialog.Builder(this)
                .setTitle("选择标的")
                .setItems(labels, (dialog, which) -> {
                    applySymbol(items.get(which));
                    loadQuotes();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applySymbol(Symbol symbol) {
        mSymbolEdit.setText(symbol.code);
        mSymbolEdit.setSelection(symbol.code.length());
        mName = symbol.name;
        mChart.setName(symbol.name);
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

    /** 按代码取K线（名称搜索完成后也走这里） */
    private void loadQuotes() {
        String symbol = symbolText();
        mLoading = true;
        mStatus.setText("加载中…  " + mSource + " / " + mInterval + " / " + symbol);
        QuoteSource.load(mSource, symbol, mInterval, LIMIT, new QuoteSource.Callback() {
            @Override
            public void onLoaded(String source, String code, List<Quote> quotes) {
                mLoading = false;
                if (isFinishing()) return;
                if (!code.equals(symbol)) {          // 归一化后的代码回填输入框
                    mSymbolEdit.setText(code);
                    mSymbolEdit.setSelection(code.length());
                }
                mChart.setLineMode(isMinuteInterval());
                mChart.setInterval(mInterval);
                mChart.setData(code + "  ·  " + source + "  ·  " + mInterval, quotes);
                resolveName(code);
                mStatus.setText(source + "  symbol=" + code + "  interval=" + mInterval
                        + "  bars=" + quotes.size() + "  " + span(quotes));
                loadSnapshot(code);
                savePrefs();
                // 顺带刷新桌面上的行情小部件（未单独配置的实例跟随这里的标的）
                MarketWidgetProvider.pushUpdate(MarketActivity.this);
            }

            @Override
            public void onFailed(String message) {
                mLoading = false;
                if (isFinishing()) return;
                mStatus.setText("查询失败：" + message);
            }
        });
    }

    /**
     * 实时盘口（市值 / 市盈 / 换手）：K线接口不给这些字段，单独问东财一次。
     * 只有能换成 secid 的标的（A股 / 港股）才拿得到，其余回传 null，详情小窗自动少几行。
     */
    private void loadSnapshot(String code) {
        if (mChart == null) return;
        mChart.setSnapshot(null);      // 换标的先把上一只的市值清掉，免得串台
        QuoteSource.fetchSnapshot(code, snapshot -> {
            if (isFinishing() || snapshot == null) return;
            mChart.setSnapshot(snapshot);
            if (snapshot.totalCap > 0) {
                mStatus.setText(mStatus.getText() + "  市值 " + Snapshot.cap(snapshot.totalCap));
            }
        });
    }

    /** 主图名称：商品用内置中文名，股票/指数再反查一次（异步，失败不影响K线） */
    private void resolveName(String code) {
        String alias = QuoteSource.aliasName(code);
        if (!alias.isEmpty()) {
            mName = alias;
            mChart.setName(alias + "  " + code);
            return;
        }
        // 东财写法是 1.600519，腾讯写法是 sh600519，统一取纯数字代码去反查
        String key = code.contains(".") ? code.substring(code.indexOf('.') + 1) : digitsOf(code);
        if (!key.matches("[0-9]{4,8}")) {
            mName = "";
            mChart.setName("");
            return;
        }
        if (mName != null && !mName.isEmpty() && key.equals(digitsOf(symbolText()))) return;
        QuoteSource.searchSymbol(key, new QuoteSource.SearchCallback() {
            @Override
            public void onResult(List<Symbol> items) {
                if (isFinishing() || items == null || items.isEmpty()) return;
                mName = items.get(0).name;
                mChart.setName(mName + "  " + code);
            }

            @Override
            public void onError(String message) {
                // 名称反查失败不打扰看盘
            }
        });
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
        if (quotes.isEmpty()) return "无数据";
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
