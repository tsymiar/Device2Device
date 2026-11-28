package com.tsymiar.device2device.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.MeasureSpec;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.ViewPager;

/**
 * 高度跟着当前页内容走的 ViewPager：wrap_content 下标准 ViewPager 会撑满父容器上限，
 * 按当前页测高可避免短页下方留白；切页时 onPageSelected 里 requestLayout 重测。
 * 超过屏高 40% 上限时夹紧，页面内部由 NestedScrollView 负责滚动。
 */
public class WrapContentViewPager extends ViewPager {
    /** 页面最多占屏高 40%：剩下还要留给标题、页签、状态卡片和日志区 */
    private static final float MAX_HEIGHT_RATIO = 0.40f;

    /** 由外部传入两页 View：首帧 ViewPager 可能还没 instantiateItem，直接测这些更可靠 */
    private View[] measuredPages;

    public void setMeasuredPages(View... pages) {
        measuredPages = pages;
        requestLayout();
    }

    public WrapContentViewPager(@NonNull Context context) {
        super(context);
        syncHeightOnPageChange();
    }

    public WrapContentViewPager(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
        syncHeightOnPageChange();
    }

    /** 两页高度不同，切页后要重测高度 */
    private void syncHeightOnPageChange() {
        addOnPageChangeListener(new SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                requestLayout();
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        View current = currentPageView();
        if (current != null) {
            // 页面里的 NestedScrollView 在 UNSPECIFIED 下会展开成内容自然高度
            current.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int contentHeight = current.getMeasuredHeight();
            if (contentHeight > 0) {
                int screen = getResources().getDisplayMetrics().heightPixels;
                int limit = MeasureSpec.getSize(heightMeasureSpec);
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int maxHeight = (int) (screen * MAX_HEIGHT_RATIO);
                if (limit > 0 && (mode == MeasureSpec.AT_MOST || mode == MeasureSpec.EXACTLY)) {
                    maxHeight = Math.min(maxHeight, limit);
                }
                int height = Math.min(contentHeight, maxHeight);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** 当前页 View：优先取 setMeasuredPages 传入的数组，拿不到退回子 View */
    private View currentPageView() {
        int index = getCurrentItem();
        if (measuredPages != null && index >= 0 && index < measuredPages.length
                && measuredPages[index] != null) {
            return measuredPages[index];
        }
        if (index >= 0 && index < getChildCount()) {
            return getChildAt(index);
        }
        return null;
    }
}
