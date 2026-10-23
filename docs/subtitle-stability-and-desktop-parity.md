# Subtitle stability + desktop parity patch

This patch set focuses on the Android subtitle/translation pipeline.

## Stability fixes

- Apply the normal subtitle-size setting to the in-memory libass translation overlay.
- Prevent HTML/WebVTT markup such as `<font>` from leaking into ASS/libass output.
- Do not consume translated events before the overlay renderer is ready.
- Resume translation prefetch correctly after backward seeks and after prefetch is re-enabled.
- Improve non-UTF-8 subtitle decoding and English ASS translation support.
- Fix WebVTT short timestamp sync handling and synced-file cache collisions.
- Preserve VTT line breaks/entities when converting to ASS.
- Detect remote subtitle formats instead of saving every unknown response as VTT.

## Desktop parity

- Claude/Anthropic translation provider with encrypted API-key storage.
- Claude model discovery/selection in AI settings.
- Claude participates in cloud-provider fallback.
- Optional desktop-style removal of short SFX/speaker labels before AI translation.

CI applies the patch scripts and builds an ARM64 debug APK for verification.
