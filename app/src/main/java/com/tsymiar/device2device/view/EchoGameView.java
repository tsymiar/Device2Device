package com.tsymiar.device2device.view;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import com.tsymiar.device2device.game.EchoEngine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 《余音回响》游戏渲染视图
 *
 * 单一视图承载全部游戏阶段：标题 → 章节引言 → 碎片选择 → 时间轴拼图
 * → 场景完成 → 导师信息 → 选择分支 → 结局。Canvas 绘制 + 触摸交互。
 *
 * 已同步 QtGames/echo_resonance 完整剧情版：
 * 锚点音 / 逆向播放 / 底噪意识 / 摩斯电码 / 幻觉 / 多选项分支 / 六结局。
 */
public class EchoGameView extends View {

    // ---------- 回调 ----------
    public interface GameListener {
        /** 游戏结束（结局展示完毕），可退出页面 */
        void onGameFinished();
    }

    private final EchoEngine mEngine;
    private GameListener mListener;
    private final Random mRandom = new Random();

    // ---------- 画笔 ----------
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mHintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    // ---------- 布局 ----------
    private float mW, mH, mDensity;
    private float mBaseY;
    /** 顶栏（标题/偏执/焦虑仪表）下方的首个正文/提示行基线，由 drawTopBar 按实际布局计算 */
    private float mHeaderContentY;
    private float mSlotGap = 8f;   // 时间轴槽位间距（dynamicSlotH 收紧时同步更新）
    private final RectF mCardRect = new RectF();
    private final RectF mSlotRect = new RectF();

    // ---------- 输入 ----------
    private float mDownX, mDownY, mLastY;
    private boolean mTapped;
    private boolean mScrollLocked;  // 移动端：水平滑动时锁定本次手势的纵向滚动
    private float mSelectScroll;   // 碎片列表滚动偏移
    private float mSelectScrollMax;
    private float mContentScroll = 0f;   // 长内容页（引言/教程/场景完成/导师/结局）滚动偏移
    private float mContentScrollMax = 0f;
    private float mContentEnd = 0f;      // 长内容页内容流终点（逻辑 y，用于计算滚动上限）

    // ---------- 动效 ----------
    private final List<float[]> mParticles = new ArrayList<>();
    private long mLastFrame = 0;

    // ---------- Qt 视觉特效（EchoMainWindow 同步） ----------
    private float mGameTime = 0f;          // Qt m_gameTime（时间轴警告闪烁等）
    private float mGlitchIntensity = 0f;   // Qt m_glitchIntensity（扭曲撕裂强度）
    private float mNoiseOffset = 0f;       // Qt m_noiseOffset
    private float mBgFlicker = 0f;         // Qt m_bgFlicker（背景微闪）
    private float mParanoiaDisplay = 0f;   // Qt m_paranoiaDisplay（偏执显示平滑值 0-1）
    private float mAnxietyDisplay = 0f;    // Qt m_anxietyDisplay（焦虑显示平滑值 0-1）
    private float mLowFreqDisplay = 0f;    // Qt m_lowFreqDisplay（低噪显示平滑值 0-1）
    private final Random mFxRandom = new Random();
    private final List<GlitchParticle> mGlitchParticles = new ArrayList<>();
    private final List<DriftText> mHallucinations = new ArrayList<>();
    private final List<NoiseText> mNoiseTexts = new ArrayList<>();
    private final List<SpectrumLine> mSpectrumLines = new ArrayList<>();

    public EchoGameView(Context context, EchoEngine engine) {
        super(context);
        mEngine = engine;
        initPaint();
        initParticles();
        initSpectrum();
    }

    public void setGameListener(GameListener listener) {
        mListener = listener;
    }

    private void initPaint() {
        mTextPaint.setColor(GameUi.TEXT);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTitlePaint.setColor(GameUi.ACCENT);
        mTitlePaint.setTextAlign(Paint.Align.CENTER);
        mHintPaint.setColor(GameUi.TEXT_FAINT);
        mHintPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void initParticles() {
        for (int i = 0; i < 40; i++) {
            mParticles.add(new float[]{
                    mRandom.nextFloat(), mRandom.nextFloat(),
                    mRandom.nextFloat() * 2f - 1f, mRandom.nextFloat() * 0.6f,
                    mRandom.nextFloat() * 0.6f + 0.1f
            });
        }
    }

    /** Qt：初始化 64 条频谱线（频率按 i/8 指数递增） */
    private void initSpectrum() {
        mSpectrumLines.clear();
        for (int i = 0; i < 64; i++) {
            SpectrumLine l = new SpectrumLine();
            l.frequency = 20f * (float) Math.pow(2f, i / 8f);
            l.amplitude = (mFxRandom.nextInt(100)) / 200f;
            l.phase = (mFxRandom.nextInt(628)) / 100f;
            mSpectrumLines.add(l);
        }
    }

    // ---------- 尺寸 ----------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mW = w;
        mH = h;
        mDensity = getResources().getDisplayMetrics().density;
        mTextPaint.setTextSize(14 * mDensity);
        mTitlePaint.setTextSize(30 * mDensity);
        mHintPaint.setTextSize(12 * mDensity);
    }

    private float sp(float sp) {
        return sp * mDensity;
    }

