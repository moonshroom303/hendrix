//! Platform-neutral Hendrix audio-to-MIDI and Standard MIDI File support.
pub mod c_api;
pub mod engine;
pub mod midi;
pub mod mobile;
pub mod parameters;
pub mod recording;
pub mod score;

pub use engine::{INFO, Output, Processor};
pub use parameters::Plugin;

#[cfg(test)]
mod allocation_test;
#[cfg(test)]
use allocation_test::probe as allocation_probe;
