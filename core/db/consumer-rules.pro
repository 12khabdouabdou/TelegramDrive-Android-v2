# Consumer ProGuard rules for :core:db
-keep class com.tgdrive.core.db.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
