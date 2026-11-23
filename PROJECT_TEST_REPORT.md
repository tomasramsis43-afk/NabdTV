# Test report

- **Automated (CI):** unit tests (`testDebugUnitTest`), `assembleDebug`, `assembleRelease` and Android lint run on every push to main. Results for the commits above: green. The new `AdaptiveArtTest` runs in the next CI run.
- **Not tested at all:** anything on a real device or emulator: the startup freeze, the new swipe/left-right navigation, the Back key in the player menus, image quality, TV focus behaviour, the splash screen, scheduling and recording.
- **Not tested:** offline / timeout / retry behaviour, rapid repeated taps, opening a screen before data finishes loading, large or corrupt playlists. These need a device or instrumented tests that do not exist.
- **Not run:** a Kotlin dead-code analysis, a dependency vulnerability scan, a profiler.
