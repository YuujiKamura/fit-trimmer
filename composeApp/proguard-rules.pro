# Keep all application classes to prevent ClassNotFoundException after R8/ProGuard shrinking
-keep class utils.** { *; }
-keep class fit.** { *; }
-keep class viewmodel.** { *; }
-keep class MainKt { *; }

# Keep ONNX Runtime classes
-keep class com.microsoft.onnxruntime.** { *; }

# Keep JNA (Java Native Access) classes
-keep class com.sun.jna.** { *; }
-keep class com.sun.jna.platform.** { *; }

# Keep serialization helper classes
-keep class kotlinx.serialization.** { *; }
