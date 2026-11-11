//! Standard MIDI File export. Use on a control/worker thread, not the audio thread.
use crate::mobile::Event;

#[derive(Debug, PartialEq, Eq)]
pub enum RecordingError {
    InvalidSampleRate,
    OutOfOrder,
    TooLong,
}

pub struct Recording {
    rate: u32,
    origin: u64,
    events: Vec<Event>,
}

impl Recording {
    pub fn events(&self) -> &[Event] {
        &self.events
    }

    pub fn origin_sample(&self) -> u64 {
        self.origin
    }
    pub fn new(sample_rate: u32) -> Result<Self, RecordingError> {
        Self::new_at(sample_rate, 0)
    }

    /// `origin` is the audio sample position at the moment Record is pressed.
    pub fn new_at(sample_rate: u32, origin: u64) -> Result<Self, RecordingError> {
        if !(8_000..=192_000).contains(&sample_rate) {
            return Err(RecordingError::InvalidSampleRate);
        }
        Ok(Self {
            rate: sample_rate,
            origin,
            events: Vec::new(),
        })
    }

    pub fn push(&mut self, event: Event) -> Result<(), RecordingError> {
        if event.sample < self.origin
            || self
                .events
                .last()
                .is_some_and(|last| event.sample < last.sample)
        {
            return Err(RecordingError::OutOfOrder);
        }
        self.events.push(event);
        Ok(())
    }

    /// Format 0, 480 ticks per quarter note, fixed 120 BPM. Final note-offs are
    /// supplied even if capture was interrupted while notes were held.
    pub fn to_smf(&self, end_sample: u64) -> Result<Vec<u8>, RecordingError> {
        if end_sample < self.origin
            || self
                .events
                .last()
                .is_some_and(|last| end_sample < last.sample)
        {
            return Err(RecordingError::OutOfOrder);
        }
        let mut track = vec![0, 0xff, 0x51, 3, 0x07, 0xa1, 0x20];
        let mut previous = 0u64;
        let mut held = [[false; 128]; 16];
        let mut bends = [false; 16];
        for event in &self.events {
            let [status, a, b] = event.data;
            if !(0x80..=0xef).contains(&status) || a > 127 || b > 127 {
                continue;
            }
            let tick = ((event.sample - self.origin) as u128 * 960 / self.rate as u128)
                .min(u64::MAX as u128) as u64;
            write_delta(&mut track, tick.saturating_sub(previous))?;
            previous = tick;
            track.push(status);
            track.push(a);
            if status & 0xe0 != 0xc0 {
                track.push(b);
            }
            let channel = (status & 15) as usize;
            match status & 0xf0 {
                0x90 => held[channel][a as usize] = b != 0,
                0x80 => held[channel][a as usize] = false,
                0xe0 => bends[channel] = a != 0 || b != 64,
                _ => {}
            }
        }
        let end_tick = ((end_sample - self.origin) as u128 * 960 / self.rate as u128)
            .min(u64::MAX as u128) as u64;
        let mut delta = end_tick.saturating_sub(previous);
        for channel in 0..16 {
            for note in 0..128 {
                if held[channel][note] {
                    write_delta(&mut track, delta)?;
                    delta = 0;
                    track.extend_from_slice(&[0x80 | channel as u8, note as u8, 0]);
                }
            }
            if bends[channel] {
                write_delta(&mut track, delta)?;
                delta = 0;
                track.extend_from_slice(&[0xe0 | channel as u8, 0, 64]);
            }
        }
        write_delta(&mut track, delta)?;
        track.extend_from_slice(&[0xff, 0x2f, 0]);
        let len = u32::try_from(track.len()).map_err(|_| RecordingError::TooLong)?;
        let mut file = b"MThd\0\0\0\x06\0\0\0\x01\x01\xe0MTrk".to_vec();
        file.extend_from_slice(&len.to_be_bytes());
        file.extend_from_slice(&track);
        Ok(file)
    }
}

fn write_delta(out: &mut Vec<u8>, value: u64) -> Result<(), RecordingError> {
    if value > 0x0fff_ffff {
        return Err(RecordingError::TooLong);
    }
    let mut buffer = [0u8; 4];
    let mut i = 3;
    let mut value = value as u32;
    buffer[i] = (value & 0x7f) as u8;
    while {
        value >>= 7;
        value != 0
    } {
        i -= 1;
        buffer[i] = ((value & 0x7f) as u8) | 0x80;
    }
    out.extend_from_slice(&buffer[i..]);
    Ok(())
}
