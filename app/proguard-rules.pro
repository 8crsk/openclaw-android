# Hilt
-keepclassmembers,allowobfuscation class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# NanoHTTPD (ShizukuBridge)
-keep class fi.iki.elonen.** { *; }

# Shizuku AIDL
-keep class com.crsk.openclaw.shizuku.IShizukuCommandService { *; }
-keep class com.crsk.openclaw.shizuku.IShizukuCommandService$* { *; }
-keep class com.crsk.openclaw.shizuku.ShizukuCommandService { *; }

# Shizuku SDK
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-dontwarn androidx.compose.**
