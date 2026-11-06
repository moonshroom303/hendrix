//! Public developer credits shared by the embedded Hendrix interfaces.
pub const DEVELOPER: &str = "Moonshroom Studio";
pub const LINKS: [(&str, &str); 4] = [
    ("SoundCloud", "https://www.soundcloud.com/moonshroom303"),
    ("YouTube", "https://www.youtube.com/@moonshroom303"),
    (
        "Moonshroom Studio",
        "https://moonshroom.moonshroom303.chatgpt.site/",
    ),
    (
        "ShroomsLoops Studio",
        "https://shroomsloops-studio-231026.moonshroom303.chatgpt.site/#",
    ),
];
pub fn show(ui: &mut egui::Ui) {
    ui.label(format!("Developed by {DEVELOPER}"));
    ui.horizontal_wrapped(|ui| {
        for (label, url) in LINKS {
            let response = ui.hyperlink_to(label, url).on_hover_text(url);
            #[cfg(test)]
            ui.ctx().data_mut(|d| {
                d.insert_temp(egui::Id::new(("hendrix-credit-link", label)), response.rect)
            });
            #[cfg(not(test))]
            let _ = response;
        }
    });
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn each_developer_link_emits_the_requested_browser_destination() {
        let ctx = egui::Context::default();
        let draw = |events| {
            ctx.run(
                egui::RawInput {
                    events,
                    screen_rect: Some(egui::Rect::from_min_size(
                        egui::Pos2::ZERO,
                        egui::vec2(760., 220.),
                    )),
                    ..Default::default()
                },
                |ctx| {
                    egui::CentralPanel::default().show(ctx, show);
                },
            )
        };
        for _ in 0..3 {
            let _ = draw(vec![]);
        }
        for (label, url) in LINKS {
            let pos = ctx
                .data(|d| d.get_temp::<egui::Rect>(egui::Id::new(("hendrix-credit-link", label))))
                .unwrap()
                .center();
            let _ = draw(vec![
                egui::Event::PointerMoved(pos),
                egui::Event::PointerButton {
                    pos,
                    button: egui::PointerButton::Primary,
                    pressed: true,
                    modifiers: Default::default(),
                },
            ]);
            let out = draw(vec![egui::Event::PointerButton {
                pos,
                button: egui::PointerButton::Primary,
                pressed: false,
                modifiers: Default::default(),
            }]);
            assert!(
                out.platform_output
                    .commands
                    .iter()
                    .any(|c| matches!(c,egui::OutputCommand::OpenUrl(u) if u.url==url)),
                "{label}"
            );
        }
    }
}
