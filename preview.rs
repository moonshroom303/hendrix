//! Interactive GUI and synthetic-signal preview. Hardware I/O is supplied by a host.
use hendrix_audio_to_midi::{Plugin, Processor, gui::Panel};
struct Preview {
    panel: Panel,
    processor: Processor,
    test_tone: bool,
    phase: f32,
    io: [Vec<f32>; 2],
}
impl Default for Preview {
    fn default() -> Self {
        let panel = Panel::default();
        let mut processor = Processor::new();
        processor.prepare(48000., 128, 2);
        for (i, p) in hendrix_audio_to_midi::INFO.params.iter().enumerate() {
            processor.set_param(p.id, panel.state.params[i]);
        }
        Self {
            panel,
            processor,
            test_tone: false,
            phase: 0.,
            io: [vec![0.; 128], vec![0.; 128]],
        }
    }
}
impl eframe::App for Preview {
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        egui::TopBottomPanel::top("demo").show(ctx, |ui| {
            ui.horizontal_wrapped(|ui| {
                ui.label("Hendrix · interface preview · synthetic input");
                ui.toggle_value(&mut self.test_tone, "Test A4");
            });
        });
        for _ in 0..6 {
            for i in 0..128 {
                self.phase = (self.phase + 440. / 48000.).fract();
                let x = if self.test_tone {
                    0.15 * (std::f32::consts::TAU * self.phase).sin()
                } else {
                    0.
                };
                self.io[0][i] = x;
                self.io[1][i] = x;
            }
            self.processor.process(&mut self.io, 128);
        }
        egui::CentralPanel::default().show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| {
                if self.panel.show(
                    ui,
                    &self.processor.output,
                    &[(1, "Host MIDI destination".into())],
                ) {
                    for (i, p) in hendrix_audio_to_midi::INFO.params.iter().enumerate() {
                        self.processor.set_param(p.id, self.panel.state.params[i]);
                    }
                }
            });
        });
        ctx.request_repaint_after(std::time::Duration::from_millis(16));
    }
}
fn main() -> eframe::Result {
    eframe::run_native(
        "Hendrix · Moonshroom Studio",
        eframe::NativeOptions {
            viewport: egui::ViewportBuilder::default().with_inner_size([1100., 950.]),
            ..Default::default()
        },
        Box::new(|_| Ok(Box::new(Preview::default()))),
    )
}
