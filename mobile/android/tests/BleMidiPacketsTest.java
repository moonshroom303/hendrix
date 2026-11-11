package io.moonshroom.hendrix;

import java.util.Arrays;

public final class BleMidiPacketsTest {
    public static void main(String[] args) {
        BleMidiPackets queue = new BleMidiPackets();
        for (int note = 60; note < 65; note++) queue.add(127, 0x90, note, 100);
        queue.add(128, 0x80, 60, 0);
        BleMidiPackets.Packet first = queue.peek();
        assert first.count() == 4 && first.data().length == 17;
        assert Arrays.equals(first.data(), queue.peek().data()); // A busy send can retry the same data.
        assert (first.data()[0] & 255) == 0x80 && (first.data()[1] & 255) == 0xff;
        queue.remove(first);
        BleMidiPackets.Packet second = queue.peek();
        assert second.count() == 1 && second.data()[3] == 64;
        queue.remove(second);
        BleMidiPackets.Packet third = queue.peek();
        assert third.count() == 1 && (third.data()[0] & 255) == 0x81 && (third.data()[1] & 255) == 0x80;
        queue.remove(third);
        assert queue.peek() == null;

        for (int i = 0; i < 129; i++) queue.add(256, 0x90, 42, 80);
        BleMidiPackets.Packet recovery = queue.peek();
        assert recovery.count() == 4;
        assert (recovery.data()[2] & 255) == 0xb0 && recovery.data()[3] == 64;
        assert recovery.data()[7] == 123 && recovery.data()[11] == 120;
        queue.remove(recovery);
        BleMidiPackets.Packet otherChannel = queue.peek();
        assert (otherChannel.data()[2] & 255) == 0xb1;
        queue.remove(otherChannel);
        BleMidiPackets.Packet latest = queue.peek();
        assert latest.count() == 1 && latest.data()[3] == 42;
        System.out.println("BLE MIDI packets: burst, timestamp boundary, retry and queue recovery OK");
    }
}
