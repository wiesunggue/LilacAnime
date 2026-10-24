# Subtitle runtime v2 + desktop parity work

The subtitle/translation fixes are now implemented directly in the Android Kotlin sources instead of being injected only by a CI-time Python patch.

## Runtime v2 fixes

- `LibassTranslationOverlay` reads the persisted normal subtitle-size setting and applies it through `AssRender.setFontScale()` while translated subtitles are rendered.
- The overlay deduplicates translated events itself, so the translator can re-offer completed events until the AndroidView/libass renderer is ready. Translation cues are no longer lost because of view creation timing.
- Switching subtitle sessions or returning to original subtitles clears the active translated overlay so stale translated events cannot remain on screen.
- `RealtimeSubtitleTranslator` resolves the actual external subtitle path currently used by mpv before falling back to `localSubtitle`.
- Subtitle format detection prefers strong content signatures over cached/downloaded file extensions, including old files mislabeled as VTT.
- UTF-8, UTF-16, Shift_JIS and EUC-KR decoding is handled for translation parsing and libass header loading.
- ASS translation now accepts any subtitle line containing letters rather than Japanese-only lines.
- Backward seeking rewinds the translation prefetch cursor; toggling prefetch back on no longer requires a new subtitle session.
- Each subtitle session gets its own translation mutex so a cancelled blocking cloud request cannot stall the next episode.
- Raw model-emitted HTML/ASS tags are sanitized before translated text is sent to libass; original ASS override tags are restored separately.

## Desktop parity work

Desktop parity features such as Claude/Anthropic support remain staged separately. They are not injected into the runtime-v2 verification build until subtitle size and realtime translation are stable.

CI now builds the direct Kotlin sources without applying the old patch scripts.
