//! Diagnostic recording of `XR_HTC_eye_tracker` samples next to the HTC eye
//! expressions, as CSV lines in logcat (`EYEPROBE_HEADER,...` once, then one
//! `EYEPROBE,...` line per poll). `tools/probe-eye-tracker.ps1` collects them.

use crate::htc_eye_tracker::{EyeTrackerSample, LEFT, RIGHT};
use openxr::{self as xr, sys};
use std::fmt::Write;

// Order of XR_HTC_facial_tracking's eye expressions
const EYE_EXPRESSION_NAMES: [&str; 14] = [
    "left_blink",
    "left_wide",
    "right_blink",
    "right_wide",
    "left_squeeze",
    "right_squeeze",
    "left_down",
    "right_down",
    "left_out",
    "right_in",
    "left_in",
    "right_out",
    "left_up",
    "right_up",
];

/// Logs the CSV header; call once when the probe starts.
pub fn log_header() {
    let mut header = String::from("poll_time_ns");
    header.push_str(",gaze_time_ns");
    for eye in ["l", "r"] {
        for column in [
            "gaze_valid", "quat_x", "quat_y", "quat_z", "quat_w", "pos_x", "pos_y", "pos_z",
            "dir_x", "dir_y", "dir_z",
        ] {
            write!(header, ",{eye}_{column}").ok();
        }
    }
    header.push_str(",pupil_time_ns");
    for eye in ["l", "r"] {
        for column in ["diameter_valid", "diameter_mm", "position_valid", "position_x", "position_y"] {
            write!(header, ",{eye}_{column}").ok();
        }
    }
    header.push_str(",geometric_time_ns");
    for eye in ["l", "r"] {
        for column in ["geometric_valid", "openness", "wide", "squeeze"] {
            write!(header, ",{eye}_{column}").ok();
        }
    }
    header.push_str(",expressions_valid");
    for name in EYE_EXPRESSION_NAMES {
        write!(header, ",expr_{name}").ok();
    }
    log::info!("EYEPROBE_HEADER,{header}");
}

/// Logs one CSV line for a tracker sample. `eye_expressions` are the HTC eye expression
/// weights of the same poll, if active.
pub fn log_sample(time: xr::Time, sample: &EyeTrackerSample, eye_expressions: Option<&[f32]>) {
    log::info!("EYEPROBE,{}", format_sample(time, sample, eye_expressions));
}

fn format_sample(time: xr::Time, sample: &EyeTrackerSample, eye_expressions: Option<&[f32]>) -> String {
    let mut line = String::with_capacity(512);
    write!(line, "{},{}", time.as_nanos(), sample.gaze_time.as_nanos()).ok();
    for eye in [LEFT, RIGHT] {
        let gaze = &sample.gaze[eye];
        let q = gaze.gaze_pose.orientation;
        let p = gaze.gaze_pose.position;
        let direction = rotate_forward(q);
        write!(
            line,
            ",{},{},{},{},{},{},{},{},{},{},{}",
            u32::from(bool::from(gaze.is_valid)),
            q.x,
            q.y,
            q.z,
            q.w,
            p.x,
            p.y,
            p.z,
            direction[0],
            direction[1],
            direction[2]
        )
        .ok();
    }
    write!(line, ",{}", sample.pupil_time.as_nanos()).ok();
    for eye in [LEFT, RIGHT] {
        let pupil = &sample.pupil[eye];
        write!(
            line,
            ",{},{},{},{},{}",
            u32::from(bool::from(pupil.is_diameter_valid)),
            pupil.pupil_diameter,
            u32::from(bool::from(pupil.is_position_valid)),
            pupil.pupil_position.x,
            pupil.pupil_position.y
        )
        .ok();
    }
    write!(line, ",{}", sample.geometric_time.as_nanos()).ok();
    for eye in [LEFT, RIGHT] {
        let geometric = &sample.geometric[eye];
        write!(
            line,
            ",{},{},{},{}",
            u32::from(bool::from(geometric.is_valid)),
            geometric.eye_openness,
            geometric.eye_wide,
            geometric.eye_squeeze
        )
        .ok();
    }
    match eye_expressions {
        Some(weights) if weights.len() == EYE_EXPRESSION_NAMES.len() => {
            line.push_str(",1");
            for weight in weights {
                write!(line, ",{weight}").ok();
            }
        }
        _ => {
            line.push_str(",0");
            for _ in EYE_EXPRESSION_NAMES {
                line.push_str(",");
            }
        }
    }

    line
}

/// The gaze direction: -Z of the pose rotated by its orientation.
fn rotate_forward(q: sys::Quaternionf) -> [f32; 3] {
    let v = [0.0, 0.0, -1.0];
    let u = [q.x, q.y, q.z];
    let uv = cross(u, v);
    let uuv = cross(u, uv);

    [
        v[0] + 2.0 * (q.w * uv[0] + uuv[0]),
        v[1] + 2.0 * (q.w * uv[1] + uuv[1]),
        v[2] + 2.0 * (q.w * uv[2] + uuv[2]),
    ]
}

fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
