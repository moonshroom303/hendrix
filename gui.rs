//! Embeddable egui panel. Hosts own audio devices, MIDI destinations and project persistence.
use crate::{INFO, Output, branding, presets};
use egui::{Color32, Rect, Sense, Stroke, Vec2};
use std::sync::atomic::Ordering::{Acquire, Release};
const LIME: Color32 = Color32::from_rgb(166, 255, 72);
const CYAN: Color32 = Color32::from_rgb(69, 230, 238);
const VIOLET: Color32 = Color32::from_rgb(207, 119, 249);
const FIRE: Color32 = Color32::from_rgb(255, 175, 83);
const INK: Color32 = Color32::from_rgb(228, 239, 235);
#[derive(Clone, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProjectState {
    pub params: [f32; 20],
    pub bank: presets::Bank,
    pub target: u64,
}
impl Default for ProjectState {
    fn default() -> Self {
        let params = [
            1., 1., -72., 20.5, 69.1, 27., 108., 1., 431.4, 0., 0., 1., 1., 1., 0., 4.9, 0., 1.,
            3., 10.,
        ];
        let mut bank = presets::Bank::default();
        bank.save(
            "My Guitar",
            INFO.params
                .iter()
                .enumerate()
                .map(|(i, p)| (p.id.into(), params[i]))
                .collect(),
            true,
        )
        .expect("valid bundled preset");
        Self {
            params,
            bank,
            target: 0,
        }
    }
}
impl ProjectState {
    pub fn validate(&self) -> Result<(), String> {
        self.bank.validate()?;
        presets::validate_values(
            &INFO
                .params
                .iter()
                .enumerate()
                .map(|(i, p)| (p.id.into(), self.params[i]))
                .collect(),
        )
    }
}
pub struct Panel {
    pub state: ProjectState,
    name: String,
    selected: Option<u64>,
    message: String,
}
impl Default for Panel {
    fn default() -> Self {
        Self {
            state: ProjectState::default(),
            name: "My Guitar".into(),
            selected: Some(1),
            message: String::new(),
        }
    }
}
fn section(ui: &mut egui::Ui, title: &str, color: Color32, body: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::new()
        .fill(Color32::from_rgba_unmultiplied(4, 15, 22, 234))
        .stroke(Stroke::new(1., color.gamma_multiply(0.3)))
        .corner_radius(7)
        .inner_margin(11)
        .show(ui, |ui| {
            ui.colored_label(color, egui::RichText::new(title).size(11.).strong());
            ui.add_space(7.);
            body(ui);
        });
    ui.add_space(8.);
}
fn note_name(n: f32) -> String {
    let n = n.round() as i32;
    format!(
        "{}{} · {}",
        [
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
        ][n.rem_euclid(12) as usize],
        n / 12 - 1,
        n
    )
}
fn knob(ui: &mut egui::Ui, values: &mut [f32; 20], index: usize, label: &str, color: Color32) {
    let p = &INFO.params[index];
    ui.vertical_centered(|ui| {
        ui.label(egui::RichText::new(label).size(12.));
        let mut t = p.to_normalized(values[index]);
        let (rect, r) = ui.allocate_exact_size(Vec2::splat(48.), Sense::click_and_drag());
        if r.clicked() {
            r.request_focus();
        }
        if r.dragged() {
            let delta = ui.input(|i| i.pointer.delta());
            t = (t
                + (delta.x - delta.y) / 180.
                    * if ui.input(|i| i.modifiers.shift) {
                        0.12
                    } else {
                        1.
                    })
            .clamp(0., 1.);
        }
        if r.double_clicked() || r.clicked() && ui.input(|i| i.modifiers.alt) {
            t = p.to_normalized(p.default);
        }
        if r.has_focus() {
            let delta = ui.input(|i| {
                (i.key_pressed(egui::Key::ArrowUp) || i.key_pressed(egui::Key::ArrowRight)) as i32
                    - (i.key_pressed(egui::Key::ArrowDown) || i.key_pressed(egui::Key::ArrowLeft))
                        as i32
            });
            t = (t + delta as f32
                * if ui.input(|i| i.modifiers.shift) {
                    0.001
                } else {
                    0.01
                })
            .clamp(0., 1.);
        }
        values[index] = p.from_normalized(t);
        let painter = ui.painter_at(rect);
        let c = rect.center();
        painter.circle_filled(c, 18., Color32::from_rgb(6, 22, 30));
        painter.circle_stroke(c, 20., Stroke::new(1., color.gamma_multiply(0.3)));
        let points = (0..=48)
            .map(|i| {
                let a =
                    std::f32::consts::PI * 0.75 + std::f32::consts::PI * 1.5 * t * i as f32 / 48.;
                c + egui::vec2(a.cos(), a.sin()) * 20.
            })
            .collect();
        painter.add(egui::Shape::line(points, Stroke::new(2., color)));
        let a = std::f32::consts::PI * 0.75 + std::f32::consts::PI * 1.5 * t;
        painter.line_segment(
            [
                c + egui::vec2(a.cos(), a.sin()) * 8.,
                c + egui::vec2(a.cos(), a.sin()) * 16.,
            ],
            Stroke::new(2., INK),
        );
        let text = match index {
            5 | 6 => note_name(values[index]),
            7 => format!("{}", values[index].round()),
            10 if values[index] < 1. => "Dynamic".into(),
            _ => p.format(values[index]),
        };
        ui.label(egui::RichText::new(text).monospace().size(12.));
    });
}
impl Panel {
    /// Returns true when settings or routing changed. Apply parameters on the host's safe boundary.
    pub fn show(
        &mut self,
        ui: &mut egui::Ui,
        output: &Output,
        destinations: &[(u64, String)],
    ) -> bool {
        let before = self.state.params;
        let before_bank = self.state.bank.clone();
        let old_target = self.state.target;
        let image_key = egui::Id::new("hendrix-public-portrait");
        let texture = ui
            .ctx()
            .data(|d| d.get_temp::<egui::TextureHandle>(image_key))
            .unwrap_or_else(|| {
                let pixels = image::load_from_memory(include_bytes!("hendrix-fire.png"))
                    .expect("bundled image")
                    .to_rgba8();
                let t = ui.ctx().load_texture(
                    "hendrix-portrait",
                    egui::ColorImage::from_rgba_unmultiplied(
                        [pixels.width() as usize, pixels.height() as usize],
                        pixels.as_raw(),
                    ),
                    egui::TextureOptions::LINEAR,
                );
                ui.ctx().data_mut(|d| d.insert_temp(image_key, t.clone()));
                t
            });
        let width = ui.available_width().max(280.);
        let top = ui.cursor().top();
        let x = ui.min_rect().left();
        let art = Rect::from_min_size(egui::pos2(x, top), egui::vec2(width, width / 1.5));
        ui.painter().rect_filled(
            ui.available_rect_before_wrap(),
            8.,
            Color32::from_rgb(3, 13, 19),
        );
        ui.painter().image(
            texture.id(),
            art,
            Rect::from_min_max(egui::Pos2::ZERO, egui::pos2(1., 1.)),
            Color32::from_white_alpha(180),
        );
        ui.label(egui::RichText::new("HENDRIX").size(42.).color(INK));
        ui.label(
            egui::RichText::new("REALTIME AUDIO / MIDI")
                .monospace()
                .size(11.)
                .color(LIME),
        );
        ui.add_space(14.);
        let controls_width = if width >= 880. { width * 0.61 } else { width };
        ui.allocate_ui_with_layout(egui::vec2(controls_width,0.),egui::Layout::top_down(egui::Align::Min),|ui|{
            ui.set_width(controls_width);ui.visuals_mut().override_text_color=Some(INK);
            let mut load=None;let mut save=false;let mut standard=false;let mut delete=false;
            section(ui,"PRESET BANK",VIOLET,|ui|{
                ui.horizontal_wrapped(|ui|{
                    let selected=self.selected.and_then(|id|self.state.bank.get(id)).map_or("Current settings",|p|p.name.as_str());
                    egui::ComboBox::from_id_salt("hendrix-bank").width(190.).selected_text(selected).show_ui(ui,|ui|{if ui.selectable_label(false,"Factory settings").clicked(){load=Some(0);}
                        for p in &self.state.bank.entries{let text=if Some(p.id)==self.state.bank.default{format!("{} · Default",p.name)}else{p.name.clone()};if ui.selectable_label(self.selected==Some(p.id),text).clicked(){load=Some(p.id);}}
                    });save=ui.button("Save").clicked();standard=ui.button("Set as Default").clicked();delete=ui.add_enabled(self.selected.is_some(),egui::Button::new("Delete")).clicked();
                });
                ui.horizontal(|ui|{ui.label("Name");ui.add(egui::TextEdit::singleline(&mut self.name).char_limit(80).desired_width(220.));});
                ui.small("Stored with the project. Presets preserve the MIDI destination.");if !self.message.is_empty(){ui.colored_label(VIOLET,&self.message);}
            });
            if let Some(id)=load{let p=self.state.bank.get(id);let values=p.map_or_else(presets::factory,|p|p.params.clone());for(i,d)in INFO.params.iter().enumerate(){self.state.params[i]=values.get(d.id).copied().unwrap_or(d.default);}self.selected=p.map(|p|p.id);if let Some(p)=p{self.name=p.name.clone();}}
            if save||standard{let values=INFO.params.iter().enumerate().map(|(i,p)|(p.id.into(),self.state.params[i])).collect();match self.state.bank.save(&self.name,values,standard){Ok(id)=>{self.selected=Some(id);self.message=if standard{"Project default saved"}else{"Preset saved"}.into();},Err(e)=>self.message=e}}
            if delete{if let Some(id)=self.selected.take(){self.state.bank.remove(id);self.message="Preset deleted".into();}}
            section(ui,"MOONSHROOM STUDIO",LIME,branding::show);
            section(ui,"SIGNAL & MIDI OUT",CYAN,|ui|{
                egui::ComboBox::from_id_salt("hendrix-destination").width(ui.available_width()).selected_text(destinations.iter().find(|(id,_)|*id==self.state.target).map_or("Choose MIDI destination",|(_,name)|name.as_str())).show_ui(ui,|ui|{for(id,name)in destinations{ui.selectable_value(&mut self.state.target,*id,name);}});
                ui.horizontal_wrapped(|ui|{for(i,label)in["MONO","POLY"].iter().enumerate(){if ui.selectable_label(self.state.params[0].round() as usize==i,*label).clicked(){self.state.params[0]=i as f32;}}let mut send=self.state.params[1]>=0.5;if ui.toggle_value(&mut send,"MIDI SEND").changed(){self.state.params[1]=send as u8 as f32;}let mut dry=self.state.params[12]>=0.5;if ui.checkbox(&mut dry,"Audio Passthrough").changed(){self.state.params[12]=dry as u8 as f32;}});
                let db=f32::from_bits(output.level.load(Acquire));ui.add(egui::ProgressBar::new(((db+72.)/72.).clamp(0.,1.)).desired_height(6.).fill(CYAN));ui.label(egui::RichText::new(format!("IN {db:.1} dB · ANALYSIS {:.1} ms",f32::from_bits(output.window_ms.load(Acquire)))).monospace().size(11.));
                ui.collapsing("Verification",|ui|{for(i,label)in["Fast","Balanced","Accurate"].iter().enumerate(){if ui.selectable_label(self.state.params[11].round() as usize==i,*label).clicked(){self.state.params[11]=i as f32;}}});
            });
            section(ui,"PICKING & TIMING",FIRE,|ui|{
                ui.add_enabled_ui(self.state.params[0]<0.5,|ui|{
                    ui.horizontal_wrapped(|ui|{for(i,label)in["Direct","5 ms","10 ms","15 ms"].iter().enumerate(){if ui.selectable_label(self.state.params[16].round() as usize==i,*label).clicked(){self.state.params[16]=i as f32;}}});
                    let mut on=self.state.params[17]>=0.5;if ui.toggle_value(&mut on,"RETRIGGER").changed(){self.state.params[17]=on as u8 as f32;}
                    ui.columns(2,|c|{knob(&mut c[0],&mut self.state.params,18,"Pick Threshold",FIRE);knob(&mut c[1],&mut self.state.params,19,"Minimum Interval",FIRE);});
                });ui.small("Mono only. Timed modes reject pitches recognized after the deadline.");
            });
            section(ui,"DETECTION",CYAN,|ui|{ui.columns(4,|cols|{for(c,(index,label))in cols.iter_mut().zip([(2,"Threshold"),(3,"Stability"),(4,"Release"),(7,"Voices")]){knob(c,&mut self.state.params,index,label,CYAN);}});});
            section(ui,"NOTES & TUNING",VIOLET,|ui|{
                ui.columns(4,|cols|{for(c,(index,label))in cols.iter_mut().zip([(5,"Lowest Note"),(6,"Highest Note"),(8,"Reference A4"),(9,"Transpose")]){knob(c,&mut self.state.params,index,label,VIOLET);}});
                if self.state.params[0]<0.5&&self.state.params[16]<0.5{let period=1000./(self.state.params[8]*2f32.powf((self.state.params[5].min(self.state.params[6])-69.)/12.));ui.colored_label(FIRE,format!("Lowest note: ~{period:.1}–{:.1} ms recognition",period*1.5)).on_hover_text("One to one-and-a-half waveform periods. Hardware buffers and the instrument add latency. Higher notes are recognized faster.");}
            });
            section(ui,"EXPRESSION",FIRE,|ui|{
                ui.add_enabled_ui(self.state.params[0]<0.5,|ui|{let mut bends=self.state.params[13]>=0.5;if ui.toggle_value(&mut bends,"MONO BENDS").changed(){self.state.params[13]=bends as u8 as f32;}ui.horizontal(|ui|{for(i,label)in["±2 st","±12 st","±24 st"].iter().enumerate(){if ui.selectable_label(self.state.params[14].round() as usize==i,*label).clicked(){self.state.params[14]=i as f32;}}});});
                ui.columns(2,|c|{knob(&mut c[0],&mut self.state.params,15,"Smoothing",FIRE);knob(&mut c[1],&mut self.state.params,10,"Velocity",FIRE);});ui.colored_label(FIRE,egui::RichText::new(format!("{:+.2} st",f32::from_bits(output.bend.load(Acquire)))).monospace().size(24.));ui.small("Match the destination's pitch-bend range. Pitch bend affects the whole MIDI channel.");
            });
            section(ui,"DETECTED NOTES",LIME,|ui|{
                let names:Vec<_>=output.notes.iter().enumerate().filter(|(_,n)|n.load(Acquire)&255>0).map(|(n,_)|note_name(n as f32)).collect();ui.label(if names.is_empty(){"Play a note or chord".into()}else{names.join(" · ")});
                let(r,_)=ui.allocate_exact_size(egui::vec2(ui.available_width(),36.),Sense::hover());let w=r.width()/48.;for note in 36..84{let active=output.notes[note].load(Acquire)&255>0;let rect=Rect::from_min_size(r.min+egui::vec2((note-36) as f32*w,0.),egui::vec2((w-1.).max(1.),36.));ui.painter().rect_filled(rect,2.,if active{LIME}else{Color32::from_rgb(25,48,58)});}
            });
        });
        if old_target != self.state.target {
            output.target.store(self.state.target, Release);
        }
        before != self.state.params
            || old_target != self.state.target
            || before_bank != self.state.bank
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn project_state_roundtrip_preserves_presets_and_destination() {
        let state = ProjectState::default();
        state.validate().unwrap();
        let mut restored: ProjectState =
            serde_json::from_slice(&serde_json::to_vec(&state).unwrap()).unwrap();
        restored.target = 71;
        let before = restored.target;
        let preset = restored.bank.initial().unwrap();
        assert_eq!(preset["3"], 20.5);
        assert_eq!(restored.target, before);
        assert_eq!(restored.params[4], 69.1);
    }
    mod snapshot {
        include!("software_preview.rs");
    }
    #[test]
    fn render_public_preview() {
        let Some(path) = std::env::var_os("HENDRIX_PREVIEW_IMAGE") else {
            return;
        };
        let ctx = egui::Context::default();
        let mut panel = Panel::default();
        let output = Output::default();
        let mut image = snapshot::Snapshot::default();
        let mut last = None;
        for _ in 0..5 {
            let out = ctx.run(
                egui::RawInput {
                    screen_rect: Some(Rect::from_min_size(
                        egui::Pos2::ZERO,
                        egui::vec2(1100., 1600.),
                    )),
                    ..Default::default()
                },
                |ctx| {
                    egui::CentralPanel::default().show(ctx, |ui| {
                        panel.show(ui, &output, &[(2, "Host MIDI destination".into())]);
                    });
                },
            );
            image.textures(&out);
            last = Some(out);
        }
        image.save(
            &ctx,
            last.unwrap(),
            [1100, 1600],
            std::path::Path::new(&path),
        );
    }
    #[test]
    fn panel_renders_at_narrow_and_wide_sizes() {
        for width in [520., 1280.] {
            let ctx = egui::Context::default();
            let mut panel = Panel::default();
            let output = Output::default();
            for _ in 0..3 {
                let _ = ctx.run(
                    egui::RawInput {
                        screen_rect: Some(Rect::from_min_size(
                            egui::Pos2::ZERO,
                            egui::vec2(width, 2200.),
                        )),
                        ..Default::default()
                    },
                    |ctx| {
                        egui::CentralPanel::default().show(ctx, |ui| {
                            egui::ScrollArea::vertical().show(ui, |ui| {
                                panel.show(ui, &output, &[]);
                            });
                        });
                    },
                );
            }
            panel.state.validate().unwrap();
        }
    }
}
