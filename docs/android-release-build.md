# Android release build

How to set up signing for the Android app (`:app:android`) and build a release APK or App Bundle.

## Where the files go

| File | Location | In git |
|---|---|---|
| Release keystore | `app/android/keystore/radarnebo-release.jks` | No (`*.jks` is gitignored) |
| Signing credentials | `keystore.properties` in the repo root | No (`keystore.properties` is gitignored) |
| Firebase config | `app/android/google-services.json` | No (gitignored) |
| Server address and access key | `client.properties` in the repo root (copy `client.properties.example`) | No (gitignored) |

Never commit any of these. Store the keystore and its password together in a password manager or other
secure place. If you lose them, you can't sign updates with the same key.

## keystore.properties

`app/android/build.gradle.kts` reads `keystore.properties` from the repo root. It has four keys:

```properties
storeFile=app/android/keystore/radarnebo-release.jks
storePassword=<keystore password>
keyAlias=radarnebo
keyPassword=<key password>
```

- `storeFile` is relative to the repo root, but an absolute path works too, so the `.jks` can live outside the repo
  (for example `~/keys/radarnebo-release.jks`).
- The keystore is PKCS12, so `storePassword` and `keyPassword` are the same.

If `keystore.properties` is missing, the release build still runs but produces an **unsigned** APK/AAB, which can't
be installed or uploaded.

## Setting up a new machine

1. Get `radarnebo-release.jks` and its password from wherever the team keeps them.
2. Copy the keystore to `app/android/keystore/radarnebo-release.jks`.
3. Create `keystore.properties` in the repo root as shown above.
4. Optionally restrict permissions: `chmod 600 keystore.properties app/android/keystore/radarnebo-release.jks`.

## Creating a new keystore

Only do this if no key exists yet. A Play Store app must keep the same upload key unless you ask Google to reset it.

```bash
mkdir -p app/android/keystore
keytool -genkeypair -v \
  -keystore app/android/keystore/radarnebo-release.jks \
  -storetype PKCS12 -alias radarnebo \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=<name>, O=Gusak Lab, C=UA"
```

`keytool` asks for a password. Put that password in `keystore.properties` as both `storePassword` and `keyPassword`.

## Building

```bash
./gradlew :app:android:assembleRelease   # APK, for sideloading or direct distribution
./gradlew :app:android:bundleRelease     # AAB, for Google Play
```

Outputs:

- `app/android/build/outputs/apk/release/android-release.apk`
- `app/android/build/outputs/bundle/release/android-release.aab`

Before a new release, bump `versionCode` and `versionName` in `defaultConfig` in `app/android/build.gradle.kts`.

## Verifying the signature

```bash
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs \
  app/android/build/outputs/apk/release/android-release.apk
```

To print the key fingerprints (SHA-1 / SHA-256), for example for Firebase or Google Play:

```bash
keytool -list -v -keystore app/android/keystore/radarnebo-release.jks -alias radarnebo
```

## CI

On CI, store the keystore as a base64 secret and the passwords as separate secrets. Before the build, decode the
keystore to a file and write `keystore.properties` from the secrets.

```bash
echo "$ANDROID_KEYSTORE_BASE64" | base64 --decode > app/android/keystore/radarnebo-release.jks
cat > keystore.properties <<EOF
storeFile=app/android/keystore/radarnebo-release.jks
storePassword=$ANDROID_KEYSTORE_PASSWORD
keyAlias=radarnebo
keyPassword=$ANDROID_KEYSTORE_PASSWORD
EOF
```
