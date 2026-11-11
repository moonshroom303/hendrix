package io.moonshroom.hendrix;

import android.os.Handler;
import android.os.SystemClock;
import java.util.function.Consumer;

/** Monotonic scheduling. The sink is captured for each run; route changes stop it. */
final class MidiPatternPlayer {
    interface Output { void send(int status, int a, int b); }
    private final Handler handler;
    private final Consumer<Boolean> changed;
    private final Runnable tick = this::tick;
    private MidiPattern pattern;
    private Output output;
    private long origin;
    private int index;
    private boolean playing;
    MidiPatternPlayer(Handler handler, Consumer<Boolean> changed) { this.handler=handler; this.changed=changed; }
    boolean playing() { return playing; }
    void play(MidiPattern value, Output sink) {
        stop(); pattern=value; output=sink; index=0; origin=SystemClock.elapsedRealtime(); playing=true;
        changed.accept(true); handler.post(tick);
    }
    long positionMillis() { return playing ? Math.min(SystemClock.elapsedRealtime()-origin, (pattern.durationMicros+999)/1000) : 0; }
    private void tick() {
        if (!playing) return;
        long elapsed=(SystemClock.elapsedRealtime()-origin)*1000;
        int budget=256;
        while (index<pattern.events.size() && pattern.events.get(index).micros()<=elapsed && budget-- > 0) {
            MidiPattern.Event event=pattern.events.get(index++);
            output.send(event.status(),event.a(),event.b());
        }
        if (index==pattern.events.size() && elapsed>=pattern.durationMicros) { stop(); return; }
        long next=index<pattern.events.size() ? pattern.events.get(index).micros() : pattern.durationMicros;
        handler.postDelayed(tick,Math.max(0,(next-elapsed+999)/1000));
    }
    void stop() {
        handler.removeCallbacks(tick);
        if (!playing) return;
        playing=false;
        // Even interrupted notes and sustain must end on the original output.
        for(int channel=0;channel<16;channel++) if((pattern.channels & (1<<channel))!=0) {
            output.send(0xb0|channel,64,0);
            output.send(0xb0|channel,123,0);
            output.send(0xb0|channel,120,0);
            output.send(0xb0|channel,1,0);
            output.send(0xe0|channel,0,64);
        }
        pattern=null; output=null; changed.accept(false);
    }
}
