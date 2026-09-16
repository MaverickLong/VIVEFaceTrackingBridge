//! Native core of the face tracking bridge: polls face/eye tracking through
//! OpenXR and streams it to a PC in the VRCFT-ALVR module format.
//! Exposed to the Android app through JNI (`dev.maverick.ftbridge.NativeCore`).

#[cfg(target_os = "android")]
mod bridge;
#[cfg(target_os = "android")]
mod egl_context;
#[cfg(target_os = "android")]
mod extensions;
#[cfg(target_os = "android")]
mod sources;
#[cfg(target_os = "android")]
mod status;

#[cfg(target_os = "android")]
mod android {
    use crate::{
        bridge::{self, Config},
        status::Status,
    };
    use jni::{
        JNIEnv,
        objects::{GlobalRef, JClass, JObject, JString},
        sys::{jfloat, jint, jstring},
    };
    use std::{
        net::{IpAddr, SocketAddr},
        panic,
        sync::{
            Arc, Mutex, Once, OnceLock,
            atomic::{AtomicBool, Ordering},
        },
        thread::{self, JoinHandle},
    };

    const LOG_TAG: &str = "ftbridge";

    static STATUS: Status = Status::new();
    static RUNNER: Mutex<Option<Runner>> = Mutex::new(None);
    static ANDROID_CONTEXT: OnceLock<GlobalRef> = OnceLock::new();
    static INIT: Once = Once::new();

    struct Runner {
        stop: Arc<AtomicBool>,
        thread: JoinHandle<()>,
    }

    fn init_once(env: &mut JNIEnv, context: &JObject) {
        INIT.call_once(|| {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag(LOG_TAG),
            );

            // The loader needs the JavaVM and a Context to find the runtime through the broker
            let Ok(vm) = env.get_java_vm() else {
                log::error!("cannot get JavaVM");
                return;
            };
            let Ok(context) = env.new_global_ref(context) else {
                log::error!("cannot create global ref for the context");
                return;
            };
            unsafe {
                ndk_context::initialize_android_context(
                    vm.get_java_vm_pointer().cast(),
                    context.as_obj().as_raw().cast(),
                );
            }
            ANDROID_CONTEXT.set(context).ok();
        });
    }

    fn parse_target(host: &str, port: i32) -> Result<SocketAddr, String> {
        let ip = host
            .trim()
            .parse::<IpAddr>()
            .map_err(|_| format!("invalid target address: {host}"))?;
        let port = u16::try_from(port).map_err(|_| format!("invalid port: {port}"))?;

        Ok(SocketAddr::new(ip, port))
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_maverick_ftbridge_NativeCore_start(
        mut env: JNIEnv,
        _class: JClass,
        context: JObject,
        host: JString,
        port: jint,
        rate_hz: jfloat,
    ) {
        init_once(&mut env, &context);

        let host = env
            .get_string(&host)
            .map(|s| String::from(s))
            .unwrap_or_default();

        // # Safety: the lock is only held for trivial operations, it cannot be poisoned
        let mut runner = RUNNER.lock().unwrap();
        if runner.as_ref().is_some_and(|r| !r.thread.is_finished()) {
            log::warn!("start requested while already running");
            return;
        }

        STATUS.reset();
        let config = match parse_target(&host, port) {
            Ok(target) => Config { target, rate_hz },
            Err(e) => {
                STATUS.set_error(&e);
                return;
            }
        };

        let stop = Arc::new(AtomicBool::new(false));
        let thread_stop = Arc::clone(&stop);
        let thread = thread::spawn(move || {
            let result = panic::catch_unwind(|| bridge::run(config, &thread_stop, &STATUS));

            match result {
                Ok(Ok(())) => (),
                Ok(Err(message)) => STATUS.set_error(&message),
                Err(_) => STATUS.set_error("bridge thread panicked, see logcat"),
            }
        });

        *runner = Some(Runner { stop, thread });
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_maverick_ftbridge_NativeCore_stop(_env: JNIEnv, _class: JClass) {
        // Not joining: the thread may be blocked in xrWaitFrame, and Android
        // would kill the process on a stalled binder call anyway.
        // # Safety: see start()
        if let Some(runner) = RUNNER.lock().unwrap().as_ref() {
            runner.stop.store(true, Ordering::Relaxed);
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_maverick_ftbridge_NativeCore_status(
        env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        env.new_string(STATUS.to_json())
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }
}
