# Shizuku starts the UserService in another process by class name.
-keep class org.opengamepad.screen.ScreenControlService { *; }
-keep class org.opengamepad.IScreenControl { *; }
-keep class org.opengamepad.IScreenControl$* { *; }

# Shizuku and the hidden-API helper use reflection internally.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.**
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**
