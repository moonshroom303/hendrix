#!/usr/bin/env python3
"""Open the shipped CLAP editor in a real X11 parent and verify rendered labels.

Run under Xvfb or Xwayland. This complements (not replaces) Bitwig testing.
Requires ImageMagick and Tesseract; captures are kept for visual inspection.
"""
from ctypes import c_ulong, c_int, c_long, c_uint, addressof
from ClapProbe import *
import subprocess
import threading
import time
import faulthandler
faulthandler.enable()


BoolPlugin = CFUNCTYPE(c_bool, c_void_p)
ApiSupport = CFUNCTYPE(c_bool, c_void_p, c_char_p, c_bool)
GetSize = CFUNCTYPE(c_bool, c_void_p, POINTER(c_uint32), POINTER(c_uint32))


class Window(Structure):
    _fields_ = [("api", c_char_p), ("x11", c_ulong)]


class Gui(Structure):
    _fields_ = [
        ("supported", ApiSupport), ("preferred", c_void_p),
        ("create", ApiSupport), ("destroy", Callback),
        ("scale", CFUNCTYPE(c_bool, c_void_p, c_double)),
        ("size", GetSize), ("can_resize", BoolPlugin), ("resize_hints", c_void_p),
        ("adjust_size", GetSize),
        ("set_size", CFUNCTYPE(c_bool, c_void_p, c_uint32, c_uint32)),
        ("parent", CFUNCTYPE(c_bool, c_void_p, POINTER(Window))),
        ("transient", c_void_p), ("title", c_void_p),
        ("show", BoolPlugin), ("hide", BoolPlugin),
    ]


class HostGui(Structure):
    _fields_ = [
        ("hints", Callback),
        ("resize", CFUNCTYPE(c_bool, c_void_p, c_uint32, c_uint32)),
        ("show", BoolPlugin), ("hide", BoolPlugin),
        ("closed", CFUNCTYPE(None, c_void_p, c_bool)),
    ]


