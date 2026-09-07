package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Color;

/**
 * 游戏统一 UI 主题与绘制工具
 *
 * 《余音回响》《声纹推演盘》共用同一套深色声纹实验室视觉体系：
 * 背景 / 面板 / 主强调（青绿）/ 次强调（淡蓝）/ 警示 / 恐惧 / 文本。
 * 颜色、sp 转换、颜色运算集中在单处，供两个 View 与两个 Activity 引用。
 */
public final class GameUi {

    private GameUi() { }

    // ─────────── 统一主题色 ───────────
    public static final int BG           = 0xFF0A0A12; // 全局背景（窗口/画布）
    public static final int PANEL        = 0xFF0F1420; // 面板底
    public static final int BORDER       = 0xFF3C4A66; // 面板描边
    public static final int PRIMARY      = 0xFF00DCA0; // 主强调（青绿）：推演按钮 / 关系高亮
    public static final int ACCENT       = 0xFF4FC3F7; // 次强调（淡蓝）：标题 / 粒子 / 波形
    public static final int WARN         = 0xFFFF964F; // 警示（橙）：贪婪 / 警告
    public static final int FEAR         = 0xFF969CFF; // 恐惧（蓝紫）
    public static final int DANGER       = 0xFFFF5C5C; // 危险（红）
    public static final int GOLD         = 0xFFFFC864; // 金色：锁存 / 奖励
    public static final int TEXT         = 0xFFE8E8F0; // 主文本
    public static final int TEXT_DIM     = 0xFF9AA0B0; // 次级文本
    public static final int TEXT_FAINT   = 0xFF7A7A8A; // 弱提示文本

    // ─────────── 尺寸换算 ───────────
    public static float sp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().scaledDensity;
    }

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    // ─────────── 颜色运算 ───────────
    /** 替换 alpha 通道（0-255） */
    public static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    /** RGB 通道整体放大 factor 倍（保留 alpha） */
    public static int lighten(int argb, float factor) {
        int r = (int) Math.min(255, Color.red(argb) * factor);
        int g = (int) Math.min(255, Color.green(argb) * factor);
        int b = (int) Math.min(255, Color.blue(argb) * factor);
        return Color.argb(Color.alpha(argb), r, g, b);
    }

    /** RGB 通道整体缩小 factor 倍（保留 alpha） */
    public static int darken(int argb, float factor) {
        return lighten(argb, 1f / Math.max(0.01f, factor));
    }

    /** 两色按 t(0-1) 线性混合 */
    public static int mix(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }
}
