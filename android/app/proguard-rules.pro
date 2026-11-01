# kotlinx.serialization keeps its own generated serializers; Retrofit interfaces are kept by its bundled rules.
-keepattributes *Annotation*, InnerClasses

# MediaPipe Tasks reaches its native graph and protobuf messages through JNI and reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**

# MediaPipe logs through Flogger, which finds its platform and backend by class name at runtime
# (Platform$LazyHolder -> "com.google.common.flogger.backend.system.DefaultPlatform"). Renamed by R8, the lookup fails,
# Flogger throws "No logging platforms found", and the pose model can't load in release builds.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# SignalR parses hub messages with Gson by reflection; event payloads stay JsonElement and go to kotlinx.
-keep class com.microsoft.signalr.** { *; }
-dontwarn com.microsoft.signalr.**
-dontwarn org.slf4j.**
