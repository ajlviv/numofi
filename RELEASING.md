# Releasing NumoFi

There are two delivery paths:

- **Testers** get every build from `main` through Firebase App Distribution. This is free on the
  Firebase Spark plan and works with a private repository; testers do not need GitHub access.
- **Google Play** gets a build only when a person runs the release workflow manually and approves
  the `play-production` environment. The Play API cannot create the app or the first release, so
  the one-time setup below must be done by hand.

Both paths are implemented in `.github/workflows/android-release.yml`. Pull requests are checked
by `.github/workflows/ci.yml`.

## Version scheme

`version.properties` holds the human-controlled part:

```properties
versionMajor=1
versionMinor=0
versionCodeOffset=1000
```

Every run of `android-release.yml` stamps:

```text
versionName = <versionMajor>.<versionMinor>.<github.run_number>
versionCode = versionCodeOffset + (github.run_number * 10) + (github.run_attempt - 1)
```

Examples: the first release run gets `1.0.1` / `1010`; its second attempt gets `1.0.1` / `1011`;
the next run gets `1.0.2` / `1020`. A local build with no CI values gets `1.0.0` / `1000`.

Rules:

- `versionCode` must always increase for Play. The run number does that; do not reuse a version.
- Do not add a second workflow that uploads to Play. `github.run_number` is monotonic **per
  workflow file**, so a second uploader would get a different counter and could collide.
- Do not rename, delete, or recreate `android-release.yml` casually: a new workflow can reset the
  counter. If it ever does, raise `versionCodeOffset` above the highest versionCode already
  uploaded.
- A major or minor bump is an edit to `version.properties`. `versionCode` keeps increasing because
  it is based on the run number, not on the semantic version.
- The version is displayed in Settings and stored in backups as `appVersion`. Releases must not
  move `AppDatabase.version` or `BackupSnapshot.FORMAT`; neither is affected by versioning.

## Required GitHub configuration

Repository variables (Settings → Secrets and variables → Actions → Variables):

| Variable | Value |
| --- | --- |
| `FIREBASE_APP_ID` | Firebase Android App ID, `1:...:android:...` |
| `FIREBASE_TESTER_GROUPS` | Comma-separated App Distribution group names, e.g. `testers` |

Repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64 of the upload keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Upload key alias |
| `KEY_PASSWORD` | Upload key password |
| `FIREBASE_SERVICE_ACCOUNT` | JSON key for a service account with Firebase App Distribution Admin |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON key for the Play Developer API service account |

Environments (Settings → Environments):

- `play-internal` — no required reviewers.
- `play-production` — required reviewers and deployment branches restricted to `main`.

The Play secret may be a repository secret or an Environment secret. If it is an Environment
secret, add it to both `play-internal` and `play-production`.

Never commit a keystore, a password, or a service-account JSON. `*.jks`, `*.keystore`, and
`keystore.properties` are already ignored by Git.

## One-time setup

### 1. Upload keystore and Google Sign-In

Generate the upload keystore outside the repository and back it up:

```bash
keytool -genkeypair -v \
  -keystore "$HOME/numofi-upload.jks" \
  -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Get the SHA-1 and register it on the Google Cloud OAuth client for `com.financetracker`:

```bash
keytool -list -v -keystore "$HOME/numofi-upload.jks" -alias upload | grep -i sha1
```

If Play App Signing is enabled, also copy the **app signing certificate SHA-1** from Play Console
→ Setup → App integrity and register it on the same OAuth client. Firebase testers install the
upload-key-signed APK; Play installs the app-signing-key-signed build. Both must be registered, or
Google Sign-In fails only on that delivery path.

Store the keystore and passwords in GitHub:

```bash
base64 -w0 "$HOME/numofi-upload.jks" > "$HOME/numofi-upload.jks.b64"
gh secret set KEYSTORE_BASE64 < "$HOME/numofi-upload.jks.b64"
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
```

For local release builds, create a gitignored `keystore.properties`:

```properties
storeFile=/absolute/path/to/numofi-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Or pass `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` as environment
variables.

