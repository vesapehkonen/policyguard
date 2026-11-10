# Device Policy Guard

Minimal Kotlin Device Policy Controller for a Samsung Galaxy S10e (SM-G970U) on Android 12. It requests **fully managed Device Owner** provisioning, keeps the normal Samsung launcher and system apps, and applies the mobile-network, Airplane Mode, and Location policies described below. It includes manual and optional automatic family location SMS sharing. It does not use kiosk mode or Knox.

The current build is **1.4.12** (`versionCode=19`, package `org.example.devicepolicyguard`). The QR and factory-reset instructions below apply only to a new, unprovisioned test device. Update an existing Device Owner installation in place using the same signing key; do not factory-reset or reprovision it for an app update.

## What this experiment proves

Android documents `DISALLOW_CONFIG_MOBILE_NETWORKS` as a Device Owner restriction that globally disallows configuring mobile networks. The app reads the mobile-data **user setting** with `TelephonyManager.isDataEnabled`, records it before applying the restriction, adds the restriction with `DevicePolicyManager.addUserRestriction`, and reads back the admin's restriction. The setting does not by itself turn mobile data on. If mobile data starts off, turn it on before enrollment or before applying the policy. The status is not a test of an actual carrier connection, SIM, signal, or data traffic.

Whether Samsung One UI 4 / Knox 3.8 disables **both** its Settings switch and Quick Settings mobile-data tile must be checked on the physical phone; Android's documented policy does not establish this vendor UI behavior. After provisioning, open Settings → Connections → Data usage → Mobile data, then try the Quick Settings Mobile data tile. Record whether either can change the setting. Return to this app and tap **Refresh status** after each attempt. A restriction reading of Enabled only verifies that the policy was set, not that every Samsung control honors it.

## Build

Requires an installed Java 21 JDK with `javac`, the Android SDK in `~/Android/Sdk`, and Gradle (the included wrapper downloads Gradle 9.6.1 on first use). The project selects the Java 21 compiler even if your default `java` is Java 25. It uses Android Gradle Plugin 9.0.0 and compile SDK 35, and targets API 31 for Android 12.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew assembleDebug
```

For this workspace, `local.properties` already points to `/home/vesa/Android/Sdk`, so `./gradlew assembleDebug` also works directly. On another computer, set `ANDROID_HOME` as above or create `local.properties` with `sdk.dir=/absolute/path/to/Android/Sdk`. If Gradle cannot find Java 21, install a Java 21 JDK or set `JAVA_HOME` to its directory.

Output: `app/build/outputs/apk/debug/app-debug.apk`. The debug manifest has `android:testOnly="true"`. Install it with:

```bash
~/Android/Sdk/platform-tools/adb install -t app/build/outputs/apk/debug/app-debug.apk
```

For a quick **QR enrollment test**, build `./gradlew assembleEnrollment`. This creates `app/build/outputs/apk/enrollment/app-enrollment.apk`, signed with the local Android debug key but **without** `android:testOnly`. Use this APK for the QR download. Keep `~/.android/debug.keystore` if you plan to update an owner installed from this build; losing it changes the signing identity. This enrollment build is for development testing.

For a long-lived QR enrollment, use a signed release APK hosted at a reachable HTTP or HTTPS URL. Configure a persistent signing key before building:

```bash
export DPC_KEYSTORE_FILE=/absolute/path/to/dpc-release.jks
export DPC_KEYSTORE_PASSWORD='your-store-password'
export DPC_KEY_ALIAS='your-alias'
export DPC_KEY_PASSWORD='your-key-password'
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Keep the keystore safe and use the **same** key for all later updates. Without the four signing variables the release APK is unsigned and cannot be provisioned. Do not publish the keystore or its passwords.

## QR Device Owner provisioning

**Factory reset erases the phone. Back up anything needed first.** Device Owner setup must happen during initial setup on an unprovisioned phone. The phone needs a working internet connection to download the APK. Host the exact `app-enrollment.apk` (test) or signed `app-release.apk` (long-lived) at a reachable HTTP or HTTPS URL, then generate the QR and JSON on your computer. For the test build:

```bash
python3 -m pip install -r requirements.txt  # Python 3.11 or newer
python3 generate_qr.py app/build/outputs/apk/enrollment/app-enrollment.apk \
  http://your-server.example/downloads/app-enrollment.apk \
  --png provisioning-qr.png --json provisioning.json
```

The script uses the SHA-256 of the **APK file**, encoded as URL-safe Base64 without padding, as Android's package checksum. Rebuilds and re-signing change those bytes; regenerate the QR after every change to the hosted file. The JSON includes the exact Device Admin component and `PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED=true` to retain Samsung system apps.

