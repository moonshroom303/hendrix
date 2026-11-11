//! C interface for native mobile hosts. All handles have one exclusive owner.
use crate::{
    mobile::{Event, MobileEngine},
    recording::Recording,
    score,
};
use std::{ffi::c_void, ptr, slice};

pub type EventCallback = extern "C" fn(*mut c_void, u64, u8, u8, u8);

#[repr(C)]
pub struct HendrixTunerReading {
    pub hz: f32,
    pub note: u8,
    pub cents: f32,
    pub level_db: f32,
}

#[repr(C)]
pub struct HendrixMetrics {
    pub level: f32,
    pub window: f32,
    pub bend: f32,
    pub attacks: u64,
}

#[repr(C)]
pub struct HendrixScoreNote {
    pub start_sample: u64,
    pub end_sample: u64,
    pub pitch: u8,
    pub velocity: u8,
    pub channel: u8,
    pub guitar_string: u8,
    pub guitar_fret: u8,
}

#[unsafe(no_mangle)]
pub extern "C" fn hendrix_mobile_create(sample_rate: f32) -> *mut MobileEngine {
    MobileEngine::new(sample_rate).map_or(ptr::null_mut(), |engine| Box::into_raw(Box::new(engine)))
}

/// # Safety
/// The handle must be valid, exclusively owned and destroyed exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_destroy(handle: *mut MobileEngine) {
    if !handle.is_null() {
        drop(unsafe { Box::from_raw(handle) });
    }
}

/// # Safety
/// A live, exclusively owned handle is required.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_set_parameter(
    handle: *mut MobileEngine,
    index: u32,
    value: f32,
) -> bool {
    unsafe { handle.as_mut() }.is_some_and(|engine| engine.set_parameter(index as usize, value))
}

/// # Safety
/// A live, exclusively owned handle is required.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_set_channel(
    handle: *mut MobileEngine,
    channel: u8,
) -> bool {
    unsafe { handle.as_mut() }.is_some_and(|engine| engine.set_channel(channel))
}

/// # Safety
/// The handle must be valid and exclusively owned by the caller.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_sample_position(handle: *const MobileEngine) -> u64 {
    unsafe { handle.as_ref() }.map_or(0, MobileEngine::sample_position)
}

/// # Safety
/// The handle is exclusively owned and output points to writable storage.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_tuner(
    handle: *const MobileEngine,
    output: *mut HendrixTunerReading,
) -> bool {
    let Some(reading) = (unsafe { handle.as_ref() }).and_then(MobileEngine::tuner) else {
        return false;
    };
    let Some(output) = (unsafe { output.as_mut() }) else {
        return false;
    };
    *output = HendrixTunerReading {
        hz: reading.hz,
        note: reading.note,
        cents: reading.cents,
        level_db: reading.level_db,
    };
    true
}

/// # Safety
/// The handle is exclusively owned. Output is writable and notes holds 128 bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_metrics(
    handle: *const MobileEngine,
    output: *mut HendrixMetrics,
    notes: *mut u8,
) -> bool {
    let Some(engine) = (unsafe { handle.as_ref() }) else {
        return false;
    };
    let Some(output) = (unsafe { output.as_mut() }) else {
        return false;
    };
    if notes.is_null() {
        return false;
    }
    let metrics = engine.metrics();
    *output = HendrixMetrics {
        level: metrics.level_db,
        window: metrics.window_ms,
        bend: metrics.bend,
        attacks: metrics.attacks,
    };
    unsafe { ptr::copy_nonoverlapping(metrics.notes.as_ptr(), notes, metrics.notes.len()) };
    true
}

/// # Safety
/// The handle is exclusively owned. The callback must be realtime-safe.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_snapshot(
    handle: *const MobileEngine,
    callback: Option<EventCallback>,
    context: *mut c_void,
) {
    if let Some(engine) = unsafe { handle.as_ref() } {
        engine.snapshot(|event| {
            if let Some(cb) = callback {
                cb(
                    context,
                    event.sample,
                    event.data[0],
                    event.data[1],
                    event.data[2],
                );
            }
        });
    }
}

