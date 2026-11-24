# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-11-24
- Context: Discovered by Agent while setting up the Android build environment and running the Go key service for the local weather app
- Category: Environment Configuration / Build Methods / Testing Methods / Operations & Deployment
- Instructions:
  - JDK 17 at `/usr/lib/jvm/java-17-openjdk-amd64` (installed via apt `openjdk-17-jdk-headless`); Android SDK at `/opt/android-sdk` (cmdline-tools/latest, platform-tools, platforms;android-34, build-tools;34.0.0).
  - Env vars are exported in `/etc/profile.d/android-sdk.sh`; `local.properties` (gitignored) contains `sdk.dir=/opt/android-sdk`.
  - Build APK: `cd /workspace && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug`; output at `app/build/outputs/apk/debug/app-debug.apk`.
  - Run unit/UI tests: `./gradlew testDebugUnitTest` (Robolectric, no emulator); results in `app/build/test-results/testDebugUnitTest/`.
  - Go key service local dev: `cd server && go run . -config config.json -data /tmp/tianqi-dev -port 8787`; admin page at `/admin` (root redirects there), health at `/api/health`.
  - The host has no KVM, so the Android emulator cannot run; UI verification relies on Robolectric.
  - Gradle 8.7 comes from the project wrapper (auto-downloaded on first build); Android Studio GUI is not installed.
