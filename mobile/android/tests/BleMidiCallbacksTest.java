package io.moonshroom.hendrix;

import android.bluetooth.*;
import android.bluetooth.le.AdvertiseCallback;
import android.content.Context;
import android.os.Handler;
import java.util.ArrayList;

/** Exercises production callbacks with a deterministic fake Android transport. */
public final class BleMidiCallbacksTest {
    static final class Session {
        final Context context = new Context();
        final Handler handler = new Handler();
        final BluetoothDevice mac = new BluetoothDevice();
        final ArrayList<String> reports = new ArrayList<>();
        final BleMidiDiagnostics log = new BleMidiDiagnostics();
        final BleMidi midi = new BleMidi(context, handler, reports::add,
                error -> { throw new AssertionError(error); }, log);
        final BluetoothGattServer server;
        final BluetoothGattServerCallback callback;
        final BluetoothGattCharacteristic characteristic;
        int completed;
        Session() {
            midi.start(); handler.runReady();
            server = context.manager.server;
            callback = server.callback;
            characteristic = server.service.characteristic;
        }
        void read() {
            callback.onCharacteristicReadRequest(mac, 1, 0, characteristic);
            handler.runReady();
        }
        void subscribe() {
            callback.onDescriptorWriteRequest(mac, 2, characteristic.descriptor, false, true, 0, new byte[]{1,0});
            handler.runReady();
        }
        void completeOutputs() {
            while (completed < server.sent.size()) {
                completed++;
                callback.onNotificationSent(mac, BluetoothGatt.GATT_SUCCESS);
                handler.runReady();
            }
        }
    }

