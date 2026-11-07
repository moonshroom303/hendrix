//! CLAP and VST3 host adapter. The detector and MIDI note ownership are shared with the AU.
use crate::{INFO, Output, Plugin as EnginePlugin, Processor, midi::MidiReader, presets::Bank};
use nice_plug::{context::gui::GuiContext, editor::dpi::LogicalSize, prelude::*};
use nice_plug_egui::{
    EguiEditor, EguiEditorState, EguiNiceSettings, Frame, NiceEguiApp, RepaintNotifier, baseview,
    create_egui_editor, widgets::ParamSlider,
};
use portable_egui as egui;
use std::sync::{Arc, RwLock, atomic::Ordering::Acquire};

const PARAM_COUNT: usize = 20;
const BLOCK: usize = 32;
// Match the native 1.0 factory preset in Hendrix.cpp.
const DEFAULTS: [f32; PARAM_COUNT] = [
    1., 1., -72., 20.5, 69.1, 27., 108., 1., 431.4, 0., 0., 1., 1., 1., 0., 4.9, 0., 1., 3., 10.,
];
const LABELS: [&str; PARAM_COUNT] = [
    "Detection mode",
    "MIDI send",
    "Threshold",
    "Stability",
    "Release",
    "Lowest note",
    "Highest note",
    "Voices",
    "Reference A4",
    "Transpose",
    "Velocity (0 = dynamic)",
    "Verification",
    "Audio passthrough",
    "Mono bends",
    "Bend range",
    "Bend smoothing",
    "Acquisition",
    "Retrigger",
    "Pick threshold",
    "Minimum interval",
];

#[derive(Params)]
struct Control {
    #[id = "value"]
    value: FloatParam,
}

#[derive(Params)]
struct HendrixParams {
    #[nested(array)]
    controls: [Control; PARAM_COUNT],
    #[persist = "presets"]
    presets: RwLock<Bank>,
    editor: Arc<EguiEditorState>,
}

impl HendrixParams {
    fn read(&self) -> [f32; PARAM_COUNT] {
        std::array::from_fn(|i| INFO.params[i].clamp(self.controls[i].value.value()))
    }

    fn new() -> Self {
        let mut bank = Bank::default();
        let factory = INFO
            .params
            .iter()
            .zip(DEFAULTS)
            .map(|(p, value)| (p.id.to_owned(), value))
            .collect();
        bank.save("My Guitar", factory, true)
            .expect("valid factory preset");
        Self {
            controls: std::array::from_fn(|i| {
                let info = &INFO.params[i];
                let value = FloatParam::new(
                    LABELS[i],
                    DEFAULTS[i],
                    FloatRange::Linear {
                        min: info.min,
                        max: info.max,
                    },
                )
                .with_value_to_string(Arc::new(move |v| match i {
                    0 => ["Mono", "Poly"][v.round().clamp(0., 1.) as usize].into(),
                    11 => ["Fast", "Balanced", "Accurate"][v.round().clamp(0., 2.) as usize].into(),
                    14 => ["±2 st", "±12 st", "±24 st"][v.round().clamp(0., 2.) as usize].into(),
                    16 => ["Direct", "5 ms", "10 ms", "15 ms"][v.round().clamp(0., 3.) as usize]
                        .into(),
                    _ => info.format(v),
                }));
                let value = if matches!(i, 0 | 1 | 5..=7 | 9..=14 | 16 | 17) {
                    value.with_step_size(1.)
                } else {
                    value.with_step_size(0.01)
                };
                Control { value }
            }),
            presets: RwLock::new(bank),
            editor: EguiEditorState::from_size(LogicalSize::new(900, 860), 1.),
        }
    }
}

struct Hendrix {
    params: Arc<HendrixParams>,
    engine: Processor,
    midi: MidiReader,
    scratch: [Vec<f32>; 2],
    flush_pending: bool,
}

impl Default for Hendrix {
    fn default() -> Self {
        Self {
            params: Arc::new(HendrixParams::new()),
            engine: Processor::new(),
            midi: MidiReader::default(),
            scratch: std::array::from_fn(|_| vec![0.; BLOCK]),
            flush_pending: false,
        }
    }
}