    // ---------- 触摸 ----------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mLastY = event.getY();
                mTapped = true;
                mScrollLocked = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                // 碎片列表 / 长内容页滚动（移动端：水平主导的滑动不触发纵向滚动，防误触）
                if ((mEngine.getState() == EchoEngine.State.FRAGMENT_SELECT || isContentScrollable())
                        && !mScrollLocked) {
                    float totalDx = event.getX() - mDownX;
                    float totalDy = event.getY() - mDownY;
                    if (Math.abs(totalDx) > sp(20) && Math.abs(totalDx) > Math.abs(totalDy)) {
                        mScrollLocked = true;
                    } else {
                        float dy = event.getY() - mLastY;
                        mLastY = event.getY();
                        if (mEngine.getState() == EchoEngine.State.FRAGMENT_SELECT) {
                            mSelectScroll -= dy;
                            clampSelectScroll();
                        } else {
                            mContentScroll -= dy;
                            clampContentScroll();
                        }
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mTapped) {
                    float dx = event.getX() - mDownX;
                    float dy = event.getY() - mDownY;
                    if (dx * dx + dy * dy < sp(10) * sp(10)) {
                        handleTap(event.getX(), event.getY());
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                mTapped = false;
                return true;
        }
        return true;
    }

    private void handleTap(float x, float y) {
        switch (mEngine.getState()) {
            case TITLE:
                // Qt：标题 → 名字输入（NameInput）
                mEngine.setState(EchoEngine.State.NAME_INPUT);
                invalidate();
                break;
            case NAME_INPUT:
                // Qt：键盘输入名字 → 回车进入序章；移动端用系统输入对话框
                showNameInputDialog();
                break;
            case CHAPTER_INTRO:
                // Qt：序章引言后进入教程页；其余章节直接进入碎片选择
                if (mEngine.getChapterIndex() == 0) {
                    mEngine.setState(EchoEngine.State.TUTORIAL);
                } else {
                    mEngine.setState(EchoEngine.State.FRAGMENT_SELECT);
                }
                invalidate();
                break;
            case TUTORIAL:
                // Qt：教程页 TAB → 时间轴拼图
                mEngine.setState(EchoEngine.State.TIMELINE);
                invalidate();
                break;
            case ECHO_ARCHIVE:
                // Qt：档案页 ENTER/ESC → 返回碎片选择
                mEngine.setState(EchoEngine.State.FRAGMENT_SELECT);
                invalidate();
                break;
            case FRAGMENT_SELECT:
                handleFragmentSelectTap(x, y);
                break;
            case TIMELINE:
                handleTimelineTap(x, y);
                break;
            case SCENE_COMPLETE:
                // Qt：序章/三章/四章 → 选择分支；终章 → 结局；一/二章 → 导师日志
                if (mEngine.getCurrentChoice() != null) {
                    mEngine.setState(EchoEngine.State.CHOICE);
                } else if (mEngine.getChapterIndex() >= mEngine.getChapterCount() - 1) {
                    mEngine.setState(EchoEngine.State.ENDING);
                } else {
                    mEngine.enterMentor();
                }
                invalidate();
                break;
            case MENTOR:
                mEngine.nextChapter();
                invalidate();
                break;
            case CHOICE:
                handleChoiceTap(x, y);
                break;
            case ENDING:
                // Qt：结局画面 ENTER → 有余波录音则进余波页，否则二周目（GameOver）
                mEngine.setState(mEngine.currentEndingHasEpilogue()
                        ? EchoEngine.State.EPILOGUE : EchoEngine.State.GAME_OVER);
                invalidate();
                break;
            case EPILOGUE:
                // Qt：余波录音页 ENTER → 二周目（GameOver，复用标题画面）
                mEngine.setState(EchoEngine.State.GAME_OVER);
                invalidate();
                break;
            case GAME_OVER:
                // Qt：GameOver ENTER → 重开序章
                mEngine.startGame();
                invalidate();
                break;
        }
    }

    // ---------- 碎片选择 ----------

    /** 移动端适配：宽屏（横屏/平板，≥600dp）3 列，普通手机 2 列 */
    private int selectCols() {
        return (mW / mDensity) >= 600 ? 3 : 2;
    }

    /** 移动端适配：碎片较多时压缩卡片高度，减少滚动距离（下限保证文字完整） */
    private float selectCardH() {
        int pool = mEngine.getChapter().pool.size();
        int rows = (pool + selectCols() - 1) / selectCols();
        if (rows <= 6) return sp(150);
        if (rows <= 10) return sp(136);
        return sp(122);
    }

    private void clampSelectScroll() {
        int cols = selectCols();
        int rows = (mEngine.getChapter().pool.size() + cols - 1) / cols;
        float total = mBaseY + rows * selectCardH() + sp(8);
        // 底部为碎片页常驻面板（计数 / 操作条 / 逆向摩斯指示），卡片滚动须止于其上，避免与文字重叠
        float viewport = mH - sp(88);
        mSelectScrollMax = Math.max(0, total - viewport);
        mSelectScroll = Math.max(0, Math.min(mSelectScrollMax, mSelectScroll));
    }

    private boolean isContentScrollable() {
        switch (mEngine.getState()) {
            case CHAPTER_INTRO:
            case TUTORIAL:
            case SCENE_COMPLETE:
            case MENTOR:
            case ENDING:
            case EPILOGUE:
            case ECHO_ARCHIVE:
                return true;
            default:
                return false;
        }
    }

    private void clampContentScroll() {
        mContentScroll = Math.max(0, Math.min(mContentScrollMax, mContentScroll));
    }

    // 长内容页滚动区：从 clipTop 裁剪到距底部 sp(90)，内容可上下滑动
    private void beginContentScroll(Canvas canvas, float clipTop) {
        canvas.save();
        canvas.clipRect(0, clipTop, mW, mH - sp(90));
        canvas.translate(0, -mContentScroll);
    }

    private void endContentScroll(Canvas canvas) {
        canvas.restore();
        mContentScrollMax = Math.max(0, mContentEnd - (mH - sp(90)));
        mContentScroll = Math.max(0, Math.min(mContentScrollMax, mContentScroll));
    }

    private void handleFragmentSelectTap(float x, float y) {
        List<EchoEngine.Fragment> pool = mEngine.getChapter().pool;
        int cols = selectCols();
        float cardW = mW / cols;
        float cardH = selectCardH();
        float startY = mBaseY;
        for (int i = 0; i < pool.size(); i++) {
            int row = i / cols;
            int col = i % cols;
            float cx = col * cardW + cardW / 2;
            float cy = startY + row * cardH + cardH / 2 - mSelectScroll;
            if (Math.abs(x - cx) < cardW / 2 && Math.abs(y - cy) < cardH / 2) {
                mEngine.selectFragment(i);
                mEngine.setState(EchoEngine.State.TIMELINE);
                invalidate();
                return;
            }
        }
        // 底部操作区（仅按钮条范围）：左 = 回声档案（Qt 按 E），右 = 进入拼图
        if (y > mH - sp(70) && y < mH - sp(20)) {
            mEngine.setState(x < mW / 2f
                    ? EchoEngine.State.ECHO_ARCHIVE : EchoEngine.State.TIMELINE);
            invalidate();
        }
    }

    // ---------- 时间轴拼图 ----------

    private void handleTimelineTap(float x, float y) {
        // 顶部切换区域：左（上一碎片）/ 中（倒放）/ 右（下一碎片）
        if (y < mBaseY + sp(26)) {
            if (x < mW * 0.33f) {
                selectPrevFragment();
            } else if (x > mW * 0.67f) {
                selectNextFragment();
            } else {
                mEngine.toggleReverse();
            }
            invalidate();
            return;
        }
        List<EchoEngine.Slot> slots = mEngine.getChapter().slots;
        float startY = mBaseY + sp(40);
        float gap = sp(8);
        float slotH = dynamicSlotH();
        float margin = sp(12);
        for (int i = 0; i < slots.size(); i++) {
            float top = startY + i * (slotH + gap);
            float bottom = top + slotH;
            if (y >= top - margin && y <= bottom + margin) {
                EchoEngine.Slot slot = slots.get(i);
                if (slot.isFilled()) {
                    mEngine.clearSlot(i); // 点击已填充槽位 → 移除
                } else {
                    mEngine.placeFragment(i); // 放置当前碎片
                }
                invalidate();
                return;
            }
        }
        // 底部确认区
        if (y > mH - sp(80)) {
            if (mEngine.isPuzzleComplete()) {
                mEngine.commitPuzzle();
                invalidate();
            }
        }
    }

    /** 槽位高度：槽位较多时自动压缩以适配屏幕 */
    private float dynamicSlotH() {
        int count = mEngine.getChapter().slots.size();
        if (count <= 0) return sp(34);
        float startY = mBaseY + sp(40);
        float avail = mH - sp(80) - startY;
        mSlotGap = sp(8);
        float h = (avail - (count - 1) * mSlotGap) / count;
        if (h < sp(34)) {
            // 槽位多 / 屏幕矮：先收紧间距，仍不够则精确适配，保证永不越过底部按钮
            mSlotGap = sp(4);
            h = (avail - (count - 1) * mSlotGap) / count;
        }
        return Math.max(sp(16), Math.min(sp(52), h));
    }

    private void selectPrevFragment() {
        int count = mEngine.getSelectedFragments().size();
        if (count > 0) {
            int idx = (mEngine.getCurrentFragmentIndex() - 1 + count) % count;
            mEngine.selectFragment(idx);
        }
    }

    private void selectNextFragment() {
        int count = mEngine.getSelectedFragments().size();
        if (count > 0) {
            int idx = (mEngine.getCurrentFragmentIndex() + 1) % count;
            mEngine.selectFragment(idx);
        }
    }

    // ---------- 选择分支 ----------

    private void handleChoiceTap(float x, float y) {
        EchoEngine.Choice choice = mEngine.getCurrentChoice();
        if (choice == null) return;
        float btnH = sp(72);
        float gap = sp(16);
        float top = choiceBtnTop();
        for (int i = 0; i < choice.options.size(); i++) {
            float btnTop = top + i * (btnH + gap);
            if (y >= btnTop && y <= btnTop + btnH) {
                mEngine.makeChoice(i);
                invalidate();
                return;
            }
        }
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        drawBackground(canvas);
        drawVignette(canvas);

        // Qt paintGL 顺序：特效层位于场景之下（频谱 → glitch → 幻觉 → 底噪）
        drawSpectrum(canvas);
        if (mGlitchIntensity > 0.01f) {
            drawGlitch(canvas);
        }
        drawHallucinations(canvas);
        drawNoiseTexts(canvas);

        switch (mEngine.getState()) {
            case TITLE:
                drawTitle(canvas, true);
                break;
            case NAME_INPUT:
                drawNameInput(canvas);
                break;
            case CHAPTER_INTRO:
                drawChapterIntro(canvas);
                break;
            case TUTORIAL:
                drawTutorial(canvas);
                break;
            case FRAGMENT_SELECT:
                drawFragmentSelect(canvas);
                break;
            case ECHO_ARCHIVE:
                drawEchoArchive(canvas);
                break;
            case TIMELINE:
                drawTimeline(canvas);
                break;
            case SCENE_COMPLETE:
                drawSceneComplete(canvas);
                break;
            case MENTOR:
                drawMentor(canvas);
                break;
            case CHOICE:
                drawChoice(canvas);
                break;
            case ENDING:
                drawEnding(canvas);
                break;
            case EPILOGUE:
                drawEpilogue(canvas);
                break;
            case GAME_OVER:
                drawGameOver(canvas);
                break;
        }

        // Qt renderReverseIndicator / renderMorseIndicator：碎片选择底部的常驻指示
        // 注意：时间轴页底部被提交按钮占用，且顶部切换区已含"倒放"状态，故仅碎片选择页绘制，避免与提交按钮重叠
        if (mEngine.getState() == EchoEngine.State.FRAGMENT_SELECT) {
            if (mEngine.isReverseUnlocked()) {
                mTextPaint.setTextSize(sp(11));
                mTextPaint.setColor(0xFFFF66C8);
                mTextPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("◄ 逆向播放已解锁", sp(16), mH - sp(14), mTextPaint);
            }
            if (mEngine.isMorseActive() && mEngine.getPendingMorse() != null) {
                mTextPaint.setTextSize(sp(11));
                mTextPaint.setColor(0xFFFFC864);
                mTextPaint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText("[摩斯] " + mEngine.getPendingMorse(), mW - sp(16), mH - sp(14), mTextPaint);
            }
            mTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        // Qt renderSilenceOverlay：静默模式遮罩
        if (mEngine.isSilenceMode()) {
            drawSilenceOverlay(canvas);
        }

        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - mLastFrame) / 1000f);
        if (now - mLastFrame > 33) {
            mLastFrame = now;
            updateFx(dt);
            updateParticles();
            invalidate();
        }
    }

