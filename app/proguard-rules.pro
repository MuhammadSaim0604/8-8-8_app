-keep class com.dayblocks.app.data.model.** { *; }
-keep class com.dayblocks.app.service.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
