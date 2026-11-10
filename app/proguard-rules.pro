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

# ── 第 82 轮评估结论（本轮只评估，不改规则）────────────────────────────
# 现状：`-keep class com.tvbox.app.ui.** { *; }` 是全量 keep，覆盖所有 Composable、
#   ViewModel 与状态类，R8 无法对 ui 包做类合并 / 内联 / 字段裁剪，是剩余体积空间里
#   最明确的一项；同时被 androidx.profileinstaller 的 Baseline Profile 链路依赖，
#   直接删除会让 profile 中的类签名在 Release 里找不到，首帧与滚动性能可能回退。
# 可收紧路径（本地只跑 assembleDebug / testDebugUnitTest，Release 由 CI 验证）：
#   1. 收窄为仅保留 Composable 入口与 profileinstaller 标记类，而不是整个 ui 包；
#   2. 用 apkanalyzer dex packages 对比前后 dex 体积与类数；
#   3. 重新采集 Baseline Profile，确认标记类仍可解析；
#   4. 由 release.yml 的 arm64-v8a Release 构建验证后再合并规则变更。
# 风险：Compose 编译器生成的 Composer / remember 相关类或反射入口被裁掉时，
#   表现为线上首屏崩溃；因此本轮不改规则，避免把未验证的收紧带进发布。

