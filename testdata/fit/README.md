# FIT test data

## synthetic/

Files made by the `fit` module's own `FitWriter` (generators in `fit/src/test/kotlin/app/selfwatch/fit/Synth.kt`). Other modules may use them in tests. Do not edit by hand: `SyntheticSamplesTest` fails if they differ from the generators. Regenerate with:

```
./gradlew :fit:test -Dfit.writeSamples=true
```

| File | What it holds |
|---|---|
| `monitor_day.fit` | One local day (UTC−6) plus an hour: cumulative steps/kcal/distance every 15 min (walking and a run), heart rate every 2 min with `timestamp_16`, stress, respiration, SpO2, resting HR |
| `sleep_night.fit` | 450 one-minute sleep stages, sleep score 82, SpO2, HRV, skin temperature |
| `hrv_status.fit` | 90 five-minute HRV values and a nightly summary |
| `activity_run_gps.fit` | 30-minute GPS run, 1 s records, session totals (4.5 km) |
| `activity_compressed_bigendian.fit` | Same kind of run with compressed timestamp headers and big-endian record definitions |
| `oddities_devfields.fit` | Developer fields (typed via `field_description` and untyped), strings, arrays, an unknown message |
| `weight.fit` | Output of `WeightFileWriter`: two weigh-ins |

## real/ (task H02, waiting)

Copy HappyRock's vívoactive 5 files here over USB (the watch shows up as a drive), keeping the watch's folder names:

```
real/Monitor/*.fit    from GARMIN/Monitor   (at least 2 full days, plus the day before)
real/Sleep/*.fit      from GARMIN/Sleep     (at least 2 nights)
real/HrvStatus/*.fit  from GARMIN/HRVStatus (if present)
real/Metrics/*.fit    from GARMIN/Metrics   (if present)
real/Activity/*.fit   from GARMIN/Activity  (a walk, a run with GPS, an indoor workout)
```

Then write `real/expected.properties` with what the **watch** (or Garmin Connect) shows, for example:

```
zone=America/Chicago
# steps per local day
steps.20261120=8432
# sleep minutes per stage and score, per sleep file name (without .fit)
sleep.S1234567.deep=74
sleep.S1234567.light=231
sleep.S1234567.rem=88
sleep.S1234567.awake=12
sleep.S1234567.score=81
# workout distance (m) and time (s, the watch's "Time"), per activity file name
workout.A7654321.distanceM=5012
workout.A7654321.durationSec=1834
```

Also note the resting heart rate and HRV night average the watch showed for the same days.

Finally remove `@Ignore` from `fit/src/test/kotlin/app/selfwatch/fit/GoldenRealFilesTest.kt` and run `./gradlew :fit:test`. Real files contain personal health data: decide before committing them whether they go into the public repository.
