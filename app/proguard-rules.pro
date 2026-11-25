# The Shizuku user service is started by class name in a separate process.
-keep class com.phonecleaner.ai.shizuku.** { *; }
-keep class com.phonecleaner.ai.IShellService { *; }
-keep class com.phonecleaner.ai.IShellService$* { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
