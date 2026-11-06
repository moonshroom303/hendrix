//! Hendrix audio-to-MIDI engine by Moonshroom Studio.
//! Processing is prepared ahead of time and produces independently owned note state.
#[cfg(feature = "gui")]
pub mod branding;
pub mod engine;
pub mod ffi;
#[cfg(feature = "gui")]
pub mod gui;
pub mod midi;
pub mod parameters;
pub mod presets;
pub use engine::{INFO, Output, Processor};
pub use parameters::Plugin;
#[cfg(test)]
mod allocation_test;
#[cfg(test)]
use allocation_test::probe as allocation_probe;
