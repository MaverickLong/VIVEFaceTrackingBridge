//! Ports of ALVR's `extra_extensions` wrappers for the face tracking OpenXR
//! extensions (MIT licensed, see the LICENSE acknowledgment).

use openxr::{
    self as xr, raw,
    sys::{self, Handle},
};
use std::ptr;

fn xr_res(result: sys::Result) -> xr::Result<()> {
    if result.into_raw() >= 0 {
        Ok(())
    } else {
        Err(result)
    }
}

fn get_props<T>(instance: &xr::Instance, system: xr::SystemId, default_struct: T) -> xr::Result<T> {
    let mut props = default_struct;
    let mut system_properties = sys::SystemProperties::out((&raw mut props).cast());
    let result = unsafe {
        (instance.fp().get_system_properties)(
            instance.as_raw(),
            system,
            system_properties.as_mut_ptr(),
        )
    };

    xr_res(result).map(|_| props)
}

pub fn supports_eye_gaze_interaction(instance: &xr::Instance, system: xr::SystemId) -> bool {
    if instance.exts().ext_eye_gaze_interaction.is_none() {
        return false;
    }

    get_props(
        instance,
        system,
        sys::SystemEyeGazeInteractionPropertiesEXT {
            ty: sys::SystemEyeGazeInteractionPropertiesEXT::TYPE,
            next: ptr::null_mut(),
            supports_eye_gaze_interaction: sys::FALSE,
        },
    )
    .map(|props| props.supports_eye_gaze_interaction.into())
    .unwrap_or(false)
}

pub struct FacialTrackerHTC {
    // Keeping a reference to the session to ensure that the tracker handle remains valid
    _session: xr::Session<xr::AnyGraphics>,
    handle: sys::FacialTrackerHTC,
    ext_fns: raw::FacialTrackingHTC,
    expression_count: usize,
}

impl FacialTrackerHTC {
    pub fn new<G>(
        session: xr::Session<G>,
        system: xr::SystemId,
        facial_tracking_type: xr::FacialTrackingTypeHTC,
    ) -> xr::Result<Self> {
        let ext_fns = session
            .instance()
            .exts()
            .htc_facial_tracking
            .ok_or(sys::Result::ERROR_EXTENSION_NOT_PRESENT)?;

        let props = get_props(
            session.instance(),
            system,
            sys::SystemFacialTrackingPropertiesHTC {
                ty: sys::SystemFacialTrackingPropertiesHTC::TYPE,
                next: ptr::null_mut(),
                support_eye_facial_tracking: sys::FALSE,
                support_lip_facial_tracking: sys::FALSE,
            },
        )?;

        let expression_count = if facial_tracking_type == sys::FacialTrackingTypeHTC::EYE_DEFAULT
            && props.support_eye_facial_tracking.into()
        {
            sys::FACIAL_EXPRESSION_EYE_COUNT_HTC
        } else if facial_tracking_type == sys::FacialTrackingTypeHTC::LIP_DEFAULT
            && props.support_lip_facial_tracking.into()
        {
            sys::FACIAL_EXPRESSION_LIP_COUNT_HTC
        } else {
            return Err(sys::Result::ERROR_FEATURE_UNSUPPORTED);
        };

        let mut handle = sys::FacialTrackerHTC::NULL;
        let info = sys::FacialTrackerCreateInfoHTC {
            ty: sys::FacialTrackerCreateInfoHTC::TYPE,
            next: ptr::null(),
            facial_tracking_type,
        };
        unsafe {
            xr_res((ext_fns.create_facial_tracker)(
                session.as_raw(),
                &info,
                &mut handle,
            ))?
        };

        Ok(Self {
            _session: session.into_any_graphics(),
            handle,
            ext_fns,
            expression_count,
        })
    }

    pub fn get_facial_expressions(&self, time: xr::Time) -> xr::Result<Option<Vec<f32>>> {
        let mut weights = Vec::with_capacity(self.expression_count);

        let mut facial_expressions = sys::FacialExpressionsHTC {
            ty: sys::FacialExpressionsHTC::TYPE,
            next: ptr::null_mut(),
            is_active: sys::FALSE,
            sample_time: time,
            expression_count: self.expression_count as u32,
            expression_weightings: weights.as_mut_ptr(),
        };

        unsafe {
            xr_res((self.ext_fns.get_facial_expressions)(
                self.handle,
                &mut facial_expressions,
            ))?;

            if facial_expressions.is_active.into() {
                weights.set_len(self.expression_count);

                Ok(Some(weights))
            } else {
                Ok(None)
            }
        }
    }
}

