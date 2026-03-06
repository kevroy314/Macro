# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Glance
-keep class androidx.glance.** { *; }

# Vico Charts
-keep class com.patrykandpatrick.vico.** { *; }
