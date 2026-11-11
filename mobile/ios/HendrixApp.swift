import SwiftUI
import UIKit
import WebKit

private let accent = Color(red: 0.92, green: 0.72, blue: 0.39)
private let openStrings = [64, 59, 55, 50, 45, 40]
private let stringNames = ["e", "B", "G", "D", "A", "E"]
private let chords: [(String, [Int])] = [
    ("C", [0, 1, 0, 2, 3, -1]), ("G", [3, 0, 0, 0, 2, 3]), ("D", [2, 3, 2, 0, -1, -1]),
    ("Am", [0, 1, 2, 2, 0, -1]), ("Em", [0, 0, 0, 2, 2, 0]), ("E", [0, 0, 1, 2, 2, 0]),
    ("A", [0, 2, 2, 2, 0, -1]), ("Dm", [1, 3, 2, 0, -1, -1])
]
private let pitchNames = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]
private let diatonic = [0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6]

@main
struct HendrixApp: App {
    @StateObject private var model = Hendrix()
    var body: some Scene {
        WindowGroup { InstrumentScreen(model: model).preferredColorScheme(.dark) }
    }
}

struct InstrumentScreen: View {
    enum Page: String, CaseIterable {
        case guitar = "Gitarre", voice = "Gesang"
    }
    enum MIDIInput: String, CaseIterable {
        case fretboard = "Griffbrett", keys = "Keys"
    }
    @ObservedObject var model: Hendrix
    @Environment(\.scenePhase) private var phase
    @State private var page = Page.guitar
    @State private var midiInput = MIDIInput.fretboard
    @State private var chordMode = false
    @State private var chord = 0
    @State private var fret = 0
    @State private var octave = 4
    @State private var scoreType = 0
    @State private var scoreVisible = false
    @State private var sustain = false
    @State private var modulation = 0.0
    @State private var classicHeight: CGFloat = 720
    @State private var studioHeight: CGFloat = 460

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Text("HENDRIX").font(.system(size: 26, weight: .heavy, design: .monospaced)).tracking(1)
                    Spacer()
                    Button { model.setBluetooth(!model.bluetooth) } label: {
                        Label("BLE MIDI", systemImage: "antenna.radiowaves.left.and.right")
                            .font(.caption.bold())
                            .padding(9)
                            .background(model.bluetooth ? accent.opacity(0.22) : .white.opacity(0.08), in: Capsule())
                    }
                    .accessibilityLabel(model.bluetooth ? "Bluetooth-MIDI ausschalten" : "Bluetooth-MIDI einschalten")
                }
                Text(model.bluetoothStatus).font(.caption).foregroundStyle(.secondary)
                Picker("Audioquelle", selection: $page) {
                    ForEach(Page.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented)

                switch page {
                case .guitar: audioInput(guitar: true)
                case .voice: voice
                }

                Divider()
                HStack {
                    Button {
                        model.toggleRecording()
                        if !model.recording && model.lastTake != nil { scoreVisible = true }
                    } label: {
                        Label(model.recording ? "Stopp" : "Aufnahme", systemImage: model.recording ? "stop.fill" : "record.circle")
                            .font(.headline).frame(maxWidth: .infinity).padding(12)
                            .background(Color(red: 0.7, green: 0.15, blue: 0.16), in: RoundedRectangle(cornerRadius: 12))
                    }.accessibilityIdentifier("record")
                    if let url = model.lastTake {
                        ShareLink(item: url) { Label("MIDI teilen", systemImage: "square.and.arrow.up").font(.subheadline) }
                    }
                }.buttonStyle(.plain)
                if !model.message.isEmpty { Text(model.message).font(.caption).foregroundStyle(.secondary) }
                if model.lastTake != nil {
                    DisclosureGroup("Aufnahme ansehen", isExpanded: $scoreVisible) {
                        Picker("Ansicht", selection: $scoreType) {
                            Text("Noten").tag(0)
                            Text("Tabulatur").tag(1)
                        }.pickerStyle(.segmented).padding(.vertical, 10)
                        if model.notes.isEmpty {
                            Text("Diese Aufnahme enthält keine Noten.").font(.callout).foregroundStyle(.secondary)
                        } else {
                            ScoreView(notes: model.notes, tablature: scoreType == 1)
                            Text(scoreType == 1 ? "Standardstimmung · Vorschläge für Einzeltöne" : "Unquantisierte Notenübersicht")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
                if page == .guitar {
                    VStack(alignment: .leading, spacing: 12) {
                        ClassicGuitarPanel(model: model, part: .controls, height: $classicHeight)
                            .frame(height: classicHeight)
                        midiInputPanel
                        ClassicGuitarPanel(model: model, part: .studio, height: $studioHeight)
                            .frame(height: studioHeight)
                    }
                }
            }.padding(20).frame(maxWidth: 680)
        }
        .background(Color(red: 0.075, green: 0.077, blue: 0.09).ignoresSafeArea())
        .tint(accent)
        .onChange(of: page) { _, _ in
            model.releaseKeys()
            resetControls()
            model.stopListening()
        }
        .onChange(of: midiInput) { _, _ in
            model.releaseKeys()
            resetControls()
        }
        .onChange(of: phase) { _, value in
            if value == .background {
                model.releaseKeys()
                resetControls()
                model.stopListening()
                model.finishTake()
            }
        }
    }

    private var midiInputPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MIDI-Eingabe")
                .font(.subheadline.weight(.semibold))
            Picker("MIDI-Eingabe", selection: $midiInput) {
                ForEach(MIDIInput.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }.pickerStyle(.segmented)
            switch midiInput {
            case .fretboard: fretboard
            case .keys: keys
            }
        }
        .padding(12)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 10))
    }

    private var fretboard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("Spielart", selection: $chordMode) {
                Text("Solo").tag(false)
                Text("Akkorde").tag(true)
            }.pickerStyle(.segmented)
            if chordMode {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(chords.indices, id: \.self) { index in
                            Button(chords[index].0) { chord = index }
                                .buttonStyle(.bordered)
                                .tint(index == chord ? accent : .gray)
                        }
                    }
                }
            } else {
                Stepper("Bünde \(fret)–\(fret + 4)", value: $fret, in: 0...20, step: 5)
            }
            PlayingSurface(layout: chordMode ? .chord(chord) : .fret(fret), model: model)
                .frame(height: 252).clipShape(RoundedRectangle(cornerRadius: 12))
            Text(chordMode ? "Streiche über die Saiten oder zupfe einzelne an." : "Berühre einen Bund. Ziehe zum nächsten Ton.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func audioInput(guitar: Bool) -> some View {
        HStack {
            Picker("Audioeingang", selection: Binding(get: { model.input }, set: { model.selectInput($0) })) {
                Text("Automatisch").tag("")
                ForEach(model.inputs, id: \.uid) { Text($0.portName).tag($0.uid) }
            }
            Spacer()
            Button(model.listening ? "Stoppen" : "Eingang starten") {
                model.listening ? model.stopListening() : model.startListening(guitar: guitar)
            }.buttonStyle(.borderedProminent)
                .accessibilityIdentifier("audioInput")
        }
    }

    private var voice: some View {
        VStack(alignment: .leading, spacing: 12) {
            audioInput(guitar: false)
            Picker("Erkennung", selection: $model.poly) {
                Text("Gesang / Solo").tag(false)
                Text("Akkorde am Eingang").tag(true)
            }.pickerStyle(.segmented).disabled(model.listening)
            TunerView(reading: model.reading)
            Picker("Stimmreferenz", selection: Binding(get: { model.tuning }, set: { model.setTuning($0) })) {
                Text("432 Hz").tag(432)
                Text("440 Hz").tag(440)
            }.pickerStyle(.segmented)
        }
    }

    private var keys: some View {
        VStack(alignment: .leading, spacing: 12) {
            Stepper("Oktave \(octave)", value: $octave, in: 1...7)
            PlayingSurface(layout: .keyboard(octave), model: model)
                .frame(height: 240).clipShape(RoundedRectangle(cornerRadius: 10))
            Toggle("Sustain", isOn: $sustain)
                .onChange(of: sustain) { _, on in model.control(64, on ? 127 : 0) }
            HStack {
                Text("Modulation")
                Slider(value: $modulation, in: 0...127, step: 1)
                    .accessibilityLabel("Modulation")
                    .onChange(of: modulation) { _, value in model.control(1, UInt8(value)) }
            }
        }
    }

    private func resetControls() {
        sustain = false
        modulation = 0
        model.control(64, 0)
        model.control(1, 0)
    }
}

