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
- ✅ Backup to a user-chosen Google Drive folder, after each import and each change
- ✅ Hilt dependency injection
- ✅ Jetpack Compose UI

## Backup
The user picks a folder in Drive once, and the app keeps a single
`finance-tracker-backup.json.gz` in it, overwritten in place. Drive's own revision history is
what provides older copies; the app keeps no history of its own, because preserving one would
mean reading the file back before every write.

The `.gz` is gzip, not zip: one file, one stream, and no dependency. The JSON underneath is
unchanged — a snapshot is still produced and consumed as text everywhere above the storage
layer, and only the store compresses. A few years of a few banks is a couple of megabytes of
JSON, and since an upload is requested on *every* change the user makes, that is tens of
megabytes of mobile data a day spent recording that a purchase was added. The JSON repeats
itself heavily (every row repeats the same key names, and each bank-assigned `externalId` is a
SHA-256), which is the shape gzip is good at: the same file compresses about 6x. Reading
sniffs the first two bytes rather than trusting the extension, so **older uncompressed backups
still restore** — the previous `finance-tracker-backup.json` is left alone in the folder rather
than overwritten or deleted, and a restore reads either. The one real cost is that the file
can no longer be opened in a text editor.

Decompressing a file the user picked out of a shared Drive is bounded: expansion past 64 MB is
refused as not-a-backup, since a compressed file can produce output orders of magnitude larger
than its input and the file is not this app's to trust.

Access is the Storage Access Framework, not the Drive API: there is no OAuth consent screen
and no app registration. The grant is persisted, so it survives a restart but not a
reinstall, and it is released again when the user turns backup off or points it at a
different folder. The file already in Drive is never deleted by either.

The upload is a logical export rather than a copy of the SQLite file, so it survives a schema
change: transactions for the signed-in account, plus every bank, bond and trade, which are
device-wide rather than per-account.

Restore is a **merge, never a replace**. It adds whatever the chosen file holds that is not
already here, and deletes nothing, so the same file can be restored twice and a restore never
throws away rows written since the file was taken. What counts as already here is the bank's
own `externalId` where the row has one, and the row's content where it does not — a
hand-entered row carries no id, and matching it by content is what stops every repeat of a
restore from doubling the rows the user typed in. A file whose rows were all present reports
that nothing was added rather than a count that reads as a success.

Rows are inserted without their file ids and matched to whatever ids this device gave them, so
a bond trade's link to its cash transaction survives the renumbering. Banks already present
keep the name they have here, because every stored row's `searchText` bakes in the bank name
as it was when that row was written and the two must not disagree.

A file taken for a different signed-in account is refused whole. Banks, bonds and trades have
no account column, so such a file carries a second account's bond positions with no way to
tell them apart from this one's, and the only safe reading is to take none of it.

The file is versioned (`BackupSnapshot.FORMAT`, currently 2) and a v1 file still restores. v2
dropped the per-transaction `userId` in favour of one `uid` at the root; the column is still
in the database. A file from a newer app is refused rather than partly read. See
`docs/plans/2026-09-30-google-drive-backup-design.md`.

Uploads are asked for explicitly by the five places that write to the database, and
coalesced by a conflated channel, so a burst of changes produces one extra upload rather
than one per row. A change that writes nothing — an import of only duplicates, a failed
rename, a move past the end of the list — asks for nothing.
