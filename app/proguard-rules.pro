# WearTube R8 rules.
#
# Media3, OkHttp and Coil all ship consumer rules, so the app's own needs are
# small. The important ones are below; everything else is safe to shrink.

# Kotlin coroutines internals referenced reflectively by the debugger agent.
-dontwarn kotlinx.coroutines.debug.**

# OkHttp pulls in optional Conscrypt/BouncyCastle/OpenJSSE providers that are
# absent on Wear OS; they are referenced but never used.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 resolves some components by name at runtime.
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.session.** { *; }

# Our DataSource is instantiated through a Factory that R8 can see, but the
# custom wt:// scheme handling is reached only via Media3's reflection paths.
-keep class com.wateruse.weartube.player.RangedDataSource { *; }
-keep class com.wateruse.weartube.player.RangedDataSource$Factory { *; }
-keep class com.wateruse.weartube.player.PlaybackService { *; }