def probe_gui(path, output):
    output.mkdir(parents=True, exist_ok=True)
    x = CDLL("libX11.so.6")
    signatures = {
        "XInitThreads": (c_int, []),
        "XOpenDisplay": (c_void_p, [c_char_p]),
        "XDefaultRootWindow": (c_ulong, [c_void_p]),
        "XCreateSimpleWindow": (c_ulong, [c_void_p, c_ulong, c_int, c_int, c_uint, c_uint, c_uint, c_ulong, c_ulong]),
        "XStoreName": (c_int, [c_void_p, c_ulong, c_char_p]),
        "XMapWindow": (c_int, [c_void_p, c_ulong]),
        "XResizeWindow": (c_int, [c_void_p, c_ulong, c_uint, c_uint]),
        "XSelectInput": (c_int, [c_void_p, c_ulong, c_long]),
        "XPending": (c_int, [c_void_p]),
        "XNextEvent": (c_int, [c_void_p, c_void_p]),
        "XFlush": (c_int, [c_void_p]),
        "XDestroyWindow": (c_int, [c_void_p, c_ulong]),
        "XCloseDisplay": (c_int, [c_void_p]),
    }
    for name, (result, args) in signatures.items():
        fn = getattr(x, name)
        fn.restype, fn.argtypes = result, args
    x.XInitThreads()
    display = x.XOpenDisplay(None)
    require(display, "X11 display is unavailable")
    root = x.XDefaultRootWindow(display)
    window = x.XCreateSimpleWindow(display, root, 20, 20, 900, 860, 0, 0, 0x404040)
    x.XStoreName(display, window, b"Hendrix Linux GUI regression")
    x.XSelectInput(display, window, (1 << 15) | (1 << 17))
    x.XMapWindow(display, window)
    x.XFlush(display)
    module = CDLL(str(path))
    entry = Entry.in_dll(module, "clap_entry")
    require(entry.init(os.fsencode(path)), "CLAP entry init failed")
    instance = None
    created = False
    pending = threading.Event()
    width, height = c_uint32(), c_uint32()

    @CFUNCTYPE(c_bool, c_void_p, c_uint32, c_uint32)
    def resize(_, w, h):
        x.XResizeWindow(display, window, w, h)
        x.XFlush(display)
        return True

    host_gui = HostGui(Callback(lambda *_: None), resize,
                       BoolPlugin(lambda *_: True), BoolPlugin(lambda *_: True),
                       CFUNCTYPE(None, c_void_p, c_bool)(lambda *_: None))
    host = Host(Version(1, 2, 0), None, b"Hendrix X11 GUI probe", b"Moonshroom Studio",
                b"https://github.com/moonshroom303/hendrix", b"1.0",
                Extension(lambda _, name: addressof(host_gui) if name == b"clap.gui" else None),
                Callback(lambda *_: None), Callback(lambda *_: None),
                Callback(lambda *_: pending.set()))
    try:
        factory_ptr = entry.factory(b"clap.plugin-factory")
        require(factory_ptr, "CLAP factory is missing")
        factory = cast(factory_ptr, POINTER(Factory)).contents
        instance = factory.create(factory_ptr, byref(host), b"studio.moonshroom.hendrix")
        require(instance, "CLAP instance creation failed")
        plugin = cast(instance, POINTER(Plugin)).contents
        require(plugin.init(instance), "CLAP init failed")
        gui_ptr = plugin.extension(instance, b"clap.gui")
        require(gui_ptr, "CLAP GUI extension is missing")
        gui = cast(gui_ptr, POINTER(Gui)).contents
        require(gui.supported(instance, b"x11", False), "Embedded X11 editor not supported")

        def pump(seconds=1.5):
            until = time.monotonic() + seconds
            event = (c_long * 24)()
            while time.monotonic() < until:
                while x.XPending(display):
                    x.XNextEvent(display, byref(event))
                if pending.is_set():
                    pending.clear()
                    plugin.main_thread(instance)
                time.sleep(0.005)

        def capture(name):
            pump()
            screenshot = output / f"{name}.png"
            subprocess.run(["import", "-window", str(window), str(screenshot)], check=True, timeout=15)
            labels = output / f"{name}-labels.png"
            subprocess.run(["convert", str(screenshot), "-crop", "450x720+0+0", "-resize", "300%", "-colorspace", "Gray", "-negate", str(labels)], check=True, timeout=15)
            text = subprocess.check_output(["tesseract", str(labels), "stdout", "--psm", "6"],
                                           text=True, timeout=30)
            (output / f"{name}.txt").write_text(text)
            print(text, flush=True)
            require("HENDRIX" in text.upper(), f"Editor title is not rendered: {name}\n{text}")
            require("THRESHOLD" in text.upper(), f"Editor controls are not rendered: {name}\n{text}")
            print(f"CLAP GUI: {name}, {width.value} x {height.value}, title and controls visible", flush=True)

        for cycle in range(2):
            created = gui.create(instance, b"x11", False)
            require(created, "Editor creation failed")
            require(gui.size(instance, byref(width), byref(height)), "Editor size unavailable")
            resize(None, width.value, height.value)
            require(gui.parent(instance, byref(Window(b"x11", window))), "Editor embedding failed")
            require(gui.show(instance), "Editor show failed")
            capture(f"open-{cycle + 1}")
            require(gui.hide(instance), "Editor hide failed")
            pump(0.1)
            require(gui.show(instance), "Editor show after hide failed")
            capture(f"reshow-{cycle + 1}")
            print("Destroying editor", flush=True)
            gui.destroy(instance)
            print("Editor destroyed", flush=True)
            created = False
            pump(0.1)
    finally:
        if instance:
            if created:
                gui.destroy(instance)
            plugin.destroy(instance)
        entry.deinit()
        x.XDestroyWindow(display, window)
        x.XCloseDisplay(display)


if __name__ == "__main__":
    probe_gui(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
