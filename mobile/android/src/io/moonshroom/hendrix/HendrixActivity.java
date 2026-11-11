package io.moonshroom.hendrix;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HendrixActivity extends Activity implements InstrumentView.Play {
    private static final int MIC = 1, BLE = 2, SAVE = 3;
    private static final int GUITAR = 0, VOICE = 1;
    private static final int FRETBOARD = 0, KEYS = 1;
    private static final int[] AUDIO_BLOCK_SIZES = {16, 32, 64, 128, 256, 512, 1024};
    private static final int DEFAULT_AUDIO_BLOCK = 256;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long[] gates = new long[128];
    private long gateID;
    private NativeHub nativeHub;
    private long hub;
    private volatile BleMidiService bluetooth;
    private boolean bluetoothBound;
    private UsbMidi usb;
    private HandlerThread usbThread;
    private Handler usbWorker;
    private volatile boolean usbSelected;
    private boolean bluetoothRequested;
    private List<UsbMidi.Destination> usbDestinations = List.of();
    private String usbMessage = "USB-MIDI aus";
    private TextView usbStatus;
    private Button usbButton;
    private AudioManager audioManager;
    private AudioRecord audio;
    private Thread capture;
    private volatile boolean capturing;
    private long queue;
    private int routed = -1;
    private boolean recording, chordMode, tab, showScore, poly;
    private boolean sustain;
    private int modulation;
    private int page, fret, octave = 4, chord, tuning = 440;
    private int midiInput = FRETBOARD;
    private boolean midiInputExpanded;
    private int inputID = -1;
    private final ArrayList<AudioDeviceInfo> inputs = new ArrayList<>();
    private String btStatus = "Bluetooth-MIDI aus", status = "";
    private File lastTake, pendingSave;
    private long[] notes = new long[0];
    private LinearLayout content;
    private LinearLayout midiControls;
    private LinearLayout midiInputBody;
    private Button midiVisibilityButton;
    private Button fretboardButton, keysButton;
    private TextView message, tuner, audioStatus, bluetoothStatus;
    private Button bluetoothButton, recordButton, listenButton;
    private Spinner inputSpinner;
    private InstrumentView surface;
    private ClassicGuitarPanel classic;
    private ClassicGuitarPanel studio;
    private float[] guitarParameters;
    private long lastUIUpdate;
    private GeneralMidiSynth gm;
    private MidiPatternPlayer player;
    private final ExecutorService patternLoader = Executors.newSingleThreadExecutor();
    private volatile boolean gmLive;
    private boolean playPending, localPlayback;
    private volatile int audioBlockFrames = DEFAULT_AUDIO_BLOCK;
    private volatile long audioProcessedFrames;
    private TextView audioTiming;
    private int gmProgram=24, patternProgram, patternDestination, playGeneration;
    private float gmVolume=.65f;
    private Button playbackButton;
    private TextView playbackStatus;
    private LinearLayout patternSoundOptions;
    private Switch gmSwitch;
    private long patternDuration;

    private long now() { return SystemClock.elapsedRealtime(); }

    private final ServiceConnection bluetoothConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            bluetooth = ((BleMidiService.Connection) binder).service();
            if (usbSelected) bluetooth.stopMidi();
            bluetooth.observe(HendrixActivity.this::showBluetoothStatus);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bluetooth = null;
            showBluetoothStatus("Bluetooth-MIDI aus");
        }
    };

    private boolean bluetoothEnabled() { return bluetooth != null && bluetooth.enabled(); }

    private void showBluetoothStatus(String value) {
        btStatus = value;
        if (bluetoothStatus != null) bluetoothStatus.setText(value);
        if (bluetoothButton != null) bluetoothButton.setText(bluetoothEnabled() ? "BLE MIDI · AN" : "BLE MIDI");
        if (player != null && !usbSelected && !localPlayback && (playPending || player.playing()) &&
                (bluetooth == null || !bluetooth.connected())) {
            stopPlayback(); showStatus("Bluetooth-Verbindung verloren · Wiedergabe gestoppt");
        }
    }

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { refreshInputs(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { refreshInputs(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audioManager = getSystemService(AudioManager.class);
        android.content.SharedPreferences response = getSharedPreferences("audio-response", MODE_PRIVATE);
        int savedBlock = response.getInt("block", DEFAULT_AUDIO_BLOCK);
        audioBlockFrames = Arrays.binarySearch(AUDIO_BLOCK_SIZES, savedBlock) >= 0 ? savedBlock : DEFAULT_AUDIO_BLOCK;
        android.content.SharedPreferences sound = getSharedPreferences("gm", MODE_PRIVATE);
        gmProgram = Math.max(0, Math.min(127, sound.getInt("liveProgram", 24)));
        patternProgram = Math.max(0, Math.min(127, sound.getInt("patternProgram", 0)));
        gmVolume = Math.max(0, Math.min(1, sound.getFloat("volume", .65f)));
        gm = new GeneralMidiSynth(this, handler, error -> {
            gmLive = false;
            stopPlayback();
            if (gmSwitch != null) gmSwitch.setChecked(false);
            showStatus(error);
        });
        gm.setProgram(0, gmProgram); gm.setProgram(1, patternProgram); gm.setVolume(gmVolume);
        player = new MidiPatternPlayer(handler, playing -> {
            if (playing) playPending = false;
            else { localPlayback = false; gm.silence(1); }
            updatePlayback();
            updateSynth();
        });
        midiInputExpanded = getSharedPreferences("midi-ui", MODE_PRIVATE).getBoolean("expanded", false);
        usbSelected = getSharedPreferences("midi-output", MODE_PRIVATE).getBoolean("usb", false);
        usbThread = new HandlerThread("Hendrix-USB-MIDI");
        usbThread.start();
        usbWorker = new Handler(usbThread.getLooper());
        usb = new UsbMidi(this, usbWorker, handler, value -> {
            if (isDestroyed()) return;
            usbMessage = value;
            if (usbStatus != null) usbStatus.setText(value);
            if (usbSelected && !localPlayback && (playPending || player.playing()) && !usb.connected()) {
                stopPlayback(); showStatus("USB-Verbindung verloren · Wiedergabe gestoppt");
            }
        }, choices -> usbDestinations = choices);
        nativeHub = new NativeHub((sample, status, a, b) -> {
            if (gmLive) gm.send(0, status, a, b);
            if (usbSelected) usb.send(status, a, b);
            else if (bluetooth != null) bluetooth.send(sample, status, a, b);
        });
        hub = nativeHub.create();
        if (hub == 0) { finish(); return; }
        restoreTake();
        render();
        audioManager.registerAudioDeviceCallback(devices, handler);
    }

    @Override protected void onStart() {
        super.onStart();
        updateSynth();
        if (hub != 0 && usbSelected) usb.start();
        updateScreenAwake();
        if (hub != 0)
            bluetoothBound = bindService(new Intent(this, BleMidiService.class), bluetoothConnection, BIND_AUTO_CREATE);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // Keep the existing session and views: render() would detach the
        // instruments, and recreation would stop audio/recording/USB in onStop.
        // Width-based layouts and the WebViews' ResizeObservers reflow in place.
        getWindow().getDecorView().requestApplyInsets();
        if (content != null) content.requestLayout();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(19, 20, 23));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(18));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);

        LinearLayout header = row(content);
        TextView title = label("HENDRIX", 27);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(label("0.1.12", 12));
        LinearLayout outputs = row(content);
        usbButton = button(usbSelected ? "USB MIDI · AN" : "USB MIDI", this::toggleUsb);
        bluetoothButton = button(bluetoothEnabled() ? "BLE MIDI · AN" : "BLE MIDI", this::toggleBluetooth);
        outputs.addView(usbButton, new LinearLayout.LayoutParams(0, -2, 1));
        outputs.addView(bluetoothButton, new LinearLayout.LayoutParams(0, -2, 1));
        usbStatus = label(usbMessage, 12);
        content.addView(usbStatus);
        bluetoothStatus = label(btStatus, 12);
        content.addView(bluetoothStatus);
        LinearLayout connectionHelp = row(content);
        TextView usbHelp = label("USB-Hilfe / Ziel", 11);
        usbHelp.setPadding(0, dp(8), 0, dp(8));
        usbHelp.setTextColor(InstrumentView.GOLD);
        usbHelp.setOnClickListener(view -> showUsbHelp());
        connectionHelp.addView(usbHelp, new LinearLayout.LayoutParams(0, -2, 1));
        TextView bluetoothDetails = label("Bluetooth-Details", 11);
        bluetoothDetails.setPadding(0, dp(8), 0, dp(8));
        bluetoothDetails.setTextColor(InstrumentView.GOLD);
        bluetoothDetails.setOnClickListener(view -> showBluetoothDetails());
        connectionHelp.addView(bluetoothDetails, new LinearLayout.LayoutParams(0, -2, 1));
        // Voice detection stays available in the implementation while its
        // quality is being improved; the released UI exposes guitar only.

        tuner = null;
        inputSpinner = null;
        listenButton = null;
        if (page == GUITAR) renderAudioInput();
        else renderVoice();
        renderRecording();
        if (page == GUITAR) {
            if (classic == null) {
                classic = new ClassicGuitarPanel(this, handler, ClassicGuitarPanel.Part.CONTROLS, parameters -> {
                    guitarParameters = parameters;
                    gm.setBendRange(parameters[14] >= 1.5f ? 24 : parameters[14] >= .5f ? 12 : 2);
                    if (page == GUITAR && audio != null) nativeHub.guitarParameters(hub, parameters);
                });
                guitarParameters = classic.savedParameters();
                if (guitarParameters != null) gm.setBendRange(guitarParameters[14] >= 1.5f ? 24 : guitarParameters[14] >= .5f ? 12 : 2);
            }
            classic.attach(content);
            renderMidiInput();
            renderAudioResponse();
            renderGeneralMidi();
            if (lastTake != null) renderPatternPlayer();
            if (studio == null) studio = new ClassicGuitarPanel(this, handler, ClassicGuitarPanel.Part.STUDIO, null);
            studio.attach(content);
        }
    }

    private void switchPage(int next) {
        if (page == next) return;
        if (surface != null) surface.release();
        resetControls();
        if (audioPage()) stopAudio();
        page = next;
        render();
    }

    private void renderMidiInput() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.rgb(28, 29, 33));
        background.setCornerRadius(dp(10));
        panel.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(12), 0, dp(12));
        content.addView(panel, params);

        midiVisibilityButton = button(midiInputExpanded ? "MIDI-Eingabe ausblenden" : "MIDI-Eingabe einblenden", this::toggleMidiInput);
        panel.addView(midiVisibilityButton, new LinearLayout.LayoutParams(-1, -2));
        midiInputBody = new LinearLayout(this);
        midiInputBody.setOrientation(LinearLayout.VERTICAL);
        panel.addView(midiInputBody, new LinearLayout.LayoutParams(-1, -2));
        midiInputBody.setVisibility(midiInputExpanded ? View.VISIBLE : View.GONE);
        LinearLayout selector = row(midiInputBody);
        fretboardButton = button("Griffbrett", () -> selectMidiInput(FRETBOARD));
        keysButton = button("Keys", () -> selectMidiInput(KEYS));
        selector.addView(fretboardButton, new LinearLayout.LayoutParams(0, -2, 1));
        selector.addView(keysButton, new LinearLayout.LayoutParams(0, -2, 1));
        midiControls = new LinearLayout(this);
        midiControls.setOrientation(LinearLayout.VERTICAL);
        midiInputBody.addView(midiControls, new LinearLayout.LayoutParams(-1, -2));
        if (midiInputExpanded) renderMidiControls();
    }

    private void toggleMidiInput() {
        if (midiInputExpanded) {
            if (surface != null) surface.release();
            resetControls();
        }
        midiInputExpanded = !midiInputExpanded;
        getSharedPreferences("midi-ui", MODE_PRIVATE).edit().putBoolean("expanded", midiInputExpanded).apply();
        midiInputBody.setVisibility(midiInputExpanded ? View.VISIBLE : View.GONE);
        midiVisibilityButton.setText(midiInputExpanded ? "MIDI-Eingabe ausblenden" : "MIDI-Eingabe einblenden");
        if (midiInputExpanded) renderMidiControls();
    }

    private void selectMidiInput(int next) {
        if (midiInput == next) return;
        resetControls();
        midiInput = next;
        renderMidiControls();
    }

    private void renderMidiControls() {
        midiControls.removeAllViews();
        fretboardButton.setTextColor(midiInput == FRETBOARD ? InstrumentView.GOLD : Color.WHITE);
        keysButton.setTextColor(midiInput == KEYS ? InstrumentView.GOLD : Color.WHITE);
        fretboardButton.setSelected(midiInput == FRETBOARD);
        keysButton.setSelected(midiInput == KEYS);
        if (midiInput == FRETBOARD) renderFretboard();
        else renderKeys();
    }

    private void renderFretboard() {
        LinearLayout modes = row(midiControls);
        Button solo = button("Solo", () -> { chordMode = false; renderMidiControls(); });
        Button chords = button("Akkorde", () -> { chordMode = true; renderMidiControls(); });
        (chordMode ? chords : solo).setTextColor(InstrumentView.GOLD);
        modes.addView(solo, new LinearLayout.LayoutParams(0, -2, 1));
        modes.addView(chords, new LinearLayout.LayoutParams(0, -2, 1));
        if (chordMode) {
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            LinearLayout row = new LinearLayout(this);
            for (int i = 0; i < InstrumentView.CHORD_NAMES.length; i++) {
                final int selected = i;
                Button button = button(InstrumentView.CHORD_NAMES[i], () -> { chord = selected; renderMidiControls(); });
                if (chord == i) button.setTextColor(InstrumentView.GOLD);
                row.addView(button);
            }
            scroll.addView(row);
            midiControls.addView(scroll);
        } else {
            stepper(midiControls, "Bünde " + fret + "–" + (fret + 4),
                    () -> { fret = Math.max(0, fret - 5); renderMidiControls(); },
                    () -> { fret = Math.min(20, fret + 5); renderMidiControls(); });
        }
        surface = new InstrumentView(this, this, chordMode ? InstrumentView.CHORD : InstrumentView.SOLO, chordMode ? chord : fret);
        midiControls.addView(surface, new LinearLayout.LayoutParams(-1, dp(252)));
        midiControls.addView(label(chordMode ? "Streiche über die Saiten oder zupfe einzelne an." : "Berühre einen Bund. Ziehe zum nächsten Ton.", 12));
    }

    private void renderKeys() {
        stepper(midiControls, "Oktave " + octave, () -> { octave = Math.max(1, octave - 1); renderMidiControls(); },
                () -> { octave = Math.min(7, octave + 1); renderMidiControls(); });
        surface = new InstrumentView(this, this, InstrumentView.KEYS, octave);
        midiControls.addView(surface, new LinearLayout.LayoutParams(-1, dp(240)));
        Switch pedal = new Switch(this);
        pedal.setText("Sustain");
        pedal.setChecked(sustain);
        pedal.setOnCheckedChangeListener((view, checked) -> {
            sustain = checked;
            nativeHub.control(hub, 64, checked ? 127 : 0, now());
        });
        midiControls.addView(pedal);
        LinearLayout row = row(midiControls);
        row.addView(label("Modulation", 13));
        SeekBar wheel = new SeekBar(this);
        wheel.setMax(127);
        wheel.setProgress(modulation);
        wheel.setContentDescription("Modulation");
        wheel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                modulation = value;
                nativeHub.control(hub, 1, value, now());
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        row.addView(wheel, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private boolean audioPage() { return page == GUITAR || page == VOICE; }

    private void renderAudioInput() {
        surface = null;
        LinearLayout row = row(content);
        inputSpinner = new Spinner(this);
        inputSpinner.setEnabled(audio == null);
        row.addView(inputSpinner, new LinearLayout.LayoutParams(0, -2, 1));
        listenButton = button(audio == null ? "Eingang starten" : "Stoppen", this::toggleAudio);
        row.addView(listenButton);
        refreshInputs();
        audioStatus = label(audio == null ? "" : "Audioeingang aktiv", 12);
        content.addView(audioStatus);
    }

    private void renderVoice() {
        renderAudioInput();
        Button detection = button(poly ? "Erkennung · Akkorde" : "Erkennung · Gesang / Solo", () -> { poly = !poly; render(); });
        detection.setEnabled(audio == null);
        content.addView(detection);
        tuner = label("–", 38);
        tuner.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tuner.setTypeface(Typeface.MONOSPACE);
        tuner.setPadding(0, dp(24), 0, dp(24));
        content.addView(tuner);
        LinearLayout references = row(content);
        for (int hz : new int[]{432, 440}) {
            Button button = button(hz + " Hz", () -> {
                tuning = hz;
                nativeHub.setReference(hub, hz);
                render();
            });
            if (tuning == hz) button.setTextColor(InstrumentView.GOLD);
            references.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private Spinner instrumentPicker(int selected, java.util.function.IntConsumer changed) {
        Spinner picker = new Spinner(this);
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, GeneralMidiPrograms.labels()));
        picker.setSelection(selected);
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { changed.accept(position); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        return picker;
    }

    private void renderAudioResponse() {
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setVisibility(View.GONE);
        Button toggle = button("Audio-Latenz", () -> options.setVisibility(options.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        content.addView(toggle);
        content.addView(options, new LinearLayout.LayoutParams(-1, -2));
        options.addView(label("Eingangs-Puffer · Leseblock", 13));
        Spinner blocks = new Spinner(this);
        blocks.setContentDescription("Eingangs-Leseblock");
        String[] choices = new String[AUDIO_BLOCK_SIZES.length];
        for (int i = 0; i < choices.length; i++) {
            int frames = AUDIO_BLOCK_SIZES[i];
            choices[i] = frames + " Samples" + (frames == DEFAULT_AUDIO_BLOCK ? " · Standard" : "");
        }
        blocks.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        blocks.setSelection(Arrays.binarySearch(AUDIO_BLOCK_SIZES, audioBlockFrames));
        blocks.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                audioBlockFrames = AUDIO_BLOCK_SIZES[position];
                getSharedPreferences("audio-response", MODE_PRIVATE).edit().putInt("block", audioBlockFrames).apply();
                updateAudioTiming();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        options.addView(blocks);
        audioTiming = label("", 11); options.addView(audioTiming); updateAudioTiming();
        options.addView(label("Wirkt beim nächsten Leseblock. Kleiner reagiert schneller, kann aber mehr Rechenlast verursachen. Android-Puffer, Tonerkennung und der Synth am Empfänger kommen hinzu.", 11));

    }

    private void updateAudioTiming() {
        if (audioTiming == null) return;
        int rate = audio == null ? 48000 : audio.getSampleRate();
        String text = String.format(Locale.GERMAN, "%d Samples ≈ %.2f ms bei %d Hz", audioBlockFrames, audioBlockFrames * 1000.0 / rate, rate);
        if (audio != null) text += " · Android-Puffer: " + audio.getBufferSizeInFrames() + " Samples";
        else text += " (Zielrate)";
        audioTiming.setText(text);
    }

    private void renderGeneralMidi() {
        gmSwitch = new Switch(this);
        gmSwitch.setText("Direktklang · General MIDI");
        gmSwitch.setChecked(gmLive);
        gmSwitch.setOnCheckedChangeListener((view, checked) -> {
            gmLive = checked;
            if (!checked) gm.silence(0);
            updateSynth();
            showStatus(checked ? "Direktklang an · für Gitarre Eingang starten und MIDI senden aktivieren" : "Direktklang aus");
        });
        content.addView(gmSwitch);
        content.addView(instrumentPicker(gmProgram, value -> {
            if (gmProgram == value) return;
            gmProgram = value; gm.setProgram(0, value);
            getSharedPreferences("gm", MODE_PRIVATE).edit().putInt("liveProgram", value).apply();
        }), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout volumeRow = row(content);
        volumeRow.addView(label("Handy-Lautstärke", 12));
        SeekBar volume = new SeekBar(this);
        volume.setMax(100); volume.setProgress(Math.round(gmVolume * 100));
        volume.setContentDescription("GM-Lautstärke");
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                gmVolume = progress / 100f; gm.setVolume(gmVolume);
                getSharedPreferences("gm", MODE_PRIVATE).edit().putFloat("volume", gmVolume).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        volumeRow.addView(volume, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(label("Gitarre, Keys und Griffbrett → Handy-Lautsprecher. Bei Mikrofonbetrieb leise beginnen: Rückkopplungen sind möglich.", 11));
        TextView licenses = label("Klänge & Lizenzen", 11);
        licenses.setTextColor(InstrumentView.GOLD);
        licenses.setOnClickListener(view -> {
            StringBuilder notices = new StringBuilder("General MIDI: TimGM6mb 1.3 · Tim Brechbill / David Bolton\nKlangerzeugung: TinySoundFont · Bernhard Schelling / Steve Folta\n\n");
            try {
                for (String file : new String[]{"TimGM6mb-copyright.txt", "TimGM6mb-LICENSE.txt", "TinySoundFont-LICENSE.txt"}) {
                    try (java.io.InputStream input = getAssets().open("synth/" + file)) {
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                        notices.append(bytes.toString("UTF-8")).append("\n\n");
                    }
                }
            } catch (IOException ignored) {}
            TextView text = label(notices.toString(), 12); text.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(this); scroll.setPadding(dp(16), dp(8), dp(16), dp(8)); scroll.addView(text);
            new AlertDialog.Builder(this).setTitle("Klänge & Lizenzen").setView(scroll).setPositiveButton("Schließen", null).show();
        });
        content.addView(licenses);
    }

    private void updateSynth() {
        if (gm == null) return;
        if (gmLive || (localPlayback && (playPending || (player != null && player.playing())))) gm.start(null);
        else gm.stop();
    }

    private void renderPatternPlayer() {
        content.addView(label("Pattern-Player · letzte Aufnahme", 15));
        Spinner destination = new Spinner(this);
        destination.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Handy-Lautsprecher · General MIDI", "MIDI senden · USB / Bluetooth"}));
        destination.setSelection(patternDestination);
        content.addView(destination, new LinearLayout.LayoutParams(-1, -2));
        patternSoundOptions = new LinearLayout(this);
        patternSoundOptions.setOrientation(LinearLayout.VERTICAL);
        patternSoundOptions.addView(instrumentPicker(patternProgram, value -> {
            if (patternProgram == value) return;
            patternProgram = value; gm.setProgram(1, value);
            getSharedPreferences("gm", MODE_PRIVATE).edit().putInt("patternProgram", value).apply();
        }), new LinearLayout.LayoutParams(-1, -2));
        content.addView(patternSoundOptions, new LinearLayout.LayoutParams(-1, -2));
        patternSoundOptions.setVisibility(patternDestination == 0 ? View.VISIBLE : View.GONE);
        destination.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (patternDestination == position) return;
                stopPlayback(); patternDestination = position;
                patternSoundOptions.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        playbackButton = button("▶ Aufnahme abspielen", this::togglePlayback);
        content.addView(playbackButton, new LinearLayout.LayoutParams(-1, -2));
        playbackStatus = label("", 12); content.addView(playbackStatus);
        updatePlayback();
    }

    private void togglePlayback() {
        if (playPending || player.playing()) { stopPlayback(); return; }
        if (lastTake == null || recording) return;
        final boolean local = patternDestination == 0;
        final MidiPatternPlayer.Output output;
        if (local) output = (status, a, b) -> gm.send(1, status, a, b);
        else if (usbSelected) {
            if (!usb.connected()) { showStatus("Zuerst USB-MIDI verbinden."); return; }
            output = usb::send;
        } else {
            if (!bluetoothEnabled() || !bluetooth.connected()) { showStatus("Zuerst BLE MIDI einschalten und mit dem Empfänger verbinden."); return; }
            BleMidiService target = bluetooth;
            output = (status, a, b) -> target.send(now() * 48, status, a, b);
        }
        int token = ++playGeneration;
        playPending = true; localPlayback = local;
        updatePlayback(); updateSynth();
        File take = lastTake;
        patternLoader.execute(() -> {
            try {
                if (take.length() > 16_000_000) throw new IOException("MIDI-Aufnahme zu groß");
                MidiPattern pattern = MidiPattern.parse(Files.readAllBytes(take.toPath()));
                handler.post(() -> {
                    if (isDestroyed() || token != playGeneration) return;
                    patternDuration = (pattern.durationMicros + 999) / 1000;
                    Runnable begin = () -> {
                        if (isDestroyed() || token != playGeneration) return;
                        player.play(pattern, output);
                    };
                    if (local) gm.start(begin); else begin.run();
                });
            } catch (IOException | RuntimeException error) {
                handler.post(() -> {
                    if (isDestroyed() || token != playGeneration) return;
                    stopPlayback(); showStatus("Wiedergabe fehlgeschlagen: " + error.getLocalizedMessage());
                });
            }
        });
    }

    private final Runnable playbackProgress = new Runnable() {
        @Override public void run() {
            if (!player.playing()) return;
            if (playbackStatus != null) playbackStatus.setText(timeLabel(player.positionMillis()) + " / " + timeLabel(patternDuration));
            handler.postDelayed(this, 200);
        }
    };

    private static String timeLabel(long millis) { long seconds = millis / 1000; return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60); }

    private void updatePlayback() {
        handler.removeCallbacks(playbackProgress);
        if (playbackButton != null) {
            playbackButton.setText(playPending || player.playing() ? "■ Wiedergabe stoppen" : "▶ Aufnahme abspielen");
            playbackButton.setEnabled(!recording);
        }
        if (playbackStatus != null) playbackStatus.setText(playPending ? "Aufnahme wird geladen …" : recording ? "Aufnahme zuerst stoppen" : "Bereit");
        if (player.playing()) handler.post(playbackProgress);
    }

    private void stopPlayback() {
        ++playGeneration; playPending = false;
        if (player != null) player.stop();
        localPlayback = false;
        if (gm != null) gm.silence(1);
        updatePlayback(); updateSynth();
    }

    private void renderRecording() {
        LinearLayout transport = row(content);
        recordButton = button(recording ? "■ Stopp" : "● Aufnahme", this::toggleRecording);
        recordButton.setTextColor(Color.rgb(255, 125, 125));
        transport.addView(recordButton, new LinearLayout.LayoutParams(0, -2, 1));
        if (lastTake != null) {
            transport.addView(button("Teilen", this::share));
            transport.addView(button("Export", this::export));
        }
        message = label(status, 13);
        content.addView(message);
        if (lastTake != null) {
            content.addView(button(showScore ? "Aufnahme ausblenden" : "Aufnahme ansehen", () -> { showScore = !showScore; render(); }));
            if (showScore) {
                LinearLayout row = row(content);
                row.addView(button("Noten", () -> { tab = false; render(); }));
                row.addView(button("Tabulatur", () -> { tab = true; render(); }));
                if (notes.length == 0) content.addView(label("Diese Aufnahme enthält keine Noten.", 13));
                else {
                    HorizontalScrollView horizontal = new HorizontalScrollView(this);
                    horizontal.addView(new ScoreView(this, notes, tab), new HorizontalScrollView.LayoutParams(-2, -2));
                    content.addView(horizontal);
                    content.addView(label(tab ? "Standardstimmung · Vorschläge für Einzeltöne" : "Unquantisierte Notenübersicht", 12));
                }
            }
        }
    }

    private void stepper(LinearLayout parent, String text, Runnable minus, Runnable plus) {
        LinearLayout row = row(parent);
        row.addView(button("−", minus));
        TextView label = label(text, 15);
        label.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("+", plus));
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.WHITE);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }
    private Button button(String text, Runnable callback) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false);
        button.setOnClickListener(view -> callback.run());
        return button;
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void showStatus(String text) {
        status = text;
        if (message != null) message.setText(text);
    }

    private void toggleBluetooth() {
        stopPlayback();
        bluetoothRequested = false;
        if (bluetoothEnabled()) { bluetooth.stopMidi(); return; }
        releaseForOutputChange();
        selectUsb(false);
        bluetoothRequested = true;
        String[] needed = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE};
        if (checkSelfPermission(needed[0]) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(needed[1]) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(needed, BLE);
        } else startBluetooth();
    }

    private void releaseForOutputChange() {
        if (surface != null) surface.release();
        release();
        resetControls();
        if (hub != 0) nativeHub.resetAudio(hub, now());
    }

    private void selectUsb(boolean selected) {
        usbSelected = selected;
        getSharedPreferences("midi-output", MODE_PRIVATE).edit().putBoolean("usb", selected).apply();
        if (selected) usb.start();
        else { usb.stop(); usbDestinations = List.of(); }
        if (usbButton != null) usbButton.setText(selected ? "USB MIDI · AN" : "USB MIDI");
        updateScreenAwake();
    }

    private void toggleUsb() {
        stopPlayback();
        bluetoothRequested = false;
        releaseForOutputChange();
        boolean selected = !usbSelected;
        if (selected && bluetooth != null) bluetooth.stopMidi();
        selectUsb(selected);
    }

    private void updateScreenAwake() {
        if (usbSelected || audio != null) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showUsbHelp() {
        List<UsbMidi.Destination> choices = List.copyOf(usbDestinations);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("USB-MIDI zum Computer")
                .setMessage("1. Handy und Computer mit einem USB-Datenkabel verbinden.\n\n" +
                        "2. Am Handy die USB-Benachrichtigung öffnen und unter USB verwenden für → MIDI wählen. " +
                        "Die Auswahl erfolgt in Android.\n\n" +
                        "3. Zurück zu Hendrix, USB MIDI einschalten. In ShroomsLoops MIDI verbinden, " +
                        "MIDI-Eingang der Spur aktivieren und das Handy / Android USB auswählen. " +
                        "Zum Test alle Kanäle empfangen.\n\n" +
                        "Keys und Griffbrett: Kanal 1. Audioerkennung: Kanal 2. " +
                        "Kein zusätzlicher Server am Mac nötig.\n\n" +
                        "Bei aktivem USB MIDI bleibt das Display an. Hendrix beim Spielen geöffnet lassen. " +
                        "Bluetooth und USB werden einzeln verwendet.\n\n" + usbMessage)
                .setPositiveButton("Schließen", null);
        if (!choices.isEmpty()) dialog.setNeutralButton("USB-Ziel wählen", (d, w) -> {
            String[] names = choices.stream().map(UsbMidi.Destination::label).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle("USB-Ausgang").setItems(names, (picker, index) -> {
                stopPlayback();
                releaseForOutputChange();
                usb.select(choices.get(index).key());
            }).setNegativeButton("Abbrechen", null).show();
        });
        dialog.show();
    }

    private void showBluetoothDetails() {
        String details = bluetooth == null ? "Bluetooth-Dienst nicht verbunden." : bluetooth.diagnostics();
        TextView text = label(details, 12);
        text.setTextIsSelectable(true);
        text.setPadding(dp(18), dp(10), dp(18), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("Bluetooth-Details").setView(scroll)
                .setPositiveButton("Schließen", null)
                .setNegativeButton("Neu verbinden", (dialog, which) -> {
                    if (bluetoothEnabled()) bluetooth.restartMidi();
                    else toggleBluetooth();
                })
                .setNeutralButton("Kopieren", (dialog, which) -> {
                    getSystemService(android.content.ClipboardManager.class)
                            .setPrimaryClip(ClipData.newPlainText("Hendrix Bluetooth", details));
                    showStatus("Bluetooth-Details kopiert.");
                }).show();
    }

    private void startBluetooth() {
        try {
            startForegroundService(new Intent(this, BleMidiService.class).setAction(BleMidiService.START));
        } catch (SecurityException | IllegalStateException error) {
            showBluetoothStatus("Bluetooth-MIDI konnte nicht gestartet werden.");
        }
    }

    private void toggleAudio() {
        if (audio != null) { stopAudio(); render(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
        } else startAudio();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        boolean allowed = results.length == permissions.length && results.length > 0;
        for (int value : results) allowed &= value == PackageManager.PERMISSION_GRANTED;
        if (!allowed) { showStatus("Zugriff in den Systemeinstellungen erlauben."); return; }
        if (code == MIC && audioPage()) startAudio();
        if (code == BLE && bluetoothRequested && !usbSelected) startBluetooth();
    }

    private void refreshInputs() {
        if (inputSpinner == null) return;
        inputs.clear();
        inputs.addAll(Arrays.asList(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)));
        inputs.removeIf(device -> !device.isSource() || device.getType() == AudioDeviceInfo.TYPE_TELEPHONY);
        ArrayList<String> labels = new ArrayList<>();
        labels.add("Automatisch");
        int selected = 0;
        for (int i = 0; i < inputs.size(); i++) {
            AudioDeviceInfo device = inputs.get(i);
            labels.add(device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "Eingebautes Mikrofon" : device.getProductName().toString());
            if (device.getId() == inputID) selected = i + 1;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        inputSpinner.setAdapter(adapter);
        inputSpinner.setSelection(selected);
    }

    @SuppressLint("MissingPermission")
    private void startAudio() {
        if (audio != null || !audioPage()) return;
        int selection = inputSpinner.getSelectedItemPosition();
        AudioDeviceInfo preferred = selection > 0 && selection <= inputs.size() ? inputs.get(selection - 1) : null;
        inputID = preferred == null ? -1 : preferred.getId();
        AudioRecord opened = null;
        for (int rate : new int[]{48000, 44100}) {
            int buffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
            if (buffer <= 0) continue;
            try {
                int source = "true".equals(audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))
                        ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;
                opened = new AudioRecord.Builder().setAudioSource(source).setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()).setBufferSizeInBytes(Math.max(buffer, audioBlockFrames * 4 * 2)).build();
                if (opened.getState() == AudioRecord.STATE_INITIALIZED) break;
                opened.release();
                opened = null;
            } catch (RuntimeException ex) {
                if (opened != null) opened.release();
                opened = null;
            }
        }
        if (opened == null) { showStatus("Kein Audioeingang verfügbar."); return; }
        if (preferred != null && !opened.setPreferredDevice(preferred)) {
            opened.release();
            showStatus("Der gewählte Eingang ist nicht verfügbar.");
            return;
        }
        if (!nativeHub.setAudio(hub, opened.getSampleRate(), tuning, poly)) {
            opened.release();
            showStatus("Samplerate nicht unterstützt.");
            return;
        }
        if (page == GUITAR && guitarParameters != null) nativeHub.guitarParameters(hub, guitarParameters);
        long target = nativeHub.createQueue();
        if (target == 0) { opened.release(); showStatus("Audiopuffer nicht verfügbar."); return; }
        try { opened.startRecording(); } catch (RuntimeException ex) {
            nativeHub.destroyQueue(target);
            opened.release();
            showStatus("Audio konnte nicht gestartet werden.");
            return;
        }
        queue = target;
        audio = opened;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        routed = -1;
        capturing = true;
        audioProcessedFrames = 0;
        AudioRecord recorder = opened;
        capture = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            float[] samples = new float[AUDIO_BLOCK_SIZES[AUDIO_BLOCK_SIZES.length - 1]];
            while (capturing) {
                int count = recorder.read(samples, 0, audioBlockFrames, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    if (!nativeHub.push(target, samples, count)) break;
                    // Process immediately on the audio thread, independent of WebView/UI work.
                    if (!nativeHub.drain(target, hub, now())) break;
                    audioProcessedFrames += count;
                } else if (count < 0) break;
            }
            if (capturing) handler.post(() -> {
                if (audio == recorder) {
                    stopAudio();
                    if (recording) finishTake();
                    showStatus("Audio unterbrochen. Bitte Eingang erneut starten.");
                    render();
                }
            });
        }, "Hendrix-Audio");
        capture.start();
        render();
        handler.post(drain);
    }

    private final Runnable drain = new Runnable() {
        @Override public void run() {
            if (audio == null) return;
            long current = now();
            if (current - lastUIUpdate >= 65) {
                lastUIUpdate = current;
                AudioDeviceInfo route = audio.getRoutedDevice();
                if (route != null) {
                    if (routed != -1 && route.getId() != routed) {
                        stopAudio(); showStatus("Audioeingang geändert. Bitte erneut starten."); render(); return;
                    }
                    routed = route.getId();
                    audioStatus.setText(route.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ? "Eingebautes Mikrofon" : route.getProductName());
                }
                if (page == GUITAR && classic != null) classic.updateMetrics(nativeHub.guitarMetrics(hub));
                if (tuner != null) {
                    float[] reading = nativeHub.tuner(hub);
                    tuner.setText(reading == null ? "–" : String.format(Locale.GERMAN, "%s%d\n%.1f Hz   %+.0f Cent",
                            InstrumentView.PITCH[(int)reading[1] % 12], (int)reading[1] / 12 - 1, reading[0], reading[2]));
                    tuner.setTextColor(reading != null && Math.abs(reading[2]) < 5 ? Color.rgb(144, 223, 140) : Color.WHITE);
                }
            }
            handler.postDelayed(this, 12);
        }
    };

    private void stopAudio() {
        if (audio == null) return;
        capturing = false;
        handler.removeCallbacks(drain);
        try { audio.stop(); } catch (IllegalStateException ignored) {}
        try { capture.join(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        nativeHub.drain(queue, hub, now());
        nativeHub.destroyQueue(queue);
        nativeHub.resetAudio(hub, now());
        if (classic != null) classic.updateMetrics(null);
        audio.release();
        audio = null;
        updateScreenAwake();
        capture = null;
        queue = 0;
    }

    @Override public void note(int pitch, int velocity) {
        if (hub != 0) nativeHub.note(hub, pitch, velocity, now());
    }
    @Override public void pluck(int pitch) {
        long id = ++gateID;
        gates[pitch] = id;
        note(pitch, 102);
        handler.postDelayed(() -> {
            if (gates[pitch] == id) { gates[pitch] = 0; note(pitch, 0); }
        }, 430);
    }
    @Override public void release() {
        Arrays.fill(gates, 0);
        if (hub != 0) nativeHub.releaseKeys(hub, now());
    }

    private void resetControls() {
        sustain = false;
        modulation = 0;
        if (hub != 0) {
            nativeHub.control(hub, 64, 0, now());
            nativeHub.control(hub, 1, 0, now());
        }
    }

    private void toggleRecording() {
        if (recording) {
            if (audio != null) nativeHub.drain(queue, hub, now());
            finishTake();
            showScore = true;
            render();
        } else {
            stopPlayback();
            if (audio != null) nativeHub.drain(queue, hub, now());
            recording = nativeHub.startTake(hub, now());
            recordButton.setText(recording ? "■ Stopp" : "● Aufnahme");
            showStatus(recording ? "MIDI-Aufnahme läuft" : "Aufnahme konnte nicht starten.");
            updatePlayback();
        }
    }

    private void finishTake() {
        if (!recording || !nativeHub.stopTake(hub, now())) return;
        recording = false;
        byte[] midi = nativeHub.exportTake(hub);
        if (midi == null) { showStatus("MIDI-Export fehlgeschlagen."); return; }
        File file = new File(getFilesDir(), "Hendrix-" + System.currentTimeMillis() + ".mid");
        File temporary = new File(file.getPath() + ".tmp");
        try {
            Files.write(temporary.toPath(), midi);
            if (!temporary.renameTo(file)) throw new IOException("Datei konnte nicht gespeichert werden.");
            lastTake = file;
            notes = nativeHub.score(hub);
            if (notes == null) notes = new long[0];
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(new File(file.getPath() + ".notes").toPath()))) {
                for (long value : notes) out.writeLong(value);
            }
            showStatus("MIDI gespeichert · " + notes.length / 5 + " Noten");
        } catch (IOException ex) { showStatus(ex.getLocalizedMessage()); }
    }

    private void restoreTake() {
        File[] files = getFilesDir().listFiles(file -> file.getName().matches("Hendrix-[0-9]+\\.mid"));
        if (files == null || files.length == 0) return;
        lastTake = Arrays.stream(files).max(Comparator.comparing(File::getName)).orElse(null);
        File data = new File(lastTake.getPath() + ".notes");
        if (data.length() <= 4_000_000 && data.length() % 40 == 0 && data.isFile()) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(data.toPath()))) {
                long[] restored = new long[(int)data.length() / 8];
                for (int i = 0; i < restored.length; i++) restored[i] = in.readLong();
                notes = restored;
            } catch (IOException ignored) {}
        }
    }

    private void share() {
        if (lastTake == null) return;
        Uri uri = Uri.parse("content://io.moonshroom.hendrix.mobile.takes/" + lastTake.getName());
        Intent intent = new Intent(Intent.ACTION_SEND).setType("audio/midi").putExtra(Intent.EXTRA_STREAM, uri);
        intent.setClipData(ClipData.newRawUri("Hendrix MIDI", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "MIDI teilen"));
    }

    private void export() {
        if (lastTake == null) return;
        pendingSave = lastTake;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("audio/midi")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, pendingSave.getName());
        startActivityForResult(intent, SAVE);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != SAVE || result != RESULT_OK || data == null || pendingSave == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IOException("Exportziel nicht verfügbar.");
            Files.copy(pendingSave.toPath(), out);
            showStatus("MIDI exportiert");
        } catch (IOException ex) { showStatus(ex.getLocalizedMessage()); }
        pendingSave = null;
    }

    @Override protected void onStop() {
        stopPlayback();
        if (surface != null) surface.release();
        else release();
        resetControls();
        stopAudio();
        if (recording) finishTake();
        usb.stop();
        gm.stop();
        usbDestinations = List.of();
        if (bluetooth != null) bluetooth.observe(null);
        if (bluetoothBound) unbindService(bluetoothConnection);
        bluetoothBound = false;
        bluetooth = null;
        if (hub != 0 && !isFinishing()) render();
        super.onStop();
    }

    @Override protected void onDestroy() {
        audioManager.unregisterAudioDeviceCallback(devices);
        handler.removeCallbacksAndMessages(null);
        if (classic != null) classic.closePanel();
        if (studio != null) studio.closePanel();
        if (hub != 0) nativeHub.destroy(hub);
        hub = 0;
        usb.stop();
        usbWorker.post(() -> usbThread.quitSafely());
        gm.stop();
        patternLoader.shutdownNow();
        super.onDestroy();
    }
}
