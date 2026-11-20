# TDLib JNI reflection — must be kept (official org.drinkless.tdlib package)
-keep class org.drinkless.tdlib.** { *; }
-keep class com.tgdrive.core.telegram.** { *; }

# Native method signatures
-keepclasseswithmembernames class * {
    native <methods>;
}
