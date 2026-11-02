# Keep Retrofit service method signatures and annotations for runtime request creation.
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# App models are serialized through kotlinx.serialization generated serializers.
# Keep companion serializer lookup stable after R8 optimization.
-keepclassmembers class com.tvbox.app.data.** {
    public static ** Companion;
}

# 内置平台直播解析来自 gomobile bind 的 platformlive AAR（Go 实现）。
# gomobile 生成的类由 native 层反射调用注册，需保留。
-keep class platformlive.** { *; }
-keep class go.** { *; }
