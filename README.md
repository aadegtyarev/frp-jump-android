# frp-jump for Android

An Android client for [frp-jump](https://github.com/aadegtyarev/frp-jump): the
phone enrolls as a frp-jump device of its own, lists the owner's other devices
and (in later stages) opens their web UI or an SSH console through frp-jump
tunnels. It only consumes ports; it never exposes anything of the phone.

Behaviour is defined in [docs/contract.md](docs/contract.md).

Current stage: enrollment, the device list, a placeholder device card and a
minimal settings screen ("Forget this phone"). No tunnels yet.

## Build

Needs the Android SDK (platform 35, NDK 26.3 for frpc), a JDK 17+ with `javac`,
and Docker for the frpc build.

```sh
scripts/build-frpc.sh        # upstream frpc 0.70.0 -> app/src/main/jniLibs/<abi>/libfrpc.so
./gradlew testDebugUnitTest assembleDebug
```

The APK builds without `libfrpc.so` too; it is only needed once tunnels land.
If Gradle reports that the toolchain has no `JAVA_COMPILER`, point `JAVA_HOME`
at a full JDK, e.g. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Enroll

On a laptop that is already enrolled:

```sh
frp-jump-client devices add-token --name phone
```

Paste the printed `frp-jump-client enroll https://… <token> --name phone` line
into the app as is; line breaks from a narrow terminal are fine. A bare
`<url> <token>` works too. The token is single-use.
