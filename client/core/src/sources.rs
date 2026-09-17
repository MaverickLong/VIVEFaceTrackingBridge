//! Face tracking source selection and polling, ported from ALVR's
//! `interaction.rs`. All gaze poses are located against the VIEW reference
//! space so they are heading independent.
//!
//! The standard gaze extension `XR_EXT_eye_gaze_interaction` is deliberately
//! not used: it is what Virtual Desktop reads for its eye tracked foveated
//! encoding, it only delivers to a focused session, and the VRCFT-ALVR module
//! derives gaze from the HTC eye expressions anyway.

use crate::{
    extensions::{EyeTrackerSocial, FaceTracker2FB, FaceTrackerBD, FacialTrackerHTC},
    htc_eye_tracker::{EyeTrackerHTC, EyeTrackerSample, LEFT, RIGHT},
};
use ftbridge_protocol::{FaceData, FaceExpressions, HtcEyeTrackerEye};
use openxr::{self as xr, sys};
use std::{
    cell::Cell,
    time::{Duration, Instant},
};

// The Wave runtime abort()ed the process on the call right after a failed one
const HTC_EYE_TRACKER_RETRY_DELAY: Duration = Duration::from_secs(1);

/// Which kinds of tracking to use. Disabled kinds are neither created nor polled.
#[derive(Clone, Copy, Debug)]
pub struct SourceFilter {
    // Eye tracking: the HTC eye expressions (blink, wide, squeeze, look direction) and, on
    // Meta, the social eye gaze. Meta and Pico deliver eye expressions with the face ones.
    pub eye: bool,
    // With eye tracking on VIVE: also send XR_HTC_eye_tracker's per-eye gaze and pupil
    // diameter (EyeTrHtc segment). Needs the VRCFT-ViveBridge module on the PC.
    pub precise_eye: bool,
    // Face expressions: HTC lip tracker, XR_FB_face_tracking2, XR_BD_facial_simulation
    pub face: bool,
}

enum ExpressionsTracker {
    Fb(FaceTracker2FB),
    Bd(FaceTrackerBD),
    Htc {
        eye: Option<FacialTrackerHTC>,
        lip: Option<FacialTrackerHTC>,
    },
}

pub struct FaceSources {
    filter: SourceFilter,
    eyes_social: Option<EyeTrackerSocial>,
    htc_eye_tracker: Option<EyeTrackerHTC>,
    // Last polling error of htc_eye_tracker, to log changes only
    htc_eye_tracker_error: Cell<Option<sys::Result>>,
    htc_eye_tracker_retry_at: Cell<Option<Instant>>,
    // From XR_EXT_user_presence; true until the runtime says otherwise
    user_present: Cell<bool>,
    expressions_tracker: Option<ExpressionsTracker>,
}

fn check_source<T>(name: &str, result: xr::Result<T>) -> Option<T> {
    match result {
        Ok(value) => {
            log::info!("source available: {name}");

            Some(value)
        }
        Err(e) => {
            log::info!("source unavailable: {name} ({e})");

            None
        }
    }
}

impl FaceSources {
    /// Must be called before `xrBeginSession`: HTC facial trackers can only be
    /// created at startup, and must not be destroyed for the process lifetime
    /// (quirks discovered by ALVR).
    pub fn new(
        session: &xr::Session<xr::OpenGlEs>,
        system: xr::SystemId,
        is_vive: bool,
        filter: SourceFilter,
        // The XR_HTC_eye_tracker tracker (extension enabled, and wanted for precise eye data
        // or the probe)
        create_htc_eye_tracker: bool,
    ) -> Self {
        log::info!(
            "source filter: eye {}, precise eye {}, face {}",
            filter.eye,
            filter.precise_eye,
            filter.face
        );

        let eyes_social = if filter.eye {
            check_source("EyeTrackerSocial", EyeTrackerSocial::new(session))
        } else {
            None
        };

        let expressions_tracker = if is_vive {
            let eye = if filter.eye {
                check_source(
                    "FacialTrackerHTC (eyes)",
                    FacialTrackerHTC::new(
                        session.clone(),
                        system,
                        xr::FacialTrackingTypeHTC::EYE_DEFAULT,
                    ),
                )
            } else {
                None
            };
            let lip = if filter.face {
                check_source(
                    "FacialTrackerHTC (lips)",
                    FacialTrackerHTC::new(
                        session.clone(),
                        system,
                        xr::FacialTrackingTypeHTC::LIP_DEFAULT,
                    ),
                )
            } else {
                None
            };

            (eye.is_some() || lip.is_some()).then_some(ExpressionsTracker::Htc { eye, lip })
        } else if !filter.face {
            None
        } else if let Some(tracker) = check_source(
            "FaceTracker2FB",
            FaceTracker2FB::new(session.clone(), true, true),
        ) {
            Some(ExpressionsTracker::Fb(tracker))
        } else if let Some(tracker) =
            check_source("FaceTrackerBD", FaceTrackerBD::new(session.clone(), system))
        {
            Some(ExpressionsTracker::Bd(tracker))
        } else {
            None
        };

        let htc_eye_tracker = if create_htc_eye_tracker {
            check_source("EyeTrackerHTC", EyeTrackerHTC::new(session.clone(), system))
        } else {
            None
        };

        Self {
            filter,
            eyes_social,
            htc_eye_tracker,
            htc_eye_tracker_error: Cell::new(None),
            htc_eye_tracker_retry_at: Cell::new(None),
            user_present: Cell::new(true),
            expressions_tracker,
        }
    }

