//! C ABI for the AU/VST3/standalone wrapper. Prepare and destroy off the audio thread.
use crate::{INFO, Output, Plugin, Processor, midi::MidiReader};
use std::{
    ffi::c_void,
    sync::{Arc, atomic::Ordering::Acquire},
};
pub type MidiFn = extern "C" fn(*mut c_void, i32, u8, u8, u8);
struct Instance {
    processor: Processor,
    midi: MidiReader,
    io: [Vec<f32>; 2],
}
#[unsafe(no_mangle)]
pub extern "C" fn hendrix_create() -> *mut c_void {
    Box::into_raw(Box::new(Instance {
        processor: Processor::new(),
        midi: MidiReader::default(),
        io: [vec![0.; 32], vec![0.; 32]],
    })) as *mut c_void
}
/// # Safety
/// `ptr` must be a live handle returned by hendrix_create. No concurrent audio processing.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_prepare(ptr: *mut c_void, rate: f64) {
    if let Some(p) = unsafe { (ptr as *mut Instance).as_mut() } {
        p.processor.prepare(rate as f32, 32, 2);
    }
}
/// # Safety
/// Call on the control thread before audio starts. Release the returned handle exactly once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_output_handle(ptr: *const c_void) -> *const c_void {
    unsafe { (ptr as *const Instance).as_ref() }.map_or(std::ptr::null(), |p| {
        Arc::into_raw(p.processor.output.clone()) as *const c_void
    })
}
/// # Safety
/// `handle` must be an unreleased output handle. May run concurrently with processing.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_snapshot(
    handle: *const c_void,
    metrics: *mut f32,
    notes: *mut u8,
) {
    if let Some(o) = unsafe { (handle as *const Output).as_ref() } {
        if !metrics.is_null() {
            let m = unsafe { std::slice::from_raw_parts_mut(metrics, 5) };
            m[0] = f32::from_bits(o.level.load(Acquire));
            m[1] = f32::from_bits(o.window_ms.load(Acquire));
            m[2] = f32::from_bits(o.bend.load(Acquire));
            m[3] = f32::from_bits(o.hz.load(Acquire));
            m[4] = o.attacks.load(Acquire) as f32;
        }
        if !notes.is_null() {
            for (n, out) in unsafe { std::slice::from_raw_parts_mut(notes, 128) }
                .iter_mut()
                .enumerate()
            {
                *out = o.notes[n].load(Acquire) as u8;
            }
        }
    }
}
/// # Safety
/// Pointers must describe the provided planar buffers and 20 parameters; input may alias output.
/// The instance has exactly one audio-thread owner. The MIDI callback must not unwind or block.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_process(
    ptr: *mut c_void,
    inputs: *const *const f64,
    outputs: *const *mut f64,
    frames: i32,
    input_channels: i32,
    output_channels: i32,
    params: *const f32,
    callback: Option<MidiFn>,
    context: *mut c_void,
) {
    let Some(p) = (unsafe { (ptr as *mut Instance).as_mut() }) else {
        return;
    };
    if frames < 0 || frames > 1048576 || outputs.is_null() || params.is_null() {
        return;
    }
    let raw = unsafe { std::slice::from_raw_parts(params, 20) };
    let mut values = [0f32; 20];
    for (i, d) in INFO.params.iter().enumerate() {
        values[i] = d.clamp(raw[i]);
        p.processor.set_param(d.id, values[i]);
    }
    let ins = if inputs.is_null() {
        &[][..]
    } else {
        unsafe { std::slice::from_raw_parts(inputs, input_channels.clamp(0, 2) as usize) }
    };
    let outs = unsafe { std::slice::from_raw_parts(outputs, output_channels.clamp(0, 2) as usize) };
    for offset in (0..frames as usize).step_by(32) {
        let n = 32.min(frames as usize - offset);
        for c in 0..2 {
            for i in 0..n {
                p.io[c][i] = ins
                    .get(c)
                    .or_else(|| ins.first())
                    .filter(|p| !p.is_null())
                    .map_or(0., |ptr| unsafe { *ptr.add(offset + i) } as f32);
            }
        }
        p.processor.process(&mut p.io, n);
        for (c, out) in outs.iter().enumerate() {
            if !out.is_null() {
                for i in 0..n {
                    unsafe {
                        *out.add(offset + i) = p.io[c][i] as f64;
                    }
                }
            }
        }
        p.midi.poll(&p.processor.output, &values, 0, |m| {
            if let Some(cb) = callback {
                cb(context, (offset + n - 1) as i32, m[0], m[1], m[2]);
            }
        });
    }
}
/// # Safety
/// Valid live instance; exclusive audio ownership. Flush before bypass/removal/destination changes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_reset(
    ptr: *mut c_void,
    callback: Option<MidiFn>,
    context: *mut c_void,
) {
    if let Some(p) = unsafe { (ptr as *mut Instance).as_mut() } {
        p.midi.flush(|m| {
            if let Some(cb) = callback {
                cb(context, 0, m[0], m[1], m[2]);
            }
        });
        p.processor.reset();
    }
}
/// # Safety
/// Destroy each instance exactly once, after callbacks have stopped.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_destroy(ptr: *mut c_void) {
    if !ptr.is_null() {
        drop(unsafe { Box::from_raw(ptr as *mut Instance) });
    }
}
/// # Safety
/// Release each output handle exactly once after all UI readers have stopped.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hendrix_release_output(handle: *const c_void) {
    if !handle.is_null() {
        drop(unsafe { Arc::from_raw(handle as *const Output) });
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn c_abi_outputs_real_notes_preserves_audio_and_flushes() {
        extern "C" fn emit(ctx: *mut c_void, _: i32, s: u8, a: u8, b: u8) {
            unsafe { &mut *(ctx as *mut Vec<[u8; 3]>) }.push([s, a, b]);
        }
        let ptr = hendrix_create();
        unsafe {
            hendrix_prepare(ptr, 48000.);
        };
        let out = unsafe { hendrix_output_handle(ptr) };
        let values: Vec<_> = INFO.params.iter().map(|p| p.default).collect();
        let mut events = Vec::<[u8; 3]>::with_capacity(100);
        let mut l = [0f64; 128];
        let mut r = [0f64; 128];
        let mut ol = [0.; 128];
        let mut or = [0.; 128];
        for block in 0..100 {
            for i in 0..128 {
                l[i] =
                    0.15 * (std::f64::consts::TAU * 440. * (block * 128 + i) as f64 / 48000.).sin();
                r[i] = l[i];
            }
            unsafe {
                hendrix_process(
                    ptr,
                    [l.as_ptr(), r.as_ptr()].as_ptr(),
                    [ol.as_mut_ptr(), or.as_mut_ptr()].as_ptr(),
                    128,
                    2,
                    2,
                    values.as_ptr(),
                    Some(emit),
                    &mut events as *mut _ as *mut c_void,
                );
            };
            for i in 0..128 {
                assert!((ol[i] - l[i]).abs() < 1e-7);
            }
        }
        assert_eq!(events.first().unwrap()[0] & 0xf0, 0x90);
        assert_eq!(events.first().unwrap()[1], 69);
        let mut metrics = [0f32; 5];
        let mut notes = [0u8; 128];
        unsafe {
            hendrix_snapshot(out, metrics.as_mut_ptr(), notes.as_mut_ptr());
        }
        assert!(notes[69] > 0);
        assert!(metrics.iter().all(|v| v.is_finite()));
        unsafe {
            hendrix_reset(ptr, Some(emit), &mut events as *mut _ as *mut c_void);
            hendrix_destroy(ptr);
            hendrix_release_output(out);
        }
        assert!(events.contains(&[0x80, 69, 0]));
    }
}
