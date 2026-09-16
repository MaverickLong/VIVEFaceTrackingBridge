//! Minimal EGL context, only used to satisfy the OpenXR graphics binding
//! requirement. Nothing is ever rendered; frames are submitted with no layers.

use khronos_egl as egl;

const PBUFFER_ATTRIBS: [i32; 5] = [egl::WIDTH, 16, egl::HEIGHT, 16, egl::NONE];
const CONTEXT_ATTRIBS: [i32; 3] = [egl::CONTEXT_CLIENT_VERSION, 3, egl::NONE];
const OPENGL_ES3_BIT: i32 = 0x0040;
const CONFIG_ATTRIBS: [i32; 13] = [
    egl::RED_SIZE,
    8,
    egl::GREEN_SIZE,
    8,
    egl::BLUE_SIZE,
    8,
    egl::ALPHA_SIZE,
    8,
    egl::SURFACE_TYPE,
    egl::PBUFFER_BIT,
    egl::RENDERABLE_TYPE,
    OPENGL_ES3_BIT,
    egl::NONE,
];

pub struct EglContext {
    instance: egl::DynamicInstance<egl::EGL1_4>,
    pub display: egl::Display,
    pub config: egl::Config,
    pub context: egl::Context,
    surface: egl::Surface,
}

impl EglContext {
    pub fn new() -> Result<Self, String> {
        let instance = unsafe { egl::DynamicInstance::<egl::EGL1_4>::load_required() }
            .map_err(|e| format!("failed to load libEGL: {e}"))?;

        let display = unsafe { instance.get_display(egl::DEFAULT_DISPLAY) }
            .ok_or("no default EGL display")?;
        instance
            .initialize(display)
            .map_err(|e| format!("eglInitialize failed: {e}"))?;

        let config = instance
            .choose_first_config(display, &CONFIG_ATTRIBS)
            .map_err(|e| format!("eglChooseConfig failed: {e}"))?
            .ok_or("no matching EGL config")?;

        instance
            .bind_api(egl::OPENGL_ES_API)
            .map_err(|e| format!("eglBindAPI failed: {e}"))?;

        let context = instance
            .create_context(display, config, None, &CONTEXT_ATTRIBS)
            .map_err(|e| format!("eglCreateContext failed: {e}"))?;

        let surface = instance
            .create_pbuffer_surface(display, config, &PBUFFER_ATTRIBS)
            .map_err(|e| format!("eglCreatePbufferSurface failed: {e}"))?;

        instance
            .make_current(display, Some(surface), Some(surface), Some(context))
            .map_err(|e| format!("eglMakeCurrent failed: {e}"))?;

        Ok(Self {
            instance,
            display,
            config,
            context,
            surface,
        })
    }
}

impl Drop for EglContext {
    fn drop(&mut self) {
        self.instance
            .make_current(self.display, None, None, None)
            .ok();
        self.instance
            .destroy_surface(self.display, self.surface)
            .ok();
        self.instance
            .destroy_context(self.display, self.context)
            .ok();
    }
}
