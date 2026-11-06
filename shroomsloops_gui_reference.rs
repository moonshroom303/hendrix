//! Hendrix's original artwork and interactive front panel. All processing remains in audio_to_midi.
use super::*;
use std::sync::Arc;
use egui::{Align,FontId,Rect,Sense,Stroke,StrokeKind,Vec2};
const LIME:Color32=Color32::from_rgb(166,255,72);
const CYAN:Color32=Color32::from_rgb(69,230,238);
const VIOLET:Color32=Color32::from_rgb(207,119,249);
const FIRE:Color32=Color32::from_rgb(255,175,83);
const INK:Color32=Color32::from_rgb(228,239,235);
const MUTED:Color32=Color32::from_rgb(139,165,167);
#[derive(Clone,Default)]
struct PresetUi{selected:Option<u64>,name:String,message:Option<String>}
fn button(ui:&mut egui::Ui,label:&str,selected:bool,color:Color32,size:Vec2)->egui::Response{
    let r=ui.add_sized(size,egui::Button::new("").selected(selected));
    laser_rack::button_face(ui,&r,color,selected);
    ui.painter_at(r.rect).text(r.rect.center(),egui::Align2::CENTER_CENTER,label,FontId::proportional(12.),if ui.is_enabled(){INK}else{MUTED});
    r.widget_info(||egui::WidgetInfo::selected(egui::WidgetType::Button,ui.is_enabled(),selected,label));r
}

fn section<R>(ui:&mut egui::Ui,title:&str,color:Color32,body:impl FnOnce(&mut egui::Ui)->R)->egui::InnerResponse<R>{
    egui::Frame::new().fill(Color32::from_rgba_unmultiplied(4,15,22,232))
        .stroke(Stroke::new(1.,color.gamma_multiply(0.24))).corner_radius(7).inner_margin(11).show(ui,|ui|{
            ui.horizontal(|ui|{let label=ui.colored_label(color,egui::RichText::new(title).size(11.).strong());ui.add_space(4.);let r=ui.available_rect_before_wrap();ui.painter().hline(r.left()..=r.right(),label.rect.center().y,Stroke::new(0.5,color.gamma_multiply(0.18)));});
            ui.add_space(7.);body(ui)
        })
}
fn background(ui:&egui::Ui,rect:Rect,_compact:bool){
    let id=egui::Id::new("hendrix-young-portrait-v2");
    let texture=ui.ctx().data(|d|d.get_temp::<egui::TextureHandle>(id)).or_else(||{
        let image=image::load_from_memory(include_bytes!("../assets/hendrix-fire-v2.png")).ok()?.to_rgba8();
        let texture=ui.ctx().load_texture("hendrix-fire-v2",egui::ColorImage::from_rgba_unmultiplied([image.width()as usize,image.height()as usize],image.as_raw()),egui::TextureOptions::LINEAR);
        ui.ctx().data_mut(|d|d.insert_temp(id,texture.clone()));Some(texture)
    });
    let p=ui.painter_at(rect);p.rect_filled(rect,8.,Color32::from_rgb(3,13,19));
    if let Some(texture)=texture{
        let art=Rect::from_min_size(rect.min,Vec2::new(rect.width(),rect.width()/1.5));
        p.image(texture.id(),art,Rect::from_min_max(egui::Pos2::ZERO,egui::pos2(1.,1.)),Color32::from_white_alpha(205));
        for n in 0..24{let y=art.bottom()-72.+n as f32*3.;p.rect_filled(Rect::from_min_size(egui::pos2(art.left(),y),Vec2::new(art.width(),3.)),0.,Color32::from_rgba_unmultiplied(3,13,19,(n*10)as u8));}
    }
    p.rect_stroke(rect,8.,Stroke::new(1.,Color32::from_rgb(42,75,84)),StrokeKind::Inside);
    p.hline(rect.left()+16. ..=rect.left()+100.,rect.top()+1.,Stroke::new(2.,LIME));
    p.hline(rect.right()-100. ..=rect.right()-16.,rect.bottom()-1.,Stroke::new(2.,FIRE.gamma_multiply(0.7)));
}
fn note_name(note:i32)->String{format!("{}{}",["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"][note.rem_euclid(12)as usize],note/12-1)}
fn direct_note_latency(note:f32,reference:f32)->(f32,f32){let period=1000./(reference*2f32.powf((note-69.)/12.));(period,period*1.5)}

