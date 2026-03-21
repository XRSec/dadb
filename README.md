# dadb

[![Maven Central](https://img.shields.io/maven-central/v/dev.mobile/dadb.svg)](https://mvnrepository.com/artifact/dev.mobile/dadb)

[Blog Post: Our First Open-Source Project](https://blog.mobile.dev/our-first-open-source-project-54cd8edc452f)

<img src="https://user-images.githubusercontent.com/847683/143626125-5872bdd8-180e-48bb-a64f-47b3688a086d.png" width="700px" />

A Kotlin/Java library for speaking the ADB protocol directly.
The core `dadb` artifact is a JVM library. Platform-specific transports can be added separately, and
using a host-side `adb` binary or `adb server` remains a normal supported option.

Current platform boundary summary:

- STLS/TLS-capable ADB connection is a general ADB capability, not an Android-only protocol
- Android USB host transport is Android-specific because it depends on Android USB APIs
- Wireless Debugging pairing (`adb pair`) is currently provided through `dadb-android`
- if a host-side `adb` binary is available, the `adb server` path it manages remains a normal, supported backend

Practical guidance:

- on desktop and server platforms, using the platform `adb` binary or `adb server` is often the
  simplest operational choice
- on Android, relying on an external `adb` binary is usually harder in practice due to packaging,
  ABI management, process lifecycle, USB host integration, and inconsistent authorization behavior
  across Android versions and vendor builds
- `dadb-android` exists mainly to make Android-native ADB integrations more app-controlled and
  predictable, especially for Wireless Debugging pairing, STLS/TLS transport, and USB host
  connections

```kotlin
dependencies {
  implementation("dev.mobile:dadb:<version>")
}
```

### Direct TCP

Connect to `emulator-5554` and install `apkFile`:

```kotlin
Dadb.create("localhost", 5555).use { dadb ->
    dadb.install(apkFile)
}
```

*Note: Connect to the odd adb daemon port (5555), not the even emulator console port (5554)*

### Android USB

If you are running on Android and have access to USB host APIs, add the Android transport module:

```kotlin
implementation("dev.mobile:dadb-android:<version>")
```

Then create a direct USB transport from `UsbManager` and `UsbDevice`:

```kotlin
val dadb = Dadb.create(
    transportFactory = UsbTransportFactory(usbManager, usbDevice, "usb:${usbDevice.deviceName}"),
    keyPair = AdbKeyPair.readDefault(),
)
```

This transport is Android-only. Desktop JVM users should use the core `dadb` artifact with direct TCP or a host-side `adb` binary / `adb server`.

### Experimental Android Runtime Helper

If your Android app wants a small convenience wrapper around app-private ADB identity storage,
pairing, and Android-specific transports, `dadb-android` also exposes `AdbRuntime`.

This layer is experimental. Prefer the lower-level transport and pairing APIs directly if you want
the smallest and most explicit integration surface.

Why this helper exists mostly for Android:

- desktop environments usually already have a manageable `adb` binary / `adb server` workflow
- Android apps often do not
- shipping or invoking an external `adb` binary inside an Android app is usually more fragile than
  using app-controlled transports directly
- in practice, different Android versions, ROMs, and `adb` binary versions may also differ in how
  authorization behaves, including repeated authorization prompts or connections that do not behave
  consistently across devices
- `dadb-android` is therefore aimed at Android-native integrations where avoiding that external
  operational dependency is valuable

`AdbRuntime` can own a single runtime directory for:

- `adbkey`
- `adbkey.pub`

```kotlin
@OptIn(ExperimentalDadbAndroidApi::class)
val runtime = AdbRuntime(File(context.filesDir, "adb_keys"))
val keyPair = runtime.loadOrCreateKeyPair()
val identity = runtime.readIdentity()
val publicKey = identity.publicKey
```

This keeps long-lived ADB identity material in app-private storage instead of scattering it across
preferences or unrelated app settings.

You can also rotate or replace the local ADB identity through the runtime:

```kotlin
@OptIn(ExperimentalDadbAndroidApi::class)
val runtime = AdbRuntime(File(context.filesDir, "adb_keys"))

runtime.regenerateKeyPair()
runtime.replaceKeyPair(privateKeyPem, publicKeyText)
```

You can also inspect the current runtime identity:

```kotlin
@OptIn(ExperimentalDadbAndroidApi::class)
val runtime = AdbRuntime(File(context.filesDir, "adb_keys"))

val identity = runtime.readIdentity()
```

### Experimental Android Wireless Debugging TLS

`dadb` core already supports STLS/TLS upgrade when the transport supports it.
On Android, `dadb-android` provides Android-facing pairing helpers, identity-backed TLS transport,
and an optional callback that reports the observed server TLS public key pin after a successful
handshake.

Important boundary:

- the TLS ADB protocol itself is not Android-specific
- the current pairing/runtime helper layer is Android-specific
- if you already have a host-side `adb` binary and its `adb server`, you can keep using `AdbServer.createDadb(...)` and do not need the Android runtime helper layer

Typical flow:

1. Pair with the device through `AdbRuntime`.
2. Connect later through the same runtime using one STLS-capable transport for both plain ADB and Wireless Debugging.
3. If TLS is established, the runtime reports the observed server identity through `onServerTlsPeerObserved`.
4. Your app can decide whether to ignore it, persist it, compare it to prior observations, or notify the user.

```kotlin
@OptIn(ExperimentalDadbAndroidApi::class)
val runtime = AdbRuntime(File(context.filesDir, "adb_keys"))

runtime.pairWithCode(
    host = "192.168.0.10",
    port = 37123,
    pairingCode = "123456",
).getOrThrow()
```

```kotlin
@OptIn(ExperimentalDadbAndroidApi::class)
val runtime =
    AdbRuntime(
        storageRoot = File(context.filesDir, "adb_keys"),
        options =
            AdbRuntimeOptions(
                onServerTlsPeerObserved = { identity ->
                    println("observed TLS peer ${identity.target.authority}: ${identity.observedPinSha256Base64}")
                },
            ),
    )
```

```kotlin
@OptIn(ExperimentalDadbAndroidApi::class)
val runtime = AdbRuntime(File(context.filesDir, "adb_keys"))
val dadb = runtime.connectNetworkDadb(host = "192.168.0.10", port = 37099)

dadb.use {
    val serial = dadb.shell("getprop ro.serialno").output.trim()
    println("connected over tls=${dadb.isTlsConnection()}: $serial")
}
```

Current trust model note:

- `AdbRuntime` does not persist endpoint or peer trust state by itself
- `AdbTlsTrustPolicy.TrustAll` accepts the presented TLS certificate and lets the app observe it
- apps that want TOFU, pin comparison, or identity change notifications should implement that in
  their own storage layer using `onServerTlsPeerObserved`

### Experimental Android TLS Trust Policy

`AdbRuntime` also accepts an explicit TLS trust policy.

```kotlin
@OptIn(ExperimentalDadbAndroidApi::class)
val runtime =
    AdbRuntime(
        storageRoot = File(context.filesDir, "adb_keys"),
        options = AdbRuntimeOptions(
            tlsTrustPolicy = AdbTlsTrustPolicy.TrustAll,
        ),
    )
```

Available policies:

- `AdbTlsTrustPolicy.TrustAll`
  - default
  - accept the presented TLS server certificate without endpoint or pin verification
  - useful when the app wants to observe the peer identity and make its own trust decision
- `AdbTlsTrustPolicy.Custom`
  - provide your own trust manager factory

### Using adb server

If you already have a host-side `adb` binary, you can connect through the `adb server` it manages instead of providing a direct transport.
This is useful on desktop JVM environments where physical devices are already managed by `adb`.
This path is intentionally still a normal supported option: new TLS, Android USB, or pairing work does not replace or deprecate it.
For many desktop and server deployments, it is still the easiest path to operate.

```kotlin
val dadb = AdbServer.createDadb(
    adbServerHost = "localhost",
    adbServerPort = 5037,
    deviceQuery = "host:transport:${serialNumber}"
)
```

### Discover a Device

The following discovers and returns a connected device or emulator. If there are multiple it returns the first one found.

```kotlin
val dadb = Dadb.discover()
if (dadb == null) throw RuntimeException("No adb device found")
```

Use the following API if you want to list all available devices:

```kotlin
val dadbs = Dadb.list()
```

If a host-side `adb` binary / `adb server` is available, `Dadb.discover()` and `Dadb.list()` can also return USB-connected physical devices.

```kotlin
// Both of these will include any USB-connected devices if they are available
val dadb = Dadb.discover()
val dadbs = Dadb.list()
```

### Custom Transport

If your ADB packets do not travel over TCP, Android USB host APIs, or a host-side `adb` binary / `adb server`, you can still supply your own transport.
This is useful for tunnels, in-process bridges, and other bidirectional byte streams.

```kotlin
val dadb = Dadb.create(
    description = "my transport",
    keyPair = AdbKeyPair.readDefault(),
) {
    SourceSinkAdbTransport(
        source = mySource,
        sink = mySink,
        description = "my transport",
        closeable = myTransport,
    )
}
```

### Install / Uninstall APK

```kotlin
dadb.install(exampleApkFile)
dadb.uninstall("com.example.app")
```

### Push / Pull Files

```kotlin
dadb.push(srcFile, "/data/local/tmp/dst.txt")
dadb.pull(dstFile, "/data/local/tmp/src.txt")
```

### Execute Shell Command

```kotlin
val response = dadb.shell("echo hello")
assert(response.exitCode == 0)
assert(response.output == "hello\n")
```

### TCP Forwarding

```kotlin
dadb.tcpForward(
    hostPort = 7001,
    targetPort = 7001
).use {
    // localhost:7001 is now forwarded to device's 7001 port
    // Do operations that depend on port forwarding
}
```

### Authentication

**Dadb will use your adb key at `~/.android/adbkey` by default. If none exists at this location, private and public keys will be generated by dadb.**

If you need to specify a custom path to your adb key, use the optional `keyPair` argument:

```kotlin
val adbKeyPair = AdbKeyPair.read(privateKeyFile, publicKeyFile)
Dadb.create("localhost", 5555, adbKeyPair)
```

On Android, prefer an app-private runtime directory instead of `~/.android`, for example:

```kotlin
@OptIn(ExperimentalDadbAndroidApi::class)
val runtime = AdbRuntime(File(context.filesDir, "adb_keys"))
val adbKeyPair = runtime.loadOrCreateKeyPair()
```

# License

```
Copyright (c) 2021 mobile.dev inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
