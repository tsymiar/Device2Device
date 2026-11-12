package com.tsymiar.device2device.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.MeasureSpec;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.ViewPager;

/**
 * 高度跟着页面内容走的 ViewPager。
 *
 * 标准 ViewPager 在 wrap_content 下会直接吃掉父容器给的 AT_MOST 上限，
 * 对话框就永远撑到接近满屏；这里先按两页里较高的一页测出自然高度，
 * 再用 EXACTLY 传回去，内容少时收窄、展开文件面板时长高。
 * 超过上限时仍给 EXACTLY，页面内部自己的 NestedScrollView 负责滚动。
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
    }

    public WrapContentViewPager(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int contentHeight = 0;
        View[] pages = measuredPages;
        if (pages == null || pages.length == 0) {
            pages = new View[getChildCount()];
            for (int i = 0; i < getChildCount(); i++) {
                pages[i] = getChildAt(i);
            }
        }
        for (View page : pages) {
            if (page == null) continue;
            // 页面里的 NestedScrollView 在 UNSPECIFIED 下会展开成内容自然高度
            page.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            contentHeight = Math.max(contentHeight, page.getMeasuredHeight());
        }

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
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
