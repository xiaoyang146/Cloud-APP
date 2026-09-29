# Keep model/data classes used by Gson
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class * extends java.lang.Enum { *; }

# Keep JSON object classes (used with org.json)
-keep class org.json.** { *; }

# Keep OkHttp (used for network calls)
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Glide
-keep class com.bumptech.glide.** { *; }
-keep class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.load.resource.bitmap.** { *; }

# Keep ZXing (QR code scanning)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Keep Alipay SDK
-keep class com.alipay.** { *; }
-dontwarn com.alipay.**

# Keep View classes referenced from XML layouts
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Keep Volley
-dontwarn com.android.volley.**

# Keep Smali/DEX libraries
-keep class org.jf.** { *; }
-dontwarn org.jf.**
-keep class org.smali.** { *; }
-dontwarn org.smali.**

# Keep all Activity/Service/Receiver classes (prevent incorrect rename from manifest)
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Application { *; }

# Keep R (resources)
-keep class **.R$* { *; }

# General Android rules
-keepattributes *Annotation*, Signature, Exception
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep custom application classes
-keep class com.cloud.dex.** {
    public protected *;
}

# Keep 下拉刷新组件（仅在 XML 中引用的自定义 View，防止被 R8 裁掉）
-keep class com.cloud.dex.widget.refresh.** { *; }
