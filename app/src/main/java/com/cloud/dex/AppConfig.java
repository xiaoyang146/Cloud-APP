package com.cloud.dex;

public final class AppConfig {

    private AppConfig() {}

    // ============ Server ============
    public static final String BASE_URL = "https://ok1666.cn/cloud/APPyingyon";
    public static final String ADMIN_BASE_URL = "https://ok1666.cn/cloud/admin";
    public static final String TOKEN_REFRESH_URL = BASE_URL + "/token_refresh.php";

    // ============ API paths ============
    public static final String LOGIN_URL            = BASE_URL + "/login.php";
    public static final String STATISTICS_URL       = BASE_URL + "/shuaxing.php";
    public static final String ADD_APP_URL          = BASE_URL + "/yingyong/tj_yingyong.php";
    public static final String GET_APPS_URL         = BASE_URL + "/yingyong/get_user_apps.php";
    public static final String UPDATE_APP_URL       = BASE_URL + "/yingyong/update_app.php";
    public static final String FROZEN_STATUS_URL    = BASE_URL + "/user/get_frozen_status.php";
    public static final String USER_INFO_URL        = BASE_URL + "/user/get_user_info.php";
    public static final String MEMBERSHIP_TYPE_URL  = BASE_URL + "/user/get_membership_type.php";
    public static final String YIYAN_API_URL        = BASE_URL + "/yiyan/yiyan.php";
    public static final String POPUP_NOTICE_URL     = ADMIN_BASE_URL + "/popup_notice_get.php";
    public static final String FIRST_LOGIN_URL       = ADMIN_BASE_URL + "/check_first_login.php";
    public static final String CHECK_VERSION_URL    = BASE_URL + "/gengxin/check_version.php";
    public static final String UPDATE_LOG_URL       = BASE_URL + "/gengxin/get_update_log.php";
    public static final String CHECK_KAMI_TYPES_URL = BASE_URL + "/kami/check_kami_types.php";
    public static final String EXPORT_KAMI_URL      = BASE_URL + "/kami/export_kami.php";
    public static final String CREATE_PAYMENT_URL   = BASE_URL + "/huiyuan/membership.php?action=create_payment";
    public static final String CHECK_PAYMENT_URL    = BASE_URL + "/huiyuan/check_payment_status.php";
    public static final String MEMBERSHIP_STATUS_URL = BASE_URL + "/huiyuan/get_membership_status.php";
    public static final String MEMBERSHIP_PRICES_URL = BASE_URL + "/huiyuan/get_membership_prices.php";
    public static final String SEND_CODE_URL        = BASE_URL + "/user/send_code.php";
    public static final String VERIFY_CODE_URL      = BASE_URL + "/user/verify_code.php";

    public static final String UPLOAD_AVATAR_URL   = BASE_URL + "/user/upload_avatar.php";
    public static final String FORGOT_PASSWORD_URL  = BASE_URL + "/password/forgot_password_api.php";
    public static final String UPDATE_PASSWORD_URL  = BASE_URL + "/user/update_password1.php";
    public static final String RESET_PASSWORD_URL   = BASE_URL + "/user/update_password.php";

    // ============ API paths - 应用管理 ============
    public static final String DELETE_APP_URL       = BASE_URL + "/yingyong/delete_app.php";
    public static final String GET_ALL_APPS_URL     = BASE_URL + "/yingyong/get_apps.php";

    // ============ API paths - 卡密管理 ============
    public static final String GET_KAMI_LIST_URL    = BASE_URL + "/kamiliebiao/get_kami_list.php";
    public static final String GET_KAMI_DETAIL_URL  = BASE_URL + "/kamiliebiao/get_kami_detail.php";
    public static final String UPDATE_KAMI_URL      = BASE_URL + "/kamiliebiao/update_kami.php";
    public static final String UNBIND_DEVICE_URL    = BASE_URL + "/kamiliebiao/unbind_device.php";
    public static final String DELETE_KAMI_URL      = BASE_URL + "/kamiliebiao/delete_kami.php";
    public static final String ADD_KAMI_URL         = BASE_URL + "/kami/add_kami.php";
    public static final String CHECK_MEMBERSHIP_URL = BASE_URL + "/kami/check_membership.php";

    // ============ API paths - 公告管理 ============
    public static final String GET_USER_NOTICES_URL      = BASE_URL + "/gonggao/get_user_notices.php";
    public static final String UPDATE_NOTICE_URL          = BASE_URL + "/gonggao/update_notice.php";
    public static final String DELETE_NOTICE_URL          = BASE_URL + "/gonggao/delete_notice.php";
    public static final String UPDATE_NOTICE_ENABLED_URL  = BASE_URL + "/gonggao/update_notice_enabled.php";
    public static final String GET_GONGGAO_URL            = BASE_URL + "/gonggao/get_gonggao.php";
    public static final String ADD_NOTICE_URL             = BASE_URL + "/gonggao/add_notice.php";

    // ============ API paths - 会员/支付 ============
    public static final String MEMBERSHIP_PAY_URL   = BASE_URL + "/huiyuan/membership.php";

    // ============ API paths - 插件更新 ============
    public static final String PLUGIN_UPDATE_URL    = BASE_URL + "/plugin_update.php";

    // ============ API paths - 其他 ============
    public static final String DOC_URL              = BASE_URL + "/doc/Word.php";
    public static final String GET_DAILY_STATS_URL  = BASE_URL + "/get_daily_stats.php";

    // ============ Contact ============
    public static final String WEBSITE_URL = "https://ok1666.cn";
    public static final String AUTHOR_QQ = "2502660988";
    public static final String CUSTOMER_SERVICE_QQ = "2502660988";

    // ============ Security ============
    public static final int MAX_LOGIN_ATTEMPTS = 5;
    public static final long LOGIN_COOLDOWN_MS = 30000; // 30s cooldown after max attempts
    public static final long LOGIN_DEBOUNCE_MS = 1500;  // 1.5s minimum interval between attempts

}
