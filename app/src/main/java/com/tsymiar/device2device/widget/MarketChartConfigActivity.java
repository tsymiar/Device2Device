package com.tsymiar.device2device.widget;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatSpinner;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.market.MarketPalette;
import com.tsymiar.device2device.market.QuoteSource;
import com.tsymiar.device2device.widget.MarketWidgetProvider.Item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 行情曲线小部件的配置页：挑「数据源 + 一个标的 + 曲线周期」。
 *
 * 这是桌面上独立的一个部件（「📈 行情曲线」），和列表版（MarketWidgetConfigActivity）
 * 是两个不同的组件：那边一次可以收多个标的，这里只收一个 —— 曲线只有一条，收多了也没处画。
 *
 * 标的列表用列表版那套读写（{@link MarketWidgetProvider#readItems} / {@code saveItems}）：
 * AppWidget id 全局唯一，按 id 存的 key 天然不会串；这里取列表里的第一个、也只有那一个。
 * 周期存 {@link MarketChartWidgetProvider#readInterval} 那份。
 *
 * 排版与列表版配置页一致（同样的配色表、圆角卡片、40dp 控件高度），
 * 内容放 ScrollView 里，「取消 / 添加到桌面」固定在底部。
 *
 * 注意兼容性：桌面不一定把 EXTRA_APPWIDGET_ID 带过来（个别第三方桌面只在 Intent 里放 IDS），
 * 拿不到时退而用「本应用最近一个这类部件」的 id，别一进来就 finish 掉（那样表现为"加不上"）。
 */
public class MarketChartConfigActivity extends AppCompatActivity {

    // 配色与行情页同一套（MarketPalette：日间 / 夜间），在 applyPalette() 里赋值
    private int C_BG;
    private int C_FIELD;
    private int C_FIELD_STROKE;
    private int C_TEXT;
    private int C_DIM;
    private int C_EDIT_HINT;
    private int C_PRIMARY;
    private int C_PRIMARY_PRESSED;
    private int C_ON_PRIMARY;
    private int C_SECOND;
    private int C_SECOND_PRESSED;

    /** 按当前日间 / 夜间模式取配色（与行情页同一套 color 资源） */
    private void applyPalette() {
        MarketPalette p = MarketPalette.of(this);
        C_BG = p.bg;
        C_FIELD = p.panel;
        C_FIELD_STROKE = p.stroke;
        C_TEXT = p.text;
        C_DIM = p.textDim;
        C_EDIT_HINT = p.textHint;
        C_PRIMARY = p.accent;
        C_PRIMARY_PRESSED = p.accentPressed;
        C_ON_PRIMARY = p.onAccent;
        C_SECOND = p.panel;
        C_SECOND_PRESSED = p.panelPressed;
    }

    private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private AppCompatSpinner mSourceSpinner;
    private EditText mSymbolEdit;
    private TextView mStatus;
    private AppCompatSpinner mIntervalSpinner;
    /** 当前下拉里的周期（跟数据源走，数据源换了候选也跟着换） */
    private String[] mIntervals = QuoteSource.intervals(QuoteSource.AUTO);
    private Item mItem;
    private TextView mCurrent;
    private String mSource = QuoteSource.AUTO;
    private String mInterval = MarketChartWidgetProvider.DEFAULT_INTERVAL;
    private boolean mSearching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.market_chart_widget_config);
        applyPalette();
        getWindow().setBackgroundDrawable(new ColorDrawable(C_BG));

        mAppWidgetId = pickWidgetId();
        // 默认取消：用户直接返回时不添加小部件
        setResult(RESULT_CANCELED, resultIntent());
        List<Item> saved = MarketWidgetProvider.readItems(this, mAppWidgetId);
        mItem = saved.get(0);              // 重新配置时把上次那个标的带出来
        mSource = mItem.source;
        mInterval = MarketChartWidgetProvider.readInterval(this, mAppWidgetId, mSource);
        setContentView(buildUi());
        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setStatus("没拿到小部件 ID：请从桌面的小部件列表里添加（不要从应用内部打开本页）");
        }
    }

    /** 桌面应该把部件 id 塞在 Intent 里；个别桌面只给 IDS，再不行就用最近一个这类部件 */
    private int pickWidgetId() {
        Intent it = getIntent();
        int id = it == null ? AppWidgetManager.INVALID_APPWIDGET_ID
                : it.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID && it != null) {
            int[] ids = it.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
            if (ids != null && ids.length > 0) id = ids[0];
        }
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            int[] ids = AppWidgetManager.getInstance(this)
                    .getAppWidgetIds(new ComponentName(this, MarketChartWidgetProvider.class));
            if (ids != null && ids.length > 0) id = ids[ids.length - 1];
        }
        return id;
    }

    private Intent resultIntent() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        scroll.addView(root);

        TextView hint = new TextView(this);
        hint.setText("只绘制走势曲线，一次只盯一个标的（自选股 / 黄金 / 原油 / 加密货币均可）；"
                + "周期决定曲线里一个点代表多久 —— 当天的分时用分钟级表示，近一周的日线用天表示。");
        hint.setTextColor(C_DIM);
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, dp(12));
        root.addView(hint);

        root.addView(buildSourceRow());
        root.addView(buildIntervalRow());
        root.addView(buildSymbolRow());
        root.addView(buildStatus());
        root.addView(buildCurrentCard());

        View buttons = buildButtons();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        root.addView(buttons, lp);
        return scroll;
    }

    private View buildSourceRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        row.addView(rowLabel("数据源"));

        mSourceSpinner = newSpinner();
        String[] labels = new String[QuoteSource.SOURCES.length];
        for (int i = 0; i < QuoteSource.SOURCES.length; i++) {
            labels[i] = QuoteSource.label(QuoteSource.SOURCES[i]);
        }
        mSourceSpinner.setAdapter(spinnerAdapter(labels));
        mSourceSpinner.setSelection(Math.max(0, Arrays.asList(QuoteSource.SOURCES).indexOf(mSource)), false);
        mSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= QuoteSource.SOURCES.length) return;
                String source = QuoteSource.SOURCES[position];
                if (source.equals(mSource)) return;
                mSource = source;
                mSymbolEdit.setHint(QuoteSource.symbolHint(mSource));
                refreshIntervals();
                setStatus("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(mSourceSpinner, fieldLp(0));
        return row;
    }

    private View buildIntervalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(10), 0, 0);
        row.setLayoutParams(rowLp);

        row.addView(rowLabel(getString(R.string.market_widget_chart_interval)));

        mIntervalSpinner = newSpinner();
        mIntervalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= mIntervals.length) return;
                mInterval = QuoteSource.normalizeInterval(mIntervals[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(mIntervalSpinner, fieldLp(0));
        refreshIntervals();
        return row;
    }

    private View buildSymbolRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        mSymbolEdit = new EditText(this);
        mSymbolEdit.setBackground(rounded(C_FIELD, C_FIELD_STROKE));
        mSymbolEdit.setHint(QuoteSource.symbolHint(mSource));
        mSymbolEdit.setSingleLine();
        mSymbolEdit.setTextColor(C_TEXT);
        mSymbolEdit.setHintTextColor(C_EDIT_HINT);
        mSymbolEdit.setTextSize(14);
        mSymbolEdit.setPadding(dp(10), dp(8), dp(10), dp(8));
        mSymbolEdit.setGravity(Gravity.CENTER_VERTICAL);
        mSymbolEdit.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(0, dp(10), 0, 0);
        mSymbolEdit.setLayoutParams(lp);
        row.addView(mSymbolEdit);

        TextView use = new TextView(this);
        use.setText("选它");
        use.setGravity(Gravity.CENTER);
        use.setTextColor(C_ON_PRIMARY);
        use.setTextSize(14);
        use.setTypeface(Typeface.DEFAULT_BOLD);
        use.setIncludeFontPadding(false);
        use.setBackground(buttonBg(C_PRIMARY, 0, C_PRIMARY_PRESSED, 0));
        use.setOnClickListener(v -> resolveSymbol());
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(dp(84), dp(40));
        useLp.setMargins(dp(8), dp(10), 0, 0);
        row.addView(use, useLp);
        return row;
    }

    /** 当前标的：改了之后一眼能看到小部件上会显示什么 */
    private View buildCurrentCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(C_BG, C_FIELD_STROKE));
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(lp);

        TextView label = new TextView(this);
        label.setText("当前标的");
        label.setTextColor(C_DIM);
        label.setTextSize(12);
        card.addView(label);

        TextView value = new TextView(this);
        value.setTextColor(C_TEXT);
        value.setTextSize(14);
        card.addView(value);
        mCurrent = value;
        refreshCurrent();
        return card;
    }

    private void refreshCurrent() {
        if (mCurrent == null) return;
        mCurrent.setText(mItem.title() + "  " + mItem.symbol
                + " · " + QuoteSource.label(mItem.source));
    }

    private View buildStatus() {
        mStatus = new TextView(this);
        mStatus.setTextColor(C_DIM);
        mStatus.setTextSize(11);
        mStatus.setPadding(0, dp(4), 0, dp(4));
        mStatus.setVisibility(View.GONE);
        return mStatus;
    }

    private void setStatus(CharSequence text) {
        if (mStatus == null) return;
        mStatus.setText(text);
        mStatus.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private View buildButtons() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setGravity(Gravity.CENTER);
        cancel.setTextColor(C_TEXT);
        cancel.setTextSize(15);
        cancel.setBackground(buttonBg(C_SECOND, C_FIELD_STROKE, C_SECOND_PRESSED, C_FIELD_STROKE));
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED, resultIntent());
            finish();
        });
        row.addView(cancel, lp);

        TextView ok = new TextView(this);
        ok.setText("添加到桌面");
        ok.setGravity(Gravity.CENTER);
        ok.setTextColor(C_ON_PRIMARY);
        ok.setTextSize(15);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setBackground(buttonBg(C_PRIMARY, 0, C_PRIMARY_PRESSED, 0));
        ok.setOnClickListener(v -> save());
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        okLp.setMargins(dp(10), 0, 0, 0);
        row.addView(ok, okLp);
        return row;
    }

    // ------------------------------------------------------------------
    // 添加 / 保存
    // ------------------------------------------------------------------

    private void resolveSymbol() {
        if (mSearching) return;
        final String input = mSymbolEdit.getText() == null
                ? "" : mSymbolEdit.getText().toString().trim();
        if (input.isEmpty()) {
            setStatus("请输入标的代码或名称");
            return;
        }
        if (!QuoteSource.needsSearch(input)) {
            useSymbol(input, QuoteSource.aliasName(input));
            return;
        }
        mSearching = true;
        setStatus("搜索中…  " + input);
        QuoteSource.searchSymbol(input, new QuoteSource.SearchCallback() {
            @Override
            public void onResult(List<QuoteSource.Symbol> items) {
                mSearching = false;
                if (isFinishing()) return;
                if (items == null || items.isEmpty()) {
                    setStatus("未找到匹配的标的：" + input);
                    return;
                }
                if (items.size() == 1) {
                    useSymbol(items.get(0).code, items.get(0).name);
                } else {
                    pickSymbol(items);
                }
            }

            @Override
            public void onError(String message) {
                mSearching = false;
                if (!isFinishing()) setStatus("搜索失败：" + message);
            }
        });
    }

    private void useSymbol(String code, String name) {
        mItem = new Item(mSource, code, name);
        mSymbolEdit.setText("");
        refreshCurrent();
        setStatus("已选用：" + (TextUtils.isEmpty(name) ? code : name));
    }

    /** 多个同名/近似的标的时让用户选一个 */
    private void pickSymbol(List<QuoteSource.Symbol> items) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).name + "  " + items.get(i).code;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择标的")
                .setItems(labels, (dialog, which) -> useSymbol(items.get(which).code, items.get(which).name))
                .setNegativeButton("取消", null)
                .show();
    }

    private void save() {
        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setStatus("没拿到小部件 ID：请从桌面长按 → 小部件里添加");
            return;
        }
        if (mItem == null || TextUtils.isEmpty(mItem.symbol)) {
            setStatus("先选一个标的");
            return;
        }
        try {
            List<Item> items = new ArrayList<>(1);
            items.add(mItem);
            MarketWidgetProvider.saveItems(this, mAppWidgetId, items);
            MarketChartWidgetProvider.saveInterval(this, mAppWidgetId, mInterval);
            setResult(RESULT_OK, resultIntent());
            // 直接渲染一次：广播在部分 ROM 上会被拦，配置完部件会一直空着
            MarketChartWidgetProvider.refreshNow(this, mAppWidgetId);
            finish();
        } catch (Throwable t) {
            setStatus("保存失败：" + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 小工具（背景一律代码生成，不新增 drawable XML）
    // ------------------------------------------------------------------

    /** 周期候选跟着数据源：数据源不支持的周期不给选，省得部件上一直刷着报错 */
    private void refreshIntervals() {
        if (mIntervalSpinner == null) return;
        mIntervals = QuoteSource.intervals(mSource);
        String[] labels = new String[mIntervals.length];
        for (int i = 0; i < mIntervals.length; i++) {
            labels[i] = QuoteSource.intervalLabel(mIntervals[i]);
        }
        mIntervalSpinner.setAdapter(spinnerAdapter(labels));
        int index = Arrays.asList(mIntervals).indexOf(mInterval);
        if (index < 0) {
            mInterval = MarketChartWidgetProvider.DEFAULT_INTERVAL;
            index = Arrays.asList(mIntervals).indexOf(mInterval);
        }
        mIntervalSpinner.setSelection(Math.max(0, index), false);
    }

    private TextView rowLabel(CharSequence text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(C_DIM);
        label.setTextSize(13);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        label.setLayoutParams(lp);
        return label;
    }

    private AppCompatSpinner newSpinner() {
        AppCompatSpinner spinner = new AppCompatSpinner(this);
        spinner.setBackground(rounded(C_FIELD, C_FIELD_STROKE));
        spinner.setPopupBackgroundDrawable(new ColorDrawable(C_FIELD));
        spinner.setMinimumHeight(dp(40));
        spinner.setPadding(dp(6), 0, dp(6), 0);
        // layout 之后若不到 40dp 再补齐一次（Spinner 不一定认 minimumHeight）
        spinner.post(new Runnable() {
            @Override
            public void run() {
                int h = spinner.getHeight();
                if (h == 0) {
                    spinner.post(this);
                    return;
                }
                if (h < dp(40)) {
                    LinearLayout.LayoutParams fix =
                            (LinearLayout.LayoutParams) spinner.getLayoutParams();
                    fix.height = dp(40);
                    spinner.setLayoutParams(fix);
                }
            }
        });
        return spinner;
    }

    private ArrayAdapter<String> spinnerAdapter(String[] labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.item_market_spinner_selected, labels);
        adapter.setDropDownViewResource(R.layout.item_market_spinner);
        return adapter;
    }

    private LinearLayout.LayoutParams fieldLp(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(10), topMargin, 0, 0);
        return lp;
    }

    private Drawable rounded(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(6));
        if (stroke != 0) drawable.setStroke(Math.max(1, dp(1)), stroke);
        return drawable;
    }

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
