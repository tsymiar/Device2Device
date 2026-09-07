package com.tsymiar.device2device.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.tsymiar.device2device.game.EchoEngine;
import com.tsymiar.device2device.view.DeductionGameScreen;
import com.tsymiar.device2device.view.EchoGameView;
import com.tsymiar.device2device.view.GameUi;

/**
 * 全屏游戏弹窗：把游戏 View 以弹窗（而非跳转新页面）展示在主页之上。
 *
 * 《余音回响》《声纹推演盘》通用：
 *  - 保持屏幕常亮 + 沉浸式全屏 + 隐藏系统导航
 *  - 窗口与内容背景统一为 GameUi.BG，避免弹起瞬间白底闪烁
 *  - 右上角半透明 ✕ 与系统返回键均可随时退出（游戏结束回调也会自动关闭）
 */
public class GameDialog extends Dialog {

    private GameDialog(Context context, View gameContent) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(GameUi.BG);
        root.addView(gameContent, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // 右上角退出按钮：贴边小尺寸，避开游戏顶部标题/仪表区
        TextView close = new TextView(context);
        close.setText("✕");
        close.setTextColor(GameUi.TEXT_DIM);
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(GameUi.withAlpha(GameUi.BG, 0xCC));
        bg.setStroke(1, GameUi.withAlpha(GameUi.TEXT_FAINT, 0x88));
        bg.setCornerRadius(GameUi.dp(context, 18));
        close.setBackground(bg);
        close.setOnClickListener(v -> dismiss());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                (int) GameUi.dp(context, 36), (int) GameUi.dp(context, 36),
                Gravity.TOP | Gravity.END);
        closeLp.setMargins(0, (int) GameUi.dp(context, 8), (int) GameUi.dp(context, 8), 0);
        root.addView(close, closeLp);

        setContentView(root);
        setCanceledOnTouchOutside(false);
        configureWindow(context);
    }

    /** 展示《余音回响 Echo Resonance》，结局播完自动关闭弹窗 */
    public static void showEcho(Activity activity) {
        EchoEngine engine = new EchoEngine();
        EchoGameView view = new EchoGameView(activity, engine);
        GameDialog dialog = new GameDialog(activity, view);
        view.setGameListener(dialog::dismiss);
        dialog.show();
    }

    /** 展示《声纹推演盘 Deduction Board》 */
    public static void showDeduction(Activity activity) {
        new GameDialog(activity, new DeductionGameScreen(activity)).show();
    }

    private void configureWindow(Context context) {
        Window w = getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(GameUi.BG));
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(GameUi.BG);
        w.setNavigationBarColor(GameUi.BG);
        setOnShowListener(d -> {
            View decor = w.getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        });
    }
}
