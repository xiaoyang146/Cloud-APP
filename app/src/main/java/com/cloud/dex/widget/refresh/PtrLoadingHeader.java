package com.cloud.dex.widget.refresh;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 下拉刷新头部（美团众包同款）。
 *
 * 对应 com.meituan.banma.waybill.widget.list.WaybillBanmaLoadingView：
 * 继承 LinearLayout，实现 ptr.e（{@link PtrUIHandler}）。
 *
 * 三个子控件（原版 findViewById）：
 * <ul>
 *   <li>箭头图标 ImageView  -&gt; com.cloud.dex.R.id.ptr_header_arrow</li>
 *   <li>转圈 ProgressBar    -&gt; com.cloud.dex.R.id.ptr_header_progress</li>
 *   <li>提示文字 TextView   -&gt; com.cloud.dex.R.id.ptr_header_tip</li>
 * </ul>
 *
 * 状态行为：
 * <ul>
 *   <li>下拉未过阈值：箭头显示、ProgressBar 隐藏，文字「下拉刷新」，箭头随下拉比例旋转 0~180°</li>
 *   <li>超过阈值：文字变「松手刷新」</li>
 *   <li>刷新中：箭头隐藏、ProgressBar 显示，文字「正在加载」</li>
 *   <li>复位：回到「下拉刷新」</li>
 * </ul>
 */
public class PtrLoadingHeader extends LinearLayout implements PtrUIHandler {

    private ImageView mArrowView;
    private ProgressBar mProgressView;
    private TextView mTipView;

    private String mTipPull = "下拉刷新";
    private String mTipRelease = "松手刷新";
    private String mTipRefreshing = "正在加载";

    public PtrLoadingHeader(Context context) {
        this(context, null);
    }

    public PtrLoadingHeader(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PtrLoadingHeader(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs == null) {
            // 代码创建（无 XML 属性）时补齐默认样式与子控件
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            final int padding = (int) (16 * getResources().getDisplayMetrics().density);
            setPadding(padding, padding, padding, padding);
            buildChildren();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (mArrowView == null) {
            mArrowView = findViewById(com.cloud.dex.R.id.ptr_header_arrow);
        }
        if (mProgressView == null) {
            mProgressView = findViewById(com.cloud.dex.R.id.ptr_header_progress);
        }
        if (mTipView == null) {
            mTipView = findViewById(com.cloud.dex.R.id.ptr_header_tip);
        }
        if (mArrowView == null && getChildCount() == 0) {
            buildChildren();
        }
        updateTip(mTipPull);
    }

    /** 代码创建时按原版结构组装子控件 */
    private void buildChildren() {
        if (mArrowView != null) {
            return;
        }
        final Context context = getContext();
        final float density = getResources().getDisplayMetrics().density;
        final int iconSize = (int) (24 * density);
        final int gap = (int) (7.5f * density);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        mArrowView = new ImageView(context);
        mArrowView.setId(com.cloud.dex.R.id.ptr_header_arrow);
        mArrowView.setImageResource(com.cloud.dex.R.drawable.ptr_icon_pull2refresh);
        LinearLayout.LayoutParams arrowLp =
                new LinearLayout.LayoutParams(iconSize, iconSize);
        arrowLp.setMarginEnd(gap);
        row.addView(mArrowView, arrowLp);

        mProgressView = new ProgressBar(context, null, android.R.attr.progressBarStyle);
        mProgressView.setId(com.cloud.dex.R.id.ptr_header_progress);
        mProgressView.setIndeterminateDrawable(
                context.getResources().getDrawable(com.cloud.dex.R.drawable.ptr_anim_pull_refreshing));
        mProgressView.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp =
                new LinearLayout.LayoutParams(iconSize, iconSize);
        progressLp.setMarginEnd(gap);
        row.addView(mProgressView, progressLp);

        mTipView = new TextView(context);
        mTipView.setId(com.cloud.dex.R.id.ptr_header_tip);
        mTipView.setTextSize(14f);
        mTipView.setTextColor(0xFF999999);
        mTipView.setText(mTipPull);
        row.addView(mTipView, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        addView(row, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    // ---------------- 文案 ----------------

    public void setTipTexts(String pull, String release, String refreshing) {
        mTipPull = pull;
        mTipRelease = release;
        mTipRefreshing = refreshing;
    }

    private void updateTip(String text) {
        if (mTipView != null && !text.contentEquals(mTipView.getText())) {
            mTipView.setText(text);
        }
    }

    // ---------------- PtrUIHandler ----------------

    @Override
    public void onUIReset(PtrFrameLayout frame) {
        showArrow(0f);
        updateTip(mTipPull);
    }

    @Override
    public void onUIRefreshPrepare(PtrFrameLayout frame) {
        showArrow(0f);
        updateTip(mTipPull);
    }

    @Override
    public void onUIRefreshBegin(PtrFrameLayout frame) {
        if (mArrowView != null) {
            mArrowView.animate().cancel();
            mArrowView.setVisibility(View.GONE);
        }
        if (mProgressView != null) {
            mProgressView.setVisibility(View.VISIBLE);
            // 转圈淡入
            mProgressView.setAlpha(0f);
            mProgressView.animate().alpha(1f).setDuration(180).start();
        }
        updateTip(mTipRefreshing);
    }

    @Override
    public void onUIRefreshComplete(PtrFrameLayout frame) {
        // 数据已返回，等回弹动画结束后的 onUIReset 复位
    }

    @Override
    public void onUIPositionChange(PtrFrameLayout frame, boolean isUnderTouch, int status,
                                   float offset, float lastOffset) {
        if (status == PtrFrameLayout.STATUS_REFRESHING) {
            return;
        }
        final int headerHeight = frame.getHeaderHeight();
        final float ratio = headerHeight <= 0 ? 0f : offset / headerHeight;
        showArrow(Math.max(0f, Math.min(1f, ratio)) * 180f);
        updateTip(status == PtrFrameLayout.STATUS_RELEASE_TO_REFRESH ? mTipRelease : mTipPull);
    }

    private void showArrow(float rotation) {
        if (mArrowView != null) {
            if (mArrowView.getVisibility() != View.VISIBLE) {
                mArrowView.setVisibility(View.VISIBLE);
                // 淡入过渡，避免"瞬间出现"的生硬感
                mArrowView.setAlpha(0f);
                mArrowView.animate().alpha(1f).setDuration(180).start();
            } else {
                mArrowView.animate().cancel();
                mArrowView.setAlpha(1f);
            }
            mArrowView.setRotation(rotation);
        }
        if (mProgressView != null && mProgressView.getVisibility() != View.GONE) {
            mProgressView.animate().cancel();
            mProgressView.setVisibility(View.GONE);
        }
    }
}