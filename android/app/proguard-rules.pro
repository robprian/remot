# WebRTC JNI classes must be kept.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlinx coroutines
-dontwarn kotlinx.coroutines.**

# System-bound service components. They are referenced BOTH from the manifest
# AND by class-name string in ServiceStatus, so R8 must never rename or strip
# them — otherwise Android cannot bind/enable the Accessibility service and
# Notification listener in release builds.
-keep class com.robrion.remot.host.RemoteInputService { *; }
-keep class com.robrion.remot.host.RemotNotificationListener { *; }
-keep class com.robrion.remot.services.ServiceStatus { *; }

# GitHub release checker (org.json-driven, kept for safety against shrinking).
-keep class com.robrion.remot.update.** { *; }