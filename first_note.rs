//! Offline integration example: a synthetic A4 becomes MIDI messages.
use hendrix_audio_to_midi::{INFO, Plugin, Processor, midi::MidiReader};
fn main() {
    let mut processor = Processor::new();
    processor.prepare(48000., 32, 2);
    let params = std::array::from_fn(|i| INFO.params[i].default);
    let mut midi = MidiReader::default();
    let mut io = [vec![0.; 32], vec![0.; 32]];
    for block in 0..1200 {
        for i in 0..32 {
            let x = if block < 600 {
                0.15 * (std::f32::consts::TAU * 440. * (block * 32 + i) as f32 / 48000.).sin()
            } else {
                0.
            };
            io[0][i] = x;
            io[1][i] = x;
        }
        processor.process(&mut io, 32);
        midi.poll(&processor.output, &params, 0, |m| {
            println!(
                "{:.3} ms: {:02X} {:02X} {:02X}",
                (block + 1) as f32 * 32. / 48.,
                m[0],
                m[1],
                m[2]
            )
        });
    }
    midi.flush(|m| println!("flush: {m:02X?}"));
}
