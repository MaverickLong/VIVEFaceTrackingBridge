//! OpenXR session lifecycle and the poll -> encode -> send loop.
//!
//! The session submits empty frames (no layers) purely to keep the runtime
//! happy; the only purpose of the session is to own the face trackers.
//! When the session is not running (before READY, or after another app took
//! the foreground) trackers are still polled with the current time: whether
//! the runtime keeps serving data in that state is platform specific.

use crate::{
    egl_context::EglContext,
    sources::{FaceSources, SourceFilter},
    status::Status,
};
use openxr as xr;
use std::{
    ffi::CString,
    net::{SocketAddr, UdpSocket},
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant},
};

const LOADER_FILE_NAME: &str = "libopenxr_loader.so";
const LEGACY_OPENXR_VERSION: xr::Version = xr::Version::new(1, 0, 34);
const CURRENT_OPENXR_VERSION: xr::Version = xr::Version::new(1, 1, 36);
const EVENT_POLL_IDLE_INTERVAL: Duration = Duration::from_millis(100);
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(5);
const SYSTEM_PROPERTY_VALUE_MAX: usize = 92;

pub struct Config {
    pub target: SocketAddr,
    pub rate_hz: f32,
    // Rate of the empty frames submitted to keep the session running. The Wave runtime only
    // activates the facial trackers for a running session, but does not need frames at
    // display rate: each frame costs runtime CPU, so this is decoupled from the poll rate.
    // 0 disables the frame loop and leaves the session in READY.
    pub frame_rate_hz: f32,
    // Which trackers to use; the others are never created
    pub sources: SourceFilter,
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Vendor {
    Htc,
    Other,
}

fn system_property(name: &str) -> String {
    let Ok(name) = CString::new(name) else {
        return String::new();
    };

    let mut buffer = [0u8; SYSTEM_PROPERTY_VALUE_MAX];
    let length = unsafe { libc::__system_property_get(name.as_ptr(), buffer.as_mut_ptr().cast()) };

    String::from_utf8_lossy(&buffer[..length.max(0) as usize]).into_owned()
}

fn detect_vendor() -> Vendor {
    let manufacturer = system_property("ro.product.manufacturer");
    let model = system_property("ro.product.model");
    log::info!("device: {manufacturer} / {model}");

    if manufacturer.eq_ignore_ascii_case("HTC") {
        Vendor::Htc
    } else {
        Vendor::Other
    }
}

fn xr_now(instance: &xr::Instance) -> Option<xr::Time> {
    let ext_fns = instance.exts().khr_convert_timespec_time?;

    let mut timespec = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut timespec) };

    let mut time = xr::Time::from_nanos(0);
    let result = unsafe {
        (ext_fns.convert_timespec_time_to_time)(instance.as_raw(), &timespec, &mut time)
    };

    (result.into_raw() >= 0).then_some(time)
}

fn create_instance(entry: &xr::Entry, vendor: Vendor) -> Result<xr::Instance, String> {
    let available_exts = entry
        .enumerate_extensions()
        .map_err(|e| format!("xrEnumerateInstanceExtensionProperties failed: {e}"))?;
    log::info!(
        "available extensions: {:?}",
        available_exts
            .names()
            .iter()
            .filter_map(|name| std::str::from_utf8(&name[..name.len().saturating_sub(1)]).ok())
            .collect::<Vec<_>>()
    );

    let mut selected_exts = xr::ExtensionSet::default();
    selected_exts.bd_facial_simulation = true;
    selected_exts.ext_eye_gaze_interaction = true;
    selected_exts.ext_user_presence = true;
    selected_exts.fb_eye_tracking_social = true;
    selected_exts.fb_face_tracking2 = true;
    selected_exts.htc_facial_tracking = true;
    selected_exts.khr_android_create_instance = true;
    selected_exts.khr_convert_timespec_time = true;
    selected_exts.khr_opengl_es_enable = true;
    let selected_exts = selected_exts.intersection(&available_exts);

    if !selected_exts.khr_opengl_es_enable {
        return Err("runtime does not support XR_KHR_opengl_es_enable".into());
    }

    let version_candidates = match vendor {
        Vendor::Htc => vec![LEGACY_OPENXR_VERSION],
        Vendor::Other => vec![CURRENT_OPENXR_VERSION, LEGACY_OPENXR_VERSION],
    };

    let mut last_error = String::new();
    for api_version in version_candidates {
        match entry.create_instance(
            &xr::ApplicationInfo {
                application_name: "FT Bridge",
                application_version: 0,
                engine_name: "FT Bridge",
                engine_version: 0,
                api_version,
            },
            &selected_exts,
            &[],
        ) {
            Ok(instance) => return Ok(instance),
            Err(e) => last_error = format!("xrCreateInstance (api {api_version}) failed: {e}"),
        }
    }

    Err(last_error)
}

