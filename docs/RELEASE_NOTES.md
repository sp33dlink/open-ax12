open-ax12 v0.2 is the first version tested on a real RadioMaster AX12. It fixes several problems that only showed up on the radio and makes the Home screen cleaner.

**Status: early alpha.** Check every model on the ground with the receiver connected and the propeller removed before you fly with it. Firmware updates stay in the stock app.

### Fixes found on the radio
- **Stick calibration:** the calibration screen now follows the radio's phases (centre first, then end points). Before, it could store a wrong centre, leaving every axis at 0 … +100 %. If you calibrated with v0.1, calibrate again.
- **Model copies:** duplicating, importing or restoring a model created a copy without channels and mixes. Copies are now complete, a model file is checked before it is loaded, and an incomplete configuration is never written to the radio.
- **Radio settings:** stick mode, RF module and low-voltage alarm were shown wrong (RF "Off", 0.0 V) because the SDK's system-settings read is unreliable. They are now read from the model configuration; implausible values block any write, turning RF off needs a confirmation, and an SDK bug that overwrote curve 0 when writing these settings is worked around.
- **Live values:** sticks, switches and channels are shown in percent (they were raw PWM / SDK values), the stick-mode mapping and switch direction (up = +100 %) are correct.
- **Deleting models** works again; a one-step hold-to-delete button with a red border.
- **After clearing the app data** the radio's model is recognised in the library instead of creating a new "Model-NNN".

### New
- **Close the stock app automatically** before connecting (setting, on by default), with a button and a hint if connecting takes long.
- **Start on boot** (setting, on by default) and a one-page **onboarding** on first start.
- **Home:** bigger stick pads with the trims on the border, borders light up only at exactly ±100 % (reveals missing calibration), widgets first in the centre card, the SP button row like the stock app, new switch colours (up turquoise, mid orange, down gray).

Install `open-ax12-v<version>.apk` from the assets below with `adb install -r` (force-stop the stock app first). If an older open-ax12 with a different signature is installed, uninstall it once first.
