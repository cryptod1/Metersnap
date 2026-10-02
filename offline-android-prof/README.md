# MeterSnap Offline Prof — Android OCR test build

This is a separate native Android test app for the next MeterSnap milestone. It takes a full-size photo with the phone's camera app or opens a saved image, then runs bundled Latin text recognition locally on the device.

## What this build proves

- Camera capture and saved-photo selection feed one bounded scan.
- OCR is bundled into the APK, so it is available offline after installation.
- The app has no `INTERNET` permission and makes no cloud or API calls.
- A scan stops after 20 seconds and reports a recoverable error instead of spinning forever.
- OCR output is shown as unverified text; the colleague must confirm it.

## What it does not claim

This is an offline OCR foundation, not the finished production Prof. It does not yet classify meter models, reliably separate serial numbers from readings, interpret registers, or certify safe/tampered conditions. OCR accuracy must be measured against real electricity and gas meters in glare, darkness, rain, blur, obstruction, and awkward angles. When the text is absent or uncertain, the product must say so and let the colleague enter it manually.

## Build

The GitHub Actions workflow in `.github/workflows/build-offline-prof.yml` builds `app-debug.apk` and publishes it as an Actions artifact. The APK is a test build, not a signed release.
