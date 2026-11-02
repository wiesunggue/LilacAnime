# v10 queue audit reproducer

This diagnostic runs the existing compiled production `RealtimeSubtitleTranslator` class. It does not copy/reimplement the queue algorithm. Android Context/preferences/logging and the translation provider are replaced with small deterministic Java substitutes. It is **not** an Android screen or external API test.

Use the application sources from commit `237739289192211929a8b18d48ba2d8f9830bbd9` and compile `:app:compileDebugKotlin` using the generated Gradle configuration in the repository workflow. Then run `python3 scripts/subtitle_audit/run.py` with Java 17 including `jdk.compiler`. The script downloads pinned Kotlin 2.2.0, coroutines 1.10.2 and jsoup 1.23.1 dependencies into a temporary directory, compiles the Java harness and uses the production classes in `app/build/tmp/kotlin-classes/debug`.

Expected on v10: 3 BUG_REPRODUCED diagnostic cases and 1 CONTROL_PASS. The script checks that this known-bad behavior is reproducible; it is not an acceptance test declaring v10 correct. A future fixed implementation needs regression tests expecting the missing cues to be translated.

Scenarios: overlapping long cue with look-ahead disabled; overlapping long cue with the default look-ahead of 10; a 1000ms rewind to an untranslated cue; a 4000ms rewind control. Each observation waits 600ms before and after moving the clock. The fake provider responds immediately.
