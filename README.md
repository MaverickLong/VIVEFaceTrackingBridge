# VIVE Face Tracking Bridge

A minimal, standalone Android app for VIVE (and other OpenXR) headsets that streams
eye and face tracking data to a PC running [VRCFaceTracking](https://github.com/benaclejames/VRCFaceTracking)
with the [VRCFT-ALVR module](https://github.com/alvr-org/VRCFT-ALVR). It runs as a
background service, so it works alongside Virtual Desktop or any other streaming app.

The design and the OpenXR face tracking code are derived from [ALVR](https://github.com/alvr-org/ALVR)
(see [LICENSE](LICENSE)); nothing else of ALVR is used.

```
headset: FT Bridge service ──(UDP, VRCFT-ALVR datagrams, Wi-Fi)──▶ PC: VRCFaceTracking + ALVR module ──▶ VRChat
```

No PC-side component is required: the headset sends the module's own wire format directly.

## Requirements

- Headset: VIVE Focus Vision (tested), or any OpenXR headset exposing
  `XR_HTC_facial_tracking`, `XR_FB_face_tracking2`, `XR_FB_eye_tracking_social` or
  `XR_BD_facial_simulation` (untested).
- PC: VRCFaceTracking with the VRCFT-ALVR module installed. The module listens on UDP
  port 41463 on all interfaces; Windows Firewall must allow inbound UDP to VRCFaceTracking
  (it normally asks on first start).
- Headset and PC on the same network.

## Install and set up (one-time, over adb)

1. Build or download `app-release.apk` (see Building).
2. Connect the headset over USB with developer mode enabled and run:

   ```powershell
   tools\provision.ps1 -PcAddress 192.168.1.10 -Autostart
   ```

   This installs the APK, exempts it from battery optimization, grants usage access (to detect
   when Virtual Desktop is in the foreground), saves the PC address and starts the service.
   `-Autostart` makes it start after every boot. The USB cable is not needed afterwards.
3. Start VRCFaceTracking on the PC, then use the headset normally. In VRChat the avatar's
   eyes and mouth follow yours through the ALVR module.

Alternatively open the **FT Bridge** app on the headset, enter the PC address and tap Start.
The app shows a live status (session state, sources, packets sent and how many carried changed
values, i.e. fresh tracker samples). Because the headset only
runs one app in the foreground at a time, the status panel is for setup and diagnostics; the
service keeps running when you switch to another app.

## Settings

| Setting | Default | Notes |
|---|---|---|
| PC address / port | – / 41463 | Where the VRCFT-ALVR module listens |
| Poll/send rate | 60 Hz | The VIVE trackers sample at 60 Hz |
| OpenXR frame rate | 10 Hz | Independent of the poll rate. The runtime only serves tracker data to a running session, which requires submitting (empty) frames; their rate does not affect the tracker samples. Fewer frames cost less CPU: ~7% of one core at 10 Hz, ~10% at 60 Hz on the Focus Vision. 0 disables frames (no data on VIVE). |
| Only while app runs | `VirtualDesktop.Android` | The bridge (OpenXR session, polling) only runs while this package, or the FT Bridge panel, is in the foreground; empty runs it always. Needs usage access, which `provision.ps1` grants over adb (`appops set dev.maverick.ftbridge android:get_usage_stats allow`); without it the bridge runs always. The VIVE runtime powers the trackers down when no VR app is active anyway. |
| Autostart | off | Start the service after boot |

Everything can also be set over adb, e.g.:

```
adb shell am start-foreground-service -n dev.maverick.ftbridge/.TrackingService \
    -a dev.maverick.ftbridge.START --es host 192.168.1.10 --ei port 41463 \
    --ef rate 60 --ef framerate 10 --ez autostart true --es gate VirtualDesktop.Android
adb shell am startservice -n dev.maverick.ftbridge/.TrackingService -a dev.maverick.ftbridge.STOP
adb logcat -s ftbridge:*      # status heartbeat every 5 s
```

## Building

Prerequisites (Windows; other hosts are similar):

- Rust with the Android target and cargo-ndk:
  `rustup target add aarch64-linux-android` and `cargo install cargo-ndk`
- Android SDK with platform 31 and build-tools 36.0.0, and an NDK (any recent one, found
  automatically under `$ANDROID_HOME/ndk`)
- A JDK 17–23 in `JAVA_HOME` (Gradle 8.11 does not run on newer JDKs, including the one
  bundled with current Android Studio)

```powershell
client\build-native.ps1                                   # Rust core + OpenXR loader -> jniLibs
client\gradlew.bat -p client assembleRelease               # -> client\app\build\outputs\apk\release\app-release.apk
cargo build --release -p ftbridge_cli                      # optional diagnostics tool
```

`cargo test` runs the protocol tests on the host.

## Diagnostics

`vrcft-cli` speaks the module protocol from both ends:

```
vrcft-cli listen [port]                 # impersonate the module; prints decoded packets (close VRCFT first)
vrcft-cli send [target] [preset] [hz]   # impersonate the headset; animates blink/jaw/smile on the avatar
```

Point the headset at a different port (e.g. `--ei port 41464`) to verify the Wi-Fi path with
`vrcft-cli listen 41464` while VRCFaceTracking keeps running.

## How it works, and platform notes

- The Rust core (`client/core`) owns a render-less OpenXR session (empty frames, minimal EGL
  context) purely to host the trackers, polls them at the configured rate and encodes the
  result exactly like ALVR's VRCFT sink.
- **VIVE Wave runtime**: it requires the `applicationActivity` passed to `xrCreateInstance`
  to be a real `android.app.Activity` (it calls `WaveRuntime.create(Activity)`, reads the
  window decor view and calls `setContentView`). A background service has none, so the app
  hands it a `HeadlessActivity`: an Activity object that is never started, with a detached
  window. With it the session reaches SYNCHRONIZED and both HTC trackers deliver data while
  another app owns the display.
- The VRCFT module derives gaze from the HTC eye expression weights, so eye gaze works in the
  background. The action-based `XR_EXT_eye_gaze_interaction` quaternion is only delivered to a
  focused session and is therefore normally absent.
- Meta and Pico sources are implemented from ALVR's code but untested; those platforms need
  the runtime permissions the app requests on first launch.

## Repository layout

```
common/protocol   VRCFT-ALVR wire format (encode/decode, tests)
client/core       Rust core: OpenXR trackers, session, UDP sender, JNI
client/app        Android app: control panel, foreground service, boot receiver
tools/vrcft-cli   protocol diagnostics for the PC
tools/provision.ps1  one-time adb setup
```
