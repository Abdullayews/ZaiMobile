//! Android platform inteqrasiyası (JNI) — clipboard.rs-in sübut olmuş nümunəsinə uyğun:
//! - MediaStore-dan fayl siyahısı (native fayl seçici üçün)
//! - ContentResolver ilə fayl oxunması (URI → bytes)
//! - Sistem brauzerini açmaq (ACTION_VIEW)
//! - Clipboard oxunması (token avtomatik aşkarlanması üçün)
//! - Runtime icazə istəyi (READ_MEDIA_IMAGES/VIDEO, köhnə: READ_EXTERNAL_STORAGE)

#[derive(Clone, Debug)]
pub struct MediaFile {
    pub id: i64,
    pub name: String,
    pub media: String, // image|video|doc
    pub size: i64,
    pub uri: String,
}

/// JNI mühiti ndk_context-dən alınır (android-activity tərəfindən qeyd olunur).
/// jni 0.21.1: `attach_current_thread` AttachGuard qaytarır və `vm`-dən borrov alır —
/// ona görə `vm` funksiya sonuna qədər yaşayır (v4-ün sübut olunmuş pattern-i).
#[cfg(target_os = "android")]
fn with_env<F, R>(f: F) -> Option<R>
where
    F: for<'a> FnOnce(&mut jni::JNIEnv<'a>) -> R,
{
    use jni::sys::JavaVM as RawJavaVM;
    let ctx = ndk_context::android_context();
    let vm_raw = ctx.vm() as *mut RawJavaVM;
    let vm = match unsafe { jni::JavaVM::from_raw(vm_raw) } {
        Ok(vm) => vm,
        Err(_) => return None,
    };
    let mut env = match vm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return None,
    };
    Some(f(&mut env))
}

/// Sistem brauzerində URL aç (ACTION_VIEW) — WebView YOX
pub fn open_url(url: &str) {
    #[cfg(target_os = "android")]
    {
        use jni::objects::JValue;
        use jni::sys::jobject;
        let url = url.to_string();
        let _ = with_env(move |env| {
            let ctx = unsafe {
                jni::objects::JObject::from_raw(ndk_context::android_context().context() as jobject)
            };
            let r: Result<(), jni::errors::Error> = (|| {
                // Uri.parse(url)
                let jurl = env.new_string(&url)?;
                let uri = env
                    .call_static_method(
                        "android/net/Uri",
                        "parse",
                        "(Ljava/lang/String;)Landroid/net/Uri;",
                        &[JValue::Object(&jurl)],
                    )?
                    .l()?;
                // new Intent(ACTION_VIEW, uri)
                let action = env.new_string("android.intent.action.VIEW")?;
                let intent = env.new_object(
                    "android/content/Intent",
                    "(Ljava/lang/String;Landroid/net/Uri;)V",
                    &[JValue::Object(&action), JValue::Object(&uri)],
                )?;
                // setFlags(FLAG_ACTIVITY_NEW_TASK)
                let flag = env
                    .get_static_field("android/content/Intent", "FLAG_ACTIVITY_NEW_TASK", "I")?
                    .i()?;
                env.call_method(
                    &intent,
                    "setFlags",
                    "(I)Landroid/content/Intent;",
                    &[JValue::Int(flag)],
                )?;
                // context.startActivity(intent)
                env.call_method(
                    &ctx,
                    "startActivity",
                    "(Landroid/content/Intent;)V",
                    &[JValue::Object(&intent)],
                )?;
                Ok(())
            })();
            if let Err(e) = r {
                eprintln!("[open_url] JNI: {}", e);
            }
        });
    }
    #[cfg(not(target_os = "android"))]
    {
        println!("[open_url] {}", url);
    }
}

