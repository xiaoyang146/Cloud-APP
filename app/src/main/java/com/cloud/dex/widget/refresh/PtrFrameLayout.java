package com.cloud.dex.widget.refresh;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.Scroller;


import java.util.ArrayList;
import java.util.List;

/**
 * 下拉刷新容器（美团众包同款）。
 *
 * 行为对齐 com.meituan.banma.base.common.ui.ptr.PtrFrameLayout 13.9.0：
 * <ul>
 *   <li>下拉阻尼：offset = distance / resistance（默认 1.7）</li>
 *   <li>触发阈值：headerHeight * ratioOfHeaderHeightToRefresh（默认 1.2）</li>
 *   <li>回弹时长：durationToClose / durationToCloseHeader（默认 300ms）</li>
 *   <li>keepHeaderWhenRefresh=true：刷新过程中头部保持铺开</li>
 *   <li>pullToFresh=false：以「下拉比例」而不是「松手」判定触发</li>
 * </ul>
 *
 * 用法：
 * <pre>
 * &lt;com.cloud.dex.widget.refresh.PtrFrameLayout
 *     android:id="@+id/swipeRefreshLayout"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"&gt;
 *     &lt;!-- 只放一个内容子 View：RecyclerView / ScrollView / 任意布局 --&gt;
 * &lt;/com.cloud.dex.widget.refresh.PtrFrameLayout&gt;
 * </pre>
 *
 * 代码里：
 * <pre>
 * ptr.setOnRefreshListener(() -&gt; loadData());
 * ptr.setRefreshing(false); // 数据加载结束
 * </pre>
 */
public class PtrFrameLayout extends ViewGroup {

    // ---------------- 状态 ----------------
    /** 初始状态：可以下拉 */
    public static final int STATUS_INIT = 0;
    /** 已超过阈值：松手就刷新 */
    public static final int STATUS_RELEASE_TO_REFRESH = 1;
    /** 正在刷新 */
    public static final int STATUS_REFRESHING = 2;
    /** 刷新完成 */
    public static final int STATUS_COMPLETE = 3;

    /** 兼容 androidx SwipeRefreshLayout.OnRefreshListener 的回调 */
    public interface OnRefreshListener {
        void onRefresh();
    }

    /** 状态变化监听 */
    public interface OnStateChangedListener {
        void onStateChanged(int oldStatus, int newStatus);
    }

    // ---------------- 可配置属性 ----------------
    private float mResistance = 1.7f;
    private float mRatioOfHeaderHeightToRefresh = 1.2f;
    private int mDurationToClose = 450;
    private int mDurationToCloseHeader = 450;
    private boolean mKeepHeaderWhenRefresh = true;
    private boolean mPullToRefresh = false;

    // ---------------- 子 View ----------------
    private View mHeaderView;
    private View mContentView;
    private View mScrollTargetView;

    // ---------------- 运行时状态 ----------------
    private int mHeaderHeight;
    private float mOffsetY;
    private int mAppliedHeaderOffset;
    private int mAppliedContentOffset;
    private float mLastMotionY;
    private final int mTouchSlop;
    private boolean mIsBeingDragged;
    private int mStatus = STATUS_INIT;
    private boolean mPullEnabled = true;
    private boolean mPendingRefreshing;
    /** 布局完成后是否要执行平滑自动刷新 */
    private boolean mPendingSmoothRefresh;
    private int mPendingSmoothRefreshDuration = 300;

    private PtrHandler mPtrHandler;
    private PtrUIHandler mHeaderUIHandler;
    private OnStateChangedListener mStateChangedListener;
    private final List<PtrUIHandler> mUIHandlers = new ArrayList<PtrUIHandler>(2);

    private final Scroller mScroller;
    /** 回弹/收起动画插值器：缓出，让收尾更柔和 */
    private final Interpolator mCloseInterpolator = new DecelerateInterpolator(1.6f);
    // 自定义插值滚动（用于让回弹收尾更柔和）
    private boolean mUseInterpolatedScroll = false;
    private float mScrollStart = 0f;
    private float mScrollTarget = 0f;
    private int mScrollDuration = 0;
    private long mScrollStartTime = 0L;
    private Interpolator mScrollInterpolator = null;
    private final Runnable mCancelRunnable = new Runnable() {
        @Override
        public void run() {
            mScroller.forceFinished(true);
        }
    };
    private final Runnable mCompleteRunnable = new Runnable() {
        @Override
        public void run() {
            mStatus = STATUS_INIT;
            notifyUIReset();
            scrollOffsetTo(0, mDurationToClose);
        }
    };

