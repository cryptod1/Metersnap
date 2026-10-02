# MeterSnap Offline Prof — three-meter field trial

This Android proof build adds a live, slow camera sweep. CameraX supplies preview frames to on-device Latin text recognition; the app does not encode or save a video and has no network permission. A still-photo picker remains available when the live camera cannot be used.

## Trial record

Each of the first three meter checks captures only work fields:

- Manufacturer and model: manufacturer selection controls the model list; Other / enter manually is available when not listed.
- Serial number: editable, with an explicit checked-against-meter tick or unable-to-confirm option.
- Register and reading: reading is entered manually; digit count is selected from a list.
- Safety/tampering observation: completed with no concern visible, possible concern, or not checked / could not check safely. This is not a safety clearance.

The colleague may submit with any field marked unable to confirm. A recognized character is never treated as a confirmed reading. The raw OCR text is hidden in a diagnostic view. No address or customer details are requested.

## Live sweep behavior

- The colleague starts the camera and sweeps slowly from a safe position, pausing over text.
- OCR samples frames at a bounded rate and only the latest queued camera frame is retained while processing catches up.
- Repeated text is surfaced as diagnostic evidence; it is not automatically assigned to the serial or reading fields.
- Passing frames are processed in memory and discarded. The live sweep does not create a video recording.
- If the view is unclear, the colleague can stop, choose a still photo, enter values manually, or mark them unable to confirm.
- A photo scan stops after 20 seconds; a live sweep stops after 60 seconds.

## Boundaries

This is a trial build, not production meter recognition or an all-weather reliability claim. Camera quality, focus, resolution, glare, water, darkness, motion and meter type affect what can be read. The app does not certify safety, prove that no tampering exists, or replace an approved work procedure. Three meters are not enough to validate all phones, meter families or weather conditions.

Trial answers are held in memory for the current app session only. The app has no INTERNET permission, account, cloud API or server submission. This trial does not yet provide durable record export or business-sale data controls.

## Build

The GitHub Actions workflow .github/workflows/build-offline-prof.yml builds a debug APK as an Actions artifact. It is a test build, not a signed release. CameraX dependencies are bundled in the APK; Latin text recognition uses the bundled ML Kit model, so scanning does not require downloading a model at runtime.
