package com.tsymiar.device2device.market;

import android.content.Context;
import android.content.res.Configuration;

import androidx.core.content.ContextCompat;

import com.tsymiar.device2device.R;

/**
 * 行情配色表：日间 / 夜间两套，全部取自 color 资源（values / values-night），
 * 于是行情页、K线视图、桌面小部件都能跟随系统深浅模式。
 *
 * 涨/跌沿用脚本 matkline.py 的「红涨绿跌」，只在浅色底上把两种颜色调深一档保证对比度。
 */
public final class MarketPalette {

    /** 是否处于夜间模式（由系统 uiMode 决定） */
    public final boolean night;

    public final int bg;              // 页面 / 图表底色
    public final int panel;           // 输入框 / 下拉底色
    public final int panelPressed;    // 按下态底色
    public final int stroke;          // 描边
    public final int strokePressed;   // 按下态描边
    public final int text;            // 主文本
    public final int textDim;         // 次级文本（状态栏）
    public final int textHint;        // 提示文本 / hint
    public final int accent;          // 主按钮（青绿）
    public final int accentPressed;
    public final int onAccent;        // 主按钮上的文字
    public final int on;              // 自动刷新「开」
    public final int onPressed;
    public final int onText;          // 橙底上的文字

    public final int up;              // 涨（红）
    public final int down;            // 跌（绿）
    public final int flat;            // 平
    public final int grid;            // 网格 / 坐标线
    public final int axis;            // 坐标文字
    public final int ma5;
    public final int ma10;
    public final int ma20;
    public final int dif;
    public final int dea;
    public final int rsi;
    public final int cursor;          // 十字光标 / 最新价虚线
    public final int trend;           // 分时折线（1分钟等超短周期）
    public final int tooltipBg;       // 详情浮窗底（带 alpha）
    public final int tooltipStroke;
    public final int tooltipText;     // 浮窗里的正文（浮窗底始终是深色，跟页面正文色分开取）
    public final int tooltipTextDim;  // 浮窗里的次级文字（时间那行）

    private MarketPalette(Context c, boolean night) {
        this.night = night;
        bg = color(c, R.color.market_bg);
        panel = color(c, R.color.market_panel);
        panelPressed = color(c, R.color.market_panel_pressed);
        stroke = color(c, R.color.market_stroke);
        strokePressed = color(c, R.color.market_stroke_pressed);
        text = color(c, R.color.market_text);
        textDim = color(c, R.color.market_text_dim);
        textHint = color(c, R.color.market_text_hint);
        accent = color(c, R.color.market_accent);
        accentPressed = color(c, R.color.market_accent_pressed);
        onAccent = color(c, R.color.market_on_accent);
        on = color(c, R.color.market_on);
        onPressed = color(c, R.color.market_on_pressed);
        onText = color(c, R.color.market_on_text);
        up = color(c, R.color.market_up);
        down = color(c, R.color.market_down);
        flat = color(c, R.color.market_flat);
        grid = color(c, R.color.market_grid);
        axis = color(c, R.color.market_axis);
        ma5 = color(c, R.color.market_ma5);
        ma10 = color(c, R.color.market_ma10);
        ma20 = color(c, R.color.market_ma20);
        dif = color(c, R.color.market_dif);
        dea = color(c, R.color.market_dea);
        rsi = color(c, R.color.market_rsi);
        cursor = color(c, R.color.market_cursor);
        trend = color(c, R.color.market_trend);
        tooltipBg = color(c, R.color.market_tooltip_bg);
        tooltipStroke = color(c, R.color.market_tooltip_stroke);
        tooltipText = color(c, R.color.market_tooltip_text);
        tooltipTextDim = color(c, R.color.market_tooltip_text_dim);
    }

    /** 按当前系统深浅模式取配色 */
    public static MarketPalette of(Context c) {
        return new MarketPalette(c.getApplicationContext(), isNight(c));
    }

    public static boolean isNight(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int color(Context c, int resId) {
        return ContextCompat.getColor(c, resId);
    }
}
