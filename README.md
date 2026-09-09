# WiFi Maxxer

Native Android app in Kotlin and Jetpack Compose Material 3. Android 10+, with root-controlled Wi-Fi and network tuning.

## Root compatibility

The app uses the common `su -c` interface provided by Magisk, KernelSU, APatch, and compatible root managers. It does not detect manager package names, change SELinux policy, or use Shizuku/ADB as an in-app control path. Root is checked automatically when the app opens.

The root check verifies an explicit UID 0 response separately from Wi-Fi command support. Some ROMs omit privileged commands from `cmd wifi help`, so the app also performs a read-only missing-argument check for the low-latency command. The Activity export includes bounded command diagnostics.

KernelSU and similar managers can apply restricted root profiles. WiFi Maxxer needs UID 0 and access to Android's Wi-Fi service, the selected network interface, and the relevant `/proc/sys` files. The app respects a denied or restricted grant.

## Overview

- **WLL (Wi-Fi Low Latency)** replaces the former Gaming name. It invokes Android's framework low-latency override with root. Android documents lower latency as the goal, with possible reductions in throughput, scanning/roaming frequency, and battery life.
- WLL is a direct toggle with no game detection. Off returns Android to automatic behavior.
- The optional foreground service adds connection monitoring, a Restore notification, and a 45-second root watchdog. It is off by default and never reapplies WLL at boot.
- Separate Wi-Fi and mobile-data sections each provide download and upload sliders. They add independent, reversible `tc` police filters to the selected interface. Their 5/1 Mbps floors match the FCC 4G LTE coverage baseline. Wi-Fi ceilings use Android's maximum supported RX/TX link speeds; mobile ceilings use Android's estimated first-hop downstream/upstream bandwidth.
- Cloudflare and Jitter.is buttons open independent browser benchmarks and never change tuning settings.

The bandwidth controls are intended to leave capacity for latency-sensitive traffic while a bulk transfer runs. Start around 85–90% of measured internet throughput; Android's reported maximum or estimate is only the slider ceiling and can differ from the actual bottleneck. Phone-side ingress policing cannot drain queues already built in an access point, carrier, or ISP, so router-side SQM remains more effective for Wi-Fi when available. Each target has its own Apply and Restore action. Limits are removed by Restore, reboot, or an interface reset.

## Basic tuning

Basic contains standard, runtime-only controls considered low risk on most devices. Each control shows its current value, saved original, recommended value, scope, expected effect, and risk. On and Off remain available even when current-value detection is unavailable; rejected commands are reported.

| Control | Recommended | What it does | Main trade-off |
| --- | --- | --- | --- |
| Wi-Fi reachability recovery | On | Lets Android disconnect and recover when the gateway becomes unreachable. | Off can leave traffic stuck on a connected-but-dead AP. |
| Disable Wi-Fi power saving | Off | Keeps the Wi-Fi radio awake between packets. | On may reduce wake delay but increases battery use and can reduce throughput on some firmware. |
| TCP receive-buffer autotuning | On | Lets Linux grow receive buffers within the existing kernel limits for each connection. | Off can severely restrict download throughput, especially on fast or high-latency paths. |

## Advanced tuning

Advanced settings require a separate warning acknowledgement for every change. Most remain runtime-only and are restored from the first captured value. Wi-Fi verbose logging and Qualcomm channel bonding are explicitly marked persistent.

| Control | Recommended | What it does | Why it is advanced |
| --- | --- | --- | --- |
| RX/TX checksum offload | On | Moves supported transport checksum work between the CPU and driver/device. | Driver, VPN, tethering, and encapsulation behavior can differ. |
| TSO/GSO | On | Defers segmentation and processes larger transmit buffers. | Can improve bulk throughput, but dependencies and tunnel paths can break. |
| GRO/Hardware GRO | On | Coalesces compatible receive packets before upper-layer processing. | Can change latency and forwarding/capture behavior; hardware GRO is vendor-specific. |
| TCP auto-corking | On | Coalesces consecutive small writes while a prior packet is queued. | Kernel-global and can trade latency for fewer packets. |
| Disable saved TCP metrics | Off | Stops reuse of cached route metrics for later TCP connections. | Workload-dependent and can make reconnects slower. |
| TCP slow start after idle | On | Times out the congestion window after an idle period. | Off can resume faster but create a burst and loss after the path changes. |
| Balanced receive CPU steering (RPS) | OEM | Distributes receive protocol work evenly across online CPUs. | Can add cross-core interrupts, wake high-power cores, or duplicate hardware RSS. |
| Balanced transmit CPU steering (XPS) | OEM | Assigns online CPUs evenly across transmit queues. | Has no benefit for a single TX queue; OEM cache/IRQ-aware maps can be better. |
| Wi-Fi verbose logging | Off | Enables Android Wi-Fi framework diagnostics at standard level 1. | Persists across reboot, can reduce performance, and can expose sensitive connection details in privileged logs. |
| 1-second RSSI polling | Off | Changes Android's link-stat polling interval to 1000 ms. | Does not directly lower ping and can increase CPU/battery use. Off restores the exact saved OEM interval. |

