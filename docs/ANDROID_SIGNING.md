# Android signing continuity

The persistent signing key for `com.lilac.anime.subtitlecheck` is backed up in the owner's private ChatGPT Library.

- Backup filename: `LilacAnime-subtitle-check-private-signing-backup.zip`
- Library file ID: `libfile_ff3ae7ee3b8081919934560dacc7ab20`
- Contents: `lilac-subtitle-check.jks`, `store-password.txt`, and restoration README.
- Alias: `lilac-subtitle-check`
- Certificate SHA-256: `9f1aa4a4b4557a2c5c8bd5e7aa79c15f36021a7d09b4203e42d098f591140927`

Before building an update, retrieve this backup through the authenticated Library tools. Extract it outside the repository; the password file supplies both the keystore and key passwords. Do not print the password or commit the backup, keystore, or password.

Supply the existing build configuration with `LILAC_KEYSTORE_PATH`, `LILAC_STORE_PASSWORD`, `LILAC_KEY_PASSWORD`, and `LILAC_KEY_ALIAS`. After building, verify the APK package and signing certificate with apksigner/aapt. Keep the package ID and certificate unchanged and increase versionCode for updates.

This backup belongs to the subtitle-check app first delivered on 2026-11-01 (versionCode 38). It does NOT sign the older `com.lilac.anime.v3test` APK. Do not silently generate a replacement key or change the package when the backup is unavailable; report the recovery problem first.

The backup is private, not a public download. GitHub Actions secrets are not provisioned by this document; CI must receive the same key before publishing compatible updates.