1. Factory reset the Galaxy S10e using its normal Settings reset flow.
2. On the initial welcome screen, tap the same spot six times to launch QR enrollment. Connect to Wi-Fi if setup asks for it.
3. Scan `provisioning-qr.png`, follow the managed-device setup prompts, and allow the DPC download and enrollment. Samsung/carrier setup screens may vary.
4. Open **Device Policy Guard** from the normal launcher. Confirm Device Owner = Yes, inspect the recorded mobile-data state before application, the restriction readback, and any errors. Test both Samsung controls as described above.

Do not use `app-debug.apk` for first-time QR provisioning: its `testOnly` flag causes installation to fail. The QR code and APK URL are enrollment artifacts; the app's diagnostic screen works offline once installed. If installation still fails with `app-enrollment.apk`, compare the downloaded APK's SHA-256 with the local file, confirm the URL serves an APK rather than an HTML page, and inspect `adb logcat` for `PackageInstaller` or `ManagedProvisioning` errors if ADB is available.

## ADB development and verification

On an unprovisioned test phone with USB debugging available, ADB can set the same Device Owner without QR. This may fail if setup has completed, accounts exist, or another owner is present. A factory reset and QR enrollment is the intended test path.

```bash
ADB="$HOME/Android/Sdk/platform-tools/adb"
"$ADB" install -t app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell dpm set-device-owner org.example.devicepolicyguard/.GuardAdminReceiver
"$ADB" shell dpm list-owners
"$ADB" shell dumpsys device_policy
"$ADB" shell am start -n org.example.devicepolicyguard/.MainActivity
```

If this ADB provisioning path does not invoke Android's provisioning callback, open **Administrator settings**, authenticate, and turn on the mobile-network restriction switch. The `dpm` command sets ownership but does not run the QR setup UI.

To remove a **test-only** owner on builds that support it:

```bash
"$ADB" shell dpm remove-active-admin org.example.devicepolicyguard/.GuardAdminReceiver
```

This removes Device Owner authority and its policies; it is **not** an APK update. You must provision Device Owner again to regain authority, and after normal setup that can require another factory reset. For a signed release owner, removal may require a factory reset. Never uninstall an active owner as a substitute for updating it.

To update an existing owner, keep `applicationId` and signing certificate unchanged, increase `versionCode` in `app/build.gradle.kts`, build the same variant, and install over it:

```bash
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk
# Or for an enrollment owner, signed with the same local debug key:
"$ADB" install -r app/build/outputs/apk/enrollment/app-enrollment.apk
# Or for a release owner signed with the same release key:
"$ADB" install -r app/build/outputs/apk/release/app-release.apk
```

An update retains Device Owner status and app data. A debug-signed or enrollment-signed build cannot update a release-signed build, or vice versa, because their signing certificates differ. Do not change the stable package ID `org.example.devicepolicyguard` or lose the signing key.

## Family location SMS test

The SMS feature was introduced in version 1.2. The current build keeps the package ID, Device Owner receiver, and enrollment signing configuration. On the already provisioned S10e, update in place:

```bash
./gradlew assembleEnrollment
"$HOME/Android/Sdk/platform-tools/adb" devices
"$HOME/Android/Sdk/platform-tools/adb" install -r app/build/outputs/apk/enrollment/app-enrollment.apk
"$HOME/Android/Sdk/platform-tools/adb" shell dpm list-owners
```

Do **not** run `dpm set-device-owner` again or factory reset for this APK update. Keep the existing `~/.android/debug.keystore`; the update requires the same signing certificate. If `adb install -r` reports a signature mismatch, stop and verify which key signed the installed APK. The update preserves app data.

### First-time sharing setup and permissions

Device Owner provisioning applies the configured device restrictions, but this app does **not** automatically grant its SMS or Location permissions during installation. Automatic sharing is off by default. A trusted administrator must unlock the app, add at least one recipient, choose an interval, and enable **Automatic offline location SMS**.

Before enabling automatic sharing, grant **SMS**, **Precise Location**, and **Background Location** ("Allow all the time"). On Android 12, the background option is in Android's app permission settings, not the normal foreground Location prompt. Alternatively, after PIN authentication in **Settings and restrictions**, turn on **Protect sharing permissions**. As Device Owner, the app then attempts to grant and protect SMS, Precise Location, Approximate Location, and Background Location; check its permission readback for success. If any grant fails, resolve that error before expecting automatic SMS. Manual **Send test SMS** requests SMS and Precise Location when needed; it does not require Background Location.

