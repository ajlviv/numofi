plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.financetracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.financetracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        // The backup file records which build wrote it, so a later restore can say what
        // produced a file it is being asked to read.
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        // Robolectric resolves real string resources (CategoryResourceParityTest compares
        // them against the Kotlin category tables); Room tests alone never needed this.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Kotlin / Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation Compose
    // The app has no NavHost of its own: screens are switched by Compose state and the whole
    // Scaffold is replaced when a detail opens. This stays pinned because hiltViewModel()
    // comes from hilt-navigation-compose, which resolves an older navigation-compose unless
    // one is named here.
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Google Sign-In (Credential Manager - replaces the deprecated GoogleSignIn API)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Settings persistence (theme, language, selected bank)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Keystore-backed storage for the bank access token
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // App lock. 1.1.0 is pinned deliberately: it is the version whose
    // setAllowedAuthenticators accepts DEVICE_CREDENTIAL, which is what keeps the library
    // supporting device credential on API 28 and below where it is only available combined
    // with a biometric class.
    implementation("androidx.biometric:biometric:1.1.0")

    // PDF statement reading. The Android port of Apache PDFBox, which brings text
    // extraction (including Cyrillic) to the device without a server round trip.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Robolectric runs the Room migration and the DAO queries on the JVM. The connected
    // device refuses to install APKs, so anything needing instrumentation could not be
    // executed here at all.
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // Desktop PDFBox for JVM tests. The app uses the Android port, which cannot be
    // loaded off-device, so the parser itself is kept free of PDFBox types and both
    // libraries are adapted to one small positioned-text interface.
    testImplementation("org.apache.pdfbox:pdfbox:2.0.27")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose UI tests on the JVM, through Robolectric, rather than on a device. The reasoning
    // is the same as for the Room tests above: an instrumented run needs an APK installed and a
    // device attached, so these would only run on the machine that happened to have a phone
    // plugged in. Robolectric resolves real resources and a real composition, so a screen can be
    // driven — a field typed into, a button pressed, the resulting text asserted — as part of the
    // ordinary `testDebugUnitTest` run. `ui-test-manifest` above is what gives a composition its
    // host, and it is on the debug variant, which is the variant Robolectric loads.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
}