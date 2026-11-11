package io.moonshroom.hendrix;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Offline GM output; no synth work or AudioTrack writes on the UI/input thread. */
final class GeneralMidiSynth {
    static { System.loadLibrary("hendrix"); }
    private final Context context;
    private final Handler main;
    private final Consumer<String> error;
    private final ArrayDeque<int[]> queue = new ArrayDeque<>();
    private final ArrayList<Runnable> readyCallbacks = new ArrayList<>();
    private volatile int generation;
    private volatile boolean enabled, ready;
    private volatile int liveProgram=24, patternProgram;
    private volatile int liveBendRange=2;
    private volatile float volume=.65f;
    GeneralMidiSynth(Context context, Handler main, Consumer<String> error) { this.context=context.getApplicationContext(); this.main=main; this.error=error; }
    boolean ready() { return ready; }
    void setProgram(int part,int value) { if(part==0) liveProgram=value; else patternProgram=value; }
    void setBendRange(int value) { liveBendRange=value; }
    void setVolume(float value) { volume=Math.max(0,Math.min(1,value)); }
    void start(Runnable onReady) {
        if (onReady!=null) { if(ready) { main.post(onReady); return; } readyCallbacks.add(onReady); }
        if(enabled) return;
        enabled=true; int token=++generation;
        Thread thread=new Thread(() -> run(token),"Hendrix-General-MIDI"); thread.start();
    }
    void stop() {
        enabled=ready=false; generation++;
        readyCallbacks.clear(); synchronized(queue) { queue.clear(); }
    }
    void send(int part,int status,int a,int b) {
        if(!enabled) return;
        synchronized(queue) {
            if(queue.size()>=1024) { queue.clear(); queue.add(new int[]{0,-1,0,0}); queue.add(new int[]{1,-1,0,0}); }
            queue.add(new int[]{part,status,a,b});
        }
    }
    void silence(int part) { send(part,-1,0,0); }
    private File bank(int token) throws IOException {
        File output=new File(context.getFilesDir(),"TimGM6mb-1.3.sf2");
        synchronized(GeneralMidiSynth.class) {
            if(output.length()!=5_969_788) {
                File temporary=new File(context.getFilesDir(),"TimGM6mb-"+token+".tmp");
                try(InputStream input=context.getAssets().open("synth/TimGM6mb.sf2")) {
                    Files.copy(input,temporary.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temporary.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING);
                } finally { temporary.delete(); }
            }
        }
        return output;
    }
    private void run(int token) {
        AudioTrack track=null; long synth=0;
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            synth=create(bank(token).getPath(),48000);
            if(synth==0) throw new IOException("General-MIDI-Klangbank konnte nicht geladen werden");
            if(!enabled || generation!=token) return;
            int minimum=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT);
            track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(Math.max(4096,minimum)).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED) throw new IOException("Audioausgang nicht verfügbar");
            for(AudioDeviceInfo device:context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { track.setPreferredDevice(device); break; }
            }
            int live=-1, pattern=-1, range=-1; float level=-1;
            track.play();
            main.post(() -> {
                if(!enabled || generation!=token) return;
                ready=true;
                ArrayList<Runnable> callbacks=new ArrayList<>(readyCallbacks); readyCallbacks.clear(); callbacks.forEach(Runnable::run);
            });
            float[] samples=new float[512];
            while(enabled && generation==token) {
                if(live!=liveProgram) { live=liveProgram; program(synth,0,live); }
                if(pattern!=patternProgram) { pattern=patternProgram; program(synth,1,pattern); }
                if(range!=liveBendRange) {
                    range=liveBendRange;
                    message(synth,0,0xb1,101,0); message(synth,0,0xb1,100,0);
                    message(synth,0,0xb1,6,range); message(synth,0,0xb1,38,0);
                    message(synth,0,0xb1,101,127); message(synth,0,0xb1,100,127);
                }
                if(level!=volume) { level=volume; track.setVolume(level); }
                synchronized(queue) {
                    if (!enabled || generation != token) break;
                    int[] event;
                    while((event=queue.poll())!=null) {
                        if(event[1]<0) panic(synth,event[0]);
                        else message(synth,event[0],event[1],event[2],event[3]);
                    }
                }
                render(synth,samples);
                int offset=0;
                while(offset<samples.length && enabled && generation==token) {
                    int written=track.write(samples,offset,samples.length-offset,AudioTrack.WRITE_BLOCKING);
                    if(written<=0) throw new IOException("Audioausgabe unterbrochen");
                    offset+=written;
                }
            }
        } catch(IOException | RuntimeException failure) {
            String reason=failure.getLocalizedMessage();
            main.post(() -> { if(generation==token) { stop(); error.accept(reason==null?"General MIDI nicht verfügbar":reason); } });
        } finally {
            if(track!=null) { try { track.pause(); track.flush(); } catch(IllegalStateException ignored) {} track.release(); }
            if(synth!=0) destroy(synth);
        }
    }
    private static native long create(String path,int rate);
    private static native void destroy(long ptr);
    private static native void program(long ptr,int part,int value);
    private static native void message(long ptr,int part,int status,int a,int b);
    private static native void panic(long ptr,int part);
    private static native void render(long ptr,float[] samples);
}
