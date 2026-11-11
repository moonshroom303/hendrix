import AVFoundation
import CoreBluetooth
import SwiftUI

private let midiService = CBUUID(string: "03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
private let midiIO = CBUUID(string: "7772E5DB-3868-4112-A1A9-F2669D106BF3")

final class BluetoothMIDI: NSObject, CBPeripheralManagerDelegate {
    var report: (String) -> Void = { _ in }
    private var manager: CBPeripheralManager?
    private var characteristic: CBMutableCharacteristic?
    private var subscribers = Set<UUID>()
    private var pending: [Data] = []
    private var enabled = false

    func start() {
        enabled = true
        report("Bluetooth wird gestartet …")
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func stop() {
        enabled = false
        manager?.stopAdvertising()
        manager?.removeAllServices()
        manager?.delegate = nil
        manager = nil
        characteristic = nil
        subscribers.removeAll()
        pending.removeAll()
        report("Bluetooth-MIDI aus")
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral === manager, enabled else { return }
        guard peripheral.state == .poweredOn else {
            subscribers.removeAll()
            pending.removeAll()
            if peripheral.state != .unknown && peripheral.state != .resetting {
                report(peripheral.state == .poweredOff ? "Bluetooth einschalten" : "Bluetooth nicht verfügbar")
            }
            return
        }
        let service = CBMutableService(type: midiService, primary: true)
        let io = CBMutableCharacteristic(type: midiIO, properties: [.read, .writeWithoutResponse, .notify],
                                         value: nil, permissions: [.readable, .writeable])
        service.characteristics = [io]
        characteristic = io
        peripheral.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard peripheral === manager, enabled else { return }
        if let error { report(error.localizedDescription); return }
        peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [midiService],
                                      CBAdvertisementDataLocalNameKey: "Hendrix"])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        report(error?.localizedDescription ?? "Hendrix ist sichtbar")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribers.insert(central.identifier)
        report("MIDI verbunden")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribers.remove(central.identifier)
        if subscribers.isEmpty { pending.removeAll(); report("Hendrix ist sichtbar") }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        request.value = Data()
        peripheral.respond(to: request, withResult: request.offset == 0 ? .success : .invalidOffset)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flush()
    }

    func send(sample: UInt64, status: UInt8, a: UInt8, b: UInt8) {
        guard !subscribers.isEmpty else { return }
        let timestamp = sample / 48 & 8191
        var packet = Data([0x80 | UInt8(timestamp >> 7), 0x80 | UInt8(timestamp & 127), status, a])
        if status & 0xe0 != 0xc0 { packet.append(b) }
        if pending.count >= 512 {
            stop()
            report("MIDI-Verbindung überlastet. Bitte neu verbinden.")
            return
        }
        pending.append(packet)
        flush()
    }

    private func flush() {
        guard let manager, let characteristic else { return }
        while let packet = pending.first {
            guard manager.updateValue(packet, for: characteristic, onSubscribedCentrals: nil) else { break }
            pending.removeFirst()
        }
    }
}

private final class AudioQueue {
    let pointer = audio_queue_create()!
    deinit { audio_queue_destroy(pointer) }
}

func monotonicMS() -> UInt64 { DispatchTime.now().uptimeNanoseconds / 1_000_000 }

struct ScoreNote: Codable {
    var start: UInt64
    var end: UInt64
    var pitch: UInt8
    var string: UInt8
    var fret: UInt8
}

final class Hendrix: ObservableObject {
    @Published var recording = false
    @Published var listening = false
    @Published var bluetooth = false
    @Published var bluetoothStatus = "Bluetooth-MIDI aus"
    @Published var message = ""
    @Published var tuning = 440
    @Published var input = ""
    @Published var inputs: [AVAudioSessionPortDescription] = []
    @Published var reading: HendrixTunerReading?
    @Published var lastTake: URL?
    @Published var notes: [ScoreNote] = []
    @Published var poly = false
    @Published private(set) var guitarMetrics: String?

    private let hub = hub_create()!
    private let guitarStateKey = "classicGuitarState"
    private var guitarParameters: [Float] = []
    private var guitarInput = false
    private let ble = BluetoothMIDI()
    private var engine: AVAudioEngine?
    private var queue: AudioQueue?
    private var timer: Timer?
    private var routeObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var routeSignature = ""
    private var requestID = UUID()
    private var lastReading: UInt64 = 0
    private var gates: [UInt8: UUID] = [:]

    private var context: UnsafeMutableRawPointer { Unmanaged.passUnretained(self).toOpaque() }
    private let forward: HendrixEventCallback = { ptr, sample, status, a, b in
        guard let ptr else { return }
        Unmanaged<Hendrix>.fromOpaque(ptr).takeUnretainedValue().ble.send(sample: sample, status: status, a: a, b: b)
    }

