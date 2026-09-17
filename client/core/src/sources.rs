//! Face tracking source selection and polling, ported from ALVR's
//! `interaction.rs`. All gaze poses are located against the VIEW reference
//! space so they are heading independent.
//!
//! The standard gaze extension `XR_EXT_eye_gaze_interaction` is deliberately
//! not used: it is what Virtual Desktop reads for its eye tracked foveated
//! encoding, it only delivers to a focused session, and the VRCFT-ALVR module
//! derives gaze from the HTC eye expressions anyway.

use crate::extensions::{EyeTrackerSocial, FaceTracker2FB, FaceTrackerBD, FacialTrackerHTC};
use ftbridge_protocol::{FaceData, FaceExpressions};
use openxr as xr;

/// Which kinds of tracking to use. Disabled kinds are neither created nor polled.
#[derive(Clone, Copy, Debug)]
pub struct SourceFilter {
    // Eye tracking: the HTC eye expressions (blink, wide, squeeze, look direction) and, on
    // Meta, the social eye gaze. Meta and Pico deliver eye expressions with the face ones.
    pub eye: bool,
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
    ) -> Self {
        log::info!("source filter: eye {}, face {}", filter.eye, filter.face);

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

        Self {
            filter,
            eyes_social,
            expressions_tracker,
        }
    }

    pub fn has_expressions_tracker(&self) -> bool {
        self.expressions_tracker.is_some()
    }

    pub fn describe(&self) -> String {
        let mut names = vec![];

        if self.eyes_social.is_some() {
            names.push("social gaze");
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

        FaceData {
            eyes_combined: None,
            eyes_social,
            face_expressions,
        }
    }
}

fn quat_to_array(quat: xr::Quaternionf) -> [f32; 4] {
    [quat.x, quat.y, quat.z, quat.w]
}