### Origami network sysctls

The Tuning tab also exposes every network control from Origami Kernel Manager v1.2.3's `net_util.sh`. These controls remain visible on every device; the current-value check reports whether the ROM exposes a path, while Apply lets the kernel accept or reject the requested value. All are device-wide, runtime-only Advanced controls and use a separate exact-value recovery journal.

**Source and attribution:** this addition uses the control list, sysctl paths, and value menus from [Origami Kernel Manager](https://github.com/Rem01Gaming/origami_kernel_manager) by Rem01Gaming, specifically [`share/utils/net/net_util.sh` at commit `49494d9`](https://github.com/Rem01Gaming/origami_kernel_manager/blob/49494d9d72dc64e9337c926be2eb27075fe378ba/share/utils/net/net_util.sh). Origami Kernel Manager is distributed under the [GNU GPL v3](https://github.com/Rem01Gaming/origami_kernel_manager/blob/49494d9d72dc64e9337c926be2eb27075fe378ba/LICENSE).

| Control | Accepted values | Meaning |
| --- | --- | --- |
| TCP congestion control | Algorithms reported by the kernel | Selects the algorithm used by new TCP connections. |
| Maximum SYN backlog | 128–32400, step 2 | Maximum remembered incomplete incoming requests per listener. |
| Keepalive idle time | 128–32400 seconds, step 2 | Idle time before probes on sockets whose apps enabled keepalive. |
| SYN cookies | 0, 1 | Disabled, or fallback when a SYN backlog overflows. |
| TIME-WAIT reuse | 0, 1, 2 | Disabled, global reuse, or loopback-only reuse. |
| ECN | 0, 1, 2 | Disabled, outgoing plus incoming negotiation, or incoming negotiation only. |
| TCP Fast Open | 0, 1, 2, 3 | Disabled, client bit, server bit, or both bits. |
| TCP SACK | 0, 1 | Disabled or enabled selective acknowledgements. |
| TCP timestamps | 0, 1, 2 | Disabled, randomized per-connection offset, or no random offset. |
| BPF JIT hardening | 0, 1, 2 | Disabled, unprivileged users, or all users. |

Each card explains every choice, shows the current and saved original value, identifies the kernel path, and requires the Advanced warning before writing. The app validates numeric ranges and algorithm names, journals the setting before the write, verifies readback, and attempts to restore the previous value if a write fails.

The **Mobile data target** selects whether interface offloads and RPS/XPS address the active Wi-Fi or cellular interface. TCP controls are kernel-global and affect both. Wi-Fi power saving and Qualcomm bonding remain Wi-Fi-only. Android reachability, RSSI, and verbose logging controls belong to the Wi-Fi framework.

RPS and XPS are always shown. The app reads whatever standard queue files a device exposes, saves every OEM mask exactly, and reports a rejected write instead of hiding the controls. On calculates masks from the device's online CPU list and queue count; Off writes zero masks. Restore writes the exact saved OEM masks. These changes are runtime-only and are not reapplied after reboot.

WiFi Maxxer does not apply fixed `tcp_rmem`, `tcp_wmem`, socket maximum, `netdev_max_backlog`, IRQ affinity, CPU governor, or core-online values. Their useful values depend on RAM, bandwidth-delay product, queue structure, CPU cache/IRQ topology, thermal policy, and vendor firmware. Oversizing queues or buffers can consume RAM and increase bufferbloat, while forced CPU policies can increase heat and battery drain.

## Modern Android and vendor features

Android 14+ supports Wi-Fi 7 MLO network selection and exposes vendor MLO policies for default, low latency, high throughput, and low power. The MLO setter is a privileged system API and is not exposed as a portable `cmd wifi` command. WiFi Maxxer therefore reports Wi-Fi 7, TID-to-link mapping, dual-band simultaneous operation, make-before-break roaming, preferred-network offload, WPA3, OWE, and TDLS capabilities without pretending it can safely force them.

## Qualcomm channel bonding

The legacy WiFi Bonding module changes `gChannelBondingMode24GHz` and `gChannelBondingMode5GHz` in `WCNSS_qcom_cfg.ini`. These flags request wider channels.

Channel bonding is classified **High risk / Persistent**. Before the first write, the app copies the complete detected file to `/data/adb/wifimaxxer/bonding/original.ini`, verifies it byte-for-byte, and records the original path, SHA-256 checksum, and ROM fingerprint. It refuses missing keys or ambiguous multiple-file results. Apply stages and validates the edit before copying it to the live file; a failed verification attempts rollback. Restore verifies the saved original and copies it back byte-for-byte. Both operations require a reboot before the driver reloads the file.

Direct editing works only when the rooted ROM permits the real partition to be written. EROFS, AVB policy, or root restrictions can block it. Editing a verified partition can stop Wi-Fi or the device from starting, which is why the app requires an explicit high-risk acknowledgement and keeps the backup after Restore.

## Baselines and recovery

On the first usable launch after installation, the app captures readable Wi-Fi interface, cellular interface, CPU queue masks, global TCP, and Android Wi-Fi framework values. These immutable originals survive app starts, reboots, and updates. A ROM fingerprint or interface mismatch blocks writes so values from another driver session are not restored blindly.

Every write is journaled before execution and readable results are checked afterward. Runtime journals expire when Android's boot count changes because the app never reapplies settings at boot.

## Persistent activity log

The Activity tab is a terminal-style view of `files/logs/wifi-maxxer.log`. Every line has a local timestamp, and new app events and applied changes are appended to the same UTF-8 file across process restarts. Clear removes the previous history and writes a new clear event. Open grants the selected Android text viewer temporary read-only access through `FileProvider`; the app's private file remains at `/data/user/0/com.wifimaxxer/files/logs/wifi-maxxer.log` and is removed if app storage is cleared or the app is uninstalled.

## Build

Open the project in Android Studio, select JDK 17, and install Android SDK 37.

Version 0.13.0 adds the complete Origami Kernel Manager network sysctl menu with value guidance and exact-value recovery. Version 0.12.0 replaced the temporary activity cards with a timestamped persistent terminal log and file viewer. Version 0.11.0 added independent mobile-data download and upload limits alongside the Wi-Fi controls. Functional checks do not claim a performance improvement.

## Primary references

- [Android Wi-Fi low-latency mode](https://source.android.com/docs/core/connect/wifi-low-latency)
- [Android Wi-Fi 7 and MLO](https://source.android.com/docs/core/connect/wifi-7)
- [Android Wi-Fi network selection](https://source.android.com/docs/core/connect/wifi-network-selection)
- [Android WifiManager API](https://developer.android.com/reference/android/net/wifi/WifiManager)
- [Android WifiInfo RX/TX link-speed API](https://developer.android.com/reference/android/net/wifi/WifiInfo)
- [Android NetworkCapabilities bandwidth estimates](https://developer.android.com/reference/android/net/NetworkCapabilities)
- [AOSP traffic-control utilities](https://android.googlesource.com/platform/frameworks/libs/net/+/refs/heads/main/common/device/com/android/net/module/util/TcUtils.java)
- [Linux traffic control](https://man7.org/linux/man-pages/man8/tc.8.html)
- [Linux traffic policing](https://man7.org/linux/man-pages/man8/tc-police.8.html)
- [IETF active queue management recommendations](https://www.rfc-editor.org/rfc/rfc7567.html)
- [FCC 4G LTE coverage-map baseline](https://help.bdc.fcc.gov/hc/en-us/articles/6047425308187-Formatting-Mobile-Broadband-Availability-Coverage-Maps)
- [AOSP WifiShellCommand](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiShellCommand.java)
- [AOSP privacy guidance for logging](https://source.android.com/docs/security/best-practices/privacy)
- [Linux checksum offloads](https://docs.kernel.org/networking/checksum-offloads.html)
- [Linux segmentation offloads](https://docs.kernel.org/networking/segmentation-offloads.html)
- [Linux TCP sysctls](https://docs.kernel.org/networking/ip-sysctl.html)
- [Linux BPF JIT sysctls](https://docs.kernel.org/admin-guide/sysctl/net.html)
- [Origami Kernel Manager network implementation](https://github.com/Rem01Gaming/origami_kernel_manager/blob/49494d9d72dc64e9337c926be2eb27075fe378ba/share/utils/net/net_util.sh)
- [Linux and Android-kernel RPS, RFS, and XPS scaling](https://android.googlesource.com/kernel/common/+/refs/tags/android16-6.12-2025-06_r35/Documentation/networking/scaling.rst)
- [Qualcomm FastConnect](https://www.qualcomm.com/wi-fi/products/fastconnect)
- [Qualcomm FastConnect 7800](https://www.qualcomm.com/wi-fi/products/fastconnect/fastconnect-7800)
- [MediaTek Dimensity 9400](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9400)
- [Broadcom BCM4398 Wi-Fi 7 announcement](https://investors.broadcom.com/news-releases/news-release-details/broadcom-announces-availability-worlds-first-wi-fi-7-ecosystem)
- [Cloudflare speed test](https://speed.cloudflare.com/)
- [Jitter.is](https://jitter.is/)
