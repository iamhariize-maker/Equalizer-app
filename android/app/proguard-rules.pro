# JNI: native methods are looked up by name from libeqjni.so.
-keepclasseswithmembernames class * { native <methods>; }
-keep class app.svan.NativeEngine { *; }
-keep class app.svan.NativeEngine$Companion { *; }
# Instantiated by class name in a separate Shizuku shell process.
-keep class app.svan.DetectionGrantService { public <init>(); *; }
-keep class app.svan.IDetectionGrant** { *; }