    private void drawBackground(Canvas canvas) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setShader(new LinearGradient(0, 0, 0, mH,
                GameUi.BG, Color.rgb(14, 12, 22), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, mW, mH, mPaint);
        mPaint.setShader(null);

        // 浮动粒子（雪花噪点）
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(GameUi.withAlpha(GameUi.ACCENT, 0x33));
        for (float[] p : mParticles) {
            canvas.drawCircle(p[0] * mW, p[1] * mH, p[4] * 2, mPaint);
        }

        // 底部波形装饰
        drawWaveform(canvas);
    }

    private void drawWaveform(Canvas canvas) {
        Path path = new Path();
        float amp = sp(6);
        float yBase = mH - sp(14);
        float step = 8;
        path.moveTo(0, yBase);
        for (float x = 0; x <= mW; x += step) {
            float y = yBase + (float) Math.sin(x * 0.03 + mRandom.nextFloat() * 0.2) * amp
                    + (float) Math.sin(x * 0.011) * amp * 2;
            path.lineTo(x, y);
        }
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1);
        mPaint.setColor(GameUi.withAlpha(GameUi.ACCENT, 0x22));
        canvas.drawPath(path, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    private void drawVignette(Canvas canvas) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setShader(new LinearGradient(0, 0, mW, mH,
                Color.TRANSPARENT, 0x66000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, mW, mH, mPaint);
        mPaint.setShader(null);
    }

    private void updateParticles() {
        for (float[] p : mParticles) {
            p[0] += p[2] * 0.0008f;
            p[1] += p[3] * 0.0006f;
            if (p[0] > 1.1f) p[0] = -0.1f;
            if (p[1] > 1.1f) p[1] = -0.1f;
        }
    }

    /** 每帧更新视觉特效（Qt onUpdate 同步） */
    private void updateFx(float dt) {
        mGameTime += dt;
        mGlitchIntensity = Math.max(0f, mGlitchIntensity - dt * 0.3f);
        mNoiseOffset += dt * 15f;
        mBgFlicker = 0.02f * (float) Math.sin(mGameTime * 3.7f)
                + 0.01f * (float) Math.sin(mGameTime * 7.3f);

        // 显示值指数平滑趋近（Qt m_paranoiaDisplay 等）
        float tp = mEngine.getParanoia() / 100f;
        mParanoiaDisplay += (tp - mParanoiaDisplay) * dt * 3f;
        float ta = mEngine.getAnxiety() / 100f;
        mAnxietyDisplay += (ta - mAnxietyDisplay) * dt * 3f;
        float tl = mEngine.getLowFreq();
        mLowFreqDisplay += (tl - mLowFreqDisplay) * dt * 2f;

        // 频谱幅度随机游走（Qt updateSpectrum）
        for (SpectrumLine l : mSpectrumLines) {
            l.phase += dt * l.frequency * 0.1f;
            float target = mFxRandom.nextInt(100) / 200f;
            l.amplitude += (target - l.amplitude) * dt * 2f;
        }

        // glitch 粒子
        for (Iterator<GlitchParticle> it = mGlitchParticles.iterator(); it.hasNext(); ) {
            GlitchParticle p = it.next();
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.life -= dt;
            if (p.life <= 0f) it.remove();
        }

        // 时间轴扭曲时：glitch 强度升至 1、40% 概率生成红色粒子（Qt TimelinePuzzle）
        if (mEngine.isDistorted() && mEngine.getState() == EchoEngine.State.TIMELINE) {
            mGlitchIntensity = Math.min(1f, mGlitchIntensity + dt * 0.5f);
            if (mFxRandom.nextInt(100) < 40 && mGlitchParticles.size() < 24) {
                GlitchParticle p = new GlitchParticle();
                p.x = mFxRandom.nextFloat() * mW;
                p.y = mFxRandom.nextFloat() * mH;
                p.vx = (mFxRandom.nextInt(100) - 50) / 200f * mW;
                p.vy = (mFxRandom.nextInt(100) - 50) / 200f * mH;
                p.life = 0.5f + mFxRandom.nextInt(50) / 100f;
                p.maxLife = p.life;
                p.g = mFxRandom.nextInt(100);
                p.b = mFxRandom.nextInt(50);
                mGlitchParticles.add(p);
            }
        }

        // 飘动幻觉文字：移动 + 寿命衰减（Qt updateHallucinations）
        for (Iterator<DriftText> it = mHallucinations.iterator(); it.hasNext(); ) {
            DriftText h = it.next();
            h.x += h.sx * dt;
            h.y += h.sy * dt;
            h.life -= dt;
            h.alpha = Math.min(h.alpha, h.life / 3f);
            if (h.life <= 0f || h.alpha <= 0f) it.remove();
        }
        // Qt 引擎：偏执 > 0.7 时 30% 概率触发幻觉（UI 帧内近似）
        if (mParanoiaDisplay > 0.7f && mFxRandom.nextFloat() < 0.02f && mHallucinations.size() < 3) {
            String text = mEngine.randomHallucination();
            if (text != null) {
                DriftText h = new DriftText();
                h.text = text;
                h.x = mW * (0.15f + mFxRandom.nextFloat() * 0.7f);
                h.y = mH * (0.2f + mFxRandom.nextFloat() * 0.55f);
                h.sx = (mFxRandom.nextInt(20) - 10) / 200f * mW;
                h.sy = (mFxRandom.nextInt(20) - 10) / 200f * mH;
                h.life = 4f + mFxRandom.nextInt(30) / 10f;
                h.alpha = 0.8f;
                mHallucinations.add(h);
            }
        }

        // 漂浮底噪文字：寿命衰减（Qt updateNoiseTexts）
        for (Iterator<NoiseText> it = mNoiseTexts.iterator(); it.hasNext(); ) {
            NoiseText n = it.next();
            n.life -= dt;
            n.alpha = Math.min(n.alpha, n.life / 2f);
            if (n.life <= 0f || n.alpha <= 0f) it.remove();
        }
        // Qt 引擎：低噪 > 0.5 时 15% 概率触发底噪（UI 帧内近似）
        if (mLowFreqDisplay > 0.5f && mFxRandom.nextFloat() < 0.015f && mNoiseTexts.size() < 3) {
            String text = mEngine.randomNoiseDialogue();
            if (text != null) {
                NoiseText n = new NoiseText();
                n.text = text;
                n.x = mW * (0.15f + mFxRandom.nextFloat() * 0.7f);
                n.y = mH * (0.25f + mFxRandom.nextFloat() * 0.6f);
                n.life = 3f + mFxRandom.nextInt(20) / 10f;
                n.alpha = 0.7f;
                mNoiseTexts.add(n);
            }
        }
    }

    // ---------- Qt 视觉特效渲染 ----------

    /** Qt renderSpectrumVisualization：底部频谱条（幅度随偏执增长，偏执>50 叠加红色） */
    private void drawSpectrum(Canvas canvas) {
        int n = mSpectrumLines.size();
        if (n == 0) return;
        float by = mH * 0.97f;
        float mh = mH * 0.13f;
        float slotW = mW * 0.95f / n;
        float left = mW * 0.025f;
        mPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            SpectrumLine l = mSpectrumLines.get(i);
            float h = l.amplitude * mh * (0.5f + 0.5f * mParanoiaDisplay);
            mPaint.setColor(Color.argb((int) (l.amplitude * 80), 0, 180, 255));
            canvas.drawRect(left + i * slotW, by - h, left + (i + 1) * slotW - 1, by, mPaint);
        }
        if (mParanoiaDisplay > 0.5f) {
            for (int i = 0; i < n / 8; i++) {
                SpectrumLine l = mSpectrumLines.get(i);
                float h = l.amplitude * mh * mParanoiaDisplay;
                int a = (int) (mParanoiaDisplay * l.amplitude * 120);
                mPaint.setColor(Color.argb(a, 255, 40, 40));
                canvas.drawRect(left + i * slotW, by - h, left + (i + 1) * slotW - 1, by, mPaint);
            }
        }
    }

    /** Qt renderGlitchEffects：扭曲撕裂（水平红线 + 红色粒子 + 闪屏） */
    private void drawGlitch(Canvas canvas) {
        float i = mGlitchIntensity;
        // 水平红线（数量/偏移/透明度随强度）
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(sp(1f));
        int lines = (int) (5 * i);
        for (int j = 0; j < lines; j++) {
            float y = mFxRandom.nextFloat() * mH;
            float o = (mFxRandom.nextInt(20) - 10) / 100f * i * mW;
            float a = i * (mFxRandom.nextInt(100) / 100f);
            mPaint.setColor(Color.argb((int) (a * 80), 255, 0, 0));
            canvas.drawLine(-mW * 0.05f + o, y, mW * 1.05f + o, y, mPaint);
        }
        // 红色粒子
        mPaint.setStyle(Paint.Style.FILL);
        for (GlitchParticle p : mGlitchParticles) {
            float a = p.life / p.maxLife;
            mPaint.setColor(Color.argb((int) (a * 100), 255, p.g, p.b));
            canvas.drawRect(p.x, p.y, p.x + sp(4), p.y + sp(4), mPaint);
        }
        // 红色闪屏（Qt glClearColor 近似）
        if (mFxRandom.nextInt(100) < (int) (i * 30)) {
            float fa = i * 0.08f;
            mPaint.setColor(Color.argb((int) (fa * 255), 38, 5, 5));
            canvas.drawRect(0, 0, mW, mH, mPaint);
        }
    }

