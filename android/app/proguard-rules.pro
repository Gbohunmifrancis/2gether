# kotlinx.serialization keeps its own generated serializers; Retrofit interfaces are kept by its bundled rules.
-keepattributes *Annotation*, InnerClasses

# MediaPipe Tasks reaches its native graph and protobuf messages through JNI and reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**

# SignalR parses hub messages with Gson by reflection; event payloads stay JsonElement and go to kotlinx.
-keep class com.microsoft.signalr.** { *; }
-dontwarn com.microsoft.signalr.**
-dontwarn org.slf4j.**
