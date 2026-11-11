# Keep JNI-linked names and the manifest-created component factory stable.
-keep class com.pandora.core.AppFactory { *; }
-keep class com.pandora.core.AppFactory$DATA { *; }
-keep class com.pandora.core.CreatorProxy { *; }
-keep class org.lsposed.hiddenapibypass.LSPass { *; }
