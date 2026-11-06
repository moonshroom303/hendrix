# HENDRIX

**Real-time audio-to-MIDI conversion by Moonshroom Studio.**

Hendrix turns instrument audio into MIDI notes and expressive pitch bends. Its mono mode makes a provisional pitch estimate from the first usable waveform cycle and verifies it afterward. Poly mode uses a longer analysis window to recognize basic chords.

[Moonshroom Studio](https://moonshroom.moonshroom303.chatgpt.site/) · [ShroomsLoops Studio](https://shroomsloops-studio-231026.moonshroom303.chatgpt.site/#) · [SoundCloud](https://www.soundcloud.com/moonshroom303) · [YouTube](https://www.youtube.com/@moonshroom303)

![Hendrix interface](Hendrix.png)

## Release scope

This repository contains the reusable Rust DSP engine, MIDI event adapter, project preset bank, and an embeddable egui interface. The interactive preview processes a synthetic test tone. Audio capture, device selection, and MIDI delivery are provided by the integrating host.

The original ShroomsLoops module and GUI are included as integration references in `shroomsloops_engine_reference.rs` and `shroomsloops_gui_reference.rs`. They describe the existing host integration; the independently buildable library uses `engine.rs` and `gui.rs`.

## Features

- Mono pitch tracking and basic polyphonic chord recognition.
- Direct first-cycle estimation, followed by pitch verification.
- Mono 5 / 10 / 15 ms acquisition limits: pitches recognized too late remain silent.
- Picking threshold and minimum interval for retriggering repeated notes.
- Input gate, stability, release, voice limit, note range, reference tuning, and transposition.
- Dynamic or fixed velocity.
- Mono pitch bends with ±2 / ±12 / ±24 semitone ranges and smoothing.
- Optional audio passthrough.
- Project-owned presets and a selectable default for new instances.
- Original Hendrix artwork, rotary controls, detected-note feedback, and clickable developer links.

## Latency

“Direct” estimates pitch after a usable waveform period. Its onset depends on frequency and phase: roughly one to one-and-a-half periods for a clean tone, plus processing-block and hardware buffering. A 440 Hz tone has a period of about 2.27 ms; a low E at about 82 Hz needs roughly 12.1 ms per period. Harmonics, noise, and technique can make an initial estimate provisional or incorrect.

The 5 / 10 / 15 ms modes are acquisition limits, rather than promises of total audio-to-synth latency. Slow or ambiguous pitches are rejected. Polyphonic mode uses a longer analysis window. Mono picking and bend controls are inactive in Poly mode.

## Build

Rust 1.88 or newer is required.

```sh
cargo test --locked
cargo test --locked --all-features
cargo run --locked --example first_note
cargo run --locked --features preview --bin hendrix-preview
```

The last command opens the interactive interface preview. Press **Test A4** to feed a synthetic signal into the actual engine. The preview is useful for evaluating controls and appearance; use the library API for host audio and MIDI I/O.

## Host integration

```rust
use hendrix_audio_to_midi::{Plugin, Processor, INFO, midi::MidiReader};

let mut processor = Processor::new();
processor.prepare(48_000.0, 128, 2);
let params = std::array::from_fn(|i| INFO.params[i].default);
let mut midi = MidiReader::default();
let mut io = [vec![0.0; 128], vec![0.0; 128]];

// In the host callback, after filling the already allocated input buffers:
processor.process(&mut io, 128);
midi.poll(&processor.output, &params, 0, |message| {
    // Enqueue these three MIDI bytes through the host's realtime-safe MIDI output.
    let _ = message;
});
```

Prepare and allocate before processing. Apply control changes at the host's safe block boundary, using `set_param(id, plain_value)`, and keep the parameter array supplied to `MidiReader` synchronized. The output state is available through `Processor::output` for UI feedback.

`MidiReader` handles transposition, same-note retriggering, pitch-bend messages, and RPN bend-range configuration. Call `flush` against the **old** destination before switching MIDI outputs, disabling the instance, or removing it. Each reader owns only the notes that it emitted. Routing shared MIDI channels still requires coordination by the host.

For the interface, enable the `gui` feature and call `gui::Panel::show`. Supply eligible MIDI destinations, consume parameter changes through the host, and serialize `panel.state` with the host project. Validate restored state before publishing it. Preset loading preserves the MIDI destination; new instances can read `bank.initial()` to inherit the project default.

## Starting preset: My Guitar

The GUI includes the requested project preset: Poly, −72 dB threshold, 20.5 ms stability, 69.1 ms release, one voice, D#1–C8, A4 = 431.4 Hz, zero transposition, dynamic velocity, Balanced verification, and audio passthrough. Mono settings include Direct, retrigger on, 3 dB picking threshold, 10 ms minimum interval, ±2 semitones, and 4.9 ms bend smoothing.

The processor's factory settings remain available through `INFO` and `presets::factory()`. Destination assignments belong to the host instance and are excluded from presets.

## Testing

The suite covers first-cycle timing at multiple rates and block sizes, low-note continuity, arbitrary signal phase, polyphonic chords, repeated picks, bends, silence and noise, allocation-free processing, MIDI ownership and release, preset persistence, responsive UI rendering, and all four clickable developer links. Synthetic tests establish reproducible behavior; real instruments and host routing require practical testing.

## License and credits

Copyright © 2026 **Moonshroom Studio**. Hendrix is released under the [MIT License](LICENSE).

Parameter metadata helpers adapted from SoundCraft retain their original notices. See [third-party notices](THIRD_PARTY_NOTICES.md) and the included SoundCraft license texts. Rust dependencies retain their respective licenses.

The illustrated portrait is generated artwork. Hendrix is developed by Moonshroom Studio.
