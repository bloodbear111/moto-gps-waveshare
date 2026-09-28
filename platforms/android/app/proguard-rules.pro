# JNI entry points are resolved by name from Java_* symbols; keep the Kotlin
# mirror classes whose fields the native layer reads/writes reflectively.
-keep class io.github.bloodbear111.motogps.protocol.MotoSnapshotInput { *; }
-keep class io.github.bloodbear111.motogps.protocol.MotoInboundBuffer { *; }
-keep class io.github.bloodbear111.motogps.protocol.MotoMapSceneInput { *; }
-keep class io.github.bloodbear111.motogps.protocol.MotoMapRoadInput { *; }
-keep class io.github.bloodbear111.motogps.protocol.MotoMapBuildingInput { *; }
-keep class io.github.bloodbear111.motogps.protocol.MotoMapPointInput { *; }
-keep class io.github.bloodbear111.motogps.navigation.MotoNavSnapshotBuffer { *; }
-keep class io.github.bloodbear111.motogps.navigation.MotoNavCommand { *; }
-keepclasseswithmembernames class * { native <methods>; }
