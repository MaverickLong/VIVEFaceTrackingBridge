# VIVE Face Tracking Bridge

A minimal, standalone Android app for VIVE (and other OpenXR) headsets that streams
eye and face tracking data to a PC running [VRCFaceTracking](https://github.com/benaclejames/VRCFaceTracking)
with the [VRCFT-ALVR module](https://github.com/alvr-org/VRCFT-ALVR). It runs as a background
service, so it works alongside Virtual Desktop, Steam Link or any other streaming app.
No PC-side component is required! A small settings app on the headset lets you change the
PC address, switch eye and face tracking separately and pick which streaming apps the bridge
starts with.

The design and the OpenXR face tracking code are derived from [ALVR](https://github.com/alvr-org/ALVR)
(see [LICENSE](LICENSE)). Thank you to the ALVR team for their outstanding work!

## Requirements

- Compatible Headsets:

| Headset                 | Compatibility                     |
| ----------------------- | --------------------------------- |
| VIVE Focus Vision       | ✅ (Tested)                       |
| VIVE Focus 3            | ❓ (Untested but should work)     |
| VIVE XR Elite           | ❓ (Untested but should work)     |
| Pico 4 Pro / Enterprise | ❓ (Untested but API implemented) |

In essence, any OpenXR headset exposing `XR_HTC_facial_tracking`, `XR_FB_face_tracking2`,
`XR_FB_eye_tracking_social` or `XR_BD_facial_simulation` running Android 12 or higher should work.

- PC: VRCFaceTracking with the VRCFT-ALVR module installed. The module listens on UDP
  port 41463 on all interfaces; Windows Firewall must allow inbound UDP to VRCFaceTracking
  (it normally asks on first start).
- Headset and PC on the same network with a good router (you need this for VD anyways).

## Installing with Windows

1. Download `FTBridge-<version>-windows.zip` from the latest
   [release](https://github.com/MaverickLong/VIVEFaceTrackingBridge/releases/latest) and unzip
   it.
2. Enable USB debugging on the headset settings and connect it over USB.
3. Double-click `Setup.cmd`. It fetches adb from Google if needed, waits for the headset,
   detects this PC's address, installs the two apps (the FT Bridge service and FT Bridge
   Settings), exempts the service from battery optimization, grants usage access (to detect
   when Virtual Desktop is in the foreground), enables autostart and starts the service.
   Unplug afterwards; the cable is only needed for setup.
   **This sets up the bridge to run with Virtual Desktop. To use Steam Link or another
   streaming app instead (or as well), open FT Bridge Settings on the headset, see below.**
4. Start VRCFaceTracking on the PC, then use the headset normally. In VRChat the avatar's
   eyes and mouth follow yours through the ALVR module.

### Changing settings on the headset

Quit the streaming app, open **FT Bridge Settings** from the headset's app library, change
what you need and tap *Apply and start*: the PC address, eye tracking, face tracking and gaze
tracking on or off, always forward, or auto-start with Virtual Desktop, Steam Link or a custom
app (package name). The values the PC setup provisioned are shown there. The status area at
the bottom shows what the service is doing (session state, sources, packets sent). Closing the
app, or the headset stopping it when a VR app starts, does not affect the service.

Gaze tracking is off by default: leaving the eye tracker's gaze to Virtual Desktop makes the
bridge compatible with **VD Eye Tracking Foveated Encoding**, and VD's own VRCFT module
supplies the gaze to VRCFaceTracking. Eye tracking (blink, wide, squeeze, eye direction) and
face tracking keep coming from the bridge.

`README.txt` inside the zip has the same steps plus troubleshooting.

## Important Notices

1. **The FT bridge may not work when other apps have the eye / face tracking data.**
   Please turn other apps' eye / face tracking (or eye-tracking based foveated encoding) off
   when using this app to avoid potential compatibility issues.
2. At current stage, the FT bridge does not have auto client discovery and relies on a fixed IP
   address set at installation time. If the PC's IP changes, enter the new one in FT Bridge
   Settings on the headset (no cable needed), or plug in the headset and re-run `Setup.cmd`.
3. The mainstream [VRCFT-ALVR](https://github.com/alvr-org/VRCFT-ALVR) has bugs parsing some specific
   mouth shapekeys. For now I recommend using
   [this VRCFT-ALVR fork](https://github.com/AzumiYura/VRCFT-ALVR/tree/fix_htcftmapping).
   (I will be making my own fork and building ready-to-use binaries soon)
4. This is not official VIVE / Virtual Desktop / VRCFT software, there is strictly no affiliation
   with any of the entities.

## Developer Stuff

Everything below is vibed docs and not reviewed by human.

### Manual setup (developers)

With adb on the PC and both APKs built:

```powershell
tools\provision.ps1 -PcAddress 192.168.1.10 -Autostart
tools\provision.ps1 -PcAddress 192.168.1.10 -Autostart -GateApps VirtualDesktop.Android,com.valvesoftware.steamlinkvr
tools\provision.ps1 -PcAddress 192.168.1.10 -Always -NoFace     # forward eye tracking only, regardless of the app
tools\provision.ps1 -PcAddress 192.168.1.10 -Autostart -Gaze    # also forward the gaze pose (not with VD eye tracking)
```

This does everything `Setup.cmd` does except fetching adb and detecting the address.

### Two apps

The bridge ships as two packages on purpose. The VIVE system force-stops every package that
has a launchable activity whenever a VR app gains focus (its one-foreground-app rule), which
would kill the service each time Virtual Desktop starts. So:

- `dev.maverick.ftbridge` (FT Bridge, `client/app`) holds the foreground service and has
  **no activity at all**; the system leaves it alone.
- `dev.maverick.ftbridge.settings` (FT Bridge Settings, `client/panel`) is the control panel.
  It may be stopped by the system when a VR app starts, which is harmless. It talks to the
  service through a Messenger interface (`ControlService`) guarded by a signature permission,
  so both APKs must be signed with the same key; the settings are stored by the service, and
  the panel shows a live status (session state, sources, packets sent and how many carried
  changed values, i.e. fresh tracker samples). The shared keys live in `client/control`.

The service is monitored with `adb logcat -s ftbridge:*` (a status heartbeat every 5 s while
the bridge runs).

### Settings

| Setting | Default | Notes |
|---|---|---|
| PC address / port | – / 41463 | Where the VRCFT-ALVR module listens |
| Eye tracking | on | The HTC eye expression tracker (`XR_HTC_facial_tracking`, eye): blink, wide, squeeze and the look-direction weights. On Meta and Pico these come with the face expressions. Off: never created. |
| Face tracking | on | HTC lip tracker, Meta and Pico expression trackers. Off: never created. |
| Gaze tracking | **off** | The gaze pose sources: `XR_EXT_eye_gaze_interaction` (combined gaze) and `XR_FB_eye_tracking_social`. Off leaves the eye tracker's gaze to Virtual Desktop, which makes the bridge compatible with VD Eye Tracking Foveated Encoding; VD's VRCFT module supplies the gaze then. On VIVE the gaze action only delivers data to a focused session anyway, so it is normally empty in the background. Note that the VRCFT-ALVR module also derives a gaze from the HTC look-direction weights (`SetEyesHtcParams`), so with the VD module loaded too both write gaze unless the ALVR module is changed to skip it. With gaze, eye and face all off nothing is forwarded. |
| Always forward | off | Ignore the app list and forward whenever the service runs |
| Auto-start with apps | `VirtualDesktop.Android` | The bridge (OpenXR session, polling) only runs while one of these packages, or FT Bridge Settings, is in the foreground; the panel offers Virtual Desktop, Steam Link (`com.valvesoftware.steamlinkvr`) and a custom package. An empty list behaves like always forward. Needs usage access, which `provision.ps1` grants over adb (`appops set dev.maverick.ftbridge android:get_usage_stats allow`); without it the bridge runs always. The VIVE runtime powers the trackers down when no VR app is active anyway. |
| Poll/send rate | 60 Hz | The VIVE trackers sample at 60 Hz |
| OpenXR frame rate | 10 Hz | Independent of the poll rate. The runtime only serves tracker data to a running session, which requires submitting (empty) frames; their rate does not affect the tracker samples. Fewer frames cost less CPU: ~7% of one core at 10 Hz, ~10% at 60 Hz on the Focus Vision. 0 disables frames (no data on VIVE). |
| Autostart | off | Start the service after boot |

Everything can also be set over adb (the keys are in `client/control`), e.g.:

```
adb shell am start-foreground-service -n dev.maverick.ftbridge/.TrackingService \
    -a dev.maverick.ftbridge.START --es host 192.168.1.10 --ei port 41463 \
    --ef rate 60 --ef framerate 10 --ez autostart true --ez gaze false --ez eye true \
    --ez face true --ez always false --es gate VirtualDesktop.Android,com.valvesoftware.steamlinkvr
adb shell am startservice -n dev.maverick.ftbridge/.TrackingService -a dev.maverick.ftbridge.STOP
adb logcat -s ftbridge:*      # status heartbeat every 5 s
```

### Building

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
                                                           #    and client\panel\build\outputs\apk\release\panel-release.apk
cargo build --release -p ftbridge_cli                      # optional diagnostics tool
```

`cargo test` runs the protocol tests on the host.

### Releases (CI)

[`.github/workflows/release.yml`](.github/workflows/release.yml) builds everything on every
push and pull request and uploads the package (the zip with `Setup.cmd` plus the two bare
APKs) as a workflow artifact. A GitHub Release is published only from a version tag:

1. Bump the version in `client/gradle.properties` (`ftbridgeVersion`, used by both apps) and
   `Cargo.toml` (`[workspace.package] version`) to the same value, e.g. `1.1.0`.
2. Commit (`chore: release v1.1.0`), tag it `v1.1.0` and push the tag:
   `git tag v1.1.0 && git push origin main v1.1.0`.

CI refuses tags whose version differs from the two files. `versionCode` is derived from the
version, so every release installs as an upgrade on the headset.

Release builds are signed with a keystore taken from repository secrets
(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`); `tools\make-keystore.ps1` creates one and prints what to set. Without
the secrets each build is signed with a fresh debug key, and `Setup.cmd` reinstalls the apps
when the signature changed (settings are re-applied, nothing is lost).

### Diagnostics

`vrcft-cli` speaks the module protocol from both ends:

```
vrcft-cli listen [port]                 # impersonate the module; prints decoded packets (close VRCFT first)
vrcft-cli send [target] [preset] [hz]   # impersonate the headset; animates blink/jaw/smile on the avatar
```

Point the headset at a different port (e.g. `--ei port 41464`) to verify the Wi-Fi path with
`vrcft-cli listen 41464` while VRCFaceTracking keeps running.

### How it works, and platform notes

- The Rust core (`client/core`) owns a render-less OpenXR session (empty frames, minimal EGL
  context) purely to host the trackers, polls them at the configured rate and encodes the
  result exactly like ALVR's VRCFT sink.
- **VIVE Wave runtime**: it requires the `applicationActivity` passed to `xrCreateInstance`
  to be a real `android.app.Activity` (it calls `WaveRuntime.create(Activity)`, reads the
  window decor view and calls `setContentView`). A background service has none, so the app
  hands it a `HeadlessActivity`: an Activity object that is never started, with a detached
  window. With it the session reaches SYNCHRONIZED and both HTC trackers deliver data while
  another app owns the display.
- VIVE splits eye data across two extensions: `XR_EXT_eye_gaze_interaction` carries only the
  gaze pose, `XR_HTC_facial_tracking` (eye tracker) carries 14 expression weights: blink, wide
  and squeeze per eye plus the look direction (up/down/in/out per eye). There is no brow data;
  the VRCFT-ALVR module emulates brows from wide/squeeze and derives gaze from the
  look-direction weights, so eye gaze works in the background even with gaze tracking off.
  The action-based gaze quaternion is only delivered to a focused session and is therefore
  normally absent anyway.
- Meta and Pico sources are implemented from ALVR's code but untested; those platforms need
  runtime permissions, which `provision.ps1` grants over adb (`pm grant`) since the service
  app has no activity to ask for them.

### Repository layout

```
common/protocol   VRCFT-ALVR wire format (encode/decode, tests)
client/core       Rust core: OpenXR trackers, session, UDP sender, JNI
client/app        FT Bridge: foreground service, control interface, boot receiver (no activity)
client/panel      FT Bridge Settings: the control panel, a separate package
client/control    keys and message ids shared by the two apps
tools/vrcft-cli   protocol diagnostics for the PC
tools/provision.ps1  one-time adb setup
packaging/windows    Setup.cmd and the end-user README
```
