//! Note extraction for a future score and guitar tablature view.
//! Bends, notation spelling, rhythm quantization and polyphonic fingering are
//! deliberately not interpreted here; the original MIDI remains authoritative.
use crate::mobile::Event;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Note {
    pub channel: u8,
    pub pitch: u8,
    pub velocity: u8,
    pub start_sample: u64,
    pub end_sample: u64,
}

#[derive(Clone, Copy)]
struct Active {
    start: u64,
    velocity: u8,
}

pub fn extract_notes(events: &[Event], end_sample: u64) -> Result<Vec<Note>, &'static str> {
    let mut active = [[None::<Active>; 128]; 16];
    let mut notes = Vec::new();
    let mut previous = 0;
    for event in events {
        if event.sample < previous || event.sample > end_sample {
            return Err("events must be ordered and end within the recording");
        }
        previous = event.sample;
        let [status, pitch, velocity] = event.data;
        if pitch > 127 || velocity > 127 {
            continue;
        }
        let channel = (status & 0x0f) as usize;
        let kind = status & 0xf0;
        if kind != 0x80 && kind != 0x90 {
            continue;
        }
        let slot = &mut active[channel][pitch as usize];
        if let Some(old) = slot.take() {
            notes.push(Note {
                channel: channel as u8,
                pitch,
                velocity: old.velocity,
                start_sample: old.start,
                end_sample: event.sample,
            });
        }
        if kind == 0x90 && velocity > 0 {
            *slot = Some(Active {
                start: event.sample,
                velocity,
            });
        }
    }
    for (channel, pitches) in active.iter().enumerate() {
        for (pitch, slot) in pitches.iter().enumerate() {
            if let Some(old) = slot {
                notes.push(Note {
                    channel: channel as u8,
                    pitch: pitch as u8,
                    velocity: old.velocity,
                    start_sample: old.start,
                    end_sample,
                });
            }
        }
    }
    notes.sort_by_key(|n| (n.start_sample, n.pitch, n.channel));
    Ok(notes)
}

/// A simple single-note suggestion, not a polyphonic fingering algorithm.
/// Strings are numbered 1–6, where 1 is high E. `None` means out of range.
pub fn standard_guitar_position(pitch: u8, max_fret: u8) -> Option<(u8, u8)> {
    let open = [64u8, 59, 55, 50, 45, 40];
    open.into_iter()
        .enumerate()
        .filter_map(|(index, base)| {
            pitch
                .checked_sub(base)
                .filter(|fret| *fret <= max_fret)
                .map(|fret| (index as u8 + 1, fret))
        })
        .min_by_key(|&(string, fret)| (fret, string))
}
