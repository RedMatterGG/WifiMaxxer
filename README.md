# WiFi Maxxer — root edition

Native Android app in Kotlin and Jetpack Compose Material 3. Android 10+, with superuser access through `su` for all tuning.

## Root compatibility

Uses the shared `su -c` interface provided by Magisk, KernelSU, APatch and compatible managers, without package-name detection or assuming that `su` is visible as a normal file. It tries `/system/bin/su`, PATH and conventional fallback locations on launch failure; the same launcher serves the toggle and optional watchdog. A denied root process is not retried through alternate managers. Root-manager policy is respected.

Root verification checks an explicit UID-0 response separately from Wi-Fi capability detection. Some ROMs omit privileged commands from `cmd wifi help`: a read-only invocation without the required argument confirms command parsing before any profile change. The root panel distinguishes authorization, missing controls and execution failures. Activity report export includes the selected executable, command exit status and bounded stdout/stderr.

If your manager uses custom app profiles, WiFi Maxxer needs UID 0 and permission to access the Wi-Fi service. Granting a restricted profile can still block tuning. The app does not change SELinux rules or bypass a denied grant. A manager implementing the common interface is supported by design, but all manager/version/device combinations cannot be guaranteed.

## Implemented

- Actual Wi-Fi connection details and framework-reported hardware capabilities.
- A simple **Gaming** toggle: on applies the global framework low-latency override; off returns it to automatic mode. No game detection, app list or usage-access permission.
- **Foreground service** is optional and off by default. With it off, applying the profile is a one-shot root command. Closing the app does not restore the profile, and no background process is required.
- With the service on, a notification provides Restore, Wi-Fi disconnection ends the session, and a root watchdog restores automatic behavior after 45 seconds without a heartbeat. Each watchdog Wi-Fi command has an 8-second timeout. Restore the active profile before changing the service option.
- Recovery state is saved before applying. Failed commands trigger a restore attempt, and unresolved recovery remains visible. Successful one-shot applications are remembered for the current boot without automatically restoring them when the app reopens.
- Ten gateway ICMP probes bound to the Wi-Fi interface, average/p95 latency, successive-sample jitter, packet loss, and a session baseline.
- Session activity and user-initiated diagnostic export.

No telemetry or remote backend. Location permission is optional; Android may hide SSID without precise permission and enabled location services. Link speed is not internet throughput. A router blocking ICMP produces missing replies, not proof of a broken connection.

## Current boundaries

Install the APK, connect to Wi-Fi, tap **Grant root access**, and approve the request in your installed root manager. Leave **Foreground service** off for apply-and-leave behavior, or turn it on before enabling Gaming for watchdog protection. Manual Restore and benchmarks work in either mode. Notifications are needed only for the optional service's visible notification controls.

The Gaming profile does not modify vendor files, driver files, radio power, antenna settings, country settings, boot scripts, or any other low-level Wi‑Fi stack configuration. The exact system change is a root-level framework toggle only:

- Apply: `/system/bin/cmd wifi force-low-latency-mode enabled`
- Restore: `/system/bin/cmd wifi force-low-latency-mode disabled`

This flips Android's built-in Wi‑Fi low-latency mode flag for the current framework state. When disabled, the app returns control to the normal framework Wi‑Fi lock behavior. It does not snapshot or restore a different app's previous optimizer settings; it turns the framework low-latency override on or off and clears forced high-performance mode when restoring. Avoid concurrent tuning tools. Firmware command acceptance is checked, including failure messages returned with exit code zero. Actual latency gains are not guaranteed.

Without the service there is deliberately no ongoing connectivity check, heartbeat, automatic disconnect restore or notification. The override lasts until Restore, reboot or a framework reset. The toggle remembers the last successful application for this boot; it is not a live getter and cannot detect changes made by another tool. Nothing is reapplied at boot. With the service, process termination or heartbeat expiry requests recovery, but forcibly killing the root shell or an unresponsive firmware can prevent it; use Restore or reboot if needed.

Driver tuning, automated A/B optimization, throughput testing and boot extensions are not implemented. No vendor file, radio power, country setting or antenna configuration is modified. Wi-Fi 7 support does not imply MLO controls are available. Results and logs last for the activity session; the service preference and recovery state persist.

## Build

Open this directory in Android Studio, select its bundled JDK, and install Android SDK 37. Use the Gradle wrapper:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Version 0.2.1 was tested on a Xiaomi 24069PC21G (peridot).

## Platform references

- [AOSP Wi-Fi shell commands](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android13-release/service/java/com/android/server/wifi/WifiShellCommand.java)
- [AOSP forced-mode and restore behavior](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiLockManager.java)
- [KernelSU app-profile restrictions](https://kernelsu.org/guide/app-profile.html)
- [Magisk command-line tools](https://github.com/topjohnwu/Magisk/blob/master/docs/tools.md)
