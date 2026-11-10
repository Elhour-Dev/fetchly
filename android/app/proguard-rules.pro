-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.** { *; }
-keep class org.apache.commons.io.** { *; }
# No debug logs in the release app.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