impl Plugin for Hendrix {
    const NAME: &'static str = "Hendrix";
    const VENDOR: &'static str = "Moonshroom Studio";
    const URL: &'static str = "https://github.com/moonshroom303/hendrix";
    const EMAIL: &'static str = "";
    const VERSION: &'static str = "1.0.0";
    const AUDIO_IO_LAYOUTS: &'static [AudioIOLayout] = &[
        AudioIOLayout {
            main_input_channels: NonZeroU32::new(2),
            main_output_channels: NonZeroU32::new(2),
            ..AudioIOLayout::const_default()
        },
        AudioIOLayout {
            main_input_channels: NonZeroU32::new(1),
            main_output_channels: NonZeroU32::new(1),
            ..AudioIOLayout::const_default()
        },
    ];
    const MIDI_OUTPUT: MidiConfig = MidiConfig::MidiCCs;
    type SysExMessage = ();
    type BackgroundTask = ();
    type Editor = EguiEditor<HendrixEditor>;

    fn params(&self) -> Arc<dyn Params> {
        self.params.clone()
    }

    fn editor(&mut self, _: AsyncExecutor<Self>) -> Option<Self::Editor> {
        create_egui_editor(
            self.params.editor.clone(),
            RepaintNotifier::default(),
            EguiNiceSettings::new().with_tile("Hendrix"),
            HendrixEditor {
                params: self.params.clone(),
                output: self.engine.output.clone(),
                context: None,
                photos: vec![],
                selected: Some(1),
                name: "My Guitar".into(),
                message: String::new(),
            },
        )
    }

    fn activate(
        &mut self,
        layout: &AudioIOLayout,
        config: &BufferConfig,
        _: &mut impl ActivateContext<Self>,
    ) -> bool {
        let channels = layout.main_output_channels.map_or(0, |n| n.get() as usize);
        self.engine.prepare(config.sample_rate, BLOCK, channels);
        true
    }

    fn reset(&mut self) {
        self.engine.reset();
        self.flush_pending = true;
    }

    fn stop_processing(&mut self) {
        self.reset();
    }

    fn process(
        &mut self,
        buffer: &mut Buffer,
        _: &mut AuxiliaryBuffers,
        context: &mut impl ProcessContext<Self>,
    ) -> ProcessStatus {
        let frames = buffer.samples();
        let channels = buffer.channels();
        if frames == 0 || !(1..=2).contains(&channels) {
            return ProcessStatus::Normal;
        }
        let values = self.params.read();
        for (p, value) in INFO.params.iter().zip(values) {
            self.engine.set_param(p.id, value);
        }
        let mut send = |timing, bytes: [u8; 3]| {
            if let Ok(event) = NoteEvent::from_midi(timing, &bytes) {
                let _ = context.try_send_event(event);
            }
        };
        if std::mem::take(&mut self.flush_pending) {
            self.midi.flush(|bytes| send(0, bytes));
        }
        let audio = buffer.as_slice();
        for offset in (0..frames).step_by(BLOCK) {
            let len = BLOCK.min(frames - offset);
            let range = offset..offset + len;
            for (scratch, channel) in self.scratch.iter_mut().zip(audio.iter()) {
                scratch[..len].copy_from_slice(&channel[range.clone()]);
            }
            self.engine.process(&mut self.scratch[..channels], len);
            for (scratch, channel) in self.scratch.iter().zip(audio.iter_mut()) {
                channel[range.clone()].copy_from_slice(&scratch[..len]);
            }
            self.midi.poll(&self.engine.output, &values, 0, |bytes| {
                send((range.end - 1) as u32, bytes);
            });
        }
        ProcessStatus::Normal
    }
}

impl ClapPlugin for Hendrix {
    const CLAP_ID: &'static str = "studio.moonshroom.hendrix";
    const CLAP_DESCRIPTION: Option<&'static str> = Some("Real-time audio-to-MIDI conversion");
    const CLAP_MANUAL_URL: Option<&'static str> = Some(Self::URL);
    const CLAP_SUPPORT_URL: Option<&'static str> = Some(Self::URL);
    const CLAP_FEATURES: &'static [ClapFeature] =
        &[ClapFeature::AudioEffect, ClapFeature::NoteEffect];
}

impl Vst3Plugin for Hendrix {
    // The portable VST3 uses its own state format and ID; the original Mac VST3 is retained.
    const VST3_CLASS_ID: [u8; 16] = *b"MoonShroomHndxV1";
    const VST3_SUBCATEGORIES: &'static [Vst3SubCategory] =
        &[Vst3SubCategory::Fx, Vst3SubCategory::Tools];
}

nice_export_clap!(Hendrix);
nice_export_vst3!(Hendrix);

struct HendrixEditor {
    params: Arc<HendrixParams>,
    output: Arc<Output>,
    context: Option<GuiContext>,
    photos: Vec<egui::TextureHandle>,
    selected: Option<u64>,
    name: String,
    message: String,
}

fn set(setter: &ParamSetter<'_>, control: &Control, value: f32) {
    setter.begin_set_parameter(&control.value);
    setter.set_parameter(&control.value, value);
    setter.end_set_parameter(&control.value);
}

