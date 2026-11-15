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
 * 声纹推演盘：三槽拖拽（样本 + 动作 + 锚点）、心理变量滑块、命题展示。
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

    /** 契约/释放不可逆，需二次确认 */
    private static final long CONFIRM_TIMEOUT_MS = 4000;
    private int mPendingId = 0;        // 待二次确认的 chip id（0=无）
    private long mPendingAtMs = 0;
    private String mStatusMsg = "";
    private long mStatusAtMs = 0;
    private float mDragStartX = 0, mDragStartY = 0; // 拖拽起点（轨迹线用）

    /** 可点击 chip（契约/释放） */
    private static final class Chip {
        final float x, y, w, h;
        final int id; // 100+ContractSpirit.ordinal=立约；200+SpiritKind.ordinal=释放；0=已缔约
        Chip(float x, float y, float w, float h, int id) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.id = id;
        }
    }
    private final List<Chip> mChips = new ArrayList<>();

    private static final int CHIP_CONTRACT_BASE = 100;
    private static final int CHIP_RELEASE_BASE = 200;


    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mLogW = 1000f, mLogH = 680f;

    /** 画布整体缩放倍数，用于把目标 sp 字号换算成画布内逻辑字号 */
    private float mCanvasScale = 1f;

    private boolean mPortrait = false;
    /** 底部控制条高度（物理像素，由 Activity 注入） */
    private float mBottomBarH = 0f;
    private float mScrollY = 0f, mScrollMax = 0f;
    private float mContentEnd = 0f;
    private float mLastTouchY = 0f;
    /** 内容坐标（未滚动）中可交互区域位置 */
    private float mGreedY = 0f, mFearY = 0f, mSlotsY0 = 0f;

    /** 顶部固定区按标题宽度给「第 N 章」留位，两处共用 */
    private static final String TITLE = "声纹推演盘";

    // 竖屏布局常量（逻辑画布 640 宽）
    private static final float P_L = 24f, P_R = 616f;
    private static final float P_REL_Y = 68f, P_REL_X0 = 336f;
    private static final float P_CHIP_W = 64f, P_CHIP_H = 30f, P_CHIP_GAP = 6f;
    private static final float P_LIB_Y = 140f; // 样本库标题基线，须在裁剪起点之下
    private static final float P_CARD_W = P_R - P_L;
    private static final float P_CARD_Y0 = P_LIB_Y + 28f; // 卡片条顶 y
    private static final float P_CARD_H = 134f;
    private static final float P_CARD_GAP = 10f;
    /** 小节标题字号：卡片下沿留白要按它的上升部算，写死数值会顶住 */
    private static final float P_SECTION_TITLE_S = 0.72f;
    private static final float P_CARD_GAP_V = 24f;

    // 横屏样本库：底部横向卡片条（结果区铺底时隐藏）
    private static final float L_SHELF_X0 = -0.95f, L_SHELF_X1 = 0.45f;
    private static final float L_SHELF_Y = -0.38f; // 条顶 gy
    private static final float L_SHELF_H = 118f;
    private static final float L_SHELF_GAP = 8f;

    private static final float P_BAR_L = 130f, P_BAR_W = 300f;
    private static final float P_SLOT_H = 112f, P_SLOT_GAP = 16f;
    private static final float P_SCROLL_TOP = 94f; // 滚动区裁剪起点（关系行下沿）

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
        mPendingId = 0;
        mStatusMsg = "";
        if (chapter == 0) mEngine.resetRun(); // 回到序章即开新局
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

    /** 底部控制条高度（物理像素） */
    public void setBottomBarHeight(float px) {
        mBottomBarH = px;
        invalidate();
    }

    // 归一化坐标(-1..1) → 逻辑像素
    private float gx2x(float gx) { return (gx + 1.0f) / 2.0f * mLogW; }
    private float gy2y(float gy) { return (1.0f - gy) / 2.0f * mLogH; }
    private float w2x(float gw) { return gw / 2.0f * mLogW; }
    private float h2y(float gh) { return gh / 2.0f * mLogH; }

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

        mChips.clear();
        renderSamples(canvas);
        renderBoard(canvas);
        renderProposition(canvas);
        renderSpirits(canvas);
        renderDeviation(canvas);
        renderSoulFragments(canvas);
        if (mHasResult) renderResult(canvas);
        if (mChapter == 4 && mHasResult) renderPersonality(canvas);

        canvas.restore();
    }

    private void renderSamples(Canvas c) {
        // 结果区铺满底部，重叠时结果优先
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
                    drawText(c, gx2x(bx + 0.02f), gy2y(y - 0.12f), act, Color.argb(160, 180, 200, 220),
                            fitScale(act, 0.32f, w2x(bw - 0.04f)));
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
                "点槽左半: 切动作 | 右半: 切锚点 | 长按槽: 清空 | 点击关系切换", Color.argb(200, 0, 220, 200), 0.35f);

        // 契约 / 释放 chips（不可逆，需二次确认）
        float chipY = by - 3 * (bh + gap) - 0.30f;
        if (mChapter == 3) {
            DeductionData.ContractSpirit[] cs = {
                    DeductionData.ContractSpirit.Mirror,
                    DeductionData.ContractSpirit.Silence,
                    DeductionData.ContractSpirit.Lie };
            String[] names = { "镜", "默", "谎" };
            for (int i = 0; i < cs.length; ++i) {
                boolean done = mEngine.hasContract(cs[i]);
                float cx = bx + i * 0.18f;
                String label = done ? "已缔约" : "立约·" + names[i];
                drawChipL(c, cx, chipY, 0.16f, 0.07f, label, done,
                        CHIP_CONTRACT_BASE + cs[i].ordinal());
            }
        }
        if (mChapter == 4) {
            List<DeductionData.SpiritKind> kinds = chapterSpiritKinds();
            for (int i = 0; i < kinds.size(); ++i) {
                DeductionData.SpiritKind kind = kinds.get(i);
                DeductionData.SpiritInfo sp = mEngine.findSpirit(kind);
                float cx = bx + i * 0.215f;
                drawChipL(c, cx, chipY, 0.20f, 0.07f, "释放·" + sp.name, false,
                        CHIP_RELEASE_BASE + kind.ordinal());
            }
        }
        // 状态行放 chips 下方，避免与结果区(-0.35)重叠
        renderChipStatusL(c, bx, chipY + 0.10f);

        // 拖拽轨迹 + 跟随光标的样本名
        if (mDragging && mSelectedSample >= 0
                && mSelectedSample < mEngine.samples().size()) {
            DeductionData.DeductionSample s = mEngine.samples().get(mSelectedSample);
            mPaint.reset();
            mPaint.setColor(Color.argb(60, 0, 220, 200));
            mPaint.setStrokeWidth(2f);
            c.drawLine(mDragStartX, mDragStartY, mDragX, mDragY, mPaint);
            drawText(c, mDragX + 10, mDragY, "▶ " + s.name,
                    GameUi.withAlpha(s.color(), 200), 0.4f);
        }
    }

    private void renderProposition(Canvas c) {
        DeductionData.Proposition p = mEngine.currentProposition();
        if (p != null) {
            drawText(c, gx2x(0.5f), gy2y(0.9f), "待验证命题", Color.rgb(255, 210, 100), 0.6f);
            drawTextWrapped(c, gx2x(0.5f), gy2y(0.8f), p.text, Color.rgb(230, 210, 170), 0.4f,
                    (int) w2x(0.44f));
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
        float gy = -0.35f;
        drawText(c, gx2x(-0.95f), gy2y(gy), "推演结果", Color.rgb(0, 220, 160), 0.55f);
        gy -= 0.09f;
        // 说明是否有真实音频，避免玩家等待声音
        drawText(c, gx2x(-0.95f), gy2y(gy),
                mAudio.hasAudioOutput() ? "♪ 真实音频输出" : "♪ 视觉模拟（未检测到音频模块）",
                Color.argb(170, 120, 160, 180), 0.32f);
        gy -= 0.09f;
        int n1 = wrappedLineCount(mLastResult.audioDescription, 0.35f, (int) w2x(1.9f));
        drawTextWrapped(c, gx2x(-0.95f), gy2y(gy), mLastResult.audioDescription,
                Color.rgb(160, 220, 200), 0.35f, (int) w2x(1.9f));
        gy -= Math.min(n1, 6) * lhLand(0.35f) + 0.08f;
        int n2 = wrappedLineCount(mLastResult.logicInference, 0.35f, (int) w2x(1.9f));
        drawTextWrapped(c, gx2x(-0.95f), gy2y(gy), mLastResult.logicInference,
                Color.rgb(200, 200, 220), 0.35f, (int) w2x(1.9f));
        gy -= Math.min(n2, 6) * lhLand(0.35f) + 0.08f;
        drawText(c, gx2x(-0.95f), gy2y(gy),
                String.format("心理可信度：%d%%", (int) (mLastResult.credibility * 100)),
                mLastResult.credibility > 0.65f ? Color.rgb(255, 200, 80) : Color.rgb(150, 180, 200), 0.4f);
        gy -= 0.11f;
        if (mLastResult.rewritesMemory) {
            drawText(c, gx2x(-0.95f), gy2y(gy), "! 此推演正在改写你的记忆库", Color.rgb(255, 100, 100), 0.4f);
        }
    }

    private void renderPersonality(Canvas c) {
        DeductionData.PersonalityProfile pp = mEngine.generatePersonalityProfile();
        drawText(c, gx2x(0.5f), gy2y(-0.5f), "推演板对你的侧写", Color.rgb(255, 210, 100), 0.5f);
        drawTextWrapped(c, gx2x(0.5f), gy2y(-0.6f), pp.summary, Color.rgb(200, 190, 170), 0.35f,
                (int) w2x(0.48f));
    }

    private boolean isPortrait() {
        return getWidth() < getHeight();
    }

    private void renderPortrait(Canvas c) {
        float s = getWidth() / 640f;
        float visH = (getHeight() - mBottomBarH) / s;

        // 基线 54：放大后标题顶部不被裁切；标题与章节各自在半宽内量宽，超出缩字号
        drawText(c, P_L, 54, TITLE, Color.rgb(0, 220, 200),
                fitScale(TITLE, 1.2f, P_REL_X0 - P_L - 8f));
        String chapter = "第 " + (mChapter + 1) + " 章";
        drawText(c, P_REL_X0, 54, chapter, Color.argb(170, 150, 180, 210),
                fitScale(chapter, 0.72f, P_R - P_REL_X0));
        String[] rels = { "掩盖", "激发", "反转", "同步" };
        for (int i = 0; i < 4; ++i) {
            float cx = P_REL_X0 + i * (P_CHIP_W + P_CHIP_GAP);
            int rc = (mSelectedRelation == i) ? Color.rgb(0, 220, 200) : Color.argb(140, 100, 140, 180);
            drawRectP(c, cx, P_REL_Y, P_CHIP_W, P_CHIP_H, GameUi.withAlpha(rc, mSelectedRelation == i ? 60 : 20), true);
            drawRectP(c, cx, P_REL_Y, P_CHIP_W, P_CHIP_H, rc, false);
            // 必须居中：左对齐会顶出圆角框
            drawTextCentered(c, cx, P_CHIP_W, baselineInBox(P_REL_Y, P_CHIP_H, 0.46f), rels[i], rc, 0.46f);
        }

        mScrollMax = Math.max(0, mContentEnd - visH + 80);
        mScrollY = clampScroll(mScrollY);
        c.save();
        c.clipRect(0, P_SCROLL_TOP, 640, visH);
        c.translate(0, -mScrollY);

        float py = P_LIB_Y;
        mChips.clear();
        py = renderPortraitSamples(c, py);
        py = renderPortraitProposition(c, py);
        py = renderPortraitSliders(c, py);
        py = renderPortraitSlots(c, py);
        py = renderPortraitSpirits(c, py);
        // 与上方声灵/槽区拉开距离
        py += 72;
        py = renderPortraitFragments(c, py);
        py = renderPortraitDeviation(c, py);
        py = renderPortraitTip(c, py);
        if (mHasResult) py = renderPortraitResult(c, py);
        mContentEnd = py;

        c.restore();

        // 画在滚动裁剪之外，光标去哪跟到哪
        if (mDragging && mSelectedSample >= 0
                && mSelectedSample < mEngine.samples().size()) {
            DeductionData.DeductionSample smp = mEngine.samples().get(mSelectedSample);
            mPaint.reset();
            mPaint.setColor(Color.argb(60, 0, 220, 200));
            mPaint.setStrokeWidth(2f);
            c.drawLine(mDragStartX, mDragStartY, mDragX, mDragY, mPaint);
            drawText(c, mDragX + 10, mDragY, "▶ " + smp.name,
                    GameUi.withAlpha(smp.color(), 200), 0.4f);
        }
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
        // 卡片下沿 → 下一节标题：标题上升部约 29 逻辑 px，且与屏幕密度无关，定值 24 会顶进卡片
        return yTop + P_CARD_H + P_CARD_GAP_V + lineHeight(P_SECTION_TITLE_S) * 0.85f;
    }

    private float renderPortraitProposition(Canvas c, float py) {
        DeductionData.Proposition p = mEngine.currentProposition();
        if (p == null) return py;
        drawText(c, P_L, py, "待验证命题", Color.rgb(255, 210, 100), P_SECTION_TITLE_S);
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
        // 标题与标签都有 descent，间距不足会重叠
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
        // 文字区左右各留 16，居中后不会顶到边框
        float boxX = P_L + 16;
        float boxW = P_R - P_L - 32;
        String[] names = { "样本", "干预动作", "时间锚点" };
        for (int i = 0; i < 3; ++i) {
            float sy = py + i * (P_SLOT_H + P_SLOT_GAP);
            drawRectP(c, P_L, sy, P_R - P_L, P_SLOT_H, Color.argb(160, 15, 20, 35), true);
            int bc = (mDragging && mSelectedSample >= 0) ? Color.rgb(0, 220, 200) : Color.argb(80, 60, 90, 130);
            drawRectP(c, P_L, sy, P_R - P_L, P_SLOT_H, bc, false);
            drawTextCentered(c, boxX, boxW, sy + 30, String.format("槽%d：%s", i + 1, names[i]),
                    Color.argb(170, 150, 180, 210), 0.58f);
            DeductionData.DeductionSlot slot = mSlots[i];
            if (slot.sampleId >= 0) {
                DeductionData.DeductionSample s = mEngine.findSample(slot.sampleId);
                if (s != null) {
                    drawTextCentered(c, boxX, boxW, sy + 66, "-> " + s.name, s.color(), 0.7f);
                    String act = String.format("动作：%s   锚点：%s", slot.action.label(), slot.anchor.label());
                    drawTextCentered(c, boxX, boxW, sy + 98, act, Color.argb(160, 180, 200, 220), 0.52f);
                }
            } else {
                drawTextCentered(c, boxX, boxW, sy + 66, "（拖拽样本到此处）",
                        Color.argb(120, 80, 100, 130), 0.5f);
            }
        }
        return py + 3 * (P_SLOT_H + P_SLOT_GAP) - P_SLOT_GAP + 26;
    }

    private float renderPortraitSpirits(Canvas c, float py) {
        boolean any = false;
        for (DeductionData.SpiritInfo sp : mEngine.spirits()) {
            if (sp.affinity > 0.0f) { any = true; break; }
        }
        boolean hasChips = (mChapter == 3 && mEngine.spirits().size() > 0)
                || (mChapter == 4 && !chapterSpiritKinds().isEmpty());
        if (!any && !hasChips) return py;
        drawText(c, P_L, py, "声灵", Color.rgb(200, 180, 255), P_SECTION_TITLE_S);
        py += 40;
        for (DeductionData.SpiritInfo sp : mEngine.spirits()) {
            if (sp.affinity <= 0.0f) continue;
            drawCircleP(c, P_L + 8, py - 12, 8, sp.color());
            String line = sp.name + "  好感 " + (int) (sp.affinity * 100) + "%"
                    + (sp.manifested ? "  ·已显形" : "");
            drawText(c, P_L + 24, py, line, sp.color(), 0.54f);
            py += 36;
            if (mEngine.isSpiritBacklashing(sp.kind)) {
                int a = (int) ((0.5f + 0.3f * (float) Math.sin(nowSec() * 4.0)) * 255);
                drawText(c, P_L + 24, py, "⚠ " + mEngine.spiritBacklashText(sp.kind),
                        Color.argb(a, 255, 100, 100), 0.46f);
                py += lineHeight(0.46f) + 8;
            }
        }
        float chipH = 44f, gap = 8f;
        if (mChapter == 3) {
            DeductionData.ContractSpirit[] cs = {
                    DeductionData.ContractSpirit.Mirror,
                    DeductionData.ContractSpirit.Silence,
                    DeductionData.ContractSpirit.Lie };
            String[] names = { "镜", "默", "谎" };
            int border = Color.rgb(180, 150, 220);
            float cw = (P_R - P_L - gap * (cs.length - 1)) / cs.length;
            for (int i = 0; i < cs.length; ++i) {
                boolean done = mEngine.hasContract(cs[i]);
                float cx = P_L + i * (cw + gap);
                String label = done ? "已缔约" : "立约·" + names[i];
                drawRectP(c, cx, py, cw, chipH,
                        GameUi.withAlpha(border, done ? 26 : 46), true);
                drawRectP(c, cx, py, cw, chipH, GameUi.withAlpha(border, done ? 90 : 255), false);
                drawTextCentered(c, cx, cw, baselineInBox(py, chipH, 0.54f), label,
                        GameUi.withAlpha(border, done ? 120 : 255), 0.54f);
                mChips.add(new Chip(cx, py, cw, chipH,
                        done ? 0 : CHIP_CONTRACT_BASE + cs[i].ordinal()));
            }
            py += chipH + 10;
        }
        if (mChapter == 4) {
            List<DeductionData.SpiritKind> kinds = chapterSpiritKinds();
            if (!kinds.isEmpty()) {
                float cw = (P_R - P_L - gap * (kinds.size() - 1)) / kinds.size();
                for (int i = 0; i < kinds.size(); ++i) {
                    DeductionData.SpiritKind kind = kinds.get(i);
                    DeductionData.SpiritInfo sp = mEngine.findSpirit(kind);
                    float cx = P_L + i * (cw + gap);
                    drawRectP(c, cx, py, cw, chipH, GameUi.withAlpha(Color.rgb(180, 150, 220), 46), true);
                    drawRectP(c, cx, py, cw, chipH, Color.rgb(180, 150, 220), false);
                    drawTextCentered(c, cx, cw, baselineInBox(py, chipH, 0.54f),
                            "释放·" + sp.name, Color.rgb(200, 180, 240),
                            fitScale("释放·" + sp.name, 0.54f, cw - 8f));
                    mChips.add(new Chip(cx, py, cw, chipH, CHIP_RELEASE_BASE + kind.ordinal()));
                }
                py += chipH + 10;
            }
        }
        return renderChipStatusP(c, py) + 8;
    }

    private float renderPortraitFragments(Canvas c, float py) {
        List<DeductionData.SoulFragment> frags = mEngine.soulFragments();
        int total = frags.size();
        if (total <= 0) return py;
        float t = nowSec();
        drawText(c, P_L, py, String.format("灵魂碎片 %d/%d",
                        mEngine.collectedSoulFragmentCount(), total),
                Color.rgb(255, 220, 120), 0.6f);
        py += 38;
        // 铺满左右边距，数量变化时自动均分
        float ph = 18f, pgap = 8f;
        float pw = (P_R - P_L - pgap * (total - 1)) / total;
        for (int i = 0; i < total; ++i) {
            DeductionData.SoulFragment f = frags.get(i);
            int col = f.collected ? 0xFFFFDC78 : Color.argb(120, 60, 70, 90);
            if (f.glowing) {
                int a = (int) (255 * (0.6f + 0.4f * (float) Math.sin(t * 3.0 + i)));
                col = Color.argb(a, 255, 220, 120);
            }
            drawRectP(c, P_L + i * (pw + pgap), py, pw, ph, col, true);
        }
        // 留足标题上升部（≈26 逻辑 px），否则「偏差能量」压住方块
        py += ph + 32;
        if (mEngine.allSoulFragmentsCollected()) {
            int a = (int) ((0.5f + 0.4f * (float) Math.sin(t * 2.0)) * 255);
            drawText(c, P_L, py, "碎片拼合成一张脸——那是林薇母亲的最终模样。",
                    Color.argb(a, 255, 230, 150), 0.52f);
            py += 34;
        }
        return py;
    }

    private float renderPortraitDeviation(Canvas c, float py) {
        DeductionData.DeviationEnergy dev = mEngine.deviation();
        drawText(c, P_L, py, String.format("偏差能量 %d%%", (int) (dev.amount * 100)),
                Color.argb(200, 180, 160, 255), 0.6f);
        py += 40;
        // 全宽条与左右边距对齐，避免左端缩进
        drawRectP(c, P_L, py - 6, P_R - P_L, 12, Color.argb(180, 40, 40, 60), true);
        drawRectP(c, P_L, py - 6, (P_R - P_L) * dev.amount, 12, Color.argb(220, 180, 160, 255), true);
        py += 30;
        if (dev.overloaded) {
            int a = (int) ((0.5f + 0.5f * (float) Math.sin(nowSec() * 1.5)) * 220);
            drawText(c, P_L, py, "「你听，世界正在听你。」", Color.argb(a, 255, 255, 255), 0.58f);
            py += 40;
        }
        return py;
    }

    private float renderPortraitTip(Canvas c, float py) {
        String tip = "长按样本锁存 | 点槽左半切动作 右半切锚点 | 长按槽清空";
        drawText(c, P_L, py, tip, Color.argb(140, 100, 130, 160), fitScale(tip, 0.5f, P_R - P_L));
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
        mEngine.accumulateDeviation(0.05f);

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
                    mDragStartX = lx; mDragStartY = ly;
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
                    }
                    mDragging = false;
                    mSelectedSample = -1;
                    invalidate();
                } else if (!mMoved) {
                    long elapsed = SystemClock.uptimeMillis() - mDownTime;
                    int cid = hitChip(gx, gy);
                    if (cid > 0) {
                        handleChipTap(cid);
                    } else {
                        if (mPendingId != 0) mPendingId = 0; // 点其他处取消待确认操作
                        int slotIdx = hitSlot(gx, gy);
                        if (slotIdx >= 0) {
                            if (elapsed > 500) {
                                mSlots[slotIdx].clear();
                            } else if (mSlots[slotIdx].sampleId >= 0) {
                                // 左半切干预动作，右半切时间锚点
                                if (gx >= 0.0f) {
                                    DeductionData.TimeAnchor[] anchors = DeductionData.TimeAnchor.values();
                                    int cur = mSlots[slotIdx].anchor.ordinal();
                                    mSlots[slotIdx].anchor = anchors[(cur + 1) % anchors.length];
                                } else {
                                    DeductionData.Intervention[] acts = DeductionData.Intervention.values();
                                    int cur = mSlots[slotIdx].action.ordinal();
                                    mSlots[slotIdx].action = acts[(cur + 1) % acts.length];
                                }
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
                    mDragStartX = lx; mDragStartY = ly;
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
                    }
                    mDragging = false;
                    mSelectedSample = -1;
                    invalidate();
                } else if (!mMoved) {
                    long elapsed = SystemClock.uptimeMillis() - mDownTime;
                    int cid = hitChip(lx, contentY);
                    if (cid > 0) {
                        handleChipTap(cid);
                    } else {
                        if (mPendingId != 0) mPendingId = 0; // 点其他处取消待确认操作
                        int slotIdx = hitSlotPortrait(contentY, lx);
                        if (slotIdx >= 0) {
                            if (elapsed > 500) {
                                mSlots[slotIdx].clear();
                            } else if (mSlots[slotIdx].sampleId >= 0) {
                                // 左半切干预动作，右半切时间锚点
                                if (lx >= (P_L + P_R) / 2f) {
                                    DeductionData.TimeAnchor[] anchors = DeductionData.TimeAnchor.values();
                                    int cur = mSlots[slotIdx].anchor.ordinal();
                                    mSlots[slotIdx].anchor = anchors[(cur + 1) % anchors.length];
                                } else {
                                    DeductionData.Intervention[] acts = DeductionData.Intervention.values();
                                    int cur = mSlots[slotIdx].action.ordinal();
                                    mSlots[slotIdx].action = acts[(cur + 1) % acts.length];
                                }
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

    // 显形的声灵：屏幕右缘彩色同心圆脉冲
    private void renderSpirits(Canvas c) {
        int shown = 0;
        float t = nowSec();
        for (DeductionData.SpiritInfo sp : mEngine.spirits()) {
            if (!sp.manifested) continue;
            // 起始点须在命题区下方，否则与右上角「待验证命题」重叠
            float baseY = 0.62f - shown * 0.17f;
            float pulse = 0.5f + 0.3f * (float) Math.sin(t * 2.0 + shown);
            for (int ring = 0; ring < 3; ++ring) {
                float rr = 0.05f + ring * 0.03f + 0.01f * pulse;
                int col = GameUi.withAlpha(sp.color(), 120 - ring * 35);
                drawCircleP(c, gx2x(0.92f), gy2y(baseY), w2x(rr), col);
            }
            drawText(c, gx2x(0.88f), gy2y(baseY - 0.04f), sp.name, sp.color(), 0.34f);
            drawText(c, gx2x(0.88f), gy2y(baseY - 0.09f),
                    String.format("好感 %d%%", (int) (sp.affinity * 100)), sp.color(), 0.3f);
            if (mEngine.isSpiritBacklashing(sp.kind)) {
                int a = (int) ((0.5f + 0.3f * (float) Math.sin(t * 4.0)) * 255);
                drawText(c, gx2x(0.88f), gy2y(baseY - 0.14f), "⚠ 声灵反噬中",
                        Color.argb(a, 255, 100, 100), 0.3f);
            }
            shown++;
        }
    }

    private void renderDeviation(Canvas c) {
        DeductionData.DeviationEnergy dev = mEngine.deviation();
        drawText(c, gx2x(0.5f), gy2y(-0.26f),
                String.format("偏差能量 %d%%", (int) (dev.amount * 100)),
                Color.argb(200, 180, 160, 255), 0.4f);
        if (dev.overloaded) {
            int a = (int) ((0.5f + 0.5f * (float) Math.sin(nowSec() * 1.5)) * 220);
            drawText(c, gx2x(0.5f), gy2y(-0.36f), "「你听，世界正在听你。」",
                    Color.argb(a, 255, 255, 255), 0.5f);
        }
    }

    private void renderSoulFragments(Canvas c) {
        List<DeductionData.SoulFragment> frags = mEngine.soulFragments();
        int total = frags.size();
        if (total <= 0) return;
        float t = nowSec();
        drawText(c, gx2x(0.5f), gy2y(-0.02f),
                String.format("灵魂碎片 %d/%d", mEngine.collectedSoulFragmentCount(), total),
                Color.argb(220, 255, 220, 120), 0.46f);
        float pieceW = 0.05f, gap = 0.015f;
        for (int i = 0; i < total; ++i) {
            DeductionData.SoulFragment f = frags.get(i);
            int col = f.collected ? 0xFFFFDC78 : Color.argb(120, 60, 70, 90);
            if (f.glowing) {
                int a = (int) (255 * (0.6f + 0.4f * (float) Math.sin(t * 3.0 + i)));
                col = Color.argb(a, 255, 220, 120);
            }
            drawRect(c, 0.5f + i * (pieceW + gap), -0.08f, pieceW, 0.05f, col, true);
        }
        if (mEngine.allSoulFragmentsCollected()) {
            int a = (int) ((0.5f + 0.4f * (float) Math.sin(t * 2.0)) * 255);
            drawText(c, gx2x(0.5f), gy2y(-0.16f),
                    "碎片拼合成一张脸——那是林薇母亲的最终模样。",
                    Color.argb(a, 255, 230, 150), 0.42f);
        }
    }

    /** 本章样本出现的声灵种类（去重保序），终章释放 chips 用 */
    private List<DeductionData.SpiritKind> chapterSpiritKinds() {
        List<DeductionData.SpiritKind> kinds = new ArrayList<>();
        for (DeductionData.DeductionSample s : mEngine.samples()) {
            if (!kinds.contains(s.spiritKind)) kinds.add(s.spiritKind);
        }
        return kinds;
    }

    private float nowSec() {
        return SystemClock.uptimeMillis() / 1000f;
    }

    private boolean pendingActive() {
        return mPendingId != 0 && SystemClock.uptimeMillis() - mPendingAtMs < CONFIRM_TIMEOUT_MS;
    }

    private int hitChip(float x, float y) {
        for (Chip ch : mChips) {
            if (ch.id > 0 && x >= ch.x && x <= ch.x + ch.w && y >= ch.y && y <= ch.y + ch.h) {
                return ch.id;
            }
        }
        return 0;
    }

    /** 契约/释放点击：不可逆操作需 4 秒内再点一次确认 */
    private void handleChipTap(int id) {
        long now = SystemClock.uptimeMillis();
        boolean confirmed = (mPendingId == id) && (now - mPendingAtMs < CONFIRM_TIMEOUT_MS);
        if (mChapter == 3 && id >= CHIP_CONTRACT_BASE && id < CHIP_RELEASE_BASE) {
            DeductionData.ContractSpirit[] cs = {
                    DeductionData.ContractSpirit.Mirror,
                    DeductionData.ContractSpirit.Silence,
                    DeductionData.ContractSpirit.Lie };
            DeductionData.ContractSpirit target = cs[id - CHIP_CONTRACT_BASE];
            if (mEngine.hasContract(target)) return;
            if (confirmed) {
                mEngine.makeContract(target);
                mStatusMsg = "契约已缔结：" + mEngine.contractCostText(target);
                mStatusAtMs = now;
                mPendingId = 0;
            } else {
                mPendingId = id;
                mPendingAtMs = now;
                mStatusMsg = mEngine.contractCostText(target) + " 再点一次确认（不可撤销）";
                mStatusAtMs = now;
            }
        } else if (mChapter == 4 && id >= CHIP_RELEASE_BASE) {
            DeductionData.SpiritKind kind = DeductionData.SpiritKind.values()[id - CHIP_RELEASE_BASE];
            DeductionData.SpiritInfo sp = mEngine.findSpirit(kind);
            if (confirmed) {
                mStatusMsg = mEngine.releaseSpirit(kind);
                mStatusAtMs = now;
                mPendingId = 0;
            } else {
                mPendingId = id;
                mPendingAtMs = now;
                mStatusMsg = "释放「" + (sp != null ? sp.name : "?") + "」不可撤销，再点一次确认";
                mStatusAtMs = now;
            }
        } else {
            mPendingId = 0;
        }
        invalidate();
    }

    /** 横屏 chip：disabled 灰化且不参与命中 */
    private void drawChipL(Canvas c, float gx, float gy, float gw, float gh,
                           String label, boolean disabled, int id) {
        int border = disabled ? Color.argb(90, 150, 150, 170) : Color.argb(220, 180, 150, 220);
        drawRect(c, gx, gy, gw, gh, GameUi.withAlpha(border, disabled ? 26 : 46), true);
        drawRect(c, gx, gy, gw, gh, border, false);
        float scale = disabled ? 0.34f : (id >= CHIP_RELEASE_BASE ? 0.30f : 0.38f);
        drawText(c, gx2x(gx) + 6, gy2y(gy) + h2y(gh) / 2f + 8, label, border, scale);
        if (!disabled) mChips.add(new Chip(gx, gy, gw, gh, id));
    }

    private void drawCircleP(Canvas c, float cx, float cy, float r, int color) {
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r, mPaint);
    }

    /** 状态与确认提示行（横屏） */
    private void renderChipStatusL(Canvas c, float gx, float gy) {
        long now = SystemClock.uptimeMillis();
        if (!mStatusMsg.isEmpty() && now - mStatusAtMs < CONFIRM_TIMEOUT_MS) {
            drawTextWrapped(c, gx2x(gx), gy2y(gy), mStatusMsg,
                    Color.rgb(255, 190, 120), 0.3f, (int) w2x(0.9f));
            return;
        }
        if (pendingActive()) {
            int a = (int) ((0.5f + 0.3f * (float) Math.sin(nowSec() * 6.0)) * 255);
            drawText(c, gx2x(gx), gy2y(gy), "⚠ 再点一次相同按钮确认（点其他处取消）",
                    Color.argb(a, 255, 100, 100), 0.35f);
        }
    }

    /** 状态与确认提示行（竖屏），返回新 y */
    private float renderChipStatusP(Canvas c, float py) {
        long now = SystemClock.uptimeMillis();
        int w = (int) (P_R - P_L - 20);
        if (!mStatusMsg.isEmpty() && now - mStatusAtMs < CONFIRM_TIMEOUT_MS) {
            int n = wrappedLineCount(mStatusMsg, 0.5f, w);
            drawTextWrapped(c, P_L, py, mStatusMsg, Color.rgb(255, 190, 120), 0.5f, w);
            return py + n * lineHeight(0.5f) + 8;
        }
        if (pendingActive()) {
            int a = (int) ((0.5f + 0.3f * (float) Math.sin(nowSec() * 6.0)) * 255);
            drawText(c, P_L, py, "⚠ 再点一次相同按钮确认（点其他处取消）",
                    Color.argb(a, 255, 100, 100), 0.52f);
            return py + 36;
        }
        return py;
    }

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

    /** 横屏行高换算成 GL 归一化 y 增量（与 gy2y 同一套坐标模型，避免结果区多行互相压） */
    private float lhLand(float s) {
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setTypeface(Typeface.MONOSPACE);
        mPaint.setTextSize(16f * s * 2.5f);
        return mPaint.getFontSpacing() / (mLogH / 2f);
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

    /**
     * 从 s 起步逐档缩小直到放得下 maxW（卡片宽度随样本数变化，写死字号会顶出边框）；
     * 到下限仍放不下由 wrapText 折行兜底。
     */
    private float fitScale(String t, float s, float maxW) {
        if (t == null || t.isEmpty() || maxW <= 0) return s;
        float sc = s;
        while (sc > 0.18f && measureTextWidth(t, sc) > maxW) {
            sc -= 0.02f;
        }
        return sc;
    }

    /** 一行文字在 [top, top+h] 这段高度里垂直居中时的基线位置 */
    private float baselineInBox(float top, float h, float s) {
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setTypeface(Typeface.MONOSPACE);
        mPaint.setTextSize(fontLogicalPx(s));
        Paint.FontMetrics fm = mPaint.getFontMetrics();
        return top + h / 2f - (fm.ascent + fm.descent) / 2f;
    }

    /** 在 [bx, bx+bw] 内水平居中画一行；放不下就先缩字号，绝不越出左右边框 */
    private void drawTextCentered(Canvas c, float bx, float bw, float baseline, String t,
                                  int color, float s) {
        if (t == null || t.isEmpty() || bw <= 0) return;
        float scale = fitScale(t, s, bw);
        float x = bx + (bw - measureTextWidth(t, scale)) / 2f;
        drawText(c, Math.max(bx, x), baseline, t, color, scale);
    }

    /** 每行各自居中（整段居中会让长短行边缘参差） */
    private void drawTextWrappedCentered(Canvas c, float bx, float bw, float baseline, String t,
                                         int color, float s) {
        if (t == null || t.isEmpty() || bw <= 0) return;
        List<String> lines = wrappedLines(t, (int) bw, s);
        float lh = lineHeight(s);
        for (int i = 0; i < lines.size(); ++i) {
            String line = lines.get(i);
            float x = bx + (bw - measureTextWidth(line, s)) / 2f;
            drawText(c, Math.max(bx, x), baseline + i * lh, line, color, s);
        }
    }

    /** 按给定字号折行：wrapText 依赖 mPaint 当前的字号，所以字号在这里统一设好 */
    private List<String> wrappedLines(String t, int maxPx, float s) {
        mPaint.reset();
        mPaint.setAntiAlias(true);
        mPaint.setTypeface(Typeface.MONOSPACE);
        mPaint.setTextSize(fontLogicalPx(s));
        return wrapText(t, maxPx);
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
        // 锁标记置于右上角，名称整体下移一行为其让位，避免两处文字重叠
        float nameTop = y + 30;
        if (s.locked) {
            float lockW = Math.min(34f, availW * 0.3f);
            drawTextCentered(c, x + w - pad - lockW, lockW, y + 22, "锁",
                    Color.rgb(255, 200, 100), 0.42f);
            nameTop = y + 52;
        }
        float fontScale;
        if (mPortrait) {
            // 竖屏：字号自动收缩，保证名称可在卡片内折行且不与 meta 行重叠
            float availName = Math.max(lineHeight(0.26f), h - (nameTop - y) - 26f);
            fontScale = 0.26f;
            float[] cands = {0.6f, 0.54f, 0.48f, 0.42f, 0.36f, 0.3f, 0.26f};
            for (float sc : cands) {
                if (wrappedLines(s.name, (int) availW, sc).size() * lineHeight(sc) <= availName) {
                    fontScale = sc;
                    break;
                }
            }
        } else {
            fontScale = w >= 170f ? 0.6f : (w >= 110f ? 0.5f : 0.42f);
        }
        drawTextWrappedCentered(c, x + pad, availW, nameTop, s.name, s.color(), fontScale);
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
                metaScale = fitScale(metaText, 0.34f, availW);
                // 缩到下限仍放不下才截断尾部，避免横向溢出到相邻卡片
                while (measureTextWidth(metaText, metaScale) > availW && metaText.length() > 1) {
                    metaText = metaText.substring(0, metaText.length() - 1);
                }
            }
            drawTextCentered(c, x + pad, availW, y + h - 12, metaText,
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
