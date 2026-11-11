package io.moonshroom.hendrix;

import android.bluetooth.*;
import java.util.UUID;

/** Deterministic idle-read tests; these do not measure real radio power or reliability. */
public final class BleMidiIdleSignalTest {
    static BluetoothGatt ready(BleMidiCallbacksTest.Session s, int property) {
        s.read(); s.subscribe(); s.completeOutputs();
        BluetoothGatt client = s.mac.client;
        BluetoothGattService gap = new BluetoothGattService(uuid(0x1800), 0);
        gap.addCharacteristic(new BluetoothGattCharacteristic(uuid(0x2a01), property, 0));
        client.services.put(uuid(0x1800), gap);
        client.callback.onConnectionStateChange(client, 0, BluetoothProfile.STATE_CONNECTED);
        s.handler.runReady();
        client.callback.onServicesDiscovered(client, 0);
        s.handler.runReady();
        return client;
    }

    static UUID uuid(int value) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", value));
    }

    static void finish(BleMidiCallbacksTest.Session s, BluetoothGatt client, int status, boolean oldApi) {
        BluetoothGattCharacteristic c = client.reads.get(client.reads.size()-1);
        if (oldApi) client.callback.onCharacteristicRead(client, c, status);
        else client.callback.onCharacteristicRead(client, c, new byte[]{0,0}, status);
        s.handler.runReady();
    }

    public static void main(String[] args) {
        BleMidiCallbacksTest.Session s = new BleMidiCallbacksTest.Session();
        BluetoothGatt client = ready(s, BluetoothGattCharacteristic.PROPERTY_READ);
        int musicalPackets = s.server.sent.size();
        s.handler.advance(4999);
        assert client.readAttempts == 0;
        s.handler.advance(1);
        assert client.readAttempts == 1 && client.reads.get(0).getUuid().equals(uuid(0x2a01));
        finish(s, client, 0, false);
        // Three simulated minutes: exactly one small read every five seconds.
        for (int i=1; i<36; i++) {
            s.handler.advance(5000);
            assert client.readAttempts == i+1;
            finish(s, client, 0, (i & 1) == 0);
        }
        assert s.server.sent.size() == musicalPackets : "No keepalive in MIDI stream";
        for (int i=0; i<20; i++) {
            s.midi.send(0, 0x90, 60, 100); s.handler.runReady(); s.completeOutputs();
            s.handler.advance(1000);
        }
        assert client.readAttempts == 36 : "Do not ping while MIDI is active";
        s.handler.advance(3999);
        assert client.readAttempts == 36;
        s.handler.advance(1);
        assert client.readAttempts == 37;
        finish(s, client, 0, true);
        // A sleeping CPU must not replay missed five-second intervals in a burst.
        s.handler.wakeAfter(600_000);
        assert client.readAttempts == 38;
        finish(s, client, 0, false);
        s.handler.advance(4999);
        assert client.readAttempts == 38;
        s.callback.onDescriptorWriteRequest(s.mac, 70, s.characteristic.descriptor, false, true, 0, new byte[]{0,0});
        s.handler.runReady(); s.handler.advance(60_000);
        assert client.readAttempts == 38 : "Stop on unsubscribe";
        s.midi.stop();

        BleMidiCallbacksTest.Session noTarget = new BleMidiCallbacksTest.Session();
        BluetoothGatt unavailable = ready(noTarget, BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        noTarget.handler.advance(60_000);
        assert unavailable.readAttempts == 0 : "Never read an unsupported attribute";
        assert noTarget.log.snapshot().contains("Lebenszeichen nicht verfügbar");
        noTarget.midi.stop();

        BleMidiCallbacksTest.Session busy = new BleMidiCallbacksTest.Session();
        BluetoothGatt busyClient = ready(busy, BluetoothGattCharacteristic.PROPERTY_READ);
        busyClient.readAccepted = false;
        busy.handler.advance(5000); assert busyClient.readAttempts == 1;
        busy.handler.advance(9999); assert busyClient.readAttempts == 1;
        busy.handler.advance(1); assert busyClient.readAttempts == 2;
        busy.handler.advance(19999); assert busyClient.readAttempts == 2;
        busy.handler.advance(1); assert busyClient.readAttempts == 3;
        busy.handler.advance(300_000); assert busyClient.readAttempts == 3;
        assert busy.server.closes == 0 : "An idle-read failure must not disconnect MIDI";
        busy.midi.stop();

        BleMidiCallbacksTest.Session timeout = new BleMidiCallbacksTest.Session();
        BluetoothGatt silent = ready(timeout, BluetoothGattCharacteristic.PROPERTY_READ);
        timeout.handler.advance(5000);
        assert silent.readAttempts == 1;
        silent.callback.onServiceChanged(silent); timeout.handler.runReady();
        assert silent.discoveries == 1 : "Do not overlap discovery with an unanswered read";
        timeout.handler.advance(300_000);
        assert silent.readAttempts == 1 && timeout.server.closes == 0;
        assert timeout.log.snapshot().contains("Antwort fehlt");
        finish(timeout, silent, 0, false);
        assert silent.discoveries == 2 : "Deferred service discovery resumes after the reply";
        timeout.midi.stop();
        int readsBefore = silent.readAttempts;
        silent.callback.onServicesDiscovered(silent, 0); timeout.handler.advance(30_000);
        assert silent.readAttempts == readsBefore : "Ignore callbacks after stop";

        BleMidiCallbacksTest.Session dropped = new BleMidiCallbacksTest.Session();
        BluetoothGatt droppedClient = ready(dropped, BluetoothGattCharacteristic.PROPERTY_READ);
        dropped.handler.advance(5000);
        dropped.callback.onConnectionStateChange(dropped.mac, 19, BluetoothProfile.STATE_DISCONNECTED);
        dropped.handler.runReady(); dropped.handler.advance(120_000);
        assert droppedClient.readAttempts == 1 && droppedClient.closes == 1;
        dropped.midi.stop();

        BleMidiCallbacksTest.Session lateError = new BleMidiCallbacksTest.Session();
        BluetoothGatt lateClient = ready(lateError, BluetoothGattCharacteristic.PROPERTY_READ);
        lateError.handler.advance(20_000);
        finish(lateError, lateClient, BluetoothGatt.GATT_FAILURE, false);
        lateError.handler.advance(60_000);
        assert lateClient.readAttempts == 1 : "Late error must not undo timeout suspension";
        lateError.midi.stop();
        // Bursts of service changes must not overlap ATT discovery or idle reads.
        BleMidiCallbacksTest.Session changes = new BleMidiCallbacksTest.Session();
        BluetoothGatt changedClient = ready(changes, BluetoothGattCharacteristic.PROPERTY_READ);
        for (int i=0; i<10; i++) changedClient.callback.onServiceChanged(changedClient);
        changes.handler.runReady();
        assert changedClient.discoveries == 2;
        changes.handler.advance(30_000);
        assert changedClient.readAttempts == 0 && changedClient.discoveries == 2;
        assert changes.log.snapshot().contains("seit 20 s keine Antwort");
        changedClient.callback.onServicesDiscovered(changedClient, 0); changes.handler.runReady();
        assert changedClient.discoveries == 3 : "One follow-up covers all changes during discovery";
        changedClient.callback.onServicesDiscovered(changedClient, 0); changes.handler.runReady();
        changes.handler.advance(1);
        assert changedClient.readAttempts == 1 : "Idle reads resume only on the fresh service snapshot";
        finish(changes, changedClient, 0, false);
        changes.midi.stop();

        BleMidiCallbacksTest.Session rejected = new BleMidiCallbacksTest.Session();
        rejected.read(); rejected.subscribe(); rejected.completeOutputs();
        BluetoothGatt rejectedClient = rejected.mac.client;
        rejectedClient.discoveryAccepted = false;
        rejectedClient.callback.onConnectionStateChange(rejectedClient, 0, BluetoothProfile.STATE_CONNECTED);
        rejected.handler.runReady();
        assert rejectedClient.discoveries == 1;
        rejected.handler.advance(1999); assert rejectedClient.discoveries == 1;
        rejected.handler.advance(1); assert rejectedClient.discoveries == 2;
        rejectedClient.discoveryAccepted = true;
        rejected.handler.advance(4000); assert rejectedClient.discoveries == 3;
        rejectedClient.callback.onServicesDiscovered(rejectedClient, BluetoothGatt.GATT_FAILURE);
        rejected.handler.runReady(); rejected.handler.advance(8000);
        assert rejectedClient.discoveries == 4 : "Retry callback failures as well as rejected starts";
        rejectedClient.callback.onServicesDiscovered(rejectedClient, BluetoothGatt.GATT_FAILURE);
        rejected.handler.runReady(); rejected.handler.advance(120_000);
        assert rejectedClient.discoveries == 4 && rejected.server.closes == 0 : "Bound retries without disconnecting MIDI";
        rejected.midi.stop();

        BleMidiCallbacksTest.Session reconnect = new BleMidiCallbacksTest.Session();
        BluetoothGatt reconnectClient = ready(reconnect, BluetoothGattCharacteristic.PROPERTY_READ);
        reconnect.handler.advance(5000);
        reconnectClient.callback.onConnectionStateChange(reconnectClient, 0, BluetoothProfile.STATE_DISCONNECTED);
        reconnect.handler.runReady(); reconnect.handler.advance(30000);
        assert !reconnect.log.snapshot().contains("Antwort fehlt") : "Disconnect cancels outstanding read timeout";
        reconnectClient.callback.onConnectionStateChange(reconnectClient, 0, BluetoothProfile.STATE_CONNECTED);
        reconnect.handler.runReady();
        assert reconnectClient.discoveries == 2 : "A new connection must rediscover instead of reusing stale state";
        reconnectClient.callback.onServicesDiscovered(reconnectClient, 0); reconnect.handler.runReady();
        reconnect.handler.advance(1);
        assert reconnectClient.readAttempts == 2 : "Idle signals resume after reconnect";
        reconnectClient.callback.onServiceChanged(reconnectClient); reconnect.handler.runReady();
        reconnect.midi.stop(); reconnect.handler.advance(120_000);
        assert reconnectClient.discoveries == 2 : "Stop cancels all deferred work";
        System.out.println("BLE idle signal: 5s spacing, playing suppression, no MIDI events, no wakeup burst, unsupported hosts, backoff, timeout, unsubscribe/disconnect/stop OK (fake transport)");
    }
}
