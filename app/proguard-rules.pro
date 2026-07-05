# Keep SDK/JNA names stable because the native layer and JNA bindings can look
# them up reflectively, but still allow R8 to optimize method bodies.
-keep,allowoptimization class com.hcusbsdk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * implements com.sun.jna.Callback { *; }
-dontwarn com.sun.jna.**

# Release builds should not contain verbose per-frame SDK callback logs.
# Keep warnings/errors so field failures are still diagnosable.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
