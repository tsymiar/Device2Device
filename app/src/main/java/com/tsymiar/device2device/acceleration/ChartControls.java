package com.tsymiar.device2device.acceleration;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.tsymiar.device2device.R;

/**
 * 实时曲线页那排小控件的实际行为：拖动 / 最小化 / 隐藏 / 暂停。
 *
 * 布局（dialog_view_chart.xml）里这四个控件一直都在，但从来没接过监听，
 * 所以点了没反应。这里统一接上：
 *  - 拖动：按住「拖动」把整块曲线搬到别处（至少留一半在容器里）
 *  - 最小化：缩到右下角 50%，再点一次还原
 *  - 隐藏：收起曲线本体（按钮行留着，随时「显示」），同时暂停采样并把暂停按钮一并藏起来
 *  - 暂停：停掉采样与重绘，按钮变「继续」；只在曲线显示时才有意义
 * 暂停要停传感器，所以由外部（Fragment）通过 {@link Callback} 实现。
 */
public final class ChartControls {

    public interface Callback {
        void onPauseChanged(boolean paused);
    }

    /** 最小化后的缩放比例 */
    private static final float MIN_SCALE = 0.5f;
    /** 拖动时至少要留在容器里的比例，免得整块被拖出去找不回来 */
    private static final float KEEP_VISIBLE = 0.5f;

    private final View mCard;
    private final View[] mContent;
    private final TextView mDragHandle;
    private final Button mPauseBtn;
    private final Button mHideBtn;
    private final TextView mMinLabel;
    private final Callback mCallback;

    private boolean mPaused;
    private boolean mMinimized;
    private boolean mHidden;
    /** 隐藏前的采样状态：重新「显示」时按它恢复，别把用户自己点的暂停给抹了 */
    private boolean mPausedBeforeHide;
    private float mDx;
    private float mDy;

    private ChartControls(View root, Callback callback) {
        mCallback = callback;
        mCard = root.findViewById(R.id.chart_root);
        mContent = new View[]{root.findViewById(R.id.show), root.findViewById(R.id.toor)};
        mDragHandle = root.findViewById(R.id.click);
        mPauseBtn = root.findViewById(R.id.pause_bn);
        mHideBtn = root.findViewById(R.id.out_bn);
        mMinLabel = root.findViewById(R.id.min_label);

        ImageView minimize = root.findViewById(R.id.image_view);
        View.OnClickListener toggleMin = v -> setMinimized(!mMinimized);
        if (minimize != null) minimize.setOnClickListener(toggleMin);
        if (mMinLabel != null) mMinLabel.setOnClickListener(toggleMin);

        if (mPauseBtn != null) {
            mPauseBtn.setOnClickListener(v -> setPaused(!mPaused));
        }
        if (mHideBtn != null) {
            mHideBtn.setOnClickListener(v -> setHidden(!mHidden));
        }
        // 拖动把手；缩小之后整块都能拖
        setupDrag(mDragHandle);
        // 祖先默认会裁子 View，拖出去的部分会被切掉，看起来像没动
        disableClipUpChain(root);
        if (mCard != null) {
            mCard.setOnClickListener(v -> {
                if (mMinimized) setMinimized(false);      // 缩小时点一下还原
            });
        }
    }

    /** 把控件接到已 inflate 好的布局上 */
    public static ChartControls attach(View root, Callback callback) {
        return new ChartControls(root, callback);
    }

    public boolean isPaused() {
        return mPaused;
    }

    private void setPaused(boolean paused) {
        mPaused = paused;
        if (mPauseBtn != null) {
            mPauseBtn.setText(paused ? R.string.resume : R.string.pause);
        }
        if (mCallback != null) mCallback.onPauseChanged(paused);
    }

    private void setHidden(boolean hidden) {
        mHidden = hidden;
        for (View v : mContent) {
            if (v != null) v.setVisibility(hidden ? View.GONE : View.VISIBLE);
        }
        if (mHideBtn != null) {
            mHideBtn.setText(hidden ? R.string.show : R.string.hide);
        }
        if (hidden) {
            // 曲线本体都收起来了，采样没必要继续烧电：顺手暂停，并把暂停按钮一起藏掉
            mPausedBeforeHide = mPaused;
            if (!mPaused) setPaused(true);
            if (mPauseBtn != null) mPauseBtn.setVisibility(View.GONE);
        } else {
            if (mPauseBtn != null) mPauseBtn.setVisibility(View.VISIBLE);
            // 只是被隐藏才停的，显示回来就恢复原来的采样状态
            if (!mPausedBeforeHide && mPaused) setPaused(false);
        }
    }

    private void setMinimized(boolean minimized) {
        mMinimized = minimized;
        if (mCard == null) return;
        float scale = minimized ? MIN_SCALE : 1f;
        if (mCard.getWidth() > 0) {
            // 以右下角为支点缩小，缩完落在右下角（和「最小化」的直觉一致）
            mCard.setPivotX(mCard.getWidth());
            mCard.setPivotY(mCard.getHeight());
        }
        mCard.animate().cancel();
        mCard.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
        if (mMinLabel != null) {
            mMinLabel.setText(minimized ? R.string.restore : R.string.min);
        }
        // 缩放改了占位，重新把可见区域夹回容器内
        mCard.post(this::applyTransform);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDrag(View handle) {
        if (handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            float lastX;
            float lastY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        v.performClick();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        mDx += event.getRawX() - lastX;
                        mDy += event.getRawY() - lastY;
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        applyTransform();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 把缩放 / 平移一起落到控件上，并保证还有一半留在容器里 */
    private void applyTransform() {
        if (mCard == null) return;
        ViewParent parent = mCard.getParent();
        if (parent instanceof View) {
            View host = (View) parent;
            float scale = mMinimized ? MIN_SCALE : 1f;
            int pw = host.getWidth();
            int ph = host.getHeight();
            int w = mCard.getWidth();
            int h = mCard.getHeight();
            if (pw > 0 && ph > 0 && w > 0 && h > 0) {
                float visW = w * scale;
                float visH = h * scale;
                // 缩放后左上角相对原始位置右/下移 (1-scale)*w / (1-scale)*h
                float left = mCard.getLeft() + w - visW + mDx;
                float top = mCard.getTop() + h - visH + mDy;
                float minX = -visW * (1f - KEEP_VISIBLE);
                float maxX = pw - visW * KEEP_VISIBLE;
                float minY = -visH * (1f - KEEP_VISIBLE);
                float maxY = ph - visH * KEEP_VISIBLE;
                if (left < minX) mDx += minX - left;
                if (left > maxX) mDx -= left - maxX;
                if (top < minY) mDy += minY - top;
                if (top > maxY) mDy -= top - maxY;
            }
        }
        mCard.setTranslationX(mDx);
        mCard.setTranslationY(mDy);
    }

    /** 逐层关掉 clipChildren：拖出容器的部分才不会被裁掉 */
    private static void disableClipUpChain(View view) {
        ViewParent parent = view.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            group.setClipChildren(false);
            parent = group.getParent();
        }
    }
}
