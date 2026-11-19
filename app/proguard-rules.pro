# sherpa-onnx JNI 入口类不能被混淆
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
