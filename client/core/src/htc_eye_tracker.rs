//! Bindings for `XR_HTC_eye_tracker`: per-eye gaze poses, pupil diameter and
//! position, and eye geometry (openness, wide, squeeze).
//!
//! The extension is not in the Khronos registry, so the openxr crate has no
//! bindings for it. Layouts, enum values (extension number 327) and function
//! names are taken from HTC's VIVE OpenXR Unity plugin
//! (`Runtime/Features/EyeTracker/ViveEyeTrackerHelper.cs`,
//! `Runtime/Common/OpenXRUtils.cs`).

use crate::extensions::{get_props, xr_res};
use openxr::{self as xr, sys};
use std::{
    ffi::{CStr, c_void},
    mem, ptr,
};

pub const EXTENSION_NAME: &str = "XR_HTC_eye_tracker";
pub const EYE_COUNT: usize = 2;
pub const LEFT: usize = 0;
pub const RIGHT: usize = 1;

// XrStructureType values (StructureType::from_raw is not const in openxr-sys)
const TYPE_EYE_TRACKER_CREATE_INFO_HTC: i32 = 1000326001;
const TYPE_EYE_GAZE_DATA_INFO_HTC: i32 = 1000326002;
const TYPE_EYE_GAZE_DATA_HTC: i32 = 1000326003;
const TYPE_SYSTEM_EYE_TRACKING_PROPERTIES_HTC: i32 = 1000326005;
const TYPE_EYE_PUPIL_DATA_INFO_HTC: i32 = 1000326006;
const TYPE_EYE_PUPIL_DATA_HTC: i32 = 1000326007;
const TYPE_EYE_GEOMETRIC_DATA_INFO_HTC: i32 = 1000326009;
const TYPE_EYE_GEOMETRIC_DATA_HTC: i32 = 1000326010;

fn structure_type(raw: i32) -> sys::StructureType {
    sys::StructureType::from_raw(raw)
}

// XR_DEFINE_HANDLE: 64 bit on every platform
type EyeTrackerHandle = u64;

#[repr(C)]
struct SystemEyeTrackingPropertiesHTC {
    ty: sys::StructureType,
    next: *mut c_void,
    supports_eye_tracking: sys::Bool32,
}

#[repr(C)]
struct EyeTrackerCreateInfoHTC {
    ty: sys::StructureType,
    next: *const c_void,
}

