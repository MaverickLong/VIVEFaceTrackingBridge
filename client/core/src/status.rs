//! Runtime status shared between the bridge thread and the Android UI.

use std::sync::Mutex;

#[derive(Default)]
struct StatusInner {
    phase: String,
    session_state: String,
    runtime_name: String,
    sources: String,
    packets_sent: u64,
    // Packets whose values differed from the previous one: fresh tracker samples
    changed_packets: u64,
    sends_failed: u64,
    idle_polls: u64,
    last_segments: String,
    last_error: String,
}

pub struct Status {
    inner: Mutex<StatusInner>,
}

impl Status {
    pub const fn new() -> Self {
        Self {
            inner: Mutex::new(StatusInner {
                phase: String::new(),
                session_state: String::new(),
                runtime_name: String::new(),
                sources: String::new(),
                packets_sent: 0,
                changed_packets: 0,
                sends_failed: 0,
                idle_polls: 0,
                last_segments: String::new(),
                last_error: String::new(),
            }),
        }
    }

    fn update(&self, f: impl FnOnce(&mut StatusInner)) {
        // # Safety: the closure cannot panic while holding the lock, so it is never poisoned
        f(&mut self.inner.lock().unwrap());
    }

    pub fn reset(&self) {
        self.update(|inner| *inner = StatusInner::default());
    }

    pub fn set_phase(&self, phase: &str) {
        log::info!("phase: {phase}");
        self.update(|inner| inner.phase = phase.into());
    }

    pub fn set_session_state(&self, state: &str) {
        self.update(|inner| inner.session_state = state.into());
    }

    pub fn set_runtime_name(&self, name: &str) {
        self.update(|inner| inner.runtime_name = name.into());
    }

    pub fn set_sources(&self, sources: &str) {
        self.update(|inner| inner.sources = sources.into());
    }

    pub fn record_packet(&self, segments: &str, changed: bool) {
        self.update(|inner| {
            inner.packets_sent += 1;
            inner.changed_packets += u64::from(changed);
            inner.last_segments = segments.into();
        });
    }

    pub fn record_send_failure(&self) {
        self.update(|inner| inner.sends_failed += 1);
    }

    pub fn record_idle_poll(&self) {
        self.update(|inner| {
            inner.idle_polls += 1;
            inner.last_segments = "(no active tracker data)".into();
        });
    }

    pub fn set_error(&self, message: &str) {
        log::error!("{message}");
        self.update(|inner| {
            inner.phase = "error".into();
            inner.last_error = message.into();
        });
    }

    pub fn to_json(&self) -> String {
        // # Safety: see update()
        let inner = self.inner.lock().unwrap();

        format!(
            concat!(
                "{{\"phase\":\"{}\",\"session_state\":\"{}\",\"runtime_name\":\"{}\",",
                "\"sources\":\"{}\",\"packets_sent\":{},\"changed_packets\":{},",
                "\"sends_failed\":{},\"idle_polls\":{},",
                "\"last_segments\":\"{}\",\"last_error\":\"{}\"}}"
            ),
            escape_json(&inner.phase),
            escape_json(&inner.session_state),
            escape_json(&inner.runtime_name),
            escape_json(&inner.sources),
            inner.packets_sent,
            inner.changed_packets,
            inner.sends_failed,
            inner.idle_polls,
            escape_json(&inner.last_segments),
            escape_json(&inner.last_error),
        )
    }
}

fn escape_json(text: &str) -> String {
    let mut out = String::with_capacity(text.len());

    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            c if c.is_control() => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }

    out
}
