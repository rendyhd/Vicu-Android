# =============================================================================
# Vicu Android — ProGuard/R8 Rules
# =============================================================================

# Preserve line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# =============================================================================
# kotlinx.serialization
# =============================================================================
# Keep @Serializable classes and their generated serializers
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static ** INSTANCE;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep all @Serializable data classes in our packages
-keep @kotlinx.serialization.Serializable class com.rendyhd.vicu.** { *; }

# =============================================================================
# Reflection metadata (generic signatures and annotations that Ktor, kotlinx.serialization and
# Room read at runtime)
# =============================================================================
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes *Annotation*

# =============================================================================
# OkHttp
# =============================================================================
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# =============================================================================
# Room
# =============================================================================
# Keep Room entities, DAOs, and database
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * {
    public abstract *;
}

# =============================================================================
# Glance (Widgets)
# =============================================================================
# Keep widget classes referenced by name in manifest/XML
-keep class com.rendyhd.vicu.widget.TaskListWidget { *; }
-keep class com.rendyhd.vicu.widget.TaskWidgetReceiver { *; }
-keep class com.rendyhd.vicu.widget.TaskWidgetWorker { *; }
-keep class com.rendyhd.vicu.widget.ToggleTaskCallback { *; }

# Keep ActionCallback subclasses (Glance resolves them by class name)
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }

# =============================================================================
# BroadcastReceivers (resolved by name from AndroidManifest)
# =============================================================================
-keep class com.rendyhd.vicu.notification.AlarmReceiver { *; }
-keep class com.rendyhd.vicu.notification.NotificationActionReceiver { *; }
-keep class com.rendyhd.vicu.notification.BootReceiver { *; }

# =============================================================================
# WorkManager Workers (resolved by class name)
# =============================================================================
-keep class * extends androidx.work.ListenableWorker { *; }

# =============================================================================
# Tink (Crypto)
# =============================================================================
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# =============================================================================
# Coil
# =============================================================================
-dontwarn coil3.**

# =============================================================================
# Logging: strip verbose/debug/info logging from release builds
# =============================================================================
# Request URLs, search terms and auth events must not reach logcat in release. Logger.d/i are
# already no-ops at runtime outside debug builds; removing the calls also drops the string
# building at every call site. Warnings and errors are kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class com.rendyhd.vicu.util.Logger {
    public void d(java.lang.String, java.lang.String);
    public void i(java.lang.String, java.lang.String);
}

# =============================================================================
# Kotlin
# =============================================================================
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# =============================================================================
# Navigation Compose (type-safe routes are @Serializable)
# =============================================================================
-keep @kotlinx.serialization.Serializable class com.rendyhd.vicu.ui.navigation.** { *; }

# =============================================================================
# Enum classes
# =============================================================================
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
