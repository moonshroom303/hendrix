package io.moonshroom.hendrix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import java.util.function.Consumer;

/** Owns the BLE connection independently of the activity or its display. */
public final class BleMidiService extends Service {
    static final String START = "io.moonshroom.hendrix.action.START_BLE_MIDI";
    private static final String STOP = "io.moonshroom.hendrix.action.STOP_BLE_MIDI";
    private static final String CHANNEL = "hendrix-ble-midi";
    private static final int NOTIFICATION = 712;

    final class Connection extends Binder {
        BleMidiService service() { return BleMidiService.this; }
    }

    private final Connection connection = new Connection();
    private BleMidi midi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BleMidiDiagnostics diagnostics = new BleMidiDiagnostics();
    private HandlerThread bluetoothThread;
    private Handler worker;
    private volatile int epoch;
    private NotificationManager notifications;
    private volatile boolean enabled;
    private String status = "Bluetooth-MIDI aus";
    private Consumer<String> listener;

    private final BroadcastReceiver bluetoothState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!enabled || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                worker.post(midi::start);
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                worker.post(midi::stop);
                publish("Bluetooth aus · wartet auf Einschalten");
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Hendrix Bluetooth-MIDI", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
        bluetoothThread = new HandlerThread("Hendrix-Bluetooth");
        bluetoothThread.start();
        worker = new Handler(bluetoothThread.getLooper());
        midi = new BleMidi(this, worker, message -> {
            int id = epoch;
            main.post(() -> { if (enabled && epoch == id) publish(message); });
        }, message -> {
            int id = epoch;
            main.post(() -> { if (enabled && epoch == id) disable(message); });
        }, diagnostics);
        diagnostics.add("Bluetooth-Dienst erstellt");
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothState, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(bluetoothState, filter);
    }

    @Override public IBinder onBind(Intent intent) { return connection; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action) || (START.equals(action) &&
                getSharedPreferences("midi-output", MODE_PRIVATE).getBoolean("usb", false))) {
            disable("Bluetooth-MIDI aus");
        } else if (START.equals(action) && !enabled) {
            enabled = true;
            ++epoch;
            status = "Bluetooth wird gestartet …";
            try {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                publish(status);
                worker.post(midi::start);
            } catch (SecurityException | IllegalStateException error) {
                disable("Bluetooth-MIDI konnte nicht im Hintergrund gestartet werden.");
            }
        }
        return START_NOT_STICKY;
    }

    boolean enabled() { return enabled; }
    boolean connected() { return enabled && midi.connected(); }
    String diagnostics() { return diagnostics.snapshot(); }

    void observe(Consumer<String> consumer) {
        listener = consumer;
        if (consumer != null) consumer.accept(status);
    }

    void send(long sample, int status, int a, int b) {
        if (!enabled) return;
        int id = epoch;
        worker.post(() -> { if (epoch == id) midi.send(sample, status, a, b); });
    }

    void stopMidi() { disable("Bluetooth-MIDI aus"); }

    void restartMidi() {
        if (!enabled) return;
        int id = ++epoch;
        diagnostics.add("Bluetooth-MIDI manuell neu gestartet");
        publish("Bluetooth wird neu sichtbar gemacht · am Mac erneut verbinden");
        worker.post(() -> {
            if (epoch != id) return;
            midi.stop();
            midi.start();
        });
    }

    private void disable(String reason) {
        enabled = false;
        ++epoch;
        worker.post(midi::stop);
        stopForeground(STOP_FOREGROUND_REMOVE);
        publish(reason);
        stopSelf();
    }

    private void publish(String message) {
        boolean changed = !message.equals(status);
        status = message;
        if (changed) diagnostics.add(message);
        if (enabled && changed) notifications.notify(NOTIFICATION, notification());
        if (listener != null) listener.accept(status);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, HendrixActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, BleMidiService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Hendrix · Bluetooth-MIDI")
                .setContentText(status)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Trennen", stop).build())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        unregisterReceiver(bluetoothState);
        enabled = false;
        ++epoch;
        worker.post(() -> { midi.stop(); bluetoothThread.quitSafely(); });
        main.removeCallbacksAndMessages(null);
        diagnostics.add("Bluetooth-Dienst beendet");
        listener = null;
        super.onDestroy();
    }
}
