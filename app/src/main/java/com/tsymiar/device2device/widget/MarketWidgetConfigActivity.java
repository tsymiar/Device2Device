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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.market.MarketPalette;
import com.tsymiar.device2device.market.QuoteSource;
import com.tsymiar.device2device.widget.MarketWidgetProvider.Item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 行情小部件的配置页：添加小部件时挑「数据源 + 标的」，一次可以放进多个标的。
 *
 * 一个部件一份列表，按 appWidgetId 单独保存；标的数量不限（上限 MAX_ITEMS），
 * 列表可拖动排序（长按整行或拖 ☰ 手柄），这个顺序就是小部件里行的顺序；
 * 部件里的列表可滚动，所以标的多也不用特意拉高 —— 这里只管收集与排序。
 * 填名称/拼音时先搜索成代码再入列，避免小部件每次刷新都走一次搜索。
 *
 * 排版：上半屏是「数据源 + 标的输入框 + 添加」，固定不动；下半屏是已选标的列表，
 * 自己占剩下的高度并滚动 —— 标的一多也不会把输入框顶出可视区（原来整页一个 ScrollView 会）。
 *
 * 注意两点兼容性：
 * - 桌面不一定把 EXTRA_APPWIDGET_ID 带过来（个别第三方桌面只在 Intent 里放 IDS），
 *   拿不到时退而用「本应用最近一个部件」的 id，别一进来就 finish 掉（那样表现为"加不上"）；
 * - 内容是滚动的、「添加到桌面」固定在底部，标的多的时候按钮不会被挤出屏幕。
 */