private struct TunerView: View {
    var reading: HendrixTunerReading?
    var body: some View {
        VStack(spacing: 8) {
            Text(reading.map { "\(pitchNames[Int($0.note) % 12])\(Int($0.note) / 12 - 1)" } ?? "–")
                .font(.system(size: 50, weight: .medium, design: .monospaced))
            GeometryReader { proxy in
                let value = min(50, max(-50, CGFloat(reading?.cents ?? 0)))
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.12))
                    Rectangle().fill(.white.opacity(0.55)).frame(width: 2, height: 18).offset(x: proxy.size.width / 2)
                    if reading != nil {
                        Circle().fill(abs(value) < 5 ? .green : accent).frame(width: 14)
                            .offset(x: (proxy.size.width - 14) * (value + 50) / 100)
                    }
                }
            }.frame(height: 18)
            Text(reading.map { String(format: "%.1f Hz  ·  %+.0f Cent", $0.hz, $0.cents) } ?? "Stimmgerät")
                .font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary)
        }.padding(14).frame(maxWidth: .infinity).background(.white.opacity(0.055), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct ScoreView: View {
    var notes: [ScoreNote]
    var tablature: Bool
    var body: some View {
        let duration = Double(notes.map(\.end).max() ?? 0) / 48000
        let scale = min(110.0, 14000 / max(1, duration))
        let width = max(320, duration * scale + 80)
        let steps = notes.map { note in
            let pitch = Int(note.pitch)
            return (pitch / 12 - 5) * 7 + diatonic[pitch % 12]
        }
        let staffTop = max(60, Double(steps.max() ?? 10) * 6 - 32)
        let height = tablature ? 186 : max(186, staffTop + 78 - Double(steps.min() ?? 0) * 6)
        ScrollView(.horizontal) {
            Canvas { context, size in
                let count = tablature ? 6 : 5
                let top = tablature ? 34.0 : staffTop
                let space = tablature ? 19.0 : 12.0
                func line(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) {
                    var path = Path()
                    path.move(to: CGPoint(x: x1, y: y1))
                    path.addLine(to: CGPoint(x: x2, y: y2))
                    context.stroke(path, with: .color(.white.opacity(0.35)))
                }
                for index in 0..<count {
                    let y = top + Double(index) * space
                    line(25, y, size.width - 8, y)
                    if tablature { context.draw(Text(stringNames[index]).font(.caption.monospaced()), at: CGPoint(x: 12, y: y)) }
                }
                if !tablature {
                    context.draw(Text("𝄞").font(.system(size: 51)), at: CGPoint(x: 29, y: top + 24))
                }
                for note in notes {
                    let x = 56 + Double(note.start) / 48000 * scale
                    if tablature {
                        guard note.string > 0 else { continue }
                        let y = top + Double(note.string - 1) * space
                        context.fill(Path(CGRect(x: x - 8, y: y - 8, width: 16, height: 16)), with: .color(Color(white: 0.13)))
                        context.draw(Text("\(note.fret)").font(.system(.caption, design: .monospaced)), at: CGPoint(x: x, y: y))
                    } else {
                        let p = Int(note.pitch)
                        let step = (p / 12 - 5) * 7 + diatonic[p % 12]
                        let y = top + 60 - Double(step) * 6
                        if y < 8 || y > size.height - 8 { continue }
                        if step <= 0 {
                            for ledger in stride(from: 0, through: step, by: -2) {
                                line(x - 9, top + 60 - Double(ledger) * 6, x + 9, top + 60 - Double(ledger) * 6)
                            }
                        } else if step >= 12 {
                            for ledger in stride(from: 12, through: step, by: 2) {
                                line(x - 9, top + 60 - Double(ledger) * 6, x + 9, top + 60 - Double(ledger) * 6)
                            }
                        }
                        context.fill(Path(ellipseIn: CGRect(x: x - 5, y: y - 3.5, width: 10, height: 7)), with: .color(accent))
                        line(x + 4, y, x + 4, y - 22)
                        if pitchNames[p % 12].contains("♯") {
                            context.draw(Text("♯").font(.caption), at: CGPoint(x: x - 10, y: y))
                        }
                    }
                }
            }.frame(width: width, height: height)
        }.background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
    }
}

private enum Layout: Equatable {
    case fret(Int), chord(Int), keyboard(Int)
}

private struct PlayingSurface: UIViewRepresentable {
    var layout: Layout
    let model: Hendrix
    func makeUIView(context: Context) -> Surface {
        let view = Surface()
        view.model = model
        return view
    }
    func updateUIView(_ view: Surface, context: Context) {
        if view.layout != layout {
            view.release()
            view.layout = layout
            view.setNeedsDisplay()
        }
        if case .keyboard = layout { view.accessibilityLabel = "Klaviatur" }
        else { view.accessibilityLabel = "Gitarrengriffbrett" }
    }
    static func dismantleUIView(_ view: Surface, coordinator: ()) { view.release() }
}

private final class Surface: UIView {
    weak var model: Hendrix?
    var layout = Layout.fret(0)
    private var active: [UITouch: Int] = [:]
    private var held: [Int: Int] = [:]
    private weak var scroll: UIScrollView?
    private var scrollLocked = false
    private let gold = UIColor(red: 0.92, green: 0.72, blue: 0.39, alpha: 1)
    private let whites = [0, 2, 4, 5, 7, 9, 11, 12]
    private let blacks = [(0, 1), (1, 3), (3, 6), (4, 8), (5, 10)]

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        isAccessibilityElement = true
        accessibilityIdentifier = "playingSurface"
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) unavailable") }

    override func draw(_ rect: CGRect) {
        guard let cg = UIGraphicsGetCurrentContext() else { return }
        switch layout {
        case .keyboard(let octave):
            let base = (octave + 1) * 12
            let width = bounds.width / 8
            for (index, key) in whites.enumerated() {
                cg.setFillColor(held[base + key] == nil ? UIColor(white: 0.9, alpha: 1).cgColor : gold.cgColor)
                cg.fill(CGRect(x: CGFloat(index) * width, y: 0, width: width - 1, height: bounds.height))
            }
            for (index, key) in blacks {
                cg.setFillColor(held[base + key] == nil ? UIColor(white: 0.08, alpha: 1).cgColor : gold.cgColor)
                cg.fill(CGRect(x: (CGFloat(index) + 0.69) * width, y: 0, width: width * 0.62, height: bounds.height * 0.58))
            }
        default:
            cg.setFillColor(UIColor(red: 0.19, green: 0.13, blue: 0.10, alpha: 1).cgColor)
            cg.fill(bounds)
            let row = bounds.height / 6
            for string in 0..<6 {
                let y = (CGFloat(string) + 0.5) * row
                cg.setStrokeColor(gold.withAlphaComponent(0.65).cgColor)
                cg.setLineWidth(CGFloat(string) * 0.19 + 0.7)
                cg.move(to: CGPoint(x: 0, y: y))
                cg.addLine(to: CGPoint(x: bounds.width, y: y))
                cg.strokePath()
                label(stringNames[string], at: CGPoint(x: 7, y: y - 18))
                if case .chord(let chord) = layout {
                    let position = chords[chord].1[string]
                    label(position < 0 ? "×" : "\(position)", at: CGPoint(x: bounds.width / 2, y: y - 20))
                }
            }
            if case .fret(let first) = layout {
                let width = bounds.width / 5
                for column in 0..<5 {
                    let x = width * CGFloat(column)
                    cg.setFillColor(UIColor.white.withAlphaComponent(0.25).cgColor)
                    cg.fill(CGRect(x: x, y: 0, width: 1, height: bounds.height))
                    label("\(first + column)", at: CGPoint(x: x + width / 2, y: 4))
                    for string in 0..<6 where held[openStrings[string] + first + column] != nil {
                        cg.setFillColor(gold.cgColor)
                        cg.fillEllipse(in: CGRect(x: x + width / 2 - 6, y: row * (CGFloat(string) + 0.5) - 6, width: 12, height: 12))
                    }
                }
            }
        }
    }

    private func label(_ text: String, at point: CGPoint) {
        (text as NSString).draw(at: point, withAttributes: [.font: UIFont.monospacedSystemFont(ofSize: 12, weight: .medium), .foregroundColor: UIColor.white])
    }

    private func hit(_ point: CGPoint) -> Int? {
        guard bounds.contains(point), bounds.width > 0 else { return nil }
        switch layout {
        case .fret(let start):
            return openStrings[min(5, Int(point.y / bounds.height * 6))] + start + min(4, Int(point.x / bounds.width * 5))
        case .chord:
            return min(5, Int(point.y / bounds.height * 6))
        case .keyboard(let octave):
            let x = point.x / bounds.width * 8
            let base = (octave + 1) * 12
            if point.y < bounds.height * 0.58,
               let black = blacks.first(where: { abs(x - Double($0.0 + 1)) < 0.31 }) { return base + black.1 }
            return base + whites[min(7, Int(x))]
        }
    }

    private func move(_ touches: Set<UITouch>) {
        for touch in touches {
            let previous = active[touch]
            let next = hit(touch.location(in: self))
            guard previous != next else { continue }
            active[touch] = next
            if case .chord(let chord) = layout {
                if let next {
                    let rows: [Int]
                    if let previous {
                        rows = Array(stride(from: previous + (next > previous ? 1 : -1), through: next, by: next > previous ? 1 : -1))
                    } else { rows = [next] }
                    for string in rows {
                        let fret = chords[chord].1[string]
                        if fret >= 0 { model?.pluck(UInt8(openStrings[string] + fret)) }
                    }
                }
            } else {
                if let previous { release(previous) }
                if let next {
                    let count = held[next, default: 0]
                    held[next] = count + 1
                    if count == 0 { model?.note(UInt8(next), 96) }
                }
            }
        }
        setNeedsDisplay()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        var parent = superview
        while let view = parent {
            if let scrollView = view as? UIScrollView {
                scroll = scrollView
                scrollView.delaysContentTouches = false
                break
            }
            parent = view.superview
        }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let scroll, scroll.isScrollEnabled {
            scroll.isScrollEnabled = false
            scrollLocked = true
        }
        move(touches)
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) { move(touches) }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { end(touches) }

    private func end(_ touches: Set<UITouch>) {
        for touch in touches {
            if let note = active.removeValue(forKey: touch) {
                if case .chord = layout {} else { release(note) }
            }
        }
        if active.isEmpty { unlockScroll() }
        setNeedsDisplay()
    }

    private func release(_ note: Int) {
        guard let count = held[note] else { return }
        if count == 1 {
            held[note] = nil
            model?.note(UInt8(note), 0)
        } else { held[note] = count - 1 }
    }

    func release() {
        active.removeAll()
        held.removeAll()
        model?.releaseKeys()
        unlockScroll()
    }

    private func unlockScroll() {
        guard scrollLocked else { return }
        scroll?.isScrollEnabled = true
        scrollLocked = false
    }
}

