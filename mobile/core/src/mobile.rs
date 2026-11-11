use crate::{INFO, Plugin, Processor, midi::MidiReader};
use std::sync::atomic::Ordering;

/// Sample-accurate timestamp in the current capture session, with MIDI 1.0 bytes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Event {
    pub sample: u64,
    pub data: [u8; 3],
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TunerReading {
    pub hz: f32,
    pub note: u8,
    pub cents: f32,
    pub level_db: f32,
}

pub struct Metrics {
    pub level_db: f32,
    pub window_ms: f32,
    pub bend: f32,
    pub attacks: u64,
    pub notes: [u8; 128],
}

/// One instance per audio stream. Create/prepare off the audio thread.
/// The callback must not allocate, block, perform Bluetooth I/O, or unwind.
pub struct MobileEngine {
    processor: Processor,
    reader: MidiReader,
    audio: [Vec<f32>; 2],
    params: [f32; 20],
    sample: u64,
    channel: u8,
}

impl MobileEngine {
    pub fn new(sample_rate: f32) -> Option<Self> {
        if !sample_rate.is_finite() || !(8_000.0..=192_000.0).contains(&sample_rate) {
            return None;
        }
        let mut processor = Processor::new();
        processor.prepare(sample_rate, 32, 2);
        Some(Self {
            processor,
            reader: MidiReader::default(),
            audio: [vec![0.0; 32], vec![0.0; 32]],
            params: std::array::from_fn(|i| INFO.params[i].default),
            sample: 0,
            channel: 0,
        })
    }

    /// Update on the processing owner, never concurrently with process/reset.
    pub fn set_parameter(&mut self, index: usize, value: f32) -> bool {
        let Some(info) = INFO.params.get(index) else {
            return false;
        };
        let value = info.clamp(value);
        self.params[index] = value;
        self.processor.set_param(info.id, value)
    }

    /// A changed channel is applied on the next process call, flushing old notes first.
    pub fn set_channel(&mut self, channel: u8) -> bool {
        if channel > 15 {
            return false;
        }
        self.channel = channel;
        true
    }

    /// Accepts mono PCM from either the built-in microphone or a selected audio input.
    /// Bluetooth and file writes belong on a separate thread.
    pub fn process(&mut self, input: &[f32], mut emit: impl FnMut(Event)) {
        for chunk in input.chunks(32) {
            for (i, &value) in chunk.iter().enumerate() {
                let value = if value.is_finite() { value } else { 0.0 };
                self.audio[0][i] = value;
                self.audio[1][i] = value;
            }
            self.processor.process(&mut self.audio, chunk.len());
            self.sample = self.sample.saturating_add(chunk.len() as u64);
            let sample = self.sample;
            self.reader
                .poll(&self.processor.output, &self.params, self.channel, |data| {
                    emit(Event { sample, data })
                });
        }
    }

    /// Call when capture stops or before a route/destination change, and deliver all events.
    pub fn reset(&mut self, mut emit: impl FnMut(Event)) {
        let sample = self.sample;
        self.reader.flush(|data| emit(Event { sample, data }));
        self.processor.reset();
        self.processor
            .output
            .hz
            .store(0f32.to_bits(), Ordering::Release);
        self.processor
            .output
            .level
            .store((-120f32).to_bits(), Ordering::Release);
    }

    pub fn sample_position(&self) -> u64 {
        self.sample
    }

    pub fn metrics(&self) -> Metrics {
        let output = &self.processor.output;
        Metrics {
            level_db: f32::from_bits(output.level.load(Ordering::Acquire)),
            window_ms: f32::from_bits(output.window_ms.load(Ordering::Acquire)),
            bend: f32::from_bits(output.bend.load(Ordering::Acquire)),
            attacks: output.attacks.load(Ordering::Acquire),
            notes: std::array::from_fn(|n| output.notes[n].load(Ordering::Acquire) as u8),
        }
    }

    /// Snapshot active MIDI notes at Record. Deliver only to the new recording,
    /// not the live output, so starting recording never retriggers live notes.
    pub fn snapshot(&self, mut emit: impl FnMut(Event)) {
        let sample = self.sample;
        self.reader.snapshot(|data| emit(Event { sample, data }));
    }

    /// Most recent analysis. A4 reference is parameter 8 (400–480 Hz).
    pub fn tuner(&self) -> Option<TunerReading> {
        let hz = f32::from_bits(self.processor.output.hz.load(Ordering::Acquire));
        let level_db = f32::from_bits(self.processor.output.level.load(Ordering::Acquire));
        let reference = self.params[8];
        if !hz.is_finite() || hz <= 0.0 || !level_db.is_finite() || level_db < self.params[2] {
            return None;
        }
        let position = 69.0 + 12.0 * (hz / reference).log2();
        if !position.is_finite() || !(0.0..=127.0).contains(&position) {
            return None;
        }
        let nearest = position.round();
        Some(TunerReading {
            hz,
            note: nearest as u8,
            cents: (position - nearest) * 100.0,
            level_db,
        })
    }
}
