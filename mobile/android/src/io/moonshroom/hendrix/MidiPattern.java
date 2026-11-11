package io.moonshroom.hendrix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** SMF timing and channel data, independent of playback destination. */
final class MidiPattern {
    record Event(long micros, int status, int a, int b) {}
    private record Tick(long tick, int order, int status, int a, int b, int tempo) {}
    final List<Event> events;
    final long durationMicros;
    final int channels;
    MidiPattern(List<Event> events, long durationMicros, int channels) {
        this.events = List.copyOf(events); this.durationMicros = durationMicros; this.channels = channels;
    }
    static MidiPattern parse(byte[] bytes) throws IOException {
        if (bytes.length > 16_000_000) throw new IOException("MIDI-Datei zu groß");
        Reader r = new Reader(bytes, 0, bytes.length);
        if (r.number(4) != 0x4d546864) throw new IOException("Keine MIDI-Datei");
        int header = r.number(4), format = r.number(2), tracks = r.number(2), division = r.number(2);
        if (header < 6 || format > 1 || tracks < 1 || tracks > 256 || division == 0 || (division & 0x8000) != 0)
            throw new IOException("MIDI-Format nicht unterstützt");
        r.skip(header - 6);
        ArrayList<Tick> ticks = new ArrayList<>();
        int order = 0;
        long end = 0;
        for (int track = 0; track < tracks; track++) {
            if (r.number(4) != 0x4d54726b) throw new IOException("MIDI-Spur fehlt");
            int length = r.number(4);
            if (length < 0 || length > r.limit - r.pos) throw new IOException("MIDI-Spur beschädigt");
            Reader t = new Reader(bytes, r.pos, r.pos + length); r.skip(length);
            long tick = 0; int running = 0;
            while (t.pos < t.limit) {
                tick += t.variable();
                if (tick > 0x7fffffffL) throw new IOException("MIDI-Aufnahme zu lang");
                end = Math.max(end, tick);
                int value = t.number(1), status;
                if (value < 128) {
                    if (running == 0) throw new IOException("MIDI-Status fehlt");
                    status = running; t.pos--;
                } else status = value;
                if (status == 0xff) {
                    int type = t.number(1), count = t.variable();
                    if (type == 0x51 && count == 3) {
                        int tempo = t.number(3);
                        if (tempo == 0) throw new IOException("Ungültiges MIDI-Tempo");
                        ticks.add(new Tick(tick, order++, 0, 0, 0, tempo));
                    } else t.skip(count);
                    if (type == 0x2f) break;
                } else if (status == 0xf0 || status == 0xf7) {
                    running = 0; t.skip(t.variable());
                } else if (status >= 0x80 && status < 0xf0) {
                    running = status;
                    int a = t.data(), b = (status & 0xe0) == 0xc0 ? 0 : t.data();
                    ticks.add(new Tick(tick, order++, status, a, b, 0));
                } else throw new IOException("Ungültiges MIDI-Ereignis");
                if (ticks.size() > 500_000) throw new IOException("Zu viele MIDI-Ereignisse");
            }
        }
        ticks.sort(Comparator.comparingLong(Tick::tick).thenComparingInt(Tick::order));
        ArrayList<Event> events = new ArrayList<>();
        long previous = 0, micros = 0, remainder = 0;
        int tempo = 500_000, channels = 0;
        for (Tick event : ticks) {
            long scaled = (event.tick - previous) * tempo + remainder;
            micros += scaled / division; remainder = scaled % division;
            previous = event.tick;
            if (event.tempo != 0) tempo = event.tempo;
            else { events.add(new Event(micros, event.status, event.a, event.b)); channels |= 1 << (event.status & 15); }
        }
        micros += ((end - previous) * tempo + remainder) / division;
        return new MidiPattern(events, micros, channels);
    }
    private static final class Reader {
        final byte[] data; final int limit; int pos;
        Reader(byte[] data, int pos, int limit) { this.data=data; this.pos=pos; this.limit=limit; }
        int number(int count) throws IOException { int n=0; for(int i=0;i<count;i++) { if(pos>=limit) throw new IOException("MIDI-Datei unvollständig"); n=(n<<8)|(data[pos++]&255); } return n; }
        int data() throws IOException { int value=number(1); if(value>127) throw new IOException("Ungültige MIDI-Daten"); return value; }
        void skip(int count) throws IOException { if(count<0 || count>limit-pos) throw new IOException("MIDI-Datei unvollständig"); pos+=count; }
        int variable() throws IOException { int n=0; for(int i=0;i<4;i++) { int v=number(1); n=(n<<7)|(v&127); if((v&128)==0) return n; } throw new IOException("Ungültige MIDI-Zeit"); }
    }
}
