# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends javax.inject.Provider { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation

# Media3 / ExoPlayer UI
-keep class androidx.media3.ui.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# TDLib (official org.drinkless.tdlib package)
-keep class org.drinkless.tdlib.** { *; }

# Coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# WorkManager
-keep class androidx.work.impl.** { *; }

# Stacktraces & strip debug/info logs in release
-keepattributes SourceFile,LineNumberTable
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class com.tgdrive.core.common.Log {
    public void d(...);
    public void i(...);
}
