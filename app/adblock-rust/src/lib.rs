//! JNI bridge between Lumen (com.phlox.tvwebbrowser.sofa.LumenAdblock) and adblock-rust.
//!
//! An engine is handed to Kotlin as a raw pointer (jlong). Kotlin guarantees an engine is not
//! destroyed while a call is still using it. Every entry point catches panics: a panic must
//! never cross into the JVM.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

fn engine_ref<'a>(ptr: jlong) -> Option<&'a Engine> {
    if ptr == 0 {
        None
    } else {
        // SAFETY: the pointer came from Box::into_raw in this library and Kotlin keeps it alive.
        Some(unsafe { &*(ptr as *const Engine) })
    }
}

fn read_jstring(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(|v| v.into())
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn into_jlong(engine: Engine) -> jlong {
    Box::into_raw(Box::new(engine)) as jlong
}

/// Parses the filter lists in the text file at `path` (all lists joined) into a new engine.
#[no_mangle]
pub extern "system" fn Java_com_phlox_tvwebbrowser_sofa_LumenAdblock_nativeCreate(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let path = match read_jstring(&mut env, &path) {
            Some(p) => p,
            None => return 0,
        };
        let bytes = match std::fs::read(&path) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        let text = String::from_utf8_lossy(&bytes);
        let mut set = FilterSet::new(false);
        set.add_filter_list(&text, ParseOptions::default());
        into_jlong(Engine::from_filter_set(set, true))
    }))
    .unwrap_or(0)
}

/// Loads an engine saved earlier by nativeSave (much faster than parsing the lists again).
#[no_mangle]
pub extern "system" fn Java_com_phlox_tvwebbrowser_sofa_LumenAdblock_nativeLoad(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let path = match read_jstring(&mut env, &path) {
            Some(p) => p,
            None => return 0,
        };
        let bytes = match std::fs::read(&path) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        let mut engine = Engine::default();
        match engine.deserialize(&bytes) {
            Ok(()) => into_jlong(engine),
            Err(_) => 0,
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_phlox_tvwebbrowser_sofa_LumenAdblock_nativeSave(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    path: JString,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let engine = match engine_ref(ptr) {
            Some(e) => e,
            None => return JNI_FALSE,
        };
        let path = match read_jstring(&mut env, &path) {
            Some(p) => p,
            None => return JNI_FALSE,
        };
        if std::fs::write(&path, engine.serialize()).is_ok() {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    }))
    .unwrap_or(JNI_FALSE)
}

/// Scriptlet and redirect resources (a JSON array in adblock-rust's Resource format).
/// Must be called before the engine is shared with other threads.
#[no_mangle]
pub extern "system" fn Java_com_phlox_tvwebbrowser_sofa_LumenAdblock_nativeUseResources(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    path: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        if ptr == 0 {
            return -1;
        }
        let path = match read_jstring(&mut env, &path) {
            Some(p) => p,
            None => return -1,
        };
        let bytes = match std::fs::read(&path) {
            Ok(b) => b,
            Err(_) => return -1,
        };
        let resources: Vec<Resource> = match serde_json::from_slice(&bytes) {
            Ok(r) => r,
            Err(_) => return -1,
        };
        let count = resources.len() as jint;
        // SAFETY: see engine_ref; Kotlin calls this before publishing the engine.
        let engine = unsafe { &mut *(ptr as *mut Engine) };
        engine.use_resources(resources);
        count
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_phlox_tvwebbrowser_sofa_LumenAdblock_nativeDestroy(
    _env: JNIEnv,
    _this: JObject,
    ptr: jlong,
) {
    if ptr != 0 {
        let _ = catch_unwind(|| {
            // SAFETY: created by into_jlong, destroyed exactly once by Kotlin.
            drop(unsafe { Box::from_raw(ptr as *mut Engine) });
        });
    }
}

/// 1 = block the request, 0 = let it load.
/// `request_type` uses adblock-rust's names: image, stylesheet, script, media, font,
/// xmlhttprequest, sub_frame, other.
#[no_mangle]
pub extern "system" fn Java_com_phlox_tvwebbrowser_sofa_LumenAdblock_nativeMatch(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    url: JString,
    source_url: JString,
    request_type: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let engine = match engine_ref(ptr) {
            Some(e) => e,
            None => return 0,
        };
        let url = match read_jstring(&mut env, &url) {
            Some(s) => s,
            None => return 0,
        };
        let source = read_jstring(&mut env, &source_url).unwrap_or_default();
        let request_type = read_jstring(&mut env, &request_type).unwrap_or_else(|| "other".to_string());
        let request = match Request::new(&url, &source, &request_type) {
            Ok(r) => r,
            Err(_) => return 0,
        };
        if engine.check_network_request(&request).matched {
            1
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

fn hide_css(selectors: impl IntoIterator<Item = String>) -> String {
    // one rule per selector: a selector the browser doesn't understand only drops its own rule
    let mut css = String::new();
    for sel in selectors {
        if sel.contains('{') || sel.contains('}') {
            continue;
        }
        css.push_str(&sel);
        css.push_str("{display:none!important}\n");
    }
    css
}

/// Element hiding and scriptlets for a page or frame address, as JSON:
/// {"css": "...", "script": "...", "generichide": false}
#[no_mangle]
pub extern "system" fn Java_com_phlox_tvwebbrowser_sofa_LumenAdblock_nativeCosmetic(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    url: JString,
) -> jstring {
    let json = catch_unwind(AssertUnwindSafe(|| {
        let engine = engine_ref(ptr)?;
        let url = read_jstring(&mut env, &url)?;
        let res = engine.url_cosmetic_resources(&url);
        let out = serde_json::json!({
            "css": hide_css(res.hide_selectors),
            "script": res.injected_script,
            "generichide": res.generichide,
        });
        Some(out.to_string())
    }))
    .ok()
    .flatten();
    match json {
        Some(s) => to_jstring(&mut env, &s),
        None => std::ptr::null_mut(),
    }
}

/// Generic hiding rules for the classes and ids found on the page (space separated).
/// Returns CSS (empty when nothing matches or the page has a $generichide exception).
#[no_mangle]
pub extern "system" fn Java_com_phlox_tvwebbrowser_sofa_LumenAdblock_nativeClassIds(
    mut env: JNIEnv,
    _this: JObject,
    ptr: jlong,
    url: JString,
    classes: JString,
    ids: JString,
) -> jstring {
    let css = catch_unwind(AssertUnwindSafe(|| {
        let engine = engine_ref(ptr)?;
        let url = read_jstring(&mut env, &url)?;
        let classes = read_jstring(&mut env, &classes).unwrap_or_default();
        let ids = read_jstring(&mut env, &ids).unwrap_or_default();
        let res = engine.url_cosmetic_resources(&url);
        if res.generichide {
            return Some(String::new());
        }
        let selectors = engine.hidden_class_id_selectors(
            classes.split_whitespace(),
            ids.split_whitespace(),
            &res.exceptions,
        );
        Some(hide_css(selectors))
    }))
    .ok()
    .flatten();
    match css {
        Some(s) => to_jstring(&mut env, &s),
        None => std::ptr::null_mut(),
    }
}
