package com.tsymiar.device2device.activity;

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
import com.tsymiar.device2device.view.KLineView;

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

    // 配色（与项目 GameUi 主色一致）：背景全部由代码生成，不再新增 drawable XML
    private static final int C_FIELD = 0xFF151A21;                 // 输入框 / 下拉底色
    private static final int C_FIELD_STROKE = 0xFF3C4A66;          // 输入框 / 下拉描边
    private static final int C_BTN = 0xFF151A21;                   // 次要按钮 常态
    private static final int C_BTN_STROKE = 0xFF3C4A66;
    private static final int C_BTN_PRESSED = 0xFF223040;           // 次要按钮 按下
    private static final int C_BTN_PRESSED_STROKE = 0xFF4FC3F7;
    private static final int C_PRIMARY = 0xFF00DCA0;               // 主按钮 查询
    private static final int C_PRIMARY_PRESSED = 0xFF00B384;
    private static final int C_ON = 0xFFFF964F;                    // 自动刷新 开
    private static final int C_ON_PRESSED = 0xFFE07A38;

    private AppCompatSpinner mSourceSpinner;
    private AppCompatSpinner mIntervalSpinner;
    private EditText mSymbolEdit;
    private KLineView mChart;
    private TextView mStatus;
    private TextView mHint;

    private String mSource = QuoteSource.AUTO;
    private String mInterval = "1d";
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
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF0E1116));

        restorePrefs();      // 先恢复上次选择，再按它初始化下拉
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1116);
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

    // ------------------------------------------------------------------
    // 界面构建
    // ------------------------------------------------------------------

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
                Object item = parent.getItemAtPosition(position);
                String interval = item == null ? mInterval : String.valueOf(item);
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
        mSymbolEdit.setTextColor(0xFFE8EAED);
        mSymbolEdit.setHintTextColor(0xFF7A7A8A);
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
        root.addView(mChart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private LinearLayout buildBottom() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        mStatus = new TextView(this);
        mStatus.setTextColor(0xFF9AA4B2);
        mStatus.setTextSize(11);
        mStatus.setText("加载中…");
        mHint = new TextView(this);
        mHint.setTextColor(0xFF6B7280);
        mHint.setTextSize(11);
        mHint.setText("可直接输入名称/拼音（茅台、gzmt）或代码 · 拖动查看历史 · 双指缩放 · 单击副图切换 成交量/MACD/RSI/KDJ");
        box.addView(mStatus);
        box.addView(mHint);
        return box;
    }

    private AppCompatSpinner spinner(int items) {
        AppCompatSpinner spinner = new AppCompatSpinner(this);
        spinner.setBackground(rounded(C_FIELD, C_FIELD_STROKE));
        spinner.setPopupBackgroundDrawable(new ColorDrawable(0xFF151A21));
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
        button.setTextColor(0xFF0A0A12);
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
        button.setTextColor(mAutoRefresh ? 0xFF0A0A12 : 0xFFE8EAED);
        button.setBackground(mAutoRefresh
                ? buttonBg(C_ON, 0, C_ON_PRESSED, 0)
                : buttonBg(C_BTN, C_BTN_STROKE, C_BTN_PRESSED, C_BTN_PRESSED_STROKE));
    }

    /** 数据源变化后重建周期列表；若当前周期不被支持则回落到该源默认周期 */
    private void refreshIntervals(boolean reload) {
        String[] intervals = QuoteSource.intervals(mSource);
        mIntervalSpinner.setAdapter(adapter(intervals));
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
                mChart.setData(code + "  ·  " + source + "  ·  " + mInterval, quotes);
                resolveName(code);
                mStatus.setText(source + "  symbol=" + code + "  interval=" + mInterval
                        + "  bars=" + quotes.size() + "  " + span(quotes));
                savePrefs();
            }

            @Override
            public void onFailed(String message) {
                mLoading = false;
                if (isFinishing()) return;
                mStatus.setText("查询失败：" + message);
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
