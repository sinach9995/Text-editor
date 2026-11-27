import { EditorHistory } from './history.js';
import { isMarkdownName } from './markdown.js';

export const MAX_TABS = 10;
export const MAX_TOTAL_CHARS = 6_000_000;
export const MAX_RECENT = 12;
export const UNTOOLED_TXT = 'untitled.txt';
export const UNTOOLED_MD = 'untitled.md';

export function normalizePathKey(filePath) {
  if (!filePath) return '';
  return String(filePath).trim().replaceAll('/', '\\').toLowerCase();
}

export function fileNameFromPath(filePath) {
  if (!filePath) return UNTOOLED_TXT;
  const parts = String(filePath).split(/[\\/]/);
  return parts[parts.length - 1] || UNTOOLED_TXT;
}

export function baseName(title) {
  const s = String(title || 'document');
  const dot = s.lastIndexOf('.');
  return dot > 0 ? s.slice(0, dot) : s || 'document';
}

export function findMatches(text, query) {
  if (!text || !query) return [];
  const hay = text.toLowerCase();
  const needle = query.toLowerCase();
  const matches = [];
  let from = 0;
  while (from <= hay.length - needle.length) {
    const idx = hay.indexOf(needle, from);
    if (idx < 0) break;
    matches.push({ start: idx, end: idx + needle.length });
    from = idx + Math.max(1, needle.length);
  }
  return matches;
}

export class TabStore {
  constructor() {
    this.tabs = [];
    this.activeTabId = '';
    this.home = true;
    this.recent = [];
    this.nextId = 1;
    this.sessionCounter = 0;
  }

  get activeTab() {
    return this.tabs.find((tab) => tab.id === this.activeTabId) || null;
  }

  totalChars(excludingTabId = null) {
    let sum = 0;
    for (const tab of this.tabs) {
      if (tab.id !== excludingTabId) sum += tab.text.length;
    }
    return sum;
  }

  canAccept(incomingChars, replacingTabId = null) {
    if (!replacingTabId && this.tabs.length >= MAX_TABS) {
      return { ok: false, reason: 'tab_limit_reached' };
    }
    if (this.totalChars(replacingTabId) + incomingChars > MAX_TOTAL_CHARS) {
      return { ok: false, reason: 'tab_memory_reached' };
    }
    return { ok: true };
  }

  recordRecent(path, title) {
    if (!path) return;
    const key = normalizePathKey(path);
    const filtered = this.recent.filter((item) => normalizePathKey(item.path) !== key);
    this.recent = [{ path, title: title || fileNameFromPath(path) }, ...filtered].slice(0, MAX_RECENT);
  }

  forgetRecent(path) {
    const key = normalizePathKey(path);
    this.recent = this.recent.filter((item) => normalizePathKey(item.path) !== key);
  }

  createTab({ path = null, title = UNTOOLED_TXT, text = '', mdPreview = false, rtl = false, dirty = false, scrollTop = 0, sessionDirChecked = false }) {
    const id = `tab-${this.nextId++}`;
    return {
      id,
      path,
      title,
      text,
      dirty,
      mdPreview,
      rtl,
      scrollTop,
      sessionDirChecked,
      selectionStart: 0,
      selectionEnd: 0,
      history: new EditorHistory(text),
    };
  }

  newDocument(markdown = false) {
    const wantedTitle = markdown ? UNTOOLED_MD : UNTOOLED_TXT;
    const cur = this.activeTab;
    const untouched =
      cur &&
      !cur.path &&
      cur.text.length === 0 &&
      !cur.dirty &&
      cur.title === wantedTitle;
    if (untouched) {
      this.home = false;
      return { ok: true, tab: cur, reused: true };
    }

    const budget = this.canAccept(0);
    if (!budget.ok) return budget;

    const tab = this.createTab({
      title: wantedTitle,
      text: '',
      mdPreview: false, // New Markdown tabs start in source editing mode
      dirty: false,
    });
    this.tabs.push(tab);
    this.activeTabId = tab.id;
    this.home = false;
    this.sessionCounter++;
    return { ok: true, tab };
  }

  openFile({ path = null, title, text = '' }) {
    const resolvedTitle = title || fileNameFromPath(path);
    const key = normalizePathKey(path);
    if (key) {
      const existing = this.tabs.find((t) => normalizePathKey(t.path) === key);
      if (existing) {
        this.activeTabId = existing.id;
        this.home = false;
        this.recordRecent(existing.path, existing.title);
        return { ok: true, tab: existing, switched: true };
      }
    }

    const cur = this.activeTab;
    const reuseEmpty = cur && !cur.path && cur.text.length === 0 && !cur.dirty;
    const budget = this.canAccept(text.length, reuseEmpty ? cur.id : null);
    if (!budget.ok) return budget;

    const preview = isMarkdownName(resolvedTitle);
    if (reuseEmpty) {
      cur.path = path;
      cur.title = resolvedTitle;
      cur.text = text;
      cur.dirty = false;
      cur.mdPreview = preview;
      cur.scrollTop = 0;
      cur.sessionDirChecked = false;
      cur.selectionStart = 0;
      cur.selectionEnd = 0;
      cur.history.reset(text);
      this.activeTabId = cur.id;
      this.home = false;
      this.sessionCounter++;
      this.recordRecent(path, resolvedTitle);
      return { ok: true, tab: cur };
    }

    const tab = this.createTab({
      path,
      title: resolvedTitle,
      text,
      mdPreview: preview,
      dirty: false,
    });
    this.tabs.push(tab);
    this.activeTabId = tab.id;
    this.home = false;
    this.sessionCounter++;
    this.recordRecent(path, resolvedTitle);
    return { ok: true, tab };
  }

