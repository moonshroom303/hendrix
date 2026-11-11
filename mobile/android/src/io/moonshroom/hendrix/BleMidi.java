package io.moonshroom.hendrix;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

@SuppressLint("MissingPermission")
final class BleMidi {
    private static final UUID SERVICE = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700");
    private static final UUID MIDI = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Handler handler;
    private final Consumer<String> report;
    private final Consumer<String> fatal;
    private final BleMidiDiagnostics diagnostics;
    private final BleMidiPeer peer;
    private final BleMidiPackets pending = new BleMidiPackets();
    private final Runnable retry = this::retrySend;
    private final Runnable sendTimeout = this::notificationTimedOut;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertisement;
    private BluetoothGattCharacteristic characteristic;
    private volatile BluetoothDevice subscriber;
    private boolean inFlight;
    private boolean retryScheduled;
    private boolean delayed;
    private int failures;
    private int generation;
    private long connectedAt;
    private String lastDisconnect = "";

    BleMidi(Context context, Handler handler, Consumer<String> report, Consumer<String> fatal,
            BleMidiDiagnostics diagnostics) {
        this.context = context;
        this.handler = handler;
        this.report = report;
        this.fatal = fatal;
        this.diagnostics = diagnostics;
        this.peer = new BleMidiPeer(context, handler, diagnostics);
    }

    boolean isActive() { return server != null; }

