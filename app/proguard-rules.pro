# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\...\AppData\Local\Android\sdk/tools/proguard/proguard-android.txt

# Retain line numbers & metadata for readable crash stacktraces while allowing full R8 shrinking/optimization
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

-keep class com.pulsarnav.app.BuildConfig { *; }

-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Preserve ViewBinding inflate/bind entry points when referenced reflectively
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(...);
}

# Preserve Android entry points
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.service.notification.NotificationListenerService
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Optional desktop AWT classes referenced in some ZXing helpers
-dontwarn java.awt.**
-dontwarn javax.imageio.**
