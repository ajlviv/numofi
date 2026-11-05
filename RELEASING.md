# Releasing NumoFi

Releases are tag-driven:

- **Pull requests** run the fast path only: compile, JVM/Robolectric tests, and lint.
- **A person starts a release** by running the `Create release tag` workflow with `patch`, `minor`,
  or `major`. That workflow is the only place the version is bumped.
- **A tag triggers the release build.** The `Release` workflow checks out exactly that tag, builds
  and signs the APK/AAB, uploads them, and creates a GitHub Release.
- **Google Play is optional.** Run the `Release` workflow manually on an existing tag and choose
  `internal` or `production`.

This separates the fast PR flow from the deliberate release flow. Every release artifact is built
from an immutable tag, so it is traceable to one commit and one `appVersionName`.

## Version scheme

`gradle.properties` is the single source of truth:

```properties
appVersionName=1.0.0
```

The **tag must match** `appVersionName`. A `v1.4.2` tag refuses to build if
`gradle.properties` says `1.4.1`; no artifact can be published with the wrong version.

`versionCode` is derived, not stored:

```text
versionCode = major * 1_000_000 + minor * 1_000 + patch
```

Examples: `1.0.0` → `1000000`, `1.4.2` → `1004002`, `2.0.0` → `2000000`. The formula is
monotonic for SemVer, so Play never sees a version code go backwards. The derived code must stay
in Play's `1..2100000000` range; `Create release tag` and the Gradle build both refuse a version
outside it.

Rules:

- The release tag is immutable. Never move, delete, or recreate a published tag. If a release is
  wrong, fix forward with the next patch/minor tag.
- The only writer of `appVersionName` is `Create release tag`. Do not hand-edit it as part of a
  feature PR.
- The version is displayed in Settings and stored in backups as `appVersion`. Releases must not
  move `AppDatabase.version` or `BackupSnapshot.FORMAT`; neither is affected by app versioning.
- Old `version.properties` is gone. Run numbers and attempt numbers no longer participate in app
  versioning.

## Branch strategy

- `main` is always releasable, protected, and requires the PR checks.
- `feature/*` branches are short-lived and never change `appVersionName`.
- `release/vX.Y.Z` is optional, only for a maintenance line; its fixes must still land on
  `main` before tagging.
- `vX.Y.Z` tags are created by `Create release tag`, not by feature branches.

## Guardrails

- Pull requests only compile, test, and lint. They never assemble an APK/AAB, run
  `bundleRelease`, sign, or touch Play.
- Only `Create release tag` writes `appVersionName`; feature branches never bump it.
- Release keystores are never committed; CI reads them from GitHub secrets.
- The tag build refuses a tag whose commit is not on `main`.
- Toolchain versions are pinned: Gradle wrapper `8.11.1`, JDK `17.0.21`, `compileSdk` 36, build-tools
  `36.0.0`, and exact Action versions.

## Required GitHub configuration

Secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_TOKEN` | Fine-grained PAT with **Contents: write**; if `main` is protected, it must be allowed to bypass that protection. |
| `KEYSTORE_BASE64` | Base64 of the upload keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Upload key alias |
| `KEY_PASSWORD` | Upload key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Optional; JSON key for the Play Developer API service account |

`RELEASE_TOKEN` is required because a tag pushed with the automatic `GITHUB_TOKEN` does **not**
start another workflow. The PAT is what makes the tag push trigger the release build.

Environments (Settings → Environments):

- `release` — required reviewers, and deployment branches/tags restricted to `main` and
  `v*.*.*`. Both tag creation and the tag build deploy to this environment, so each release is
  approved twice: once before the tag is created and once before artifacts are built.
- `play-internal` — no required reviewers; deployment branches/tags restricted to `main` and
  `v*.*.*`.
- `play-production` — required reviewers; deployment branches/tags restricted to `main` and
  `v*.*.*`. The release workflow also restricts production uploads to the `production` input, but
  the environment approval is the real gate.

Store `RELEASE_TOKEN` and the four keystore values as Environment secrets on `release`, so a
workflow on an unprotected branch cannot read them. Store `PLAY_SERVICE_ACCOUNT_JSON` on both
`play-internal` and `play-production`; a repository secret would be readable by any workflow, so
use one only as a deliberate simplification.

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
→ Setup → App integrity and register it on the same OAuth client. GitHub Releases carry the
upload-key-signed APK; Play installs the app-signing-key-signed build. Both must be registered, or
Google Sign-In fails only on that delivery path.

Create the `release`, `play-internal`, and `play-production` environments first, then store the
keystore and passwords on `release`:

```bash
base64 < "$HOME/numofi-upload.jks" | tr -d '\r\n' > "$HOME/numofi-upload.jks.b64"
gh secret set KEYSTORE_BASE64 --env release < "$HOME/numofi-upload.jks.b64"
gh secret set KEYSTORE_PASSWORD --env release
gh secret set KEY_ALIAS --env release
gh secret set KEY_PASSWORD --env release
```

Create the release token and store it on `release`:

```bash
gh secret set RELEASE_TOKEN --env release
```

The token needs `Contents: write` on this repository. If `main` rejects direct pushes, grant the
token permission to bypass that rule; the workflow commits the version bump directly to `main`.
If `main` requires signed commits, the bot's commit must be signed or that rule relaxed — a PAT
does not make the commit signed.

For local release builds, create a gitignored `keystore.properties`:

```properties
storeFile=/absolute/path/to/numofi-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Or pass `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` as environment
variables.