impl Drop for FacialTrackerHTC {
    fn drop(&mut self) {
        unsafe {
            (self.ext_fns.destroy_facial_tracker)(self.handle);
        }
    }
}

pub struct FaceTracker2FB {
    _session: xr::Session<xr::AnyGraphics>,
    handle: sys::FaceTracker2FB,
    ext_fns: raw::FaceTracking2FB,
}

impl FaceTracker2FB {
    pub fn new<G>(session: xr::Session<G>, visual: bool, audio: bool) -> xr::Result<Self> {
        let ext_fns = session
            .instance()
            .exts()
            .fb_face_tracking2
            .ok_or(sys::Result::ERROR_EXTENSION_NOT_PRESENT)?;

        let mut requested_data_sources = vec![];
        if visual {
            requested_data_sources.push(sys::FaceTrackingDataSource2FB::VISUAL);
        }
        if audio {
            requested_data_sources.push(sys::FaceTrackingDataSource2FB::AUDIO);
        }

        let mut handle = sys::FaceTracker2FB::NULL;
        let info = sys::FaceTrackerCreateInfo2FB {
            ty: sys::FaceTrackerCreateInfo2FB::TYPE,
            next: ptr::null(),
            face_expression_set: xr::FaceExpressionSet2FB::DEFAULT,
            requested_data_source_count: requested_data_sources.len() as u32,
            requested_data_sources: requested_data_sources.as_mut_ptr(),
        };
        unsafe {
            xr_res((ext_fns.create_face_tracker2)(
                session.as_raw(),
                &info,
                &mut handle,
            ))?
        };

        Ok(Self {
            _session: session.into_any_graphics(),
            handle,
            ext_fns,
        })
    }

    pub fn get_face_expression_weights(&self, time: xr::Time) -> xr::Result<Option<Vec<f32>>> {
        let expression_info = sys::FaceExpressionInfo2FB {
            ty: sys::FaceExpressionInfo2FB::TYPE,
            next: ptr::null(),
            time,
        };

        let weights_count = xr::FaceExpression2FB::COUNT.into_raw() as usize;
        let confidence_count = xr::FaceConfidence2FB::COUNT.into_raw() as usize;

        let mut weights: Vec<f32> = Vec::with_capacity(weights_count);
        let mut confidences: Vec<f32> = vec![0.0; confidence_count];

        let mut expression_weights = sys::FaceExpressionWeights2FB {
            ty: sys::FaceExpressionWeights2FB::TYPE,
            next: ptr::null_mut(),
            weight_count: weights_count as u32,
            weights: weights.as_mut_ptr(),
            confidence_count: confidence_count as u32,
            confidences: confidences.as_mut_ptr(),
            is_valid: sys::FALSE,
            is_eye_following_blendshapes_valid: sys::FALSE,
            data_source: sys::FaceTrackingDataSource2FB::from_raw(0),
            time: xr::Time::from_nanos(0),
        };

        unsafe {
            xr_res((self.ext_fns.get_face_expression_weights2)(
                self.handle,
                &expression_info,
                &mut expression_weights,
            ))?;

            if expression_weights.is_valid.into() {
                weights.set_len(weights_count);

                Ok(Some(weights))
            } else {
                Ok(None)
            }
        }
    }
}

impl Drop for FaceTracker2FB {
    fn drop(&mut self) {
        unsafe {
            (self.ext_fns.destroy_face_tracker2)(self.handle);
        }
    }
}

pub struct EyeTrackerSocial {
    handle: sys::EyeTrackerFB,
    ext_fns: raw::EyeTrackingSocialFB,
}

impl EyeTrackerSocial {
    pub fn new<G>(session: &xr::Session<G>) -> xr::Result<Self> {
        let ext_fns = session
            .instance()
            .exts()
            .fb_eye_tracking_social
            .ok_or(sys::Result::ERROR_EXTENSION_NOT_PRESENT)?;

        let mut handle = sys::EyeTrackerFB::NULL;
        let info = sys::EyeTrackerCreateInfoFB {
            ty: sys::EyeTrackerCreateInfoFB::TYPE,
            next: ptr::null(),
        };
        unsafe {
            xr_res((ext_fns.create_eye_tracker)(
                session.as_raw(),
                &info,
                &mut handle,
            ))?
        };

        Ok(Self { handle, ext_fns })
    }

