const STATE_FALLBACK_KEY = 'hermes_windows_state_v1';

function tauriInvoke() {
  return window.__TAURI__?.core?.invoke || null;
}

function tauriListen() {
  return window.__TAURI__?.event?.listen || null;
}

function tauriWindow() {
  return window.__TAURI__?.window?.getCurrentWindow?.() || null;
}

export const isTauri = () => Boolean(tauriInvoke());

export async function readTextFile(path) {
  const invoke = tauriInvoke();
  if (!invoke) throw new Error('Direct path read requires the desktop app.');
  return invoke('read_text_file', { path });
}

export async function writeTextFile(path, text) {
  const invoke = tauriInvoke();
  if (!invoke) throw new Error('Direct path write requires the desktop app.');
  return invoke('write_text_file', { path, text });
}

export async function pickOpenFile() {
  const invoke = tauriInvoke();
  if (invoke) return invoke('pick_open_file');

  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.txt,.md,.markdown,text/plain,text/markdown';
    input.onchange = async () => {
      const file = input.files?.[0];
      if (!file) return resolve(null);
      const text = await file.text();
      resolve({ path: null, title: file.name, text });
    };
    input.click();
  });
}

export async function pickSaveFile({ defaultName, filterName, extensions }) {
  const invoke = tauriInvoke();
  if (invoke) {
    return invoke('pick_save_file', { defaultName, filterName, extensions });
  }
  return null;
}

export function downloadFallbackFile(filename, text) {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function loadAppState() {
  const invoke = tauriInvoke();
  if (invoke) {
    try {
      const raw = await invoke('load_app_state');
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }
  try {
    const raw = localStorage.getItem(STATE_FALLBACK_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export async function saveAppState(snapshot) {
  const payload = JSON.stringify(snapshot);
  const invoke = tauriInvoke();
  if (invoke) {
    return invoke('save_app_state', { stateJson: payload });
  }
  try {
    localStorage.setItem(STATE_FALLBACK_KEY, payload);
  } catch {
    // Ignore browser quota errors in fallback mode.
  }
}

export async function getStartupFiles() {
  const invoke = tauriInvoke();
  if (!invoke) return [];
  return invoke('get_startup_files');
}

export async function onOpenFiles(callback) {
  const listen = tauriListen();
  if (!listen) return () => {};
  return listen('open-files', (event) => {
    if (Array.isArray(event.payload)) callback(event.payload);
  });
}

export async function openExternalUrl(url) {
  const invoke = tauriInvoke();
  if (invoke) return invoke('open_external_url', { url });
  window.open(url, '_blank', 'noopener,noreferrer');
}

export async function setWindowTitle(title) {
  const win = tauriWindow();
  if (win?.setTitle) {
    try { await win.setTitle(title); } catch { }
  } else {
    document.title = title;
  }
}

export async function onCloseRequested(handler) {
  const win = tauriWindow();
  if (win?.onCloseRequested) {
    return win.onCloseRequested(handler);
  }
  window.addEventListener('beforeunload', handler);
  return () => window.removeEventListener('beforeunload', handler);
}