fn session_state_name(state: xr::SessionState) -> &'static str {
    match state {
        xr::SessionState::IDLE => "IDLE",
        xr::SessionState::READY => "READY",
        xr::SessionState::SYNCHRONIZED => "SYNCHRONIZED",
        xr::SessionState::VISIBLE => "VISIBLE",
        xr::SessionState::FOCUSED => "FOCUSED",
        xr::SessionState::STOPPING => "STOPPING",
        xr::SessionState::LOSS_PENDING => "LOSS_PENDING",
        xr::SessionState::EXITING => "EXITING",
        _ => "UNKNOWN",
    }
}

fn segments_summary(packet: &[u8]) -> String {
    ftbridge_protocol::decode_vrcft_packet(packet)
        .map(|segments| {
            segments
                .iter()
                .map(|segment| segment.name)
                .collect::<Vec<_>>()
                .join("+")
        })
        .unwrap_or_default()
}

pub fn run(config: Config, stop: &AtomicBool, status: &Status) -> Result<(), String> {
    status.set_phase("loading OpenXR loader");
    let vendor = detect_vendor();

    let entry = unsafe { xr::Entry::load_from(Path::new(LOADER_FILE_NAME)) }
        .map_err(|e| format!("failed to load {LOADER_FILE_NAME}: {e}"))?;
    entry
        .initialize_android_loader()
        .map_err(|e| format!("xrInitializeLoaderKHR failed: {e}"))?;

    status.set_phase("creating instance");
    let instance = create_instance(&entry, vendor)?;
    if let Ok(props) = instance.properties() {
        log::info!("runtime: {} {}", props.runtime_name, props.runtime_version);
        status.set_runtime_name(&format!("{} {}", props.runtime_name, props.runtime_version));
    }

    let system = instance
        .system(xr::FormFactor::HEAD_MOUNTED_DISPLAY)
        .map_err(|e| format!("xrGetSystem failed: {e}"))?;
    if let Ok(props) = instance.system_properties(system) {
        log::info!("system: {} (vendor id {})", props.system_name, props.vendor_id);
    }

    status.set_phase("creating session");
    let egl_context = EglContext::new()?;
    let _graphics_requirements = instance
        .graphics_requirements::<xr::OpenGlEs>(system)
        .map_err(|e| format!("xrGetOpenGLESGraphicsRequirementsKHR failed: {e}"))?;

    let (session, mut frame_waiter, mut frame_stream) = unsafe {
        instance.create_session::<xr::OpenGlEs>(
            system,
            &xr::opengles::SessionCreateInfo::Android {
                display: egl_context.display.as_ptr(),
                config: egl_context.config.as_ptr(),
                context: egl_context.context.as_ptr(),
            },
        )
    }
    .map_err(|e| format!("xrCreateSession failed: {e}"))?;

    let view_reference_space = session
        .create_reference_space(xr::ReferenceSpaceType::VIEW, xr::Posef::IDENTITY)
        .map_err(|e| format!("xrCreateReferenceSpace failed: {e}"))?;

    let blend_mode = instance
        .enumerate_environment_blend_modes(system, xr::ViewConfigurationType::PRIMARY_STEREO)
        .ok()
        .and_then(|modes| modes.first().copied())
        .unwrap_or(xr::EnvironmentBlendMode::OPAQUE);

    status.set_phase("creating trackers");
    let sources = FaceSources::new(
        &instance,
        &session,
        system,
        vendor == Vendor::Htc,
        config.sources,
    );
    if !sources.has_expressions_tracker() {
        log::warn!("no face expression tracker available on this device");
    }
    status.set_sources(&sources.describe());

    let socket = UdpSocket::bind("0.0.0.0:0").map_err(|e| format!("cannot bind UDP socket: {e}"))?;
    socket.set_broadcast(true).ok();

    status.set_phase("running");
    let send_interval = Duration::from_secs_f32(1.0 / config.rate_hz.max(1.0));
    let frame_interval =
        (config.frame_rate_hz > 0.0).then(|| Duration::from_secs_f32(1.0 / config.frame_rate_hz));
    let mut packet_buffer = vec![];
    let mut previous_packet = vec![];
    let mut next_send = Instant::now();
    let mut last_frame = Instant::now();
    let mut last_heartbeat = Instant::now();
    let mut event_storage = xr::EventDataBuffer::new();
    let mut session_running = false;

    while !stop.load(Ordering::Relaxed) {
        if last_heartbeat.elapsed() >= HEARTBEAT_INTERVAL {
            last_heartbeat = Instant::now();
            log::info!("heartbeat: {}", status.to_json());
        }

        while let Some(event) = instance
            .poll_event(&mut event_storage)
            .map_err(|e| format!("xrPollEvent failed: {e}"))?
        {
            match event {
                xr::Event::SessionStateChanged(event) => {
                    let state = event.state();
                    log::info!("session state: {}", session_state_name(state));
                    status.set_session_state(session_state_name(state));

                    match state {
                        xr::SessionState::READY if frame_interval.is_some() => {
                            session
                                .begin(xr::ViewConfigurationType::PRIMARY_STEREO)
                                .map_err(|e| format!("xrBeginSession failed: {e}"))?;
                            session_running = true;
                        }
                        xr::SessionState::READY => {
                            log::info!("frame loop disabled, session stays READY");
                        }
                        xr::SessionState::STOPPING => {
                            session.end().ok();
                            session_running = false;
                        }
                        xr::SessionState::EXITING | xr::SessionState::LOSS_PENDING => {
                            return Err(format!(
                                "session ended by runtime ({})",
                                session_state_name(state)
                            ));
                        }
                        _ => (),
                    }
                }
                xr::Event::InstanceLossPending(_) => {
                    return Err("instance loss pending".into());
                }
                xr::Event::EventsLost(event) => {
                    log::warn!("lost {} events", event.lost_event_count());
                }
                xr::Event::UserPresenceChangedEXT(event) => {
                    log::info!("user present: {}", event.is_user_present());
                }
                _ => (),
            }
        }

        let frame_due = session_running
            && frame_interval.is_some_and(|interval| last_frame.elapsed() >= interval);
        let poll_time = if frame_due {
            last_frame = Instant::now();

            let frame_state = frame_waiter
                .wait()
                .map_err(|e| format!("xrWaitFrame failed: {e}"))?;
            frame_stream
                .begin()
                .map_err(|e| format!("xrBeginFrame failed: {e}"))?;
            frame_stream
                .end(frame_state.predicted_display_time, blend_mode, &[])
                .map_err(|e| format!("xrEndFrame failed: {e}"))?;

            frame_state.predicted_display_time
        } else {
            // Sleep until the next send is due, but wake up regularly to poll events
            let wait = next_send
                .saturating_duration_since(Instant::now())
                .min(EVENT_POLL_IDLE_INTERVAL);
            if !wait.is_zero() {
                thread::sleep(wait);
            }

            match xr_now(&instance) {
                Some(time) => time,
                None => continue,
            }
        };

        let now = Instant::now();
        if now < next_send {
            continue;
        }
        // Deadline based pacing keeps the average rate exact; if we fell behind by more than one
        // interval (e.g. a long xrWaitFrame) skip ahead instead of bursting
        next_send += send_interval;
        if next_send < now {
            next_send = now + send_interval;
        }

        let face_data = sources.get_face_data(&session, &view_reference_space, poll_time);
        if ftbridge_protocol::encode_vrcft_packet(&face_data, &mut packet_buffer) {
            let changed = packet_buffer != previous_packet;
            previous_packet.clone_from(&packet_buffer);

            match socket.send_to(&packet_buffer, config.target) {
                Ok(_) => status.record_packet(&segments_summary(&packet_buffer), changed),
                Err(_) => status.record_send_failure(),
            }
        } else {
            status.record_idle_poll();
        }
    }

    status.set_phase("stopped");

    Ok(())
}