    pub fn get_eye_gazes(
        &self,
        base: &xr::Space,
        time: xr::Time,
    ) -> xr::Result<[Option<xr::Posef>; 2]> {
        let gaze_info = sys::EyeGazesInfoFB {
            ty: sys::EyeGazesInfoFB::TYPE,
            next: ptr::null(),
            base_space: base.as_raw(),
            time,
        };

        let mut eye_gazes = sys::EyeGazesFB::out(ptr::null_mut());

        let eye_gazes = unsafe {
            xr_res((self.ext_fns.get_eye_gazes)(
                self.handle,
                &gaze_info,
                eye_gazes.as_mut_ptr(),
            ))?;

            eye_gazes.assume_init()
        };

        let left_valid: bool = eye_gazes.gaze[0].is_valid.into();
        let right_valid: bool = eye_gazes.gaze[1].is_valid.into();

        Ok([
            left_valid.then(|| eye_gazes.gaze[0].gaze_pose),
            right_valid.then(|| eye_gazes.gaze[1].gaze_pose),
        ])
    }
}

impl Drop for EyeTrackerSocial {
    fn drop(&mut self) {
        unsafe {
            (self.ext_fns.destroy_eye_tracker)(self.handle);
        }
    }
}

pub struct FaceTrackerBD {
    _session: xr::Session<xr::AnyGraphics>,
    handle: sys::FaceTrackerBD,
    ext_fns: raw::FacialSimulationBD,
}

impl FaceTrackerBD {
    pub fn new<G>(session: xr::Session<G>, system: xr::SystemId) -> xr::Result<Self> {
        let ext_fns = session
            .instance()
            .exts()
            .bd_facial_simulation
            .ok_or(sys::Result::ERROR_EXTENSION_NOT_PRESENT)?;

        let props = get_props(
            session.instance(),
            system,
            sys::SystemFacialSimulationPropertiesBD {
                ty: sys::SystemFacialSimulationPropertiesBD::TYPE,
                next: ptr::null_mut(),
                supports_face_tracking: sys::FALSE,
            },
        )?;

        if props.supports_face_tracking == sys::FALSE {
            return Err(sys::Result::ERROR_FEATURE_UNSUPPORTED);
        }

        let mut modes_count = 0;
        unsafe {
            xr_res((ext_fns.enumerate_facial_simulation_modes)(
                session.as_raw(),
                0,
                &mut modes_count,
                ptr::null_mut(),
            ))?;
        }

        let mut modes = vec![xr::FacialSimulationModeBD::default(); modes_count as usize];
        unsafe {
            xr_res((ext_fns.enumerate_facial_simulation_modes)(
                session.as_raw(),
                modes_count,
                &mut modes_count,
                modes.as_mut_ptr(),
            ))?;
        }

        // Prefer combined (with audio), fall back to default (no audio)
        let mode = [
            xr::FacialSimulationModeBD::COMBINED_AUDIO,
            xr::FacialSimulationModeBD::DEFAULT,
        ]
        .into_iter()
        .find(|mode| modes.contains(mode))
        .ok_or(sys::Result::ERROR_FEATURE_UNSUPPORTED)?;

        let mut handle = sys::FaceTrackerBD::NULL;
        let info = sys::FaceTrackerCreateInfoBD {
            ty: sys::FaceTrackerCreateInfoBD::TYPE,
            next: ptr::null(),
            mode,
        };
        unsafe {
            xr_res((ext_fns.create_face_tracker)(
                session.as_raw(),
                &info,
                &mut handle,
            ))?;
        };

        Ok(Self {
            _session: session.into_any_graphics(),
            handle,
            ext_fns,
        })
    }

    pub fn get_facial_simulation_data(&self, time: xr::Time) -> xr::Result<Option<Vec<f32>>> {
        let info = sys::FacialSimulationDataGetInfoBD {
            ty: sys::FacialSimulationDataGetInfoBD::TYPE,
            next: ptr::null(),
            time,
        };

        let mut weights = vec![0.0; sys::FACE_EXPRESSION_COUNT_BD];
        let mut facial_simulation_data = sys::FacialSimulationDataBD {
            ty: sys::FacialSimulationDataBD::TYPE,
            next: ptr::null_mut(),
            face_expression_weight_count: sys::FACE_EXPRESSION_COUNT_BD as u32,
            face_expression_weights: weights.as_mut_ptr(),
            is_upper_face_data_valid: sys::FALSE,
            is_lower_face_data_valid: sys::FALSE,
            time,
        };

        unsafe {
            xr_res((self.ext_fns.get_facial_simulation_data)(
                self.handle,
                &info,
                &mut facial_simulation_data,
            ))?;
        }

        if facial_simulation_data.is_upper_face_data_valid == sys::TRUE
            || facial_simulation_data.is_lower_face_data_valid == sys::TRUE
        {
            Ok(Some(weights))
        } else {
            Ok(None)
        }
    }
}

impl Drop for FaceTrackerBD {
    fn drop(&mut self) {
        unsafe {
            (self.ext_fns.destroy_face_tracker)(self.handle);
        }
    }
}
