# FinanceTracker - Private Finance Tracking Android Application

## Overview
A private finance tracking Android application with Google Sign-In authentication.
Only authenticated users can access the app. Unauthenticated users are redirected to
the login screen.

## Architecture
- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3
- **DI**: Hilt (Dagger)
- **Database**: Room (SQLite)
- **Auth**: Firebase Auth + Google Sign-In
- **Navigation**: Navigation Component (Compose)
- **Networking**: Retrofit + OkHttp
- **Image Loading**: Coil

## Project Structure
```
app/src/main/
├── java/com/financetracker/
│   ├── FinanceTrackerApp.kt          # Application class (Hilt)
│   ├── LoginActivity.kt              # Google Sign-In entry point
│   ├── MainActivity.kt               # Authenticated main screen
│   ├── auth/
│   │   ├── AuthViewModel.kt          # Auth state ViewModel
│   │   └── LoginScreen.kt            # Google login UI
│   ├── ui/
│   │   ├── dashboard/DashboardScreen.kt
│   │   ├── transaction/
│   │   │   ├── AddTransactionScreen.kt
│   │   │   ├── TransactionListScreen.kt
│   │   │   └── TransactionDetailScreen.kt
│   │   ├── main/MainScreen.kt        # Bottom nav host
│   │   ├── settings/SettingsScreen.kt
│   │   └── theme/Theme.kt
│   ├── data/
│   │   ├── AppDatabase.kt            # Room database
│   │   ├── TransactionDao.kt
│   │   └── Converters.kt
│   ├── model/
│   │   └── Model.kt                  # Data classes
│   ├── repository/                    # Data repositories
│   ├── di/                            # Hilt modules
│   └── navigation/
│       └── AppNavigation.kt          # Navigation graph
├── res/
│   ├── values/strings.xml, themes.xml, colors.xml
│   └── xml/auth_strings.xml          # Google Client ID
└── AndroidManifest.xml
```

## Setup Instructions

### 1. Replace placeholders
- **app/build.gradle.kts** → Replace `YOUR_GOOGLE_WEB_CLIENT_ID` with your actual Web Client ID
- **app/google-services.json** → Download from Firebase Console and replace
- **app/src/main/res/xml/auth_strings.xml** → Update Web Client ID

### 2. Firebase setup
1. Create a project in [Firebase Console](https://console.firebase.google.com/)
2. Add Android app with package name `com.financetracker`
3. Enable Google Sign-In in Firebase Auth
4. Download `google-services.json` into `app/`

### 3. Google Cloud Console
1. Create OAuth 2.0 Web Client ID
2. Add the SHA-1 fingerprint from your debug keystore:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
3. Put the Web Client ID in `auth_strings.xml` and `build.gradle.kts`

### 4. Build
```bash
./gradlew assembleDebug
```

## Authentication Flow
1. App launches → `LoginActivity` (LAUNCHER)
2. `AuthViewModel` checks current auth state
3. If authenticated → navigate to `MainActivity`
4. If not → show Google Sign-In button
5. `MainActivity` wraps UI in `AuthGuard` — redirects to login if session expires

## Features
- ✅ Google Sign-In authentication
- ✅ Auth-guarded navigation
- ✅ Transaction CRUD (Room database)
- ✅ Dashboard with income/expense summary
- ✅ Transaction list with filtering
- ✅ Add/Edit/Delete transactions
- ✅ Settings screen
- ✅ Hilt dependency injection
- ✅ Jetpack Compose UI
- ✅ Navigation Component