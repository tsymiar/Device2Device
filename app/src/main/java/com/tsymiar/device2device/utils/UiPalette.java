package com.tsymiar.device2device.utils;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.tsymiar.device2device.R;

/**
 * Canvas 手绘 View 取 ui_* 语义色用：深浅两套值由资源限定符自动切换，View 不用判断当前模式。
 */
public final class UiPalette {

    public final int card;
    public final int cardPressed;
    public final int stroke;
    public final int divider;

    public final int text;
    public final int textDim;
    public final int textHint;

    public final int accent;
    /** 实心底之上的文字，配 accent 用 */
    public final int onFill;

    public final int ok;
    public final int warn;
    public final int danger;
    public final int info;
    public final int purple;

    private UiPalette(Context c) {
        card = get(c, R.color.ui_card);
        cardPressed = get(c, R.color.ui_card_pressed);
        stroke = get(c, R.color.ui_stroke);
        divider = get(c, R.color.ui_divider);

        text = get(c, R.color.ui_text);
        textDim = get(c, R.color.ui_text_dim);
        textHint = get(c, R.color.ui_text_hint);

        accent = get(c, R.color.ui_accent);
        onFill = get(c, R.color.ui_on_fill);

        ok = get(c, R.color.ui_ok);
        warn = get(c, R.color.ui_warn);
        danger = get(c, R.color.ui_danger);
        info = get(c, R.color.ui_info);
        purple = get(c, R.color.ui_purple);
    }

    public static UiPalette of(Context c) {
        return new UiPalette(c.getApplicationContext());
    }

    private static int get(Context c, int resId) {
        return ContextCompat.getColor(c, resId);
    }
}
