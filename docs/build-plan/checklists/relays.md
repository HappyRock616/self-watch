# Relays manual checks (Q07, B19–B25)

For HappyRock: Pixel 5 (Android 17) and the vívoactive 5, already paired and **Connected**. About 40 minutes. Write what you see in the **Result** column and send this file back with the log.

Until the settings screens (C19) exist, turn the relay options and permissions on by hand as the steps say. The app is meant to ask for each permission only when you turn its option on.

## Before you start

- [ ] Install the debug build: `./gradlew :app:installDebug`
- [ ] Start the log and leave it running:
  `adb logcat -s SyncService NotificationRelay NotificationReader NotificationActionRouter MusicRelay MusicControlRouter CallRelay CallControl WeatherReceiver GenericWeather FindPhoneAlert AlarmPlayer TimeZoneWatcher WebRequestRouter ListenerHealth RelayHub`
- [ ] Notification access: **Settings → Notifications → Device & app notifications → Self Watch → Allow notification access**. The log shows `listener connected`.

## 3. Notifications (B19)

| # | Do | Success looks like | Result |
|---|---|---|---|
| 3.1 | Send yourself a message in a chat app (Signal, Messages). | It shows on the watch within a few seconds, with the app's name, sender and text. | |
| 3.2 | Have the same app re-post the same message (or send the same text twice quickly). | Only one copy on the watch. | |
| 3.3 | Start music or a timer (an ongoing notification). | Nothing new on the watch. | |
| 3.4 | Send two messages from the same chat (a group summary appears on the phone). | The messages show; the "2 new messages" summary doesn't. | |
| 3.5 | On the watch, open the message and **Reply** with a canned text. | The reply arrives in the chat on the phone. | |
| 3.6 | On the watch, pick a custom action (for example **Mark as read**). | The action happens on the phone. | |
| 3.7 | On the watch, **Dismiss** a notification. | It goes away on the phone too. | |
| 3.8 | Dismiss a notification on the phone. | It goes away on the watch. | |
| 3.9 | Block an app: `adb shell "run-as app.selfwatch.debug sqlite3 databases/selfwatch.db \"INSERT OR REPLACE INTO setting(key,value) VALUES('notify.apps_denied','org.thoughtcrime.securesms')\""` (use the app's package), then send a message from it. | Nothing on the watch. | |

## 4. Calls (B20)

Grant the permissions by hand: `adb shell pm grant app.selfwatch.debug android.permission.READ_PHONE_STATE` and `android.permission.ANSWER_PHONE_CALLS`, then **Sync now** (the service re-checks on start).

| # | Do | Success looks like | Result |
|---|---|---|---|
| 4.1 | Call the Pixel from another phone. | The watch shows an incoming call, with no number or name. | |
| 4.2 | Hang up from the other phone. | The call alert goes away on the watch. | |
| 4.3 | Call again; **Accept** on the watch. | The call is answered on the phone. | |
| 4.4 | Call again; **Reject** on the watch. | The call ends on the phone. | |
| 4.5 | Set `calls.show_number` = `true` and grant `READ_CALL_LOG`; call again. | The watch shows the number. | |
| 4.6 | Set `calls.show_name` = `true` and grant `READ_CONTACTS`; call from a saved contact. | The watch shows the contact's name. | |
| 4.7 | Revoke `ANSWER_PHONE_CALLS`; call and press Accept on the watch. | Nothing happens; the app doesn't crash; the log says `NO_PERMISSION`. | |

## 5. Music (B21)

| # | Do | Success looks like | Result |
|---|---|---|---|
| 5.1 | Play music in any player. | The watch music controls show artist and title. | |
| 5.2 | Press pause, play, next and previous on the watch. | The player follows each press. | |
| 5.3 | Press volume up and down on the watch. | Phone media volume changes; the watch shows the new level. | |
| 5.4 | Skip tracks quickly on the phone. | The watch catches up to the right track within about a second. | |

## 6. Weather (B22) and find my phone (B23)

| # | Do | Success looks like | Result |
|---|---|---|---|
| 6.1 | Set `weather.enabled` = `true`. In Breezy Weather: **Settings → Widgets & live wallpaper → Send Gadgetbridge data**, pick Self Watch. Refresh Breezy. | The log shows `weather received`; the watch weather widget shows today's temperature and the forecast. | |
| 6.2 | Turn the watch's Bluetooth off and on (or wait for a reconnect). | Weather is still there after reconnecting. | |
| 6.3 | Put the phone on **silent**, lock the screen. On the watch, run **Find my phone**. | The phone rings on the alarm sound and vibrates; a "Your watch is looking for this phone" notification with **Stop**. | |
| 6.4 | Stop it from the watch. | Sound, vibration and notification stop. | |
| 6.5 | Start again, tap **Stop** in the notification. | Everything stops. | |

## 7. Time zone (B24), web (B25) and the "fix it" notice

| # | Do | Success looks like | Result |
|---|---|---|---|
| 7.1 | **Settings → System → Date & time**: turn off automatic time zone and pick another zone. | The watch clock changes to the new zone within a few seconds. Set it back. | |
| 7.2 | Run a watch app that makes a web request (if one is installed). | It gets its answer; the network log shows the request (blocked unless the watch-app web switch is on; requests to 127.0.0.1 are logged as local). | |
| 7.3 | Turn notification access off for Self Watch, then restart the service: `adb shell am force-stop app.selfwatch.debug` and open the app. | A "Watch notifications are off" notice; tapping it opens the notification-access screen. Turning access back on removes it. | |

If a step fails, note what you saw, keep going if you can, and send the log.
