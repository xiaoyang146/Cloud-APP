package com.cloud.dex;

public class NoticeItem {
    private int noticeId;
    private int appId;
    private String appName;
    private String title;
    private String content;
    private String createTime;
    private boolean enabled;

    // 唯一构造函数，必须传入 enabled
    public NoticeItem(int noticeId, int appId, String appName, String title,
                      String content, String createTime, boolean enabled) {
        this.noticeId = noticeId;
        this.appId = appId;
        this.appName = appName;
        this.title = title;
        this.content = content;
        this.createTime = createTime;
        this.enabled = enabled;
    }

    // Getters
    public int getNoticeId() { return noticeId; }
    public int getAppId() { return appId; }
    public String getAppName() { return appName; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getCreateTime() { return createTime; }
    public boolean isEnabled() { return enabled; }

    // Setter for enabled
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}