impl App{
    fn hendrix_capture_preset(host:&crate::external_plugins::Hosted)->crate::hendrix_presets::Values{
        host.info.params.iter().enumerate().map(|(i,p)|(p.id.into(),f32::from_bits(host.values[i].load(Ordering::Acquire)))).collect()
    }
    fn hendrix_apply_preset(&mut self,id:u64,params:&crate::hendrix_presets::Values)->Result<(),String>{
        crate::hendrix_presets::validate_values(params)?;
        let host=self.audio.plugins.host(id).cloned().filter(|h|h.audio_to_midi.is_some()).ok_or("Hendrix ist nicht verfügbar")?;
        let before=self.history_before();
        for p in host.info.params {self.external_parameter(id,p.id,p.to_normalized(params.get(p.id).copied().unwrap_or(p.default)));}
        self.history_commit("Hendrix · Preset laden",before);Ok(())
    }
    fn hendrix_store_preset(&mut self,host:&crate::external_plugins::Hosted,name:&str,default:bool)->Result<u64,String>{
        let before=self.history_before();let id=self.project.hendrix_presets.save(name,Self::hendrix_capture_preset(host),default)?;
        self.dirty=true;self.history_commit(if default{"Hendrix · Projektstandard"}else{"Hendrix · Preset speichern"},before);Ok(id)
    }
    fn hendrix_preset_bank(&mut self,ui:&mut egui::Ui,id:u64,host:&Arc<crate::external_plugins::Hosted>){
        let key=egui::Id::new(("hendrix-preset-bank",id,Arc::as_ptr(&self.shared)as usize));
        let mut state=ui.ctx().data(|d|d.get_temp::<PresetUi>(key)).unwrap_or_else(||PresetUi{name:"Mein Standard".into(),..Default::default()});
        if state.selected.is_some_and(|id|id!=0&&self.project.hendrix_presets.get(id).is_none()){state.selected=None;}
        let entries=self.project.hendrix_presets.entries.clone();let mut load=None;let mut save=false;let mut make_default=false;let mut remove=false;
        section(ui,"PRESETBANK",VIOLET,|ui|{
            ui.horizontal_wrapped(|ui|{
                let text=state.selected.and_then(|id|if id==0{Some("Werkseinstellungen".to_owned())}else{entries.iter().find(|p|p.id==id).map(|p|p.name.clone())}).unwrap_or_else(||"Aktuelle Einstellung".into());
                egui::ComboBox::from_id_salt(("hendrix-presets",id)).width((ui.available_width()*0.45).clamp(100.,260.)).selected_text(text).show_ui(ui,|ui|{
                    if ui.selectable_label(state.selected==Some(0),"Werkseinstellungen").clicked(){load=Some(0);}
                    for preset in &entries {let label=if self.project.hendrix_presets.default==Some(preset.id){format!("{} · Standard",preset.name)}else{preset.name.clone()};if ui.selectable_label(state.selected==Some(preset.id),label).clicked(){load=Some(preset.id);}}
                });
                save=button(ui,"Speichern",false,VIOLET,egui::vec2(92.,27.)).on_hover_text("Aktuelle Regler unter dem Namen speichern; vorhandenen Namen aktualisieren").clicked();
                make_default=button(ui,"Als Standard",false,LIME,egui::vec2(104.,27.)).on_hover_text("Aktuelle Erkennungseinstellungen als Standard für neue Hendrix-Instanzen in diesem Projekt speichern").clicked();
                remove=ui.add_enabled(state.selected.is_some_and(|id|id!=0),egui::Button::new("Löschen")).on_hover_text("Gewähltes Preset löschen · Rückgängig ist möglich").clicked();
            });
            ui.horizontal_wrapped(|ui|{ui.label("Name");ui.add(egui::TextEdit::singleline(&mut state.name).char_limit(80).desired_width(220.).hint_text("Presetname"));});
            let standard=self.project.hendrix_presets.default.and_then(|id|self.project.hendrix_presets.get(id)).map(|p|p.name.as_str()).unwrap_or("Werkseinstellungen");
            ui.small(format!("Projektstandard: {standard} · Mit dem Projekt gespeichert. MIDI-Ziel bleibt am Plugin."));
            if let Some(message)=&state.message{ui.label(egui::RichText::new(message).size(11.).color(VIOLET));}
        });
        if let Some(selected)=load{
            let preset=entries.iter().find(|p|p.id==selected);
            let params=preset.map(|p|p.params.clone()).unwrap_or_else(crate::hendrix_presets::factory);
            match self.hendrix_apply_preset(id,&params){Ok(())=>{state.selected=Some(selected);if let Some(p)=preset{state.name=p.name.clone();}state.message=Some("Preset geladen".into());},Err(e)=>state.message=Some(e)}
        }
        if save||make_default{
            match self.hendrix_store_preset(host,&state.name,make_default){Ok(id)=>{state.selected=Some(id);state.message=Some(if make_default{"Projektstandard gespeichert"}else{"Preset gespeichert"}.into());},Err(e)=>state.message=Some(e)}
        }
        if remove{if let Some(selected)=state.selected{let before=self.history_before();self.project.hendrix_presets.remove(selected);self.dirty=true;self.history_commit("Hendrix · Preset löschen",before);state.selected=None;state.message=Some("Preset gelöscht".into());}}
        ui.ctx().data_mut(|d|d.insert_temp(key,state));
    }
    fn hendrix_register(&mut self,ui:&egui::Ui,r:&egui::Response,id:u64,key:&str){
        midi_ui::register(ui,r.rect,crate::midi_control::Target::External{instance:id,parameter:key.into()},format!("Hendrix · {}",crate::audio_to_midi::INFO.params.iter().find(|p|p.id==key).map_or(key,|p|p.name)));
    }
    fn hendrix_knob(&mut self,ui:&mut egui::Ui,id:u64,host:&Arc<crate::external_plugins::Hosted>,index:usize,label:&str,color:Color32){
        let p=&host.info.params[index];let mut value=p.to_normalized(host.effective_plain(index));let before=value;
        ui.with_layout(egui::Layout::top_down(Align::Center),|ui|{
            ui.add(egui::Label::new(egui::RichText::new(label).size(12.).color(MUTED)).truncate());
            let(rect,mut r)=ui.allocate_exact_size(Vec2::splat(48.),Sense::click_and_drag());
            self.hendrix_register(ui,&r,id,p.id);
            if r.clicked(){r.request_focus();}
            if !self.midi.learn{
                if r.dragged(){let d=ui.input(|i|i.pointer.delta());value=(value+(d.x-d.y)/180.*if ui.input(|i|i.modifiers.shift){0.12}else{1.}).clamp(0.,1.);}
                if r.double_clicked()||fader_ui::alt(ui,&r){value=p.to_normalized(p.default);}
                if r.has_focus(){let d=ui.input(|i|(i.key_pressed(egui::Key::ArrowUp)||i.key_pressed(egui::Key::ArrowRight))as i32-(i.key_pressed(egui::Key::ArrowDown)||i.key_pressed(egui::Key::ArrowLeft))as i32);value=(value+d as f32*if ui.input(|i|i.modifiers.shift){0.001}else{0.01}).clamp(0.,1.);}
            }
            if before!=value{r.mark_changed();}
            r.widget_info(||egui::WidgetInfo::slider(ui.is_enabled(),p.from_normalized(value)as f64,label));
            laser_rack::dial(&ui.painter_at(rect),rect.center(),22.,value,color);
            let plain=p.from_normalized(value);
            let formatted=match index{5|6=>format!("{} · {}",note_name(plain.round()as i32),plain.round()as i32),7=>format!("{}",plain.round()as u8),10 if plain<1.=>"Dynamik".into(),10=>format!("{}",plain.round()as u8),_=>p.format(plain)};
            ui.label(egui::RichText::new(formatted).monospace().size(12.).color(INK));
            if index==5{
                #[cfg(test)]ui.ctx().data_mut(|d|d.remove::<Rect>(egui::Id::new(("hendrix-low-note-latency",id))));
                if host.effective_plain(0)<0.5&&host.effective_plain(16)<0.5{
                    let(min,max)=direct_note_latency(plain.min(host.effective_plain(6)),host.effective_plain(8));
                    let r=ui.label(egui::RichText::new(format!("~ {min:.1}–{max:.1} ms")).size(11.).color(FIRE))
                        .on_hover_text("Geschätzte Erkennungszeit, wenn die tiefste erlaubte Note gespielt wird: eine bis anderthalb Perioden. Audio-/Verarbeitungspuffer und Synth kommen hinzu. Höhere gespielte Noten werden schneller erkannt; die Untergrenze bremst sie nicht pauschal.");
                    #[cfg(test)]ui.ctx().data_mut(|d|d.insert_temp(egui::Id::new(("hendrix-low-note-latency",id)),r.rect));
                    let _=r;
                }
            }
            r.on_hover_text(format!("{}\nZiehen · Shift: fein · Alt-Klick: Standard · Pfeiltasten",p.name));
            #[cfg(test)]ui.ctx().data_mut(|d|d.insert_temp(egui::Id::new(("hendrix-knob",id,index)),rect));
        });
        if before!=value{self.external_parameter(id,p.id,value);}
    }
    fn hendrix_target(&mut self,ui:&mut egui::Ui,id:u64,host:&Arc<crate::external_plugins::Hosted>){
        let output=host.audio_to_midi.as_ref().unwrap();
        let source=(0..self.project.tracks.len()).find(|&t|self.project.external[t].effects.iter().any(|s|s.instance==id)||crate::plugin_scope::clip_slots(&self.project,t).iter().any(|s|s.instance==id));
        let targets:Vec<_>=self.project.live.channels.iter().filter(|c|c.audio_input.is_none()&&Some(c.track)!=source).map(|c|(self.project.tracks[c.track].midi_id,self.project.tracks[c.track].name.clone(),c.assigned||self.project.external[c.track].instrument.is_some())).collect();
        let mut target=output.target.load(Ordering::Acquire);let before=target;
        let label=if target==0{"MIDI-Ziel wählen".into()}else{self.project.tracks.iter().find(|t|t.midi_id==target).map_or_else(||"Ziel nicht vorhanden".into(),|t|t.name.clone())};
        egui::ComboBox::from_id_salt(("hendrix-target",id)).width(ui.available_width()).selected_text(label).show_ui(ui,|ui|{
            ui.selectable_value(&mut target,0,"Kein MIDI-Ziel");for(t,name,ready)in &targets{ui.selectable_value(&mut target,*t,if *ready{name.clone()}else{format!("{name} · Instrument laden")});}
        });
        if target!=before{let history=self.history_before();output.target.store(target,Ordering::Release);for s in crate::plugin_scope::slots_mut(&mut self.project){if s.instance==id{s.state=crate::audio_to_midi::route_state(target);}}self.dirty=true;self.history_commit("Hendrix · MIDI-Ziel",history);}
        if target>0&&!targets.iter().any(|(t,_,ready)|*t==target&&*ready){ui.colored_label(FIRE,"Am MIDI-Ziel ein Instrument laden.");}
    }
    pub(super) fn hendrix_gui(&mut self,ui:&mut egui::Ui,id:u64,host:&Arc<crate::external_plugins::Hosted>){
        let output=host.audio_to_midi.as_ref().unwrap();
        let width=ui.available_width().max(280.);let compact=width<880.;let hero=if compact{width/1.5}else{98.};
        let base_height=if compact{hero+760.}else{(width*0.67).clamp(825.,1000.)};
        let scale=crate::i18n::text_scale(ui.ctx());let height_key=egui::Id::new(("hendrix-measured-height",id));
        let height=ui.ctx().data(|d|d.get_temp::<(f32,f32,f32)>(height_key)).filter(|(w,s,_)|(w-width).abs()<2.&&(s-scale).abs()<0.01).map_or(base_height,|(_,_,h)|h.max(base_height));
        let(canvas,_)=ui.allocate_exact_size(Vec2::new(width,height),Sense::hover());background(ui,canvas,compact);
        #[cfg(test)]ui.ctx().data_mut(|d|d.insert_temp(egui::Id::new(("hendrix-canvas",id)),canvas));
        let p=ui.painter_at(canvas);let origin=canvas.min+egui::vec2(22.,12.);
        p.text(origin+egui::vec2(1.,2.),egui::Align2::LEFT_TOP,"HENDRIX",FontId::proportional(48./scale.max(1.)),LIME.gamma_multiply(0.2));
        p.text(origin,egui::Align2::LEFT_TOP,"HENDRIX",FontId::proportional(48./scale.max(1.)),INK);
        p.text(origin+egui::vec2(2.,58.),egui::Align2::LEFT_TOP,"REALTIME AUDIO / MIDI",FontId::monospace(11.),LIME);
        let left=Rect::from_min_max(canvas.min+egui::vec2(18.,hero+8.),egui::pos2(if compact{canvas.right()-18.}else{canvas.left()+width*0.61},canvas.bottom()-18.));
        let mut controls=ui.new_child(egui::UiBuilder::new().id_salt(("hendrix-panel",id)).max_rect(left).layout(egui::Layout::top_down(Align::Min)));
        controls.set_clip_rect(ui.clip_rect().intersect(canvas));controls.spacing_mut().item_spacing=egui::vec2(8.,9.);
        controls.visuals_mut().override_text_color=Some(INK);
        self.hendrix_preset_bank(&mut controls,id,host);
        section(&mut controls,"MOONSHROOM STUDIO",LIME,crate::hendrix_branding::show);
        section(&mut controls,"SIGNAL & MIDI OUT",CYAN,|ui|{
            ui.columns(2,|cols|{
                self.hendrix_target(&mut cols[0],id,host);
                let sending=host.effective_plain(1)>=0.5;let send_width=cols[1].available_width();let r=button(&mut cols[1],if sending{"MIDI SENDEN · AN"}else{"MIDI SENDEN · AUS"},sending,if sending{LIME}else{VIOLET},egui::vec2(send_width,27.));
                self.hendrix_register(&cols[1],&r,id,"1");if r.clicked()&&!self.midi.learn{self.external_parameter(id,"1",if sending{0.}else{1.});}
            });
            ui.horizontal_wrapped(|ui|{
                let mode=host.effective_plain(0).round()as usize;
                for(i,label)in["MONO","POLY"].into_iter().enumerate(){let r=button(ui,label,mode==i,if i==0{CYAN}else{VIOLET},egui::vec2(76.,27.));self.hendrix_register(ui,&r,id,"0");if r.clicked()&&!self.midi.learn{self.external_parameter(id,"0",i as f32);}}
                let dry=host.effective_plain(12)>=0.5;let r=ui.selectable_label(dry,"Audio durchlassen");self.hendrix_register(ui,&r,id,"12");if r.clicked()&&!self.midi.learn{self.external_parameter(id,"12",if dry{0.}else{1.});}
            });
            let db=f32::from_bits(output.level.load(Ordering::Acquire));let frame=f32::from_bits(output.window_ms.load(Ordering::Acquire));
            ui.add(egui::ProgressBar::new(((db+72.)/72.).clamp(0.,1.)).fill(CYAN.gamma_multiply(0.65)).desired_height(6.).show_percentage());
            let direct=host.effective_plain(0)<0.5;
            ui.horizontal_wrapped(|ui|{ui.label(egui::RichText::new(format!("IN {db:.1} dB")).monospace().size(11.).color(CYAN));
                let timing=if direct{if frame>0.{format!("PERIODE {frame:.1} ms")}else{"PERIODE · wartet".into()}}else{format!("ANALYSE {frame:.0} ms")};
                ui.label(egui::RichText::new(timing).monospace().size(11.).color(MUTED)).on_hover_text("Signalanalyse ohne Ein-/Ausgangspuffer. Im Direktmodus: Länge der erkannten Periode, keine garantierte Gesamtlatenz.");
            });
            ui.collapsing("Nachprüfung",|ui|{
                let mut quality=host.effective_plain(11).round()as usize;let before=quality;let choices=["Schnell","Ausgewogen","Genau"];
                let combo=egui::ComboBox::from_id_salt(("hendrix-quality",id)).width(154.).selected_text(choices[quality]).show_ui(ui,|ui|{for(i,label)in choices.into_iter().enumerate(){ui.selectable_value(&mut quality,i,label);}});
                self.hendrix_register(ui,&combo.response,id,"11");if quality!=before&&!self.midi.learn{self.external_parameter(id,"11",quality as f32/2.);}
                ui.small("Tonhöhe nach dem Erstanschlag verfeinern. Keine späteren Ersatznoten in den Zeitmodi.");
            });
        });
        section(&mut controls,"ANSCHLÄGE & ZEITMODUS",FIRE,|ui|{
            let mono=host.effective_plain(0)<0.5;
            ui.add_enabled_ui(mono,|ui|{
                ui.horizontal_wrapped(|ui|{let mode=host.effective_plain(16).round()as usize;
                    for(i,label)in["Direkt","5 ms","10 ms","15 ms"].into_iter().enumerate(){let r=button(ui,label,mode==i,FIRE,egui::vec2(76.,27.));self.hendrix_register(ui,&r,id,"16");
                        #[cfg(test)]ui.ctx().data_mut(|d|d.insert_temp(egui::Id::new(("hendrix-time-mode",id,i)),r.rect));
                        if r.clicked()&&!self.midi.learn{self.external_parameter(id,"16",i as f32/3.);}}
                });
                ui.small("Zeitmodus: Nur rechtzeitig erkannte Töne senden. Tiefere / zu spät erkannte Töne bleiben stumm.");
                ui.columns(3,|cols|{
                    cols[0].vertical_centered(|ui|{ui.label(egui::RichText::new("Neu anschlagen").size(12.).color(MUTED));let enabled=host.effective_plain(17)>=0.5;let width=ui.available_width();let r=button(ui,if enabled{"RETRIGGER · AN"}else{"RETRIGGER · AUS"},enabled,FIRE,egui::vec2(width,27.));self.hendrix_register(ui,&r,id,"17");if r.clicked()&&!self.midi.learn{self.external_parameter(id,"17",if enabled{0.}else{1.});}ui.small(format!("{} neue Anschläge",output.attacks.load(Ordering::Acquire)));});
                    self.hendrix_knob(&mut cols[1],id,host,18,"Pick-Threshold",FIRE);
                    self.hendrix_knob(&mut cols[2],id,host,19,"Mindestabstand",FIRE);
                });
                ui.small("Threshold niedriger: empfindlicher auf erneutes Picken. Abstand verhindert doppelte Anschläge.");
            });
            if !mono{ui.small("Die Anschlags- und Zeitmodi gelten für Mono. Poly nutzt die Nachprüfung oben.");}
        });
        let response=section(&mut controls,"ERKENNUNG",CYAN,|ui|{ui.columns(4,|cols|{for(col,(index,label))in cols.iter_mut().zip([(2,"Schwelle"),(3,"Stabilität"),(4,"Loslassen"),(7,"Stimmen")]){self.hendrix_knob(col,id,host,index,label,CYAN);}});});
        #[cfg(test)]ui.ctx().data_mut(|d|d.insert_temp(egui::Id::new(("hendrix-detection",id)),response.response.rect));let _=response;
        section(&mut controls,"NOTEN & STIMMUNG",VIOLET,|ui|{ui.columns(4,|cols|{for(col,(index,label))in cols.iter_mut().zip([(5,"Tiefste Note"),(6,"Höchste Note"),(8,"Referenz A4"),(9,"Transpose")]){self.hendrix_knob(col,id,host,index,label,VIOLET);}});});
        section(&mut controls,"EXPRESSION",FIRE,|ui|{ui.columns(4,|cols|{
            cols[0].vertical_centered(|ui|{
                ui.label(egui::RichText::new("Mono-Bends").size(12.).color(MUTED));
                let on=host.effective_plain(13)>=0.5;let mono=host.effective_plain(0)<0.5;
                let r=ui.add_enabled_ui(mono,|ui|{let w=ui.available_width();button(ui,if !mono{"Nur Mono"}else if on{"BEND · AN"}else{"BEND · AUS"},on&&mono,FIRE,egui::vec2(w,27.))}).inner;self.hendrix_register(ui,&r,id,"13");
                if r.clicked()&&!self.midi.learn{self.external_parameter(id,"13",if on{0.}else{1.});}
                r.on_hover_text("Mono: Note bis zur Spielpause halten; Tonhöhenbewegungen als Pitchbend übertragen.");
                let mut range=host.effective_plain(14).round()as usize;let before=range;
                let combo=egui::ComboBox::from_id_salt(("hendrix-bend-range",id)).width(95.).selected_text(["±2 st","±12 st","±24 st"][range]).show_ui(ui,|ui|{for(i,label)in["±2 Halbtöne","±12 Halbtöne","±24 Halbtöne"].into_iter().enumerate(){ui.selectable_value(&mut range,i,label);}});
                self.hendrix_register(ui,&combo.response,id,"14");
                if range!=before&&!self.midi.learn{self.external_parameter(id,"14",range as f32/2.);}
            });
            self.hendrix_knob(&mut cols[1],id,host,15,"Glättung",FIRE);
            self.hendrix_knob(&mut cols[2],id,host,10,"Velocity",FIRE);
            cols[3].vertical_centered(|ui|{
                ui.label(egui::RichText::new("Pitchbend").size(12.).color(MUTED));
                let bend=f32::from_bits(output.bend.load(Ordering::Acquire));ui.add_space(10.);ui.label(egui::RichText::new(format!("{bend:+.2}")).monospace().size(24.).color(FIRE));ui.label(egui::RichText::new("Halbtöne").size(11.).color(MUTED));
            });
        });ui.small(egui::RichText::new("Am Synth denselben Bend-Bereich einstellen. Bends wirken auf den ganzen MIDI-Zielkanal.").size(10.).color(MUTED));});
        section(&mut controls,"ERKANNTE NOTEN",LIME,|ui|{
            let names:Vec<_>=output.notes.iter().enumerate().filter(|(_,v)|v.load(Ordering::Acquire)&255!=0).map(|(n,_)|note_name(n as i32)).collect();
            ui.label(egui::RichText::new(if names.is_empty(){"Spiele eine Note oder einen Akkord".into()}else{names.join("  ·  ")}).size(16.).color(if names.is_empty(){MUTED}else{LIME}));
            let(rect,_)=ui.allocate_exact_size(Vec2::new(ui.available_width(),39.),Sense::hover());let w=rect.width()/48.;
            for note in 36..84{let v=output.notes[note].load(Ordering::Acquire)as u8;let r=Rect::from_min_size(rect.min+egui::vec2((note-36)as f32*w,0.),Vec2::new((w-1.).max(1.),rect.height()));let black=matches!(note%12,1|3|6|8|10);ui.painter().rect_filled(r,2.,if v>0{LIME}else if black{Color32::from_rgb(7,19,27)}else{Color32::from_rgb(33,55,64)});}
        });
        let needed=(controls.min_rect().bottom()-canvas.top()+20.).max(base_height);
        if (needed-height).abs()>1.{ui.ctx().data_mut(|d|d.insert_temp(height_key,(width,scale,needed)));ui.ctx().request_repaint();}
        #[cfg(test)]ui.ctx().data_mut(|d|d.insert_temp(egui::Id::new(("hendrix-controls",id)),controls.min_rect()));
    }
}