/// Runtime icazə istə (fayl seçici üçün)
pub fn request_storage_permission() {
    #[cfg(target_os = "android")]
    {
        use jni::objects::JValue;
        use jni::sys::jobject;
        let _ = with_env(|env| {
            let ctx = unsafe {
                jni::objects::JObject::from_raw(ndk_context::android_context().context() as jobject)
            };
            let r: Result<(), jni::errors::Error> = (|| {
                let sdk = env.get_static_field("android/os/Build$VERSION", "SDK_INT", "I")?.i()?;
                let perms: Vec<&str> = if sdk >= 33 {
                    vec!["android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"]
                } else {
                    vec!["android.permission.READ_EXTERNAL_STORAGE"]
                };
                // artıq verilibsə istəmə
                let mut granted = true;
                for p in &perms {
                    let jp = env.new_string(p)?;
                    let st = env.call_method(
                        &ctx,
                        "checkSelfPermission",
                        "(Ljava/lang/String;)I",
                        &[JValue::Object(&jp)],
                    )?.i()?;
                    if st != 0 {
                        granted = false;
                    }
                }
                if granted {
                    return Ok(());
                }
                // requestPermissions(String[], 1)
                let arr = env.new_object_array(
                    perms.len() as i32,
                    "java/lang/String",
                    env.new_string("")?,
                )?;
                for (i, p) in perms.iter().enumerate() {
                    let jp = env.new_string(p)?;
                    env.set_object_array_element(&arr, i as i32, &jp)?;
                }
                env.call_method(
                    &ctx,
                    "requestPermissions",
                    "([Ljava/lang/String;I)V",
                    &[JValue::Object(&arr), JValue::Int(1)],
                )?;
                Ok(())
            })();
            if let Err(e) = r {
                eprintln!("[perm] JNI: {}", e);
            }
        });
    }
}

/// Bir MediaStore kolleksiyasından faylları oxu
#[cfg(target_os = "android")]
fn query_collection(
    env: &mut jni::JNIEnv<'_>,
    cr: &jni::objects::JObject<'_>,
    uri_field_class: &str,
    media: &str,
    limit: i32,
    out: &mut Vec<MediaFile>,
) {
    use jni::objects::JValue;

    let uri = match env.get_static_field(uri_field_class, "EXTERNAL_CONTENT_URI", "Landroid/net/Uri;") {
        Ok(u) => match u.l() {
            Ok(u) => u,
            Err(_) => return,
        },
        Err(_) => return,
    };

    let empty = match env.new_string("") {
        Ok(e) => e,
        Err(_) => return,
    };
    let projection = match env.new_object_array(3, "java/lang/String", &empty) {
        Ok(a) => a,
        Err(_) => return,
    };
    let c0 = match env.new_string("_id") {
        Ok(s) => s,
        Err(_) => return,
    };
    let c1 = match env.new_string("_display_name") {
        Ok(s) => s,
        Err(_) => return,
    };
    let c2 = match env.new_string("_size") {
        Ok(s) => s,
        Err(_) => return,
    };
    if env.set_object_array_element(&projection, 0, &c0).is_err() {
        return;
    }
    if env.set_object_array_element(&projection, 1, &c1).is_err() {
        return;
    }
    if env.set_object_array_element(&projection, 2, &c2).is_err() {
        return;
    }

    let sort = match env.new_string("date_modified DESC") {
        Ok(s) => s,
        Err(_) => return,
    };
    let cursor = env
        .call_method(
            cr,
            "query",
            "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
            &[
                JValue::Object(&uri),
                JValue::Object(&projection),
                JValue::Object(&jni::objects::JObject::null()),
                JValue::Object(&jni::objects::JObject::null()),
                JValue::Object(&sort),
            ],
        )
        .and_then(|r| r.l());
    let cursor = match cursor {
        Ok(c) => c,
        Err(_) => return,
    };

    let mut count = 0;
    loop {
        if count >= limit {
            break;
        }
        let moved = env
            .call_method(&cursor, "moveToNext", "()Z", &[])
            .and_then(|r| r.z())
            .unwrap_or(false);
        if !moved {
            break;
        }
        let id = env
            .call_method(&cursor, "getLong", "(I)J", &[JValue::Int(0)])
            .and_then(|r| r.j())
            .unwrap_or(0);
        let name = env
            .call_method(&cursor, "getString", "(I)Ljava/lang/String;", &[JValue::Int(1)])
            .and_then(|r| r.l())
            .ok()
            .and_then(|j| {
                let s = jni::objects::JString::from(j);
                env.get_string(&s).ok().map(|g| g.to_string_lossy().to_string())
            })
            .unwrap_or_default();
        let size = env
            .call_method(&cursor, "getLong", "(I)J", &[JValue::Int(2)])
            .and_then(|r| r.j())
            .unwrap_or(0);
        if name.is_empty() {
            continue;
        }
        let uri_str = match media {
            "image" => format!("content://media/external/images/media/{}", id),
            "video" => format!("content://media/external/video/media/{}", id),
            _ => format!("content://media/external/downloads/{}", id),
        };
        out.push(MediaFile {
            id,
            name,
            media: media.to_string(),
            size,
            uri: uri_str,
        });
        count += 1;
    }
    let _ = env.call_method(&cursor, "close", "()V", &[]);
}