    init() {
        ble.report = { [weak self] status in self?.bluetoothStatus = status }
        let center = NotificationCenter.default
        routeObserver = center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.routeChanged()
        }
        interruptionObserver = center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            if notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.began.rawValue {
                self?.stopListening()
                self?.message = "Audio unterbrochen"
            }
        }
        restoreTake()
        if let saved = guitarState {
            guitarParameters = readGuitarParameters(saved) ?? []
        }
    }

    deinit {
        timer?.invalidate()
        if let routeObserver { NotificationCenter.default.removeObserver(routeObserver) }
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        engine?.stop()
        engine?.inputNode.removeTap(onBus: 0)
        hub_destroy(hub)
    }

    func setBluetooth(_ enabled: Bool) {
        bluetooth = enabled
        if enabled { ble.start() } else { ble.stop() }
    }

    func setTuning(_ hz: Int) {
        tuning = hz
        hub_set_reference(hub, Float(hz))
    }

    var guitarState: [String: Any]? {
        guard let data = UserDefaults.standard.data(forKey: guitarStateKey) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func readGuitarParameters(_ state: [String: Any]) -> [Float]? {
        guard let values = state["current"] as? [NSNumber], values.count == 20 else { return nil }
        let result = values.map(\.floatValue)
        return result.allSatisfy(\.isFinite) ? result : nil
    }

    func saveGuitarState(_ state: [String: Any]) {
        guard let values = readGuitarParameters(state),
              let data = try? JSONSerialization.data(withJSONObject: state),
              data.count < 64_000 else { return }
        guitarParameters = values
        UserDefaults.standard.set(data, forKey: guitarStateKey)
        if listening && guitarInput { applyGuitarParameters() }
    }

    func updateGuitarParameters(_ values: [NSNumber]) {
        let parameters = values.map(\.floatValue)
        guard parameters.count == 20, parameters.allSatisfy(\.isFinite) else { return }
        guitarParameters = parameters
        if listening && guitarInput { applyGuitarParameters() }
    }

    private func applyGuitarParameters() {
        guitarParameters.withUnsafeBufferPointer { hub_set_guitar_parameters(hub, $0.baseAddress, $0.count) }
    }

    private func readGuitarMetrics() {
        var metrics = HendrixMetrics()
        var detected = [UInt8](repeating: 0, count: 128)
        guard detected.withUnsafeMutableBufferPointer({ hub_guitar_metrics(hub, &metrics, $0.baseAddress) }) else { return }
        let notes = detected.enumerated().compactMap { index, velocity in
            velocity == 0 ? nil : [index, Int(velocity)]
        }
        let payload: [String: Any] = [
            "level": metrics.level, "window": metrics.window, "bend": metrics.bend,
            "attacks": metrics.attacks, "notes": notes,
        ]
        if let data = try? JSONSerialization.data(withJSONObject: payload, options: .sortedKeys),
           let json = String(data: data, encoding: .utf8), json != guitarMetrics {
            guitarMetrics = json
        }
    }

    func refreshInputs() {
        let session = AVAudioSession.sharedInstance()
        guard let available = session.availableInputs, !available.isEmpty else { return }
        inputs = available
        if !inputs.contains(where: { $0.uid == input }) { input = "" }
    }

    func selectInput(_ uid: String) {
        guard uid != input else { return }
        let restart = listening
        if restart { stopListening() }
        input = uid
        if restart { openInput() }
    }

    func startListening(guitar: Bool) {
        let id = UUID()
        requestID = id
        guitarInput = guitar
        AVAudioApplication.requestRecordPermission { [weak self] permitted in
            DispatchQueue.main.async {
                guard let self, self.requestID == id else { return }
                guard permitted else { self.message = "Mikrofonzugriff in den Einstellungen erlauben."; return }
                self.openInput()
            }
        }
    }

    private func signature() -> String {
        AVAudioSession.sharedInstance().currentRoute.inputs.map(\.uid).joined(separator: ",")
    }

    private func routeChanged() {
        refreshInputs()
        if listening && signature() != routeSignature {
            stopListening()
            message = "Audioeingang geändert. Eingang wählen und erneut starten."
        }
    }

    private func openInput() {
        guard !listening else { return }
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.record, mode: .measurement)
            try audioSession.setActive(true)
            refreshInputs()
            try audioSession.setPreferredInput(inputs.first(where: { $0.uid == input }))
            let engine = AVAudioEngine()
            let node = engine.inputNode
            let format = node.outputFormat(forBus: 0)
            guard format.channelCount > 0, hub_set_audio(hub, Float(format.sampleRate), Float(tuning), poly) else {
                message = "Kein nutzbarer Audioeingang."
                return
            }
            if guitarInput { applyGuitarParameters() }
            let queue = AudioQueue()
            node.installTap(onBus: 0, bufferSize: 256, format: format) { buffer, _ in
                guard let samples = buffer.floatChannelData?[0] else { return }
                _ = audio_queue_push(queue.pointer, samples, buffer.frameLength)
            }
            do { try engine.start() } catch {
                node.removeTap(onBus: 0)
                throw error
            }
            self.engine = engine
            self.queue = queue
            routeSignature = signature()
            listening = true
            message = audioSession.currentRoute.inputs.first?.portName ?? ""
            let timer = Timer(timeInterval: 0.012, repeats: true) { [weak self] _ in self?.drain() }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        } catch {
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            message = error.localizedDescription
        }
    }

    private func drain() {
        guard let queue else { return }
        let now = monotonicMS()
        guard audio_queue_drain(queue.pointer, hub, now, forward, context) else {
            stopListening()
            if recording { finishTake() }
            message = "Audiopuffer überlastet. Aufnahme bis zum Abbruch gespeichert."
            return
        }
        if now - lastReading >= 65 {
            lastReading = now
            if guitarInput {
                readGuitarMetrics()
            } else {
                var value = HendrixTunerReading()
                reading = hub_tuner(hub, &value) ? value : nil
            }
        }
    }

    func stopListening() {
        requestID = UUID()
        timer?.invalidate()
        timer = nil
        if let engine {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        engine = nil
        if let queue { _ = audio_queue_drain(queue.pointer, hub, monotonicMS(), forward, context) }
        queue = nil
        hub_reset_audio(hub, monotonicMS(), forward, context)
        if listening { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        listening = false
        reading = nil
        guitarMetrics = nil
    }

    func note(_ pitch: UInt8, _ velocity: UInt8) {
        hub_key(hub, pitch, velocity, monotonicMS(), forward, context)
    }

    func control(_ number: UInt8, _ value: UInt8) {
        hub_control(hub, number, value, monotonicMS(), forward, context)
    }

    func pluck(_ pitch: UInt8) {
        let gate = UUID()
        gates[pitch] = gate
        note(pitch, 102)
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(430)) { [weak self] in
            guard let self, self.gates[pitch] == gate else { return }
            self.gates[pitch] = nil
            self.note(pitch, 0)
        }
    }

    func releaseKeys() {
        gates.removeAll()
        hub_release_keys(hub, monotonicMS(), forward, context)
    }

    func toggleRecording() {
        let wasRecording = recording
        drain()
        if wasRecording { finishTake() } else {
            recording = hub_start_take(hub, monotonicMS())
            message = recording ? "MIDI-Aufnahme läuft" : "Aufnahme konnte nicht starten."
        }
    }

    func finishTake() {
        guard recording, hub_stop_take(hub, monotonicMS()) else { return }
        recording = false
        let count = hub_score(hub, nil, 0)
        if count > 0 && count < 100_000 {
            var raw = [HendrixScoreNote](repeating: HendrixScoreNote(), count: count)
            _ = raw.withUnsafeMutableBufferPointer { hub_score(hub, $0.baseAddress, $0.count) }
            notes = raw.map { ScoreNote(start: $0.start_sample, end: $0.end_sample, pitch: $0.pitch,
                                        string: $0.guitar_string, fret: $0.guitar_fret) }
        } else { notes = [] }
        let size = hub_export(hub, nil, 0)
        guard size > 0 else { message = "MIDI-Export fehlgeschlagen."; return }
        var data = Data(count: size)
        _ = data.withUnsafeMutableBytes { hub_export(hub, $0.bindMemory(to: UInt8.self).baseAddress, size) }
        do {
            let url = try takeFolder().appendingPathComponent("Hendrix-\(Int(Date().timeIntervalSince1970))-\(UUID().uuidString.prefix(4)).mid")
            try data.write(to: url, options: .atomic)
            lastTake = url
            if let noteData = try? JSONEncoder().encode(notes) {
                try? noteData.write(to: url.deletingPathExtension().appendingPathExtension("json"), options: .atomic)
            }
            message = "MIDI gespeichert · \(notes.count) Noten"
        } catch { message = error.localizedDescription }
    }

    private func takeFolder() throws -> URL {
        let folder = URL.documentsDirectory.appendingPathComponent("Hendrix", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    private func restoreTake() {
        guard let folder = try? takeFolder(),
              let items = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil),
              let latest = items.filter({ $0.pathExtension == "mid" }).sorted(by: { $0.lastPathComponent > $1.lastPathComponent }).first else { return }
        lastTake = latest
        if let data = try? Data(contentsOf: latest.deletingPathExtension().appendingPathExtension("json")),
           let saved = try? JSONDecoder().decode([ScoreNote].self, from: data) { notes = saved }
    }
}
