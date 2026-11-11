import XCTest

final class InstrumentTests: XCTestCase {
    func testRecordGuitarChordsAndKeys() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["audioInput"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.webViews.staticTexts["PRESETBANK"].waitForExistence(timeout: 8))
        let record = app.buttons["record"]
        XCTAssertTrue(record.waitForExistence(timeout: 5))
        record.tap()

        let surface = app.descendants(matching: .any)["playingSurface"].firstMatch
        let detected = app.webViews.staticTexts["ERKANNTE NOTEN"]
        reveal(detected, in: app)
        XCTAssertLessThan(detected.frame.minY, surface.frame.minY)
        reveal(surface, in: app)
        surface.coordinate(withNormalizedOffset: CGVector(dx: 0.62, dy: 0.34)).press(forDuration: 0.20)
        reveal(app.buttons["Akkorde"], in: app)
        app.buttons["Akkorde"].tap()
        reveal(surface, in: app)
        let top = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.92))
        let bottom = surface.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.08))
        top.press(forDuration: 0.10, thenDragTo: bottom)
        reveal(app.buttons["Keys"], in: app)
        app.buttons["Keys"].tap()
        reveal(surface, in: app)
        surface.coordinate(withNormalizedOffset: CGVector(dx: 0.52, dy: 0.81)).press(forDuration: 0.20)

        let logo = app.webViews.links["Moonshroom Studio · Website"].firstMatch
        reveal(logo, in: app)
        XCTAssertGreaterThan(logo.frame.minY, surface.frame.minY)
        reveal(record, in: app)
        record.tap()
        let saved = app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH %@", "MIDI gespeichert")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 5))
        XCTAssertEqual(saved.label, "MIDI gespeichert · 7 Noten")
        XCTAssertTrue(app.buttons["MIDI teilen"].exists)
        XCTAssertTrue(app.buttons["Tabulatur"].exists)
        app.buttons["Tabulatur"].tap()
        XCTAssertTrue(app.staticTexts["Standardstimmung · Vorschläge für Einzeltöne"].exists)
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        let screen = app.frame.insetBy(dx: 0, dy: 74)
        for _ in 0..<14 {
            let frame = element.exists ? element.frame : .zero
            if element.isHittable, frame.minY >= screen.minY, frame.maxY <= screen.maxY { return }
            let backwards = !frame.isEmpty && frame.minY < screen.minY
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.025, dy: backwards ? 0.28 : 0.70))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.025, dy: backwards ? 0.70 : 0.28))
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        XCTFail("Bedienelement nicht erreichbar: \(element)")
    }
}
