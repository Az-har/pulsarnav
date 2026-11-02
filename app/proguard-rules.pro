# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\...\AppData\Local\Android\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep data classes and models
-keep class com.pulsarnav.app.model.** { *; }
-keep class com.pulsarnav.app.protocol.** { *; }
-keep class com.pulsarnav.app.parser.** { *; }
-keep class com.pulsarnav.app.data.** { *; }
-keep class com.pulsarnav.app.maintenance.** { *; }
-keep class com.pulsarnav.app.tracking.** { *; }
-keep class com.pulsarnav.app.telephony.** { *; }
