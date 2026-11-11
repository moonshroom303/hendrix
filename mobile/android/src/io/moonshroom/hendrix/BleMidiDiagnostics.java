package io.moonshroom.hendrix;

import android.os.Build;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.Locale;

/** Small local log; never records device addresses, notes, or audio. */
final class BleMidiDiagnostics {
    private final long started = SystemClock.elapsedRealtime();
    private final ArrayDeque<String> events = new ArrayDeque<>();

    synchronized void add(String event) {
        if (events.size() == 100) events.removeFirst();
        events.addLast(String.format(Locale.ROOT, "+%.3fs %s",
                (SystemClock.elapsedRealtime() - started) / 1000.0, event));
    }

    synchronized String snapshot() {
        return "Hendrix 0.1.12 · " + Build.MANUFACTURER + " " + Build.MODEL +
                " · Android " + Build.VERSION.RELEASE + " (" + Build.VERSION.SDK_INT + ")\n\n" +
                String.join("\n", events);
    }
}