#[repr(C)]
struct EyeGazeDataInfoHTC {
    ty: sys::StructureType,
    next: *const c_void,
    base_space: sys::Space,
    time: sys::Time,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct SingleEyeGazeDataHTC {
    pub is_valid: sys::Bool32,
    // In the base space of the request
    pub gaze_pose: sys::Posef,
}

#[repr(C)]
struct EyeGazeDataHTC {
    ty: sys::StructureType,
    next: *mut c_void,
    time: sys::Time,
    gaze: [SingleEyeGazeDataHTC; EYE_COUNT],
}

#[repr(C)]
struct EyePupilDataInfoHTC {
    ty: sys::StructureType,
    next: *const c_void,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct SingleEyePupilDataHTC {
    pub is_diameter_valid: sys::Bool32,
    pub is_position_valid: sys::Bool32,
    // Millimeters
    pub pupil_diameter: f32,
    // In the sensor area, x and y normalized to [0, 1], +Y up, +X right
    pub pupil_position: sys::Vector2f,
}

#[repr(C)]
struct EyePupilDataHTC {
    ty: sys::StructureType,
    next: *mut c_void,
    time: sys::Time,
    pupil: [SingleEyePupilDataHTC; EYE_COUNT],
}

#[repr(C)]
struct EyeGeometricDataInfoHTC {
    ty: sys::StructureType,
    next: *const c_void,
}

// Field order as in the plugin's struct (openness, wide, squeeze), not as in its comments
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct SingleEyeGeometricDataHTC {
    pub is_valid: sys::Bool32,
    // [0, 1]: 0 closed, 1 open normally
    pub eye_openness: f32,
    // [0, 1]: how much wider than normal the eye is open
    pub eye_wide: f32,
    // [0, 1]: how tightly the eye is closed
    pub eye_squeeze: f32,
}

#[repr(C)]
struct EyeGeometricDataHTC {
    ty: sys::StructureType,
    next: *mut c_void,
    time: sys::Time,
    geometric: [SingleEyeGeometricDataHTC; EYE_COUNT],
}

type CreateEyeTrackerHTC = unsafe extern "system" fn(
    sys::Session,
    *const EyeTrackerCreateInfoHTC,
    *mut EyeTrackerHandle,
) -> sys::Result;
type DestroyEyeTrackerHTC = unsafe extern "system" fn(EyeTrackerHandle) -> sys::Result;
type GetEyeGazeDataHTC = unsafe extern "system" fn(
    EyeTrackerHandle,
    *const EyeGazeDataInfoHTC,
    *mut EyeGazeDataHTC,
) -> sys::Result;
type GetEyePupilDataHTC = unsafe extern "system" fn(
    EyeTrackerHandle,
    *const EyePupilDataInfoHTC,
    *mut EyePupilDataHTC,
) -> sys::Result;
type GetEyeGeometricDataHTC = unsafe extern "system" fn(
    EyeTrackerHandle,
    *const EyeGeometricDataInfoHTC,
    *mut EyeGeometricDataHTC,
) -> sys::Result;

struct Functions {
    create: CreateEyeTrackerHTC,
    destroy: DestroyEyeTrackerHTC,
    get_gaze: GetEyeGazeDataHTC,
    get_pupil: GetEyePupilDataHTC,
    get_geometric: GetEyeGeometricDataHTC,
}

/// One sample of everything the extension provides.
#[derive(Clone, Copy, Debug)]
pub struct EyeTrackerSample {
    pub gaze_time: sys::Time,
    pub gaze: [SingleEyeGazeDataHTC; EYE_COUNT],
    pub pupil_time: sys::Time,
    pub pupil: [SingleEyePupilDataHTC; EYE_COUNT],
    pub geometric_time: sys::Time,
    pub geometric: [SingleEyeGeometricDataHTC; EYE_COUNT],
}

pub struct EyeTrackerHTC {
    _session: xr::Session<xr::AnyGraphics>,
    handle: EyeTrackerHandle,
    functions: Functions,
}

fn load_function(instance: &xr::Instance, name: &CStr) -> xr::Result<sys::pfn::VoidFunction> {
    let mut function = None;
    unsafe {
        xr_res((instance.fp().get_instance_proc_addr)(
            instance.as_raw(),
            name.as_ptr(),
            &mut function,
        ))?;
    }

    function.ok_or(sys::Result::ERROR_FUNCTION_UNSUPPORTED)
}

impl EyeTrackerHTC {
    pub fn supports_eye_tracking(instance: &xr::Instance, system: xr::SystemId) -> bool {
        get_props(
            instance,
            system,
            SystemEyeTrackingPropertiesHTC {
                ty: structure_type(TYPE_SYSTEM_EYE_TRACKING_PROPERTIES_HTC),
                next: ptr::null_mut(),
                supports_eye_tracking: sys::FALSE,
            },
        )
        .map(|props| props.supports_eye_tracking.into())
        .unwrap_or(false)
    }

