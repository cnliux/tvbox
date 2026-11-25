# 「压缩优化调试版」专用 R8 规则（buildType = debugOptimized）。
# 目标：拿到接近 Release 的体积 / 混淆优化，同时保留 isDebuggable=true 以便连 ADB 真机验证。
# 与 release 规则的差异：不剥离 android.util.Log 的 v/d/i/w 调用——调试版要靠这些日志排查。

# kotlinx.serialization / Compose / AndroidX 的 R8 规则由各自 AAR 内置的
# META-INF/com.android.tools/r8/*.pro 提供，这里只保留本项目的必要规则。

# 允许 R8 放宽访问修饰符，提升内联与类合并效果。
-allowaccessmodification

# 内置平台直播解析来自 gomobile bind 的 platformlive AAR（Go 实现）。
# gomobile 生成的类由 native 层反射/JNI 调用注册，需保留。
-keep class platformlive.** { *; }
-keep class go.** { *; }

# OkHttp 可选 TLS 实现（未打包，仅避免 R8 缺失类警告）。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Baseline Profiles：保留 profileinstaller 生成所需的标记类。
-keep class com.tvbox.app.ui.** { *; }
-keepclassmembers class com.tvbox.app.ui.** {
    void set*(...);
    *** get*();
}

# 远程管理：内嵌的 HTML/JS 通过 loadUrl("javascript:...") 注入浏览器，
# 相关字符串常量不能被裁剪（虽然 R8 一般不裁字符串字面量，显式声明更稳）。
-keep class com.tvbox.app.data.RemoteManageServer { *; }