private struct ClassicGuitarPanel: UIViewRepresentable {
    enum Part: String {
        case controls = "index", studio
    }
    let model: Hendrix
    let part: Part
    @Binding var height: CGFloat

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.userContentController.add(context.coordinator, name: "classicGuitar")
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = UIColor(red: 0.008, green: 0.051, blue: 0.075, alpha: 1)
        view.scrollView.isScrollEnabled = false
        context.coordinator.webView = view
        if let page = context.coordinator.page {
            view.loadFileURL(page, allowingReadAccessTo: page.deletingLastPathComponent())
        }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.panel = self
        context.coordinator.updateMetrics()
    }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        coordinator.setDragging(false)
        view.configuration.userContentController.removeScriptMessageHandler(forName: "classicGuitar")
        view.stopLoading()
    }

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        var panel: ClassicGuitarPanel
        weak var webView: WKWebView?
        let page: URL?
        private var ready = false
        private var lastMetrics: String?
        private weak var parentScrollView: UIScrollView?
        private let links = [
            "https://moonshroom.moonshroom303.chatgpt.site/",
            "https://www.soundcloud.com/moonshroom303",
            "https://www.youtube.com/@moonshroom303",
            "https://shroomsloops-studio-231026.moonshroom303.chatgpt.site/#",
        ]

        init(_ panel: ClassicGuitarPanel) {
            self.panel = panel
            page = Bundle.main.url(forResource: panel.part.rawValue, withExtension: "html", subdirectory: "classic")
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            let url = action.request.url
            decisionHandler(url == page && url != nil ? .allow : .cancel)
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "classicGuitar", message.frameInfo.isMainFrame,
                  message.frameInfo.request.url?.isFileURL == true,
                  let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
            switch type {
            case "ready" where panel.part == .controls:
                ready = true
                let saved = panel.model.guitarState ?? [:]
                webView?.callAsyncJavaScript("window.configureClassic(saved)", arguments: ["saved": saved],
                                              in: nil, in: .page, completionHandler: nil)
                updateMetrics()
            case "height":
                if let value = body["height"] as? NSNumber, (200...10_000).contains(value.intValue) {
                    let height = CGFloat(value.intValue)
                    if panel.height != height { panel.height = height }
                }
            case "state" where panel.part == .controls:
                if let state = body["state"] as? [String: Any] { panel.model.saveGuitarState(state) }
            case "parameters" where panel.part == .controls:
                if let values = body["values"] as? [NSNumber] { panel.model.updateGuitarParameters(values) }
            case "drag" where panel.part == .controls:
                setDragging(body["active"] as? Bool == true)
            case "link" where panel.part == .studio:
                if let index = body["index"] as? Int, links.indices.contains(index),
                   let url = URL(string: links[index]) { UIApplication.shared.open(url) }
            default:
                break
            }
        }

        func setDragging(_ dragging: Bool) {
            if dragging {
                var parent = webView?.superview
                while let view = parent {
                    if let scroll = view as? UIScrollView {
                        parentScrollView = scroll
                        scroll.isScrollEnabled = false
                        break
                    }
                    parent = view.superview
                }
            } else {
                parentScrollView?.isScrollEnabled = true
                parentScrollView = nil
            }
        }

        func updateMetrics() {
            guard ready else { return }
            let data = panel.model.guitarMetrics ?? #"{"level":-120,"window":0,"bend":0,"attacks":0,"notes":[]}"#
            guard lastMetrics != data else { return }
            lastMetrics = data
            webView?.evaluateJavaScript("window.classicMetrics(\(data))")
        }
    }
}
