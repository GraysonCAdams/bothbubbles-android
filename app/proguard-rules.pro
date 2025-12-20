# BlueBubbles Android ProGuard Rules

# Keep Room entities
-keep class com.bothbubbles.data.local.db.entity.** { *; }

# Keep Moshi adapters
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# Keep Retrofit interfaces
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep generic signatures for Retrofit
-keepattributes Signature

# Keep Socket.IO
-keep class io.socket.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializable classes
-keep,includedescendorclasses class com.bothbubbles.**$$serializer { *; }
-keepclassmembers class com.bothbubbles.** {
    *** Companion;
}
-keepclasseswithmembers class com.bothbubbles.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# YouTube Player library
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.** { *; }
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.** { *; }
