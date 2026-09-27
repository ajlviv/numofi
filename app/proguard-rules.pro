# ProGuard rules for FinanceTracker
# Google Services
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Google Sign-In
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.android.gms.common.api.** { *; }

# Retrofit / OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Room
-keep class com.financetracker.data.** { *; }

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepclassmembers class kotlinx.coroutines.CoroutineExceptionHandler {
    <init>(...);
}

# Gson
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.financetracker.model.** { *; }