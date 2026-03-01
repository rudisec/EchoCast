# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep application class
-keep class com.rudisec.echocast.EchoCastApplication { *; }

# Keep service classes
-keep class com.rudisec.echocast.EchoCastInCallService { *; }
-keep class com.rudisec.echocast.EchoCastTileService { *; }

# Keep data classes
-keep class com.rudisec.echocast.AudioItem { *; }

# Keep interfaces
-keep interface com.rudisec.echocast.MultiAudioPlayer$OnPlaybackCompletedListener { *; }
