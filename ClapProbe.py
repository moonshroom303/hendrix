#!/usr/bin/env python3
"""Smoke-test the actual CLAP binary through its public C ABI."""
from ctypes import (
    CDLL, CFUNCTYPE, POINTER, Structure, byref, c_bool, c_char_p, c_double,
    c_float, c_int16, c_int32, c_int64, c_uint8, c_uint16, c_uint32, c_uint64,
    c_void_p, cast, pointer,
)
from pathlib import Path
import math
import os
import sys

class Version(Structure):
    _fields_ = [("major", c_uint32), ("minor", c_uint32), ("revision", c_uint32)]

class Entry(Structure):
    _fields_ = [
        ("version", Version), ("init", CFUNCTYPE(c_bool, c_char_p)),
        ("deinit", CFUNCTYPE(None)), ("factory", CFUNCTYPE(c_void_p, c_char_p)),
    ]

Extension = CFUNCTYPE(c_void_p, c_void_p, c_char_p)
Callback = CFUNCTYPE(None, c_void_p)

class Host(Structure):
    _fields_ = [
        ("version", Version), ("data", c_void_p), ("name", c_char_p),
        ("vendor", c_char_p), ("url", c_char_p), ("version_text", c_char_p),
        ("extension", Extension), ("restart", Callback), ("process", Callback),
        ("callback", Callback),
    ]

class Factory(Structure):
    _fields_ = [
        ("count", CFUNCTYPE(c_uint32, c_void_p)),
        ("descriptor", CFUNCTYPE(c_void_p, c_void_p, c_uint32)),
        ("create", CFUNCTYPE(c_void_p, c_void_p, POINTER(Host), c_char_p)),
    ]

class Plugin(Structure):
    _fields_ = [
        ("description", c_void_p), ("data", c_void_p),
        ("init", CFUNCTYPE(c_bool, c_void_p)), ("destroy", Callback),
        ("activate", CFUNCTYPE(c_bool, c_void_p, c_double, c_uint32, c_uint32)),
        ("deactivate", Callback), ("start", CFUNCTYPE(c_bool, c_void_p)),
        ("stop", Callback), ("reset", Callback),
        ("process", CFUNCTYPE(c_int32, c_void_p, c_void_p)),
        ("extension", Extension), ("main_thread", Callback),
    ]

class Audio(Structure):
    _fields_ = [
        ("data32", POINTER(POINTER(c_float))), ("data64", c_void_p),
        ("channels", c_uint32), ("latency", c_uint32), ("constant_mask", c_uint64),
    ]

class Header(Structure):
    _fields_ = [
        ("size", c_uint32), ("time", c_uint32), ("space", c_uint16),
        ("kind", c_uint16), ("flags", c_uint32),
    ]

class Note(Structure):
    _fields_ = [
        ("header", Header), ("id", c_int32), ("port", c_int16), ("channel", c_int16),
        ("key", c_int16), ("velocity", c_double),
    ]

class Midi(Structure):
    _fields_ = [("header", Header), ("port", c_uint16), ("data", c_uint8 * 3)]

Size = CFUNCTYPE(c_uint32, c_void_p)
Get = CFUNCTYPE(c_void_p, c_void_p, c_uint32)
Push = CFUNCTYPE(c_bool, c_void_p, POINTER(Header))

class InputEvents(Structure):
    _fields_ = [("context", c_void_p), ("size", Size), ("get", Get)]

class OutputEvents(Structure):
    _fields_ = [("context", c_void_p), ("push", Push)]

class Process(Structure):
    _fields_ = [
        ("time", c_int64), ("frames", c_uint32), ("transport", c_void_p),
        ("inputs", POINTER(Audio)), ("outputs", POINTER(Audio)),
        ("input_count", c_uint32), ("output_count", c_uint32),
        ("input_events", POINTER(InputEvents)), ("output_events", POINTER(OutputEvents)),
    ]

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def probe(path):
    library = path / "Contents/MacOS/Hendrix" if path.is_dir() else path
    module = CDLL(str(library))
    entry = Entry.in_dll(module, "clap_entry")
    require(entry.init(os.fsencode(path)), "CLAP entry init failed")
    instance = None
    active = started = False
    try:
        factory_ptr = entry.factory(b"clap.plugin-factory")
        require(factory_ptr, "Missing plugin factory")
        factory = cast(factory_ptr, POINTER(Factory)).contents
        require(factory.count(factory_ptr) == 1, "Expected one plugin")
        host = Host(Version(1, 2, 0), None, b"Hendrix probe", b"Moonshroom Studio",
                    b"https://github.com/moonshroom303/hendrix", b"1.0",
                    Extension(lambda *_: None), Callback(lambda *_: None),
                    Callback(lambda *_: None), Callback(lambda *_: None))
        instance = factory.create(factory_ptr, byref(host), b"studio.moonshroom.hendrix")
        require(instance, "CLAP factory failed to create Hendrix")
        plugin = cast(instance, POINTER(Plugin)).contents
        require(plugin.init(instance), "CLAP plugin init failed")
        active = plugin.activate(instance, 48000., 32, 128)
        require(active, "CLAP activate failed")
        started = plugin.start(instance)
        require(started, "CLAP start failed")
        frames = 128
        left, right, out_left, out_right = [(c_float * frames)() for _ in range(4)]
        ins = Audio((POINTER(c_float) * 2)(left, right), None, 2, 0, 0)
        outs = Audio((POINTER(c_float) * 2)(out_left, out_right), None, 2, 0, 0)
        notes = []
        errors = []

        @Push
        def push(_, header):
            event = header.contents
            if event.time >= frames:
                errors.append("MIDI timestamp out of range")
            if event.space == 0 and event.kind in (0, 1):
                note = cast(header, POINTER(Note)).contents
                notes.append((event.kind == 0, note.key))
            elif event.space == 0 and event.kind == 10:
                status, key, velocity = cast(header, POINTER(Midi)).contents.data
                if status & 0xf0 in (0x80, 0x90):
                    notes.append((status & 0xf0 == 0x90 and velocity > 0, key))
            return True

        inputs = InputEvents(None, Size(lambda *_: 0), Get(lambda *_: None))
        outputs = OutputEvents(None, push)
        data = Process(0, frames, None, pointer(ins), pointer(outs), 1, 1, pointer(inputs), pointer(outputs))
        for block in range(300):
            data.time = block * frames
            for i in range(frames):
                left[i] = right[i] = (
                    0.4 * math.sin(math.tau * 440 * (data.time + i) / 48000)
                    if block < 150 else 0
                )
            require(plugin.process(instance, byref(data)) != 0, "CLAP process failed")
            require(all(abs(a-b) < 1e-6 for a,b in zip(left,out_left)), "Audio passthrough differs")
        require(not errors, errors)
        require((True, 69) in notes and (False, 69) in notes, f"A4 note pair missing: {notes}")
        print("CLAP: A4 note-on and note-off, valid timestamps, audio passthrough")
    finally:
        if instance:
            if started:
                plugin.stop(instance)
            if active:
                plugin.deactivate(instance)
            plugin.destroy(instance)
        entry.deinit()

if __name__ == "__main__":
    probe(Path(sys.argv[1]).resolve())
