# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.helper.voiceassistant.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.helper.voiceassistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# sherpa-onnx: native code reads the Kotlin config classes via JNI by field name
-keep class com.k2fsa.sherpa.onnx.** { *; }
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
