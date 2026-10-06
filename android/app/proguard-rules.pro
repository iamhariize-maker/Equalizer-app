# JNI: native methods are looked up by name from libeqjni.so.
-keepclasseswithmembernames class * { native <methods>; }
-keep class app.svan.NativeEngine { *; }
-keep class app.svan.NativeEngine$Companion { *; }

# Shizuku instantiates this Binder by class name from the release APK.
-keep class app.svan.AudioReportsService { public <init>(); *; }
-keep class app.svan.IAudioReports$Stub { *; }
