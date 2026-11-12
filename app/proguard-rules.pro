# Release builds ship with minification disabled (see app/build.gradle.kts).
# These rules keep the app working if you turn R8 on.

# Ktor server + CIO engine
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.zeroinstall.relay.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.zeroinstall.relay.**$$serializer { *; }

# Room entities / WorkManager workers are referenced reflectively
-keep class com.zeroinstall.relay.sync.AutoSyncWorker { *; }
