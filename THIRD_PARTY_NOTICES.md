# Third-party notices

Hendrix is developed by Moonshroom Studio and released under the MIT License.

- `parameters.rs` adapts parameter metadata helpers from SoundCraft (copyright 2026 ArtCraft Team and the SoundCraft contributors), under its MIT option. Both original license texts are included.
- The AU, VST3 and standalone wrappers use iPlug2 at `d82b2e99b17c8f723d3f6e2a88ffd8b656f28e5d` under its permissive zlib-style license. Its WebView library is MIT licensed. See `iPlug2-LICENSE.txt` and `iPlug2-WebView-LICENSE.txt`.
- The VST3 build uses Steinberg's MIT-licensed SDK at `3cdf9ca5d1f5b1b21e0a86832aa4abe55607bd96`. See `VST3-LICENSE.txt`.
- Standalone audio and MIDI I/O use RtAudio and RtMidi. iPlug2 also uses Cockos WDL/SWELL and nlohmann JSON. Their notices and the runtime Rust dependency licenses are collected in `DEPENDENCY-LICENSES.txt`.

`build_plugins.py` makes two clearly marked changes to the pinned iPlug2 source: it maps bipolar pitch bend to the full unsigned MIDI range in the VST3 adapter, and uses RtMidi's pointer overload to avoid a temporary allocation for each standalone MIDI event. All other framework copyrights and licenses are retained.

The portrait is generated illustration. The Moonshroom logo was supplied by Moonshroom Studio. No endorsement by the depicted artist or his estate is implied.
