//! Platform-a xas funksiyalar: Android JNI clipboard (yapışdır/kopyala),
//! task-a geri qaytarma. Host-da da kompilyasiya olunur — JNI zəngləri
//! yalnız Android-də icra olunur (host-da JavaVM singleton boşdur).

// imp modulu HƏR platformada kompilyasiya olunur — beləcə JNI kodu host-da
// da tam type-check olunur (jni + ndk-context portativ Rust-dır). Yalnız
// runtime-da fərq var: hostda JavaVM::singleton() Err qaytarır.
#[allow(dead_code)]
mod imp {
    use jni::objects::{JClass, JObject, JString};
    use jni::{jni_sig, jni_str, Env, JavaVM};

    /// attach_current_thread üçün xəta tipi (jni::errors::Error-dən çevrilir)
    enum JErr {
        Jni(jni::errors::Error),
        Msg(String),
    }

    impl From<jni::errors::Error> for JErr {
        fn from(e: jni::errors::Error) -> Self {
            JErr::Jni(e)
        }
    }

    /// JNI çağırışı: xətada pending Java exception-u təmizləyir
    macro_rules! jcall {
        ($env:expr, $call:expr) => {
            match $call {
                Ok(v) => v,
                Err(e) => {
                    $env.exception_clear();
                    return Err(JErr::Jni(e));
                }
            }
        };
    }

    /// JVM + Activity (Context) referansı ilə callback işlədir.
    /// android-activity android_main-dən əvvəl:
    ///  - JavaVM singleton-u işə salır,
    ///  - ndk_context-ə qlobal Activity referansı qeyd edir,
    ///  - android_main threadini JVM-ə bağlayır.
    /// egui yenidən çəkilmə döngüsi həmin threaddə işləyir → bütün
    /// çağırışlar artıq bağlı threaddən edilir (ucuz, detach yoxdur).
    /// Hostda singleton Err qaytarır → funksiya heç vaxt icra olunmur.
    fn with_env<T>(
        f: impl FnOnce(&mut Env, &JObject) -> Result<T, JErr>,
    ) -> Result<T, String> {
        let vm = JavaVM::singleton().map_err(|e| format!("JVM tapılmadı: {}", e))?;
        let activity_raw = ndk_context::android_context().context() as jni::sys::jobject;
        if activity_raw.is_null() {
            return Err("Android konteksti alınmadı".into());
        }
        vm.attach_current_thread(|env| {
            // Təhlükəsizlik: ndk_context-dəki referans qlobaldır, tətbiq ömrü
            // boyu yaşayır; JObject-də Drop yoxdur (sahiblik götürülmür).
            let activity = unsafe { JObject::from_raw(env, activity_raw) };
            f(env, &activity)
        })
        .map_err(|e| match e {
            JErr::Jni(err) => format!("JNI xətası: {}", err),
            JErr::Msg(m) => m,
        })
    }

    /// Android sistem mübadilə buferindən mətn oxu
    pub fn clipboard_get() -> Result<String, String> {
        with_env(|env, activity| {
            // ClipboardManager cm = (ClipboardManager) context.getSystemService("clipboard")
            let svc = jcall!(env, env.new_string("clipboard"));
            let svc_obj: &JObject = &svc;
            let mgr = jcall!(
                env,
                env.call_method(
                    activity,
                    jni_str!("getSystemService"),
                    jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                    &[(svc_obj).into()]
                )
            )
            .l()?;
            if mgr.as_raw().is_null() {
                return Err(JErr::Msg("ClipboardManager alınmadı".into()));
            }

            // ClipData data = cm.getPrimaryClip()
            let clip = jcall!(
                env,
                env.call_method(
                    &mgr,
                    jni_str!("getPrimaryClip"),
                    jni_sig!("()Landroid/content/ClipData;"),
                    &[]
                )
            )
            .l()?;
            if clip.as_raw().is_null() {
                return Err(JErr::Msg("Mübadilə buferi boşdur".into()));
            }

            // data.getItemCount() / data.getItemAt(0)
            let count = jcall!(
                env,
                env.call_method(&clip, jni_str!("getItemCount"), jni_sig!("()I"), &[])
            )
            .i()?;
            if count < 1 {
                return Err(JErr::Msg("Mübadilə buferi boşdur".into()));
            }
            let item = jcall!(
                env,
                env.call_method(
                    &clip,
                    jni_str!("getItemAt"),
                    jni_sig!("(I)Landroid/content/ClipData$Item;"),
                    &[(0_i32).into()]
                )
            )
            .l()?;
            if item.as_raw().is_null() {
                return Err(JErr::Msg("Bufer elementi boşdur".into()));
            }

            // item.coerceToText(context).toString()
            let text = jcall!(
                env,
                env.call_method(
                    &item,
                    jni_str!("coerceToText"),
                    jni_sig!("(Landroid/content/Context;)Ljava/lang/CharSequence;"),
                    &[(activity).into()]
                )
            )
            .l()?;
            if text.as_raw().is_null() {
                return Err(JErr::Msg(
                    "Buferdə mətn yoxdur (şəkil/fayl ola bilər)".into()
                ));
            }
            let s = jcall!(
                env,
                env.call_method(&text, jni_str!("toString"), jni_sig!("()Ljava/lang/String;"), &[])
            )
            .l()?;
            if s.as_raw().is_null() {
                return Err(JErr::Msg("Mətn boşdur".into()));
            }
            // java String → Rust String
            let jstr = unsafe { JString::from_raw(env, s.as_raw()) };
            let mutf8 = jcall!(env, jstr.mutf8_chars(env));
            Ok(mutf8.to_str().to_string())
        })
    }

