//! Clipboard: Android-də JNI (ClipboardManager.setPrimaryClip),
//! desktop-da sadə log.

pub fn copy_text(text: &str) {
    #[cfg(target_os = "android")]
    {
        android_clipboard_copy(text);
    }
    #[cfg(not(target_os = "android"))]
    {
        println!("[clipboard] {} bytes copied", text.len());
    }
}

#[cfg(target_os = "android")]
fn android_clipboard_copy(text: &str) {
    use jni::objects::{JObject, JValue};
    use jni::sys::{jobject, JavaVM as RawJavaVM};

    // ndk_context (android-activity tərəfindən qeyd olunur):
    //   vm()      -> JavaVM* (raw)
    //   context() -> Activity jobject (qlobal referans)
    let ctx = ndk_context::android_context();
    let vm_raw = ctx.vm() as *mut RawJavaVM;
    let mut env = unsafe {
        match jni::JavaVM::from_raw(vm_raw) {
            Ok(vm) => match vm.attach_current_thread() {
                Ok(e) => e,
                Err(e) => {
                    eprintln!("[clipboard] attach error: {}", e);
                    return;
                }
            },
            Err(e) => {
                eprintln!("[clipboard] JavaVM error: {}", e);
                return;
            }
        }
    };
    let activity = unsafe { JObject::from_raw(ctx.context() as jobject) };

    let result: Result<(), jni::errors::Error> = (|| {
        // Context.CLIPBOARD_SERVICE sabiti
        let svc = env
            .get_static_field(
                "android/content/Context",
                "CLIPBOARD_SERVICE",
                "Ljava/lang/String;",
            )?
            .l()?;
        // activity.getSystemService(CLIPBOARD_SERVICE)
        let clipboard = env
            .call_method(
                &activity,
                "getSystemService",
                "(Ljava/lang/String;)Ljava/lang/Object;",
                &[JValue::Object(&svc)],
            )?
            .l()?;
        // ClipData.newPlainText("zai", text)
        let jlabel = env.new_string("zai")?;
        let jtext = env.new_string(text)?;
        let clip = env
            .call_static_method(
                "android/content/ClipData",
                "newPlainText",
                "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;",
                &[JValue::Object(&jlabel), JValue::Object(&jtext)],
            )?
            .l()?;
        // clipboard.setPrimaryClip(clip)
        env.call_method(
            &clipboard,
            "setPrimaryClip",
            "(Landroid/content/ClipData;)V",
            &[JValue::Object(&clip)],
        )?;
        Ok(())
    })();

    if let Err(e) = result {
        eprintln!("[clipboard] JNI error: {}", e);
    }
}
