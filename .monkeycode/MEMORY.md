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
- Date: 2026-11-15
- Context: Discovered by Agent while setting up the native Android development environment and build pipeline for the local weather app
- Category: Environment Configuration / Build Methods / Testing Methods
- Instructions:
  - JDK 17 is installed at `/usr/lib/jvm/java-17-openjdk-amd64`; Android SDK at `/opt/android-sdk` (platform-tools, platforms;android-34, build-tools;34.0.0), env vars also exported in `/etc/profile.d/android-sdk.sh`.
  - Build APK: `cd /workspace && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug`; output at `app/build/outputs/apk/debug/app-debug.apk`.
  - Run unit/UI tests: `./gradlew testDebugUnitTest` (Robolectric, no emulator required); the UI test renders a real screenshot to `/tmp/android_ui_screenshot.png`.
  - The host has no KVM, so the Android emulator cannot run; UI verification relies on Robolectric with `GraphicsMode.NATIVE`.
  - The environment is headless; Android Studio GUI is not installed.
  - Lunar calendar code is plain Java and can be verified outside Android: compile `LunarCalendar.java` together with a test class via `javac`/`java`.
  - Gradle 8.7 is installed at `/opt/gradle/gradle-8.7`; the project also ships a Gradle wrapper.
