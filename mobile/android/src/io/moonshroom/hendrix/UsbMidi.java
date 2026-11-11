package io.moonshroom.hendrix;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.os.Build;
import android.os.Handler;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** MIDI 1.0 USB output. InputPort is the remote device's input, hence our output.
 * Android's USB peripheral endpoint uses the same API as USB host-mode devices.
 * Port writes, device open/close and enumeration run on a dedicated worker.
 */
final class UsbMidi {
    record Destination(String key, String label, MidiDeviceInfo info, int port) {}
    private static final byte[] RESET = {(byte)0xb0,64,0, (byte)0xb0,123,0, (byte)0xb0,120,0, (byte)0xe0,0,64,
            (byte)0xb1,64,0, (byte)0xb1,123,0, (byte)0xb1,120,0, (byte)0xe1,0,64};
    private final MidiManager manager;
    private final Handler worker, main;
    private final Consumer<String> report;
    private final Consumer<List<Destination>> listChanged;
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final Runnable drain = this::drain;
    private volatile boolean enabled, connected;
    private volatile int generation;
    private MidiManager.DeviceCallback callback;
    private MidiDevice device;
    private MidiInputPort output;
    private Destination active;
    private String selected, blocked;
    private int openSequence;
    private boolean opening, drainScheduled;
    private Runnable openTimeout;

    UsbMidi(Context context, Handler worker, Handler main, Consumer<String> report,
            Consumer<List<Destination>> listChanged) {
        this.manager = context.getSystemService(MidiManager.class);
        this.worker = worker;
        this.main = main;
        this.report = report;
        this.listChanged = listChanged;
    }

    boolean enabled() { return enabled; }
    boolean connected() { return enabled && connected; }