    /// Android sistem mübadilə buferinə mətn yaz
    pub fn clipboard_set(text: &str) -> Result<(), String> {
        with_env(|env, activity| {
            let svc = jcall!(env, env.new_string("clipboard"));
            let svc_obj: &JObject = &svc;
            let mgr = jcall!(
                env,
                env.call_method(
                    activity,
                    jni_str!("getSystemService"),
                    jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                    &[(svc_obj).into()]
                )
            )
            .l()?;
            if mgr.as_raw().is_null() {
                return Err(JErr::Msg("ClipboardManager alınmadı".into()));
            }

            // ClipData.newPlainText(label, text)
            let label = jcall!(env, env.new_string("Z.ai"));
            let label_obj: &JObject = &label;
            let txt = jcall!(env, env.new_string(text));
            let txt_obj: &JObject = &txt;
            let class: JClass = jcall!(
                env,
                env.find_class(jni_str!("android/content/ClipData"))
            );
            let clip = jcall!(
                env,
                env.call_static_method(
                    &class,
                    jni_str!("newPlainText"),
                    jni_sig!(
                        "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;"
                    ),
                    &[(label_obj).into(), (txt_obj).into()]
                )
            )
            .l()?;
            if clip.as_raw().is_null() {
                return Err(JErr::Msg("ClipData yaradılmadı".into()));
            }

            // cm.setPrimaryClip(clip)
            jcall!(
                env,
                env.call_method(
                    &mgr,
                    jni_str!("setPrimaryClip"),
                    jni_sig!("(Landroid/content/ClipData;)V"),
                    &[(&clip).into()]
                )
            );
            Ok(())
        })
    }

    /// Tətbiqi arxa plana qaytar (Android back düyməsi Chats ekranında)
    pub fn move_task_to_back() {
        let _ = with_env(|env, activity| {
            jcall!(
                env,
                env.call_method(
                    activity,
                    jni_str!("moveTaskToBack"),
                    jni_sig!("(Z)Z"),
                    &[(true).into()]
                )
            );
            Ok(())
        });
    }
}

#[cfg(target_os = "android")]
pub use imp::{clipboard_get, clipboard_set, move_task_to_back};

#[cfg(not(target_os = "android"))]
pub fn clipboard_get() -> Result<String, String> {
    Err("Mübadilə buferindən oxuma yalnız Android-də mövcuddur.".into())
}

#[cfg(not(target_os = "android"))]
pub fn clipboard_set(_text: &str) -> Result<(), String> {
    Err("Sistem buferinə yazma yalnız Android-də mövcuddur.".into())
}

#[cfg(not(target_os = "android"))]
pub fn move_task_to_back() {}

/// Kopyala: Android-də JNI sistem buferi; uğursuzluqda və desktop-da
/// egui buferi (desktop-da arboard işləyir, Android-də tətbiqdaxili ehtiyat).
/// (egui-winit Android-də arboard YOXDUR — ctx.copy_text orada sistemə çatmır!)
pub fn copy_to_clipboard(ctx: &egui::Context, text: &str) {
    #[cfg(target_os = "android")]
    {
        if clipboard_set(text).is_ok() {
            return;
        }
    }
    ctx.copy_text(text.to_string());
}