/// # Safety
/// Input must point to `frames` readable f32 samples; the handle is exclusively
/// owned. The callback must be realtime-safe and must not re-enter this handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_process(
    handle: *mut MobileEngine,
    input: *const f32,
    frames: u32,
    callback: Option<EventCallback>,
    context: *mut c_void,
) -> bool {
    let Some(engine) = (unsafe { handle.as_mut() }) else {
        return false;
    };
    if input.is_null() || frames > 1_048_576 {
        return false;
    }
    let input = unsafe { slice::from_raw_parts(input, frames as usize) };
    engine.process(input, |event| {
        if let Some(callback) = callback {
            callback(
                context,
                event.sample,
                event.data[0],
                event.data[1],
                event.data[2],
            );
        }
    });
    true
}

/// # Safety
/// The handle is valid and exclusively owned. Deliver the callback's note-offs.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_mobile_reset(
    handle: *mut MobileEngine,
    callback: Option<EventCallback>,
    context: *mut c_void,
) {
    if let Some(engine) = unsafe { handle.as_mut() } {
        engine.reset(|event| {
            if let Some(cb) = callback {
                cb(
                    context,
                    event.sample,
                    event.data[0],
                    event.data[1],
                    event.data[2],
                );
            }
        });
    }
}

/// The recording functions allocate and must only be used off the audio thread.
#[unsafe(no_mangle)]
pub extern "C" fn hendrix_recording_create(sample_rate: u32) -> *mut Recording {
    Recording::new(sample_rate).map_or(ptr::null_mut(), |r| Box::into_raw(Box::new(r)))
}

#[unsafe(no_mangle)]
pub extern "C" fn hendrix_recording_create_at(
    sample_rate: u32,
    origin_sample: u64,
) -> *mut Recording {
    Recording::new_at(sample_rate, origin_sample)
        .map_or(ptr::null_mut(), |r| Box::into_raw(Box::new(r)))
}

/// # Safety
/// The handle must be valid, exclusively owned and destroyed exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_recording_destroy(handle: *mut Recording) {
    if !handle.is_null() {
        drop(unsafe { Box::from_raw(handle) });
    }
}

/// # Safety
/// A live, exclusively owned recording handle is required.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_recording_add(
    handle: *mut Recording,
    sample: u64,
    status: u8,
    a: u8,
    b: u8,
) -> bool {
    unsafe { handle.as_mut() }.is_some_and(|r| {
        r.push(Event {
            sample,
            data: [status, a, b],
        })
        .is_ok()
    })
}

/// Returns required bytes, or zero on error. With a null buffer, query the size;
/// otherwise copies only when capacity is large enough. Run on a worker thread.
/// # Safety
/// Handle is live and exclusively owned. A non-null buffer is writable for capacity bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_recording_export(
    handle: *const Recording,
    end_sample: u64,
    buffer: *mut u8,
    capacity: usize,
) -> usize {
    let Some(recording) = (unsafe { handle.as_ref() }) else {
        return 0;
    };
    let Ok(bytes) = recording.to_smf(end_sample) else {
        return 0;
    };
    if !buffer.is_null() && capacity >= bytes.len() {
        unsafe {
            ptr::copy_nonoverlapping(bytes.as_ptr(), buffer, bytes.len());
        }
    }
    bytes.len()
}

/// Query the count with a null output. `SIZE_MAX` signals invalid input.
/// Values retain absolute sample timestamps; subtract recording origin for display.
/// # Safety
/// Handle is live/exclusively owned. A non-null output holds `capacity` notes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_recording_notes(
    handle: *const Recording,
    end_sample: u64,
    output: *mut HendrixScoreNote,
    capacity: usize,
) -> usize {
    let Some(recording) = (unsafe { handle.as_ref() }) else {
        return usize::MAX;
    };
    if end_sample < recording.origin_sample() {
        return usize::MAX;
    }
    let Ok(notes) = score::extract_notes(recording.events(), end_sample) else {
        return usize::MAX;
    };
    if !output.is_null() && capacity >= notes.len() {
        for (i, note) in notes.iter().enumerate() {
            let (string, fret) = score::standard_guitar_position(note.pitch, 22).unwrap_or((0, 0));
            unsafe {
                output.add(i).write(HendrixScoreNote {
                    start_sample: note.start_sample,
                    end_sample: note.end_sample,
                    pitch: note.pitch,
                    velocity: note.velocity,
                    channel: note.channel,
                    guitar_string: string,
                    guitar_fret: fret,
                });
            }
        }
    }
    notes.len()
}
