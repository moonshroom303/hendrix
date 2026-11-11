package io.moonshroom.hendrix;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import java.util.UUID;

/** Holds the existing link and initializes Android's client side of the same ATT link.
 * macOS also sends GATT Service Changed indications while acting as the MIDI central.
 * Those confirmations belong to the Android GATT client stack, not the MIDI server.
 * Idle signals read only standard Bluetooth metadata on the same link. They never
 * enter the MIDI stream or the recording. No wake lock or reconnect loop is used.
 */
@SuppressLint("MissingPermission")
final class BleMidiPeer {
    private static final long IDLE_INTERVAL_MS = 5000;
    private static final long READ_TIMEOUT_MS = 15000;
    private final Context context;
    private final Handler handler;
    private final BleMidiDiagnostics diagnostics;
    private BluetoothDevice device;
    private BluetoothGatt client;
    private boolean discovered, discovering, held;
    private int discoveryFailures;
    private boolean connected, subscribed, discoveryPending, idleSuspended;
    private BluetoothGattCharacteristic idleTarget, idleRead;
    private long lastMidiAt, nextIdleAt;
    private int idleFailures, idleSent;
    private final Runnable idleSignal = this::sendIdleSignal;
    private final Runnable idleTimeout = this::idleReadTimedOut;
    private final Runnable discoveryRetry = this::discoverPeerServices;
    private final Runnable discoveryTimeout = this::discoveryTimedOut;

    private void discoveryTimedOut() {
        if (discovering) diagnostics.add("Dienstabgleich: seit 20 s keine Antwort · keine parallele Anfrage");
        // A timeout does not cancel Android's ATT operation. Wait for its callback.
    }

    private void idleReadTimedOut() {
        if (idleRead == null) return;
        idleSuspended = true;
        diagnostics.add("Lebenszeichen: Antwort fehlt · weitere Abfragen pausiert");
        // Keep the read marked outstanding: never overlap another ATT operation.
    }

    BleMidiPeer(Context context, Handler handler, BleMidiDiagnostics diagnostics) {
        this.context = context;
        this.handler = handler;
        this.diagnostics = diagnostics;
    }

    boolean matches(BluetoothDevice other) { return other.equals(device); }
    boolean attached() { return held; }
    boolean available(BluetoothDevice other) { return !held || matches(other); }

    void prepare(BluetoothDevice other) {
        if (device != null) return;
        diagnostics.add("Bekannte MIDI-Gegenrichtung vor Verbindung vorbereiten");
        openClient(other, true);
    }

    void attach(BluetoothGattServer server, BluetoothDevice other) {
        if (held) return;
        // Only called after a request to our MIDI characteristic/CCCD, never for
        // unrelated devices reported by the shared Android GATT server callback.
        if (device != null && !matches(other)) close(null, false);
        diagnostics.add("MIDI-Gegenstelle · Kopplungsstatus=" + other.getBondState());
        held = true;
        diagnostics.add("Bestehende Verbindung halten=" + server.connect(other, false));
        if (client == null) openClient(other, false);
    }

