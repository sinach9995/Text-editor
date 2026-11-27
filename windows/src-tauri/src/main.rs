#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{Emitter, Manager};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

const MAX_FILE_BYTES: u64 = 5 * 1024 * 1024;
const MAX_STATE_BYTES: usize = 8 * 1024 * 1024;

struct StartupFiles(Mutex<Vec<String>>);

#[derive(Serialize)]
struct FilePayload {
    path: String,
    title: String,
    text: String,
}

fn is_allowed_external_url(url: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    lower.starts_with("http://") || lower.starts_with("https://")
}

fn collect_file_args(args: &[String]) -> Vec<String> {
    args.iter()
        .skip(1)
        .filter(|arg| !arg.starts_with("--") && Path::new(arg).is_file())
        .cloned()
        .collect()
}

fn read_text_file_impl(path: &Path) -> Result<FilePayload, String> {
    let meta = fs::metadata(path).map_err(|e| format!("Could not open file: {e}"))?;
    if !meta.is_file() {
        return Err("Selected path is not a file".to_string());
    }
    if meta.len() > MAX_FILE_BYTES {
        return Err("File is larger than 5 MB".to_string());
    }
    let bytes = fs::read(path).map_err(|e| format!("Could not read file: {e}"))?;
    let mut text = String::from_utf8(bytes).map_err(|_| "File is not valid UTF-8 text".to_string())?;
    if text.starts_with('\u{FEFF}') {
        text.remove(0);
    }
    let title = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("Document.txt")
        .to_string();
    Ok(FilePayload {
        path: path.to_string_lossy().into_owned(),
        title,
        text,
    })
}

fn write_text_file_atomic(path: &Path, text: &str) -> Result<(), String> {
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent).map_err(|e| format!("Failed to prepare directory: {e}"))?;

    let unique = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let file_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("document");
    let tmp_path = parent.join(format!(".{file_name}.hermes-tmp-{unique}"));

    {
        let mut tmp = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&tmp_path)
            .map_err(|e| format!("Could not create temporary file: {e}"))?;
        if let Err(e) = tmp.write_all(text.as_bytes()).and_then(|_| tmp.sync_all()) {
            drop(tmp);
            let _ = fs::remove_file(&tmp_path);
            return Err(format!("Could not write file: {e}"));
        }
    }

    if let Err(e) = fs::rename(&tmp_path, path) {
        let _ = fs::remove_file(&tmp_path);
        return Err(format!("Could not save file: {e}"));
    }
    Ok(())
}

fn state_file_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("Could not resolve app data directory: {e}"))?;
    Ok(dir.join("session_state.json"))
}

#[tauri::command]
fn read_text_file(path: String) -> Result<FilePayload, String> {
    read_text_file_impl(Path::new(&path))
}

#[tauri::command]
fn write_text_file(path: String, text: String) -> Result<(), String> {
    write_text_file_atomic(Path::new(&path), &text)
}

#[tauri::command]
async fn pick_open_file(app: tauri::AppHandle) -> Result<Option<FilePayload>, String> {
    let app_clone = app.clone();
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app_clone
            .dialog()
            .file()
            .add_filter("Supported Files", &["md", "markdown", "txt"])
            .add_filter("Markdown", &["md", "markdown"])
            .add_filter("Text", &["txt"])
            .blocking_pick_file()
    })
    .await
    .map_err(|e| format!("Open dialog failed: {e}"))?;

    let Some(file_path) = picked else {
        return Ok(None);
    };
    let path = file_path
        .into_path()
        .map_err(|_| "Selected path is not a local file".to_string())?;
    read_text_file_impl(&path).map(Some)
}

#[tauri::command]
async fn pick_save_file(
    app: tauri::AppHandle,
    default_name: String,
    filter_name: String,
    extensions: Vec<String>,
) -> Result<Option<String>, String> {
    let picked = tauri::async_runtime::spawn_blocking(move || {
        let ext_refs: Vec<&str> = extensions.iter().map(|s| s.as_str()).collect();
        let mut builder = app.dialog().file().set_file_name(&default_name);
        if !ext_refs.is_empty() {
            builder = builder.add_filter(&filter_name, &ext_refs);
        }
        builder.blocking_save_file()
    })
    .await
    .map_err(|e| format!("Save dialog failed: {e}"))?;

    Ok(picked
        .and_then(|fp| fp.into_path().ok())
        .map(|p| p.to_string_lossy().into_owned()))
}