public class MarketWidgetConfigActivity extends AppCompatActivity {

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
    private TextView mListLabel;
    /** 已选标的列表：拖动手柄可排序（顺序就是小部件里行的顺序） */
    private RecyclerView mList;
    private ItemAdapter mAdapter;
    private ItemTouchHelper mTouchHelper;
    private TextView mEmpty;
    private final List<Item> mItems = new ArrayList<>();
    private String mSource = QuoteSource.AUTO;
    private boolean mSearching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.market_widget_config);
        applyPalette();
        getWindow().setBackgroundDrawable(new ColorDrawable(C_BG));

        mAppWidgetId = pickWidgetId();
        // 默认取消：用户直接返回时不添加小部件
        setResult(RESULT_CANCELED, resultIntent());
        // 已有配置（重新配置时）直接带出来，可继续增删
        mItems.addAll(MarketWidgetProvider.readItems(this, mAppWidgetId));
        mSource = mItems.get(0).source;
        setContentView(buildUi());
        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setStatus("没拿到小部件 ID：请从桌面的小部件列表里添加（不要从应用内部打开本页）");
        }
    }

    /** 桌面应该把部件 id 塞在 Intent 里；个别桌面只给 IDS，再不行就用最近一个部件 */
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
                    .getAppWidgetIds(new ComponentName(this, MarketWidgetProvider.class));
            if (ids != null && ids.length > 0) id = ids[ids.length - 1];
        }
        return id;
    }

    private Intent resultIntent() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
    }

    // ------------------------------------------------------------------
    // 界面：内容可滚动，「添加到桌面」固定在底部
    // ------------------------------------------------------------------

    private View buildUi() {
        // 整页包一层 ScrollView 兜底：列表高度写死（见 listHeight()）后，屏矮 / 字号大时
        // 可能放不下，这时靠滚动补齐，而不是把某一块压扁、让内容溢出盖住下面的控件
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 整页不铺底色：顶部和输入框下面空出来的地方就透出窗口底色（见 onCreate 里的
        // setBackgroundDrawable），只有卡片 / 输入框 / 按钮这些实体控件才有自己的底色
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        scroll.addView(root);

        // 上：标题 / 说明 / 数据源 / 输入框+添加 —— 这一块不参与滚动。
        // 原来整页是一个 ScrollView，标的一多列表把页面撑长，输入框和「添加」就被挤出可视区了。
        // 同样不铺底色，也不加 elevation：阴影会正好落在它和列表之间那条间隙上，
        // 看着就像列表顶部压住了输入框（布局上并没有重叠，是阴影的锅）
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        // 页面标题省掉（ActionBar 上已经有「行情小部件设置」），说明文字直接顶到上面
        TextView hint = new TextView(this);
        hint.setText("可以放多个标的（自选股 / 黄金 / 原油 / 加密货币均可）；"
                + "长按或拖 ☰ 调整顺序，部件里每行小字标出市场代码，超出高度可上下滚动");
        hint.setTextColor(C_DIM);
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, dp(12));
        header.addView(hint);

        header.addView(buildSourceRow());
        header.addView(buildSymbolRow());
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 状态提示放回输入区和卡片之间（原来这行在卡片里）：回执出现时顺手把这块空白用上，
        // 没回执时整行收掉（见 setStatus()），间距回到下面的紧凑值
        root.addView(buildStatus());

        // 下：已选标的用 weight 占满剩余高度，标的多就卡片里自己滚，
        // 滚多少都不会动到上面的输入区；「取消 / 添加到桌面」也自然贴底。
        LinearLayout.LayoutParams listHostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        // 状态行自己带上下留白，这里只留一点间距，整页更紧凑
        listHostLp.setMargins(0, dp(12), 0, 0);
        root.addView(buildListSection(), listHostLp);

        View buttons = buildButtons();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        root.addView(buttons, lp);
        return scroll;
    }

    private View buildSourceRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 同 buildSymbolRow：不用 CENTER_VERTICAL，防止以后加垂直 margin 再踩同一个坑
        // 也不裁子控件：行高一旦小于内容（系统字号放大时）就把下沿切掉一小条
        row.setClipChildren(false);
        row.setClipToPadding(false);

        TextView label = new TextView(this);
        label.setText("数据源");
        label.setTextColor(C_DIM);
        label.setTextSize(13);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(label, labelLp);

        mSourceSpinner = new AppCompatSpinner(this);
        mSourceSpinner.setBackground(rounded(C_FIELD, C_FIELD_STROKE));
        mSourceSpinner.setPopupBackgroundDrawable(new ColorDrawable(C_FIELD));
        String[] labels = new String[QuoteSource.SOURCES.length];
        for (int i = 0; i < QuoteSource.SOURCES.length; i++) {
            labels[i] = QuoteSource.label(QuoteSource.SOURCES[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.item_market_spinner_selected, labels);
        adapter.setDropDownViewResource(R.layout.item_market_spinner);
        mSourceSpinner.setAdapter(adapter);
        int index = Arrays.asList(QuoteSource.SOURCES).indexOf(mSource);
        mSourceSpinner.setSelection(Math.max(0, index), false);
        mSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSource = QuoteSource.SOURCES[position];
                mSymbolEdit.setHint(QuoteSource.symbolHint(mSource));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        // 整页按紧凑排版：输入类控件和底部按钮统一 40dp（原来 50/52 偏高，屏小的时候挤）。
        // 40dp 是「下限」而不是死值：系统字号放大后，收起态那行文字（见
        // item_market_spinner_selected）会顶满内容区、下沿被切掉一小条，所以高度按内容量，
        // item 里同时去掉字体自带留白，保证 40dp 装得下；行也不裁子控件，双保险。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(10), 0, 0, 0);
        mSourceSpinner.setMinimumHeight(dp(40));
        mSourceSpinner.setPadding(dp(6), 0, dp(6), 0);
        row.addView(mSourceSpinner, lp);
        // layout 之后若不到 40dp 再补齐一次（Spinner 不一定认 minimumHeight）；
        // 内容本来就高于 40dp 时不做任何事，让它继续长高，绝不再把下沿切掉
        mSourceSpinner.post(new Runnable() {
            @Override
            public void run() {
                int h = mSourceSpinner.getHeight();
                if (h == 0) {                  // 还没 layout，等下一帧
                    mSourceSpinner.post(this);
                    return;
                }
                if (h < dp(40)) {
                    LinearLayout.LayoutParams fix =
                            (LinearLayout.LayoutParams) mSourceSpinner.getLayoutParams();
                    fix.height = dp(40);
                    mSourceSpinner.setLayoutParams(fix);
                }
            }
        });
        return row;
    }

    private View buildSymbolRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 别设 CENTER_VERTICAL：居中会把子控件的 topMargin 也算进去，
        // 行高 50dp、控件 40dp + topMargin 10dp 时控件被放到 15dp 处，
        // 底部 5dp 超出行界被 clipChildren 裁掉 —— 就是「下边缘缺一小条」的元凶。
        // 不设 gravity（默认 top）+ topMargin，控件正好落在 10~50dp，完整贴合。

        mSymbolEdit = new EditText(this);
        mSymbolEdit.setBackground(rounded(C_FIELD, C_FIELD_STROKE));
        mSymbolEdit.setHint(QuoteSource.symbolHint(mSource));
        mSymbolEdit.setSingleLine();
        mSymbolEdit.setTextColor(C_TEXT);
        mSymbolEdit.setHintTextColor(C_EDIT_HINT);
        mSymbolEdit.setTextSize(14);
        mSymbolEdit.setPadding(dp(10), dp(8), dp(10), dp(8));
        // 显式垂直居中 + 去掉字体自带的额外留白：不然 14sp 在系统字体放大时会顶满
        // 内容区，汉字下沿和光标底部就先被切掉（看着像控件下半截没了）
        mSymbolEdit.setGravity(Gravity.CENTER_VERTICAL);
        mSymbolEdit.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(0, dp(10), 0, 0);
        mSymbolEdit.setLayoutParams(lp);
        row.addView(mSymbolEdit);

        TextView add = new TextView(this);
        add.setText("添加");
        add.setGravity(Gravity.CENTER);
        add.setTextColor(C_ON_PRIMARY);
        add.setTextSize(14);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setIncludeFontPadding(false);
        add.setBackground(buttonBg(C_PRIMARY, 0, C_PRIMARY_PRESSED, 0));
        add.setOnClickListener(v -> addSymbol());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(84), dp(40));
        addLp.setMargins(dp(8), dp(10), 0, 0);
        row.addView(add, addLp);
        return row;
    }

    private View buildListSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        // 高度与上边距由 buildUi() 给（listHeight() + 44dp），这里只管排内容
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 整块做成描边卡片：和上面的输入区有明确分界，行也只在卡片里滚动，不会越界
        section.setBackground(rounded(C_BG, C_FIELD_STROKE));
        section.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView label = new TextView(this);
        label.setTextColor(C_DIM);
        label.setTextSize(12);
        section.addView(label);
        mListLabel = label;

        mList = new RecyclerView(this);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 外面是 ScrollView：按在列表上先把竖向手势锁给列表，别让页面跟着滚
        mList.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                disallowScrollFrom(v);
            }
            return false;      // 事件照常交给 RecyclerView 自己处理
        });
        // 占满 section 剩下的高度并自己滚：LinearLayout 里 weight 才撑得起，
        // 用 LinearLayout.LayoutParams（RecyclerView.LayoutParams 的 margin 会在转换时丢掉）
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.setMargins(0, dp(8), 0, dp(8));
        mList.setLayoutParams(listLp);
        mAdapter = new ItemAdapter();
        mList.setAdapter(mAdapter);
        mTouchHelper = new ItemTouchHelper(new DragCallback());
        mTouchHelper.attachToRecyclerView(mList);
        section.addView(mList);

        mEmpty = new TextView(this);
        mEmpty.setText("还没有标的，先在上面添加一个（默认跟随行情页当前标的）");
        mEmpty.setTextColor(C_DIM);
        mEmpty.setTextSize(12);
        mEmpty.setPadding(0, dp(10), 0, dp(10));
        section.addView(mEmpty);

        refreshList();
        return section;
    }

    /** 列表有增删或换了顺序时刷新：标题数量、空态提示与行号都跟着变 */
    private void refreshList() {
        boolean empty = mItems.isEmpty();
        if (mList != null) mList.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (mEmpty != null) mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        if (mListLabel != null) {
            mListLabel.setText("已选标的（" + mItems.size() + "）"
                    + (mItems.size() > 1 ? " · 长按或拖 ☰ 可调整顺序" : ""));
        }
    }

    /** 已选标的一行：☰ 手柄（拖动排序）+ 名称 + 删除 */
    private final class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(C_FIELD, C_FIELD_STROKE));
            row.setPadding(dp(10), dp(6), dp(6), dp(6));
            RecyclerView.LayoutParams rowLp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(6));
            row.setLayoutParams(rowLp);

            // 拖动手柄：40dp 高，和输入框同一档，按下就开始拖
            TextView handle = new TextView(parent.getContext());
            handle.setText("☰");
            handle.setTextColor(C_DIM);
            handle.setTextSize(15);
            handle.setGravity(Gravity.CENTER);
            handle.setMinWidth(dp(36));
            handle.setMinHeight(dp(40));
            row.addView(handle);

            TextView name = new TextView(parent.getContext());
            name.setTextColor(C_TEXT);
            name.setTextSize(14);
            name.setSingleLine();
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView remove = new TextView(parent.getContext());
            remove.setText("删除");
            remove.setGravity(Gravity.CENTER);
            remove.setTextColor(C_DIM);
            remove.setTextSize(13);
            remove.setMinWidth(dp(60));
            remove.setMinHeight(dp(40));
            remove.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.addView(remove);
            return new Holder(row, handle, name, remove);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final View handle;
            final TextView name;
            final TextView remove;

            Holder(View itemView, View handle, TextView name, TextView remove) {
                super(itemView);
                this.handle = handle;
                this.name = name;
                this.remove = remove;
            }

            void bind(int position) {
                Item item = mItems.get(position);
                name.setText((position + 1) + ". " + item.title() + "  " + item.symbol
                        + " · " + QuoteSource.label(item.source));
                remove.setOnClickListener(v -> {
                    int p = getAdapterPosition();
                    if (p < 0 || p >= mItems.size()) return;
                    Item gone = mItems.remove(p);
                    notifyItemRemoved(p);
                    refreshList();           // 后面的行号要重排
                    setStatus("已移除：" + gone.title());
                });
                handle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        disallowScrollFrom(v);
                        mTouchHelper.startDrag(this);
                    }
                    return true;
                });
                itemView.setOnLongClickListener(v -> {
                    disallowScrollFrom(v);
                    mTouchHelper.startDrag(this);
                    return true;
                });
            }
        }
    }

    /** 只准上下拖：拖出来的顺序就是小部件里行的顺序，松手才落定 */
    private final class DragCallback extends ItemTouchHelper.Callback {

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder) {
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (from < 0 || to < 0 || from >= mItems.size() || to >= mItems.size()) return false;
            // 逐个交换而不是直接 swap(from,to)：行数多的时候跟手，不会一下跳过去
            if (from < to) {
                for (int i = from; i < to; i++) Collections.swap(mItems, i, i + 1);
            } else {
                for (int i = from; i > to; i--) Collections.swap(mItems, i, i - 1);
            }
            mAdapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            refreshList();       // 行号按新顺序重排
            // 顺序和添加 / 删除一样，统一在「添加到桌面」时落盘，这里只给个回执
            if (mItems.size() > 1) {
                setStatus("顺序已调整，点「添加到桌面」后生效");
            }
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;        // 长按由行自己处理（见 Holder），这里只认手柄按下的拖拽
        }
    }

    /** 开始拖拽时把外层 ScrollView 的滑动拦截掉，免得拖着手柄页面跟着滚 */
    private static void disallowScrollFrom(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
            parent = parent.getParent();
        }
    }

    private View buildStatus() {
        mStatus = new TextView(this);
        mStatus.setTextColor(C_DIM);
        mStatus.setTextSize(11);
        // 独立一行夹在输入区和卡片之间：上下各留一点，不贴着两边的控件
        mStatus.setPadding(0, dp(4), 0, dp(4));
        // 没回执时整行收掉：不然卡片顶部永远空出一行
        mStatus.setVisibility(View.GONE);
        return mStatus;
    }

    /** 所有回执都走这里：空内容就不占位，卡片顶部不会白留一行 */
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

    private void addSymbol() {
        if (mSearching) return;
        final String input = mSymbolEdit.getText().toString().trim();
        if (input.isEmpty()) {
            setStatus("请输入标的代码或名称");
            return;
        }
        if (mItems.size() >= MarketWidgetProvider.MAX_ITEMS) {
            setStatus("一个部件最多 " + MarketWidgetProvider.MAX_ITEMS + " 个标的");
            return;
        }
        for (Item item : mItems) {
            if (item.symbol.equals(input) && item.source.equals(mSource)) {
                setStatus("已经在列表里了：" + input);
                return;
            }
        }
        if (QuoteSource.needsSearch(input)) {
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
                        add(items.get(0).code, items.get(0).name);
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
            return;
        }
        add(input, QuoteSource.aliasName(input));
    }

    private void add(String code, String name) {
        mItems.add(new Item(mSource, code, name));
        mSymbolEdit.setText("");
        refreshList();
        setStatus("已添加：" + (TextUtils.isEmpty(name) ? code : name));
    }

    /** 多个同名/近似的标的时让用户选一个 */
    private void pickSymbol(List<QuoteSource.Symbol> items) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).name + "  " + items.get(i).code;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择标的")
                .setItems(labels, (dialog, which) -> add(items.get(which).code, items.get(which).name))
                .setNegativeButton("取消", null)
                .show();
    }

    private void save() {
        if (mItems.isEmpty()) {
            setStatus("至少添加一个标的");
            return;
        }
        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setStatus("没拿到小部件 ID：请从桌面长按 → 小部件里添加");
            return;
        }
        try {
            MarketWidgetProvider.saveItems(this, mAppWidgetId, mItems);
            setResult(RESULT_OK, resultIntent());
            // 直接渲染一次：广播在部分 ROM 上会被拦，配置完部件会一直空着
            MarketWidgetProvider.refreshNow(this, mAppWidgetId);
            finish();
        } catch (Throwable t) {
            setStatus("保存失败：" + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 小工具（背景一律代码生成，不新增 drawable XML）
    // ------------------------------------------------------------------

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