    private void openClient(BluetoothDevice other, boolean background) {
        device = other;
        client = other.connectGatt(context, background, new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
                handler.post(() -> {
                    if (client != gatt) return;
                    diagnostics.add("GATT-Gegenrichtung · Status=" + status + " Zustand=" + state);
                    boolean wasConnected = connected;
                    connected = status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED;
                    if (!connected) resetOperations();
                    if (connected && !wasConnected) {
                        discoveryPending = true;
                        discoverPeerServices();
                    }
                    // The server owns MIDI state. A client error must not tear it down.
                });
            }
            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                handler.post(() -> {
                    if (client != gatt || !connected || !discovering) return;
                    discovering = false;
                    handler.removeCallbacks(discoveryTimeout);
                    diagnostics.add("Dienstabgleich · Status=" + status);
                    if (status != BluetoothGatt.GATT_SUCCESS) { discoveryFailed(); return; }
                    discovered = true;
                    discoveryFailures = 0;
                    // A Service Changed event invalidates the in-flight snapshot.
                    if (discoveryPending) { discoverPeerServices(); return; }
                    idleTarget = status == BluetoothGatt.GATT_SUCCESS ? findIdleTarget(gatt) : null;
                    diagnostics.add(idleTarget == null ? "Lebenszeichen nicht verfügbar: kein lesbares Bluetooth-Attribut" :
                            "Lebenszeichen bereit · 5 s Spielpause · Attribut=" + idleTarget.getUuid());
                    scheduleIdleSignal();
                });
            }
            @Override public void onServiceChanged(BluetoothGatt gatt) {
                handler.post(() -> {
                    if (client != gatt || !connected) return;
                    diagnostics.add("Gegenstelle meldet geänderte Dienste");
                    idleTarget = null;
                    handler.removeCallbacks(idleSignal);
                    discoveryPending = true;
                    discoveryFailures = 0;
                    discoverPeerServices();
                });
            }
            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
                handler.post(() -> finishIdleRead(gatt, c, status));
            }
            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] value, int status) {
                // Values are deliberately ignored; only completion matters.
                handler.post(() -> finishIdleRead(gatt, c, status));
            }
        }, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler);
        diagnostics.add("GATT-Gegenrichtung geöffnet=" + (client != null));
    }

    void setMidiSubscribed(boolean active) {
        if (subscribed == active) return;
        subscribed = active;
        if (active) {
            lastMidiAt = SystemClock.elapsedRealtime();
            nextIdleAt = lastMidiAt + IDLE_INTERVAL_MS;
        }
        scheduleIdleSignal();
    }

    void midiActivity() { lastMidiAt = SystemClock.elapsedRealtime(); }

    private void discoverPeerServices() {
        if (client == null || !connected || idleRead != null || discovering || !discoveryPending) return;
        handler.removeCallbacks(discoveryRetry);
        discoveryPending = false;
        idleTarget = null;
        handler.removeCallbacks(idleSignal);
        try { discovering = client.discoverServices(); }
        catch (SecurityException error) { diagnostics.add("Dienstabgleich: Bluetooth-Berechtigung entzogen"); }
        diagnostics.add("Dienstabgleich gestartet=" + discovering);
        if (discovering) handler.postDelayed(discoveryTimeout, 20000);
        else discoveryFailed();
    }

    private void discoveryFailed() {
        discovered = false;
        discoveryPending = true;
        if (++discoveryFailures <= 3) {
            long delay = 1000L << discoveryFailures;
            diagnostics.add("Dienstabgleich wiederholen in " + delay / 1000 + " s");
            handler.postDelayed(discoveryRetry, delay);
        } else diagnostics.add("Dienstabgleich pausiert nach drei Wiederholungen");
    }

    private void resetOperations() {
        discovered = discovering = discoveryPending = idleSuspended = false;
        discoveryFailures = idleFailures = 0;
        idleTarget = idleRead = null;
        handler.removeCallbacks(idleSignal);
        handler.removeCallbacks(idleTimeout);
        handler.removeCallbacks(discoveryRetry);
        handler.removeCallbacks(discoveryTimeout);
    }

    private static UUID uuid(int shortUuid) {
        return UUID.fromString(String.format(java.util.Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", shortUuid));
    }

    private static BluetoothGattCharacteristic findIdleTarget(BluetoothGatt gatt) {
        // Prefer one/two-byte metadata. All candidates are standard, read-only
        // operations: supported features, appearance, database hash, device name.
        int[][] candidates = {{0x1801, 0x2b3a}, {0x1800, 0x2a01}, {0x1801, 0x2b29},
                {0x1801, 0x2b2a}, {0x1800, 0x2a00}};
        for (int[] pair : candidates) {
            BluetoothGattService service = gatt.getService(uuid(pair[0]));
            BluetoothGattCharacteristic c = service == null ? null : service.getCharacteristic(uuid(pair[1]));
            if (c != null && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) return c;
        }
        return null;
    }

    private void scheduleIdleSignal() {
        handler.removeCallbacks(idleSignal);
        if (!subscribed || !connected || !discovered || discovering || discoveryPending || idleTarget == null || idleRead != null || idleSuspended) return;
        long due = Math.max(nextIdleAt, lastMidiAt + IDLE_INTERVAL_MS);
        handler.postDelayed(idleSignal, Math.max(1, due - SystemClock.elapsedRealtime()));
    }

    private void sendIdleSignal() {
        if (!subscribed || !connected || !discovered || discovering || discoveryPending || client == null || idleTarget == null || idleRead != null || idleSuspended) return;
        long now = SystemClock.elapsedRealtime();
        if (now < lastMidiAt + IDLE_INTERVAL_MS || now < nextIdleAt) { scheduleIdleSignal(); return; }
        nextIdleAt = now + IDLE_INTERVAL_MS;
        idleRead = idleTarget;
        try {
            if (client.readCharacteristic(idleRead)) {
                idleSent++;
                if (idleSent == 1 || idleSent % 12 == 0) diagnostics.add("Lebenszeichen gesendet · Anzahl=" + idleSent);
                handler.postDelayed(idleTimeout, READ_TIMEOUT_MS);
            } else {
                idleRead = null;
                idleFailed("Bluetooth belegt");
            }
        } catch (SecurityException error) {
            idleRead = null;
            idleSuspended = true;
            diagnostics.add("Lebenszeichen pausiert: Bluetooth-Berechtigung entzogen");
        }
    }

    private void finishIdleRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
        if (client != gatt || idleRead != c) return;
        handler.removeCallbacks(idleTimeout);
        idleRead = null;
        if (status == BluetoothGatt.GATT_SUCCESS) {
            idleFailures = 0;
            if (idleSent == 1 || idleSent % 12 == 0) diagnostics.add("Lebenszeichen bestätigt");
        } else idleFailed("Status=" + status);
        if (discoveryPending) discoverPeerServices();
        scheduleIdleSignal();
    }

    private void idleFailed(String reason) {
        idleFailures++;
        idleSuspended |= idleFailures >= 3;
        nextIdleAt = SystemClock.elapsedRealtime() + (IDLE_INTERVAL_MS << idleFailures);
        diagnostics.add("Lebenszeichen fehlgeschlagen · " + reason + (idleSuspended ? " · pausiert" : " · Abstand erhöht"));
        scheduleIdleSignal();
    }

    void close(BluetoothGattServer server, boolean disconnect) {
        BluetoothGatt previous = client;
        BluetoothDevice previousDevice = device;
        boolean wasHeld = held;
        client = null;
        device = null;
        held = false;
        resetOperations();
        connected = subscribed = discoveryPending = idleSuspended = false;
        idleTarget = idleRead = null;
        idleFailures = idleSent = 0;
        handler.removeCallbacks(idleSignal);
        handler.removeCallbacks(idleTimeout);
        if (previous != null) {
            try {
                if (disconnect) previous.disconnect();
            } catch (SecurityException ignored) {}
            try { previous.close(); } catch (SecurityException ignored) {}
        }
        if (disconnect && wasHeld && server != null && previousDevice != null) {
            try { server.cancelConnection(previousDevice); } catch (SecurityException ignored) {}
        }
    }
}