    public PtrFrameLayout(Context context) {
        this(context, null);
    }

    public PtrFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PtrFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mScroller = new Scroller(context);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, com.cloud.dex.R.styleable.PtrFrameLayout);
            mResistance = a.getFloat(com.cloud.dex.R.styleable.PtrFrameLayout_ptr_resistance, mResistance);
            mRatioOfHeaderHeightToRefresh = a.getFloat(
                    com.cloud.dex.R.styleable.PtrFrameLayout_ptr_ratio_of_header_height_to_refresh,
                    mRatioOfHeaderHeightToRefresh);
            mDurationToClose = a.getInt(
                    com.cloud.dex.R.styleable.PtrFrameLayout_ptr_duration_to_close, mDurationToClose);
            mDurationToCloseHeader = a.getInt(
                    com.cloud.dex.R.styleable.PtrFrameLayout_ptr_duration_to_close_header, mDurationToCloseHeader);
            mKeepHeaderWhenRefresh = a.getBoolean(
                    com.cloud.dex.R.styleable.PtrFrameLayout_ptr_keep_header_when_refresh, mKeepHeaderWhenRefresh);
            mPullToRefresh = a.getBoolean(
                    com.cloud.dex.R.styleable.PtrFrameLayout_ptr_pull_to_fresh, mPullToRefresh);
            a.recycle();
        }

        setClipToPadding(false);

        // 默认头部：美团众包同款（箭头 + 转圈 + 提示文字）
        View header = LayoutInflater.from(context)
                .inflate(com.cloud.dex.R.layout.view_ptr_loading_header, this, false);
        if (header != null) {
            setHeaderView(header);
        }
    }

    // ---------------- 头部 / 回调 ----------------

    /** 设置头部 View；若它实现了 PtrUIHandler 会自动注册 */
    public void setHeaderView(View headerView) {
        if (mHeaderView != null) {
            if (mHeaderUIHandler != null) {
                removePtrUIHandler(mHeaderUIHandler);
                mHeaderUIHandler = null;
            }
            removeView(mHeaderView);
        }
        mHeaderView = headerView;
        if (mHeaderView != null) {
            mHeaderView.setVisibility(View.VISIBLE);
            addView(mHeaderView, 0);
            if (mHeaderView instanceof PtrUIHandler) {
                mHeaderUIHandler = (PtrUIHandler) mHeaderView;
                addPtrUIHandler(mHeaderUIHandler);
            }
        }
    }

    public void addPtrUIHandler(PtrUIHandler handler) {
        if (handler != null && !mUIHandlers.contains(handler)) {
            mUIHandlers.add(handler);
        }
    }

    public void removePtrUIHandler(PtrUIHandler handler) {
        mUIHandlers.remove(handler);
    }

    /** 设置业务刷新回调 */
    public void setPtrHandler(PtrHandler handler) {
        mPtrHandler = handler;
        if (handler != null && mHeaderUIHandler != null) {
            mHeaderUIHandler.onUIRefreshPrepare(this);
        }
    }

    public PtrHandler getPtrHandler() {
        return mPtrHandler;
    }

    /**
     * 兼容 SwipeRefreshLayout 的写法：
     * {@code ptr.setOnRefreshListener(() -> loadData());}
     */
    public void setOnRefreshListener(final OnRefreshListener listener) {
        if (listener == null) {
            mPtrHandler = null;
            return;
        }
        mPtrHandler = new PtrDefaultHandler() {
            @Override
            public void onRefreshBegin(PtrFrameLayout frame) {
                listener.onRefresh();
            }
        };
    }

    public void setOnStateChangedListener(OnStateChangedListener listener) {
        mStateChangedListener = listener;
    }

    /** 指定「内容是否滚到顶部」的判定依据（默认自动递归查找内容里的可滚动子 View） */
    public void setScrollTargetView(View view) {
        mScrollTargetView = view;
    }

    // ---------------- 兼容 SwipeRefreshLayout 的 API ----------------

    /** 是否正在刷新 */
    public boolean isRefreshing() {
        return mStatus == STATUS_REFRESHING;
    }

    /**
     * 兼容 SwipeRefreshLayout#setRefreshing(boolean)。
     * true：头部铺开并显示「正在加载」（不会回调 onRefresh，业务自己发起请求）；
     * false：收起头部（等价 refreshComplete）。
     */
    public void setRefreshing(boolean refreshing) {
        if (refreshing) {
            if (mStatus == STATUS_REFRESHING) {
                return;
            }
            if (mHeaderHeight <= 0) {
                // 还没测量完，等布局结束再铺开
                mPendingRefreshing = true;
                return;
            }
            mPendingRefreshing = false;
            mStatus = STATUS_REFRESHING;
            mScroller.forceFinished(true);
            notifyUIRefreshBegin();
            setOffset(mHeaderHeight, false);
        } else {
            // 兼容 SwipeRefreshLayout 语义：仅要求"不是刷新状态"。
            // 若本就未处于刷新中，则静默复位，绝不播放头部展开/收起动画，
            // 避免切换页面或点底部导航时出现莫名下拉动画。
            if (mStatus != STATUS_REFRESHING) {
                silentReset();
                return;
            }
            refreshComplete();
        }
    }

    /** 静默复位：不播动画、不回调，仅把头部归位到 0 */
    public void silentReset() {
        if (mStatus == STATUS_REFRESHING) {
            return;
        }
        mPendingRefreshing = false;
        mUseInterpolatedScroll = false;
        mScroller.forceFinished(true);
        removeCallbacks(mCompleteRunnable);
        removeCallbacks(mPerformRefreshRunnable);
        mStatus = STATUS_INIT;
        notifyUIReset();
        setOffset(0, false);
    }

    /** 兼容 SwipeRefreshLayout 的配色 API：本实现使用固定图标，忽略 */
    public void setColorSchemeColors(int... colors) {
        // no-op：众包同款头部的配色由 drawable 决定
    }

    /** 兼容 SwipeRefreshLayout 的 API：忽略 */
    public void setProgressBackgroundColorSchemeColor(int color) {
        // no-op
    }

    /** 兼容 SwipeRefreshLayout 的 API：忽略 */
    public void setProgressBackgroundColorSchemeResource(int colorRes) {
        // no-op
    }

    /** 兼容 SwipeRefreshLayout 的 API：忽略 */
    public void setProgressViewOffset(boolean scale, int start, int end) {
        // no-op
    }

    /** 兼容 SwipeRefreshLayout 的 API：忽略 */
    public void setDistanceToTriggerSync(int distance) {
        // no-op
    }

    /** 是否允许下拉刷新 */
    public void setPullDownEnabled(boolean enabled) {
        mPullEnabled = enabled;
        if (!enabled) {
            if (mStatus == STATUS_REFRESHING) {
                refreshComplete();
            } else {
                resetHeader();
            }
        }
    }

    public boolean isPullDownEnabled() {
        return mPullEnabled;
    }

    // ---------------- 属性读写 ----------------

    public void setResistance(float resistance) {
        this.mResistance = resistance;
    }

    public float getResistance() {
        return mResistance;
    }

    public void setRatioOfHeaderHeightToRefresh(float ratio) {
        this.mRatioOfHeaderHeightToRefresh = ratio;
    }

    public float getRatioOfHeaderHeightToRefresh() {
        return mRatioOfHeaderHeightToRefresh;
    }

    public void setDurationToClose(int duration) {
        this.mDurationToClose = duration;
    }

    public void setDurationToCloseHeader(int duration) {
        this.mDurationToCloseHeader = duration;
    }

    public void setKeepHeaderWhenRefresh(boolean keep) {
        this.mKeepHeaderWhenRefresh = keep;
    }

    public void setPullToRefresh(boolean pullToRefresh) {
        this.mPullToRefresh = pullToRefresh;
    }

    public int getStatus() {
        return mStatus;
    }

    public int getHeaderHeight() {
        return mHeaderHeight;
    }

    public View getHeaderView() {
        return mHeaderView;
    }

    public View getContentView() {
        return mContentView;
    }

    // ---------------- 主动刷新 ----------------

    /** 代码触发刷新（会回调 onRefresh） */
    public boolean autoRefresh() {
        return autoRefresh(true);
    }

    public boolean autoRefresh(boolean atOnce) {
        if (mStatus == STATUS_REFRESHING || mHeaderHeight <= 0) {
            return false;
        }
        mPendingRefreshing = false;
        mStatus = STATUS_REFRESHING;
        mScroller.forceFinished(true);
        if (atOnce) {
            setOffset(mHeaderHeight, false);
        } else {
            scrollOffsetTo(mHeaderHeight, 200);
        }
        notifyUIRefreshBegin();
        if (mPtrHandler != null) {
            mPtrHandler.onRefreshBegin(this);
        }
        return true;
    }

    /**
     * 平滑自动刷新：头部带过渡动画铺开，再回调 onRefresh。
     * 用于"新增/修改/删除数据后自动刷新一次"的场景，比 atOnce 更自然。
     *
     * @return 是否成功触发（已在刷新中、未测量完成时为 false）
     */
    public boolean autoRefreshSmoothly() {
        return autoRefreshSmoothly(300);
    }

    public boolean autoRefreshSmoothly(int duration) {
        if (mStatus == STATUS_REFRESHING) {
            return false;
        }
        if (mHeaderHeight <= 0) {
            // 尚未测量完成，等布局结束后再触发
            mPendingSmoothRefresh = true;
            mPendingSmoothRefreshDuration = duration;
            return true;
        }
        mPendingRefreshing = false;
        mPendingSmoothRefresh = false;
        mStatus = STATUS_REFRESHING;
        mScroller.forceFinished(true);
        // 先播放头部展开过渡，动画结束后再回调 onRefresh
        scrollOffsetTo(mHeaderHeight, duration, mCloseInterpolator);
        notifyUIRefreshBegin();
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mStatus == STATUS_REFRESHING && mPtrHandler != null) {
                    mPtrHandler.onRefreshBegin(PtrFrameLayout.this);
                }
            }
        }, duration);
        return true;
    }

    /** 数据加载完成，头部收起 */
    public void refreshComplete() {
        if (mStatus != STATUS_REFRESHING) {
            return;
        }
        mStatus = STATUS_COMPLETE;
        notifyUIRefreshComplete();
        removeCallbacks(mCompleteRunnable);
        postDelayed(mCompleteRunnable, mDurationToCloseHeader);
    }

    /** 立即复位（不带动画回调） */
    private void resetHeader() {
        if (mStatus == STATUS_REFRESHING) {
            return;
        }
        mStatus = STATUS_INIT;
        notifyUIReset();
        scrollOffsetTo(0, mDurationToClose);
    }

    // ---------------- 触摸处理 ----------------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mLastMotionY = ev.getY();
                mIsBeingDragged = false;
                removeCallbacks(mCancelRunnable);
                if (mStatus != STATUS_REFRESHING) {
                    mScroller.forceFinished(true);
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                if (!mPullEnabled || mStatus == STATUS_REFRESHING || mIsBeingDragged) {
                    break;
                }
                final float y = ev.getY();
                final float dy = y - mLastMotionY;
                if (dy > mTouchSlop && !canChildScrollUp()) {
                    mIsBeingDragged = true;
                    mLastMotionY = y;
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mIsBeingDragged = false;
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mLastMotionY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mStatus == STATUS_REFRESHING) {
                    return true;
                }
                final float y = ev.getY();
                if (!mIsBeingDragged) {
                    if (y - mLastMotionY > mTouchSlop && !canChildScrollUp()) {
                        mIsBeingDragged = true;
                        mLastMotionY = y;
                    }
                    return true;
                }
                final float dy = y - mLastMotionY;
                mLastMotionY = y;
                float target = mOffsetY + dy / mResistance;
                if (target < 0f) {
                    target = 0f;
                }
                final float max = mHeaderHeight * 2f;
                if (max > 0f && target > max) {
                    target = max;
                }
                setOffset(target, true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!mIsBeingDragged) {
                    return true;
                }
                mIsBeingDragged = false;
                if (mStatus == STATUS_REFRESHING) {
                    return true;
                }
                if (mHeaderHeight > 0
                        && mOffsetY >= mHeaderHeight * mRatioOfHeaderHeightToRefresh) {
                    performRefresh();
                } else {
                    resetHeader();
                }
                return true;
            }
            default:
                return super.onTouchEvent(ev);
        }
    }

    /** 头部铺开动画播完后再真正发起刷新，避免"动画没走完就开始加载" */
    private final Runnable mPerformRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (mStatus == STATUS_REFRESHING && mPtrHandler != null) {
                mPtrHandler.onRefreshBegin(PtrFrameLayout.this);
            }
        }
    };

    private void performRefresh() {
        mStatus = STATUS_REFRESHING;
        mScroller.forceFinished(true);
        // 头部铺开动画时长：至少 380ms，保证过渡看得见
        final int duration = Math.max(mDurationToClose, 380);
        scrollOffsetTo(mHeaderHeight, duration);
        notifyUIRefreshBegin();
        // 关键：等铺开动画播完，再稍微延时一点发起数据刷新
        removeCallbacks(mPerformRefreshRunnable);
        postDelayed(mPerformRefreshRunnable, duration + 60);
    }

    /** 内容是否已滚到顶部 */
    public boolean canChildScrollUp() {
        View v = mScrollTargetView != null ? mScrollTargetView : mContentView;
        return PtrDefaultHandler.canChildScrollUp(v);
    }

    // ---------------- 位移与动画 ----------------

    private int currentHeaderOffset() {
        return (int) Math.min(mOffsetY, mHeaderHeight);
    }

    private void setOffset(float offset, boolean underTouch) {
        final float last = mOffsetY;
        mOffsetY = offset;

        if (underTouch && mStatus != STATUS_REFRESHING) {
            mStatus = (mHeaderHeight > 0
                    && offset >= mHeaderHeight * mRatioOfHeaderHeightToRefresh)
                    ? STATUS_RELEASE_TO_REFRESH : STATUS_INIT;
        }

        applyOffset(currentHeaderOffset(), (int) offset);

        if (last != offset) {
            notifyUIPositionChange(underTouch, mStatus, offset, last);
        }
    }

    private void applyOffset(int headerOffset, int contentOffset) {
        if (mHeaderView != null) {
            final int delta = headerOffset - mAppliedHeaderOffset;
            if (delta != 0) {
                mHeaderView.offsetTopAndBottom(delta);
            }
            mAppliedHeaderOffset = headerOffset;
        }
        if (mContentView != null) {
            final int delta = contentOffset - mAppliedContentOffset;
            if (delta != 0) {
                mContentView.offsetTopAndBottom(delta);
            }
            mAppliedContentOffset = contentOffset;
        }
    }

    private void scrollOffsetTo(int target, int duration) {
        scrollOffsetTo(target, duration, mCloseInterpolator);
    }

    /** 指定位移动画，使用自定义插值器控制缓动曲线 */
    private void scrollOffsetTo(int target, int duration, Interpolator interpolator) {
        mScroller.forceFinished(true);
        final int start = (int) mOffsetY;
        if (duration <= 0 || start == target) {
            setOffset(target, false);
            return;
        }
        if (interpolator != null) {
            mScroller.startScroll(0, start, 0, target - start, duration);
            // Scroller 的插值器在 computeScroll 内通过 getCurrY 体现，
            // 这里改用按比例插值的方式保证缓出效果稳定可用。
            mScrollStart = start;
            mScrollTarget = target;
            mScrollDuration = duration;
            mScrollStartTime = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            mScrollInterpolator = interpolator;
            mUseInterpolatedScroll = true;
            postInvalidateOnAnimation();
            return;
        }
        mUseInterpolatedScroll = false;
        mScroller.startScroll(0, start, 0, target - start, duration);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (mUseInterpolatedScroll) {
            final long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            final long elapsed = now - mScrollStartTime;
            if (mScrollDuration <= 0 || elapsed >= mScrollDuration) {
                mUseInterpolatedScroll = false;
                setOffset(mScrollTarget, false);
                return;
            }
            final float t = elapsed / (float) mScrollDuration;
            final float fraction = mScrollInterpolator != null
                    ? mScrollInterpolator.getInterpolation(t) : t;
            float y = mScrollStart + (mScrollTarget - mScrollStart) * fraction;
            if (y < 0f) {
                y = 0f;
            }
            setOffset(y, false);
            postInvalidateOnAnimation();
            return;
        }
        if (mScroller.computeScrollOffset()) {
            float y = mScroller.getCurrY();
            if (y < 0f) {
                y = 0f;
            }
            setOffset(y, false);
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mCancelRunnable);
        removeCallbacks(mCompleteRunnable);
        mScroller.forceFinished(true);
    }

    // ---------------- 回调分发 ----------------

    private void notifyUIPositionChange(boolean underTouch, int status, float offset, float last) {
        for (int i = 0, size = mUIHandlers.size(); i < size; i++) {
            mUIHandlers.get(i).onUIPositionChange(this, underTouch, status, offset, last);
        }
    }

    private void notifyUIRefreshBegin() {
        for (int i = 0, size = mUIHandlers.size(); i < size; i++) {
            mUIHandlers.get(i).onUIRefreshBegin(this);
        }
        notifyState(STATUS_REFRESHING);
    }

    private void notifyUIRefreshComplete() {
        for (int i = 0, size = mUIHandlers.size(); i < size; i++) {
            mUIHandlers.get(i).onUIRefreshComplete(this);
        }
        notifyState(STATUS_COMPLETE);
    }

    private void notifyUIReset() {
        for (int i = 0, size = mUIHandlers.size(); i < size; i++) {
            mUIHandlers.get(i).onUIReset(this);
        }
        notifyState(STATUS_INIT);
    }

    private void notifyState(int newStatus) {
        if (mStateChangedListener != null && mStatus != newStatus) {
            mStateChangedListener.onStateChanged(mStatus, newStatus);
        }
    }

    // ---------------- 测量 / 布局 ----------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height = getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec);

        mContentView = null;
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child == mHeaderView) {
                continue;
            }
            mContentView = child;
            break;
        }

        if (mHeaderView != null) {
            measureChildWithMargins(mHeaderView, widthMeasureSpec, 0, heightMeasureSpec, 0);
            mHeaderHeight = mHeaderView.getMeasuredHeight();
        }
        if (mContentView != null) {
            measureChildWithMargins(mContentView, widthMeasureSpec, 0, heightMeasureSpec, 0);
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // 基线位置（offset = 0）
        placeChildren();

        mAppliedHeaderOffset = 0;
        mAppliedContentOffset = 0;
        applyOffset(currentHeaderOffset(), (int) mOffsetY);

        if (mPendingRefreshing) {
            mPendingRefreshing = false;
            post(new Runnable() {
                @Override
                public void run() {
                    setRefreshing(true);
                }
            });
        } else if (mPendingSmoothRefresh) {
            mPendingSmoothRefresh = false;
            final int duration = mPendingSmoothRefreshDuration;
            post(new Runnable() {
                @Override
                public void run() {
                    autoRefreshSmoothly(duration);
                }
            });
        }
    }

    private void placeChildren() {
        if (mHeaderView != null) {
            final MarginLayoutParams lp = (MarginLayoutParams) mHeaderView.getLayoutParams();
            final int left = getPaddingLeft() + lp.leftMargin;
            final int top = getPaddingTop() + lp.topMargin - mHeaderView.getMeasuredHeight();
            mHeaderView.layout(left, top, left + mHeaderView.getMeasuredWidth(),
                    top + mHeaderView.getMeasuredHeight());
        }
        if (mContentView != null) {
            final MarginLayoutParams lp = (MarginLayoutParams) mContentView.getLayoutParams();
            final int left = getPaddingLeft() + lp.leftMargin;
            final int top = getPaddingTop() + lp.topMargin;
            mContentView.layout(left, top, left + mContentView.getMeasuredWidth(),
                    top + mContentView.getMeasuredHeight());
        }
    }

    // ---------------- LayoutParams ----------------

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(MarginLayoutParams.MATCH_PARENT,
                MarginLayoutParams.MATCH_PARENT);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}