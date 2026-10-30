# frp-jump for Android

An Android client for [frp-jump](https://github.com/aadegtyarev/frp-jump): the
phone enrolls as a frp-jump device of its own, lists the owner's other devices
and (in later stages) opens their web UI or an SSH console through frp-jump
tunnels. It only consumes ports; it never exposes anything of the phone.

Behaviour is defined in [docs/contract.md](docs/contract.md).

Current stage: enrollment, the device list, tunnels from the device card and a
minimal settings screen ("Forget this phone").

## How tunnels work

- The card lists Web (80), SSH (22) and ports the owner adds (port, name, kind).
  Connect calls `POST /api/agent/connect` (`ssh` for kind ssh or ports 22/2222,
  `tcp` otherwise, as the Linux client classifies them), then polls
  desired state every second for up to 15 s until the grant shows up.
- Desired state is the only source of truth: frpc runs one stcp + xtcp
  visitor pair per consumed grant, laid out as the Linux agent's
  `driver/frp/config.py` does, except frpc logs at `info` so the app can tell
  connecting / ready / direct / relayed / failed from its output.
- Local ports come from 40000-40999 and stay with (device, port), so
  `127.0.0.1:<port>` bookmarks keep working.
- frpc runs from `nativeLibraryDir` as a child process; its config, cert, key
  and CA are written 0600 into `no_backup/frpc/`. The key and the config are
  deleted whenever frpc stops.
- While the app is visible it sends a heartbeat and fetches desired state
  every 2 s. While at least one tunnel is up a foreground service (type
  `specialUse`) keeps the process alive and shows "N tunnels" with
  "Disconnect all". Closed, the app does not poll yet.

## Build

Needs the Android SDK (platform 35, NDK 26.3 for frpc), a JDK 17+ with `javac`,
and Docker for the frpc build.

```sh
scripts/build-frpc.sh        # upstream frpc 0.70.0 -> app/src/main/jniLibs/<abi>/libfrpc.so
./gradlew testDebugUnitTest assembleDebug
```

The APK builds without `libfrpc.so` too, but tunnels then cannot start.
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
