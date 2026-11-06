//! Parameter metadata adapted from SoundCraft. See SoundCraft-LICENSE-MIT.txt.
/// Plugin category, used to group the insert menu.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub enum Category {
    Eq,
    Dynamics,
    PitchShift,
    Reverb,
    Delay,
    Modulation,
    Harmonic,
    Dither,
    Instrument,
    Other,
}

/// Display unit of a parameter.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize)]
pub enum Unit {
    None,
    Db,
    Hz,
    Ms,
    Percent,
    Ratio,
    Semitones,
    Cents,
    Seconds,
    Toggle,
    Choice,
}

/// How a control maps its 0..1 travel onto the parameter range.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize)]
pub enum Taper {
    Linear,
    Log,
}

/// Static description of one plugin parameter.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct ParamInfo {
    pub id: &'static str,
    pub name: &'static str,
    pub min: f32,
    pub max: f32,
    pub default: f32,
    pub unit: Unit,
    pub taper: Taper,
    /// Labels for `Unit::Choice` parameters (value is the index).
    pub choices: &'static [&'static str],
}

impl ParamInfo {
    /// Clamps any value (including NaN/inf) into this parameter's legal range.
    /// NaN becomes the default; toggles and choices round to whole numbers.
    pub fn clamp(&self, value: f32) -> f32 {
        let v = if value.is_nan() { self.default } else { value };
        let v = v.clamp(self.min, self.max);
        match self.unit {
            Unit::Toggle | Unit::Choice => v.round().clamp(self.min, self.max),
            _ => v,
        }
    }

    /// Maps a value to 0..1 control travel honoring the taper.
    pub fn to_normalized(&self, value: f32) -> f32 {
        let v = self.clamp(value);
        let span = self.max - self.min;
        if span <= 0.0 {
            return 0.0;
        }
        match self.taper {
            Taper::Log if self.min > 0.0 => {
                ((v / self.min).ln() / (self.max / self.min).ln()).clamp(0.0, 1.0)
            }
            _ => ((v - self.min) / span).clamp(0.0, 1.0),
        }
    }

    /// Maps 0..1 control travel back to a value honoring the taper.
    pub fn from_normalized(&self, t: f32) -> f32 {
        let t = if t.is_nan() { 0.0 } else { t.clamp(0.0, 1.0) };
        let v = match self.taper {
            Taper::Log if self.min > 0.0 => self.min * (self.max / self.min).powf(t),
            _ => self.min + (self.max - self.min) * t,
        };
        self.clamp(v)
    }

    /// Human-readable label for a value (choice label, On/Off, or number with unit).
    pub fn format(&self, value: f32) -> String {
        let v = self.clamp(value);
        match self.unit {
            Unit::Choice => self
                .choices
                .get(v as usize)
                .map(|s| (*s).to_string())
                .unwrap_or_else(|| format!("{v}")),
            Unit::Toggle => if v >= 0.5 { "On" } else { "Off" }.to_string(),
            Unit::Db => {
                if v <= -95.9 {
                    "-inf dB".to_string()
                } else {
                    format!("{v:.1} dB")
                }
            }
            Unit::Hz => {
                if v >= 1000.0 {
                    format!("{:.2} kHz", v / 1000.0)
                } else {
                    format!("{v:.1} Hz")
                }
            }
            Unit::Ms => format!("{v:.1} ms"),
            Unit::Percent => format!("{v:.0}%"),
            Unit::Ratio => format!("{v:.1}:1"),
            Unit::Semitones => format!("{v:.0} st"),
            Unit::Cents => format!("{v:.0} ct"),
            Unit::Seconds => format!("{v:.2} s"),
            Unit::None => format!("{v:.2}"),
        }
    }
}

/// Static description of a plugin.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct PluginInfo {
    pub id: &'static str,
    pub name: &'static str,
    /// At most 8 characters, for insert slots.
    pub short_name: &'static str,
    pub category: Category,
    pub params: &'static [ParamInfo],
    pub is_instrument: bool,
    /// Usable as an offline (AudioSuite-style) process.
    pub audiosuite: bool,
}

impl PluginInfo {
    /// Looks up a parameter description by id.
    pub fn param(&self, id: &str) -> Option<&'static ParamInfo> {
        self.params.iter().find(|p| p.id == id)
    }
}

/// Minimal host interface used by Hendrix. Allocate during preparation, not processing.
pub trait Plugin: Send {
    fn info(&self) -> &'static PluginInfo;
    fn prepare(&mut self, sample_rate: f32, max_block: usize, channels: usize);
    fn reset(&mut self);
    fn set_param(&mut self, id: &str, value: f32) -> bool;
    fn param(&self, id: &str) -> Option<f32>;
    fn process(&mut self, io: &mut [Vec<f32>], frames: usize);
    fn all_notes_off(&mut self) {}
    fn save_state(&mut self) -> Option<Vec<u8>> {
        None
    }
    fn state_reader(&mut self) -> Option<Box<dyn PluginStateReader>> {
        None
    }
    fn load_state(&mut self, _: &[u8]) -> bool {
        false
    }
}
pub trait PluginStateReader: Send {
    fn save_state(&mut self) -> Option<Vec<u8>>;
}
impl<F: FnMut() -> Option<Vec<u8>> + Send> PluginStateReader for F {
    fn save_state(&mut self) -> Option<Vec<u8>> {
        self()
    }
}
