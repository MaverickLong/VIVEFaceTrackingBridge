FT Bridge - eye and face tracking from a VIVE headset to VRCFaceTracking
========================================================================

What you need
-------------
- A VIVE Focus Vision (eye tracking built in; the face tracker if you have one).
- On this PC: VRCFaceTracking with the "ALVR" module installed
  (https://github.com/alvr-org/VRCFT-ALVR), and Virtual Desktop or Steam Link
  for streaming.
- Headset and PC on the same Wi-Fi network.
- A USB cable, once, for the setup.

Setup (once)
------------
1. On the headset, enable USB debugging: in the VIVE Manager phone app select
   the headset and turn on Developer mode, or on the headset open
   Settings > Advanced.
2. Connect the headset to this PC with the USB cable.
3. Double-click Setup.cmd and follow the messages. When the headset asks
   whether to allow USB debugging, put it on and tap Allow.
4. Unplug the cable. Done.

Using it
--------
- Start VRCFaceTracking on the PC, then use Virtual Desktop on the headset as
  usual. Your avatar's eyes and mouth follow yours.
- FT Bridge starts by itself whenever Virtual Desktop is running and stops
  when it isn't. It also comes back after a reboot.

Changing settings on the headset
--------------------------------
The setup installs two apps: FT Bridge (the background service, it has no
window) and FT Bridge Settings. To change something:
1. Quit the streaming app (Virtual Desktop / Steam Link).
2. Open "FT Bridge Settings" from the headset's app library.
3. Change what you need and tap "Apply and start":
   - PC address, if this PC's network address changed.
   - Eye tracking / Face tracking on or off. (The bridge does not touch the
     gaze data Virtual Desktop uses, so VD Eye Tracking Foveated Encoding
     keeps working alongside it.)
   - Precise gaze and pupil size: only turn this on if VRCFaceTracking uses
     the VRCFT-ViveBridge module instead of the stock ALVR module.
   - Always forward, or auto-start with Virtual Desktop, Steam Link or any
     other app (enter its package name).
   - The status area at the bottom shows what the service is doing.
4. Close it and start the streaming app again. The service keeps running in
   the background; the settings app may be closed by the headset when a VR
   app starts, which is normal.

If the avatar's face does not move
----------------------------------
- Check that VRCFaceTracking lists the ALVR module.
- Windows Firewall must allow VRCFaceTracking to receive network data (it
  asks the first time it runs).
- Eye tracking must be enabled and calibrated in the headset settings.
- If this PC got a different network address (new router, different Wi-Fi),
  enter it in FT Bridge Settings on the headset, or run Setup.cmd again.
- In FT Bridge Settings, "gate" shows whether the bridge is active and
  "packets" should count up while you stream.

Files
-----
- Setup.cmd, setup.ps1, provision.ps1: the setup.
- FTBridge.apk: the headset service, for manual installation (adb install).
- FTBridgeSettings.apk: the settings app for the headset.
- tools\vrcft-cli.exe: diagnostics tool, see the project page.

Project page, source code and documentation:
https://github.com/MaverickLong/VIVEFaceTrackingBridge
