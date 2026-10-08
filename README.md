# ScootDash

An unofficial dashboard and test bench for "365Bluetooth" scooters, the ones the
stock 365Scooter app drives. It's built from a decoded Bluetooth capture of that app.
Kotlin, no Gradle, no AndroidX. The only runtime dependency is the Kotlin stdlib.

- Application ID and Kotlin namespace: `io.github.pizzimenti.scootdash`
- Repository: `github.com/pizzimenti/scootdash`

## Install

1. On the phone, download `scootdash.apk` from
   [Releases](https://github.com/pizzimenti/scootdash/releases/latest) and open it.
   Allow "install unknown apps" for the app you opened it from (Chrome, Files).
2. On first launch, allow **Nearby devices**. The app first tries the address from
   the original capture, `00:00:00:01:84:0B`; use **Find scooter** for any other scooter.
3. **Find scooter** scans for anything named `365Bluetooth…`. Scanning also asks for
   Location, and Location has to be switched on.
4. Close the stock 365Scooter app first. The scooter only takes one connection.

## Screens

Swipe between the two panels. On a wide screen they sit side by side.

**Dash**
- The dial runs from 0 to the highest gear's top speed.
- The sage band is the current gear's limit, and the red band is everything above it.
- The yellow notch marks the limit. The red pointer marks the peak this session; tap the dial to reset it.
- An orange ring lights around the dial while cruise control is on.
- Below the dial: battery, acceleration and brake levels, trip and odometer,
  power (not reported by this scooter yet), both time counters, the link rate,
  and lamps for cruise, zero start, light, lock and the mph flag.

**Setup**
- Connection, gear 0–3, and the acceleration and brake sliders (sent when you let go).
- Five switches, each labelled with its register number.
- Password, and the top-speed experiment.
- A raw register read/write tool, the display units, and **Copy log**.

## The top-speed experiment

Registers A3/A0/A1/A2 hold the top speed in km/h for gears 0/1/2/3. The capture
read them as 6/12/18/31; 31 km/h (19.3 mph) matches that scooter's 19 mph display cap. The stock app only
ever reads them. Here you can step a value and write it. The app reads it back at
0.4 s and 1.5 s, and the log records the write and both read-backs. Do it with the
wheel off the ground. To undo, write the old value; the screen shows "was …" after
a change.

## Copy log

**Copy log** puts the newest 600 lines on the clipboard, under a header with the
current state. Status frames are logged only when something other than the time
counters changes, plus a heartbeat every 10 s. Paste it into an issue when you report
what you saw.

## What's known and what's guessed

- **Confirmed from the capture:** the cipher, the framing, gear (register 05), the
  acceleration and brake levels (08/09, echoed in the status frame), the per-gear
  top speeds (A0–A3), battery %, trip and odometer in metres, and both time counters.
- **Guessed:** the names of registers 00 (headlight), 01 (cruise), 02 (lock),
  03 (units) and 07 (zero start). Flip each one and watch the scooter.
- **Assumed:** the speed field is km/h × 10. If the dial disagrees with the scooter's
  own display, the log will show the raw numbers.

## Build (no Gradle)

You need a JDK 17+, `kotlinc` 2.x, Android build-tools (`aapt2`, `zipalign`,
`apksigner`, plus `d8`, or `dx` as a fallback), an `android.jar` (API 23+), and python3.

```
ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar \
BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0 \
./build.sh                # -> build/scootdash.apk
```

`build.sh` signs with `keystore/scootdash-debug.jks` (password `android`) and creates it
on the first build.
Keep it, so rebuilt APKs install as updates. It's in `.gitignore`, so keep a copy
outside the repo; without it, `build.sh` makes a new key, and you'll have to uninstall
the old app before the new one will install. When `d8` is missing, the build runs
`tools/check_indy.py`. That check proves no Kotlin-stdlib code path needing Java's
invokedynamic is reachable, because the old `dx` can't desugar it.

`test/ProtoTest.kt` replays the captured traffic (`test/capture_fixture.txt`)
through the protocol code:

```
kotlinc src/main/kotlin/io/github/pizzimenti/scootdash/Proto.kt test/ProtoTest.kt -d /tmp/pt
java -cp /tmp/pt:$KOTLIN_HOME/lib/kotlin-stdlib.jar ProtoTestKt test/capture_fixture.txt
```

## Credits

Barlow and Barlow Condensed by Jeremy Tribby are used under the SIL Open Font
License 1.1 (`src/main/assets/fonts/OFL-Barlow.txt`).
