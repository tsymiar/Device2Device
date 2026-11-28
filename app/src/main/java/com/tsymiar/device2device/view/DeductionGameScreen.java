package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tsymiar.device2device.R;
import com.tsymiar.device2device.game.DeductionAudio;
import com.tsymiar.device2device.game.DeductionEngine;

/**
 * 《声纹推演盘》完整游戏界面（可复用 View）
 *
 * 由 Activity 全屏壳或全屏 Dialog（{@code GameDialog}）承载：
 *  - 主推演盘（三槽拖拽 + 心理滑块 + 命题展示）
 *  - 底部控制条：章节切换 chips + 推演按钮
 */
public class DeductionGameScreen extends FrameLayout {

    private final DeductionBoardView mBoardView;

    public DeductionGameScreen(Context context) {
        super(context);
        setBackgroundColor(GameUi.BG);

        // ── 主推演盘（依赖注入：引擎与音频由本类创建） ──
        mBoardView = new DeductionBoardView(context, new DeductionEngine(), new DeductionAudio());
        addView(mBoardView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // ── 底部控制条：chips 可横向滑动占剩余宽度，推演按钮固定在右（窄屏不溢出） ──
        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bottomBar.setBackgroundColor(GameUi.withAlpha(GameUi.PANEL, 0xCC));

        LinearLayout chipRow = new LinearLayout(context);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] chapters = { "序章", "一章", "二章", "三章", "终章" };
        for (int i = 0; i < chapters.length; ++i) {
            final int idx = i;
            TextView btn = makeChip(chapters[i]);
            btn.setOnClickListener(v -> mBoardView.loadChapter(idx));
            chipRow.addView(btn);
        }
        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chipRow);

        TextView deduceBtn = new TextView(context);
        deduceBtn.setText(R.string.game_deduce);
        deduceBtn.setTextColor(GameUi.BG);
        deduceBtn.setTextSize(16);
        deduceBtn.setTypeface(Typeface.DEFAULT_BOLD);
        deduceBtn.setGravity(Gravity.CENTER);
        deduceBtn.setPadding(dp(24), dp(12), dp(24), dp(12));
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(GameUi.PRIMARY);
        dg.setCornerRadius(dp(28));
        deduceBtn.setBackground(dg);
        deduceBtn.setOnClickListener(v -> mBoardView.performDeduction());

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bottomBar.addView(chipScroll, scrollLp);
        bottomBar.addView(deduceBtn);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        addView(bottomBar, barLp);
        // 布局完成后把底部控制条高度注入推演盘，滚动/绘制自动避开遮挡
        bottomBar.post(() -> mBoardView.setBottomBarHeight(bottomBar.getHeight()));
    }

    private TextView makeChip(String label) {
        TextView btn = new TextView(getContext());
        btn.setText(label);
        btn.setTextColor(Color.rgb(200, 225, 235));
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x44224466);
        d.setCornerRadius(dp(20));
        d.setStroke(1, GameUi.withAlpha(GameUi.PRIMARY, 0x66));
        btn.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(float v) {
        return Math.round(GameUi.dp(getContext(), v));
    }
}
