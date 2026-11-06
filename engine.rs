//! Streaming mono/poly audio-to-note analysis. No allocation or UI work in process().
//! Outputs are independent note owners; the prepared bus reads them on the audio thread.
use crate::parameters::{Category, ParamInfo, Plugin, PluginInfo, Taper, Unit};
use rustfft::{Fft, FftPlanner, num_complex::Complex32};
use std::sync::{
    Arc,
    atomic::{AtomicU32, AtomicU64, Ordering::SeqCst},
};

pub const ID: &str = "internal:moonshroom.audio-to-midi.v1";
pub fn is_builtin(id: &str) -> bool {
    id == ID
}
const fn p(
    id: &'static str,
    name: &'static str,
    min: f32,
    max: f32,
    default: f32,
    unit: Unit,
    choices: &'static [&'static str],
) -> ParamInfo {
    ParamInfo {
        id,
        name,
        min,
        max,
        default,
        unit,
        taper: Taper::Linear,
        choices,
    }
}
pub static INFO: PluginInfo = PluginInfo {
    id: ID,
    name: "Hendrix",
    short_name: "Hendrix",
    category: Category::Other,
    is_instrument: false,
    audiosuite: false,
    params: &[
        p(
            "0",
            "Erkennung",
            0.,
            1.,
            0.,
            Unit::Choice,
            &["Mono · einzelne Noten", "Poly · Akkorde"],
        ),
        p("1", "MIDI senden", 0., 1., 1., Unit::Toggle, &[]),
        p("2", "Eingangsschwelle", -72., -6., -48., Unit::Db, &[]),
        p("3", "Notenstabilität", 0., 80., 12., Unit::Ms, &[]),
        p("4", "Loslassen", 10., 300., 40., Unit::Ms, &[]),
        p("5", "Tiefste Note", 24., 96., 36., Unit::None, &[]),
        p("6", "Höchste Note", 36., 108., 96., Unit::None, &[]),
        p("7", "Stimmen", 1., 6., 6., Unit::None, &[]),
        p("8", "Referenz A4", 400., 480., 440., Unit::Hz, &[]),
        p("9", "Transponieren", -24., 24., 0., Unit::Semitones, &[]),
        p(
            "10",
            "Velocity · 0 = Dynamik",
            0.,
            127.,
            0.,
            Unit::None,
            &[],
        ),
        p(
            "11",
            "Nachprüfung",
            0.,
            2.,
            1.,
            Unit::Choice,
            &["Schnell", "Ausgewogen", "Genau"],
        ),
        p("12", "Audio durchlassen", 0., 1., 1., Unit::Toggle, &[]),
        p("13", "Mono-Bends", 0., 1., 0., Unit::Toggle, &[]),
        p(
            "14",
            "Bend-Bereich",
            0.,
            2.,
            0.,
            Unit::Choice,
            &["±2 Halbtöne", "±12 Halbtöne", "±24 Halbtöne"],
        ),
        p("15", "Bend glätten", 0., 80., 8., Unit::Ms, &[]),
        p(
            "16",
            "Mono · Erkennungsfenster",
            0.,
            3.,
            0.,
            Unit::Choice,
            &["Direkt", "5 ms", "10 ms", "15 ms"],
        ),
        p("17", "Mono · Neue Anschläge", 0., 1., 1., Unit::Toggle, &[]),
        p("18", "Anschlag-Threshold", 0.5, 18., 3., Unit::Db, &[]),
        p(
            "19",
            "Anschlag-Mindestabstand",
            10.,
            150.,
            35.,
            Unit::Ms,
            &[],
        ),
    ],
};
pub struct Output {
    pub target: AtomicU64,
    pub notes: [AtomicU64; 128],
    pub serial: AtomicU64,
    pub level: AtomicU32,
    pub hz: AtomicU32,
    pub bend: AtomicU32,
    pub window_ms: AtomicU32,
    pub attacks: AtomicU64,
}
impl Default for Output {
    fn default() -> Self {
        Self {
            target: AtomicU64::new(0),
            notes: std::array::from_fn(|_| AtomicU64::new(0)),
            serial: AtomicU64::new(0),
            level: AtomicU32::new((-120f32).to_bits()),
            hz: AtomicU32::new(0),
            bend: AtomicU32::new(0),
            window_ms: AtomicU32::new(0),
            attacks: AtomicU64::new(0),
        }
    }
}
#[derive(serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct RouteState {
    format: String,
    target: u64,
}
pub fn route_state(target: u64) -> Vec<u8> {
    serde_json::to_vec(&RouteState {
        format: ID.into(),
        target,
    })
    .unwrap()
}
impl Output {
    fn note(&self, n: usize, v: u8) {
        let old = self.notes[n].load(SeqCst);
        self.notes[n].store(((old >> 8).wrapping_add(1) << 8) | v as u64, SeqCst);
    }
    fn clear(&self) {
        self.bend.store(0f32.to_bits(), SeqCst);
        for (n, s) in self.notes.iter().enumerate() {
            if s.load(SeqCst) & 255 != 0 {
                self.note(n, 0);
            }
        }
    }
}
const MAX_FRAME: usize = 2048;
const FFT: usize = 4096;
const HOP: usize = 128;
#[derive(Clone, Copy, Default)]
struct Peak {
    hz: f32,
    amp: f32,
}
/// A deliberately provisional estimate from one observed waveform period.
/// Schmitt arming rejects crossings near zero; the longer YIN window verifies it.
struct FirstCycle {
    clock: u64,
    previous: f32,
    armed: [bool; 2],
    crossing: [Option<f64>; 2],
    energy: [f64; 2],
    count: [usize; 2],
    quiet: usize,
}
impl Default for FirstCycle {
    fn default() -> Self {
        Self {
            clock: 0,
            previous: 0.,
            armed: [false; 2],
            crossing: [None; 2],
            energy: [0.; 2],
            count: [0; 2],
            quiet: 0,
        }
    }
}
impl FirstCycle {
    fn push(&mut self, x: f32, rate: f32, lo: f32, hi: f32, threshold: f32) -> Option<(f32, f32)> {
        self.clock = self.clock.wrapping_add(1);
        for i in 0..2 {
            self.energy[i] += (x as f64).powi(2);
            self.count[i] = self.count[i].saturating_add(1);
        }
        self.quiet = if x.abs() >= threshold {
            0
        } else {
            self.quiet.saturating_add(1)
        };
        if x < -threshold * 0.25 {
            self.armed[0] = true;
        }
        if x > threshold * 0.25 {
            self.armed[1] = true;
        }
        let mut result = None;
        let direction = if self.previous <= 0. && x > 0. {
            Some(0)
        } else if self.previous >= 0. && x < 0. {
            Some(1)
        } else {
            None
        };
        if let Some(i) = direction.filter(|&i| self.armed[i]) {
            let crossing = self.clock as f64 - x as f64 / (x - self.previous) as f64;
            if let Some(last) = self.crossing[i] {
                let period = (crossing - last) as f32;
                let hz = rate / period;
                let rms = (self.energy[i] / self.count[i].max(1) as f64).sqrt() as f32;
                if hz.is_finite() && hz >= lo * 0.975 && hz <= hi * 1.025 && rms >= threshold {
                    result = Some((hz, rms));
                }
            }
            self.crossing[i] = Some(crossing);
            self.armed[i] = false;
            self.energy[i] = 0.;
            self.count[i] = 0;
        }
        self.previous = x;
        result
    }
}
#[derive(Default)]
struct PickDetector {
    peak: f32,
    baseline: f32,
    hold: usize,
    quiet: usize,
    cooldown: usize,
    audible: bool,
    armed: bool,
}
#[derive(Clone, Copy)]
struct PickSettings {
    gate: f32,
    ratio: f32,
    rearm: f32,
    hold: usize,
    gap: usize,
    release: usize,
    decay: f32,
    attack: f32,
    relax: f32,
    repeat: bool,
}
impl PickDetector {
    // The peak hold spans a full pitch period: waveform cycles must never look
    // like individual picks. A slower reference follows the decay between picks.
    fn push(&mut self, x: f32, s: PickSettings) -> u8 {
        let amplitude = x.abs();
        self.quiet = if amplitude >= s.gate {
            0
        } else {
            self.quiet.saturating_add(1)
        };
        if amplitude >= self.peak {
            self.peak = amplitude;
            self.hold = s.hold;
        } else if self.hold > 0 {
            self.hold -= 1;
        } else {
            self.peak *= s.decay;
        }
        self.cooldown = self.cooldown.saturating_sub(1);
        if !self.audible && amplitude >= s.gate {
            self.audible = true;
            self.armed = false;
            self.cooldown = s.gap;
            self.baseline = self.peak;
            return 1;
        }
        if self.quiet >= s.release {
            self.audible = false;
            self.armed = false;
            self.baseline = self.peak;
            return 0;
        }
        let reference = self.baseline.max(s.gate);
        let rise = self.peak / reference;
        if rise < s.rearm {
            self.armed = true;
        }
        let picked = self.audible
            && s.repeat
            && self.armed
            && self.cooldown == 0
            && rise >= s.ratio
            && self.peak >= s.gate;
        let coefficient = if self.peak > self.baseline {
            s.attack
        } else {
            s.relax
        };
        self.baseline += (self.peak - self.baseline) * coefficient;
        if picked {
            self.cooldown = s.gap;
            self.armed = false;
            self.baseline = self.peak;
            2
        } else {
            0
        }
    }
}
struct Acquisition {
    age: usize,
    found: Option<(usize, f32, f32)>,
    repick: bool,
}
pub struct Processor {
    pub output: Arc<Output>,
    values: [f32; 20],
    rate: f32,
    step: usize,
    acc: f32,
    acc_count: usize,
    ring: [f32; MAX_FRAME],
    at: usize,
    filled: usize,
    hop: usize,
    frame: [f32; MAX_FRAME],
    difference: [f32; MAX_FRAME / 2 + 1],
    fft: Arc<dyn Fft<f32>>,
    spectrum: Vec<Complex32>,
    scratch: Vec<Complex32>,
    magnitudes: Vec<f32>,
    candidate: [usize; 128],
    missing: [usize; 128],
    held: [u8; 128],
    first_cycle: FirstCycle,
    provisional: Option<(usize, usize)>,
    picks: PickDetector,
    acquisition: Option<Acquisition>,
    rejected: bool,
}
impl Processor {
    pub fn new() -> Self {
        let fft = FftPlanner::new().plan_fft_forward(FFT);
        let scratch = vec![Complex32::default(); fft.get_inplace_scratch_len()];
        Self {
            output: Arc::new(Default::default()),
            values: std::array::from_fn(|i| INFO.params[i].default),
            rate: 12000.,
            step: 4,
            acc: 0.,
            acc_count: 0,
            ring: [0.; MAX_FRAME],
            at: 0,
            filled: 0,
            hop: 0,
            frame: [0.; MAX_FRAME],
            difference: [0.; MAX_FRAME / 2 + 1],
            fft,
            spectrum: vec![Complex32::default(); FFT],
            scratch,
            magnitudes: vec![0.; FFT / 2 + 1],
            candidate: [0; 128],
            missing: [0; 128],
            held: [0; 128],
            first_cycle: Default::default(),
            provisional: None,
            picks: Default::default(),
            acquisition: None,
            rejected: false,
        }
    }
    fn length(&self) -> usize {
        let n = [512, 1024, 2048][self.values[11].round() as usize];
        // The provisional attack stays independent of this later verification.
        // Low bass notes still need a window covering their full lag search.
        if self.direct() {
            let periods = (2. * self.rate / self.frequency(self.values[5].min(self.values[6])))
                .ceil() as usize;
            n.max(periods.next_power_of_two()).min(MAX_FRAME)
        } else {
            n
        }
    }
    fn direct(&self) -> bool {
        self.values[0] < 0.5
    }
    fn limit_ms(&self) -> f32 {
        [0., 5., 10., 15.][self.values[16].round() as usize]
    }
    fn clear_notes(&mut self) {
        self.output.clear();
        self.held.fill(0);
        self.candidate.fill(0);
        self.missing.fill(0);
        self.provisional = None;
    }
    fn release(&mut self) {
        self.clear_notes();
        self.first_cycle = Default::default();
        self.picks = Default::default();
        self.acquisition = None;
        self.rejected = false;
    }
    fn velocity(&self, db: f32) -> u8 {
        if self.values[10] >= 1. {
            self.values[10].round() as u8
        } else {
            ((db + 60.) / 54. * 126. + 1.).clamp(1., 127.) as u8
        }
    }
    fn direct_sample(&mut self, x: f32, settings: PickSettings, lo: f32, hi: f32, deadline: usize) {
        if let Some((_, age)) = &mut self.provisional {
            *age = age.saturating_add(1);
        }
        let onset = self.picks.push(x, settings);
        if onset > 0 && self.values[1] >= 0.5 {
            self.acquisition = Some(Acquisition {
                age: 0,
                found: None,
                repick: onset == 2,
            });
            self.rejected = false;
            self.first_cycle = Default::default();
            self.provisional = None;
        }
        if onset == 0 {
            if let Some(a) = &mut self.acquisition {
                a.age += 1;
            }
        }
        if let Some((hz, rms)) = self.first_cycle.push(x, self.rate, lo, hi, settings.gate) {
            self.output.window_ms.store((1000. / hz).to_bits(), SeqCst);
            if let Some(note) = self.note_number(hz) {
                if let Some(a) = &mut self.acquisition {
                    a.found = Some((note, hz, rms));
                }
            }
        }
        let ready = self.acquisition.as_ref().is_some_and(|a| {
            if deadline == 0 {
                a.found.is_some()
            } else {
                a.age >= deadline
            }
        });
        if ready {
            let a = self.acquisition.take().unwrap();
            if let Some((note, hz, rms)) = a.found {
                let db = 20. * rms.max(1e-6).log10();
                let velocity = self.velocity(db);
                for old in 0..128 {
                    if old != note && self.held[old] > 0 {
                        self.held[old] = 0;
                        self.candidate[old] = 0;
                        self.output.note(old, 0);
                    }
                }
                self.held[note] = velocity;
                self.candidate[note] = (self.values[3] * 0.001 * self.rate).round() as usize;
                self.missing[note] = 0;
                self.provisional = Some((note, 0));
                self.output.note(note, velocity);
                if a.repick {
                    self.output.attacks.fetch_add(1, SeqCst);
                }
                self.output.bend.store(0f32.to_bits(), SeqCst);
                self.output.level.store(db.to_bits(), SeqCst);
                self.output.hz.store(hz.to_bits(), SeqCst);
            } else {
                self.clear_notes();
                self.rejected = true;
            }
        }
        if self.first_cycle.quiet >= settings.release
            && (self.acquisition.is_some()
                || self.provisional.is_some()
                || self.held.iter().any(|v| *v > 0))
        {
            let quiet = self.first_cycle.quiet;
            self.release();
            self.first_cycle.quiet = quiet;
            self.picks.quiet = quiet;
        }
    }
    fn mono(&mut self, n: usize) -> Option<f32> {
        let lo = self.frequency(self.values[5].min(self.values[6]));
        let hi = self.frequency(self.values[5].max(self.values[6]));
        let max_tau = ((self.rate / lo) as usize).min(n / 2);
        let min_tau = ((self.rate / hi) as usize).max(2);
        if max_tau <= min_tau + 2 {
            return None;
        }
        let width = n - max_tau;
        let mut running = 0.;
        self.difference[0] = 1.;
        for tau in 1..=max_tau {
            let mut sum = 0.;
            for j in 0..width {
                let d = self.frame[j] - self.frame[j + tau];
                sum += d * d;
            }
            running += sum;
            self.difference[tau] = if running > 1e-16 {
                sum * tau as f32 / running
            } else {
                1.
            };
        }
        let mut tau = min_tau;
        while tau < max_tau {
            if self.difference[tau] < 0.14 {
                while tau + 1 < max_tau && self.difference[tau + 1] < self.difference[tau] {
                    tau += 1;
                }
                let (a, b, c) = (
                    self.difference[tau - 1],
                    self.difference[tau],
                    self.difference[tau + 1],
                );
                let d = a - 2. * b + c;
                let shift = if d.abs() > 1e-12 {
                    (0.5 * (a - c) / d).clamp(-1., 1.)
                } else {
                    0.
                };
                return Some(self.rate / (tau as f32 + shift));
            }
            tau += 1;
        }
        None
    }
    fn frequency(&self, n: f32) -> f32 {
        self.values[8] * 2f32.powf((n - 69.) / 12.)
    }
    fn note_number(&self, hz: f32) -> Option<usize> {
        let n = 69. + 12. * (hz / self.values[8]).log2();
        let rounded = n.round();
        let limit = self.limit_ms();
        let allowed = !self.direct() || limit == 0. || 1000. / hz <= limit + 0.001;
        if allowed
            && n.is_finite()
            && (n - rounded).abs() < 0.42
            && rounded >= self.values[5].min(self.values[6])
            && rounded <= self.values[5].max(self.values[6])
        {
            Some(rounded as usize)
        } else {
            None
        }
    }
    fn poly(&mut self, n: usize, wanted: &mut [u8; 128], velocity: u8) {
        self.spectrum.fill(Complex32::default());
        for i in 0..n {
            self.spectrum[i].re = self.frame[i]
                * (0.5 - 0.5 * (std::f32::consts::TAU * i as f32 / (n - 1) as f32).cos());
        }
        self.fft
            .process_with_scratch(&mut self.spectrum, &mut self.scratch);
        let mut maximum = 0f32;
        let mut arithmetic = 0.;
        let mut geometric = 0.;
        let mut count = 0;
        for i in 1..=FFT / 2 {
            let a = self.spectrum[i].norm();
            self.magnitudes[i] = a;
            maximum = maximum.max(a);
            if i > 5 {
                arithmetic += a;
                geometric += a.max(1e-12).ln();
                count += 1;
            }
        }
        if maximum < 1e-8
            || ((geometric / count as f32).exp() / (arithmetic / count as f32).max(1e-12)) > 0.65
        {
            return;
        }
        let mut peaks = [Peak::default(); 64];
        let mut used = 0;
        let minimum = self.frequency(self.values[5].min(self.values[6])) * 0.96;
        let mut i = ((minimum * FFT as f32 / self.rate).floor() as usize).max(2);
        while i < FFT / 2 - 1 {
            let a = self.magnitudes[i];
            if a > maximum * 0.10 && a > self.magnitudes[i - 1] && a >= self.magnitudes[i + 1] {
                let (l, m, r) = (
                    self.magnitudes[i - 1].max(1e-12).ln(),
                    a.ln(),
                    self.magnitudes[i + 1].max(1e-12).ln(),
                );
                let denom = l - 2. * m + r;
                let shift = if denom.abs() > 1e-12 {
                    (0.5 * (l - r) / denom).clamp(-0.5, 0.5)
                } else {
                    0.
                };
                let peak = Peak {
                    hz: (i as f32 + shift) * self.rate / FFT as f32,
                    amp: a,
                };
                if used < peaks.len() {
                    peaks[used] = peak;
                    used += 1;
                } else {
                    let mut weakest = 0;
                    for k in 1..used {
                        if peaks[k].amp < peaks[weakest].amp {
                            weakest = k;
                        }
                    }
                    if peak.amp > peaks[weakest].amp {
                        peaks[weakest] = peak;
                    }
                }
            }
            i += 1;
        }
        // Frequency order lets lower fundamentals explain their decaying partials.
        peaks[..used].sort_unstable_by(|a, b| a.hz.total_cmp(&b.hz));
        let mut accepted = [Peak::default(); 64];
        let mut valid = 0;
        for peak in peaks.into_iter().take(used) {
            let harmonic = accepted[..valid].iter().any(|base| {
                let h = (peak.hz / base.hz).round();
                h >= 2.
                    && h <= 16.
                    && (12. * (peak.hz / (base.hz * h)).log2()).abs() < 0.32
                    && peak.amp < base.amp * (1.25 / h.sqrt())
            });
            if !harmonic {
                accepted[valid] = peak;
                valid += 1;
            }
        }
        accepted[..valid].sort_unstable_by(|a, b| b.amp.total_cmp(&a.amp));
        let mut voices = 0;
        for peak in accepted.into_iter().take(valid) {
            if let Some(note) = self.note_number(peak.hz) {
                if wanted[note] == 0 {
                    wanted[note] =
                        ((velocity as f32 * (peak.amp / maximum).sqrt()).round() as u8).max(1);
                    voices += 1;
                    if voices >= self.values[7].round() as usize {
                        break;
                    }
                }
            }
        }
    }
    fn analyse(&mut self) {
        let n = self.length();
        for i in 0..n {
            self.frame[i] = self.ring[(self.at + MAX_FRAME - n + i) % MAX_FRAME];
        }
        let mean = self.frame[..n].iter().sum::<f32>() / n as f32;
        let mut energy = 0.;
        for x in &mut self.frame[..n] {
            *x -= mean;
            energy += *x * *x;
        }
        let rms = (energy / n as f32).sqrt();
        let db = 20. * rms.max(1e-6).log10();
        self.output.level.store(db.to_bits(), SeqCst);
        if !self.direct() {
            self.output
                .window_ms
                .store((n as f32 * 1000. / self.rate).to_bits(), SeqCst);
        }
        // Do not cancel a newly assumed note using a window still containing its
        // preceding silence. The first full fresh window then verifies the estimate.
        if self.direct()
            && (self.acquisition.is_some()
                || self.rejected
                || self.provisional.is_some_and(|(_, age)| age < n)
                || !self.held.iter().any(|v| *v > 0))
        {
            return;
        }
        let velocity = self.velocity(db);
        let mut wanted = [0u8; 128];
        let mut hz = 0.;
        let mut bend = None;
        if db >= self.values[2] && self.values[1] >= 0.5 {
            if self.values[0] < 0.5 {
                if let Some(f) = self.mono(n) {
                    hz = f;
                    if let Some((old, age)) = self.provisional {
                        if let Some(note) = self.note_number(f) {
                            // Preserve genuine bends; correct an initial harmonic/octave assumption.
                            if old != note && (self.values[13] < 0.5 || old.abs_diff(note) >= 11) {
                                if self.limit_ms() > 0. {
                                    self.clear_notes();
                                    self.rejected = true;
                                    return;
                                }
                                self.held[old] = 0;
                                self.candidate[old] = 0;
                                self.output.note(old, 0);
                                self.held[note] = velocity;
                                self.output.note(note, velocity);
                                self.provisional = None;
                            } else if age >= n * 2 {
                                self.provisional = None;
                            }
                        }
                    }
                    if self.values[13] >= 0.5 {
                        let pitch = 69. + 12. * (f / self.values[8]).log2();
                        let anchor = self
                            .held
                            .iter()
                            .position(|v| *v > 0)
                            .or_else(|| self.note_number(f));
                        if let Some(note) = anchor {
                            wanted[note] = velocity;
                            let range = [2., 12., 24.][self.values[14].round() as usize];
                            bend = Some((pitch - note as f32).clamp(-range, range));
                        }
                    } else if let Some(note) = self.note_number(f) {
                        wanted[note] = velocity;
                    }
                }
            } else {
                self.poly(n, &mut wanted, velocity);
            }
        }
        if let Some(bend) = bend {
            let previous = f32::from_bits(self.output.bend.load(SeqCst));
            let alpha = if self.values[15] > 0. {
                1. - (-(HOP as f32) / (self.rate * self.values[15] * 0.001)).exp()
            } else {
                1.
            };
            self.output
                .bend
                .store((previous + (bend - previous) * alpha).to_bits(), SeqCst);
        }
        self.output.hz.store(hz.to_bits(), SeqCst);
        let stable = (self.values[3] * 0.001 * self.rate).round() as usize;
        let release = (self.values[4] * 0.001 * self.rate).round() as usize;
        for note in 0..128 {
            if wanted[note] > 0 {
                self.missing[note] = 0;
                self.candidate[note] = self.candidate[note].saturating_add(HOP);
                if self.held[note] == 0
                    && self.candidate[note] >= stable
                    && (!self.direct() || self.limit_ms() == 0.)
                {
                    self.held[note] = wanted[note];
                    self.output.note(note, self.held[note]);
                }
            } else {
                self.candidate[note] = 0;
                self.missing[note] = self.missing[note].saturating_add(HOP);
                if self.held[note] > 0 && self.missing[note] >= release {
                    self.held[note] = 0;
                    self.output.note(note, 0);
                }
            }
        }
        if !self.held.iter().any(|v| *v > 0) {
            self.output.bend.store(0f32.to_bits(), SeqCst);
        }
    }
}
impl Plugin for Processor {
    fn info(&self) -> &'static PluginInfo {
        &INFO
    }
    fn prepare(&mut self, sr: f32, _: usize, _: usize) {
        let sr = if sr.is_finite() && sr >= 8000. {
            sr
        } else {
            48000.
        };
        self.step = (sr / 12000.).round().max(1.) as usize;
        self.rate = sr / self.step as f32;
        self.reset();
        self.output.window_ms.store(
            (if self.direct() {
                0.
            } else {
                self.length() as f32 * 1000. / self.rate
            })
            .to_bits(),
            SeqCst,
        );
    }
    fn reset(&mut self) {
        self.release();
        self.ring.fill(0.);
        self.at = 0;
        self.filled = 0;
        self.hop = 0;
        self.acc = 0.;
        self.acc_count = 0;
    }
    fn set_param(&mut self, id: &str, v: f32) -> bool {
        let Some(i) = INFO.params.iter().position(|p| p.id == id) else {
            return false;
        };
        let v = INFO.params[i].clamp(v);
        if self.values[i] == v {
            return true;
        }
        if matches!(i, 0 | 1 | 5 | 6 | 8 | 9 | 11 | 13 | 14 | 16) {
            self.release();
        }
        self.values[i] = v;
        if i == 11 || i == 0 || i == 16 {
            self.output.window_ms.store(
                (if self.direct() {
                    0.
                } else {
                    self.length() as f32 * 1000. / self.rate
                })
                .to_bits(),
                SeqCst,
            );
        }
        true
    }
    fn param(&self, id: &str) -> Option<f32> {
        INFO.params
            .iter()
            .position(|p| p.id == id)
            .map(|i| self.values[i])
    }
    fn process(&mut self, io: &mut [Vec<f32>], frames: usize) {
        let n = io.iter().map(Vec::len).min().unwrap_or(0).min(frames);
        if io.is_empty() {
            return;
        }
        let direct = self.direct();
        let lo = self.frequency(self.values[5].min(self.values[6]));
        let hi = self.frequency(self.values[5].max(self.values[6]));
        let stable = self.acquisition.is_none()
            && self.provisional.is_none()
            && !self.rejected
            && self.held.iter().any(|v| *v > 0);
        let hz = if stable {
            f32::from_bits(self.output.hz.load(SeqCst)).max(lo)
        } else {
            lo
        };
        let ratio = 10f32.powf(self.values[18] / 20.);
        let settings = PickSettings {
            gate: 10f32.powf(self.values[2] / 20.),
            ratio,
            rearm: ratio.sqrt(),
            hold: (self.rate / hz * 1.15).ceil() as usize,
            gap: (self.values[19] * 0.001 * self.rate).round() as usize,
            release: (self.values[4] * 0.001 * self.rate).round() as usize,
            decay: (-1. / (self.rate * 0.010)).exp(),
            attack: 1. - (-1. / (self.rate * 0.008)).exp(),
            relax: 1. - (-1. / (self.rate * 0.080)).exp(),
            repeat: self.values[17] >= 0.5,
        };
        let deadline = (self.limit_ms() * 0.001 * self.rate).round() as usize;
        for i in 0..n {
            let mut x = 0.;
            for c in io.iter() {
                let v = c[i];
                if v.is_finite() {
                    x += v.clamp(-16., 16.);
                }
            }
            x /= io.len() as f32;
            self.acc += x;
            self.acc_count += 1;
            if self.acc_count == self.step {
                let sample = self.acc / self.step as f32;
                self.ring[self.at] = sample;
                self.at = (self.at + 1) % MAX_FRAME;
                self.filled = (self.filled + 1).min(MAX_FRAME);
                self.acc = 0.;
                self.acc_count = 0;
                if direct {
                    self.direct_sample(sample, settings, lo, hi, deadline);
                }
                self.hop += 1;
                if self.hop >= HOP && self.filled >= self.length() {
                    self.hop = 0;
                    self.analyse();
                }
            }
        }
        if self.values[12] < 0.5 {
            for c in io {
                c[..n].fill(0.);
            }
        }
        self.output.serial.fetch_add(1, SeqCst);
    }
    fn all_notes_off(&mut self) {
        self.reset();
    }
    fn save_state(&mut self) -> Option<Vec<u8>> {
        Some(route_state(self.output.target.load(SeqCst)))
    }
    fn state_reader(&mut self) -> Option<Box<dyn crate::parameters::PluginStateReader>> {
        let o = self.output.clone();
        Some(Box::new(move || Some(route_state(o.target.load(SeqCst)))))
    }
    fn load_state(&mut self, data: &[u8]) -> bool {
        if data.len() > 256 {
            return false;
        }
        let Ok(s) = serde_json::from_slice::<RouteState>(data) else {
            return false;
        };
        if s.format != ID {
            return false;
        }
        self.release();
        self.output.target.store(s.target, SeqCst);
        true
    }
}
impl Drop for Processor {
    fn drop(&mut self) {
        self.output.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn play(
        p: &mut Processor,
        sr: f32,
        notes: &[u8],
        harmonics: usize,
        seconds: f32,
        block: usize,
    ) {
        let mut io = [vec![0.; block], vec![0.; block]];
        let total = (sr * seconds) as usize;
        let mut at = 0;
        while at < total {
            let n = block.min(total - at);
            for i in 0..n {
                let t = (at + i) as f32 / sr;
                let mut v = 0.;
                for note in notes {
                    let f = 440. * 2f32.powf((*note as f32 - 69.) / 12.);
                    for h in 1..=harmonics {
                        v += 0.12 / notes.len().max(1) as f32 / h as f32
                            * (std::f32::consts::TAU * f * h as f32 * t).sin();
                    }
                }
                io[0][i] = v;
                io[1][i] = v;
            }
            p.process(&mut io, n);
            at += n;
        }
    }
    fn notes(p: &Processor) -> Vec<u8> {
        p.output
            .notes
            .iter()
            .enumerate()
            .filter(|(_, v)| v.load(SeqCst) & 255 != 0)
            .map(|(n, _)| n as u8)
            .collect()
    }
    #[test]
    fn timed_modes_emit_at_their_window_end_and_never_emit_a_late_low_note() {
        let mut measurements = Vec::new();
        for sr in [44100., 48000., 96000.] {
            for (mode, ms) in [(1, 5.), (2, 10.), (3, 15.)] {
                let mut p = Processor::new();
                p.prepare(sr, 1, 2);
                p.set_param("16", mode as f32);
                let mut io = [vec![0.; 1], vec![0.; 1]];
                let mut found = None;
                for i in 0..(sr * 0.05) as usize {
                    let x = 0.15 * (std::f32::consts::TAU * 440. * i as f32 / sr).sin();
                    io[0][0] = x;
                    io[1][0] = x;
                    p.process(&mut io, 1);
                    if !notes(&p).is_empty() {
                        found = Some((i + 1) as f32 * 1000. / sr);
                        break;
                    }
                }
                let actual = found.expect("Timed mode did not emit a detectable note");
                assert_eq!(notes(&p), vec![69]);
                assert!(
                    actual >= ms - 0.1 && actual < ms + 0.3,
                    "mode={mode}, rate={sr}, got {actual} ms"
                );
                measurements
                    .push(serde_json::json!({"rate":sr,"window_ms":ms,"first_midi_ms":actual}));
                let mut p = Processor::new();
                p.prepare(sr, 128, 2);
                p.set_param("16", mode as f32);
                play(&mut p, sr, &[40], 1, 0.4, 128);
                assert!(
                    notes(&p).is_empty(),
                    "Low note must remain rejected after the deadline, mode {mode}"
                );
                assert_eq!(
                    p.output
                        .notes
                        .iter()
                        .map(|n| n.load(SeqCst) >> 8)
                        .sum::<u64>(),
                    0,
                    "Rejected notes must never have emitted an earlier ghost note"
                );
            }
        }
        if let Some(dir) = std::env::var_os("SHROOMS_LATENCY_REPORT") {
            std::fs::write(
                std::path::Path::new(&dir).join("timed-mode-measurements.json"),
                serde_json::to_vec_pretty(&measurements).unwrap(),
            )
            .unwrap();
        }
    }
    fn plucked(p: &mut Processor, note: u8, seconds: f32) {
        let rate = 48000.;
        let hz = 440. * 2f32.powf((note as f32 - 69.) / 12.);
        let mut io = [vec![0.; 32], vec![0.; 32]];
        for block in 0..(rate * seconds / 32.) as usize {
            for i in 0..32 {
                let t = (block * 32 + i) as f32 / rate;
                let since = t % 0.173;
                let amp = 0.025 + 0.22 * (-since / 0.035).exp();
                let x = amp * (std::f32::consts::TAU * hz * t).sin();
                io[0][i] = x;
                io[1][i] = x;
            }
            p.process(&mut io, 32);
        }
    }
    #[test]
    fn repeated_picks_retrigger_the_same_sustained_pitch_and_threshold_controls_sensitivity() {
        for mode in [0., 1., 2., 3.] {
            let mut p = Processor::new();
            p.prepare(48000., 32, 2);
            p.set_param("16", mode);
            plucked(&mut p, 69, 0.66);
            assert_eq!(notes(&p), vec![69]);
            assert_eq!(
                p.output.attacks.load(SeqCst),
                3,
                "Every new pick should retrigger once, mode={mode}"
            );
            assert_eq!(p.output.notes[69].load(SeqCst) >> 8, 4);
        }
        for disabled in [false, true] {
            let mut p = Processor::new();
            p.prepare(48000., 32, 2);
            if disabled {
                p.set_param("17", 0.);
            } else {
                p.set_param("18", 18.);
            }
            plucked(&mut p, 69, 0.66);
            assert_eq!(notes(&p), vec![69]);
            assert_eq!(p.output.attacks.load(SeqCst), 0);
        }
    }
    #[test]
    fn limited_modes_do_not_insert_late_replacement_notes_after_a_pitch_change() {
        for mode in [1., 2., 3.] {
            let mut p = Processor::new();
            p.prepare(48000., 32, 2);
            p.set_param("16", mode);
            play(&mut p, 48000., &[69], 1, 0.06, 32);
            assert_eq!(notes(&p), vec![69]);
            play(&mut p, 48000., &[72], 1, 0.3, 32);
            assert_eq!(
                p.output.notes[72].load(SeqCst) >> 8,
                0,
                "Late replacement after deadline in mode {mode}"
            );
        }
    }
    #[test]
    fn steady_low_tones_and_bends_do_not_look_like_repeated_picks() {
        for note in [24u8, 28, 40, 57, 69, 81] {
            let mut p = Processor::new();
            p.prepare(48000., 128, 2);
            p.set_param("5", 24.);
            play(&mut p, 48000., &[note], 4, 0.7, 128);
            assert_eq!(notes(&p), vec![note]);
            assert_eq!(
                p.output.attacks.load(SeqCst),
                0,
                "Steady waveform became a pick, note={note}"
            );
        }
        let mut p = Processor::new();
        p.prepare(48000., 128, 2);
        p.set_param("5", 24.);
        play(&mut p, 48000., &[81], 1, 0.2, 128);
        play(&mut p, 48000., &[], 1, 0.1, 128);
        play(&mut p, 48000., &[24], 1, 0.4, 128);
        assert_eq!(notes(&p), vec![24]);
        assert_eq!(
            p.output.attacks.load(SeqCst),
            0,
            "The previous high pitch must not shorten peak hold for a fresh low note"
        );
    }

    #[test]
    fn direct_start_uses_the_first_period_without_waiting_for_a_frame_or_stability() {
        let mut measurements = Vec::new();
        for sr in [44100., 48000., 96000.] {
            for block in [1usize, 32, 128] {
                for note in [40u8, 57, 69, 81] {
                    for warm in [false, true] {
                        let mut p = Processor::new();
                        p.prepare(sr, block, 2);
                        p.set_param("11", 0.);
                        p.set_param("3", 80.);
                        if warm {
                            play(&mut p, sr, &[], 1, 0.2, block);
                        }
                        let f = 440. * 2f32.powf((note as f32 - 69.) / 12.);
                        let period = sr / f;
                        let mut io = [vec![0.; block], vec![0.; block]];
                        let mut at = 0;
                        let mut found = None;
                        while at < (sr * 0.040) as usize {
                            for i in 0..block {
                                let x =
                                    0.15 * (std::f32::consts::TAU * f * (at + i) as f32 / sr).sin();
                                io[0][i] = x;
                                io[1][i] = x;
                            }
                            p.process(&mut io, block);
                            at += block;
                            if !notes(&p).is_empty() {
                                found = Some(at);
                                break;
                            }
                        }
                        let samples = found.expect("No first-cycle note");
                        assert_eq!(
                            notes(&p),
                            vec![note],
                            "sr={sr}, block={block}, note={note}, warm={warm}"
                        );
                        let ms = samples as f32 * 1000. / sr;
                        // One complete observed period needs two equal-direction crossings. From
                        // an arbitrary onset, the first such crossing can take half a period.
                        assert!(
                            samples as f32 <= period * 1.5 + block as f32 + sr * 0.0005,
                            "Did not start on first observed period: {ms} ms, note {note}"
                        );
                        if note >= 69 {
                            assert!(
                                ms < 6.,
                                "Upper notes should start below 6 ms before I/O buffers: {ms}"
                            );
                        }
                        measurements.push(serde_json::json!({"rate":sr,"block":block,"note":note,"warm":warm,"first_midi_ms":ms,"period_ms":1000./f}));
                    }
                }
            }
        }
        if let Some(dir) = std::env::var_os("SHROOMS_LATENCY_REPORT") {
            std::fs::write(
                std::path::Path::new(&dir).join("first-cycle-measurements.json"),
                serde_json::to_vec_pretty(&measurements).unwrap(),
            )
            .unwrap();
        }
    }
    #[test]
    fn direct_start_releases_quick_notes_and_does_not_retrigger_from_stale_audio() {
        let mut p = Processor::new();
        p.prepare(48000., 32, 2);
        p.set_param("11", 0.);
        play(&mut p, 48000., &[69], 1, 0.008, 32);
        assert_eq!(notes(&p), vec![69]);
        play(&mut p, 48000., &[], 1, 0.045, 32);
        assert!(notes(&p).is_empty());
        let stamp = p.output.notes[69].load(SeqCst);
        play(&mut p, 48000., &[], 1, 0.3, 32);
        assert_eq!(p.output.notes[69].load(SeqCst), stamp);
        play(&mut p, 48000., &[72], 1, 0.01, 32);
        assert_eq!(notes(&p), vec![72]);
        p.set_param("1", 0.);
        assert!(notes(&p).is_empty());
        play(&mut p, 48000., &[72], 1, 0.1, 32);
        assert!(notes(&p).is_empty());
    }
    #[test]
    fn first_observed_cycle_does_not_assume_a_zero_phase_at_the_input_onset() {
        for offset in [0., 0.4, 1.7, 3.3, 5.4] {
            for note in [40u8, 57, 69, 81] {
                let mut p = Processor::new();
                p.prepare(48000., 32, 2);
                p.set_param("11", 0.);
                let f = 440. * 2f32.powf((note as f32 - 69.) / 12.);
                let mut io = [vec![0.; 32], vec![0.; 32]];
                let mut at = 0;
                while notes(&p).is_empty() && at < 1600 {
                    for i in 0..32 {
                        let x = 0.15
                            * (offset + std::f32::consts::TAU * f * (at + i) as f32 / 48000.).sin();
                        io[0][i] = x;
                        io[1][i] = x;
                    }
                    p.process(&mut io, 32);
                    at += 32;
                }
                assert_eq!(notes(&p), vec![note], "phase={offset}, note={note}");
                assert!(
                    at as f32 <= 48000. / f * 1.5 + 64.,
                    "phase={offset}, note={note}, samples={at}"
                );
            }
        }
    }
    #[test]
    fn direct_start_verifies_an_early_harmonic_and_preserves_bends_and_single_note_ownership() {
        let mut p = Processor::new();
        p.prepare(48000., 32, 2);
        p.set_param("11", 0.);
        p.set_param("13", 1.);
        p.set_param("15", 0.);
        play(&mut p, 48000., &[81], 1, 0.008, 32);
        assert_eq!(notes(&p), vec![81]);
        play(&mut p, 48000., &[69], 1, 0.2, 32);
        assert_eq!(notes(&p), vec![69]);
        let stamp = p.output.notes[69].load(SeqCst);
        let mut phase = 0f32;
        let mut io = [vec![0.; 32], vec![0.; 32]];
        for block in 0..600 {
            let semitone = (block as f32 / 400.).min(1.);
            let hz = 440. * 2f32.powf(semitone / 12.);
            for i in 0..32 {
                phase = (phase + hz / 48000.).fract();
                let x = 0.15 * (std::f32::consts::TAU * phase).sin();
                io[0][i] = x;
                io[1][i] = x;
            }
            p.process(&mut io, 32);
        }
        assert_eq!(notes(&p), vec![69]);
        assert_eq!(p.output.notes[69].load(SeqCst), stamp);
        assert!((f32::from_bits(p.output.bend.load(SeqCst)) - 1.).abs() < 0.03);
    }
    #[test]
    fn direct_cycle_is_allocation_free_and_silence_dc_reset_and_mode_switch_are_safe() {
        let mut p = Processor::new();
        p.prepare(48000., 128, 2);
        p.set_param("11", 0.);
        let mut io = [vec![0.; 128], vec![0.; 128]];
        let mut phase = 0f32;
        let a = crate::allocation_probe(|| {
            for _ in 0..200 {
                for i in 0..128 {
                    phase = (phase + 440. / 48000.).fract();
                    let x = 0.15 * (std::f32::consts::TAU * phase).sin();
                    io[0][i] = x;
                    io[1][i] = x;
                }
                p.process(&mut io, 128)
            }
        });
        assert_eq!(a["allocations_or_reallocations"], 0);
        assert_eq!(a["deallocations"], 0);
        assert_eq!(notes(&p), vec![69]);
        p.reset();
        io[0].fill(0.);
        io[1].fill(0.);
        for _ in 0..100 {
            p.process(&mut io, 128);
        }
        assert!(notes(&p).is_empty());
        io[0].fill(0.3);
        io[1].fill(0.3);
        for _ in 0..100 {
            p.process(&mut io, 128);
        }
        assert!(notes(&p).is_empty());
        p.set_param("0", 1.);
        assert!(!p.direct());
        assert!((f32::from_bits(p.output.window_ms.load(SeqCst)) - 42.6667).abs() < 0.1);
        p.set_param("0", 0.);
        assert!(p.direct());
        assert_eq!(p.output.window_ms.load(SeqCst), 0f32.to_bits());
    }
    #[test]
    fn first_cycle_low_notes_remain_held_when_the_longer_verifier_takes_over() {
        for sr in [44100., 48000.] {
            for note in [24u8, 28, 36, 40] {
                let mut p = Processor::new();
                p.prepare(sr, 128, 2);
                p.set_param("5", 24.);
                p.set_param("11", 0.);
                let f = 440. * 2f32.powf((note as f32 - 69.) / 12.);
                let mut io = [vec![0.; 128], vec![0.; 128]];
                let mut stamp = None;
                for block in 0..180 {
                    for i in 0..128 {
                        let x = 0.15
                            * (std::f32::consts::TAU * f * (block * 128 + i) as f32 / sr).sin();
                        io[0][i] = x;
                        io[1][i] = x;
                    }
                    p.process(&mut io, 128);
                    if let Some(stamp) = stamp {
                        assert_eq!(notes(&p), vec![note]);
                        assert_eq!(
                            p.output.notes[note as usize].load(SeqCst),
                            stamp,
                            "Low note retriggered at block {block}, note {note}"
                        );
                    } else if !notes(&p).is_empty() {
                        assert_eq!(notes(&p), vec![note]);
                        stamp = Some(p.output.notes[note as usize].load(SeqCst));
                    }
                }
                assert!(stamp.is_some());
            }
        }
    }
    #[test]
    fn mono_bends_keep_the_anchor_note_and_recentre_after_release() {
        let mut p = Processor::new();
        p.prepare(48000., 128, 2);
        p.set_param("13", 1.);
        p.set_param("15", 0.);
        let mut phase = 0f32;
        let mut io = [vec![0.; 128], vec![0.; 128]];
        for block in 0..360 {
            let semitone = if block < 100 {
                0.
            } else {
                ((block - 100) as f32 / 100.).min(1.)
            };
            let hz = 440. * 2f32.powf(semitone / 12.);
            for i in 0..128 {
                phase = (phase + hz / 48000.).fract();
                io[0][i] = 0.15 * (std::f32::consts::TAU * phase).sin();
                io[1][i] = io[0][i];
            }
            p.process(&mut io, 128);
        }
        assert_eq!(notes(&p), vec![69]);
        assert!((f32::from_bits(p.output.bend.load(SeqCst)) - 1.).abs() < 0.025);
        play(&mut p, 48000., &[], 1, 0.3, 128);
        assert!(notes(&p).is_empty());
        assert_eq!(f32::from_bits(p.output.bend.load(SeqCst)), 0.);
    }

    #[test]
    fn mono_tracks_real_tones_at_three_rates_and_releases() {
        for (sr, block) in [(44100., 127), (48000., 32), (96000., 512)] {
            for note in [40, 57, 69, 81] {
                let mut p = Processor::new();
                p.prepare(sr, block, 2);
                play(&mut p, sr, &[note], 5, 0.4, block);
                assert_eq!(notes(&p), vec![note], "sr={sr} note={note}");
                play(&mut p, sr, &[], 1, 0.3, block);
                assert!(notes(&p).is_empty());
            }
        }
    }
    #[test]
    fn poly_tracks_major_and_minor_chords_without_emitting_their_overtones() {
        for chord in [
            &[60, 64, 67][..],
            &[57, 60, 64][..],
            &[48, 55, 62][..],
            &[48, 55, 60, 64, 67, 72][..],
        ] {
            for harmonics in [1, 6] {
                let mut p = Processor::new();
                p.prepare(48000., 128, 2);
                p.set_param("0", 1.);
                play(&mut p, 48000., chord, harmonics, 0.5, 128);
                assert_eq!(notes(&p), chord, "{chord:?} harmonics={harmonics}");
            }
        }
    }
    #[test]
    fn silence_noise_dc_and_transients_do_not_leave_notes_or_change_dry_audio() {
        let mut p = Processor::new();
        p.prepare(48000., 128, 2);
        p.set_param("0", 1.);
        let mut random = 77u32;
        let mut io = [vec![0.; 128], vec![0.; 128]];
        for _ in 0..160 {
            for i in 0..128 {
                random = random.wrapping_mul(1664525).wrapping_add(1013904223);
                let x = (random as f32 / u32::MAX as f32 - 0.5) * 0.05;
                io[0][i] = x;
                io[1][i] = x;
            }
            let copy = io.clone();
            p.process(&mut io, 128);
            assert_eq!(io, copy);
        }
        assert!(notes(&p).is_empty());
        io[0].fill(0.3);
        io[1].fill(0.3);
        for _ in 0..160 {
            p.process(&mut io, 128);
        }
        assert!(notes(&p).is_empty());
        io[0][0] = f32::NAN;
        io[1][1] = f32::INFINITY;
        p.process(&mut io, 128);
        p.reset();
        assert!(notes(&p).is_empty());
    }
    #[test]
    fn streaming_analysis_is_allocation_free_in_both_modes() {
        for mode in [0., 1.] {
            let mut p = Processor::new();
            p.prepare(48000., 128, 2);
            p.set_param("0", mode);
            let mut io = [vec![0.; 128], vec![0.; 128]];
            for i in 0..128 {
                io[0][i] = (i as f32 * 0.0576).sin() * 0.1;
                io[1][i] = io[0][i];
            }
            let a = crate::allocation_probe(|| {
                for _ in 0..100 {
                    p.process(&mut io, 128)
                }
            });
            assert_eq!(a["allocations_or_reallocations"], 0);
            assert_eq!(a["deallocations"], 0);
        }
    }
}
