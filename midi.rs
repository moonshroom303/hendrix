//! Allocation-free translation of one Hendrix owner's note state into MIDI 1.0.
use crate::Output;
use std::sync::atomic::Ordering::Acquire;

pub struct MidiReader {
    held: [u8; 128],
    stamps: [u64; 128],
    bend: Option<(u16, u8)>,
    channel: u8,
}
impl Default for MidiReader {
    fn default() -> Self {
        Self {
            held: [0; 128],
            stamps: [0; 128],
            bend: None,
            channel: 0,
        }
    }
}
impl MidiReader {
    /// Channel is zero based. Flush before changing destination or removing the owner.
    pub fn flush(&mut self, mut emit: impl FnMut([u8; 3])) {
        for (n, velocity) in self.held.iter_mut().enumerate() {
            if *velocity > 0 {
                emit([0x80 | self.channel, n as u8, 0]);
                *velocity = 0;
            }
        }
        if self.bend.take().is_some() {
            emit([0xe0 | self.channel, 0, 64]);
        }
        self.stamps.fill(0);
    }
    /// Call after processing. `emit` belongs to the host and must itself be realtime-safe.
    /// Parameters are the same plain values used by the processor, in INFO order.
    pub fn poll(
        &mut self,
        output: &Output,
        params: &[f32; 20],
        channel: u8,
        mut emit: impl FnMut([u8; 3]),
    ) {
        let channel = channel.min(15);
        if channel != self.channel {
            self.flush(&mut emit);
            self.channel = channel;
        }
        if params[1] < 0.5 {
            self.flush(emit);
            return;
        }
        let mut notes = [0u8; 128];
        let mut stamps = [0u64; 128];
        let transpose = params[9].round().clamp(-24., 24.) as i32;
        for (n, note) in output.notes.iter().enumerate() {
            let v = note.load(Acquire);
            let pitch = n as i32 + transpose;
            if v & 255 > 0 && (0..128).contains(&pitch) {
                notes[pitch as usize] = v as u8;
                stamps[pitch as usize] = v >> 8;
            }
        }
        let bend = if params[0] < 0.5 && params[13] >= 0.5 && notes.iter().any(|v| *v > 0) {
            let range = [2, 12, 24][params[14].round().clamp(0., 2.) as usize];
            let semitones = f32::from_bits(output.bend.load(Acquire));
            let value = if semitones.is_finite() {
                ((8192. + semitones / range as f32 * 8192.)
                    .round()
                    .clamp(0., 16383.)) as u16
            } else {
                8192
            };
            Some((value, range))
        } else {
            None
        };
        if let Some((_, range)) = bend {
            if self.bend.is_none_or(|(_, old)| old != range) {
                for (cc, value) in [
                    (101, 0),
                    (100, 0),
                    (6, range),
                    (38, 0),
                    (101, 127),
                    (100, 127),
                ] {
                    emit([0xb0 | channel, cc, value]);
                }
            }
        }
        if bend != self.bend {
            let value = bend.map_or(8192, |(value, _)| value);
            emit([0xe0 | channel, (value & 127) as u8, (value >> 7) as u8]);
            self.bend = bend;
        }
        for n in 0..128 {
            let retrigger = self.held[n] > 0 && notes[n] > 0 && stamps[n] != self.stamps[n];
            if self.held[n] > 0 && (notes[n] == 0 || retrigger) {
                emit([0x80 | channel, n as u8, 0]);
            }
            if notes[n] > 0 && (self.held[n] == 0 || retrigger) {
                emit([0x90 | channel, n as u8, notes[n]]);
            }
        }
        self.held = notes;
        self.stamps = stamps;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::Ordering::Release;
    #[test]
    fn repeated_notes_transpose_channel_and_flush_keep_ownership() {
        let o = Output::default();
        let mut reader = MidiReader::default();
        let mut p = std::array::from_fn(|i| crate::INFO.params[i].default);
        let mut events = vec![];
        o.notes[60].store((1 << 8) | 100, Release);
        reader.poll(&o, &p, 0, |m| events.push(m));
        assert_eq!(events, vec![[0x90, 60, 100]]);
        events.clear();
        o.notes[60].store((2 << 8) | 110, Release);
        reader.poll(&o, &p, 0, |m| events.push(m));
        assert_eq!(events, vec![[0x80, 60, 0], [0x90, 60, 110]]);
        events.clear();
        p[9] = 12.;
        reader.poll(&o, &p, 1, |m| events.push(m));
        assert_eq!(events, vec![[0x80, 60, 0], [0x91, 72, 110]]);
        events.clear();
        reader.flush(|m| events.push(m));
        assert_eq!(events, vec![[0x81, 72, 0]]);
        let result = crate::allocation_probe(|| reader.poll(&o, &p, 1, |_| {}));
        assert_eq!(result["allocations_or_reallocations"], 0);
    }
    #[test]
    fn bend_range_is_configured_and_released_without_stuck_notes() {
        let o = Output::default();
        let mut reader = MidiReader::default();
        let mut p = std::array::from_fn(|i| crate::INFO.params[i].default);
        p[13] = 1.;
        p[14] = 1.;
        o.notes[69].store((1 << 8) | 100, Release);
        o.bend.store(1f32.to_bits(), Release);
        let mut events = vec![];
        reader.poll(&o, &p, 0, |m| events.push(m));
        assert!(events.contains(&[0xb0, 6, 12]));
        assert!(events.contains(&[0x90, 69, 100]));
        events.clear();
        p[1] = 0.;
        reader.poll(&o, &p, 0, |m| events.push(m));
        assert_eq!(events, vec![[0x80, 69, 0], [0xe0, 0, 64]]);
    }
}