#[tauri::command]
fn load_app_state(app: tauri::AppHandle) -> Result<Option<String>, String> {
    let path = state_file_path(&app)?;
    if !path.is_file() {
        return Ok(None);
    }
    let meta = fs::metadata(&path).map_err(|e| e.to_string())?;
    if meta.len() > MAX_STATE_BYTES as u64 {
        return Ok(None);
    }
    let text = fs::read_to_string(&path).map_err(|e| e.to_string())?;
    Ok(Some(text))
}

#[tauri::command]
fn save_app_state(app: tauri::AppHandle, state_json: String) -> Result<(), String> {
    if state_json.len() > MAX_STATE_BYTES {
        return Err("Session state exceeds safety limit".to_string());
    }
    let path = state_file_path(&app)?;
    write_text_file_atomic(&path, &state_json)
}

#[tauri::command]
fn get_startup_files(state: tauri::State<'_, StartupFiles>) -> Vec<String> {
    let mut guard = state.0.lock().unwrap_or_else(|e| e.into_inner());
    std::mem::take(&mut *guard)
}

#[tauri::command]
fn open_external_url(app: tauri::AppHandle, url: String) -> Result<(), String> {
    if !is_allowed_external_url(&url) {
        return Err("Blocked unsafe URL scheme".to_string());
    }
    app.opener()
        .open_url(url.trim(), None::<&str>)
        .map_err(|e| format!("Could not open URL: {e}"))
}

fn main() {
    let initial_args: Vec<String> = std::env::args().collect();
    let startup_files = collect_file_args(&initial_args);

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            let paths = collect_file_args(&args);
            if !paths.is_empty() {
                let _ = app.emit("open-files", paths);
            }
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .manage(StartupFiles(Mutex::new(startup_files)))
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::DragDrop(tauri::DragDropEvent::Drop { paths, .. }) = event {
                let files: Vec<String> = paths
                    .iter()
                    .filter(|p| p.is_file())
                    .map(|p| p.to_string_lossy().into_owned())
                    .collect();
                if !files.is_empty() {
                    let _ = window.app_handle().emit("open-files", files);
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            read_text_file,
            write_text_file,
            pick_open_file,
            pick_save_file,
            load_app_state,
            save_app_state,
            get_startup_files,
            open_external_url,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Hermes Text Editor");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn external_url_whitelist_allows_only_http_https() {
        assert!(is_allowed_external_url("https://example.com/docs"));
        assert!(is_allowed_external_url("  HTTP://localhost:8080/test "));
        assert!(!is_allowed_external_url("javascript:alert(1)"));
        assert!(!is_allowed_external_url("file:///C:/Windows/System32"));
        assert!(!is_allowed_external_url("data:text/html,<script>"));
    }

    #[test]
    fn write_atomic_and_read_roundtrip_strips_bom_and_leaves_no_temp() {
        let base = std::env::temp_dir().join(format!(
            "hermes_win_test_{}",
            SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()
        ));
        fs::create_dir_all(&base).unwrap();
        let target = base.join("sample.md");

        write_text_file_atomic(&target, "\u{FEFF}# سلام\n\n| A | B |\n|---|---|\n| 1 | 2 |\n").unwrap();
        let loaded = read_text_file_impl(&target).unwrap();
        assert_eq!(loaded.title, "sample.md");
        assert!(loaded.text.starts_with("# سلام"));

        let leftover_tmp = fs::read_dir(&base)
            .unwrap()
            .filter_map(|e| e.ok())
            .any(|e| e.file_name().to_string_lossy().contains(".hermes-tmp-"));
        assert!(!leftover_tmp);
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn read_rejects_oversized_file() {
        let base = std::env::temp_dir().join(format!(
            "hermes_win_big_{}",
            SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()
        ));
        fs::create_dir_all(&base).unwrap();
        let target = base.join("big.txt");
        let f = fs::File::create(&target).unwrap();
        f.set_len(MAX_FILE_BYTES + 1).unwrap();

        let err = read_text_file_impl(&target).err().unwrap();
        assert!(err.contains("5 MB"));
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn collect_file_args_skips_binary_and_flags() {
        let base = std::env::temp_dir().join(format!(
            "hermes_win_args_{}",
            SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()
        ));
        fs::create_dir_all(&base).unwrap();
        let note = base.join("note.md");
        fs::write(&note, "# hi").unwrap();

        let args = vec![
            "hermes-text-editor.exe".to_string(),
            "--some-flag".to_string(),
            note.to_string_lossy().into_owned(),
            base.join("missing.md").to_string_lossy().into_owned(),
        ];
        let found = collect_file_args(&args);
        assert_eq!(found, vec![note.to_string_lossy().into_owned()]);
        let _ = fs::remove_dir_all(&base);
    }
}
