# 纯 shrink：只删死代码，不混淆、不做激进优化。
# 目的是砍掉 material-icons-extended 里几千个没用到的图标类
# （debug 包 44 MB 的 classes.dex 大半是它），同时把反射相关的
# 风险降到最低 —— 不改名，任何按名字找类的地方都照常工作。
-dontobfuscate
-dontoptimize
-keepattributes *
-dontwarn kotlinx.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn org.w3c.**

# JNI：native 方法和它们的宿主类必须原样保留
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.biji.notes.nativebridge.** { *; }

# JNA / Vosk 全靠反射 + JNI 找类
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keep class org.jetbrains.annotations.** { *; }

# Room entity / DAO / 数据模型（Room 自带 consumer rules，这里再兜一层）
-keep class com.biji.notes.data.** { *; }

# kotlinx.serialization 的 @Serializable 伴生 serializer
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
