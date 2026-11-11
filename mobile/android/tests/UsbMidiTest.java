package io.moonshroom.hendrix;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiDeviceInfo.PortInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Real USB transport; only the Android system boundary is simulated. */
public final class UsbMidiTest {
    static final class Session {
        final Context context = new Context();
        final MidiManager manager = context.midi;
        final Handler worker = new Handler(), main = new Handler();
        final ArrayList<String> reports = new ArrayList<>();
        List<UsbMidi.Destination> choices = List.of();
        final UsbMidi usb = new UsbMidi(context, worker, main, reports::add, value -> choices = value);
        void pump() { worker.runReady(); main.runReady(); }
        void start() { usb.start(); pump(); }
        void stop() { usb.stop(); pump(); }
        MidiDevice device() { return manager.opened.get(manager.opened.size() - 1); }
        String status() { return reports.get(reports.size() - 1); }
    }
    static MidiDeviceInfo info(int id) { return info(id, MidiDeviceInfo.TYPE_USB, new PortInfo(PortInfo.TYPE_INPUT, 0, "")); }
    static MidiDeviceInfo info(int id, int type, PortInfo... ports) {
        return new MidiDeviceInfo(id, type, "Android USB Peripheral " + id, ports);
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static byte[] bytes(MidiInputPort port) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        port.sent.forEach(value -> result.write(value, 0, value.length));
        return result.toByteArray();
    }
    static void equal(byte[] actual, int... expected) {
        byte[] wanted = new byte[expected.length];
        for (int i = 0; i < expected.length; i++) wanted[i] = (byte)expected[i];
        check(Arrays.equals(actual, wanted), "Wrong MIDI stream: " + Arrays.toString(actual));
    }
    static void automaticAndNotes() {
        Session s = new Session();
        s.manager.devices.add(info(1));
        s.manager.devices.add(info(2, MidiDeviceInfo.TYPE_BLUETOOTH, new PortInfo(1,0,"")));
        s.manager.devices.add(info(3, MidiDeviceInfo.TYPE_VIRTUAL, new PortInfo(1,0,"")));
        s.manager.devices.add(info(4, MidiDeviceInfo.TYPE_USB, new PortInfo(PortInfo.TYPE_OUTPUT,0,"")));
        s.start();
        check(s.choices.size() == 1 && s.manager.opened.size() == 1, "Only USB destination input ports are eligible");
        check(s.device().portNumber == 0 && s.status().startsWith("USB verbunden"), "USB device opened");
        if (Build.VERSION.SDK_INT >= 33) check(s.manager.modernRegistrations == 1 && s.manager.legacyEnumerations == 0, "MIDI 1 transport API");
        else check(s.manager.legacyRegistrations == 1 && s.manager.modernEnumerations == 0, "Android 12 API");
        MidiInputPort port = s.device().port;
        check(bytes(port).length == 24, "Reset remote notes when connecting");
        port.sent.clear();
        s.usb.send(0x90,60,100); s.usb.send(0x80,60,0);
        s.usb.send(0x91,64,96); s.usb.send(0x81,64,0);
        s.usb.send(0xb0,64,127); s.usb.send(0xe1,0,64);
        s.usb.send(0xc0,5,99); s.usb.send(0xd0,12,99);
        s.usb.send(0xa1,64,24);
        s.usb.send(0x00,1,2); s.usb.send(0xf8,1,2);
        check(port.sent.isEmpty(), "Writes must be off the UI/audio thread");
        s.pump();
        equal(bytes(port), 0x90,60,100,0x80,60,0,0x91,64,96,0x81,64,0,
                0xb0,64,127,0xe1,0,64,0xc0,5,0xd0,12,0xa1,64,24);
        int count = port.sent.size();
        s.worker.advance(180_000); s.main.runReady();
        check(port.sent.size() == count, "USB needs no idle traffic or polling");
        port.sent.clear(); s.stop();
        check(bytes(port).length == 24 && port.closed && s.device().closed, "Stop resets notes and closes handles");
        check(s.manager.unregisters == 1, "Stop unregisters device callbacks");
        s.usb.send(0x90,60,100); s.pump();
        check(bytes(port).length == 24, "No notes after stop");
    }
    static void cableChanges() {
        Session s = new Session(); s.start();
        check(s.status().startsWith("Wartet auf USB"), "Wait for cable");
        MidiDeviceInfo first = info(10); s.manager.add(first); s.pump();
        MidiDevice old = s.device();
        s.usb.send(0x91,40,90); s.pump();
        s.manager.remove(first); s.pump();
        check(old.closed && old.port.closed && s.choices.isEmpty(), "Unplug releases port");
        s.usb.send(0x91,41,90);
        s.manager.add(info(11)); s.pump();
        MidiDevice next = s.device();
        check(next != old && bytes(next.port).length == 24, "Replug opens new ID without stale notes");
        next.port.sent.clear(); s.usb.send(0x91,42,90); s.pump();
        equal(bytes(next.port), 0x91,42,90);
        s.stop();
    }
    static void portSelection() {
        Session s = new Session();
        s.manager.devices.add(info(20, MidiDeviceInfo.TYPE_USB, new PortInfo(1,0,"Main"), new PortInfo(1,1,"Second")));
        s.start();
        check(s.manager.opened.isEmpty() && s.choices.size() == 2, "Multiple ports need an explicit choice");
        s.usb.select("20:1"); s.pump();
        MidiDevice old = s.device(); check(old.portNumber == 1, "Chosen port opened");
        s.usb.send(0x90,60,100);
        s.usb.select("20:0"); s.pump();
        check(old.closed && s.device().portNumber == 0, "Changing destination closes old port");
        check(bytes(s.device().port).length == 24, "Queued notes are not transferred between destinations");
        s.stop();
    }
    static void staleAndMissingReplies() {
        Session s = new Session();
        s.manager.delay = true;
        MidiDeviceInfo first = info(30); s.manager.devices.add(first); s.start();
        MidiDevice old = s.device();
        s.manager.remove(first); s.manager.add(info(31)); s.pump();
        MidiDevice next = s.device();
        s.manager.reply(); s.pump();
        check(old.closed && old.portNumber == -1 && !next.closed, "Late result for removed device is closed");
        s.stop();

        Session timeout = new Session(); timeout.manager.delay = true;
        timeout.manager.devices.add(info(32)); timeout.start();
        timeout.worker.advance(5001); timeout.main.runReady();
        check(timeout.status().contains("antwortet nicht"), "Missing callback times out");
        timeout.manager.reply(); timeout.pump();
        check(timeout.device().closed && timeout.device().portNumber == -1, "Late timed-out handle closes");
        timeout.stop();

        Session stopped = new Session(); stopped.manager.delay = true;
        stopped.manager.devices.add(info(33)); stopped.start(); stopped.stop();
        stopped.manager.reply(); stopped.pump();
        check(stopped.device().closed && stopped.status().equals("USB-MIDI aus"), "Late result cannot reopen stopped USB");
    }
    static void unavailableAndFailures() {
        Session s = new Session(); s.manager.busy = true; s.manager.devices.add(info(40)); s.start();
        check(s.device().closed && s.status().contains("belegt"), "Busy port gives actionable status");
        s.manager.busy = false; s.stop(); s.start();
        check(s.status().startsWith("USB verbunden"), "User can retry busy port");
        s.device().port.fail = true; s.usb.send(0x90,60,100); s.pump();
        check(s.device().closed && s.status().contains("unterbrochen"), "Write failure closes device");
        int opened = s.manager.opened.size();
        s.worker.advance(60_000); s.main.runReady();
        check(s.manager.opened.size() == opened, "Failure does not spin/reopen continuously");
        s.stop();

        Session absent = new Session(); absent.manager.nullDevice = true; absent.manager.devices.add(info(41)); absent.start();
        check(absent.status().contains("nicht verfügbar"), "Failed open reports unavailable"); absent.stop();
    }
    static void boundedQueue() {
        Session s = new Session(); s.manager.devices.add(info(50)); s.start();
        byte[] reset = bytes(s.device().port); s.device().port.sent.clear();
        for (int i = 0; i < 300; i++) s.usb.send(0x91,40 + i % 12,80);
        s.pump(); byte[] sent = bytes(s.device().port);
        check(sent.length <= 24 + 256*3 && sent.length > 24, "Pending queue stays bounded");
        check(Arrays.equals(Arrays.copyOf(sent,24), reset), "Overflow resets notes before fresh messages");
        check(s.device().port.sent.stream().allMatch(p -> p.length <= 256), "Writes stay within system packet size");
        s.stop();
    }
    static void rapidLifecycle() {
        Session s = new Session(); s.manager.devices.add(info(60));
        s.usb.start(); s.usb.stop(); s.usb.start(); s.pump();
        check(s.manager.opened.size() == 1 && s.status().startsWith("USB verbunden"), "Rapid lifecycle creates one active endpoint");
        MidiManager.DeviceCallback stale = s.manager.callback;
        s.stop(); stale.onDeviceAdded(info(61)); s.pump();
        check(s.manager.opened.size() == 1 && s.status().equals("USB-MIDI aus"), "Old callback cannot reopen USB");
    }
    public static void main(String[] args) {
        for (int api : new int[]{31,35}) {
            Build.VERSION.SDK_INT = api; SystemClock.time = 0;
            automaticAndNotes(); cableChanges(); portSelection(); staleAndMissingReplies();
            unavailableAndFailures(); boundedQueue(); rapidLifecycle();
        }
        System.out.println("USB-MIDI transport tests passed (Android 12 and 15 APIs).");
    }
}