#[cfg(test)]mod preset_tests{
    use super::*;
    #[test]fn loading_and_undo_preserve_routing_and_other_instances(){
        let mut a=shrooms_ui::tests::app();
        let slot=|id,target|crate::external_plugins::Slot{instance:id,descriptor:crate::audio_to_midi::descriptor(),enabled:true,params:Default::default(),state:crate::audio_to_midi::route_state(target),breakroom_view:Default::default()};
        a.project.external[0].effects.push(slot(990,a.project.tracks[1].midi_id));
        a.project.external[1].effects.push(slot(991,a.project.tracks[2].midi_id));
        a.shared=Arc::new(Shared::from_project(&a.project));a.audio.plugins=external_ui::State::load(&a.project,&a.shared.external,48000);
        assert!(a.capture_plugins(),"{}",a.status);
        let original_route=a.project.external[0].effects[0].state.clone();let other=a.project.external[1].effects[0].clone();
        let host=a.audio.plugins.host(990).unwrap().clone();let mut values=crate::hendrix_presets::factory();values.insert("2".into(),-72.);values.insert("3".into(),20.5);values.insert("4".into(),69.1);
        a.hendrix_apply_preset(990,&values).unwrap();
        assert_eq!(a.project.external[0].effects[0].state,original_route);assert_eq!(a.project.external[0].effects[0].instance,990);
        assert_eq!(a.project.external[1].effects[0],other);assert!((host.effective_plain(3)-20.5).abs()<0.001);
        let saved=a.hendrix_store_preset(&host,"Meine Gitarre",true).unwrap();assert_eq!(a.project.hendrix_presets.default,Some(saved));assert_eq!(a.project.hendrix_presets.initial().unwrap()["4"],69.1);
        a.workspace_undo(false);assert!(a.project.hendrix_presets.is_empty(),"{}",a.status);a.workspace_undo(true);assert_eq!(a.project.hendrix_presets.default,Some(saved));
        assert_eq!(a.project.external[0].effects[0].state,original_route);
        a.hendrix_apply_preset(990,&crate::hendrix_presets::factory()).unwrap();assert!((host.effective_plain(3)-12.).abs()<0.001);
        a.workspace_undo(false);let host=a.audio.plugins.host(990).unwrap();assert!((host.effective_plain(3)-20.5).abs()<0.001);
    }
}

