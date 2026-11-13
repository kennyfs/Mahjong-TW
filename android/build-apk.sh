#!/bin/bash
# Builds the release APK and copies it to $MAHJONG_APK_DIR (default build/apk) as mahjong-<versionName>.apk; with
# BUNDLE=1 the bundle for Google Play too (.aab). It is signed with the key that $MAHJONG_SIGNING (default
# ~/.android/thirteen-release.txt: the key is shared with Thirteen) describes, in lines "keystore PATH", "alias NAME"
# and "password PASS"; without that file, with the debug key. MAHJONG_ID_SUFFIX (.test): a test build, another id
# (app/build.gradle.kts), named mahjong-<versionName>-test.apk. JAVA_HOME (JDK 17 or later), ANDROID_HOME, GRADLE (default gradle) and GRADLE_USER_HOME come
# from the environment or from local.env next to this script (not in the repository); GRADLE_FLAGS adds flags
# (--offline here: every dependency is in the cache). The log is build-apk.log. Thirteen's build-apk.sh, adapted.
set -euo pipefail
cd "$(dirname "$0")"
if [ -f local.env ]; then set -a; . ./local.env; set +a; fi
export ANDROID_HOME=${ANDROID_HOME:-~/Android/Sdk}
GRADLE=${GRADLE:-gradle}
# (The key is Thirteen's, shared: its description file is the default.)
NOTES=${MAHJONG_SIGNING:-~/.android/thirteen-release.txt}
if [ -f "$NOTES" ]; then
  export MAHJONG_KEYSTORE=$(awk '$1 == "keystore" { print $2 }' "$NOTES")
  export MAHJONG_KEY_ALIAS=$(awk '$1 == "alias" { print $2 }' "$NOTES")
  export MAHJONG_KEYSTORE_PASSWORD=$(awk '$1 == "password" { print $2 }' "$NOTES")
  export MAHJONG_KEY_PASSWORD=$MAHJONG_KEYSTORE_PASSWORD
fi
tasks=(assembleRelease)
if [ "${BUNDLE:-0}" = 1 ]; then tasks+=(bundleRelease); fi
# shellcheck disable=SC2086
"$GRADLE" --no-daemon ${GRADLE_FLAGS:-} "${tasks[@]}" 2>&1 | tee build-apk.log
name=$(sed -n 's/.*"versionName": *"\([^"]*\)".*/\1/p' app/build/outputs/apk/release/output-metadata.json)
dir=${MAHJONG_APK_DIR:-build/apk}
mkdir -p "$dir"
# A test build (MAHJONG_ID_SUFFIX=.test, another id): mahjong-X.Y.Z-test.apk.
tag=${MAHJONG_ID_SUFFIX:+-${MAHJONG_ID_SUFFIX#.}}
cp app/build/outputs/apk/release/app-release.apk "$dir/mahjong-$name$tag.apk"
echo "$dir/mahjong-$name$tag.apk"
if [ "${BUNDLE:-0}" = 1 ]; then
  cp app/build/outputs/bundle/release/app-release.aab "$dir/mahjong-$name$tag.aab"
  echo "$dir/mahjong-$name$tag.aab"
fi
