# Client regressions

Run the optional regression mod with Java 21:

```sh
./gradlew --init-script client-tests.init.gradle runRegressionClient --no-configuration-cache
```

On Windows, use `gradlew.bat`. The test client opens a window, waits for resources to load, runs the checks, writes `build/client-regression/results.txt`, and exits. The Gradle task fails when a check fails. It uses `build/client-regression/run` and does not join or create a world. Release any physical W/A/S/D, Space, Enter, Q and R keys while running it.

Checks cover animation timing and wraparound through all constructors, button transitions and repeat semantics, input cleanup on prepare/removal/restart/game selection, operating system repeats, an empty inventory, focus loss and a missed key release, high Blocktris scores, stable particle expiry, and preparation/reset of all seven games.

These sources and their mod descriptor are loaded only by the init script; the normal build and published jar exclude them. This is an automated regression check, not a substitute for playing the games or testing physical focus changes on every platform.
