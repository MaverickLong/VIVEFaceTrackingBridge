FT Bridge - eye and face tracking from a VIVE headset to VRCFaceTracking
========================================================================

What you need
-------------
- A VIVE Focus Vision (eye tracking built in; the face tracker if you have one).
- On this PC: VRCFaceTracking with the "ALVR" module installed
  (https://github.com/alvr-org/VRCFT-ALVR), and Virtual Desktop for streaming.
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

If the avatar's face does not move
----------------------------------
- Check that VRCFaceTracking lists the ALVR module.
- Windows Firewall must allow VRCFaceTracking to receive network data (it
  asks the first time it runs).
- Eye tracking must be enabled and calibrated in the headset settings.
- If this PC got a different network address (new router, different Wi-Fi),
  run Setup.cmd again.
- Do not open the FT Bridge app on the headset while streaming; it is hidden
  by default for a reason (the VIVE system stops background apps that have a
  visible window whenever a VR app starts).

Files
-----
- Setup.cmd, setup.ps1, provision.ps1: the setup.
- FTBridge.apk: the headset app, for manual installation (adb install).
- tools\vrcft-cli.exe: diagnostics tool, see the project page.

Project page, source code and documentation:
https://github.com/MaverickLong/VIVEFaceTrackingBridge
