# Subtitle runtime v3 + desktop parity work

The current subtitle verification build focuses on the two runtime causes reproduced after v2.

## Runtime v3 fixes

- Translated subtitle size no longer polls `pref_subtitle_size` from a separate SharedPreferences path. `PlayerScreen.subtitleSize` is now the single source of truth and is forwarded directly to `LibassTranslationOverlay`.
- `LibassTranslationOverlay.setSource()` preserves the current direct font scale when libass is recreated instead of resetting it from stale persisted state.
- `PlayerScreen` applies translated subtitle scale both when the AndroidView is created/reused and whenever the slider value changes.
- Realtime translation no longer depends only on the one-shot Compose prepare effect. The polling loop idempotently re-prepares the translator when mpv's actual subtitle file appears after that effect.
- The initial prepare path also prefers `engine.currentSubtitleFilePath` over a stale/null local subtitle path.

## Existing runtime stability work

- The overlay deduplicates translated events itself, so completed events can be re-offered until AndroidView/libass is ready.
- Switching subtitle sessions or returning to original subtitles clears stale translated events.
- Subtitle format detection prefers content signatures over misleading file extensions.
- UTF-8, UTF-16, Shift_JIS and EUC-KR decoding is supported in the translation path.
- ASS translation accepts subtitle lines containing letters rather than Japanese-only lines.
- Backward seeking rewinds translation prefetch; re-enabling prefetch does not require a new session.
- Each subtitle session gets its own translation mutex so an old blocking cloud request cannot hold the next session.
- Raw model-emitted HTML/ASS tags are sanitized before translated text is sent to libass.

CI applies the focused runtime-v3 patch and builds a dedicated ARM64 debug APK for verification before any additional desktop-parity features are layered on top.
