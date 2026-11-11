package io.moonshroom.hendrix;

final class NativeHub {
    static { System.loadLibrary("hendrix"); }

    interface MidiOutput { void send(long sample, int status, int a, int b); }
    private final MidiOutput midi;
    NativeHub(MidiOutput midi) { this.midi = midi; }

    @SuppressWarnings("unused")
    private void onMidi(long sample, int status, int a, int b) {
        midi.send(sample, status, a, b);
    }

    synchronized native long create();
    synchronized native void destroy(long hub);
    synchronized native boolean setAudio(long hub, float rate, float reference, boolean poly);
    synchronized native void setReference(long hub, float hz);
    synchronized native void guitarParameters(long hub, float[] values);
    synchronized native float[] guitarMetrics(long hub);
    synchronized native void resetAudio(long hub, long ms);
    synchronized native float[] tuner(long hub);
    synchronized native void note(long hub, int pitch, int velocity, long ms);
    synchronized native void control(long hub, int controller, int value, long ms);
    synchronized native void releaseKeys(long hub, long ms);
    synchronized native boolean startTake(long hub, long ms);
    synchronized native boolean stopTake(long hub, long ms);
    synchronized native byte[] exportTake(long hub);
    synchronized native long[] score(long hub);
    synchronized native long createQueue();
    synchronized native void destroyQueue(long queue);
    synchronized native boolean push(long queue, float[] data, int frames);
    synchronized native boolean drain(long queue, long hub, long ms);
}