The phone also needs Location enabled, an Android default SMS SIM, and cellular SMS coverage. These are device conditions, not permissions granted by installing the APK.

After unlocking the app, enter an international number in **Test recipient** on the home screen and tap **Send test SMS**. The number is remembered for the next test. Grant SMS and **Precise location** permissions when prompted. Both manual and automatic sharing use Android's default SMS SIM. No Phone permission or in-app SIM selection is needed. If Android has no default, set one in the phone's SIM settings. **Check current location without sending** requests a new GPS fix; it never uses the last known location. **Send test SMS** obtains another fresh fix, then sends a plain-text message with the fix time, accuracy, battery percentage, and Google Maps link. The result reports carrier send success or a send error; it does not prove recipient delivery. A long message may use multiple SMS parts, with carrier charges per part. Repeated taps are blocked while a send is pending.

### S10e test checklist

1. Verify Device Owner = Yes and the mobile-network restriction still reads Enabled.
2. Enter the test recipient on the home screen, ensure Android has a default SMS SIM, and grant requested SMS and Location permissions.
3. Enable the phone's Location setting. Go outdoors if GPS cannot fix indoors.
4. Tap **Check current location without sending** and check timestamp, coordinates, and accuracy on the home screen.
5. Tap **Send test SMS** once. Check the carrier result on screen and confirm the recipient receives a Google Maps link at the reported coordinates.

Manual test SMS works with or without internet; only automatic fallback waits for an outage. Android may report no fresh GPS fix before the app's 90-second safety timeout. In either case, the app reports an error and sends nothing. SMS requires cellular SMS coverage but does not require mobile internet or Wi-Fi. This manual test does not enable automatic sharing or schedule background messages.

## Airplane Mode and Location policy test (version 1.3.2)

Version 1.3.2 introduced these policies. On first launch after the policy update, if this app is Device Owner, it saves whether this admin already set the Airplane Mode and Location restrictions and whether Location Services were enabled. It then calls `addUserRestriction(DISALLOW_AIRPLANE_MODE)`, `setLocationEnabled(admin, true)`, and `addUserRestriction(DISALLOW_CONFIG_LOCATION)`. The same operation runs during fresh provisioning. The administrator screen now has separate switches for the mobile-network, Airplane Mode, and Location restrictions. Each switch reads back this Device Owner's actual policy after a change. The Location switch also enables Location Services when turned on. The previous policy snapshot remains in app data for update compatibility; the legacy restore operation is no longer exposed in the settings UI. API exceptions and readback mismatches appear on screen.

Test on the physical S10e after applying:

1. Verify the app reports both new restrictions as Enabled and Location Services as Enabled.
2. Swipe down twice. Try **Airplane mode**, return to the app, tap **Refresh status**, and check that Airplane Mode stayed Off. Repeat with the **Location** tile and check Location Services stayed Enabled.
3. Open **Settings → Connections → Airplane mode** and **Settings → Location**. Try both controls, then refresh the app and check actual state again.
4. In Administrator settings, turn each policy switch off and on for a controlled test, and confirm the readback and any error under that switch.

The policy readbacks show Android's reported state; Samsung Quick Settings and Settings behavior must be checked on the device. Record any tile or Settings control that still changes the state.

## Administrator settings and automatic SMS fallback (version 1.4)

Version 1.4.4 introduced a PIN gate for the entire app. Its in-memory unlock expires when the app leaves the foreground or after two minutes without interaction. The current home screen shows sharing status above the manual **Send test SMS** controls; **Activity and detailed status** contains recent local events, SMS attempt results, and full Device Owner readbacks. The event history keeps only the latest 30 status entries and does not store SMS recipients or coordinates. Android's secure-window flag hides these screens from screenshots and recent-app previews. The automatic sharing service continues its opted-in work while the UI is locked, with an ongoing notification.

Version 1.4.12 has `versionCode=19` and retains the application ID, Device Owner receiver, app preferences, and signing configuration. All configuration and policy controls live behind **Administrator settings**. On first use create a 6–12 digit administrator PIN; subsequent visits require it. The verifier is a salted HMAC with a key held by Android Keystore. Five failed attempts cause a one-minute lockout. Change PIN requires the current PIN.

