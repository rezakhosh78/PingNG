# AndroidLibXrayLite for Android 7+

PingNG bundles the existing AndroidLibXrayLite/Xray fork because it supports
ServerLess v57 FinalMask `merge` and `oob`. The bundled AAR is built for Android
API 24, so it can be installed on Android 7 and newer.

To rebuild the AAR on Linux, install Go 1.27+, Android SDK platform 37.0, NDK
29.0.14206865, `git`, `unzip`, and the pinned gomobile version:

```sh
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
export PATH="$(go env GOPATH)/bin:$PATH"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
native/xray/build-android.sh
```

The script checks out AndroidLibXrayLite commit
`faad7a00ee2934ac101a677681523f6375b27f86`, keeps the geo assets from the
bundled AAR, and builds with `gomobile bind -androidapi 24`. It replaces the
bundled AAR only after checking the generated manifest.
