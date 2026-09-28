package com.cloud.dex;

public class AppItem {
    private int appId;
    private String appName;
    private String appDesc;
    private String createTime;

    public AppItem(int appId, String appName, String appDesc) {
        this.appId = appId;
        this.appName = appName;
        this.appDesc = appDesc;
    }

    public int getAppId() { return appId; }
    public String getAppName() { return appName; }
    public String getAppDesc() { return appDesc; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    // 用于Spinner显示
    public String getDisplayName() {
        return appName + " - (" + appId + ")";
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}