### 2. Google Play

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
6. Store the JSON as `PLAY_SERVICE_ACCOUNT_JSON` on both Play environments.
7. Enroll in Play App Signing and register the app-signing SHA-1 as described above.
8. Upload once to `internal` before any `production` run. Play returns `Precondition check failed`
   for a first-ever production release.

**Play target API:** new apps and updates must target API 36 since 2026-08-31. The project now
compiles and targets API 36.

## Release runbook

1. Merge the feature PR to `main` through the normal PR pipeline.
2. Go to **Actions → Create release tag → Run workflow**, on `main`.
3. Select `patch`, `minor`, or `major`, then approve the `release` environment.
4. The workflow updates `appVersionName`, commits it to `main`, creates the annotated
   `vX.Y.Z` tag, and pushes both atomically.
5. The tag push starts **Release**. Approve the `release` environment again. It checks out the
   tag, verifies the tag is on `main` and matches `appVersionName`, runs the tests, builds and
   signs the APK/AAB, and creates the GitHub Release.
6. Download the APK/AAB from the GitHub Release, or from the workflow run's
   `numofi-release-vX.Y.Z` artifact.

If tag creation fails before the push, fix the cause and re-run it. If the tag exists and the
`Release` build fails, re-run that workflow for the same tag; an existing GitHub Release is
updated in place with `--clobber`, from the same immutable tag.

### Optional Play upload

The tag push does **not** upload to Play. To publish an already-tagged build:

1. Go to **Actions → Release → Run workflow**, from `main` or the tag itself.
2. Enter the existing tag, for example `v1.4.2`.
3. Choose `play_track`: `internal` or `production`.
4. Approve the `release` environment for the rebuild, then `play-production` if prompted.

This rebuilds from the same tag, verifies the version again, updates the GitHub Release, and then
uploads the AAB. It does not move the tag.

### Downloading a build

- **Release APK/AAB:** GitHub → Releases → the `vX.Y.Z` release.
- **Workflow artifact:** the tag run's `numofi-release-vX.Y.Z` artifact.

Pull request CI does not upload an APK; it compiles, tests, and lints only.

## Tester install note

A GitHub Release APK is signed with the upload key. A Play build is re-signed by Play App Signing,
so Android will refuse to install one over the other. Uninstall the Play copy before installing a
GitHub Release build. The same signature rule is why the Google Sign-In SHA-1 list must contain
both certificates.

## Local release build

Edit `gradle.properties` locally if you are testing a version, then:

```bash
KEYSTORE_FILE="$HOME/numofi-upload.jks" \
KEYSTORE_PASSWORD=... KEY_ALIAS=upload KEY_PASSWORD=... \
./gradlew :app:assembleRelease :app:bundleRelease --console=plain
```

Without a keystore the release is unsigned; the tag workflow refuses to publish an unsigned APK.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `Create release tag` refuses to start | `RELEASE_TOKEN` is missing, cannot push to protected `main`, or `main` requires signed commits. |
| `RELEASE_TOKEN is required` | Create the PAT and store it on the `release` environment. |
| No `Release` run after the tag appears | The tag was pushed with `GITHUB_TOKEN`; the manual workflow must push it with `RELEASE_TOKEN`. |
| Release is waiting for approval | Approve the `release` environment; tag creation and the tag build each ask once. |
| `Tag vX.Y.Z does not point to a commit on main` | The tag was made from a feature branch. Tags are immutable; create a new tag from `main`. |
| The `Release` run is skipped | Re-run **Release** from `main` (or a `vX.Y.Z` tag), not a feature branch. |
| `Tag vX.Y.Z does not match ... appVersionName` | The tag and `gradle.properties` disagree. Fix forward with a new tag; do not move the old one. |
| `Tag vX.Y.Z already exists` | Tags are immutable. Bump again; never delete or reuse a tag. |
| `KEYSTORE_BASE64 is not configured` | The `release` environment secret is missing or empty. |
| `apksigner verify` fails | The build was not signed; check all four keystore values and the decoded `KEYSTORE_FILE`. |
| Play `Package not found` | The app or its first manual release does not exist in Play Console. |
| Play `Precondition check failed` | Store listing incomplete, or no internal/alpha/beta release yet. |
| Play rejects the target API | The targetSdk 36 migration has not landed. |
| Testers cannot install | Uninstall the Play-signed copy first; signatures differ. |
| Sign-in fails only on GitHub Releases or only on Play | That certificate's SHA-1 is missing from the OAuth client. |
