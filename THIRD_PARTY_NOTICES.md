# Third-party notices

QuicklyDrop is distributed under GPL-3.0. The dependencies below are compatible with
that license. This is a summary, not a replacement for each project's license text.

## Runtime

### noise-java

- Coordinate: `org.signal.forks:noise-java:0.1.1`
- Origin: <https://github.com/signalapp/noise-java> (fork of `rweather/noise-java`)
- License: MIT
- Use: Noise NK handshake and ChaCha20-Poly1305 transport in `:protocol`.
- Compatibility: MIT is compatible with GPL-3.0.

### Kotlin standard library

- Coordinate: `org.jetbrains.kotlin:kotlin-stdlib` (transitive)
- License: Apache 2.0
- Use: Kotlin runtime.

### Kotlin coroutines

- Coordinate: `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`
- License: Apache 2.0
- Use: `StateFlow` transfer status and app coroutines.

### AndroidX

- Coordinates: `androidx.core:core-ktx`, `androidx.activity:activity-compose`,
  `androidx.lifecycle:lifecycle-runtime-ktx`,
  `androidx.lifecycle:lifecycle-runtime-compose`,
  `androidx.lifecycle:lifecycle-viewmodel-ktx`
- License: Apache 2.0
- Use: Android compatibility, activity result APIs, lifecycle and ViewModel.

### Jetpack Compose

- Coordinates: `androidx.compose.ui:ui`, `androidx.compose.ui:ui-tooling-preview`,
  `androidx.compose.material3:material3`, Compose BOM `2024.10.01`
- License: Apache 2.0
- Use: product UI and themes.
- Debug-only: `androidx.compose.ui:ui-tooling`.

### Android SDK

- Use: compile and runtime platform APIs.
- Distributed under Google's Android SDK terms; not bundled as source.

## Tests

### JUnit 4

- Coordinate: `junit:junit:4.13.2`
- License: Eclipse Public License 1.0
- Use: unit tests in all modules.

### Robolectric

- Coordinate: `org.robolectric:robolectric:4.14.1`
- License: MIT
- Use: Android-dependent unit tests in `:core`, `:app`, `:lab` and `:diag`.

## Build

### Gradle wrapper

- License: Apache 2.0
- Use: build tooling; the wrapper JAR is included in the repository.

### Android Gradle Plugin and Kotlin Gradle plugin

- License: Apache 2.0
- Use: build tooling only.

## Notes

- AndroidX and Compose were added when the product app was created; the earlier
  "pending audit" item is resolved by this file.
- No code from Motorola, Google Quick Share or Apple AirDrop is redistributed.
- Re-run the dependency report before store distribution and confirm that no new
  dependency is GPL-incompatible.
- The Android SDK and Google Play services are not distributed with the APK.
