use hendrix_mobile_core::score::{extract_notes, standard_guitar_position};
use hendrix_mobile_core::{
    mobile::{Event, MobileEngine},
    recording::{Recording, RecordingError},
};

#[test]
fn live_audio_produces_recordable_notes_and_reset_releases_them() {
    let mut engine = MobileEngine::new(48_000.).unwrap();
    let mut events = Vec::new();
    for block in 0..200 {
        let audio: Vec<_> = (0..128)
            .map(|i| {
                (std::f32::consts::TAU * 440. * (block * 128 + i) as f32 / 48_000.).sin() * 0.15
            })
            .collect();
        engine.process(&audio, |e| events.push(e));
    }
    engine.reset(|e| events.push(e));
    assert!(
        events
            .iter()
            .any(|e| e.data[0] & 0xf0 == 0x90 && e.data[1] == 69)
    );
    assert!(events.iter().any(|e| e.data == [0x80, 69, 0]));
    assert!(events.windows(2).all(|w| w[0].sample <= w[1].sample));
    let mut rec = Recording::new(48_000).unwrap();
    for event in events {
        rec.push(event).unwrap();
    }
    let file = rec.to_smf(engine.sample_position()).unwrap();
    assert_eq!(&file[..4], b"MThd");
    assert_eq!(&file[14..18], b"MTrk");
    assert_eq!(
        u32::from_be_bytes(file[18..22].try_into().unwrap()) as usize,
        file.len() - 22
    );
    assert!(
        file.windows(3)
            .any(|b| b[0] == 0x90 && b[1] == 69 && b[2] > 0)
    );
    assert!(file.windows(3).any(|b| b == [0x80, 69, 0]));
}

#[test]
fn interrupted_capture_closes_notes_and_preserves_timing() {
    let mut recording = Recording::new(48_000).unwrap();
    recording
        .push(Event {
            sample: 0,
            data: [0x90, 60, 100],
        })
        .unwrap();
    let file = recording.to_smf(48_000).unwrap();
    assert!(file.windows(5).any(|v| v == [0x87, 0x40, 0x80, 60, 0])); // 960 ticks = one second
    assert_eq!(
        recording.push(Event {
            sample: 1,
            data: [0x80, 60, 0]
        }),
        Ok(())
    );
    assert_eq!(
        recording.push(Event {
            sample: 0,
            data: [0x90, 60, 100]
        }),
        Err(RecordingError::OutOfOrder)
    );
    assert_eq!(recording.to_smf(0), Err(RecordingError::OutOfOrder));
}

#[test]
fn audio_validation_and_silence_are_safe() {
    assert!(MobileEngine::new(f32::NAN).is_none());
    let mut engine = MobileEngine::new(44_100.).unwrap();
    assert!(!engine.set_parameter(20, 1.));
    assert!(!engine.set_channel(16));
    let mut events = Vec::new();
    engine.process(&[f32::NAN; 1024], |e| events.push(e));
    assert!(events.is_empty());
}

#[test]
fn recording_starts_at_button_position_and_extracts_guitar_notes() {
    let mut recording = Recording::new_at(48_000, 960_000).unwrap();
    recording
        .push(Event {
            sample: 984_000,
            data: [0x90, 69, 100],
        })
        .unwrap();
    recording
        .push(Event {
            sample: 1_008_000,
            data: [0x80, 69, 0],
        })
        .unwrap();
    let notes = extract_notes(recording.events(), 1_008_000).unwrap();
    assert_eq!(
        (notes[0].pitch, notes[0].start_sample, notes[0].end_sample),
        (69, 984_000, 1_008_000)
    );
    assert_eq!(standard_guitar_position(69, 22), Some((1, 5)));
    assert_eq!(standard_guitar_position(39, 22), None);
    let file = recording.to_smf(1_008_000).unwrap();
    assert!(file.windows(5).any(|w| w == [0x83, 0x60, 0x90, 69, 100])); // 480 ticks after Record
    assert_eq!(
        recording.push(Event {
            sample: 100,
            data: [0x80, 69, 0]
        }),
        Err(RecordingError::OutOfOrder)
    );
}

#[test]
fn tuning_reference_changes_the_cents_display() {
    let mut engine = MobileEngine::new(48_000.).unwrap();
    assert!(engine.set_parameter(8, 432.));
    for block in 0..200 {
        let samples: Vec<_> = (0..128)
            .map(|i| {
                (std::f32::consts::TAU * 432. * (block * 128 + i) as f32 / 48_000.).sin() * 0.2
            })
            .collect();
        engine.process(&samples, |_| {});
    }
    let reading = engine.tuner().expect("a stable A4 should be detected");
    assert_eq!(reading.note, 69);
    assert!(reading.cents.abs() < 5., "{reading:?}");
    assert!(engine.set_parameter(8, 440.));
    let standard = engine.tuner().unwrap();
    assert!(
        standard.cents < -25. && standard.cents > -40.,
        "{standard:?}"
    );
    engine.reset(|_| {});
    assert!(
        engine.tuner().is_none(),
        "stopping capture clears the tuner"
    );
}

#[test]
fn starting_recording_during_a_held_note_captures_it_without_retriggering_live() {
    let mut engine = MobileEngine::new(48_000.).unwrap();
    for block in 0..200 {
        let samples: Vec<_> = (0..128)
            .map(|i| {
                (std::f32::consts::TAU * 440. * (block * 128 + i) as f32 / 48_000.).sin() * 0.2
            })
            .collect();
        engine.process(&samples, |_| {});
    }
    let origin = engine.sample_position();
    let mut recording = Recording::new_at(48_000, origin).unwrap();
    engine.snapshot(|event| recording.push(event).unwrap());
    assert!(
        recording
            .events()
            .iter()
            .any(|e| e.data[0] & 0xf0 == 0x90 && e.data[1] == 69)
    );
    let notes = extract_notes(recording.events(), origin + 24_000).unwrap();
    assert_eq!(
        (notes[0].start_sample, notes[0].end_sample),
        (origin, origin + 24_000)
    );
}