    /** Qt renderHallucinations：飘动幻觉文字（橙） */
    private void drawHallucinations(Canvas canvas) {
        if (mHallucinations.isEmpty()) return;
        mTextPaint.setTextSize(sp(16));
        for (DriftText h : mHallucinations) {
            int a = clampAlpha((int) (h.alpha * 150));
            mTextPaint.setColor(Color.argb(a, 255, 200, 100));
            float w = mTextPaint.measureText(h.text);
            canvas.drawText(h.text, h.x - w / 2, h.y, mTextPaint);
        }
    }

    /** Qt renderNoiseDialogues：漂浮底噪文字（绿） */
    private void drawNoiseTexts(Canvas canvas) {
        if (mNoiseTexts.isEmpty()) return;
        mTextPaint.setTextSize(sp(14));
        for (NoiseText n : mNoiseTexts) {
            int a = clampAlpha((int) (n.alpha * 120));
            mTextPaint.setColor(Color.argb(a, 100, 255, 200));
            float w = mTextPaint.measureText(n.text);
            canvas.drawText(n.text, n.x - w / 2, n.y, mTextPaint);
        }
    }

    private static int clampAlpha(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ---------- 标题 ----------

    private void drawTitle(Canvas canvas, boolean showStartHint) {
        // 标题
        mTitlePaint.setTextSize(sp(40));
        mTitlePaint.setColor(GameUi.ACCENT);
        canvas.drawText("余 音 回 响", mW / 2, mH * 0.36f, mTitlePaint);

        mTextPaint.setTextSize(sp(16));
        mTextPaint.setColor(0xFF26C6DA);
        canvas.drawText("ECHO RESONANCE", mW / 2, mH * 0.36f + sp(40), mTextPaint);

        mTextPaint.setTextSize(sp(13));
        mTextPaint.setColor(0xFFB8B8C8);
        canvas.drawText("一段声音，一段被篡改的记忆", mW / 2, mH * 0.36f + sp(72), mTextPaint);

        // 闪动提示（GameOver 复用时隐藏，避免与"重新开始"重叠）
        if (showStartHint) {
            long t = System.currentTimeMillis() % 1200;
            int alpha = t < 800 ? (int) (255 * (t / 800f)) : 255;
            mHintPaint.setAlpha(alpha);
            mHintPaint.setTextSize(sp(14));
            mHintPaint.setColor(GameUi.TEXT);
            canvas.drawText("· 触 屏 开 始 ·", mW / 2, mH * 0.72f, mHintPaint);
            mHintPaint.setAlpha(255);
        }
    }

    // ---------- 名字输入（Qt：NameInput） ----------

    private void drawNameInput(Canvas canvas) {
        // 标题
        mTitlePaint.setTextSize(sp(28));
        mTitlePaint.setColor(GameUi.ACCENT);
        canvas.drawText("请输入你的名字", mW / 2, mH * 0.3f, mTitlePaint);

        mTextPaint.setTextSize(sp(13));
        mTextPaint.setColor(0xFFB8B8C8);
        canvas.drawText("导师日志将以这个名字称呼你", mW / 2, mH * 0.3f + sp(34), mTextPaint);

        // 输入框
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setColor(GameUi.ACCENT);
        mPaint.setStrokeWidth(sp(1.5f));
        float boxW = Math.min(mW - sp(80), sp(360));
        float boxH = sp(58);
        float boxLeft = mW / 2 - boxW / 2;
        float boxTop = mH * 0.42f;
        mSlotRect.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH);
        canvas.drawRect(mSlotRect, mPaint);

        // 当前名字 + 光标闪烁
        String name = mEngine.playerName();
        mTextPaint.setTextSize(sp(22));
        mTextPaint.setColor(GameUi.TEXT);
        float nameW = mTextPaint.measureText(name);
        float textX = mSlotRect.centerX() - nameW / 2;
        canvas.drawText(name, textX, mSlotRect.centerY() + sp(8), mTextPaint);
        long t = System.currentTimeMillis() % 1000;
        if (t < 600) {
            canvas.drawRect(textX + nameW + sp(4), boxTop + sp(14),
                    textX + nameW + sp(4) + sp(2), boxTop + boxH - sp(14), mTextPaint);
        }

        // 提示
        mHintPaint.setTextSize(sp(14));
        mHintPaint.setColor(GameUi.TEXT);
        canvas.drawText("—— 触 屏 输 入 名 字 ——", mW / 2, mH * 0.72f, mHintPaint);
    }

    /** Qt：NameInput 键盘输入 → 移动端系统输入对话框 */
    private void showNameInputDialog() {
        Context ctx = getContext();
        EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setHint("输入名字（留空默认'小周'）");
        input.setText(mEngine.playerName());
        new AlertDialog.Builder(ctx)
                .setTitle("请输入你的名字")
                .setView(input)
                .setPositiveButton("确认", (d, w) -> {
                    String name = input.getText().toString().trim();
                    mEngine.setPlayerName(name);
                    mEngine.startGame();
                    invalidate();
                })
                .setNegativeButton("取消", (d, w) -> {
                    // 留空 → 默认"小周"直接进入
                    mEngine.setPlayerName("");
                    mEngine.startGame();
                    invalidate();
                })
                .setOnCancelListener(d -> {
                    mEngine.setPlayerName("");
                    mEngine.startGame();
                    invalidate();
                })
                .show();
    }

    // ---------- 教程页（Qt：序章专属） ----------

    private void drawTutorial(Canvas canvas) {
        drawTopBar(canvas, "序章：入职第7天 — 教程", true);

        // 内容区可滚动，底部提示固定，避免内容溢出时重叠
        beginContentScroll(canvas, mH * 0.2f);
        float y = mH * 0.24f;
        y = drawWrappedParagraph(canvas, "目标：还原昨天下午茶水间的对话", mW / 2, y,
                mW - sp(48), sp(17), 0xFF96C8DC);
        y += sp(14);
        y = drawWrappedParagraph(canvas, "操作：点击左侧碎片 → 拖放到时间轴槽位 → 按 TAB 切换到时间轴", mW / 2, y,
                mW - sp(48), sp(14), 0xFF78A0C8);
        y += sp(10);
        y = drawWrappedParagraph(canvas, "蓝色碎片=真实残留 | 青色=可疑 | 黄色=噪声 | 红色=伪造", mW / 2, y,
                mW - sp(48), sp(13), 0xFF648CB4);
        y += sp(10);

        // 锚点音提示（Qt）
        y += sp(8);
        y = drawWrappedParagraph(canvas, "锚点音：门禁刷卡声（必须放在第1个槽位）", mW / 2, y,
                mW - sp(48), sp(14), 0xFFFFC800);
        y += sp(10);
        mContentEnd = y + sp(20);
        endContentScroll(canvas);

        // 底部操作提示（Qt："按 TAB 开始拼接" → 触屏版）
        mHintPaint.setTextSize(sp(13));
        mHintPaint.setColor(0xFF8A8A9A);
        canvas.drawText("—— 触碰开始拼接 ——", mW / 2, mH - sp(56), mHintPaint);
    }

    // ---------- 二周目（Qt：结局后 ENTER 返回标题画面） ----------

    private void drawGameOver(Canvas canvas) {
        // Qt：GameOver 复用标题画面（隐藏"触屏开始"提示，避免与"重新开始"重叠）
        drawTitle(canvas, false);

        mTextPaint.setTextSize(sp(13));
        mTextPaint.setColor(0xFF9A9AAC);
        canvas.drawText("结局已达成，已解锁二周目", mW / 2, mH * 0.62f, mTextPaint);
        mHintPaint.setTextSize(sp(13));
        mHintPaint.setColor(GameUi.TEXT);
        canvas.drawText("—— 触屏重新开始 ——", mW / 2, mH * 0.72f, mHintPaint);
    }

    // ---------- 静默遮罩（Qt：偏执高 + 低噪高时覆盖） ----------

