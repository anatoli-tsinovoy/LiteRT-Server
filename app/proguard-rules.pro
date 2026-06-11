# Keep LiteRT-LM SDK Java/Kotlin entry points and native bindings intact.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Ktor server uses service loading and coroutine internals that R8 may not see statically.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# OkHttp/Okio optional platform integrations.
-dontwarn okhttp3.**
-dontwarn okio.**

# SLF4J's concrete binding is optional on Android for this app.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Kotlin serialization generated serializers are referenced by plugin-generated code.
-keepclassmembers class **$$serializer { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**
