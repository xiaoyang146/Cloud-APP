package com.cloud.dex.widget.refresh;

import android.view.View;

/**
 * 下拉刷新的状态回调接口。
 *
 * 对应美团众包 13.9.0 里的 com.meituan.banma.base.common.ui.ptr.e
 * （即 Android-Ultra-Pull-To-Refresh 的 PtrUIHandler）。
 * 头部 View 实现此接口后，通过 {@link PtrFrameLayout#setHeaderView(View)} 注册。
 */
public interface PtrUIHandler {

    /** 内容回到初始位置，头部复位 */
    void onUIReset(PtrFrameLayout frame);

    /** 头部准备就绪（可下拉） */
    void onUIRefreshPrepare(PtrFrameLayout frame);

    /** 进入刷新中 */
    void onUIRefreshBegin(PtrFrameLayout frame);

    /** 刷新结束，头部即将收起 */
    void onUIRefreshComplete(PtrFrameLayout frame);

    /**
     * 下拉位移变化。
     *
     * @param isUnderTouch 是否是手指拖动中（false 表示回弹动画）
     * @param status       当前状态，见 PtrFrameLayout.STATUS_*
     * @param offset       当前位移（已阻尼）
     * @param lastOffset   上一次位移
     */
    void onUIPositionChange(PtrFrameLayout frame, boolean isUnderTouch, int status,
                            float offset, float lastOffset);
}