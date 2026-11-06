//! Project-owned Hendrix settings. Routing and instance identity stay with the plugin.
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
pub type Values = BTreeMap<String, f32>;
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Preset {
    pub id: u64,
    pub name: String,
    pub params: Values,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Bank {
    pub version: u32,
    pub entries: Vec<Preset>,
    pub default: Option<u64>,
}
impl Default for Bank {
    fn default() -> Self {
        Self {
            version: 1,
            entries: vec![],
            default: None,
        }
    }
}
pub fn factory() -> Values {
    crate::engine::INFO
        .params
        .iter()
        .map(|p| (p.id.into(), p.default))
        .collect()
}
pub fn validate_values(values: &Values) -> Result<(), String> {
    if values.len() > 32 {
        return Err("Zu viele Hendrix-Parameter".into());
    }
    for (id, value) in values {
        let p = crate::engine::INFO
            .params
            .iter()
            .find(|p| p.id == id)
            .ok_or("Unbekannter Hendrix-Parameter")?;
        if !value.is_finite() || !(p.min..=p.max).contains(value) {
            return Err(format!("Ungültiger Hendrix-Wert: {}", p.name));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bank_roundtrip_update_and_delete_keep_the_default_consistent() {
        let mut b = Bank::default();
        let mut p = factory();
        p.insert("3".into(), 20.5);
        let id = b.save("My Guitar", p.clone(), true).unwrap();
        p.insert("4".into(), 69.1);
        assert_eq!(b.save("my guitar", p.clone(), false).unwrap(), id);
        assert_eq!(b.entries.len(), 1);
        assert_eq!(b.initial(), Some(&p));
        let restored: Bank = serde_json::from_slice(&serde_json::to_vec(&b).unwrap()).unwrap();
        assert_eq!(restored, b);
        restored.validate().unwrap();
        b.remove(id);
        assert!(b.is_empty());
    }
    #[test]
    fn malformed_presets_are_rejected_without_modifying_the_bank() {
        let mut b = Bank::default();
        assert!(b.save("", factory(), true).is_err());
        let mut p = factory();
        p.insert("2".into(), f32::NAN);
        assert!(b.save("Invalid", p, true).is_err());
        let mut p = factory();
        p.insert("MIDI destination".into(), 1.);
        assert!(b.save("Routing", p, true).is_err());
        assert!(b.is_empty());
    }
}
impl Bank {
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty() && self.default.is_none()
    }
    pub fn get(&self, id: u64) -> Option<&Preset> {
        self.entries.iter().find(|p| p.id == id)
    }
    pub fn initial(&self) -> Option<&Values> {
        self.default.and_then(|id| self.get(id)).map(|p| &p.params)
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.version != 1 || self.entries.len() > 128 {
            return Err("Ungültige Hendrix-Presetbank".into());
        }
        let mut ids = BTreeSet::new();
        let mut names = BTreeSet::new();
        for p in &self.entries {
            let name = p.name.trim();
            if p.id == 0
                || !ids.insert(p.id)
                || name.is_empty()
                || name.chars().count() > 80
                || name.chars().any(char::is_control)
                || !names.insert(name.to_lowercase())
            {
                return Err("Ungültiger oder doppelter Hendrix-Presetname".into());
            }
            validate_values(&p.params)?;
        }
        if self.default.is_some_and(|id| self.get(id).is_none()) {
            return Err("Hendrix-Projektstandard fehlt".into());
        }
        Ok(())
    }
    pub fn save(&mut self, name: &str, params: Values, make_default: bool) -> Result<u64, String> {
        validate_values(&params)?;
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 80 || name.chars().any(char::is_control) {
            return Err("Bitte einen Presetnamen mit 1–80 Zeichen eingeben".into());
        }
        let id = if let Some(p) = self
            .entries
            .iter_mut()
            .find(|p| p.name.to_lowercase() == name.to_lowercase())
        {
            p.name = name.into();
            p.params = params;
            p.id
        } else {
            if self.entries.len() >= 128 {
                return Err("Die Hendrix-Presetbank ist voll (128 Presets)".into());
            }
            let id = self
                .entries
                .iter()
                .map(|p| p.id)
                .max()
                .unwrap_or(0)
                .checked_add(1)
                .ok_or("Hendrix-Preset-IDs erschöpft")?;
            self.entries.push(Preset {
                id,
                name: name.into(),
                params,
            });
            id
        };
        if make_default {
            self.default = Some(id);
        }
        Ok(id)
    }
    pub fn remove(&mut self, id: u64) {
        self.entries.retain(|p| p.id != id);
        if self.default == Some(id) {
            self.default = None;
        }
    }
}