/// MediaStore-dan son faylları oxu (şəkil + video + downloads)
pub fn query_media_files(limit: i32) -> Vec<MediaFile> {
    #[cfg(target_os = "android")]
    {
        use jni::sys::jobject;
        with_env(move |env| {
            let ctx = unsafe {
                jni::objects::JObject::from_raw(ndk_context::android_context().context() as jobject)
            };
            let cr = env
                .call_method(&ctx, "getContentResolver", "()Landroid/content/ContentResolver;", &[])
                .and_then(|r| r.l())
                .ok();
            let cr = match cr {
                Some(c) => c,
                None => return Vec::new(),
            };
            let mut out: Vec<MediaFile> = Vec::new();
            query_collection(env, &cr, "android/provider/MediaStore$Images$Media", "image", limit, &mut out);
            query_collection(env, &cr, "android/provider/MediaStore$Video$Media", "video", limit, &mut out);
            query_collection(env, &cr, "android/provider/MediaStore$Downloads", "doc", limit, &mut out);
            out
        })
        .unwrap_or_default()
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = limit;
        vec![
            MediaFile { id: 1, name: "sample-image.jpg".into(), media: "image".into(), size: 245_760, uri: "file:///tmp/sample-image.jpg".into() },
            MediaFile { id: 2, name: "document.pdf".into(), media: "doc".into(), size: 1_234_567, uri: "file:///tmp/document.pdf".into() },
        ]
    }
}