    pub fn set_user_present(&self, present: bool) {
        self.user_present.set(present);
    }

    pub fn has_expressions_tracker(&self) -> bool {
        self.expressions_tracker.is_some()
    }

    pub fn has_htc_eye_tracker(&self) -> bool {
        self.htc_eye_tracker.is_some()
    }

    fn forwards_htc_eye_tracker(&self) -> bool {
        self.htc_eye_tracker.is_some() && self.filter.eye && self.filter.precise_eye
    }

    pub fn describe(&self) -> String {
        let mut names = vec![];

        if self.eyes_social.is_some() {
            names.push("social gaze");
        }
        if self.forwards_htc_eye_tracker() {
            names.push("HTC gaze+pupil");
        }
        match &self.expressions_tracker {
            Some(ExpressionsTracker::Fb(_)) => names.push("FB face"),
            Some(ExpressionsTracker::Bd(_)) => names.push("Pico face"),
            Some(ExpressionsTracker::Htc { eye, lip }) => {
                if eye.is_some() {
                    names.push("HTC eye");
                }
                if lip.is_some() {
                    names.push("HTC lip");
                }
            }
            None => (),
        }

        if !self.filter.eye {
            names.push("(eye tracking off)");
        }
        if !self.filter.face {
            names.push("(face tracking off)");
        }

        if names.is_empty() {
            "none".into()
        } else {
            names.join(", ")
        }
    }

    /// Also returns the raw XR_HTC_eye_tracker sample of this poll, for the probe.
    pub fn get_face_data(
        &self,
        view_reference_space: &xr::Space,
        time: xr::Time,
    ) -> (FaceData, Option<EyeTrackerSample>) {
        let eyes_social = if let Some(tracker) = &self.eyes_social
            && let Ok(gazes) = tracker.get_eye_gazes(view_reference_space, time)
        {
            [
                gazes[0].map(|pose| quat_to_array(pose.orientation)),
                gazes[1].map(|pose| quat_to_array(pose.orientation)),
            ]
        } else {
            [None, None]
        };

        let face_expressions = if let Some(tracker) = &self.expressions_tracker {
            match tracker {
                ExpressionsTracker::Fb(tracker) => tracker
                    .get_face_expression_weights(time)
                    .ok()
                    .flatten()
                    .map(FaceExpressions::Fb),
                ExpressionsTracker::Bd(tracker) => tracker
                    .get_facial_simulation_data(time)
                    .ok()
                    .flatten()
                    .map(FaceExpressions::Bd),
                ExpressionsTracker::Htc { eye, lip } => {
                    let eye = eye
                        .as_ref()
                        .and_then(|tracker| tracker.get_facial_expressions(time).ok().flatten());
                    let lip = lip
                        .as_ref()
                        .and_then(|tracker| tracker.get_facial_expressions(time).ok().flatten());

                    Some(FaceExpressions::Htc { eye, lip })
                }
            }
        } else {
            None
        };

        let eye_expressions_active = matches!(
            &face_expressions,
            Some(FaceExpressions::Htc { eye: Some(_), .. })
        );

        // Only while the headset is worn and the eye cameras deliver (the expression tracker is
        // active). With the eye tracker not started (headset not worn) the Wave runtime first
        // fails xrGetEyeGazeDataHTC with XR_ERROR_RUNTIME_FAILURE and then abort()s the
        // process on the next call, hence also the pause after an error.
        let backing_off = self
            .htc_eye_tracker_retry_at
            .get()
            .is_some_and(|retry_at| Instant::now() < retry_at);
        let poll_eye_tracker = self.user_present.get() && eye_expressions_active && !backing_off;
        let eye_tracker_sample = self
            .htc_eye_tracker
            .as_ref()
            .filter(|_| poll_eye_tracker)
            .and_then(|tracker| match tracker.sample(view_reference_space, time) {
                Ok(sample) => {
                    self.htc_eye_tracker_error.set(None);
                    self.htc_eye_tracker_retry_at.set(None);

                    Some(sample)
                }
                Err(e) => {
                    if self.htc_eye_tracker_error.replace(Some(e)) != Some(e) {
                        log::warn!("XR_HTC_eye_tracker: {e}");
                    }
                    self.htc_eye_tracker_retry_at
                        .set(Some(Instant::now() + HTC_EYE_TRACKER_RETRY_DELAY));

                    None
                }
            });

        // Sent even with both eyes closed (no valid values): the module holds the last gaze
        let htc_eye_tracker = eye_tracker_sample
            .as_ref()
            .filter(|_| self.forwards_htc_eye_tracker())
            .map(|sample| {
                [LEFT, RIGHT].map(|eye| HtcEyeTrackerEye {
                    gaze: bool::from(sample.gaze[eye].is_valid)
                        .then(|| quat_to_array(sample.gaze[eye].gaze_pose.orientation)),
                    pupil_diameter_mm: bool::from(sample.pupil[eye].is_diameter_valid)
                        .then_some(sample.pupil[eye].pupil_diameter),
                })
            });

        let face_data = FaceData {
            eyes_combined: None,
            eyes_social,
            htc_eye_tracker,
            face_expressions,
        };

        (face_data, eye_tracker_sample)
    }
}

fn quat_to_array(quat: xr::Quaternionf) -> [f32; 4] {
    [quat.x, quat.y, quat.z, quat.w]
}