impl NiceEguiApp for HendrixEditor {
    fn build(
        &mut self,
        egui: egui::Context,
        context: GuiContext,
        _: &mut Frame,
    ) -> Result<(), baseview::HandlerError> {
        egui.set_visuals(egui::Visuals::dark());
        self.context = Some(context);
        self.photos = [
            &include_bytes!("hendrix-live-01.png")[..],
            &include_bytes!("hendrix-live-02.png")[..],
            &include_bytes!("moonshroom-studio.png")[..],
        ]
        .into_iter()
        .filter_map(|bytes| {
            let img = image::load_from_memory(bytes).ok()?.into_rgba8();
            let pixels = egui::ColorImage::from_rgba_unmultiplied(
                [img.width() as usize, img.height() as usize],
                img.as_raw(),
            );
            Some(egui.load_texture("Hendrix artwork", pixels, egui::TextureOptions::LINEAR))
        })
        .collect();
        Ok(())
    }

    fn editor_closed(&mut self) {
        self.photos.clear();
        self.context = None;
    }

    fn ui(&mut self, ui: &mut egui::Ui, _: &mut Frame) {
        let Some(context) = self.context.as_ref() else {
            return;
        };
        let setter = context.param_setter();
        ui.heading("HENDRIX");
        ui.colored_label(egui::Color32::LIGHT_GREEN, "REALTIME AUDIO / MIDI");
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.columns(2, |cols| {
                let left = &mut cols[0];
                left.heading("Preset bank");
                let entries = self.params.presets.read().unwrap().entries.clone();
                let selected = entries.iter().find(|p| Some(p.id) == self.selected);
                egui::ComboBox::from_id_salt("hendrix-preset")
                    .selected_text(selected.map_or("Choose a preset", |p| p.name.as_str()))
                    .show_ui(left, |ui| {
                        for entry in &entries {
                            if ui
                                .selectable_label(Some(entry.id) == self.selected, &entry.name)
                                .clicked()
                            {
                                self.selected = Some(entry.id);
                                self.name.clone_from(&entry.name);
                                for (i, p) in INFO.params.iter().enumerate() {
                                    if let Some(&v) = entry.params.get(p.id) {
                                        set(&setter, &self.params.controls[i], p.clamp(v));
                                    }
                                }
                            }
                        }
                    });
                left.horizontal_wrapped(|ui| {
                    ui.text_edit_singleline(&mut self.name);
                    if ui.button("Save").clicked() {
                        let values = INFO
                            .params
                            .iter()
                            .zip(self.params.read())
                            .map(|(p, v)| (p.id.to_owned(), v))
                            .collect();
                        match self
                            .params
                            .presets
                            .write()
                            .unwrap()
                            .save(&self.name, values, false)
                        {
                            Ok(id) => {
                                self.selected = Some(id);
                                self.message.clear();
                            }
                            Err(message) => self.message = message,
                        }
                    }
                    if ui.button("Delete").clicked() {
                        if let Some(id) = self.selected.take() {
                            self.params.presets.write().unwrap().remove(id);
                        }
                    }
                });
                if !self.message.is_empty() {
                    left.label(&self.message);
                }
                left.separator();
                for (i, label) in LABELS.iter().enumerate() {
                    let control = &self.params.controls[i];
                    left.horizontal(|ui| {
                        ui.add(ParamSlider::for_param(&control.value, &setter).with_width(126.));
                        ui.label(*label);
                    });
                    if i == 8 {
                        left.horizontal(|ui| {
                            for hz in [432., 440.] {
                                if ui
                                    .selectable_label(
                                        (control.value.value() - hz).abs() < 0.005,
                                        format!("{hz:.0} Hz"),
                                    )
                                    .clicked()
                                {
                                    set(&setter, control, hz);
                                }
                            }
                        });
                    }
                }
                left.separator();
                let level = f32::from_bits(self.output.level.load(Acquire));
                left.label(format!("Input {level:.1} dB"));
                let notes: Vec<_> = self
                    .output
                    .notes
                    .iter()
                    .enumerate()
                    .filter(|(_, note)| note.load(Acquire) & 255 != 0)
                    .map(|(number, _)| number.to_string())
                    .collect();
                left.label(if notes.is_empty() {
                    "Play a note or a chord".to_owned()
                } else {
                    format!("MIDI notes: {}", notes.join(" · "))
                });
                let right = &mut cols[1];
                for (i, photo) in self.photos.iter().enumerate() {
                    let width = right
                        .available_width()
                        .min(if i == 2 { 176. } else { 340. });
                    right.add(egui::Image::new(photo).max_width(width));
                }
                right.hyperlink_to(
                    "Moonshroom Studio",
                    "https://moonshroom.moonshroom303.chatgpt.site/",
                );
                right.hyperlink_to("SoundCloud", "https://www.soundcloud.com/moonshroom303");
                right.hyperlink_to("YouTube", "https://www.youtube.com/@moonshroom303");
                right.hyperlink_to(
                    "ShroomsLoops",
                    "https://shroomsloops-studio-231026.moonshroom303.chatgpt.site/#",
                );
            });
        });
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_millis(67));
    }
}