  switchTab(tabId) {
    if (!this.tabs.some((t) => t.id === tabId)) return false;
    this.activeTabId = tabId;
    this.home = false;
    return true;
  }

  goHome() {
    this.home = true;
  }

  showDocument() {
    if (this.tabs.length > 0) this.home = false;
  }

  closeTab(tabId) {
    const idx = this.tabs.findIndex((t) => t.id === tabId);
    if (idx < 0) return false;
    this.tabs.splice(idx, 1);
    if (this.tabs.length === 0) {
      this.activeTabId = '';
      this.home = true;
    } else if (this.activeTabId === tabId) {
      const next = this.tabs[Math.min(idx, this.tabs.length - 1)];
      this.activeTabId = next.id;
    }
    return true;
  }

  updateText(tabId, nextText, { now = Date.now(), allowCoalescing = true } = {}) {
    const tab = this.tabs.find((t) => t.id === tabId);
    if (!tab) return false;
    if (tab.text === nextText) return false;
    tab.history.record(nextText, now, allowCoalescing);
    tab.text = nextText;
    tab.dirty = true;
    return true;
  }

  undo(tabId = this.activeTabId) {
    const tab = this.tabs.find((t) => t.id === tabId);
    if (!tab) return null;
    const prev = tab.history.undo();
    if (prev === null) return null;
    tab.text = prev;
    tab.dirty = true;
    return prev;
  }

  redo(tabId = this.activeTabId) {
    const tab = this.tabs.find((t) => t.id === tabId);
    if (!tab) return null;
    const next = tab.history.redo();
    if (next === null) return null;
    tab.text = next;
    tab.dirty = true;
    return next;
  }

  markSaved(tabId, { path, title, writtenText }) {
    const tab = this.tabs.find((t) => t.id === tabId);
    if (!tab) return { deduped: false };
    const newPath = path ?? tab.path;
    const newTitle = title ?? (newPath ? fileNameFromPath(newPath) : tab.title);
    let deduped = false;
    const key = normalizePathKey(newPath);
    if (key) {
      const before = this.tabs.length;
      this.tabs = this.tabs.filter((t) => t.id === tabId || normalizePathKey(t.path) !== key);
      deduped = this.tabs.length !== before;
    }
    tab.path = newPath;
    tab.title = newTitle;
    if (tab.text === writtenText) tab.dirty = false;
    this.recordRecent(newPath, newTitle);
    return { deduped, title: newTitle };
  }

  discardActiveTab() {
    const tab = this.activeTab;
    if (!tab) return false;
    tab.history.breakCoalescing();
    if (tab.text !== '') {
      tab.history.record('', Date.now(), false);
      tab.text = '';
      tab.dirty = Boolean(tab.path);
    }
    return true;
  }

  togglePreview(tabId = this.activeTabId) {
    const tab = this.tabs.find((t) => t.id === tabId);
    if (!tab || !isMarkdownName(tab.title)) return false;
    tab.mdPreview = !tab.mdPreview;
    return tab.mdPreview;
  }

  setDirection(tabId, rtl) {
    const tab = this.tabs.find((t) => t.id === tabId);
    if (!tab) return;
    tab.rtl = Boolean(rtl);
  }

  serialize(extraPrefs = {}) {
    return {
      version: 1,
      activeTabId: this.activeTabId,
      home: this.home,
      recent: this.recent,
      prefs: extraPrefs,
      tabs: this.tabs.map((tab) => ({
        id: tab.id,
        path: tab.path,
        title: tab.title,
        text: tab.text,
        dirty: tab.dirty,
        mdPreview: tab.mdPreview,
        rtl: tab.rtl,
        scrollTop: tab.scrollTop,
      })),
    };
  }

  hydrate(snapshot) {
    if (!snapshot || typeof snapshot !== 'object') return {};
    this.recent = Array.isArray(snapshot.recent) ? snapshot.recent.slice(0, MAX_RECENT) : [];
    const loadedTabs = [];
    let runningChars = 0;
    for (const raw of snapshot.tabs || []) {
      if (loadedTabs.length >= MAX_TABS) break;
      const text = typeof raw.text === 'string' ? raw.text : '';
      if (runningChars + text.length > MAX_TOTAL_CHARS) continue;
      runningChars += text.length;
      const tab = this.createTab({
        path: typeof raw.path === 'string' ? raw.path : null,
        title: typeof raw.title === 'string' ? raw.title : UNTOOLED_TXT,
        text,
        dirty: Boolean(raw.dirty),
        mdPreview: raw.mdPreview !== undefined ? Boolean(raw.mdPreview) : isMarkdownName(raw.title),
        rtl: Boolean(raw.rtl),
        scrollTop: Number(raw.scrollTop) || 0,
        sessionDirChecked: true,
      });
      if (typeof raw.id === 'string' && raw.id) tab.id = raw.id;
      loadedTabs.push(tab);
    }
    this.tabs = loadedTabs;
    this.nextId = loadedTabs.length + 1;
    if (this.tabs.some((t) => t.id === snapshot.activeTabId)) {
      this.activeTabId = snapshot.activeTabId;
    } else {
      this.activeTabId = this.tabs[0]?.id || '';
    }
    this.home = this.tabs.length === 0 ? true : Boolean(snapshot.home);
    return snapshot.prefs || {};
  }
}