    /// The extension must have been enabled on the instance (`XR_HTC_eye_tracker`).
    pub fn new<G>(session: xr::Session<G>, system: xr::SystemId) -> xr::Result<Self> {
        let instance = session.instance();
        if !Self::supports_eye_tracking(instance, system) {
            return Err(sys::Result::ERROR_FEATURE_UNSUPPORTED);
        }

        // # Safety: the runtime returns these function pointers for exactly these names, with
        // the signatures HTC's plugin declares (see module docs)
        let functions = unsafe {
            Functions {
                create: mem::transmute::<sys::pfn::VoidFunction, CreateEyeTrackerHTC>(
                    load_function(instance, c"xrCreateEyeTrackerHTC")?,
                ),
                destroy: mem::transmute::<sys::pfn::VoidFunction, DestroyEyeTrackerHTC>(
                    load_function(instance, c"xrDestroyEyeTrackerHTC")?,
                ),
                get_gaze: mem::transmute::<sys::pfn::VoidFunction, GetEyeGazeDataHTC>(
                    load_function(instance, c"xrGetEyeGazeDataHTC")?,
                ),
                get_pupil: mem::transmute::<sys::pfn::VoidFunction, GetEyePupilDataHTC>(
                    load_function(instance, c"xrGetEyePupilDataHTC")?,
                ),
                get_geometric: mem::transmute::<sys::pfn::VoidFunction, GetEyeGeometricDataHTC>(
                    load_function(instance, c"xrGetEyeGeometricDataHTC")?,
                ),
            }
        };

        let info = EyeTrackerCreateInfoHTC {
            ty: structure_type(TYPE_EYE_TRACKER_CREATE_INFO_HTC),
            next: ptr::null(),
        };
        let mut handle = 0;
        unsafe { xr_res((functions.create)(session.as_raw(), &info, &mut handle))? };

        Ok(Self {
            _session: session.into_any_graphics(),
            handle,
            functions,
        })
    }

    pub fn sample(&self, base_space: &xr::Space, time: xr::Time) -> xr::Result<EyeTrackerSample> {
        let empty_gaze = SingleEyeGazeDataHTC {
            is_valid: sys::FALSE,
            gaze_pose: sys::Posef::IDENTITY,
        };
        let empty_pupil = SingleEyePupilDataHTC {
            is_diameter_valid: sys::FALSE,
            is_position_valid: sys::FALSE,
            pupil_diameter: 0.0,
            pupil_position: sys::Vector2f { x: 0.0, y: 0.0 },
        };
        let empty_geometric = SingleEyeGeometricDataHTC {
            is_valid: sys::FALSE,
            eye_openness: 0.0,
            eye_wide: 0.0,
            eye_squeeze: 0.0,
        };

        // HTC's plugin sets the output time to the requested time before each call
        let gaze_info = EyeGazeDataInfoHTC {
            ty: structure_type(TYPE_EYE_GAZE_DATA_INFO_HTC),
            next: ptr::null(),
            base_space: base_space.as_raw(),
            time,
        };
        let mut gaze_data = EyeGazeDataHTC {
            ty: structure_type(TYPE_EYE_GAZE_DATA_HTC),
            next: ptr::null_mut(),
            time,
            gaze: [empty_gaze; EYE_COUNT],
        };
        let pupil_info = EyePupilDataInfoHTC {
            ty: structure_type(TYPE_EYE_PUPIL_DATA_INFO_HTC),
            next: ptr::null(),
        };
        let mut pupil_data = EyePupilDataHTC {
            ty: structure_type(TYPE_EYE_PUPIL_DATA_HTC),
            next: ptr::null_mut(),
            time,
            pupil: [empty_pupil; EYE_COUNT],
        };
        let geometric_info = EyeGeometricDataInfoHTC {
            ty: structure_type(TYPE_EYE_GEOMETRIC_DATA_INFO_HTC),
            next: ptr::null(),
        };
        let mut geometric_data = EyeGeometricDataHTC {
            ty: structure_type(TYPE_EYE_GEOMETRIC_DATA_HTC),
            next: ptr::null_mut(),
            time,
            geometric: [empty_geometric; EYE_COUNT],
        };

        unsafe {
            xr_res((self.functions.get_gaze)(self.handle, &gaze_info, &mut gaze_data))?;
            xr_res((self.functions.get_pupil)(self.handle, &pupil_info, &mut pupil_data))?;
            xr_res((self.functions.get_geometric)(
                self.handle,
                &geometric_info,
                &mut geometric_data,
            ))?;
        }

        Ok(EyeTrackerSample {
            gaze_time: gaze_data.time,
            gaze: gaze_data.gaze,
            pupil_time: pupil_data.time,
            pupil: pupil_data.pupil,
            geometric_time: geometric_data.time,
            geometric: geometric_data.geometric,
        })
    }
}

impl Drop for EyeTrackerHTC {
    fn drop(&mut self) {
        unsafe {
            (self.functions.destroy)(self.handle);
        }
    }
}
