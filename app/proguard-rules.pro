# Keep Retrofit service method signatures and annotations for runtime request creation.
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# App models are serialized through kotlinx.serialization generated serializers.
# Keep companion serializer lookup stable after R8 optimization.
-keepclassmembers class com.tvbox.app.data.** {
    public static ** Companion;
}

# 内置 Python 解析器（Chaquopy）通过 jclass("com.tvbox.app.data.DouyinSigner")
# 按字符串反射调用该类；R8 混淆改名后反射会 ClassNotFoundException，
# 导致斗鱼签名退回本机 Node.js（设备上没有 node）而解析失败。必须保留类名与成员。
-keep class com.tvbox.app.data.DouyinSigner { *; }
