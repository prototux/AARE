# F-Droid

The owner consents to AARE being submitted to F-Droid (discussion #169). This page is for whoever
submits it, and for each release after that.

## What the repository provides

- **Store listing:** `fastlane/metadata/android/en-US/` (title, short and full description, icon,
  screenshots, and a changelog per version code under `changelogs/`), which F-Droid reads from the
  tagged commit.
- **The version in the build file:** `versionName` / `versionCode` in `app/build.gradle.kts`, raised
  in each release commit. The release workflow refuses a tag that doesn't match them.
- **No update check:** build with `-Paare.updateCheck=false`; F-Droid updates the app itself. The
  check's settings section is hidden too.
- **Nothing secret in the build:** no built-in certificate since 2.0; dependencies come from Google's
  Maven, Maven Central and the Gradle plugin portal only. Licence GPL-3.0-or-later.

## Suggested recipe

Not checked with `fdroidserver` (`fdroid lint`, `fdroid build`): a starting point for the merge
request to fdroiddata, to adjust to what its reviewers ask.

```yaml
Categories:
  - Navigation
  - Multimedia
License: GPL-3.0-or-later
SourceCode: https://github.com/prototux/AARE
IssueTracker: https://github.com/prototux/AARE/issues
Changelog: https://github.com/prototux/AARE/releases

AutoName: AARE

RepoType: git
Repo: https://github.com/prototux/AARE.git

Builds:
  - versionName: 2.0.0
    versionCode: 20000
    commit: v2.0.0
    subdir: app
    gradle:
      - yes
    gradleprops:
      - aare.updateCheck=false

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 2.0.0
CurrentVersionCode: 20000
```

## Worth telling the reviewers up front

- **Certificate download.** *Download certificate* (Advanced > Phone certificates) fetches, only when
  the user taps it, the certificate and key extracted from Google's Android Auto app, from the
  third-party repository iscle/android-auto-certificates. Many factory head units only accept that
  certificate; AARE doesn't ship it. `extract_key.py` (a desktop script, not in the app) gets the
  same from an APK.
- **Root and Shizuku are optional.** Without them the app works with the screen-capture permission
  (music only); with them, music and navigation voice go to their own car channels.
- **Hidden APIs** (through HiddenApiBypass): in the root/Shizuku helpers (audio routing, the app
  launcher, Bluetooth pairing), and to read the turn-by-turn instructions out of navigation apps'
  notifications (RemoteViews).
- **protoc**: the protobuf Gradle plugin fetches `com.google.protobuf:protoc` from Maven Central to
  generate the protocol classes at build time.

## Each release

1. Raise `versionName` and `versionCode` in `app/build.gradle.kts` (versionCode = major * 10000 +
   minor * 100 + patch).
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: a few lines, 500 characters
   at most, plain text.
3. Commit, then tag `v<versionName>` on that commit.
