# ============================================================================
# R8/ProGuard 规则（release minify 开启）。
# 目标：混淆业务 Java/Kotlin（FlashEngine/OFP/OPS 等），但保留 RN 桥与 JNI 契约。
# ============================================================================

# ---- React Native 桥 ----
# @ReactMethod 方法由 JS 按方法名反射调用，名字必须保留（类名可混淆）。
-keepclassmembers class * {
    @com.facebook.react.bridge.ReactMethod <methods>;
}
# ReactPackage/NativeModule：allowobfuscation 保留类(防裁剪)但允许改名(业务类名继续混淆)，
# 构造器保留(框架/我方 new 出实例)。@ReactMethod 方法名由下面的成员规则单独保住。
-keep,allowobfuscation class * extends com.facebook.react.bridge.NativeModule { <init>(...); }
-keep,allowobfuscation class * extends com.facebook.react.ReactPackage { <init>(...); }
# Facebook DoNotStrip / 注解保留（Hermes/JNI 互操作）。
-keep @com.facebook.proguard.annotations.DoNotStrip class *
-keep @com.facebook.jni.annotations.DoNotStrip class *
-keepclassmembers class * {
    @com.facebook.proguard.annotations.DoNotStrip *;
    @com.facebook.jni.annotations.DoNotStrip *;
}
-keep class com.facebook.react.turbomodule.** { *; }
-dontwarn com.facebook.react.**
-dontwarn com.facebook.hermes.**

# ---- 加固安全核心：SecurityCore 由 native JNI_OnLoad 按全限定名 FindClass +
# RegisterNatives(方法 a/b/c/d) 绑定。仅需保住"类名 + native 方法名/签名"，
# 其余成员(init/gate/verify 等)可被混淆/内联，不必全量 keep。----
-keep class com.edlflash.edl.SecurityCore {
    native <methods>;
}

# ---- 字符串解密器 + 不透明谓词状态类：被 ASM 注入的字节码按全限定名/方法名引用，保留。----
# StringVault 必须 keep 保名：dpt-shell 的方法体抽取按原始类名(dpt-exclude.rules
# Lcom/edlflash/edl/StringVault;)排除它不被抽取(它是 bootstrap 解密器，抽走会成环)；
# R8 改名会使该排除失配→被壳抽取→启动期解密崩。保名不损强度——解密密钥已下沉 native，
# MT 即便按名定位 StringVault.d 也无法执行 .so 取 key。
-keep class com.edlflash.edl.StringVault { *; }
-keep class com.edlflash.edl.EdlState { public static int x; }

# ---- 去掉 release 日志噪声（不影响 App 自有 onLog 回 JS 的日志）。----
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
