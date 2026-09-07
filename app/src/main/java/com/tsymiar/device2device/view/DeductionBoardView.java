package com.tsymiar.device2device.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.tsymiar.device2device.game.DeductionAudio;
import com.tsymiar.device2device.game.DeductionData;
import com.tsymiar.device2device.game.DeductionEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * 声纹推演盘 —— 主界面
 * 三槽拖拽（样本+动作+锚点）+ 心理变量滑块 + 命题展示
 * （对应 Qt 端 DeductionBoardWidget.h/cpp）
 */
public class DeductionBoardView extends View {

    private final DeductionEngine mEngine;
    private final DeductionAudio mAudio;

    private final DeductionData.DeductionSlot[] mSlots = new DeductionData.DeductionSlot[3];
    private final List<DeductionData.Relation> mRelations = new ArrayList<>();
    private int mSelectedSample = -1;
    private boolean mDragging = false;
    private float mDragX = 0, mDragY = 0;
    private DeductionData.DeductionResult mLastResult;
    private boolean mHasResult = false;

    private float mGreed = 0.5f;
    private float mFear = 0.5f;
    private boolean mDraggingGreed = false;
    private boolean mDraggingFear = false;

    private int mSelectedRelation = 0;
    private int mChapter = 0;

    private long mDownTime = 0;
    private float mDownX = 0, mDownY = 0;
    private boolean mMoved = false;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mLogW = 1000f, mLogH = 680f;

    // ── 字体自适应 ──
    /** 当前帧画布（Canvas）整体缩放倍数，用于把“目标 sp 字号”换算成画布内逻辑字号 */
    private float mCanvasScale = 1f;

    // ── 移动端竖屏适配 ──
    private boolean mPortrait = false;
    // 底部控制条高度（物理像素，由 Activity 注入），内容区自动避开遮挡
    private float mBottomBarH = 0f;
    private float mScrollY = 0f, mScrollMax = 0f;
    private float mContentEnd = 0f;
    private float mLastTouchY = 0f;
    // 内容坐标（未滚动）中可交互区域的位置，供触摸判定
    private float mGreedY = 0f, mFearY = 0f, mSlotsY0 = 0f;

    // 竖屏布局常量（逻辑画布 640 宽；卡片全宽，最大化移动端可读性与触摸区）
    private static final float P_L = 24f, P_R = 616f;
    private static final float P_REL_Y = 68f, P_REL_X0 = 336f;
    private static final float P_CHIP_W = 64f, P_CHIP_H = 30f, P_CHIP_GAP = 6f;
    private static final float P_LIB_Y = 140f; // 样本库标题基线（在滚动区裁剪起点之下，完整可见）
    // 样本库：单行横向卡片条（样本左右排列成一行，不再纵向整屏堆叠）
    private static final float P_CARD_W = P_R - P_L;
    private static final float P_CARD_Y0 = P_LIB_Y + 28f; // 卡片条顶 y
    private static final float P_CARD_H = 134f;           // 卡片条高
    private static final float P_CARD_GAP = 10f;          // 相邻卡片水平间距

    // 横屏样本库：画布底部的一行横向卡片条（结果区铺底时临时隐藏）
    private static final float L_SHELF_X0 = -0.95f, L_SHELF_X1 = 0.45f;
    private static final float L_SHELF_Y = -0.38f; // 条顶 gy
    private static final float L_SHELF_H = 118f;   // 条高（逻辑像素）
    private static final float L_SHELF_GAP = 8f;   // 相邻卡片间距（逻辑像素）

    private static final float P_BAR_L = 130f, P_BAR_W = 300f;
    private static final float P_SLOT_H = 112f, P_SLOT_GAP = 16f;
    private static final float P_SCROLL_TOP = 94f; // 滚动区裁剪起点（固定区底部：关系行 chips 下沿）

    public DeductionBoardView(Context context) {
        this(context, new DeductionEngine(), new DeductionAudio());
    }

    public DeductionBoardView(Context context, DeductionEngine engine, DeductionAudio audio) {
        super(context);
        mEngine = engine;
        mAudio = audio;
        setFocusable(true);
        for (int i = 0; i < 3; ++i) mSlots[i] = new DeductionData.DeductionSlot();
        mRelations.add(DeductionData.Relation.Mask);
        mRelations.add(DeductionData.Relation.Excite);
        loadChapter(0);
    }

    public void loadChapter(int chapter) {
        mChapter = chapter;
        for (DeductionData.DeductionSlot s : mSlots) s.clear();
        mHasResult = false;
        switch (chapter) {
            case 0: mEngine.setupPrologue(); break;
            case 1: mEngine.setupChapter1(); break;
            case 2: mEngine.setupChapter2(); break;
            case 3: mEngine.setupChapter3(); break;
            case 4: mEngine.setupFinale(); break;
            default: mEngine.setupPrologue(); break;
        }
        invalidate();
    }

    /** 设置底部控制条高度（物理像素），竖屏内容区与横屏画布自动避开 */
    public void setBottomBarHeight(float px) {
        mBottomBarH = px;
        invalidate();
    }

    // ═══════════════════════════════════════════════════════════════
    //  坐标换算
    // ═══════════════════════════════════════════════════════════════

