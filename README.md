# FinanceTracker - Private Finance Tracking Android Application

## Overview
A private finance tracking Android application with Google Sign-In authentication.
Only authenticated users can access the app. Unauthenticated users are redirected to
the login screen.

## Architecture
- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3
- **DI**: Hilt (Dagger)
- **Database**: Room (SQLite), created from scratch at install — there are no migrations
- **Auth**: Google Sign-In through Credential Manager, with the signed-in uid in DataStore
- **Screen switching**: Compose state in `MainScreen`, not a NavHost
- **Networking**: Retrofit + OkHttp (Monobank sync)
- **Statement reading**: PDFBox-Android for PDF, an XML reader for XLSX, a plain reader for CSV

## Project Structure
```
app/src/main/
├── java/com/financetracker/
│   ├── FinanceTrackerApp.kt          # Application class (Hilt), PDFBox assets
│   ├── LoginActivity.kt              # Google Sign-In entry point (LAUNCHER)
│   ├── MainActivity.kt               # Authenticated main screen
│   ├── ui/
│   │   ├── AppViewModel.kt           # App-wide preferences (theme)
│   │   ├── main/MainScreen.kt        # Bottom bar, screen switching, snackbars
│   │   ├── dashboard/DashboardScreen.kt
│   │   ├── transaction/              # list, detail, add, bond entry form
│   │   ├── bond/BondListScreen.kt
│   │   ├── settings/SettingsScreen.kt
│   │   ├── statement/                # statement import preview
│   │   ├── auth/                     # LoginScreen, AuthGuard, AuthViewModel
│   │   ├── component/                # dropdowns and field labels
│   │   ├── MoneyAmount.kt, TransactionAppearance.kt
│   │   └── theme/Theme.kt
│   ├── data/
│   │   ├── AppDatabase.kt            # Room database and the bank seed
│   │   ├── TransactionDao.kt, BankDao.kt, BondDao.kt, UserDao.kt
│   │   ├── bank/                     # provider registry, Monobank API, sync, token store
│   │   ├── statement/                # CSV/XLSX/PDF readers, parser, import service
│   │   └── settings/SettingsRepository.kt
│   ├── model/                        # entities, domain types, ОВДП arithmetic
│   ├── repository/                   # what the screens talk to
│   ├── util/                         # amount and category formatting
│   └── di/                           # Hilt modules
├── res/
│   ├── values/strings.xml, themes.xml, colors.xml
│   └── xml/network_security_config.xml
└── AndroidManifest.xml
```

## Setup Instructions

### 1. Google Sign-In
1. Create an OAuth 2.0 **Web** client ID in the
   [Google Cloud Console](https://console.cloud.google.com/). It is a public identifier and is
   expected to ship inside the APK; the matching client secret is not needed.
2. Register the SHA-1 fingerprint of the keystore you build with on that client:
   ```
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
3. Put the client ID in `app/src/main/res/values/strings.xml` as `default_web_client_id`.
   Sign-in stays disabled while that string still holds the placeholder.

`app/google-services.json` is left over from an earlier Firebase-based sign-in. Firebase is no
longer a dependency and nothing reads the file, so it can be deleted.

### 2. Build
```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest   # JVM + Robolectric suite
```

## Authentication Flow
1. App launches → `LoginActivity` (LAUNCHER)
2. `LoginScreen` asks Credential Manager for a Google ID token and hands the credential to
   `AuthViewModel`; the credential's id is the uid every local row is scoped to
3. The uid is stored in DataStore, and the profile is cached in the `users` table
4. A signed-in session sends the app on to `MainActivity`, which wraps the UI in `AuthGuard`
   and returns to login if the session goes away — including when the user signs out from
   Settings
5. Signed-in state is observed rather than read once, so signing out tears the UI down
   immediately

## Database
The database is built from its entities when it does not exist and is only ever reopened
afterwards: `AppDatabase` ships no migrations and no destructive fallback. A file written by
an older schema therefore makes Room refuse to open with a message saying a migration was
required but not found, rather than rewriting or discarding somebody's rows. Taking a schema
change means bumping `version` and clearing the app's data (or reinstalling).

## Features
- ✅ Google Sign-In authentication, with the main screen behind an auth guard
- ✅ Statement import from CSV, XLSX and PDF, with the bank detected from the file
- ✅ Monobank sync, with the API token in Keystore-backed storage
- ✅ Transaction list with bank, card, type and date filters plus search
- ✅ Add and delete transactions, hand-entered with a bank and a currency
- ✅ Per-currency income and expense totals, never summed across currencies
- ✅ ОВДП bond trades, with positions folded from the trades at read time
- ✅ Dashboard summary, kept separate from bond value
- ✅ Settings: theme, banks, bank connection, sign out
- ✅ Hilt dependency injection
- ✅ Jetpack Compose UI
