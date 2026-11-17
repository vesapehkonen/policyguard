# Policy Guard

The Android Device Policy Controller is in [`android/`](android/). See the [Android README](android/README.md) for build, signing, and provisioning instructions.

From the repository root, build the enrollment APK with:

```bash
cd android
./gradlew assembleEnrollment
```

The output is `android/app/build/outputs/apk/enrollment/app-enrollment.apk` relative to the repository root. `backend/` is reserved for future work and is currently empty.
