# Changelog

## 1.0

Build 5, 7 November 2026

- Add the original Moonshroom Studio logo and “MUSIC IS OPEN SOURCE” artwork in AU, VST3 and the standalone app.
- Open the Moonshroom website when the logo is clicked.
- Keep the four separate website, SoundCloud, YouTube and ShroomsLoops links.

- Add the supplied Moonshroom guitar-and-MIDI artwork as the macOS application icon.
- Include the icon in reproducible builds and app bundles.
- Audio/MIDI processing is unchanged.

## 0.1.0 — first public beta

- Standalone macOS app, Audio Unit v2 and VST3 formats with the Rust Hendrix engine.
- Host MIDI output and an optional per-instance virtual CoreMIDI output.
- English/German interface, preset bank and configurable default settings.
- Moonshroom Studio logo and four browser links in the right column.
- First-cycle mono detection, bounded acquisition modes, polyphonic analysis, pick retriggering and mono pitch bend.
- Offline Rust, AU audio/MIDI/state and official VST3 validator checks.
- Retain the reusable Rust API, synthetic-tone preview and original ShroomsLoops integration references.