    private void drawSilenceOverlay(Canvas canvas) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xB3000000);
        canvas.drawRect(0, 0, mW, mH, mPaint);

        long t = System.currentTimeMillis() % 1200;
        int alpha = t < 600 ? (int) (255 * (0.45f + 0.25f * (t / 600f)))
                : (int) (255 * (0.45f + 0.25f * (1 - (t - 600) / 600f)));
        mTextPaint.setTextSize(sp(24));
        mTextPaint.setColor(0x99000000 | (alpha << 24));
        canvas.drawText("静默中……", mW / 2, mH / 2, mTextPaint);
    }

    // ---------- 章节引言 ----------

    private void drawChapterIntro(Canvas canvas) {
        drawTopBar(canvas, mEngine.getChapter().title, false);

        // 内容区可滚动，底部提示固定，避免长描述溢出时重叠
        beginContentScroll(canvas, mH * 0.22f);
        float y = mH * 0.26f;
        for (String line : mEngine.getChapter().description.split("\n")) {
            y = drawWrappedParagraph(canvas, line, mW / 2, y, mW - sp(48), sp(16), 0xFFD8D8E8);
            y += sp(12);
        }
        mContentEnd = y + sp(20);
        endContentScroll(canvas);

        mHintPaint.setTextSize(sp(13));
        mHintPaint.setColor(0xFF8A8A9A);
        canvas.drawText("—— 触屏继续 ——", mW / 2, mH - sp(56), mHintPaint);
    }

    // ---------- 碎片选择 ----------

    private void drawFragmentSelect(Canvas canvas) {
        drawTopBar(canvas, mEngine.getChapter().title, true);
        clampSelectScroll();

        mTextPaint.setTextSize(sp(14));
        mTextPaint.setColor(0xFFB8B8C8);
        // 提示行排在仪表条（mHeaderContentY）之下，避免与“偏执/焦虑”标签/标题重叠
        canvas.drawText("选择一条声音碎片，触碰进入时间轴拼图", mW / 2, mHeaderContentY, mTextPaint);

        List<EchoEngine.Fragment> pool = mEngine.getChapter().pool;
        int cols = selectCols();
        float cardW = mW / cols;
        float cardH = selectCardH();

        for (int i = 0; i < pool.size(); i++) {
            EchoEngine.Fragment frag = pool.get(i);
            int row = i / cols;
            int col = i % cols;
            float left = col * cardW + sp(8);
            float top = mBaseY + row * cardH + sp(8) - mSelectScroll;
            float right = left + cardW - sp(16);
            float bottom = top + cardH - sp(8);
            mCardRect.set(left, top, right, bottom);
            drawFragmentCard(canvas, mCardRect, frag, i == mEngine.getCurrentFragmentIndex());
        }

        // 碎片计数（Qt renderFragmentSelect 底部；卡片滚动区止于上方，计数行与操作条分层不重叠）
        int tf = pool.size();
        int placed = mEngine.placedFragmentCount();
        mHintPaint.setTextSize(sp(12));
        mHintPaint.setColor(0xFF7878A0);
        canvas.drawText("可用碎片: " + (tf - placed) + " / " + tf + " (已放置: " + placed + ")"
                        + (mSelectScrollMax > 0 ? " · 上下滑动查看更多" : ""),
                mW / 2, mH - sp(84), mHintPaint);

        // 底部操作条：左 = 回声档案（Qt 按 E），右 = 进入拼图（Qt TAB）；逆向/摩斯指示固定在其下方一行
        float barTop = mH - sp(68);
        float barBottom = mH - sp(24);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0x14000000);
        mCardRect.set(0, barTop, mW / 2f, barBottom);
        canvas.drawRect(mCardRect, mPaint);
        mCardRect.set(mW / 2f, barTop, mW, barBottom);
        canvas.drawRect(mCardRect, mPaint);
        mPaint.setColor(0xFF2A2A44);
        mPaint.setStrokeWidth(sp(1));
        canvas.drawLine(mW / 2f, barTop, mW / 2f, barBottom, mPaint);
        mTextPaint.setTextSize(sp(14));
        mTextPaint.setColor(0xFF26C6DA);
        canvas.drawText("回声档案(" + mEngine.unlockedEchoFragmentCount() + "/6)",
                mW / 4f, (barTop + barBottom) / 2 + sp(5), mTextPaint);
        mTextPaint.setColor(GameUi.ACCENT);
        canvas.drawText("进入拼图", mW * 0.75f, (barTop + barBottom) / 2 + sp(5), mTextPaint);
    }

    // ---------- 回声碎片档案（Qt：EchoArchiveScreen） ----------

    private void drawEchoArchive(Canvas canvas) {
        drawTopBar(canvas, "回声碎片档案 · 被遗忘者的回声", true);

        // 内容区可滚动，底部提示固定
        beginContentScroll(canvas, mH * 0.2f);
        float y = mH * 0.24f;
        y = drawWrappedParagraph(canvas, "已解锁 " + mEngine.unlockedEchoFragmentCount() + " / 6",
                mW / 2, y, mW - sp(40), sp(15), 0xFF26C6DA);
        y += sp(14);

        int seq = 0;
        for (EchoEngine.EchoFragmentRecord rec : mEngine.echoFragments()) {
            seq++;
            if (!rec.unlocked) {
                y = drawWrappedParagraph(canvas, "第" + seq + "份回声 · ???（未解锁）", mW / 2, y,
                        mW - sp(40), sp(14), 0xFF6A6A80);
                y += sp(4);
                y = drawWrappedParagraph(canvas, "完成对应章节的拼图后，这里的录音会被唤醒。", mW / 2, y,
                        mW - sp(40), sp(12), 0xFF484860);
                y += sp(18);
            } else {
                y = drawWrappedParagraph(canvas, rec.title, mW / 2, y,
                        mW - sp(40), sp(16), 0xFFFFD54F);
                y = drawWrappedParagraph(canvas, rec.location, mW / 2, y + sp(2),
                        mW - sp(40), sp(12), 0xFFB388FF);
                y += sp(6);
                y = drawWrappedParagraph(canvas, rec.content, mW / 2, y,
                        mW - sp(40), sp(14), 0xFFD0D0E0);
                y += sp(20);
            }
        }
        mContentEnd = y + sp(24);
        endContentScroll(canvas);

        mHintPaint.setTextSize(sp(13));
        mHintPaint.setColor(0xFF8A8A9A);
        canvas.drawText("—— 触碰返回碎片选择 · 上下滑动浏览 ——", mW / 2, mH - sp(56), mHintPaint);
    }

    private void drawFragmentCard(Canvas canvas, RectF rect, EchoEngine.Fragment frag, boolean selected) {
        // 卡片背景
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xFF14141E);
        canvas.drawRoundRect(rect, sp(10), sp(10), mPaint);

        // 边框（可信度颜色）
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(selected ? sp(3) : sp(1.5f));
        mPaint.setColor(frag.cred.color);
        canvas.drawRoundRect(rect, sp(10), sp(10), mPaint);

        // 可信度标签
        mTextPaint.setTextSize(sp(11));
        mTextPaint.setColor(frag.cred.color);
        canvas.drawText(frag.cred.label, rect.centerX(), rect.top + sp(22), mTextPaint);

        // 碎片名（锚点 / 倒放标记；窄卡截断防溢出）
        mTextPaint.setTextSize(sp(15));
        mTextPaint.setColor(GameUi.TEXT);
        String name = TextUtils.ellipsize(
                (frag.anchor ? "✦ " : "") + (frag.reversed ? "⏪ " : "") + frag.name,
                mTextPaint, rect.width() - sp(24), TextUtils.TruncateAt.END).toString();
        canvas.drawText(name, rect.centerX(), rect.top + sp(52), mTextPaint);

        // 锚点标记 / 何悦"照妖镜"：救出何悦后伪造碎片被干净声纹照出（Qt isCredibilityExposed）
        if (frag.anchor) {
            mTextPaint.setTextSize(sp(11));
            mTextPaint.setColor(0xFFFFD54F);
            canvas.drawText("锚点音", rect.centerX(), rect.top + sp(76), mTextPaint);
        } else if (mEngine.isCredibilityExposed(frag)) {
            mTextPaint.setTextSize(sp(11));
            mTextPaint.setColor(0xFFFF3C3C);
            canvas.drawText("[被照出]", rect.centerX(), rect.top + sp(76), mTextPaint);
        }

        // 描述（行数随卡片高度自适应，防止长描述溢出卡片）
        int descLines = Math.max(1, (int) ((rect.height() - sp(96)) / (sp(11) * 1.25f)));
        drawWrappedText(canvas, frag.desc, rect.centerX(), rect.top + sp(92),
                rect.width() - sp(24), sp(11), 0xFF9A9AAC, descLines);
    }

    // ---------- 时间轴拼图 ----------

    private void drawTimeline(Canvas canvas) {
        drawTopBar(canvas, mEngine.getChapter().title, true);

        // Qt：实时一致性显示 + 声纹冲突扭曲警告（L278-282）
        float coh = mEngine.getCoherence() / 100f;
        String cs = "场景一致性: " + mEngine.getCoherence() + "%"
                + (mEngine.isRightEarSilenced() ? " · 右耳被静音" : "");
        int cc = coh > 0.7f ? 0xFF00C864 : (coh > 0.4f ? 0xFFC8C800 : 0xFFFF3C3C);

        // 顶部信息区：统一排在仪表条（mHeaderContentY）之下，避免“偏执/焦虑”标签与场景一致性重叠
        float infoY0 = mHeaderContentY;
        float lineGap = sp(19);
        boolean distorted = mEngine.isDistorted();
        float promptY = distorted ? infoY0 + 2 * lineGap : infoY0 + lineGap;
        mBaseY = Math.max(mBaseY, promptY + sp(30)); // 为切换区/放置提示/槽位留出空间

        mTextPaint.setTextSize(sp(13));
        mTextPaint.setColor(cc);
        canvas.drawText(cs, mW * 0.2f, infoY0, mTextPaint);
        if (distorted) {
            float blink = 0.5f + 0.3f * (float) Math.sin(mGameTime * 6.0);
            int a = (int) (255 * blink);
            mTextPaint.setColor(0xFFFF5030 & 0x00FFFFFF | (a << 24));
            canvas.drawText("⚠ 声纹冲突 - 场景已扭曲", mW * 0.2f, infoY0 + lineGap, mTextPaint);
        }

        // 当前碎片提示 + 切换区域（左 上一条 / 中 倒放 / 右 下一条）
        EchoEngine.Fragment cur = mEngine.getCurrentFragment();
        if (cur != null) {
            String curName = (cur.anchor ? "✦ " : "") + (cur.reversed ? "⏪ " : "") + cur.name;
            mTextPaint.setTextSize(sp(13));
            mTextPaint.setColor(cur.cred.color);
            String prompt = TextUtils.ellipsize("当前碎片：[" + curName + "]",
                    mTextPaint, mW - sp(40), TextUtils.TruncateAt.END).toString();
            canvas.drawText(prompt, mW / 2, promptY, mTextPaint);
            mTextPaint.setTextSize(sp(11));
            mTextPaint.setColor(0xFF9A9AAC);
            canvas.drawText("◀ 上一碎片", mW * 0.15f, mBaseY + sp(4), mTextPaint);
            canvas.drawText("下一碎片 ▶", mW * 0.85f, mBaseY + sp(4), mTextPaint);
            if (mEngine.isReverseUnlocked()) {
                mTextPaint.setColor(cur.reversed ? 0xFFB388FF : GameUi.ACCENT);
                canvas.drawText(cur.reversed ? "⏪ 恢复正放" : "倒放 ↻", mW * 0.5f, mBaseY + sp(4), mTextPaint);
            }
            mTextPaint.setTextSize(sp(11));
            mTextPaint.setColor(0xFF8A8A9C);
            canvas.drawText("触碰槽位放置 · 触碰已放置槽位移除",
                    mW / 2, mBaseY + sp(20), mTextPaint);
        }

        List<EchoEngine.Slot> slots = mEngine.getChapter().slots;
        float startY = mBaseY + sp(40);
        float slotH = dynamicSlotH();
        float left = sp(16);
        float right = mW - sp(16);

        for (int i = 0; i < slots.size(); i++) {
            EchoEngine.Slot slot = slots.get(i);
            float top = startY + i * (slotH + mSlotGap);
            float bottom = top + slotH;
            mSlotRect.set(left, top, right, bottom);

            // 槽位背景
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(slot.isFilled() ? 0xFF1A2230 : 0xFF12121C);
            canvas.drawRoundRect(mSlotRect, sp(8), sp(8), mPaint);

            // 槽位边框
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(sp(1.5f));
            mPaint.setColor(slot.anchorSlot ? 0xFFFFD54F : 0xFF3A3A4C);
            canvas.drawRoundRect(mSlotRect, sp(8), sp(8), mPaint);

            // 槽位过矮（紧凑模式，横屏矮屏/槽位多）时只显示单行，避免文字溢出到相邻槽位或按钮
            boolean compact = slotH < sp(40);
            if (slot.isFilled()) {
                EchoEngine.Fragment placed = findFragmentById(Integer.parseInt(slot.placedId));
                if (placed != null) {
                    String name = (placed.anchor ? "✦ " : "") + placed.name
                            + (placed.reversed ? " ⏪" : "");
                    mTextPaint.setTextSize(compact ? sp(12) : sp(14));
                    mTextPaint.setColor(placed.cred.color);
                    String shown = TextUtils.ellipsize(name, mTextPaint,
                            mSlotRect.width() - sp(16), TextUtils.TruncateAt.END).toString();
                    canvas.drawText(shown, mSlotRect.centerX(),
                            compact ? mSlotRect.centerY() : mSlotRect.centerY() - sp(6), mTextPaint);
                    if (!compact) {
                        mTextPaint.setTextSize(sp(10));
                        mTextPaint.setColor(0xFF9A9AAC);
                        canvas.drawText("触碰移除", mSlotRect.centerX(), mSlotRect.centerY() + sp(12), mTextPaint);
                    }
                }
            } else {
                mTextPaint.setTextSize(compact ? sp(10) : sp(12));
                mTextPaint.setColor(0xFF9A9AAC);
                String hint = TextUtils.ellipsize((slot.anchorSlot ? "⛨ " : "") + slot.hint,
                        mTextPaint, mSlotRect.width() - sp(16), TextUtils.TruncateAt.END).toString();
                canvas.drawText(hint, mSlotRect.centerX(),
                        compact ? mSlotRect.centerY() : mSlotRect.centerY() + sp(4), mTextPaint);
            }
        }

        // 底部确认按钮
        float btnTop = mH - sp(64);
        mSlotRect.set(sp(16), btnTop, mW - sp(16), btnTop + sp(46));
        boolean complete = mEngine.isPuzzleComplete();
        boolean anchorOk = mEngine.isAnchorCorrect();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(complete ? (anchorOk ? 0xFF1F4F3A : 0xFF4F3A1F) : 0xFF1A1A28);
        canvas.drawRoundRect(mSlotRect, sp(23), sp(23), mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(sp(1));
        mPaint.setColor(complete ? (anchorOk ? 0xFF2E7D57 : 0xFF7D572E) : 0xFF3A3A4C);
        canvas.drawRoundRect(mSlotRect, sp(23), sp(23), mPaint);
        mTextPaint.setTextSize(sp(15));
        mTextPaint.setColor(complete ? (anchorOk ? 0xFF4CAF50 : 0xFFFFB74D) : 0xFF9A9AAC);
        canvas.drawText(complete ? (anchorOk ? "▶ 提交场景" : "⚠ 锚点错误，仍要提交？") : "填满所有槽位后提交",
                mSlotRect.centerX(), mSlotRect.centerY() + sp(5), mTextPaint);
    }

    private EchoEngine.Fragment findFragmentById(int id) {
        for (EchoEngine.Fragment f : mEngine.getChapter().pool) {
            if (f.id == id) return f;
        }
        return null;
    }

    // ---------- 场景完成 ----------

    private void drawSceneComplete(Canvas canvas) {
        boolean distorted = mEngine.isDistorted();

        // Qt：扭曲版本背景染红
        if (distorted) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0x1F7A1F1F);
            canvas.drawRect(0, 0, mW, mH, mPaint);
        }

        // Qt 标题：扭曲 / 正常
        mTitlePaint.setTextSize(sp(26));
        mTitlePaint.setColor(distorted ? 0xFFFF5028 : GameUi.ACCENT);
        canvas.drawText(distorted ? "场景重建 - 扭曲版本" : "场景重建完成",
                mW / 2, mH * 0.14f, mTitlePaint);

        // Qt：声纹一致性
        int coherence = mEngine.getCoherence();
        mTextPaint.setTextSize(sp(17));
        mTextPaint.setColor(distorted ? 0xFFFF7840 : 0xFF4CAF50);
        canvas.drawText("声纹一致性: " + coherence + "%", mW / 2, mH * 0.2f, mTextPaint);
        drawMeter(canvas, mW * 0.2f, mH * 0.23f, mW * 0.6f, sp(8), coherence,
                distorted ? 0xFFFF7840 : 0xFF4CAF50);

        // 内容区可滚动，底部提示固定，避免长内容（锚点错误/描述/引用/秘密/低噪/幻觉/警告）溢出重叠
        beginContentScroll(canvas, mH * 0.26f);
        float y = mH * 0.29f;

        // 锚点错误警告（保留）
        if (!mEngine.isAnchorCorrect()) {
            y = drawWrappedParagraph(canvas, "⚠ 锚点槽错误：缺少「"
                    + anchorName(mEngine.getChapter().anchor) + "」", mW / 2, y,
                    mW - sp(48), sp(14), 0xFFFFB74D);
            y += sp(10);
        }

        // Qt：场景描述（扭曲 / 正常固定文本）
        String desc = distorted
                ? "你使用了冲突的声纹碎片强行拼接。\n现实出现了裂痕——但裂痕中，有时会透出真相。"
                : "音频场景完整重现。但请记住导师的警告：别相信你听到的任何声音。";
        for (String line : desc.split("\n")) {
            y = drawWrappedParagraph(canvas, line, mW / 2, y, mW - sp(48), sp(15), GameUi.TEXT);
            y += sp(10);
        }

        // Qt：导师引用（扭曲或偏执 > 0.5 时显示）
        if (distorted || mEngine.getParanoia() > 50) {
            y += sp(6);
            y = drawWrappedParagraph(canvas, "\"" + mEngine.getMentorMessage(
                    Math.min(mEngine.getChapterIndex(), 8)) + "\"",
                    mW / 2, y, mW - sp(48), sp(14), 0xFFFFC864);
            y += sp(6);
        }

        // 倒放秘密
        for (EchoEngine.Slot slot : mEngine.getChapter().slots) {
            if (!slot.isFilled()) continue;
            EchoEngine.Fragment f = findFragmentById(Integer.parseInt(slot.placedId));
            if (f != null && f.reversed && f.revealsSecret && f.reverseDesc != null) {
                y += sp(4);
                y = drawWrappedParagraph(canvas, "⏪ " + f.reverseDesc, mW / 2, y,
                        mW - sp(48), sp(13), 0xFFB388FF);
                y += sp(4);
            }
        }

        // 底噪对话 / 静默模式
        if (mEngine.isSilenceMode()) {
            y += sp(4);
            y = drawWrappedParagraph(canvas, "静默模式：低频淹没一切，你再也听不到谎言。",
                    mW / 2, y, mW - sp(48), sp(14), 0xFF26C6DA);
            y += sp(4);
        } else if (mEngine.getPendingNoise() != null) {
            y += sp(4);
            y = drawWrappedParagraph(canvas, "低噪 · " + mEngine.getPendingNoise(),
                    mW / 2, y, mW - sp(48), sp(14), 0xFF26C6DA);
            y += sp(4);
        }

        // 幻觉
        if (mEngine.getPendingHallucination() != null) {
            y += sp(4);
            y = drawWrappedParagraph(canvas, "幻听 · " + mEngine.getPendingHallucination(),
                    mW / 2, y, mW - sp(48), sp(14), 0xFFEF5350);
            y += sp(4);
        }

        // 低频警告
        String warning = mEngine.getLowFreqWarning();
        if (warning != null) {
            y += sp(4);
            y = drawWrappedParagraph(canvas, warning, mW / 2, y,
                    mW - sp(48), sp(13), 0xFFFFD54F);
            y += sp(4);
        }

        // Qt：声灵回响（释放 / 反噬文案，仅本局发生过时展示；位于滚动内容区不会与底栏重叠）
        if (!mEngine.spiritLog().isEmpty()) {
            y += sp(6);
            y = drawWrappedParagraph(canvas, "—— 声灵回响 ——", mW / 2, y,
                    mW - sp(48), sp(13), 0xFFFF8A65);
            y += sp(2);
            for (String spiritLine : mEngine.spiritLog()) {
                y = drawWrappedParagraph(canvas, spiritLine, mW / 2, y + sp(2),
                        mW - sp(48), sp(14), 0xFFE0B0A0);
                y += sp(6);
            }
        }

        // Qt：扭曲版本提示
        if (distorted) {
            y += sp(6);
            y = drawWrappedParagraph(canvas, "提示：扭曲版本可能揭示了隐藏线索", mW / 2, y,
                    mW - sp(48), sp(13), 0xFFFF5028);
            y += sp(6);
        }

        mContentEnd = y + sp(20);
        endContentScroll(canvas);

        mHintPaint.setTextSize(sp(13));
        mHintPaint.setColor(0xFF8A8A9A);
        canvas.drawText("—— 触屏继续 ——", mW / 2, mH - sp(56), mHintPaint);
    }

    // ---------- 导师信息 ----------

    private void drawMentor(Canvas canvas) {
        // Qt：导师的语音日志 #N（N 即当前章节序号）
        drawTopBar(canvas, "导师的语音日志 #" + (mEngine.getChapterIndex() + 1), true);

        // 导师头像（简单波形圆）；矮屏下空间不足（会与偏执/焦虑仪表重叠、被滚动区裁剪）时自动省略
        float cx = mW / 2, cy = mH * 0.18f;
        float metersBottom = mH * 0.095f + sp(13);
        boolean avatarFits = cy - sp(36) >= metersBottom && cy + sp(36) <= mH * 0.24f;
        if (avatarFits) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0xFF1A1A2E);
            canvas.drawCircle(cx, cy, sp(36), mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(sp(2));
            mPaint.setColor(0xFF26C6DA);
            canvas.drawCircle(cx, cy, sp(36), mPaint);
            mTextPaint.setTextSize(sp(22));
            mTextPaint.setColor(0xFF26C6DA);
            canvas.drawText("♫", cx, cy + sp(8), mTextPaint);
        }

        // 内容区可滚动，底部提示固定，避免长日志 + 摩斯/低噪/幻觉叠加时重叠
        beginContentScroll(canvas, mH * 0.24f);
        float y = mH * 0.28f;
        String msg = mEngine.getMentorMessage(mEngine.getChapterIndex());
        if (msg == null || msg.isEmpty()) {
            msg = mEngine.chapterMentor();
        }
        for (String line : msg.split("\n")) {
            y = drawWrappedParagraph(canvas, line, mW / 2, y, mW - sp(48), sp(16), 0xFFD8D8E8);
            y += sp(12);
        }

        // 摩斯电码（老刘）
        if (mEngine.isMorseActive() && mEngine.getPendingMorse() != null) {
            y += sp(4);
            y = drawWrappedParagraph(canvas, "摩斯电码 · " + mEngine.getPendingMorse(),
                    mW / 2, y, mW - sp(48), sp(13), 0xFF9CCC65);
            y += sp(4);
        }

        // 底噪对话 / 静默模式
        if (mEngine.isSilenceMode()) {
            y += sp(4);
            y = drawWrappedParagraph(canvas, "静默模式：低频淹没一切，你再也听不到谎言。",
                    mW / 2, y, mW - sp(48), sp(14), 0xFF26C6DA);
            y += sp(4);
        } else if (mEngine.getPendingNoise() != null) {
            y += sp(4);
            y = drawWrappedParagraph(canvas, "低噪 · " + mEngine.getPendingNoise(),
                    mW / 2, y, mW - sp(48), sp(14), 0xFF26C6DA);
            y += sp(4);
        }

        // 幻觉
        if (mEngine.getPendingHallucination() != null) {
            y += sp(4);
            y = drawWrappedParagraph(canvas, "幻听 · " + mEngine.getPendingHallucination(),
                    mW / 2, y, mW - sp(48), sp(14), 0xFFEF5350);
            y += sp(4);
        }

        mContentEnd = y + sp(20);
        endContentScroll(canvas);

        mHintPaint.setTextSize(sp(13));
        mHintPaint.setColor(0xFF8A8A9A);
        canvas.drawText("—— 触屏继续 ——", mW / 2, mH - sp(56), mHintPaint);
    }

    // ---------- 选择分支 ----------

    private void drawChoice(Canvas canvas) {
        drawTopBar(canvas, "抉择", true);

        EchoEngine.Choice choice = mEngine.getCurrentChoice();
        if (choice == null) return;

        // Qt：选择页仅显示对白 prompt（自动折行）
        float y = mBaseY;
        for (String line : choice.prompt.split("\n")) {
            y = drawWrappedParagraph(canvas, line, mW / 2, y, mW - sp(40), sp(15), GameUi.TEXT);
            y += sp(12);
        }

        float btnW = mW - sp(40);
        float btnH = sp(72);
        float gap = sp(16);
        float top = choiceBtnTop();
        int[] colors = {GameUi.ACCENT, 0xFFB388FF, 0xFFFFD54F};
        for (int i = 0; i < choice.options.size(); i++) {
            drawChoiceButton(canvas, mW / 2, top + i * (btnH + gap), btnW, btnH,
                    choice.options.get(i), colors[i % colors.length]);
        }
    }

    /** 计算抉择按钮的顶部 y 坐标（与绘制使用同一套折行估算，保证点击判定一致） */
    private float choiceBtnTop() {
        float y = mBaseY;
        EchoEngine.Choice choice = mEngine.getCurrentChoice();
        if (choice == null) return y;
        for (String line : choice.prompt.split("\n")) {
            y += estimateLines(line, mW - sp(40), sp(15)) * sp(15) * 1.25f + sp(12);
        }
        return y + sp(24);
    }

    private void drawChoiceButton(Canvas canvas, float cx, float top, float w, float h, String text, int color) {
        mSlotRect.set(cx - w / 2, top, cx + w / 2, top + h);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xFF16161F);
        canvas.drawRoundRect(mSlotRect, sp(12), sp(12), mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(sp(1.5f));
        mPaint.setColor(color);
        canvas.drawRoundRect(mSlotRect, sp(12), sp(12), mPaint);
        // 长选项自动折行（最多 2 行，垂直居中），避免溢出按钮
        mTextPaint.setTextSize(sp(15));
        mTextPaint.setColor(GameUi.TEXT);
        StaticLayout layout = buildLayout(text, w - sp(24));
        int lines = Math.min(layout.getLineCount(), 2);
        float lineHeight = sp(15) * 1.25f;
        float y0 = mSlotRect.centerY() - (lines * lineHeight) / 2 + lineHeight * 0.72f;
        for (int i = 0; i < lines; i++) {
            canvas.drawText(text.substring(layout.getLineStart(i), layout.getLineEnd(i)),
                    cx, y0 + i * lineHeight, mTextPaint);
        }
    }

    // ---------- 结局 ----------

    private void drawEnding(Canvas canvas) {
        EchoEngine.Ending ending = mEngine.computeEnding();
        // Qt：结局解锁记录
        mEngine.markEndingUnlocked(ending.title);

        // 背景微染结局色
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(ending.color & 0x12FFFFFF);
        canvas.drawRect(0, 0, mW, mH, mPaint);

        mTitlePaint.setTextSize(sp(26));
        mTitlePaint.setColor(ending.color);
        canvas.drawText("结局 · " + ending.title, mW / 2, mH * 0.16f, mTitlePaint);

        // Qt：已解锁结局计数
        mTextPaint.setTextSize(sp(12));
        mTextPaint.setColor(0xFF9090A0);
        canvas.drawText("已解锁结局 " + mEngine.unlockedEndingCount() + " / " + mEngine.getEndingCount(),
                mW / 2, mH * 0.16f + sp(24), mTextPaint);

        // 内容区可滚动，统计与提示固定，避免长结局文本溢出重叠；
        // 起点避开上方“已解锁结局”计数行（矮屏下 0.16mH+sp(24) 会与 0.24mH 首行重叠）
        beginContentScroll(canvas, mH * 0.22f);
        float y = mH * 0.27f;
        for (String line : ending.text.split("\n")) {
            y = drawWrappedParagraph(canvas, line, mW / 2, y, mW - sp(48), sp(15), GameUi.TEXT);
            y += sp(8);
        }

        // 达成条件
        y += sp(6);
        y = drawWrappedParagraph(canvas, "达成条件：", mW / 2, y,
                mW - sp(48), sp(13), 0xFFB0B0C0);
        y = drawWrappedParagraph(canvas, ending.condition, mW / 2, y + sp(2),
                mW - sp(48), sp(13), ending.color);

        // Qt：被遗忘者名单（结局屏，已记住的他们）
        if (mEngine.unlockedEchoFragmentCount() > 0) {
            y += sp(12);
            for (String line : mEngine.forgottenListText().split("\n")) {
                y = drawWrappedParagraph(canvas, line, mW / 2, y,
                        mW - sp(48), sp(13), 0xFF9AB0C0);
                y += sp(4);
            }
        }
        mContentEnd = y + sp(20);
        endContentScroll(canvas);

        // 数值统计
        mTextPaint.setTextSize(sp(13));
        mTextPaint.setColor(0xFFA0A0B0);
        canvas.drawText("偏执 " + mEngine.getParanoia() + " · 焦虑 " + mEngine.getAnxiety()
                + " · 低噪 " + (int) (mEngine.getLowFreq() * 100)
                + (mEngine.isHardMode() ? " · 困难模式" : ""),
                mW / 2, mH - sp(84), mTextPaint);

        mHintPaint.setTextSize(sp(13));
        mHintPaint.setColor(0xFF8A8A9A);
        canvas.drawText(mEngine.currentEndingHasEpilogue()
                        ? "—— 触屏聆听余波录音 ——" : "—— 触屏进入二周目 ——",
                mW / 2, mH - sp(56), mHintPaint);
    }

    // ---------- 结局余波（Qt：EpilogueScreen） ----------

    private void drawEpilogue(Canvas canvas) {
        EchoEngine.Ending ending = mEngine.computeEnding();
        String epilogue = mEngine.currentEndingEpilogue();

        // 背景微染结局色
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(ending.color & 0x12FFFFFF);
        canvas.drawRect(0, 0, mW, mH, mPaint);

        mTitlePaint.setTextSize(sp(24));
        mTitlePaint.setColor(ending.color);
        canvas.drawText("余波 · " + ending.title, mW / 2, mH * 0.15f, mTitlePaint);

        mTextPaint.setTextSize(sp(12));
        mTextPaint.setColor(0xFF9090A0);
        canvas.drawText("结局之后，一段被归档的录音", mW / 2, mH * 0.15f + sp(22), mTextPaint);

        beginContentScroll(canvas, mH * 0.21f);
        float y = mH * 0.26f;
        y = drawWrappedParagraph(canvas, epilogue, mW / 2, y, mW - sp(48), sp(16), GameUi.TEXT);
        y += sp(10);
        y = drawWrappedParagraph(canvas, "（录音结束）", mW / 2, y,
                mW - sp(48), sp(13), 0xFF707080);
        mContentEnd = y + sp(20);
        endContentScroll(canvas);

        mHintPaint.setTextSize(sp(13));
        mHintPaint.setColor(0xFF8A8A9A);
        canvas.drawText("—— 触屏进入二周目 ——", mW / 2, mH - sp(56), mHintPaint);
    }

    // ---------- 工具 ----------

    private String anchorName(EchoEngine.AnchorType t) {
        switch (t) {
            case DOOR_BEEP: return "门禁刷卡声";
            case COFFEE_MACHINE: return "咖啡机启动声";
            case HEART_MONITOR: return "心率监护仪";
            case KEY_TURN: return "钥匙转动";
            case CLOCK_CHIME: return "钟声";
            default: return "";
        }
    }

    private void drawTopBar(Canvas canvas, String title, boolean showMeters) {
        mBaseY = mH * 0.16f;

        mTextPaint.setTextSize(sp(17));
        mTextPaint.setColor(GameUi.TEXT);
        canvas.drawText(title, mW / 2, mH * 0.06f, mTextPaint);

        // 仪表行仅在高度足够的屏上绘制：矮屏下仪表标签会与标题/下方正文交叠，此时整体省略。
        // 各页正文/提示行统一从 mHeaderContentY 起排，保证不再压到“偏执/焦虑”标签或标题文字。
        boolean metersFit = showMeters && mH / mDensity >= 500f;
        mHeaderContentY = metersFit
                ? mH * 0.095f + sp(20)   // 仪表条底沿 + 行首留白 → 首行基线
                : mH * 0.06f + sp(34);   // 无仪表：紧贴标题之下
        if (!metersFit) return;

        // 偏执条（红）
        drawMeter(canvas, mW * 0.1f, mH * 0.095f, mW * 0.34f, sp(5), mEngine.getParanoia(), 0xFFEF5350);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setColor(0xFFEF5350);
        canvas.drawText("偏执 " + mEngine.getParanoia(), mW * 0.27f, mH * 0.095f - sp(4), mTextPaint);

        // 焦虑条（紫）
        drawMeter(canvas, mW * 0.56f, mH * 0.095f, mW * 0.34f, sp(5), mEngine.getAnxiety(), 0xFFB388FF);
        mTextPaint.setTextSize(sp(9));
        mTextPaint.setColor(0xFFB388FF);
        canvas.drawText("焦虑 " + mEngine.getAnxiety(), mW * 0.73f, mH * 0.095f - sp(4), mTextPaint);

        // 困难模式标记
        if (mEngine.isHardMode()) {
            mTextPaint.setTextSize(sp(10));
            mTextPaint.setColor(0xFFFFD54F);
            canvas.drawText("困难", mW / 2, mH * 0.095f - sp(6), mTextPaint);
        }
    }

    private void drawMeter(Canvas canvas, float left, float top, float width, float height, int value, int color) {
        // 背景
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xFF1A1A28);
        canvas.drawRoundRect(new RectF(left, top, left + width, top + height), height / 2, height / 2, mPaint);
        // 填充
        float filled = width * Math.min(100, Math.max(0, value)) / 100f;
        if (filled > 0) {
            mPaint.setColor(color);
            canvas.drawRoundRect(new RectF(left, top, left + filled, top + height), height / 2, height / 2, mPaint);
        }
    }

    private void drawWrappedText(Canvas canvas, String text, float cx, float top,
                                 float maxWidth, float lineHeight, int color, int maxLines) {
        mTextPaint.setTextSize(lineHeight);
        mTextPaint.setColor(color);
        StaticLayout layout = buildLayout(text, maxWidth);
        float y = top;
        int lines = Math.min(layout.getLineCount(), Math.max(1, maxLines));
        for (int i = 0; i < lines; i++) {
            canvas.drawText(text.substring(layout.getLineStart(i), layout.getLineEnd(i)),
                    cx, y, mTextPaint);
            y += lineHeight;
        }
    }

    /** 构建居中折行布局（需先设置 mTextPaint 的字号） */
    private StaticLayout buildLayout(String text, float maxWidth) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return StaticLayout.Builder
                    .obtain(text, 0, text.length(), mTextPaint, (int) maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build();
        }
        return new StaticLayout(text, mTextPaint, (int) maxWidth,
                Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
    }

    /** 估算文本折行后的行数（与 drawWrappedParagraph 一致） */
    private int estimateLines(String text, float maxWidth, float textSize) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        mTextPaint.setTextSize(textSize);
        return buildLayout(text, maxWidth).getLineCount();
    }

    /** 绘制自动折行的段落（不限行数），返回下一行内容的 y 坐标 */
    private float drawWrappedParagraph(Canvas canvas, String text, float cx, float top,
                                       float maxWidth, float textSize, int color) {
        if (text == null || text.isEmpty()) {
            return top;
        }
        mTextPaint.setTextSize(textSize);
        mTextPaint.setColor(color);
        StaticLayout layout = buildLayout(text, maxWidth);
        float y = top;
        float lineHeight = textSize * 1.25f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            canvas.drawText(text.substring(layout.getLineStart(i), layout.getLineEnd(i)),
                    cx, y, mTextPaint);
            y += lineHeight;
        }
        return y;
    }

    // ---------- 视觉特效数据类（Qt 同步） ----------

    /** Qt GlitchParticle：扭曲撕裂红色粒子 */
    private static class GlitchParticle {
        float x, y, vx, vy, life, maxLife;
        int g, b;
    }

    /** Qt HallucinationText：飘动幻觉文字 */
    private static class DriftText {
        String text;
        float x, y, sx, sy, life, alpha;
    }

    /** Qt FloatingNoise：漂浮底噪文字 */
    private static class NoiseText {
        String text;
        float x, y, life, alpha;
    }

    /** Qt SpectrumLine：底部频谱线 */
    private static class SpectrumLine {
        float phase, frequency, amplitude;
    }
}