### 2. Firebase App Distribution

1. Create a Firebase project on the free Spark plan.
2. Add an Android app with package `com.financetracker` and copy its App ID.
3. Create a testers group (for example `testers`) and add tester emails.
4. Create a service account with the **Firebase App Distribution Admin** role, download a JSON key,
   and store it as `FIREBASE_SERVICE_ACCOUNT`.

The app does not need the Firebase SDK, the Gradle plugin, or `google-services.json` for App
Distribution. Do not add them for this purpose.

Limits: 500 testers per project, 200 per group, 1,000 releases per app retained for 150 days.

### 3. Google Play

1. Confirm `com.financetracker` is available to the Play developer account. If it is not, change
   `applicationId` before the first upload; changing it later creates a new listing and requires a
   new Google OAuth client.
2. Create the app in Play Console and complete the store listing, content rating, data-safety
   form, privacy policy, target audience, and ads declaration.
3. Upload the first AAB manually to **Internal testing** and roll it out. The API cannot create
   the first release.
4. Enable **Google Play Android Developer API** in Google Cloud.
5. Create a service account, download its JSON key, and invite it in Play Console → Users and
   permissions with access to this app and permission to release to testing tracks and production.
6. Store the JSON as `PLAY_SERVICE_ACCOUNT_JSON`.
7. Enroll in Play App Signing and register the app-signing SHA-1 as described above.
8. Run the `play-internal` destination once before any production push. Play returns
   `Precondition check failed` for a first-ever production release.

**Play target API:** new apps and updates must target API 36 since 2026-08-31. The project now
compiles and targets API 36. Firebase distribution is not affected by this requirement.

## Running the workflows

- **Pull request:** `.github/workflows/ci.yml` runs the JVM/Robolectric suite, lint, and a debug
  APK. It uses no secrets.
- **Push to `main`:** `.github/workflows/android-release.yml` runs the suite, builds a signed APK
  and AAB, and distributes the APK to the Firebase group.
- **Manual tester build:** Actions → Android release → Run workflow → `destination=firebase`.
- **Play internal:** Actions → Android release → Run workflow → `destination=play-internal`.
- **Play production:** Actions → Android release → Run workflow → `destination=play-production`,
  then approve the `play-production` environment. Rejecting the approval uploads nothing.

Every run gets a new `versionName` and `versionCode`. The run summary shows both and the commit.

## Tester install note

A Firebase build is signed with the upload key. A Play build is re-signed by Play App Signing, so
Android will refuse to install one over the other. Uninstall the Play copy before installing a
Firebase build. The same signature rule is why the Google Sign-In SHA-1 list must contain both
certificates.

## Local release build

```bash
KEYSTORE_FILE="$HOME/numofi-upload.jks" \
KEYSTORE_PASSWORD=... KEY_ALIAS=upload KEY_PASSWORD=... \
./gradlew :app:assembleRelease :app:bundleRelease \
  -PversionName=1.0.99 -PversionCode=1099 --console=plain
```

Without a keystore the release is unsigned; CI refuses to distribute an unsigned APK.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `KEYSTORE_BASE64 is not configured` | Repository secret is missing or empty. |
| `apksigner verify` fails | One of the four keystore values is missing, so the build was unsigned. |
| Firebase `PERMISSION_DENIED` | Service account role or `FIREBASE_APP_ID` is wrong. |
| Play `Package not found` | The app or its first manual release does not exist in Play Console. |
| Play `Precondition check failed` | Store listing incomplete, or no internal/alpha/beta release yet. |
| Play rejects the target API | The targetSdk 36 migration has not landed. |
| `version code already used` | A run was repeated past the 10-attempt window, or the workflow counter was reset. |
| Testers cannot install | Uninstall the Play-signed copy first; signatures differ. |
| Sign-in fails only on Firebase or only on Play | That certificate's SHA-1 is missing from the OAuth client. |
