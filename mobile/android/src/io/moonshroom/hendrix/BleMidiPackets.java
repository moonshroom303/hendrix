package io.moonshroom.hendrix;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Packs complete MIDI 1.0 events within the default 20-byte ATT payload. */
final class BleMidiPackets {
    record Packet(byte[] data, int count) {}

    private static final int LIMIT = 128;
    private static final int PAYLOAD = 20;
    private final ArrayDeque<byte[]> events = new ArrayDeque<>();

    void add(long milliseconds, int status, int a, int b) {
        if (events.size() >= LIMIT) resetNotes(milliseconds);
        append(milliseconds, status, a, b);
    }

    void resetNotes(long milliseconds) {
        events.clear();
        for (int channel = 0; channel < 2; channel++) {
            append(milliseconds, 0xb0 | channel, 64, 0);
            append(milliseconds, 0xb0 | channel, 123, 0);
            append(milliseconds, 0xb0 | channel, 120, 0);
            append(milliseconds, 0xe0 | channel, 0, 64);
        }
    }

    private void append(long milliseconds, int status, int a, int b) {
        int time = (int)(milliseconds & 8191);
        byte[] event = {(byte)(0x80 | (time >> 7)), (byte)(0x80 | (time & 127)),
                (byte)status, (byte)a, (byte)b};
        events.addLast((status & 0xe0) == 0xc0 ? Arrays.copyOf(event, 4) : event);
    }

    Packet peek() {
        byte[] first = events.peekFirst();
        if (first == null) return null;
        byte[] packet = new byte[PAYLOAD];
        packet[0] = first[0];
        int size = 1;
        int count = 0;
        for (byte[] event : events) {
            int length = event.length - 1;
            if (event[0] != first[0] || size + length > PAYLOAD) break;
            System.arraycopy(event, 1, packet, size, length);
            size += length;
            count++;
        }
        return new Packet(Arrays.copyOf(packet, size), count);
    }

    void remove(Packet sent) {
        for (int i = 0; i < sent.count(); i++) events.removeFirst();
    }

    void clear() { events.clear(); }
}
