# Build and signing instructions

Before producing an Android APK or changing package/signing configuration, read [docs/ANDROID_SIGNING.md](docs/ANDROID_SIGNING.md). Reuse the persistent private backup described there for subtitle-check updates. Never commit signing keys or passwords. Verify package ID and certificate before delivering an APK.

# Continuing subtitle work

Before continuing the Android subtitle task, read [docs/CLOUD_HANDOFF.md](docs/CLOUD_HANDOFF.md). The in-progress fixes are on `fix/subtitle-verified-v11`; `main` alone does not contain them. Check the latest Actions run before treating a recorded pending or failed test as resolved.