#[cfg(test)]mod tests{
    use super::*;
    fn fixture()->(App,Arc<crate::external_plugins::Hosted>){
        let mut a=shrooms_ui::tests::app();let t=a.project.add_live_channel().unwrap();
        let slot=crate::external_plugins::Slot{instance:988,descriptor:crate::audio_to_midi::descriptor(),enabled:true,params:Default::default(),state:crate::audio_to_midi::route_state(a.project.tracks[t].midi_id),breakroom_view:Default::default()};
        let h=crate::external_plugins::load(&slot,48000).unwrap();a.project.external[0].effects.push(slot);a.audio.plugins.test_host(h.clone());(a,h)
    }
    #[test]fn lowest_note_latency_tracks_pitch_and_reference_and_only_appears_in_direct_mode(){
        let(a,b)=direct_note_latency(36.,440.);assert!((a-15.289).abs()<0.01&&(b-22.934).abs()<0.01);let(c,d)=direct_note_latency(24.,440.);assert!((c-2.*a).abs()<0.01&&(d-2.*b).abs()<0.01);assert!(direct_note_latency(36.,432.).0>a);
        let ctx=egui::Context::default();shrooms_ui::theme(&ctx);let(mut app,h)=fixture();
        let mut draw=|app:&mut App|{let _=ctx.run(egui::RawInput{screen_rect:Some(Rect::from_min_size(egui::Pos2::ZERO,egui::vec2(1280.,1800.))),..Default::default()},|ctx|{egui::CentralPanel::default().show(ctx,|ui|{app.audio_to_midi_controls(ui,988,&h);});});};
        draw(&mut app);assert!(ctx.data(|d|d.get_temp::<Rect>(egui::Id::new(("hendrix-low-note-latency",988u64)))).is_some());
        app.external_parameter(988,"16",1./3.);draw(&mut app);assert!(ctx.data(|d|d.get_temp::<Rect>(egui::Id::new(("hendrix-low-note-latency",988u64)))).is_none());
    }
    #[test]fn four_time_buttons_select_and_persist_the_exact_mode(){
        let ctx=egui::Context::default();shrooms_ui::theme(&ctx);let(mut app,h)=fixture();let mut time=0.;
        let mut draw=|app:&mut App,events|{time+=0.1;let _=ctx.run(egui::RawInput{time:Some(time),events,screen_rect:Some(Rect::from_min_size(egui::Pos2::ZERO,egui::vec2(1280.,1800.))),..Default::default()},|ctx|{egui::CentralPanel::default().show(ctx,|ui|{app.audio_to_midi_controls(ui,988,&h);});});};
        for _ in 0..3{draw(&mut app,vec![]);}
        for mode in [1usize,2,3,0]{let pos=ctx.data(|d|d.get_temp::<Rect>(egui::Id::new(("hendrix-time-mode",988u64,mode)))).unwrap().center();for pressed in [true,false]{draw(&mut app,vec![egui::Event::PointerMoved(pos),egui::Event::PointerButton{pos,pressed,button:egui::PointerButton::Primary,modifiers:Default::default()}]);}assert_eq!(h.effective_plain(16),mode as f32);assert_eq!(app.project.external[0].effects[0].params["16"],mode as f32);}
    }
    #[test]fn hendrix_layout_keeps_the_art_and_controls_inside_wide_and_narrow_frames(){
        for (width,scale) in [(1280.,1.),(520.,1.7)]{
            let ctx=egui::Context::default();shrooms_ui::theme(&ctx);crate::i18n::set_text_scale(&ctx,scale);workspace_ui::edit(&ctx,|p|p.development_theme=clip_appearance::DevelopmentTheme::DevTheme3);
            let(mut a,h)=fixture();
            for _ in 0..5{let _=ctx.run(egui::RawInput{screen_rect:Some(Rect::from_min_size(egui::Pos2::ZERO,egui::vec2(width,2400.))),..Default::default()},|ctx|{egui::CentralPanel::default().show(ctx,|ui|{assert!(a.audio_to_midi_controls(ui,988,&h));});});}
            assert!(ctx.data(|d|d.get_temp::<egui::TextureHandle>(egui::Id::new("hendrix-young-portrait-v2"))).is_some());
            let canvas=ctx.data(|d|d.get_temp::<Rect>(egui::Id::new(("hendrix-canvas",988u64)))).unwrap();
            let controls=ctx.data(|d|d.get_temp::<Rect>(egui::Id::new(("hendrix-controls",988u64)))).unwrap();
            assert!(canvas.expand(1.).contains_rect(controls),"width={width} scale={scale}: {canvas:?} / {controls:?}");
            if width>880.{assert!(controls.right()<canvas.left()+canvas.width()*0.65,"Portrait stays unobstructed");}
        }
    }
    #[test]fn hendrix_knob_drag_accumulates_and_holding_the_pointer_does_not_drift(){
        let ctx=egui::Context::default();shrooms_ui::theme(&ctx);let(mut a,h)=fixture();let mut time=0.;
        let mut frame=|a:&mut App,events|{time+=0.1;let _=ctx.run(egui::RawInput{time:Some(time),screen_rect:Some(Rect::from_min_size(egui::Pos2::ZERO,egui::vec2(1280.,1200.))),events,..Default::default()},|ctx|{egui::CentralPanel::default().show(ctx,|ui|{a.audio_to_midi_controls(ui,988,&h);});});};
        for _ in 0..3{frame(&mut a,vec![]);}
        let rect=ctx.data(|d|d.get_temp::<Rect>(egui::Id::new(("hendrix-knob",988u64,2usize)))).unwrap();let pos=rect.center();
        let click=|pos,pressed|vec![egui::Event::PointerMoved(pos),egui::Event::PointerButton{pos,pressed,button:egui::PointerButton::Primary,modifiers:Default::default()}];
        let before=h.effective_plain(2);frame(&mut a,click(pos,true));frame(&mut a,vec![egui::Event::PointerMoved(pos-egui::vec2(0.,24.))]);let first=h.effective_plain(2);assert!(first>before);
        frame(&mut a,vec![]);assert_eq!(h.effective_plain(2),first);
        frame(&mut a,vec![egui::Event::PointerMoved(pos-egui::vec2(0.,48.))]);assert!(h.effective_plain(2)>first);frame(&mut a,click(pos-egui::vec2(0.,48.),false));
        assert!(a.project.external[0].effects[0].params.contains_key("2"));
    }
}