    private float gx2x(float gx) { return (gx + 1.0f) / 2.0f * mLogW; }
    private float gy2y(float gy) { return (1.0f - gy) / 2.0f * mLogH; }
    private float w2x(float gw) { return gw / 2.0f * mLogW; }
    private float h2y(float gh) { return gh / 2.0f * mLogH; }

    // ═══════════════════════════════════════════════════════════════
    //  绘制
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(GameUi.BG);
        mPortrait = isPortrait();
        if (mPortrait) {
            float s = getWidth() / 640f;
            mCanvasScale = s;
            canvas.save();
            canvas.scale(s, s);
            renderPortrait(canvas);
            canvas.restore();
            return;
        }

        float availH = getHeight() - mBottomBarH;
        float scale = Math.min(getWidth() / mLogW, availH / mLogH);
        mCanvasScale = scale;
        float offX = (getWidth() - mLogW * scale) / 2f;
        float offY = (availH - mLogH * scale) / 2f;
        canvas.save();
        canvas.translate(offX, offY);
        canvas.scale(scale, scale);

        renderSamples(canvas);
        renderBoard(canvas);
        renderProposition(canvas);
        if (mHasResult) renderResult(canvas);
        if (mChapter == 4 && mHasResult) renderPersonality(canvas);

        canvas.restore();
    }

    private void renderSamples(Canvas c) {
        // 横屏：推演结果区铺满画布底部，与样本条重叠时结果优先、样本条隐藏
        if (mHasResult) return;
        List<DeductionData.DeductionSample> samples = mEngine.samples();
        int n = samples.size();
        if (n <= 0) return;

        float x0 = gx2x(L_SHELF_X0);
        float wTotal = gx2x(L_SHELF_X1) - x0;
        float yTop = gy2y(L_SHELF_Y);
        float cw = (wTotal - L_SHELF_GAP * (n - 1)) / n;
        drawText(c, x0, yTop - 16, "声纹样本库", Color.rgb(0, 200, 255), 0.6f);
        for (int i = 0; i < n; ++i) {
            float x = x0 + i * (cw + L_SHELF_GAP);
            drawSampleTile(c, x, yTop, cw, L_SHELF_H, samples.get(i), i);
        }
        drawText(c, gx2x(-0.95f), gy2y(-0.9f), "长按样本: 锁存（防负片覆盖）", Color.argb(140, 100, 130, 160), 0.3f);
    }

    private void renderBoard(Canvas c) {
        drawText(c, gx2x(-0.45f), gy2y(0.9f), "声纹推演盘", Color.rgb(0, 220, 200), 0.8f);
        String[] slotNames = { "样本", "干预动作", "时间锚点" };

        float bx = -0.45f, by = 0.7f, bw = 0.9f, bh = 0.18f, gap = 0.06f;
        for (int i = 0; i < 3; ++i) {
            float y = by - (bh + gap) * i;
            drawRect(c, bx, y - bh, bw, bh, Color.argb(160, 15, 20, 35), true);
            int bc = (mDragging && mSelectedSample >= 0) ? Color.rgb(0, 220, 200) : Color.argb(80, 60, 90, 130);
            drawRect(c, bx, y - bh, bw, bh, bc, false);
            drawText(c, gx2x(bx + 0.02f), gy2y(y - 0.03f), String.format("槽%d：%s", i + 1, slotNames[i]),
                    Color.argb(170, 150, 180, 210), 0.35f);

            DeductionData.DeductionSlot slot = mSlots[i];
            if (slot.sampleId >= 0) {
                DeductionData.DeductionSample s = mEngine.findSample(slot.sampleId);
                if (s != null) {
                    drawText(c, gx2x(bx + 0.02f), gy2y(y - 0.08f), "-> " + s.name, s.color(), 0.45f);
                    String act = String.format("动作：%s   锚点：%s", slot.action.label(), slot.anchor.label());
                    drawText(c, gx2x(bx + 0.02f), gy2y(y - 0.12f), act, Color.argb(160, 180, 200, 220), 0.32f);
                }
            } else {
                drawText(c, gx2x(bx + 0.02f), gy2y(y - 0.08f), "（拖拽样本到此处）", Color.argb(120, 80, 100, 130), 0.32f);
            }
        }

        String[] rels = { "掩盖", "激发", "反转", "同步" };
        drawText(c, gx2x(bx), gy2y(by - 3 * (bh + gap) - 0.02f), "相对关系：", Color.argb(170, 150, 180, 210), 0.35f);
        for (int i = 0; i < 4; ++i) {
            float rx = bx + 0.35f + i * 0.15f;
            int rc = (mSelectedRelation == i) ? Color.rgb(0, 220, 200) : Color.argb(140, 100, 140, 180);
            drawText(c, gx2x(rx), gy2y(by - 3 * (bh + gap) - 0.02f), "[" + rels[i] + "]", rc, 0.35f);
        }

        drawText(c, gx2x(bx), gy2y(by - 3 * (bh + gap) - 0.2f),
                "点击槽: 切换动作 | 长按槽: 清空 | 点击关系切换", Color.argb(200, 0, 220, 200), 0.35f);
    }

    private void renderProposition(Canvas c) {
        DeductionData.Proposition p = mEngine.currentProposition();
        if (p != null) {
            drawText(c, gx2x(0.5f), gy2y(0.9f), "待验证命题", Color.rgb(255, 210, 100), 0.6f);
            drawTextWrapped(c, gx2x(0.5f), gy2y(0.8f), p.text, Color.rgb(230, 210, 170), 0.4f,
                    (int) w2x(0.48f));
        }

        if (mChapter >= 1) {
            drawText(c, gx2x(0.5f), gy2y(0.45f), "陈远山动机锚定偏差", Color.rgb(180, 160, 200), 0.45f);
            drawText(c, gx2x(0.5f), gy2y(0.38f), String.format("贪婪 %d%%", (int) (mGreed * 100)),
                    Color.rgb(255, 150, 80), 0.35f);
            drawRect(c, 0.5f, 0.32f, 0.5f, 0.03f, Color.argb(180, 40, 40, 60), true);
            drawRect(c, 0.5f, 0.32f, 0.5f * mGreed, 0.03f, Color.argb(220, 255, 150, 80), true);
            drawText(c, gx2x(0.5f), gy2y(0.24f), String.format("恐惧 %d%%", (int) (mFear * 100)),
                    Color.rgb(150, 160, 255), 0.35f);
            drawRect(c, 0.5f, 0.18f, 0.5f, 0.03f, Color.argb(180, 40, 40, 60), true);
            drawRect(c, 0.5f, 0.18f, 0.5f * mFear, 0.03f, Color.argb(220, 150, 160, 255), true);
        }
    }

    private void renderResult(Canvas c) {
        drawText(c, gx2x(-0.95f), gy2y(-0.35f), "推演结果", Color.rgb(0, 220, 160), 0.55f);
        drawTextWrapped(c, gx2x(-0.95f), gy2y(-0.45f), mLastResult.audioDescription,
                Color.rgb(160, 220, 200), 0.35f, (int) w2x(1.9f));
        drawTextWrapped(c, gx2x(-0.95f), gy2y(-0.58f), mLastResult.logicInference,
                Color.rgb(200, 200, 220), 0.35f, (int) w2x(1.9f));
        drawText(c, gx2x(-0.95f), gy2y(-0.68f),
                String.format("心理可信度：%d%%", (int) (mLastResult.credibility * 100)),
                mLastResult.credibility > 0.65f ? Color.rgb(255, 200, 80) : Color.rgb(150, 180, 200), 0.4f);
        if (mLastResult.rewritesMemory) {
            drawText(c, gx2x(-0.95f), gy2y(-0.78f), "! 此推演正在改写你的记忆库", Color.rgb(255, 100, 100), 0.4f);
        }
    }

    private void renderPersonality(Canvas c) {
        DeductionData.PersonalityProfile pp = mEngine.generatePersonalityProfile();
        drawText(c, gx2x(0.5f), gy2y(-0.5f), "推演板对你的侧写", Color.rgb(255, 210, 100), 0.5f);
        drawTextWrapped(c, gx2x(0.5f), gy2y(-0.6f), pp.summary, Color.rgb(200, 190, 170), 0.35f,
                (int) w2x(0.48f));
    }

    // ═══════════════════════════════════════════════════════════════
    //  竖屏渲染（逻辑画布 640 宽，从上到下动态布局，可滚动）
    // ═══════════════════════════════════════════════════════════════

    private boolean isPortrait() {
        return getWidth() < getHeight();
    }

    private void renderPortrait(Canvas c) {
        float s = getWidth() / 640f;
        float visH = (getHeight() - mBottomBarH) / s;

        // 固定顶部：标题 + 相对关系行（基线取 54，避免放大后大字号标题顶部被画布裁切）
        drawText(c, P_L, 54, "声纹推演盘", Color.rgb(0, 220, 200), 1.2f);
        drawText(c, 336, 54, "第 " + (mChapter + 1) + " 章", Color.argb(170, 150, 180, 210), 0.72f);
        String[] rels = { "掩盖", "激发", "反转", "同步" };
        for (int i = 0; i < 4; ++i) {
            float cx = P_REL_X0 + i * (P_CHIP_W + P_CHIP_GAP);
            int rc = (mSelectedRelation == i) ? Color.rgb(0, 220, 200) : Color.argb(140, 100, 140, 180);
            drawRectP(c, cx, P_REL_Y, P_CHIP_W, P_CHIP_H, GameUi.withAlpha(rc, mSelectedRelation == i ? 60 : 20), true);
            drawRectP(c, cx, P_REL_Y, P_CHIP_W, P_CHIP_H, rc, false);
            drawText(c, cx + 6, P_REL_Y + P_CHIP_H / 2, rels[i], rc, 0.46f);
        }

        // 滚动区
        mScrollMax = Math.max(0, mContentEnd - visH + 80);
        mScrollY = clampScroll(mScrollY);
        c.save();
        c.clipRect(0, P_SCROLL_TOP, 640, visH);
        c.translate(0, -mScrollY);

        float py = P_LIB_Y;
        py = renderPortraitSamples(c, py);
        py = renderPortraitProposition(c, py);
        py = renderPortraitSliders(c, py);
        py = renderPortraitSlots(c, py);
        py = renderPortraitTip(c, py);
        if (mHasResult) py = renderPortraitResult(c, py);
        mContentEnd = py;

        c.restore();
    }

    private float renderPortraitSamples(Canvas c, float py) {
        drawText(c, P_L, py, "声纹样本库", Color.rgb(0, 200, 255), 0.85f);
        List<DeductionData.DeductionSample> samples = mEngine.samples();
        int n = samples.size();
        if (n <= 0) return P_LIB_Y + 56;
        float yTop = P_CARD_Y0;
        float cw = (P_CARD_W - P_CARD_GAP * (n - 1)) / n;
        for (int i = 0; i < n; ++i) {
            float x = P_L + i * (cw + P_CARD_GAP);
            drawSampleTile(c, x, yTop, cw, P_CARD_H, samples.get(i), i);
        }
        return yTop + P_CARD_H + 24;
    }

    private float renderPortraitProposition(Canvas c, float py) {
        DeductionData.Proposition p = mEngine.currentProposition();
        if (p == null) return py;
        drawText(c, P_L, py, "待验证命题", Color.rgb(255, 210, 100), 0.72f);
        py += 40;
        int w = (int) (P_R - P_L - 20);
        int n = wrappedLineCount(p.text, 0.5f, w);
        drawTextWrapped(c, P_L, py, p.text, Color.rgb(230, 210, 170), 0.5f, w);
        py += n * lineHeight(0.5f) + 24;
        return py;
    }

    private float renderPortraitSliders(Canvas c, float py) {
        if (mChapter < 1) return py;
        drawText(c, P_L, py, "陈远山动机锚定偏差", Color.rgb(180, 160, 200), 0.6f);
        // 滑块行距：标题(24px)/标签(20px) 均有 descent，间距不足会文字重叠
        py += 50;
        mGreedY = py;
        drawText(c, P_L, py - 22, String.format("贪婪 %d%%", (int) (mGreed * 100)),
                Color.rgb(255, 150, 80), 0.5f);
        drawRectP(c, P_BAR_L, py - 6, P_BAR_W, 12, Color.argb(180, 40, 40, 60), true);
        drawRectP(c, P_BAR_L, py - 6, P_BAR_W * mGreed, 12, Color.argb(220, 255, 150, 80), true);
        py += 48;
        mFearY = py;
        drawText(c, P_L, py - 22, String.format("恐惧 %d%%", (int) (mFear * 100)),
                Color.rgb(150, 160, 255), 0.5f);
        drawRectP(c, P_BAR_L, py - 6, P_BAR_W, 12, Color.argb(180, 40, 40, 60), true);
        drawRectP(c, P_BAR_L, py - 6, P_BAR_W * mFear, 12, Color.argb(220, 150, 160, 255), true);
        py += 44;
        return py;
    }

    private float renderPortraitSlots(Canvas c, float py) {
        drawText(c, P_L, py, "三槽：拖拽样本至此", Color.rgb(0, 220, 200), 0.72f);
        py += 40;
        mSlotsY0 = py;
        String[] names = { "样本", "干预动作", "时间锚点" };
        for (int i = 0; i < 3; ++i) {
            float sy = py + i * (P_SLOT_H + P_SLOT_GAP);
            drawRectP(c, P_L, sy, P_R - P_L, P_SLOT_H, Color.argb(160, 15, 20, 35), true);
            int bc = (mDragging && mSelectedSample >= 0) ? Color.rgb(0, 220, 200) : Color.argb(80, 60, 90, 130);
            drawRectP(c, P_L, sy, P_R - P_L, P_SLOT_H, bc, false);
            drawText(c, P_L + 16, sy + 30, String.format("槽%d：%s", i + 1, names[i]),
                    Color.argb(170, 150, 180, 210), 0.58f);
            DeductionData.DeductionSlot slot = mSlots[i];
            if (slot.sampleId >= 0) {
                DeductionData.DeductionSample s = mEngine.findSample(slot.sampleId);
                if (s != null) {
                    drawText(c, P_L + 16, sy + 66, "-> " + s.name, s.color(), 0.7f);
                    String act = String.format("动作：%s   锚点：%s", slot.action.label(), slot.anchor.label());
                    drawText(c, P_L + 16, sy + 98, act, Color.argb(160, 180, 200, 220), 0.52f);
                }
            } else {
                drawText(c, P_L + 16, sy + 66, "（拖拽样本到此处）", Color.argb(120, 80, 100, 130), 0.5f);
            }
        }
        return py + 3 * (P_SLOT_H + P_SLOT_GAP) - P_SLOT_GAP + 26;
    }

    private float renderPortraitTip(Canvas c, float py) {
        drawText(c, P_L, py, "长按样本锁存 | 点击槽切动作 | 长按槽清空", Color.argb(140, 100, 130, 160), 0.5f);
        return py + 44;
    }

    private float renderPortraitResult(Canvas c, float py) {
        int w = (int) (P_R - P_L - 20);
        drawText(c, P_L, py, "推演结果", Color.rgb(0, 220, 160), 0.72f);
        py += 40;
        int n1 = wrappedLineCount(mLastResult.audioDescription, 0.5f, w);
        drawTextWrapped(c, P_L, py, mLastResult.audioDescription, Color.rgb(160, 220, 200), 0.5f, w);
        py += n1 * lineHeight(0.5f) + 16;
        int n2 = wrappedLineCount(mLastResult.logicInference, 0.5f, w);
        drawTextWrapped(c, P_L, py, mLastResult.logicInference, Color.rgb(200, 200, 220), 0.5f, w);
        py += n2 * lineHeight(0.5f) + 16;
        drawText(c, P_L, py,
                String.format("心理可信度：%d%%", (int) (mLastResult.credibility * 100)),
                mLastResult.credibility > 0.65f ? Color.rgb(255, 200, 80) : Color.rgb(150, 180, 200), 0.55f);
        py += 40;
        if (mLastResult.rewritesMemory) {
            drawText(c, P_L, py, "! 此推演正在改写你的记忆库", Color.rgb(255, 100, 100), 0.55f);
            py += 38;
        }
        if (mChapter == 4) {
            DeductionData.PersonalityProfile pp = mEngine.generatePersonalityProfile();
            drawText(c, P_L, py, "推演板对你的侧写", Color.rgb(255, 210, 100), 0.6f);
            py += 40;
            int n3 = wrappedLineCount(pp.summary, 0.5f, w);
            drawTextWrapped(c, P_L, py, pp.summary, Color.rgb(200, 190, 170), 0.5f, w);
            py += n3 * lineHeight(0.5f) + 20;
        }
        return py;
    }

    // ═══════════════════════════════════════════════════════════════
    //  执行推演
    // ═══════════════════════════════════════════════════════════════

    public void performDeduction() {
        List<DeductionData.DeductionSlot> filled = new ArrayList<>();
        for (DeductionData.DeductionSlot s : mSlots) {
            if (s.sampleId >= 0) filled.add(s);
        }
        if (filled.size() < 2) {
            mHasResult = true;
            mLastResult = new DeductionData.DeductionResult();
            mLastResult.audioDescription = "需要至少两个声纹样本才能推演。";
            mLastResult.logicInference = "推演板拒绝空转。";
            mLastResult.credibility = 0.0f;
            mLastResult.rewritesMemory = false;
            invalidate();
            return;
        }

        for (DeductionData.DeductionSlot s : filled) mEngine.recordAnchor(s.anchor);
        mEngine.setGreedFear(mGreed, mFear);

        mLastResult = mEngine.deduce(filled, mRelations);
        mHasResult = true;

        List<DeductionAudio.AudioLayer> layers = new ArrayList<>();
        for (DeductionData.DeductionSlot s : filled) {
            DeductionData.DeductionSample smp = mEngine.findSample(s.sampleId);
            if (smp == null) continue;
            DeductionAudio.AudioLayer l = new DeductionAudio.AudioLayer();
            l.frequency = 200.0f + (smp.id * 137) % 800;
            l.amplitude = 0.5f;
            l.durationSec = 0.8f;
            l.pitchScale = smp.basePitch;
            layers.add(l);
        }
        String audioDesc = mAudio.synthesize(layers, true);
        mLastResult.audioDescription += " | " + audioDesc;

        DeductionData.Proposition p = mEngine.currentProposition();
        if (p != null) mEngine.resolveProposition(p.id);
        invalidate();
    }

    // ═══════════════════════════════════════════════════════════════
    //  触摸处理
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isPortrait()) return onTouchPortrait(event);

        float px = event.getX(), py = event.getY();
        float availH = getHeight() - mBottomBarH;
        float scale = Math.min(getWidth() / mLogW, availH / mLogH);
        float offX = (getWidth() - mLogW * scale) / 2f;
        float offY = (availH - mLogH * scale) / 2f;
        float lx = (px - offX) / scale;
        float ly = (py - offY) / scale;

        float gx = lx / mLogW * 2.0f - 1.0f;
        float gy = 1.0f - ly / mLogH * 2.0f;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownTime = SystemClock.uptimeMillis();
                mDownX = lx; mDownY = ly;
                mMoved = false;

                int si = hitSample(gx, gy);
                if (si >= 0) {
                    mSelectedSample = si;
                    mDragging = true;
                    mDragX = lx; mDragY = ly;
                }
                if (mChapter >= 1) {
                    if (gx >= 0.5f && gx <= 1.0f && gy >= 0.30f && gy <= 0.36f) {
                        mDraggingGreed = true;
                        mGreed = clamp((gx - 0.5f) / 0.5f);
                    }
                    if (gx >= 0.5f && gx <= 1.0f && gy >= 0.16f && gy <= 0.22f) {
                        mDraggingFear = true;
                        mFear = clamp((gx - 0.5f) / 0.5f);
                    }
                }
                for (int i = 0; i < 4; ++i) {
                    float rx = -0.45f + 0.35f + i * 0.15f;
                    if (gx >= rx - 0.04f && gx <= rx + 0.12f && gy >= -0.75f && gy <= -0.65f) {
                        mSelectedRelation = i;
                        updateRelations();
                        invalidate();
                        break;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(lx - mDownX) > 8 || Math.abs(ly - mDownY) > 8) mMoved = true;
                float val = clamp((gx - 0.5f) / 0.5f);
                if (mDraggingGreed) mGreed = val;
                if (mDraggingFear) mFear = val;
                if (mDragging) { mDragX = lx; mDragY = ly; }
                if (mDraggingGreed || mDraggingFear || mDragging) invalidate();
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (mDragging && mSelectedSample >= 0) {
                    int slotIdx = hitSlot(gx, gy);
                    if (slotIdx >= 0) {
                        mSlots[slotIdx].sampleId = mEngine.samples().get(mSelectedSample).id;
                        mSlots[slotIdx].anchor = DeductionData.TimeAnchor.values()[slotIdx % 3];
                    }
                    mDragging = false;
                    mSelectedSample = -1;
                    invalidate();
                } else if (!mMoved) {
                    long elapsed = SystemClock.uptimeMillis() - mDownTime;
                    int slotIdx = hitSlot(gx, gy);
                    if (slotIdx >= 0) {
                        if (elapsed > 500) {
                            mSlots[slotIdx].clear();
                        } else if (mSlots[slotIdx].sampleId >= 0) {
                            DeductionData.Intervention[] acts = DeductionData.Intervention.values();
                            int cur = mSlots[slotIdx].action.ordinal();
                            mSlots[slotIdx].action = acts[(cur + 1) % acts.length];
                        }
                        invalidate();
                    }
                    if (elapsed > 500) {
                        int si2 = hitSample(gx, gy);
                        if (si2 >= 0) {
                            mEngine.lockSample(mEngine.samples().get(si2).id);
                            invalidate();
                        }
                    }
                }
                mDraggingGreed = false;
                mDraggingFear = false;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                mDragging = false;
                mSelectedSample = -1;
                mDraggingGreed = false;
                mDraggingFear = false;
                break;
            }
        }
        return true;
    }

    private boolean onTouchPortrait(MotionEvent event) {
        float s = getWidth() / 640f;
        float lx = event.getX() / s;
        float ly = event.getY() / s;
        float contentY = ly + mScrollY;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownTime = SystemClock.uptimeMillis();
                mDownX = lx; mDownY = ly;
                mMoved = false;
                mLastTouchY = ly;

                for (int i = 0; i < 4; ++i) {
                    float cx = P_REL_X0 + i * (P_CHIP_W + P_CHIP_GAP);
                    if (lx >= cx && lx <= cx + P_CHIP_W && ly >= P_REL_Y && ly <= P_REL_Y + P_CHIP_H) {
                        mSelectedRelation = i;
                        updateRelations();
                        invalidate();
                        return true;
                    }
                }
                int si = hitSamplePortrait(contentY, lx);
                if (si >= 0) {
                    mSelectedSample = si;
                    mDragging = true;
                    mDragX = lx; mDragY = ly;
                }
                if (mChapter >= 1) {
                    if (contentY >= mGreedY - 24 && contentY <= mGreedY + 24
                            && lx >= P_BAR_L - 12 && lx <= P_BAR_L + P_BAR_W + 12) {
                        mDraggingGreed = true;
                        mGreed = clamp((lx - P_BAR_L) / P_BAR_W);
                    }
                    if (contentY >= mFearY - 24 && contentY <= mFearY + 24
                            && lx >= P_BAR_L - 12 && lx <= P_BAR_L + P_BAR_W + 12) {
                        mDraggingFear = true;
                        mFear = clamp((lx - P_BAR_L) / P_BAR_W);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                float dy = ly - mLastTouchY;
                if (Math.abs(lx - mDownX) > 8 || Math.abs(ly - mDownY) > 8) mMoved = true;
                mLastTouchY = ly;
                if (mDraggingGreed) mGreed = clamp((lx - P_BAR_L) / P_BAR_W);
                if (mDraggingFear) mFear = clamp((lx - P_BAR_L) / P_BAR_W);
                if (mDragging) { mDragX = lx; mDragY = ly; }
                if (!mDragging && !mDraggingGreed && !mDraggingFear && mMoved) {
                    mScrollY = clampScroll(mScrollY - dy);
                }
                if (mDragging || mDraggingGreed || mDraggingFear || mMoved) invalidate();
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (mDragging && mSelectedSample >= 0) {
                    int slotIdx = hitSlotPortrait(contentY, lx);
                    if (slotIdx >= 0) {
                        mSlots[slotIdx].sampleId = mEngine.samples().get(mSelectedSample).id;
                        mSlots[slotIdx].anchor = DeductionData.TimeAnchor.values()[slotIdx % 3];
                    }
                    mDragging = false;
                    mSelectedSample = -1;
                    invalidate();
                } else if (!mMoved) {
                    long elapsed = SystemClock.uptimeMillis() - mDownTime;
                    int slotIdx = hitSlotPortrait(contentY, lx);
                    if (slotIdx >= 0) {
                        if (elapsed > 500) {
                            mSlots[slotIdx].clear();
                        } else if (mSlots[slotIdx].sampleId >= 0) {
                            DeductionData.Intervention[] acts = DeductionData.Intervention.values();
                            int cur = mSlots[slotIdx].action.ordinal();
                            mSlots[slotIdx].action = acts[(cur + 1) % acts.length];
                        }
                        invalidate();
                    }
                    if (elapsed > 500) {
                        int si2 = hitSamplePortrait(contentY, lx);
                        if (si2 >= 0) {
                            mEngine.lockSample(mEngine.samples().get(si2).id);
                            invalidate();
                        }
                    }
                }
                mDraggingGreed = false;
                mDraggingFear = false;
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                mDragging = false;
                mSelectedSample = -1;
                mDraggingGreed = false;
                mDraggingFear = false;
                break;
            }
        }
        return true;
    }

    private int hitSamplePortrait(float contentY, float lx) {
        List<DeductionData.DeductionSample> samples = mEngine.samples();
        int n = samples.size();
        if (n <= 0) return -1;
        if (contentY < P_CARD_Y0 || contentY > P_CARD_Y0 + P_CARD_H) return -1;
        float cw = (P_CARD_W - P_CARD_GAP * (n - 1)) / n;
        for (int i = 0; i < n; ++i) {
            float x = P_L + i * (cw + P_CARD_GAP);
            if (lx >= x && lx <= x + cw) return i;
        }
        return -1;
    }

    private int hitSlotPortrait(float contentY, float lx) {
        for (int i = 0; i < 3; ++i) {
            float sy = mSlotsY0 + i * (P_SLOT_H + P_SLOT_GAP);
            if (lx >= P_L && lx <= P_R && contentY >= sy && contentY <= sy + P_SLOT_H) return i;
        }
        return -1;
    }

    private float clampScroll(float v) {
        return Math.max(0f, Math.min(mScrollMax, v));
    }

    private void updateRelations() {
        mRelations.clear();
        DeductionData.Relation[] rels = DeductionData.Relation.values();
        mRelations.add(rels[mSelectedRelation % rels.length]);
        if (mRelations.get(0) == DeductionData.Relation.Excite) {
            mRelations.add(DeductionData.Relation.Mask);
        } else {
            mRelations.add(DeductionData.Relation.Excite);
        }
    }

    private float clamp(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    private int hitSample(float gx, float gy) {
        // 横屏样本条在结果铺底时隐藏，同步不可命中
        if (mHasResult) return -1;
        List<DeductionData.DeductionSample> samples = mEngine.samples();
        int n = samples.size();
        if (n <= 0) return -1;
        float x0 = gx2x(L_SHELF_X0);
        float wTotal = gx2x(L_SHELF_X1) - x0;
        float cw = (wTotal - L_SHELF_GAP * (n - 1)) / n;
        float yTop = gy2y(L_SHELF_Y);
        float px = gx2x(gx), py = gy2y(gy);
        if (py < yTop || py > yTop + L_SHELF_H) return -1;
        for (int i = 0; i < n; ++i) {
            float x = x0 + i * (cw + L_SHELF_GAP);
            if (px >= x && px <= x + cw) return i;
        }
        return -1;
    }

    private int hitSlot(float gx, float gy) {
        float bx = -0.45f, by = 0.7f, bw = 0.9f, bh = 0.18f, gap = 0.06f;
        for (int i = 0; i < 3; ++i) {
            float y = by - (bh + gap) * i;
            if (gx >= bx && gx <= bx + bw && gy >= y - bh && gy <= y) return i;
        }
        return -1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  绘制辅助
    // ═══════════════════════════════════════════════════════════════

    private void drawText(Canvas c, float x, float y, String t, int color, float s) {
        if (t == null || t.isEmpty()) return;
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setColor(color);
        mPaint.setTypeface(Typeface.MONOSPACE);
        String[] lines = t.split("\n");
        if (mPortrait) {
            // 竖屏：逻辑字号按画布缩放/屏幕密度换算，使最终物理字号 ≈ 28*s 像素密度 sp
            mPaint.setTextSize(fontLogicalPx(s));
            float lh = mPaint.getFontSpacing();
            for (int li = 0; li < lines.length; ++li) c.drawText(lines[li], x, y + li * lh, mPaint);
            return;
        }
        // 横屏：沿用经典坐标模型
        mPaint.setTextSize(16f * s * 2.5f);
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        float lh = mPaint.getFontSpacing();
        for (int li = 0; li < lines.length; ++li) c.drawText(lines[li], 0, li * lh, mPaint);
        c.restore();
    }

    private void drawTextWrapped(Canvas c, float x, float y, String t, int color, float s, int maxPx) {
        if (t == null || t.isEmpty()) return;
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setColor(color);
        mPaint.setTypeface(Typeface.MONOSPACE);
        if (mPortrait) {
            mPaint.setTextSize(fontLogicalPx(s));
            List<String> lines = wrapText(t, maxPx);
            float lh = mPaint.getFontSpacing();
            for (int li = 0; li < lines.size(); ++li) c.drawText(lines.get(li), x, y + li * lh, mPaint);
            return;
        }
        // 横屏：沿用经典坐标模型
        mPaint.setTextSize(16f * s * 2.5f);
        List<String> lines = wrapText(t, maxPx);
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        float lh = mPaint.getFontSpacing();
        for (int li = 0; li < lines.size(); ++li) c.drawText(lines.get(li), 0, li * lh, mPaint);
        c.restore();
    }

    /** 竖屏画布内的逻辑字号：最终物理字号 ≈ 28*s（像素密度缩放），不同屏宽/密度下体感一致 */
    private float fontLogicalPx(float s) {
        if (!mPortrait) return 16f * s * 2.5f;
        float d = getResources().getDisplayMetrics().density;
        return 28f * s * d / Math.max(mCanvasScale, 1f);
    }

    private float lineHeight(float s) {
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setTypeface(Typeface.MONOSPACE);
        mPaint.setTextSize(fontLogicalPx(s));
        return mPaint.getFontSpacing();
    }

    private int wrappedLineCount(String t, float s, int maxPx) {
        if (t == null || t.isEmpty()) return 0;
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setTypeface(Typeface.MONOSPACE);
        mPaint.setTextSize(fontLogicalPx(s));
        return wrapText(t, maxPx).size();
    }

    /** 测量给定字号下文本的宽度（逻辑像素） */
    private float measureTextWidth(String t, float s) {
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setTypeface(Typeface.MONOSPACE);
        mPaint.setTextSize(fontLogicalPx(s));
        return mPaint.measureText(t);
    }

    /** 绘制单个样本卡片瓦片（横向样本条共用），bounds 均为逻辑像素 */
    private void drawSampleTile(Canvas c, float x, float y, float w, float h,
                                DeductionData.DeductionSample s, int index) {
        if (w <= 24 || h <= 24) return;
        boolean sel = mSelectedSample == index;
        int bg = GameUi.withAlpha(s.color(), sel ? 70 : 30);
        drawRectP(c, x, y, w, h, bg, true);
        int bc = GameUi.withAlpha(s.color(), sel ? 255 : 80);
        drawRectP(c, x, y, w, h, bc, false);

        float pad = Math.max(6f, w * 0.05f);
        float availW = Math.max(1f, w - pad * 2f);
        float fontScale;
        if (mPortrait) {
            // 竖屏：字号自动收缩，保证名称可在卡片内折行且不与 meta 行重叠
            float availName = Math.max(lineHeight(0.26f), h - 44f);
            fontScale = 0.26f;
            float[] cands = {0.6f, 0.54f, 0.48f, 0.42f, 0.36f, 0.3f, 0.26f};
            for (float sc : cands) {
                if (wrappedLineCount(s.name, sc, (int) availW) * lineHeight(sc) <= availName) {
                    fontScale = sc;
                    break;
                }
            }
        } else {
            fontScale = w >= 170f ? 0.6f : (w >= 110f ? 0.5f : 0.42f);
        }
        if (s.locked) {
            drawText(c, x + w - 18, y + 22, "锁", Color.rgb(255, 200, 100), 0.55f);
        }
        drawTextWrapped(c, x + pad, y + 30, s.name, s.color(), fontScale,
                (int) Math.max(1, availW));
        StringBuilder meta = new StringBuilder();
        if (w >= 170f) {
            meta.append("[").append(s.kindLabel()).append("] ").append(s.source);
            if (s.basePitch > 1.01f) {
                meta.append(" · 音高+").append(String.format("%.1f", s.basePitch));
            }
        } else if (s.basePitch > 1.01f) {
            meta.append("音高+").append(String.format("%.1f", s.basePitch));
        }
        if (meta.length() > 0) {
            String metaText = meta.toString();
            float metaScale = 0.3f;
            if (mPortrait) {
                metaScale = 0.34f;
                while (metaScale > 0.2f && measureTextWidth(metaText, metaScale) > availW) {
                    metaScale -= 0.02f;
                }
                // 仍放不下时截断尾部，避免横向溢出到相邻卡片
                while (measureTextWidth(metaText, metaScale) > availW && metaText.length() > 1) {
                    metaText = metaText.substring(0, metaText.length() - 1);
                }
            }
            drawText(c, x + pad, y + h - 12, metaText,
                    s.basePitch > 1.01f ? Color.rgb(255, 120, 120)
                            : GameUi.lighten(s.color(), 1.3f), metaScale);
        }
    }

    private void drawRectP(Canvas c, float x, float y, float w, float h, int color, boolean filled) {
        RectF r = new RectF(x, y, x + w, y + h);
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setColor(color);
        mPaint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        mPaint.setStrokeWidth(1.5f);
        c.drawRect(r, mPaint);
    }

    private List<String> wrapText(String t, int maxPx) {
        List<String> out = new ArrayList<>();
        for (String raw : t.split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < raw.length(); ++i) {
                char ch = raw.charAt(i);
                cur.append(ch);
                if (mPaint.measureText(cur.toString()) > maxPx && cur.length() > 1) {
                    char last = cur.charAt(cur.length() - 1);
                    cur.deleteCharAt(cur.length() - 1);
                    out.add(cur.toString());
                    cur = new StringBuilder().append(last);
                }
            }
            out.add(cur.toString());
        }
        return out;
    }

    private void drawRect(Canvas c, float gx, float gy, float gw, float gh, int color, boolean filled) {
        float x = gx2x(gx), y = gy2y(gy);
        float w = w2x(gw), h = h2y(gh);
        RectF r = new RectF(x, y, x + w, y + h);
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setColor(color);
        mPaint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        mPaint.setStrokeWidth(1.5f);
        c.drawRect(r, mPaint);
    }

}
