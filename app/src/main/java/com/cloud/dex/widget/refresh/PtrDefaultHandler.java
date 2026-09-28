package com.cloud.dex.widget.refresh;

import android.view.View;
import android.view.ViewGroup;

/**
 * PtrHandler 的默认实现：只在「内容已经滚到顶部」时才允许下拉刷新。
 *
 * 与 Android-Ultra-Pull-To-Refresh 的 PtrDefaultHandler 行为一致，
 * 但做了增强：内容本身不是一个可滚动控件（例如外面套了一层 RelativeLayout /
 * LinearLayout）时，会递归向下寻找真正可滚动的子 View（RecyclerView / ScrollView / ...）。
 */
public abstract class PtrDefaultHandler implements PtrHandler {

    /** 内容是否已经滚到顶部 */
    public boolean checkCanDoRefresh(PtrFrameLayout frame, View content, View header) {
        return !canChildScrollUp(content);
    }

    /** 递归判断 view 或其后代是否可以继续向上滚动 */
    public static boolean canChildScrollUp(View view) {
        if (view == null) {
            return false;
        }
        if (view.canScrollVertically(-1)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                final View child = group.getChildAt(i);
                if (child != null && child.getVisibility() == View.VISIBLE
                        && canChildScrollUp(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public abstract void onRefreshBegin(PtrFrameLayout frame);
}