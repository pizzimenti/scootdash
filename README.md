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
- The dial runs from 0 to the highest mode's speed limit.
- The sage band is the current mode's limit, and the red band is everything above it.
- The yellow notch marks the limit. The red pointer marks the peak this session; tap the dial to reset it.
- An orange ring lights around the dial while cruise control is on.
- Under the dial are the four riding modes, the scooter's gears 0–3: 🚶‍♀️ Walk, 🏃‍♀️ Run,
  🚴‍♀️ Bike and 🛵 Drive, each with its speed limit. Tap one to switch. Above 5 km/h,
  touch and hold instead, so brushing Walk at speed can't drop you to a few km/h.
- Below that: battery, acceleration and brake levels, trip and odometer, power
  (not reported by this scooter yet), GPS speed (tap it to start the GPS check), the
  scale factor, the raw speed field, and lamps for cruise, zero start, light, lock and
  the mph flag.

**Setup**
- Connection, and the acceleration and brake sliders (sent when you let go).
- Five switches, each labelled with its register number.
- The GPS check, the speed limit per mode, and the password.
- A raw register read/write tool, the display units, diagnostics and **Copy log**.

## Units

A ride log settled what the numbers mean. With the scooter's units switch on:

- **Speed** is mph × 10, in whole mph: 190 means 19 mph. Full-throttle runs against
  limits of 11, 14 and 31 km/h read 70, 90 and 190, which only fits mph.
- **Trip and odometer** count thousandths of a mile, in 0.01 mi steps. The speed
  field, integrated over the ride, matches the trip counter to within 1 %.
- With the switch off, the scooter should send km/h × 10 and thousandths of a km.
  That's untested.

ScootDash 1.0 read speed as km/h × 10, so it showed 62 % of the real speed, and it
read the distances as metres.

## Speed limit per mode

Registers A3, A0, A1 and A2 hold the limit in km/h for Walk, Run, Bike and Drive.
The factory values were 6, 12, 18 and 31. The stock app only ever reads them.

The scooter answers every write with the value it kept:
- Lower values stick.
- Anything above 31 is clamped to 31, the 19 mph the scooter tops out at. Asking
  Drive for 42 while it sat at 14 set it to 31.
- Untested: whether another mode can go above its factory value while staying
  under 31, for example Bike at 25.

Step a value and tap **Write**, standing still. The row shows "waiting…" until the
scooter answers, then what it kept, and "was …" once a value has changed. The app
reads the register again at 0.4 s and 1.5 s, and the log records the write and every
reply. To undo, write the old value.

## GPS check

The GPS check compares the scooter with GPS once a second. For each fix it logs the
GPS Doppler speed and the distance since the last fix, next to the scooter's average
speed and trip-counter change over the same second. Coordinates never go in the log.

A second counts toward the scale factor only when:
- status frames arrived during it, so a Bluetooth dropout can't feed in stale numbers;
- the fix is within 12 m, and 0.5 to 2.5 s after the previous one;
- GPS says at least 2 m/s and the scooter at least about 8 mph (12.5 km/h). Below that,
  rounding to whole mph is too coarse.

The factor is GPS ÷ scooter, so ×0.88 means the scooter reads 14 % high (1 ÷ 0.88 = 1.14).
- **Speed factor:** from the Doppler speed. This is the main one.
- **Distance factor:** from the trip counter. It needs 400 m first, because the
  counter moves in 0.01 mi steps.

Ride a few minutes under open sky and vary your speed. At one steady speed, the
scooter's rounding to whole mph never averages out.

**Save factor** keeps the speed factor. **Correct speed and distance with the saved
factor** then applies it to the dial, the mode limits, trip and odometer.

While the check runs, status frames are logged only when a setting changes, plus a
heartbeat every 30 s, so the GPS lines aren't buried. A CAL line with the running
totals comes every 15 s.

## Copy log

**Copy log** puts the newest 1,500 lines on the clipboard, under a header with the
current state, the limit per mode and the GPS totals. Status frames are logged only
when something other than the two time counters changes, plus a heartbeat every 10 s.

Paste it into a chat. It's usually more than the 65,536 characters a GitHub issue can
hold, so for an issue, save it to a text file and attach that.

## What's known and what's guessed

- **Confirmed:** the cipher, the framing, the mode (register 05), the acceleration and
  brake levels (08/09, echoed in the status frame), the per-mode limits (A0–A3),
  battery %, the speed and distance units above, and both time counters.
- **Guessed:** the names of registers 00 (headlight), 01 (cruise), 02 (lock),
  03 (units) and 07 (zero start). Flip each one and watch the scooter.

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
