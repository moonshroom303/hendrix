# Hendrix Mobile

Hendrix turns a guitar, voice or other audio input into MIDI. Use the phone's microphone or a compatible wired audio interface. Send MIDI to a DAW or hardware instrument, or play notes using the on-screen fretboard and keys. Record either input, view the take as notes or guitar tablature, and share it as a standard MIDI file.

| | Android 12+ | iOS / iPadOS 17+ |
| --- | --- | --- |
| Audio input, mono/poly tracking, 432/440 Hz reference | Yes | Yes |
| Bluetooth MIDI, fretboard, chord mode and keys | Yes | Yes |
| Recording, notes, tablature and MIDI export | Yes | Yes |
| Editable monophonic voice ranges in Notes & Tuning | Yes | Yes |
| USB MIDI to a host, adjustable audio read size | Yes | |
| Local General MIDI sound and take playback to speaker or MIDI output | Yes | |

## Beta 0.1.12

The Android APK supports arm64 and x86-64. If you download a transfer ZIP, unzip it once and open the APK on your Android device. The iOS ZIP is an Apple silicon Simulator build. For a physical iPhone or iPad, build the Xcode project with your own signing team.

For Bluetooth on Android, enable **BLE MIDI**, then connect from the Mac's **Audio MIDI Setup → MIDI Studio → Bluetooth** panel. In your DAW, select the phone as a MIDI input. If the first connection after this update drops, reconnect once so Hendrix can prepare the known peer. The Seeker / Mac test passed reconnection and use after unplugging USB. Bluetooth behavior can vary by device and OS version.

Use **Notes & Tuning → Vocal range → Apply Mono** to set the low and high notes together. The ranges are editable starting points drawn from the [Yale Library table based on the New Harvard Dictionary of Music](https://yalelibrary.atlassian.net/wiki/spaces/YMD/pages/202030334). On Android, **Audio latency** offers 16, 32, 64, 128, 256, 512 or 1024 samples per input read. The default is 256. The 16-sample option is experimental and increases how often the audio thread reads from Android. If you hear glitches or lose notes, raise the block size. Audio hardware, pitch detection, MIDI transport and the receiving synth each contribute to the delay you hear.

## Build from source

Run these commands from the `mobile/` directory on an Apple silicon Mac. Install Rust 1.88 or newer and the listed Rust targets. Cargo downloads the dependencies in `core/Cargo.lock` on the first build.

**Android.** Install JDK 17, Android SDK Platform 35, Build Tools 35.0.0 and NDK 27.3.13750724. Set `JAVA_HOME` and `ANDROID_HOME` to your local installations.

```sh
rustup target add aarch64-linux-android x86_64-linux-android
scripts/build-android
```

The script writes `build/android/Hendrix-0.1.12-test.apk`. It generates a local test signing key when needed. Keep your key private and reuse it for updates to your own builds. Use the release APK to update an installation signed by Moonshroom Studio.

**iOS.** Install Xcode and an iOS Simulator runtime. Use `scripts/build-ios simulator` for the simulator, or open `ios/Hendrix.xcodeproj` in Xcode and select your team to install on a device.

```sh
rustup target add aarch64-apple-ios-sim aarch64-apple-ios
scripts/build-ios simulator
```

For the packaged simulator download, boot an iPhone simulator, unzip the download and run `xcrun simctl install booted Hendrix.app`.

```sh
cargo test --locked --manifest-path core/Cargo.toml
python3 android/tests/run-ble-tests.py
```

## Licenses

Hendrix code is distributed under the [MIT license](../LICENSE). The shared core retains the [SoundCraft MIT](core/SoundCraft-LICENSE-MIT.txt) and [Apache 2.0](core/SoundCraft-LICENSE-APACHE.txt) notices. Android's local synth uses [TinySoundFont (MIT)](android/assets/synth/TinySoundFont-LICENSE.txt) and the unmodified [TimGM6mb SoundFont (GPL-2)](android/assets/synth/TimGM6mb-LICENSE.txt). The SoundFont and its [copyright notice](android/assets/synth/TimGM6mb-copyright.txt) are included. See [third-party sources](native/vendor/SOURCES.txt) for upstream versions and links.