/// Content URI-ni bytes kimi oxu (upload üçün) → (bytes, ad, mime)
pub fn read_uri_bytes(uri: &str) -> Result<(Vec<u8>, String, String), String> {
    #[cfg(target_os = "android")]
    {
        use jni::objects::JValue;
        use jni::sys::jobject;
        let uri = uri.to_string();
        with_env(move |env| {
            let ctx = unsafe {
                jni::objects::JObject::from_raw(ndk_context::android_context().context() as jobject)
            };
            let r: Result<(Vec<u8>, String, String), String> = (|| {
                let cr = env
                    .call_method(&ctx, "getContentResolver", "()Landroid/content/ContentResolver;", &[])
                    .and_then(|r| r.l())
                    .map_err(|e| format!("resolver: {}", e))?;

                // Uri.parse
                let juri_str = env.new_string(&uri).map_err(|e| e.to_string())?;
                let juri = env
                    .call_static_method(
                        "android/net/Uri",
                        "parse",
                        "(Ljava/lang/String;)Landroid/net/Uri;",
                        &[JValue::Object(&juri_str)],
                    )
                    .and_then(|r| r.l())
                    .map_err(|e| e.to_string())?;

                // display name
                let mut name = "file".to_string();
                let proj = env
                    .new_object_array(1, "java/lang/String", env.new_string("_display_name").map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
                if let Ok(cursor) = env
                    .call_method(
                        &cr,
                        "query",
                        "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
                        &[
                            JValue::Object(&juri),
                            JValue::Object(&proj),
                            JValue::Object(&jni::objects::JObject::null()),
                            JValue::Object(&jni::objects::JObject::null()),
                            JValue::Object(&jni::objects::JObject::null()),
                        ],
                    )
                    .and_then(|r| r.l())
                {
                    let moved = env
                        .call_method(&cursor, "moveToFirst", "()Z", &[])
                        .and_then(|r| r.z())
                        .unwrap_or(false);
                    if moved {
                        if let Some(display) = env
                            .call_method(&cursor, "getString", "(I)Ljava/lang/String;", &[JValue::Int(0)])
                            .and_then(|r| r.l())
                            .ok()
                            .and_then(|j| {
                                let s = jni::objects::JString::from(j);
                                env.get_string(&s).ok().map(|g| g.to_string_lossy().to_string())
                            })
                        {
                            if !display.is_empty() {
                                name = display;
                            }
                        }
                    }
                    let _ = env.call_method(&cursor, "close", "()V", &[]);
                }

                // mime
                let mime = env
                    .call_method(&cr, "getType", "(Landroid/net/Uri;)Ljava/lang/String;", &[JValue::Object(&juri)])
                    .and_then(|r| r.l())
                    .ok()
                    .and_then(|j| {
                        let s = jni::objects::JString::from(j);
                        env.get_string(&s).ok().map(|g| g.to_string_lossy().to_string())
                    })
                    .unwrap_or_else(|| "application/octet-stream".into());

                // openInputStream → 64KB çünklərlə birbaşa Rust Vec-ə
                // (ByteArrayOutputStream yox → 60MB faylda ~3 əlavə surət yox)
                let istream = env
                    .call_method(&cr, "openInputStream", "(Landroid/net/Uri;)Ljava/io/InputStream;", &[JValue::Object(&juri)])
                    .and_then(|r| r.l())
                    .map_err(|e| format!("open: {}", e))?;
                let mut out: Vec<u8> = Vec::new();
                let buf = env.new_byte_array(65536).map_err(|e| e.to_string())?;
                let mut tmp: Vec<i8> = Vec::with_capacity(65536);
                loop {
                    let n = env
                        .call_method(&istream, "read", "([B)I", &[JValue::Object(&buf)])
                        .and_then(|r| r.i())
                        .map_err(|e| format!("read: {}", e))?;
                    if n <= 0 {
                        break;
                    }
                    // GetByteArrayRegion: Java → Rust birbaşa (jbyte = i8)
                    tmp.resize(n as usize, 0);
                    env.get_byte_array_region(&buf, 0, &mut tmp)
                        .map_err(|e| format!("copy: {}", e))?;
                    out.extend(tmp.iter().map(|&b| b as u8));
                }
                let _ = env.call_method(&istream, "close", "()V", &[]);
                Ok((out, name, mime))
            })();
            r
        })
        .unwrap_or_else(|| Err("JNI env unavailable".into()))
    }
    #[cfg(not(target_os = "android"))]
    {
        let name = uri.rsplit('/').next().unwrap_or("file").to_string();
        let mime = if name.ends_with(".jpg") || name.ends_with(".png") {
            "image/jpeg".to_string()
        } else {
            "application/pdf".to_string()
        };
        Ok((b"desktop-test-content".to_vec(), name, mime))
    }
}

/// Clipboard-dan mətn oxu (token avtomatik aşkarlanması)
pub fn read_clipboard_text() -> Option<String> {
    #[cfg(target_os = "android")]
    {
        use jni::objects::JValue;
        use jni::sys::jobject;
        with_env(|env| {
            let ctx = unsafe {
                jni::objects::JObject::from_raw(ndk_context::android_context().context() as jobject)
            };
            let r: Result<String, jni::errors::Error> = (|| {
                let svc = env
                    .get_static_field("android/content/Context", "CLIPBOARD_SERVICE", "Ljava/lang/String;")?
                    .l()?;
                let cm = env
                    .call_method(
                        &ctx,
                        "getSystemService",
                        "(Ljava/lang/String;)Ljava/lang/Object;",
                        &[JValue::Object(&svc)],
                    )?
                    .l()?;
                let clip = env.call_method(&cm, "getPrimaryClip", "()Landroid/content/ClipData;", &[])?.l()?;
                let n = env.call_method(&clip, "getItemCount", "()I", &[])?.i()?;
                if n < 1 {
                    return Err(jni::errors::Error::NullPtr("empty clip".into()));
                }
                let item = env
                    .call_method(&clip, "getItemAt", "(I)Landroid/content/ClipData$Item;", &[JValue::Int(0)])?
                    .l()?;
                let cs = env.call_method(&item, "coerceToText", "()Ljava/lang/CharSequence;", &[])?.l()?;
                let js = jni::objects::JString::from(cs);
                let s: String = env.get_string(&js)?.into();
                Ok(s)
            })();
            r.ok()
        })
        .flatten()
    }
    #[cfg(not(target_os = "android"))]
    {
        std::env::var("ZAI_TEST_TOKEN").ok()
    }
}
