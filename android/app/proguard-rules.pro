# JNI: native methods are looked up by name from libeqjni.so.
-keepclasseswithmembernames class * { native <methods>; }
-keep class app.svan.NativeEngine { *; }
-keep class app.svan.NativeEngine$Companion { *; }