    @SuppressWarnings("deprecation")
    void start() {
        if (enabled) return;
        enabled = true;
        int id = ++generation;
        worker.post(() -> {
            if (!enabled || generation != id) return;
            if (manager == null) { show("Dieses Handy stellt kein System-MIDI bereit."); return; }
            blocked = null;
            callback = new MidiManager.DeviceCallback() {
                @Override public void onDeviceAdded(MidiDeviceInfo info) {
                    if (enabled && generation == id && info.getType() == MidiDeviceInfo.TYPE_USB) { blocked = null; refresh(); }
                }
                @Override public void onDeviceRemoved(MidiDeviceInfo info) {
                    if (!enabled || generation != id || info.getType() != MidiDeviceInfo.TYPE_USB) return;
                    if (active != null && active.info().getId() == info.getId()) closeEndpoint();
                    blocked = null;
                    refresh();
                }
            };
            try {
                // Enumerate both before and after registration to cover devices
                // already connected and cable changes racing with registration.
                devices();
                if (Build.VERSION.SDK_INT >= 33)
                    manager.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, task -> worker.post(task), callback);
                else manager.registerDeviceCallback(callback, worker);
                refresh();
            } catch (SecurityException | IllegalStateException error) {
                show("USB-MIDI nicht verfügbar. Kabel und USB-Modus MIDI prüfen.");
            }
        });
    }

    void stop() {
        enabled = connected = false;
        ++generation;
        clearPending();
        worker.post(() -> {
            closeEndpoint();
            if (manager != null && callback != null) {
                try { manager.unregisterDeviceCallback(callback); } catch (IllegalStateException | SecurityException ignored) {}
            }
            callback = null;
            show("USB-MIDI aus");
        });
    }

    void select(String key) {
        connected = false;
        clearPending();
        worker.post(() -> {
            selected = key;
            blocked = null;
            closeEndpoint();
            if (enabled) refresh();
        });
    }

    @SuppressWarnings("deprecation")
    private List<MidiDeviceInfo> devices() {
        return Build.VERSION.SDK_INT >= 33 ? new ArrayList<>(manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)) :
                Arrays.asList(manager.getDevices());
    }

    private void refresh() {
        if (!enabled || manager == null) return;
        try { refreshDevices(); }
        catch (SecurityException | IllegalStateException error) {
            closeEndpoint();
            show("USB-MIDI nicht verfügbar · USB MIDI aus- und wieder einschalten");
        }
    }

    private void refreshDevices() {
        ArrayList<Destination> choices = new ArrayList<>();
        for (MidiDeviceInfo info : devices()) {
            if (info.getType() != MidiDeviceInfo.TYPE_USB) continue;
            String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
            if (name == null || name.trim().isEmpty()) name = "USB-MIDI-Gerät";
            for (MidiDeviceInfo.PortInfo p : info.getPorts()) {
                if (p.getType() != MidiDeviceInfo.PortInfo.TYPE_INPUT) continue;
                String suffix = info.getInputPortCount() > 1 ? " · " +
                        (p.getName() == null || p.getName().trim().isEmpty() ? "Port " + (p.getPortNumber()+1) : p.getName()) : "";
                choices.add(new Destination(info.getId() + ":" + p.getPortNumber(), name + suffix, info, p.getPortNumber()));
            }
        }
        choices.sort(Comparator.comparing(Destination::key));
        int id = generation;
        List<Destination> snapshot = List.copyOf(choices);
        main.post(() -> { if (enabled && generation == id) listChanged.accept(snapshot); });
        Destination choice = choices.stream().filter(c -> c.key().equals(selected)).findFirst().orElse(null);
        if (choice == null && choices.size() == 1) choice = choices.get(0);
        if (choice == null) {
            closeEndpoint();
            show(choices.isEmpty() ? "Wartet auf USB · Datenkabel anschließen und am Handy USB-Modus MIDI wählen" :
                    "Mehrere USB-Ausgänge · bitte USB-Ziel wählen");
            return;
        }
        if (active != null && active.key().equals(choice.key()) && (opening || connected)) return;
        if (choice.key().equals(blocked)) return;
        open(choice);
    }

    private void open(Destination choice) {
        closeEndpoint();
        active = choice;
        opening = true;
        int token = ++openSequence;
        int id = generation;
        show("USB-MIDI wird verbunden …");
        openTimeout = () -> {
            if (!enabled || generation != id || openSequence != token || !opening) return;
            failed(choice, "USB antwortet nicht · USB MIDI aus- und wieder einschalten");
        };
        worker.postDelayed(openTimeout, 5000);
        try {
            manager.openDevice(choice.info(), opened -> {
                if (!enabled || generation != id || openSequence != token) { closeDevice(opened); return; }
                worker.removeCallbacks(openTimeout);
                opening = false;
                if (opened == null) { failed(choice, "USB-Gerät nicht verfügbar · Kabel und USB-Modus MIDI prüfen"); return; }
                device = opened;
                try {
                    output = opened.openInputPort(choice.port());
                    if (output == null) { failed(choice, "USB-Port belegt · andere MIDI-App schließen und USB MIDI neu einschalten"); return; }
                    output.send(RESET, 0, RESET.length);
                    connected = true;
                    show("USB verbunden · " + choice.label());
                } catch (IOException | SecurityException error) {
                    failed(choice, "USB-MIDI konnte nicht gestartet werden · Kabel neu verbinden");
                }
            }, worker);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException error) {
            failed(choice, "USB-MIDI konnte nicht geöffnet werden · Kabel und USB-Modus MIDI prüfen");
        }
    }

    void send(int status, int a, int b) {
        if (!enabled || !connected || status < 0x80 || status >= 0xf0) return;
        byte[] event = (status & 0xe0) == 0xc0 ? new byte[]{(byte)status,(byte)(a & 127)} :
                new byte[]{(byte)status,(byte)(a & 127),(byte)(b & 127)};
        synchronized (pending) {
            if (!enabled || !connected) return;
            if (pending.size() >= 256) { pending.clear(); pending.add(RESET); }
            pending.add(event);
            if (!drainScheduled) { drainScheduled = true; worker.post(drain); }
        }
    }

    private void drain() {
        byte[] batch = new byte[256];
        int size = 0;
        synchronized (pending) {
            drainScheduled = false;
            if (!enabled || !connected || output == null) { pending.clear(); return; }
            while (!pending.isEmpty() && size + pending.peek().length <= batch.length) {
                byte[] message = pending.remove();
                System.arraycopy(message, 0, batch, size, message.length);
                size += message.length;
            }
        }
        try {
            if (size > 0) output.send(batch, 0, size);
        } catch (IOException error) {
            failed(active, "USB-Verbindung unterbrochen · Kabel neu verbinden");
            return;
        }
        synchronized (pending) {
            if (!pending.isEmpty() && !drainScheduled) { drainScheduled = true; worker.post(drain); }
        }
    }

    private void failed(Destination choice, String message) {
        blocked = choice == null ? null : choice.key();
        closeEndpoint();
        show(message);
    }

    private void closeEndpoint() {
        connected = false;
        ++openSequence;
        opening = false;
        if (openTimeout != null) worker.removeCallbacks(openTimeout);
        clearPending();
        if (output != null) {
            try { output.send(RESET, 0, RESET.length); } catch (IOException ignored) {}
            try { output.close(); } catch (IOException ignored) {}
        }
        output = null;
        closeDevice(device);
        device = null;
        active = null;
    }

    private void clearPending() {
        synchronized (pending) { pending.clear(); drainScheduled = false; worker.removeCallbacks(drain); }
    }

    private static void closeDevice(MidiDevice device) {
        if (device != null) try { device.close(); } catch (IOException ignored) {}
    }

    private void show(String text) {
        int id = generation;
        main.post(() -> { if (generation == id) report.accept(text); });
    }
}
