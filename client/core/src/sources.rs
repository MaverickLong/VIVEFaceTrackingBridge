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
use ftbridge_protocol::{FaceData, FaceExpressions, HtcEyeGaze, HtcEyeGeometry, HtcEyeTrackerEye};
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
    // With eye tracking on VIVE: also send XR_HTC_eye_tracker's per-eye gaze, pupil and eye
    // geometry (EyeTrHtc segment). Needs our VRCFT-ALVR module fork on the PC.
    pub accurate_eye: bool,
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
        // Whether the instance has XR_HTC_eye_tracker enabled
        htc_eye_tracker_enabled: bool,
    ) -> Self {
        log::info!(
            "source filter: eye {}, accurate eye {}, face {}",
            filter.eye,
            filter.accurate_eye,
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

        // Only polled while the HTC eye expressions are active, see get_face_data
        let htc_eye_expressions = matches!(
            &expressions_tracker,
            Some(ExpressionsTracker::Htc { eye: Some(_), .. })
        );
        let htc_eye_tracker = if htc_eye_expressions && filter.accurate_eye {
            if htc_eye_tracker_enabled {
                check_source("EyeTrackerHTC", EyeTrackerHTC::new(session.clone(), system))
            } else {
                log::info!("source unavailable: EyeTrackerHTC (extension not available)");

                None
            }
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

    pub fn describe(&self) -> String {
        let mut names = vec![];

        if self.eyes_social.is_some() {
            names.push("social gaze");
        }
        if self.htc_eye_tracker.is_some() {
            names.push("HTC accurate eye");
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

    pub fn get_face_data(&self, view_reference_space: &xr::Space, time: xr::Time) -> FaceData {
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
        let htc_eye_tracker = self
            .poll_htc_eye_tracker(view_reference_space, time, eye_expressions_active)
            .map(|sample| [LEFT, RIGHT].map(|eye| htc_eye_tracker_eye(&sample, eye)));

        FaceData {
            eyes_combined: None,
            eyes_social,
            htc_eye_tracker,
            face_expressions,
        }
    }

    /// Only while the headset is worn and the eye cameras deliver (the expression tracker is
    /// active). With the eye tracker not started (headset not worn) the Wave runtime first
    /// fails xrGetEyeGazeDataHTC with XR_ERROR_RUNTIME_FAILURE and then abort()s the process
    /// on the next call, hence also the pause after an error.
    fn poll_htc_eye_tracker(
        &self,
        view_reference_space: &xr::Space,
        time: xr::Time,
        eye_expressions_active: bool,
    ) -> Option<EyeTrackerSample> {
        let tracker = self.htc_eye_tracker.as_ref()?;

        let backing_off = self
            .htc_eye_tracker_retry_at
            .get()
            .is_some_and(|retry_at| Instant::now() < retry_at);
        if !self.user_present.get() || !eye_expressions_active || backing_off {
            return None;
        }

        match tracker.sample(view_reference_space, time) {
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
        }
    }
}

fn htc_eye_tracker_eye(sample: &EyeTrackerSample, eye: usize) -> HtcEyeTrackerEye {
    let gaze = &sample.gaze[eye];
    let pupil = &sample.pupil[eye];
    let geometric = &sample.geometric[eye];

    HtcEyeTrackerEye {
        gaze: bool::from(gaze.is_valid).then(|| HtcEyeGaze {
            orientation: quat_to_array(gaze.gaze_pose.orientation),
            position: [
                gaze.gaze_pose.position.x,
                gaze.gaze_pose.position.y,
                gaze.gaze_pose.position.z,
            ],
        }),
        pupil_diameter_mm: bool::from(pupil.is_diameter_valid).then_some(pupil.pupil_diameter),
        pupil_position: bool::from(pupil.is_position_valid)
            .then_some([pupil.pupil_position.x, pupil.pupil_position.y]),
        geometry: bool::from(geometric.is_valid).then_some(HtcEyeGeometry {
            openness: geometric.eye_openness,
            wide: geometric.eye_wide,
            squeeze: geometric.eye_squeeze,
        }),
    }
}

fn quat_to_array(quat: xr::Quaternionf) -> [f32; 4] {
    [quat.x, quat.y, quat.z, quat.w]
}
