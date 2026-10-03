# 下拉刷新组件（美团众包同款）

> 本组件是把美团众包 13.9.0 里的下拉刷新框架（`com.meituan.banma.base.common.ui.ptr`，
> 即 Android-Ultra-Pull-To-Refresh 的魔改版）用可读 Java 复刻后，整体替换进 Cloud-APP 的。
> 原来使用的 `androidx.swiperefreshlayout.SwipeRefreshLayout` 已全部移除。

## 1. 文件清单

| 文件 | 说明 |
|---|---|
| `PtrFrameLayout.java` | 下拉刷新容器（阻尼 / 阈值 / 回弹 / 状态机），与原版 `PtrFrameLayout` 行为一致 |
| `PtrUIHandler.java` | 头部状态回调接口，对应原版 `ptr.e`（PtrUIHandler） |
| `PtrHandler.java` | 业务刷新回调，对应原版 `ptr.f`（RefreshHandler） |
| `PtrDefaultHandler.java` | 默认实现：内容滚到顶部才允许下拉（会递归查找可滚动子 View） |
| `PtrLoadingHeader.java` | 刷新头部：箭头 + 转圈 + 提示文字，对应原版 `WaybillBanmaLoadingView` |

资源：

| 资源 | 说明 |
|---|---|
| `res/layout/view_ptr_loading_header.xml` | 头部布局（根节点就是 `PtrLoadingHeader`） |
| `res/drawable/ptr_icon_pull2refresh.png` | 下拉箭头（原版真实图标，63px xxxhdpi） |
| `res/drawable/ptr_icon_pull_refreshing.png` | 刷新中图标（原版真实图标） |
| `res/drawable/ptr_anim_pull_refreshing.xml` | 转圈动画：0° → 1080° 旋转 |
| `res/values/attrs_ptr.xml` | `PtrFrameLayout` 的 6 个自定义属性 |
| `res/values/strings_ptr.xml` | 下拉刷新 / 松手刷新 / 正在加载 |

## 2. 用法

布局里只用把原来的 `SwipeRefreshLayout` 标签换成 `PtrFrameLayout` 即可（id 不用改）：

```xml
<com.cloud.dex.widget.refresh.PtrFrameLayout
    android:id="@+id/swipeRefreshLayout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    app:ptr_resistance="1.7"
    app:ptr_ratio_of_header_height_to_refresh="1.2"
    app:ptr_duration_to_close="300"
    app:ptr_duration_to_close_header="300"
    app:ptr_keep_header_when_refresh="true"
    app:ptr_pull_to_fresh="false">

    <!-- 只放一个内容子 View：RecyclerView / ScrollView / 任意布局 -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recyclerView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
</com.cloud.dex.widget.refresh.PtrFrameLayout>
```

头部不需要手动设置：`PtrFrameLayout` 构造时会自动 inflate `view_ptr_loading_header`
并挂成头部（也可以自己 `setHeaderView(view)` 换成别的头部）。

代码调用与 SwipeRefreshLayout 完全一致：

```java
PtrFrameLayout ptr = findViewById(R.id.swipeRefreshLayout);

ptr.setOnRefreshListener(() -> loadData());  // 下拉触发
ptr.setRefreshing(true);                      // 代码触发（头部铺开显示"正在加载"）
ptr.setRefreshing(false);                     // 数据加载完，头部收起
ptr.isRefreshing();                           // 是否刷新中
ptr.autoRefresh();                            // 等价：铺开头部 + 回调 onRefresh
```

## 3. 与原版的对应关系

| 原版 | 本组件 |
|---|---|
| `ptr_duration_to_close=300` | 默认值一致 |
| `ptr_duration_to_close_header=300` | 默认值一致 |
| `ptr_keep_header_when_refresh=true` | 默认值一致 |
| `ptr_pull_to_fresh=false` | 默认值一致 |
| `ptr_ratio_of_header_height_to_refresh=1.2` | 默认值一致 |
| `ptr_resistance=1.7`（位移 = 手指距离 / 1.7） | 默认值一致 |
| 状态 INIT / RELEASE_TO_REFRESH / REFRESHING / COMPLETE | `STATUS_INIT` 等同名常量 |
| 头部「下拉刷新 / 松手刷新 / 正在加载」+ 箭头 0~180° 旋转 | `PtrLoadingHeader` 一致 |

## 4. 兼容性说明

- `setColorSchemeColors(...)`、`setProgressBackgroundColorSchemeColor(...)`、
  `setDistanceToTriggerSync(...)`、`setProgressViewOffset(...)` 保留为空实现，
  这样业务代码里的老调用不用删。
- `setRefreshing(true)` 只做「视觉上进入刷新」并**不**回调 `onRefresh`，
  与 SwipeRefreshLayout 语义一致（业务自己在后面发起请求）。
- `PtrFrameLayout` 必须由父布局（RelativeLayout / LinearLayout / FrameLayout）派生
  自己的 LayoutParams，因此普通用法没问题；但不要给它自己的**子 View** 加
  `layout_below` 这类相对布局属性。