#!/usr/bin/env python3
"""Run the actual Bluetooth and USB transports against deterministic Android API fakes.

These tests cover app callback ordering and cleanup, not radio/firmware behavior.
Fakes are generated only into build/, never included in the Android APK.
"""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
build = root / "build/ble-host-tests"
stubs = {
    "android.annotation.SuppressLint": 'public @interface SuppressLint { String[] value(); }',
    "android.os.SystemClock": 'public class SystemClock { public static long time; public static long elapsedRealtime() { return time; } }',
    "android.os.Build": '''public class Build {
        public static String MANUFACTURER="Test", MODEL="Phone";
        public static class VERSION { public static int SDK_INT=35; public static String RELEASE="15"; }
    }''',
    "android.os.ParcelUuid": 'public class ParcelUuid { public ParcelUuid(java.util.UUID uuid) {} }',
    "android.os.Handler": '''public class Handler {
        record Task(long at, long order, Runnable action) {}
        private long order;
        private final java.util.PriorityQueue<Task> tasks = new java.util.PriorityQueue<>(
            java.util.Comparator.comparingLong(Task::at).thenComparingLong(Task::order));
        public boolean post(Runnable r) { return postDelayed(r, 0); }
        public boolean postDelayed(Runnable r, long delay) { tasks.add(new Task(SystemClock.time+delay, order++, r)); return true; }
        public void removeCallbacks(Runnable r) { tasks.removeIf(t -> t.action()==r); }
        public void runReady() { advance(0); }
        public void wakeAfter(long ms) {
            SystemClock.time += ms;
            int limit=10000;
            while (!tasks.isEmpty() && tasks.peek().at()<=SystemClock.time) {
                if (--limit==0) throw new AssertionError("Unbounded wakeup loop");
                tasks.remove().action().run();
            }
        }
        public void advance(long ms) {
            long until=SystemClock.time+ms;
            int limit=10000;
            while (!tasks.isEmpty() && tasks.peek().at()<=until) {
                if (--limit==0) throw new AssertionError("Unbounded callback loop");
                Task t=tasks.remove(); SystemClock.time=t.at(); t.action().run();
            }
            SystemClock.time=until;
        }
    }''',
    "android.content.SharedPreferences": '''public class SharedPreferences {
        private final java.util.Map<String,String> data=new java.util.HashMap<>();
        public String getString(String key,String fallback) { return data.getOrDefault(key,fallback); }
        public Editor edit() { return new Editor(); }
        public class Editor {
            public Editor putString(String k,String v) { data.put(k,v); return this; }
            public Editor remove(String k) { data.remove(k); return this; }
            public void apply() {}
        }
    }''',
    "android.content.Context": '''public class Context {
        public static final int MODE_PRIVATE=0;
        public final android.content.SharedPreferences preferences=new android.content.SharedPreferences();
        public android.content.SharedPreferences getSharedPreferences(String name,int mode) { return preferences; }
        public final android.bluetooth.BluetoothManager manager=new android.bluetooth.BluetoothManager();
        public final android.media.midi.MidiManager midi=new android.media.midi.MidiManager();
        public <T> T getSystemService(Class<T> type) {
            return type.cast(type==android.media.midi.MidiManager.class ? midi : manager);
        }
    }''',
    "android.os.Bundle": 'public class Bundle extends java.util.HashMap<String,String> { public String getString(String key) { return get(key); } }',
    "android.media.midi.MidiDeviceInfo": '''public class MidiDeviceInfo {
        public static final int TYPE_USB=1, TYPE_VIRTUAL=2, TYPE_BLUETOOTH=3;
        public static final String PROPERTY_NAME="name";
        private final int id, type;
        private final PortInfo[] ports;
        private final android.os.Bundle properties=new android.os.Bundle();
        public MidiDeviceInfo(int id,int type,String name,PortInfo... ports) {
            this.id=id; this.type=type; this.ports=ports; properties.put(PROPERTY_NAME,name);
        }
        public int getId() { return id; }
        public int getType() { return type; }
        public android.os.Bundle getProperties() { return properties; }
        public PortInfo[] getPorts() { return ports; }
        public int getInputPortCount() { return (int)java.util.Arrays.stream(ports).filter(p -> p.getType()==1).count(); }
        public static class PortInfo {
            public static final int TYPE_INPUT=1, TYPE_OUTPUT=2;
            private final int type, number; private final String name;
            public PortInfo(int type,int number,String name) { this.type=type; this.number=number; this.name=name; }
            public int getType() { return type; }
            public int getPortNumber() { return number; }
            public String getName() { return name; }
        }
    }''',
    "android.media.midi.MidiInputPort": '''public class MidiInputPort {
        public boolean closed, fail;
        public final java.util.ArrayList<byte[]> sent=new java.util.ArrayList<>();
        public void send(byte[] data,int offset,int count) throws java.io.IOException {
            if (closed || fail) throw new java.io.IOException("Disconnected");
            if (count>1015) throw new AssertionError("MIDI packet too large");
            sent.add(java.util.Arrays.copyOfRange(data,offset,offset+count));
        }
        public void close() throws java.io.IOException { closed=true; }
    }''',
    "android.media.midi.MidiDevice": '''public class MidiDevice {
        public boolean closed, busy;
        public int portNumber=-1;
        public final MidiInputPort port=new MidiInputPort();
        public MidiInputPort openInputPort(int number) { portNumber=number; return busy ? null : port; }
        public void close() throws java.io.IOException { closed=true; }
    }''',
    "android.media.midi.MidiManager": '''public class MidiManager {
        public static final int TRANSPORT_MIDI_BYTE_STREAM=1;
        public final java.util.List<MidiDeviceInfo> devices=new java.util.ArrayList<>();
        public final java.util.List<MidiDevice> opened=new java.util.ArrayList<>();
        public final java.util.List<Runnable> delayed=new java.util.ArrayList<>();
        public DeviceCallback callback;
        private java.util.concurrent.Executor executor;
        public boolean delay, nullDevice, busy;
        public int legacyEnumerations, modernEnumerations, legacyRegistrations, modernRegistrations, unregisters;
        public MidiDeviceInfo[] getDevices() { legacyEnumerations++; return devices.toArray(MidiDeviceInfo[]::new); }
        public java.util.Set<MidiDeviceInfo> getDevicesForTransport(int transport) {
            checkTransport(transport); modernEnumerations++; return new java.util.HashSet<>(devices);
        }
        private void checkTransport(int transport) { if (transport!=1) throw new AssertionError("Need MIDI 1.0"); }
        public void registerDeviceCallback(int transport,java.util.concurrent.Executor e,DeviceCallback c) {
            checkTransport(transport); modernRegistrations++; executor=e; callback=c;
        }
        public void registerDeviceCallback(DeviceCallback c,android.os.Handler h) {
            legacyRegistrations++; executor=r -> h.post(r); callback=c;
        }
        public void unregisterDeviceCallback(DeviceCallback c) {
            unregisters++; if (callback==c) callback=null;
        }
        public void openDevice(MidiDeviceInfo info,OnDeviceOpenedListener listener,android.os.Handler h) {
            MidiDevice device=nullDevice ? null : new MidiDevice();
            if (device!=null) device.busy=busy;
            opened.add(device);
            Runnable reply=() -> h.post(() -> listener.onDeviceOpened(device));
            if (delay) delayed.add(reply); else reply.run();
        }
        public void reply() { java.util.List<Runnable> copy=new java.util.ArrayList<>(delayed); delayed.clear(); copy.forEach(Runnable::run); }
        public void add(MidiDeviceInfo info) { devices.add(info); DeviceCallback c=callback; if(c!=null) executor.execute(() -> c.onDeviceAdded(info)); }
        public void remove(MidiDeviceInfo info) { devices.remove(info); DeviceCallback c=callback; if(c!=null) executor.execute(() -> c.onDeviceRemoved(info)); }
        public interface OnDeviceOpenedListener { void onDeviceOpened(MidiDevice device); }
        public static class DeviceCallback {
            public void onDeviceAdded(MidiDeviceInfo info) {} public void onDeviceRemoved(MidiDeviceInfo info) {}
        }
    }''',
    "android.bluetooth.BluetoothProfile": 'public interface BluetoothProfile { int STATE_DISCONNECTED=0, STATE_CONNECTED=2; }',
    "android.bluetooth.BluetoothStatusCodes": 'public class BluetoothStatusCodes { public static final int SUCCESS=0, ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION=6; }',
    "android.bluetooth.BluetoothDevice": '''public class BluetoothDevice {
        public static final int TRANSPORT_LE=2, PHY_LE_1M_MASK=1, BOND_BONDED=12;
        public static final java.util.Map<String,BluetoothDevice> devices=new java.util.HashMap<>();
        public final String address=String.format("00:00:00:00:00:%02X",devices.size()+1);
        public boolean background;
        public BluetoothDevice() { devices.put(address,this); }
        public String getAddress() { return address; }
        public int clients;
        public BluetoothGatt client;
        public int getBondState() { return 12; }
        public BluetoothGatt connectGatt(android.content.Context c, boolean auto, BluetoothGattCallback cb, int transport, int phy, android.os.Handler h) {
            if (transport!=TRANSPORT_LE) throw new AssertionError("Unexpected scan or transport");
            background=auto; clients++; client=new BluetoothGatt(cb); return client;
        }
    }''',
    "android.bluetooth.BluetoothGatt": '''public class BluetoothGatt {
        public static final int GATT_SUCCESS=0, GATT_FAILURE=257, GATT_INVALID_OFFSET=7, GATT_REQUEST_NOT_SUPPORTED=6;
        public final BluetoothGattCallback callback;
        public int discoveries, closes, disconnects, readAttempts;
        public boolean readAccepted=true, discoveryAccepted=true;
        public final java.util.Map<java.util.UUID,BluetoothGattService> services=new java.util.HashMap<>();
        public final java.util.ArrayList<BluetoothGattCharacteristic> reads=new java.util.ArrayList<>();
        public BluetoothGatt(BluetoothGattCallback cb) { callback=cb; }
        public boolean discoverServices() { discoveries++; return discoveryAccepted; }
        public BluetoothGattService getService(java.util.UUID uuid) { return services.get(uuid); }
        public boolean readCharacteristic(BluetoothGattCharacteristic c) {
            readAttempts++;
            if (readAccepted) reads.add(c);
            return readAccepted;
        }
        public void close() { closes++; }
        public void disconnect() { disconnects++; }
    }''',
    "android.bluetooth.BluetoothGattCallback": '''public class BluetoothGattCallback {
        public void onConnectionStateChange(BluetoothGatt g,int s,int n) {}
        public void onServicesDiscovered(BluetoothGatt g,int s) {}
        public void onServiceChanged(BluetoothGatt g) {}
        public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status) {}
        public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status) {}
    }''',
    "android.bluetooth.BluetoothGattCharacteristic": '''public class BluetoothGattCharacteristic {
        public static final int PROPERTY_READ=2, PROPERTY_WRITE_NO_RESPONSE=4, PROPERTY_NOTIFY=16,
            PERMISSION_READ_ENCRYPTED=2, PERMISSION_WRITE_ENCRYPTED=32;
        private final java.util.UUID uuid;
        private final int properties;
        public BluetoothGattDescriptor descriptor;
        public byte[] value;
        public BluetoothGattCharacteristic(java.util.UUID u,int p,int a) { uuid=u; properties=p; }
        public java.util.UUID getUuid() { return uuid; }
        public int getProperties() { return properties; }
        public boolean addDescriptor(BluetoothGattDescriptor d) { descriptor=d; return true; }
        public boolean setValue(byte[] v) { value=v; return true; }
    }''',
    "android.bluetooth.BluetoothGattDescriptor": '''public class BluetoothGattDescriptor {
        public static final int PERMISSION_READ_ENCRYPTED=2, PERMISSION_WRITE_ENCRYPTED=32;
        public static final byte[] ENABLE_NOTIFICATION_VALUE={1,0}, DISABLE_NOTIFICATION_VALUE={0,0};
        private final java.util.UUID uuid;
        public BluetoothGattDescriptor(java.util.UUID u,int p) { uuid=u; }
        public java.util.UUID getUuid() { return uuid; }
    }''',
    "android.bluetooth.BluetoothGattService": '''public class BluetoothGattService {
        public static final int SERVICE_TYPE_PRIMARY=0;
        public BluetoothGattCharacteristic characteristic;
        public BluetoothGattService(java.util.UUID u,int t) {}
        public BluetoothGattCharacteristic getCharacteristic(java.util.UUID u) {
            return characteristic!=null && characteristic.getUuid().equals(u) ? characteristic : null;
        }
        public boolean addCharacteristic(BluetoothGattCharacteristic c) { characteristic=c; return true; }
    }''',
    "android.bluetooth.BluetoothGattServerCallback": '''public class BluetoothGattServerCallback {
        public void onServiceAdded(int s,BluetoothGattService v) {}
        public void onConnectionStateChange(BluetoothDevice d,int s,int n) {}
        public void onCharacteristicReadRequest(BluetoothDevice d,int r,int o,BluetoothGattCharacteristic c) {}
        public void onDescriptorReadRequest(BluetoothDevice d,int r,int o,BluetoothGattDescriptor c) {}
        public void onDescriptorWriteRequest(BluetoothDevice d,int r,BluetoothGattDescriptor c,boolean p,boolean a,int o,byte[] v) {}
        public void onCharacteristicWriteRequest(BluetoothDevice d,int r,BluetoothGattCharacteristic c,boolean p,boolean a,int o,byte[] v) {}
        public void onExecuteWrite(BluetoothDevice d,int r,boolean e) {}
        public void onMtuChanged(BluetoothDevice d,int m) {}
        public void onNotificationSent(BluetoothDevice d,int s) {}
    }''',
    "android.bluetooth.BluetoothGattServer": '''public class BluetoothGattServer {
        public record Response(int request,int status,int offset,byte[] value) {}
        public final BluetoothGattServerCallback callback;
        public BluetoothGattService service;
        public int connects, cancels, closes, notifyStatus;
        public boolean responseAccepted=true;
        public final java.util.ArrayList<Response> responses=new java.util.ArrayList<>();
        public final java.util.ArrayList<byte[]> sent=new java.util.ArrayList<>();
        public BluetoothGattServer(BluetoothGattServerCallback c) { callback=c; }
        public boolean addService(BluetoothGattService s) { service=s; callback.onServiceAdded(0,s); return true; }
        public boolean connect(BluetoothDevice d,boolean auto) {
            if (auto) throw new AssertionError("Unexpected reconnect loop");
            connects++; callback.onConnectionStateChange(d,0,2); return true;
        }
        public void cancelConnection(BluetoothDevice d) { cancels++; }
        public void close() { closes++; }
        public boolean sendResponse(BluetoothDevice d,int request,int status,int offset,byte[] value) {
            responses.add(new Response(request,status,offset,value)); return responseAccepted;
        }
        public int notifyCharacteristicChanged(BluetoothDevice d,BluetoothGattCharacteristic c,boolean confirm,byte[] data) {
            if (confirm) throw new AssertionError("MIDI must use notifications");
            if (notifyStatus==0) sent.add(data.clone()); return notifyStatus;
        }
        public boolean notifyCharacteristicChanged(BluetoothDevice d,BluetoothGattCharacteristic c,boolean confirm) {
            return notifyCharacteristicChanged(d,c,confirm,c.value)==0;
        }
    }''',
    "android.bluetooth.BluetoothAdapter": '''public class BluetoothAdapter {
        public BluetoothDevice getRemoteDevice(String address) { return BluetoothDevice.devices.get(address); }
        public final android.bluetooth.le.BluetoothLeAdvertiser advertiser=new android.bluetooth.le.BluetoothLeAdvertiser();
        public boolean isEnabled() { return true; }
        public android.bluetooth.le.BluetoothLeAdvertiser getBluetoothLeAdvertiser() { return advertiser; }
    }''',
    "android.bluetooth.BluetoothManager": '''public class BluetoothManager {
        public BluetoothGattServer server;
        public int opens;
        public final BluetoothAdapter adapter=new BluetoothAdapter();
        public BluetoothAdapter getAdapter() { return adapter; }
        public BluetoothGattServer openGattServer(android.content.Context c,BluetoothGattServerCallback cb) {
            opens++; server=new BluetoothGattServer(cb); return server;
        }
    }''',
    "android.bluetooth.le.AdvertiseCallback": '''public class AdvertiseCallback {
        public static final int ADVERTISE_FAILED_DATA_TOO_LARGE=1;
        public void onStartSuccess(AdvertiseSettings s) {} public void onStartFailure(int error) {}
    }''',
    "android.bluetooth.le.AdvertiseSettings": '''public class AdvertiseSettings {
        public static final int ADVERTISE_MODE_LOW_POWER=0;
        public static class Builder {
            public Builder setAdvertiseMode(int m) { return this; } public Builder setConnectable(boolean c) { return this; }
            public Builder setTimeout(int t) { return this; } public AdvertiseSettings build() { return new AdvertiseSettings(); }
        }
    }''',
    "android.bluetooth.le.AdvertiseData": '''public class AdvertiseData {
        public static class Builder {
            public Builder addServiceUuid(android.os.ParcelUuid u) { return this; }
            public Builder setIncludeDeviceName(boolean n) { return this; }
            public AdvertiseData build() { return new AdvertiseData(); }
        }
    }''',
    "android.bluetooth.le.BluetoothLeAdvertiser": '''public class BluetoothLeAdvertiser {
        public int starts, stops;
        public AdvertiseCallback callback;
        public void startAdvertising(AdvertiseSettings s,AdvertiseData d,AdvertiseData scan,AdvertiseCallback c) {
            starts++; callback=c; c.onStartSuccess(s);
        }
        public void stopAdvertising(AdvertiseCallback c) { stops++; }
    }''',
}
files = []
for name, body in stubs.items():
    package, _ = name.rsplit(".", 1)
    path = build / (name.replace(".", "/") + ".java")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"package {package};\n{body}\n")
    files.append(str(path))
files += [str(root / f"android/src/io/moonshroom/hendrix/{name}.java")
          for name in ("BleMidi", "BleMidiPeer", "BleMidiPackets", "BleMidiDiagnostics", "UsbMidi", "MidiPattern", "MidiPatternPlayer", "GeneralMidiPrograms")]
files += [str(p) for p in (root / "android/tests").glob("*Test.java")]
java_home = os.environ.get("JAVA_HOME")
jdk = Path(java_home) / "bin" if java_home else root / ".toolchain/jdk/bin"
if not java_home and not (jdk / "javac").is_file():
    jdk = Path()
subprocess.run([str(jdk / "javac"), "-encoding", "UTF-8", "-d", str(build), *files], check=True)
for test in ("BleMidiPacketsTest", "BleMidiCallbacksTest", "BleMidiIdleSignalTest", "UsbMidiTest", "MidiPatternPlayerTest"):
    subprocess.run([str(jdk / "java"), "-ea", "-cp", str(build), f"io.moonshroom.hendrix.{test}"], check=True)
