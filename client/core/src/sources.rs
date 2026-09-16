//! Face tracking source selection and polling, ported from ALVR's
//! `interaction.rs`. All gaze poses are located against the VIEW reference
//! space so they are heading independent.

use crate::extensions::{
    self, EyeTrackerSocial, FaceTracker2FB, FaceTrackerBD, FacialTrackerHTC,
};
use ftbridge_protocol::{FaceData, FaceExpressions};
use openxr as xr;

const EYE_GAZE_PROFILE_PATH: &str = "/interaction_profiles/ext/eye_gaze_interaction";
const EYE_GAZE_INPUT_PATH: &str = "/user/eyes_ext/input/gaze_ext/pose";

enum ExpressionsTracker {
    Fb(FaceTracker2FB),
    Bd(FaceTrackerBD),
    Htc {
        eye: Option<FacialTrackerHTC>,
        lip: Option<FacialTrackerHTC>,
    },
}

pub struct FaceSources {
    action_set: Option<xr::ActionSet>,
    eyes_combined: Option<(xr::Action<xr::Posef>, xr::Space)>,
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
        instance: &xr::Instance,
        session: &xr::Session<xr::OpenGlEs>,
        system: xr::SystemId,
        is_vive: bool,
    ) -> Self {
        let eye_gaze_supported = extensions::supports_eye_gaze_interaction(instance, system);
        log::info!("eye gaze interaction supported: {eye_gaze_supported}");

        let mut action_set = None;
        let eyes_combined = if eye_gaze_supported {
            let combined_eyes_source = instance
                .create_action_set("ftbridge", "FT Bridge", 0)
                .and_then(|set| {
                    let action =
                        set.create_action::<xr::Posef>("combined_eye_gaze", "Combined eye gaze", &[])?;

                    let res = instance.suggest_interaction_profile_bindings(
                        instance.string_to_path(EYE_GAZE_PROFILE_PATH)?,
                        &[xr::Binding::new(
                            &action,
                            instance.string_to_path(EYE_GAZE_INPUT_PATH)?,
                        )],
                    );
                    if let Err(e) = res {
                        log::warn!("failed to register combined eye gaze input: {e}");
                    }

                    let space = action.create_space(session, xr::Path::NULL, xr::Posef::IDENTITY)?;

                    session.attach_action_sets(&[&set])?;
                    action_set = Some(set);

                    Ok((action, space))
                });

            check_source("combined eye gaze", combined_eyes_source)
        } else {
            None
        };

        let eyes_social = check_source("EyeTrackerSocial", EyeTrackerSocial::new(session));

        let expressions_tracker = if is_vive {
            let eye = check_source(
                "FacialTrackerHTC (eyes)",
                FacialTrackerHTC::new(session.clone(), system, xr::FacialTrackingTypeHTC::EYE_DEFAULT),
            );
            let lip = check_source(
                "FacialTrackerHTC (lips)",
                FacialTrackerHTC::new(session.clone(), system, xr::FacialTrackingTypeHTC::LIP_DEFAULT),
            );

            (eye.is_some() || lip.is_some()).then_some(ExpressionsTracker::Htc { eye, lip })
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
            action_set,
            eyes_combined,
            eyes_social,
            expressions_tracker,
        }
    }

    pub fn has_expressions_tracker(&self) -> bool {
        self.expressions_tracker.is_some()
    }

    pub fn describe(&self) -> String {
        let mut names = vec![];

        if self.eyes_combined.is_some() {
            names.push("combined gaze");
        }
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

        if names.is_empty() {
            "none".into()
        } else {
            names.join(", ")
        }
    }

    pub fn get_face_data(
        &self,
        session: &xr::Session<xr::OpenGlEs>,
        view_reference_space: &xr::Space,
        time: xr::Time,
    ) -> FaceData {
        // The gaze action requires a focused session; failures are expected in background
        if let Some(action_set) = &self.action_set {
            session
                .sync_actions(&[xr::ActiveActionSet::new(action_set)])
                .ok();
        }

        let eyes_combined = if let Some((action, space)) = &self.eyes_combined
            && action.is_active(session, xr::Path::NULL).unwrap_or(false)
            && let Ok(location) = space.locate(view_reference_space, time)
            && location
                .location_flags
                .contains(xr::SpaceLocationFlags::ORIENTATION_VALID)
        {
            Some(quat_to_array(location.pose.orientation))
        } else {
            None
        };

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
            eyes_combined,
            eyes_social,
            face_expressions,
        }
    }
}

fn quat_to_array(quat: xr::Quaternionf) -> [f32; 4] {
    [quat.x, quat.y, quat.z, quat.w]
}