    public static void main(String[] args) {
        Session s = new Session();
        BluetoothDevice other = new BluetoothDevice();
        s.callback.onConnectionStateChange(other, 0, BluetoothProfile.STATE_CONNECTED);
        s.handler.runReady();
        assert s.server.connects == 0 && other.clients == 0 : "Do not claim other apps' BLE devices";
        s.read(); s.read();
        assert s.server.connects == 1 && s.mac.clients == 1 : "Hold existing peer exactly once";
        BluetoothGatt client = s.mac.client;
        client.callback.onConnectionStateChange(client, 0, BluetoothProfile.STATE_CONNECTED);
        client.callback.onConnectionStateChange(client, 0, BluetoothProfile.STATE_CONNECTED);
        s.handler.runReady();
        assert client.discoveries == 1;
        client.callback.onServiceChanged(client); s.handler.runReady();
        assert client.discoveries == 1 : "Coalesce changes while discovery is outstanding";
        client.callback.onServicesDiscovered(client, 0); s.handler.runReady();
        assert client.discoveries == 2;
        client.callback.onServicesDiscovered(client, 0); s.handler.runReady();
        s.subscribe(); s.completeOutputs();
        assert s.server.sent.size() == 2 : "Initial reset reaches both channels";
        int initial = s.server.sent.size();
        s.subscribe(); s.completeOutputs();
        assert s.server.sent.size() == initial : "Repeated CCCD enable must not interrupt held notes";
        s.handler.advance(90_000);
        assert s.midi.isActive() && client.closes == 0 && s.server.closes == 0;
        assert s.server.sent.size() == initial && s.server.connects == 1 : "Idle sends no dummy traffic";
        s.midi.send(90_000 * 48L, 0x90, 60, 100); s.handler.runReady(); s.completeOutputs();
        assert s.server.sent.size() == initial + 1;
        s.callback.onConnectionStateChange(other, 8, BluetoothProfile.STATE_DISCONNECTED);
        s.handler.runReady();
        assert client.closes == 0 : "Unrelated disconnect must not reset MIDI";

        // ATT requests must complete, including unsupported prepared writes.
        s.callback.onExecuteWrite(s.mac, 10, true);
        s.callback.onExecuteWrite(s.mac, 11, false);
        s.callback.onCharacteristicWriteRequest(s.mac, 12, s.characteristic, true, true, 0, new byte[]{1});
        s.callback.onCharacteristicWriteRequest(s.mac, 13, s.characteristic, false, true, 3, new byte[]{1});
        s.callback.onDescriptorReadRequest(s.mac, 14, 1, s.characteristic.descriptor);
        s.handler.runReady();
        assert response(s, 10).status() == BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        assert response(s, 11).status() == BluetoothGatt.GATT_SUCCESS;
        assert response(s, 12).status() == BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
        assert response(s, 13).status() == BluetoothGatt.GATT_INVALID_OFFSET;
        assert response(s, 14).value().length == 1 && response(s, 14).value()[0] == 0;

        s.callback.onConnectionStateChange(s.mac, 19, BluetoothProfile.STATE_DISCONNECTED);
        s.handler.runReady();
        assert client.closes == 1 && client.disconnects == 0;
        assert s.mac.clients == 2 && s.mac.background : "Prepare known client before the next incoming MIDI read";
        assert s.server.connects == 1 : "Preparation must not claim a server MIDI connection";
        assert s.context.manager.adapter.advertiser.starts == 2;
        assert s.reports.get(s.reports.size()-1).contains("90 s getrennt (19)");
        int count = s.server.sent.size();
        s.midi.send(1, 0x90, 60, 90); s.handler.runReady();
        assert s.server.sent.size() == count : "Disconnected notes must not replay on reconnect";
        s.read(); s.subscribe(); s.completeOutputs();
        assert s.mac.clients == 2 && s.server.connects == 2;
        BluetoothGatt next = s.mac.client;
        client.callback.onServiceChanged(client); s.handler.runReady();
        assert next.discoveries == 0 : "Ignore stale client callback";
        s.midi.stop();
        assert next.disconnects == 1 && next.closes == 1 && s.server.cancels == 1 && s.server.closes == 1;
        s.callback.onCharacteristicReadRequest(s.mac, 50, 0, s.characteristic);
        s.handler.runReady();
        assert s.server.responses.stream().noneMatch(r -> r.request() == 50);

        s.midi.start(); s.handler.runReady();
        BluetoothGatt prepared = s.mac.client;
        assert s.mac.clients == 3 && s.mac.background && s.context.manager.server.connects == 0 : "Remember the bonded receiver across server restarts";
        prepared.callback.onConnectionStateChange(prepared, 0, BluetoothProfile.STATE_CONNECTED);
        s.handler.runReady();
        assert prepared.discoveries == 1 : "Client discovery starts before any MIDI request";
        s.midi.stop();
        assert prepared.closes == 1 && s.context.manager.server.cancels == 0 : "Do not cancel unclaimed server connections on stop";

        Session failedResponse = new Session();
        failedResponse.server.responseAccepted = false;
        failedResponse.subscribe();
        assert failedResponse.server.sent.isEmpty() : "Cannot subscribe after failed response";
        failedResponse.midi.stop();

        Session stopped = new Session();
        AdvertiseCallback lateAdvertisement = stopped.context.manager.adapter.advertiser.callback;
        stopped.midi.stop();
        int reportCount = stopped.reports.size();
        lateAdvertisement.onStartFailure(2); stopped.handler.runReady();
        assert stopped.reports.size() == reportCount && !stopped.midi.isActive();

        // The API 31/32 notification path also retains the link through a busy send.
        android.os.Build.VERSION.SDK_INT = 31;
        Session legacy = new Session();
        legacy.server.notifyStatus = 1;
        legacy.subscribe(); legacy.handler.advance(1000);
        assert legacy.server.closes == 0 && legacy.context.manager.opens == 1;
        legacy.server.notifyStatus = 0;
        legacy.handler.advance(1000); legacy.completeOutputs();
        assert !legacy.server.sent.isEmpty();
        legacy.midi.stop();

        for (int i=0; i<150; i++) s.log.add("event " + i);
        String snapshot = s.log.snapshot();
        assert !snapshot.contains("event 49\n") && snapshot.contains("event 149");
        System.out.println("BLE callbacks: peer ownership, service discovery, 90s idle, ATT replies, disconnect/reconnect, stale callbacks, cleanup and busy send OK (fake transport)");
    }

    static BluetoothGattServer.Response response(Session s, int request) {
        return s.server.responses.stream().filter(r -> r.request() == request).findFirst().orElseThrow();
    }
}
