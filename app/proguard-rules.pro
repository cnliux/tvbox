# kotlinx.serialization / Compose / AndroidX 的 R8 规则由各自 AAR 内置的
# META-INF/com.android.tools/r8/*.pro 提供，这里只保留本项目的必要规则。

# Release 移除 v/d/i/w 日志调用（保留 error，便于线上排障）。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

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