    void start() {
        if (isActive()) return;
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
                report.accept("Bluetooth aus · wartet auf Einschalten");
                return;
            }
            advertiser = manager.getAdapter().getBluetoothLeAdvertiser();
            if (advertiser == null) {
                fail("Dieses Gerät kann kein Bluetooth-MIDI anbieten.");
                return;
            }
            int id = ++generation;
            diagnostics.add("MIDI-Server starten");
            prepareKnownPeer();
            server = manager.openGattServer(context, callbacks(id));
            if (server == null) { fail("Bluetooth-MIDI nicht verfügbar."); return; }
            BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            characteristic = new BluetoothGattCharacteristic(MIDI,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE |
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED |
                            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
            characteristic.addDescriptor(new BluetoothGattDescriptor(CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED |
                            BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED));
            service.addCharacteristic(characteristic);
            if (!server.addService(service)) fail("MIDI-Dienst konnte nicht starten.");
            else report.accept("Bluetooth wird gestartet …");
        } catch (SecurityException ex) { fail("Bluetooth-Berechtigung fehlt."); }
    }

    boolean connected() { return subscriber != null; }

    void stop() {
        ++generation;
        stopAdvertising();
        peer.close(server, true);
        if (server != null) {
            try { server.close(); } catch (SecurityException ignored) {}
        }
        server = null;
        advertiser = null;
        characteristic = null;
        resetSubscriber();
        lastDisconnect = "";
    }

    private void stopAdvertising() {
        AdvertiseCallback current = advertisement;
        advertisement = null;
        if (advertiser != null && current != null) {
            try { advertiser.stopAdvertising(current); } catch (SecurityException ignored) {}
        }
    }

    private void resetSubscriber() {
        peer.setMidiSubscribed(false);
        handler.removeCallbacks(retry);
        handler.removeCallbacks(sendTimeout);
        subscriber = null;
        pending.clear();
        inFlight = false;
        retryScheduled = false;
        delayed = false;
        failures = 0;
    }

    private void fail(String reason) {
        stop();
        fatal.accept(reason);
    }

    private void advertise(int id, boolean includeName) {
        if (advertisement != null || advertiser == null || subscriber != null) return;
        advertisement = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settings) {
                handler.post(() -> {
                    if (generation == id && advertisement == this && subscriber == null)
                        report.accept(lastDisconnect.isEmpty() ? "MIDI sichtbar · am Mac verbinden" : lastDisconnect);
                });
            }
            @Override public void onStartFailure(int errorCode) {
                handler.post(() -> {
                    if (generation != id || advertisement != this) return;
                    advertisement = null;
                    if (includeName && errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE)
                        advertise(id, false);
                    else fail("Bluetooth-Ankündigung fehlgeschlagen (" + errorCode + ").");
                });
            }
        };
        try {
            AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build();
            AdvertiseData scan = new AdvertiseData.Builder().setIncludeDeviceName(includeName).build();
            advertiser.startAdvertising(new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setConnectable(true).setTimeout(0).build(), data, scan, advertisement);
        } catch (SecurityException ex) { fail("Bluetooth-Berechtigung fehlt."); }
    }

    private BluetoothGattServerCallback callbacks(int id) {
        return new BluetoothGattServerCallback() {
            @Override public void onServiceAdded(int status, BluetoothGattService service) {
                handler.post(() -> {
                    if (generation != id) return;
                    if (status == BluetoothGatt.GATT_SUCCESS) advertise(id, true);
                    else fail("MIDI-Dienst konnte nicht starten.");
                });
            }
            @Override public void onConnectionStateChange(BluetoothDevice device, int status, int state) {
                handler.post(() -> {
                    if (generation != id) return;
                    // Android reports other apps' BLE links here too. Only our MIDI
                    // requests select a peer; never attach to unrelated peripherals.
                    if (state == BluetoothProfile.STATE_DISCONNECTED && (peer.matches(device) || device.equals(subscriber))) {
                        long seconds = Math.max(0, (SystemClock.elapsedRealtime() - connectedAt) / 1000);
                        diagnostics.add("MIDI-Verbindung getrennt · Status=" + status + " nach " + seconds + "s");
                        peer.close(server, false);
                        if (device.equals(subscriber)) resetSubscriber();
                        if (subscriber == null) {
                            prepareKnownPeer();
                            lastDisconnect = "Verbindung nach " + seconds + " s getrennt (" + status + ") · am Mac verbinden";
                            report.accept(lastDisconnect);
                            advertise(id, true);
                        }
                    }
                });
            }
            @Override public void onCharacteristicReadRequest(BluetoothDevice device, int request, int offset,
                                                              BluetoothGattCharacteristic c) {
                handler.post(() -> {
                    if (generation != id || server == null) return;
                    if (c.getUuid().equals(MIDI) && !attachPeer(device)) return;
                    respond(device, request, !c.getUuid().equals(MIDI) ? BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED :
                            offset == 0 ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_INVALID_OFFSET, offset, new byte[0]);
                });
            }
            @Override public void onDescriptorReadRequest(BluetoothDevice device, int request, int offset, BluetoothGattDescriptor descriptor) {
                handler.post(() -> {
                    if (generation != id || server == null) return;
                    byte[] value = device.equals(subscriber) ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE :
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    int status = !descriptor.getUuid().equals(CCCD) ? BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED :
                            offset < 0 || offset > value.length ? BluetoothGatt.GATT_INVALID_OFFSET : BluetoothGatt.GATT_SUCCESS;
                    respond(device, request, status, offset, status == BluetoothGatt.GATT_SUCCESS ?
                            Arrays.copyOfRange(value, offset, value.length) : null);
                });
            }
            @Override public void onDescriptorWriteRequest(BluetoothDevice device, int request, BluetoothGattDescriptor descriptor,
                    boolean prepared, boolean response, int offset, byte[] value) {
                byte[] bytes = value.clone();
                handler.post(() -> {
                    if (generation != id || server == null) return;
                    boolean enable = Arrays.equals(bytes, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean disable = Arrays.equals(bytes, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                    boolean valid = descriptor.getUuid().equals(CCCD) && offset == 0 && !prepared && (enable || disable)
                            && (subscriber == null || subscriber.equals(device)) && peer.available(device);
                    if (valid && enable && !attachPeer(device)) return;
                    diagnostics.add("MIDI-Anmeldung · gültig=" + valid + " aktiv=" + enable + " Antwort=" + response);
                    if (response && !respond(device, request, valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)) return;
                    if (valid && enable && subscriber == null) {
                        subscriber = device;
                        peer.setMidiSubscribed(true);
                        lastDisconnect = "";
                        stopAdvertising();
                        report.accept("MIDI verbunden");
                        pending.resetNotes(SystemClock.elapsedRealtime());
                        scheduleSend(0);
                    }
                    if (valid && disable && device.equals(subscriber)) {
                        resetSubscriber();
                        report.accept("MIDI abgemeldet · wartet auf den Mac");
                        advertise(id, true);
                    }
                });
            }
            @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int request, BluetoothGattCharacteristic c,
                    boolean prepared, boolean response, int offset, byte[] value) {
                if (response) handler.post(() -> {
                    if (generation == id && server != null) respond(device, request,
                            prepared || !c.getUuid().equals(MIDI) ? BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED :
                            offset != 0 ? BluetoothGatt.GATT_INVALID_OFFSET : BluetoothGatt.GATT_SUCCESS, 0, null);
                });
            }
            @Override public void onExecuteWrite(BluetoothDevice device, int request, boolean execute) {
                handler.post(() -> {
                    if (generation == id && server != null) respond(device, request,
                            execute ? BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED : BluetoothGatt.GATT_SUCCESS, 0, null);
                });
            }
            @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
                handler.post(() -> {
                    if (generation == id && peer.matches(device)) diagnostics.add("MIDI-MTU=" + mtu);
                });
            }
            @Override public void onNotificationSent(BluetoothDevice device, int status) {
                handler.post(() -> {
                    if (generation != id || !device.equals(subscriber) || !inFlight) return;
                    handler.removeCallbacks(sendTimeout);
                    inFlight = false;
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        failures = 0;
                        if (delayed) report.accept("MIDI verbunden");
                        delayed = false;
                        flush();
                    } else {
                        diagnostics.add("MIDI-Sendebestätigung · Status=" + status);
                        recoverOutput();
                    }
                });
            }
        };
    }

    private boolean attachPeer(BluetoothDevice device) {
        try {
            if (peer.available(device) && subscriber == null) {
                if (!peer.attached()) connectedAt = SystemClock.elapsedRealtime();
                peer.attach(server, device);
                if (device.getBondState() == BluetoothDevice.BOND_BONDED)
                    context.getSharedPreferences("ble-peer", Context.MODE_PRIVATE).edit().putString("address", device.getAddress()).apply();
            }
            return true;
        } catch (SecurityException ex) {
            fail("Bluetooth-Berechtigung fehlt.");
            return false;
        }
    }

    private void prepareKnownPeer() {
        String address = context.getSharedPreferences("ble-peer", Context.MODE_PRIVATE).getString("address", "");
        if (address.isEmpty()) return;
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothDevice device = manager.getAdapter().getRemoteDevice(address);
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) peer.prepare(device);
        } catch (IllegalArgumentException invalid) {
            context.getSharedPreferences("ble-peer", Context.MODE_PRIVATE).edit().remove("address").apply();
        } catch (SecurityException denied) {
            diagnostics.add("Vorbereitung nicht möglich: Bluetooth-Berechtigung fehlt");
        }
    }

    private boolean respond(BluetoothDevice device, int request, int status, int offset, byte[] value) {
        try {
            boolean sent = server.sendResponse(device, request, status, offset, value);
            diagnostics.add("Bluetooth-Anfrage beantwortet=" + sent + " Status=" + status);
            return sent;
        } catch (SecurityException ex) { fail("Bluetooth-Berechtigung fehlt."); }
        return false;
    }

    void send(long sample, int status, int a, int b) {
        if (subscriber == null) return;
        peer.midiActivity();
        if (failures >= 8) {
            pending.resetNotes(sample / 48);
            failures = 0;
        }
        pending.add(sample / 48, status, a, b);
        scheduleSend(0);
    }

    private void scheduleSend(long delay) {
        if (inFlight || retryScheduled || subscriber == null) return;
        retryScheduled = true;
        handler.postDelayed(retry, delay);
    }

    private void retrySend() {
        retryScheduled = false;
        flush();
    }

    private void notificationTimedOut() {
        if (!inFlight) return;
        diagnostics.add("MIDI-Sendebestätigung ausgeblieben");
        inFlight = false;
        recoverOutput();
    }

    private void recoverOutput() {
        // Delivery is unknown. Reset remote notes before sending fresh input.
        pending.resetNotes(SystemClock.elapsedRealtime());
        sendDelayed();
    }

    private void sendDelayed() {
        failures++;
        if (failures >= 3 && !delayed) {
            delayed = true;
            report.accept("MIDI verbunden · Senden verzögert");
        }
        if (failures < 8) scheduleSend(Math.min(400L, 12L << (failures - 1)));
    }

    @SuppressWarnings("deprecation")
    private void flush() {
        if (inFlight || retryScheduled || subscriber == null || server == null) return;
        BleMidiPackets.Packet packet = pending.peek();
        if (packet == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int status = server.notifyCharacteristicChanged(subscriber, characteristic, false, packet.data());
                if (status == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION) {
                    fail("Bluetooth-Berechtigung fehlt.");
                    return;
                }
                inFlight = status == BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setValue(packet.data());
                inFlight = server.notifyCharacteristicChanged(subscriber, characteristic, false);
            }
            if (inFlight) {
                peer.midiActivity();
                pending.remove(packet);
                handler.postDelayed(sendTimeout, 2500);
            } else {
                // Busy and other transient results leave the GATT session intact.
                sendDelayed();
            }
        } catch (SecurityException ex) { fail("Bluetooth-Berechtigung fehlt."); }
    }
}
