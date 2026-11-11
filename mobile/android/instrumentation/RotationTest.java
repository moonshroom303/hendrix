package io.moonshroom.hendrix.tests;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Instrumentation against the real APK, native engine, WebView and AudioRecord.
 * Use a dedicated emulator: the test records a take and changes UI preferences.
 */
public final class RotationTest extends Instrumentation {
    private Activity activity;
    private volatile int stopped, destroyed;
    private final Map<String, Object> session = new LinkedHashMap<>();
    private long usbGeneration;
    private int gmGeneration;
    private int taps;
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity a, Bundle b) {}
        public void onActivityStarted(Activity a) {}
        public void onActivityResumed(Activity a) {}
        public void onActivityPaused(Activity a) {}
        public void onActivityStopped(Activity a) { stopped++; }
        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        public void onActivityDestroyed(Activity a) { destroyed++; }
    };

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            exercise();
            result.putString("stream", "PASS: rotation kept the same AudioRecord, capture thread, native engine, recording, USB session, Bluetooth binding, GM synth and WebViews. Keys, fretboard, collapse/expand, MIDI export and GM pattern playback across rotation passed.\n");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(failure));
        } finally {
            getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE);
            if (activity != null) main(() -> {
                activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
                activity.finish();
            });
        }
        finish(code, result);
    }

    private void exercise() throws Exception {
        getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_0);
        Intent intent = new Intent(Intent.ACTION_MAIN).setClassName("io.moonshroom.hendrix.mobile", "io.moonshroom.hendrix.HendrixActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = startActivitySync(intent);
        main(() -> activity.getApplication().registerActivityLifecycleCallbacks(lifecycle));
        await(() -> field(activity, "bluetooth") != null && (boolean)field(field(activity, "classic"), "ready"), "Initial UI and service");
        main(() -> {
            check(text(activity.getWindow().getDecorView(), "Gesang") == null, "Voice is hidden");
            if (!(boolean)field(activity, "usbSelected")) call(activity, "toggleUsb");
            click("Eingang starten");
            ((Switch)text(activity.getWindow().getDecorView(), "Direktklang · General MIDI")).setChecked(true);
            if (!(boolean)field(activity, "midiInputExpanded")) click("MIDI-Eingabe einblenden");
            click("Keys");
            set(activity, "octave", 5);
            call(activity, "renderMidiControls");
            ((Switch)text(activity.getWindow().getDecorView(), "Sustain")).setChecked(true);
            ((SeekBar)description(activity.getWindow().getDecorView(), "Modulation")).setProgress(37);
            ((WebView)field(activity, "classic")).evaluateJavascript("window.configureClassic({version:1,current:values.map((v,i)=>i===8?432:v)})", null);
        });
        await(() -> field(activity, "audio") != null && ((float[])field(activity, "guitarParameters"))[8] == 432 && (boolean)field(field(activity,"gm"),"ready"), "Microphone, GM audio and classic tuning");
        exerciseVocalRanges();
        main(() -> {
            call(activity, "toggleRecording");
            call(activity, "note", new Class<?>[]{int.class,int.class}, 60,100);
            for (String key : new String[]{"audio","capture","nativeHub","hub","queue","usb","usbWorker","bluetooth","classic","studio","content","surface","gm"}) session.put(key, field(activity,key));
            usbGeneration = ((Number)field(field(activity,"usb"),"generation")).longValue();
            gmGeneration = (int)field(field(activity,"gm"),"generation");
        });
        AudioRecord recorder = (AudioRecord)session.get("audio");
        main(() -> {
            click("Audio-Latenz");
            ((android.widget.Spinner)description(activity.getWindow().getDecorView(), "Eingangs-Leseblock")).setSelection(0);
        });
        await(() -> (int)field(activity,"audioBlockFrames") == 16, "16-sample input block selected");
        main(() -> {
            long before = (long)field(activity,"audioProcessedFrames");
            // Deliberately stall the UI. Audio processing must continue independently.
            SystemClock.sleep(500);
            check((long)field(activity,"audioProcessedFrames") > before, "Detector advances while UI thread is blocked");
            check(field(activity,"audio") == recorder, "Live settings do not restart audio");
        });
        long firstFrame = frames(recorder);
        for (int rotation : new int[]{1,0,3,0,1,0}) {
            rotate(rotation);
            main(() -> verifySession(true));
            tapSurface();
            screenshot(rotation == 0 ? "portrait" : "landscape");
        }
        check(frames(recorder) > firstFrame, "Audio frames continue across rotation");
        main(() -> {
            check((int)field(activity,"audioBlockFrames") == 16, "16-sample setting survives rotation");
            android.widget.Spinner blocks = (android.widget.Spinner)description(
                    activity.getWindow().getDecorView(), "Eingangs-Leseblock");
            blocks.setSelection(blocks.getCount() - 1);
        });
        await(() -> (int)field(activity,"audioBlockFrames") == 1024, "Large input block selected live");
        main(() -> {
            click("MIDI-Eingabe ausblenden");
            check(!(boolean)field(activity,"midiInputExpanded"), "Inputs collapse");
            check(((View)field(activity,"midiInputBody")).getVisibility() == View.GONE, "Input body hidden");
            verifySession(false);
        });
        rotate(1);
        main(() -> {
            verifySession(false);
            check(!(boolean)field(activity,"midiInputExpanded"), "Collapsed state survives rotation");
            click("MIDI-Eingabe einblenden");
            check((int)field(activity,"midiInput") == 1 && (int)field(activity,"octave") == 5, "Keys selection and octave retained");
        });
        tapSurface();
        main(() -> click("Griffbrett"));
        Object fretboard = field(activity,"surface");
        rotate(0);
        main(() -> {
            check(field(activity,"surface") == fretboard && (int)field(activity,"midiInput") == 0, "Fretboard survives rotation");
            verifySession(false);
        });
        tapSurface();
        main(() -> {
            call(activity,"toggleRecording");
            check(!(boolean)field(activity,"recording"), "Stop recording works");
            check(field(activity,"audio") == recorder && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING, "Recording stop leaves microphone running");
            long[] notes = (long[])field(activity,"notes");
            check(notes.length / 5 >= taps + 1, "All played notes, including held note, recorded");
            click("Tabulatur");
            click("Noten");
            File take = (File)field(activity,"lastTake");
            try {
                byte[] midi = Files.readAllBytes(take.toPath());
                check(midi.length > 32 && midi[0]=='M' && midi[1]=='T' && midi[2]=='h' && midi[3]=='d', "Valid MIDI export");
                Files.write(new File(outputDirectory(), "rotation-take.mid").toPath(), midi);
            } catch (Exception e) { throw new RuntimeException(e); }
            check(stopped == 0 && destroyed == 0, "No lifecycle teardown during any rotation");
        });
        main(() -> click("▶ Aufnahme abspielen"));
        await(() -> (boolean)field(field(activity,"player"),"playing"), "GM pattern playback starts");
        Object player = field(activity,"player");
        rotate(1); rotate(0);
        main(() -> {
            check(field(activity,"player")==player && (boolean)field(player,"playing"), "Playback continues through rotation");
            check(field(activity,"audio")==recorder && recorder.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING, "Microphone continues during playback rotation");
            click("■ Wiedergabe stoppen");
            check(!(boolean)field(player,"playing"), "Playback stop works");
            check((boolean)field(activity,"gmLive") && (boolean)field(field(activity,"gm"),"ready"), "Pattern stop keeps direct GM alive");
            check((int)field(field(activity,"gm"),"generation")==gmGeneration, "GM engine was not restarted");
            check(stopped==0 && destroyed==0, "Playback rotation has no lifecycle teardown");
        });
        main(() -> {
            View input=(View)field(activity,"midiInputBody");
            input.requestRectangleOnScreen(new Rect(0,0,input.getWidth(),input.getHeight()),true);
        });
        waitForIdleSync(); SystemClock.sleep(200); screenshot("manual-inputs");
        main(() -> {
            View button=(View)field(activity,"playbackButton");
            button.requestRectangleOnScreen(new Rect(0,0,button.getWidth(),button.getHeight()),true);
        });
        waitForIdleSync(); SystemClock.sleep(200); screenshot("pattern-player");
    }

    private void verifySession(boolean controlsHeld) {
        check(stopped == 0 && destroyed == 0 && !activity.isDestroyed(), "Activity must stay alive");
        for (Map.Entry<String,Object> entry : session.entrySet()) {
            if (entry.getKey().equals("surface") && !controlsHeld) continue;
            Object current = field(activity,entry.getKey());
            boolean same = entry.getValue() instanceof Number ? entry.getValue().equals(current) : entry.getValue() == current;
            check(same, "Session replaced: " + entry.getKey());
        }
        check((boolean)field(activity,"recording") && (boolean)field(activity,"capturing"), "Recording and capture remain active");
        check(((Thread)field(activity,"capture")).isAlive(), "Capture thread alive");
        check(((AudioRecord)field(activity,"audio")).getRecordingState() == AudioRecord.RECORDSTATE_RECORDING, "AudioRecord remains recording");
        check((boolean)field(activity,"usbSelected") && (boolean)field(field(activity,"usb"),"enabled"), "USB remains selected/enabled");
        check(((Number)field(field(activity,"usb"),"generation")).longValue() == usbGeneration, "USB must not reconnect");
        check((boolean)field(activity,"gmLive") && (boolean)field(field(activity,"gm"),"ready"), "Direct GM remains active");
        check((int)field(field(activity,"gm"),"generation")==gmGeneration, "GM generation unchanged");
        check(((float[])field(activity,"guitarParameters"))[8] == 432, "Classic parameters retained");
        if (controlsHeld) {
            check((boolean)field(activity,"sustain") && (int)field(activity,"modulation") == 37, "Controllers retained");
            check((int)field(activity,"octave") == 5 && (int)field(activity,"midiInput") == 1, "Keys settings retained");
        }
    }
    private void rotate(int rotation) {
        check(getUiAutomation().setRotation(rotation), "Rotation accepted");
        int expected = rotation % 2 == 0 ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        await(() -> activity.getResources().getConfiguration().orientation == expected, "Orientation change");
        SystemClock.sleep(350);
        waitForIdleSync();
    }
    private long frames(AudioRecord recorder) {
        AudioTimestamp timestamp = new AudioTimestamp();
        await(() -> recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS, "Audio timestamp");
        return timestamp.framePosition;
    }
    private void tapSurface() {
        long now = SystemClock.uptimeMillis();
        main(() -> {
            View surface = (View)field(activity,"surface");
            check(surface.getWidth() > 0 && surface.getHeight() > 0, "Instrument layout usable");
            MotionEvent down = MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,surface.getWidth()*.1f,surface.getHeight()*.85f,0);
            surface.dispatchTouchEvent(down);
            down.recycle();
        });
        SystemClock.sleep(80);
        main(() -> {
            View surface = (View)field(activity,"surface");
            MotionEvent up = MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,surface.getWidth()*.1f,surface.getHeight()*.85f,0);
            surface.dispatchTouchEvent(up);
            up.recycle(); taps++;
        });
    }
    private File outputDirectory() { File dir = new File(getTargetContext().getExternalFilesDir(null),"rotation-test"); dir.mkdirs(); return dir; }
    private void screenshot(String name) throws Exception {
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image != null) try (FileOutputStream out = new FileOutputStream(new File(outputDirectory(),name+".png"))) { image.compress(Bitmap.CompressFormat.PNG,100,out); image.recycle(); }
    }
    private void click(String label) { View view = text(activity.getWindow().getDecorView(),label); check(view != null,"Missing control: "+label); view.performClick(); }
    private static View text(View view,String wanted) {
        if (view instanceof TextView text && wanted.contentEquals(text.getText())) return view;
        if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) { View found=text(group.getChildAt(i),wanted); if(found!=null) return found; }
        return null;
    }
    private static View description(View view,String wanted) {
        if (wanted.contentEquals(view.getContentDescription()==null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) { View found=description(group.getChildAt(i),wanted); if(found!=null) return found; }
        return null;
    }
    private void exerciseVocalRanges() {
        Object originalAudio = field(activity,"audio");
        jsTrue("(window.__voiceBackup=JSON.parse(JSON.stringify({...state,current:[...values],selected,draftName:$('#presetName').value})),window.__voiceValues=[...values],window.__voiceBank=JSON.stringify(state.presets),true)");
        String[] keys={"bass","baritone","tenor","alto","mezzo","soprano"};
        int[] lows={40,43,47,53,57,60}, highs={60,64,67,74,77,81};
        for(int i=0;i<keys.length;i++) {
            int low=lows[i], high=highs[i];
            jsTrue("($('#voiceRange').value='"+keys[i]+"',$('#applyVoice').click(),values[0]===0&&values[5]==="+low+"&&values[6]==="+high+"&&selected===0&&JSON.stringify(state.presets)===window.__voiceBank&&values.every((v,i)=>[0,5,6].includes(i)||v===window.__voiceValues[i]))");
            await(() -> {
                float[] p=(float[])field(activity,"guitarParameters");
                return p[0]==0 && p[5]==low && p[6]==high;
            }, "Vocal preset reaches live native parameter path: "+keys[i]);
        }
        jsTrue("(gesture(5,61),gesture(6,82),$('#presetName').value='Voice range regression',bank('save'),window.__voiceSaved={...state,current:[...values],selected,draftName:$('#presetName').value},configureClassic(window.__voiceSaved),values[5]===61&&values[6]===82&&values[0]===0&&state.presets.some(p=>p.name==='Voice range regression'&&p.values[5]===61&&p.values[6]===82))");
        jsTrue("(configureClassic(window.__voiceBackup),JSON.stringify(state.presets)===window.__voiceBank&&values.every((v,i)=>v===window.__voiceValues[i]))");
        main(() -> check(field(activity,"audio")==originalAudio,"Vocal presets do not restart microphone"));
    }

    private void jsTrue(String expression) {
        AtomicReference<String> value=new AtomicReference<>();
        main(() -> ((WebView)field(activity,"classic")).evaluateJavascript("(()=>{try{return !!("+expression+")}catch(e){return String(e)}})()",value::set));
        await(() -> value.get()!=null,"Vocal preset JavaScript callback");
        check("true".equals(value.get()),"Vocal preset assertion: "+value.get());
    }

    private void main(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch(Throwable t) { error.set(t); } });
        if(error.get()!=null) throw new AssertionError(error.get());
    }
    private void await(BooleanSupplier ready,String description) {
        long end=SystemClock.uptimeMillis()+10_000;
        while(SystemClock.uptimeMillis()<end) { AtomicReference<Boolean> result=new AtomicReference<>(false); main(() -> result.set(ready.getAsBoolean())); if(result.get()) return; SystemClock.sleep(40); }
        throw new AssertionError("Timeout: "+description);
    }
    private static Object field(Object target,String name) {
        try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    private static void set(Object target,String name,Object value) {
        try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    private static Object call(Object target,String name) { return call(target,name,new Class<?>[0]); }
    private static Object call(Object target,String name,Class<?>[] types,Object... args) {
        try { Method m=target.getClass().getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(target,args); }
        catch(Exception e) { throw new RuntimeException(e); }
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