**PIN recovery:** Store the PIN in a trusted password manager. There is no silent PIN reset or Device Owner policy bypass. If the PIN or Keystore key is lost, a trusted administrator with the original signing key and physical custody of the device must prepare and install a narrowly scoped, signed in-place maintenance update that sets a replacement PIN. Verify ownership and the signing certificate before that update, then install the normal app update. The Device Owner component and restrictions stay active throughout. Do not clear app data, uninstall, reprovision, or factory-reset as a PIN recovery step. Without the original signing key, this app cannot offer an in-place recovery path.

Automatic sharing is **off by default**. In Administrator settings, add 1–5 distinct international numbers (for example `+15551234567`). Each add, edit, or removal saves immediately; removing a recipient asks for confirmation. Choose 1, 5, 10, 15, or 30 minutes (default 10); the interval saves when selected. Then enable automatic SMS. Both automatic sharing and manual tests use Android's default SMS SIM. The app requests SMS and precise Location permissions if missing. To run and restart sharing after the app is backgrounded or the device reboots, grant Location access **Allow all the time** in Android app settings or use the PIN-protected permission control. A receiver restarts the opted-in foreground service after a reboot or in-place app update; failed restarts are recorded in app status. The service listens for default-network changes instead of polling every 15 seconds while online; it schedules checks for offline SMS deadlines and saves service status only when its text changes. One quiet, ongoing notification says "Family policy guard active" while the service runs. When validated internet is unavailable, a second ongoing warning asks the user to connect to Wi-Fi or mobile data and opens Android's Internet settings panel when tapped. The warning appears once per outage, stays until internet returns, and then clears automatically. SMS results stay in the app status and activity screens.

The service pauses SMS while Android reports a validated internet connection. After losing validated internet it waits one minute for the one-minute interval, or two minutes for longer intervals, then asks GPS for a **new fix**. A null, old, or timed-out fix causes an error and **no SMS**. The app cancels a pending fix after 90 seconds; Android may end the one-shot request sooner. While offline, it attempts again no sooner than the chosen interval. Each SMS includes timestamp, accuracy, battery percentage, and a Google Maps link. If no default SMS SIM is available, the attempt fails with a visible error. Carrier send and delivery callbacks, missing permissions, SIM failures, and GPS failures appear in the diagnostic screen. Delivery reports depend on carrier support.

The service is best effort: Android or Samsung may stop it, especially because of battery management. Reopening the app restarts it if enabled. The status shows connectivity, the last attempt, the last carrier-confirmed successful send, and any failure reason. The current app has **no online location upload endpoint**; validated internet only pauses SMS. Supply an endpoint and authentication format before adding online location reporting.

Version 1.4.5 makes the one-minute interval apply to the first offline attempt as well. Other intervals retain the two-minute initial wait. The home screen now names the reason automatic sharing is paused, including missing background Location, missing SMS permission, or validated internet. Grant **Allow all the time** Location access in Android app settings before expecting background or rebooted sharing.

Version 1.4.6 adds **Protect sharing permissions** under Administrator settings. After PIN authentication, turn it on to have this Device Owner policy-grant this app's Precise Location, Approximate Location, Background Location, and SMS permissions. Android Settings then shows these grants as administrator-controlled rather than allowing a user to revoke them there. The app reads back each policy and runtime grant and reports any failure. Turn the switch off after entering the PIN to return permission control to Android Settings; existing grants remain until changed there. This does not add access to calls, messages, browsing history, microphone, or camera.

Version 1.4.9 removes in-app SIM selection and `READ_PHONE_STATE`. Both SMS paths use Android's default SMS SIM. This update preserves app data and Device Owner status; any previously saved in-app SIM choice is no longer used.

For a safe device test, first use a test recipient you control. Enable automatic sharing with Wi-Fi/mobile data available and confirm the status says validated internet and SMS paused. Then disconnect validated internet while keeping cellular SMS service and Location enabled. After one minute with the 1-minute interval, or two minutes with a longer interval, allow time for a fresh GPS fix; Android may delay background work. Check the internet warning, in-app status, attempt result, and recipient SMS. Restore internet and confirm the warning clears and subsequent SMS attempts stop. Disable automatic sharing in Administrator settings when finished. Never interpret the last saved fix as a current fix.

## Android references

- [UserManager mobile-network restriction](https://developer.android.com/reference/android/os/UserManager#DISALLOW_CONFIG_MOBILE_NETWORKS)
- [Android 12 provisioning mode and compliance actions](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#ACTION_GET_PROVISIONING_MODE)
- [Provisioning package checksum](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM)
- [TelephonyManager mobile-data setting](https://developer.android.com/reference/android/telephony/TelephonyManager#isDataEnabled())
- [ADB `dpm` commands](https://developer.android.com/tools/adb#dpm)
