# Privacy policy

Self Watch is a health, food and smartwatch app that keeps your data on your phone.

## What Self Watch collects

Nothing is collected by the developers. There is no account, no server, no analytics and no crash reporting.

The app stores your health data (weight, measurements, activity, sleep, heart rate, workouts, food diary and settings) **only on your phone**, in the app's private storage. It leaves the phone only when you choose to send it somewhere: an export, a backup to a folder you pick, or Health Connect sync if you turn it on.

## Internet

Every internet feature has its own switch in **Me › Privacy center**, and every request is listed there with its time, address and size. Nothing is sent unless its switch is on; only the features the app needs out of the box start on.

| Feature | What is sent | Default |
|---|---|---|
| Food database download | Nothing about you; downloads the food list (starts by itself the first time you add food) | On, Wi-Fi only |
| Barcode lookups | Only the barcode number, to Open Food Facts, when the food list doesn't have it | Off |
| Watch app web requests | Whatever a Connect IQ app on your watch asks to fetch, to the address it asks for (Garmin servers stay blocked) | On |
| Apps on this phone | Watch apps talking to apps on this phone (127.0.0.1); never leaves the phone | On |
| GPS assistance for the watch | A request for satellite data, only when the watch asks; a recent copy is reused | On |

Weather on the watch comes from a weather app on your phone (for example Breezy Weather). Self Watch passes its forecast to the watch and makes no weather requests of its own; it is on by default and can be turned off in Devices › Weather.

Every 90 days or so a Garmin watch asks to sign in to Garmin Connect. Self Watch never signs in to Garmin and sends nothing for this. If you turn on **Devices › Watch settings › Answer the watch's Garmin sign-in requests itself** (off by default, per watch), Self Watch answers the watch over Bluetooth with a made-up sign-in so weather and GPS help keep working; nothing goes over the internet.

## Permissions

| Permission | Why |
|---|---|
| Bluetooth / nearby devices | Pair and sync with your watch |
| Camera | Scan food barcodes. Pictures are processed in memory and never saved |
| Notifications and notification access | Show sync status; forward phone notifications to the watch if you turn it on |
| Phone, call log, contacts | Show caller names on the watch and answer or reject calls from it, only if you turn it on |
| Health Connect (Android 14+) | Share data with Health Connect, only if you turn it on |
| Internet | The features in the table above |

## Third parties

- **Open Food Facts** receives a barcode number when you look one up. See their [privacy policy](https://world.openfoodfacts.org/privacy).
- **Health Connect** is part of Android; data you share there follows Android's rules.

## Deleting your data

Uninstalling the app deletes everything it stored. Workouts, foods and measurements can be deleted one at a time, and any import can be undone from **Me › Data › Import**.

## Contact

Open an issue on this repository. For security issues, see [SECURITY.md](SECURITY.md).
