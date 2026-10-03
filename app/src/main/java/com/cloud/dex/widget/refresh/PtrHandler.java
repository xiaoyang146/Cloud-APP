package com.cloud.dex.widget.refresh;

/**
 * 下拉刷新的业务回调。
 *
 * 对应美团众包 13.9.0 里的 com.meituan.banma.base.common.ui.ptr.f（RefreshHandler）。
 */
public interface PtrHandler {

    /** 头部已经就位、开始刷新，业务方在这里发起数据请求 */
    void onRefreshBegin(PtrFrameLayout frame);
}