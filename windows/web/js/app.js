import { t, STRINGS } from './i18n.js';
import {
  isMarkdownName,
  detectDirection,
  markdownToPlainText,
  renderMarkdownPreview,
  renderSourceHighlight,
  splitMarkdownSegments,
  serializeTableMarkdown,
  findAnchorIndex,
} from './markdown.js';
import { TabStore, baseName, fileNameFromPath, findMatches } from './state.js';
import {
  readTextFile,
  writeTextFile,
  pickOpenFile,
  pickSaveFile,
  downloadFallbackFile,
  loadAppState,
  saveAppState,
  getStartupFiles,
  onOpenFiles,
  openExternalUrl,
  setWindowTitle,
  onCloseRequested,
} from './bridge.js';

const APP_VERSION = '1.7.1';
const MIN_FONT = 13;
const MAX_FONT = 30;
const AUTOSAVE_DELAY_MS = 350;

const store = new TabStore();
const prefs = {
  theme: 'dark',
  lang: 'en',
  fontSize: 17,
};

let finding = false;
let query = '';
let matchIndex = 0;
let autosaveTimer = null;
let toastTimer = null;
let draggingScrollbar = false;

// Live segmented Edit DOM state for the active tab; cleared on rebuilds.
let renderedSegments = [];
const rawTableModes = new Map();

const el = {
  homeBtn: document.getElementById('home-btn'),
  tabStrip: document.getElementById('tab-strip'),
  modePill: document.getElementById('mode-pill'),
  moreBtn: document.getElementById('more-btn'),
  moreMenu: document.getElementById('more-menu'),
  menuThemeLabel: document.getElementById('menu-theme-label'),
  menuThemeIcon: document.getElementById('menu-theme-icon'),
  menuLangLabel: document.getElementById('menu-lang-label'),
  menuDirLabel: document.getElementById('menu-dir-label'),
  menuPreviewLabel: document.getElementById('menu-preview-label'),
  menuFindLabel: document.getElementById('menu-find-label'),
  menuFontLabel: document.getElementById('menu-font-label'),
  fontDecBtn: document.getElementById('font-dec-btn'),
  fontIncBtn: document.getElementById('font-inc-btn'),
  fontVal: document.getElementById('font-val'),
  menuDiscardLabel: document.getElementById('menu-discard-label'),
  menuHelpLabel: document.getElementById('menu-help-label'),
  menuAboutLabel: document.getElementById('menu-about-label'),
  previewScroll: document.getElementById('preview-scroll'),
  previewContent: document.getElementById('preview-content'),
  editorScroll: document.getElementById('editor-scroll'),
  editorStage: document.getElementById('editor-stage'),
  fastScrollbar: document.getElementById('fast-scrollbar'),
  fastScrollbarTrack: document.getElementById('fast-scrollbar-track'),
  fastScrollbarThumb: document.getElementById('fast-scrollbar-thumb'),
  findBar: document.getElementById('find-bar'),
  findInput: document.getElementById('find-input'),
  findCount: document.getElementById('find-count'),
  findPrevBtn: document.getElementById('find-prev-btn'),
  findNextBtn: document.getElementById('find-next-btn'),
  findCloseBtn: document.getElementById('find-close-btn'),
  floatingActions: document.getElementById('floating-actions'),
  btnSave: document.getElementById('btn-save'),
  btnSaveMore: document.getElementById('btn-save-more'),
  saveMenu: document.getElementById('save-menu'),
  popSaveAs: document.getElementById('pop-save-as'),
  popExportTxt: document.getElementById('pop-export-txt'),
  btnNew: document.getElementById('btn-new'),
  btnNewMore: document.getElementById('btn-new-more'),
  newMenu: document.getElementById('new-menu'),
  popNewTxt: document.getElementById('pop-new-txt'),
  popNewMd: document.getElementById('pop-new-md'),
  btnOpen: document.getElementById('btn-open'),
  btnUndo: document.getElementById('btn-undo'),
  btnRedo: document.getElementById('btn-redo'),
  recentPage: document.getElementById('recent-page'),
  recentAppName: document.getElementById('recent-app-name'),
  recentTitle: document.getElementById('recent-title'),
  recentNewBtn: document.getElementById('recent-new-btn'),
  recentOpenBtn: document.getElementById('recent-open-btn'),
  recentList: document.getElementById('recent-list'),
  modalBackdrop: document.getElementById('modal-backdrop'),
  modalTitle: document.getElementById('modal-title'),
  modalBody: document.getElementById('modal-body'),
  modalActions: document.getElementById('modal-actions'),
  toast: document.getElementById('toast'),
};

function tr(key, ...args) {
  return t(prefs.lang, key, ...args);
}

function getRawTableSet(tabId) {
  let set = rawTableModes.get(tabId);
  if (!set) {
    set = new Set();
    rawTableModes.set(tabId, set);
  }
  return set;
}

function showToast(message) {
  el.toast.textContent = message;
  el.toast.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.toast.classList.add('hidden'), 2800);
}

function closeAllMenus() {
  el.moreMenu.classList.add('hidden');
  el.moreBtn.setAttribute('aria-expanded', 'false');
  el.saveMenu.classList.add('hidden');
  el.newMenu.classList.add('hidden');
}

function scheduleAutosave() {
  clearTimeout(autosaveTimer);
  autosaveTimer = setTimeout(() => {
    saveAppState(store.serialize({ ...prefs }));
  }, AUTOSAVE_DELAY_MS);
}

async function flushAutosave() {
  clearTimeout(autosaveTimer);
  await saveAppState(store.serialize({ ...prefs }));
}

function activeScrollContainer() {
  const tab = store.activeTab;
  if (!tab || store.home) return null;
  return isMarkdownName(tab.title) && tab.mdPreview ? el.previewScroll : el.editorScroll;
}

function saveActiveScroll() {
  const tab = store.activeTab;
  const scroller = activeScrollContainer();
  if (tab && scroller) {
    tab.scrollTop = scroller.scrollTop;
  }
}

function updateScrollbar() {
  const scroller = activeScrollContainer();
  if (!scroller || scroller.scrollHeight <= scroller.clientHeight + 2) {
    el.fastScrollbar.classList.add('hidden');
    return;
  }
  el.fastScrollbar.classList.remove('hidden');
  el.fastScrollbar.classList.toggle('finding', finding);

  const trackHeight = el.fastScrollbarTrack.clientHeight;
  if (trackHeight <= 0) return;
  const maxScroll = Math.max(1, scroller.scrollHeight - scroller.clientHeight);
  const ratio = scroller.clientHeight / scroller.scrollHeight;
  const thumbHeight = Math.max(32, Math.round(trackHeight * ratio));
  const travel = Math.max(1, trackHeight - thumbHeight);
  const progress = Math.min(1, Math.max(0, scroller.scrollTop / maxScroll));
  el.fastScrollbarThumb.style.height = `${thumbHeight}px`;
  el.fastScrollbarThumb.style.top = `${Math.round(progress * travel)}px`;
}

function resizeTextarea(textarea) {
  textarea.style.height = 'auto';
  textarea.style.height = `${textarea.scrollHeight}px`;
}

function focusFirstEditor() {
  const firstInput = el.editorStage.querySelector('.editor-input, .table-cell-input');
  if (firstInput) firstInput.focus();
}

// --- Viewport Scroll Anchoring between Preview and Edit ---

function getPreviewAnchorElements() {
  const nodes = [];
  for (const child of el.previewContent.children) {
    if (child.classList.contains('md-table-wrap') || child.tagName === 'UL' || child.tagName === 'OL') {
      const inner = child.querySelectorAll('tr[data-source-offset], li[data-source-offset]');
      if (inner.length > 0) {
        nodes.push(...inner);
        continue;
      }
    }
    if (child.dataset.sourceOffset !== undefined) {
      nodes.push(child);
    }
  }
  return nodes;
}

function measureLocalOffsetY(preEl, localOffset) {
  const text = preEl.textContent || '';
  if (!text) return preEl.getBoundingClientRect().top;

  let target = Math.max(0, Math.min(localOffset, text.length));
  if (target === text.length && text.length > 0) target = text.length - 1;
  while (target < text.length && text[target] === '\n') target++;
  if (target >= text.length) {
    target = Math.max(0, localOffset - 1);
  }

  const walker = document.createTreeWalker(preEl, NodeFilter.SHOW_TEXT);
  let node = walker.nextNode();
  let seen = 0;
  while (node) {
    const len = node.nodeValue.length;
    if (seen + len > target) {
      const idx = target - seen;
      const range = document.createRange();
      const endIdx = Math.min(len, idx + 1);
      range.setStart(node, idx);
      range.setEnd(node, endIdx);
      const rect = range.getClientRects()[0] || range.getBoundingClientRect();
      if (rect && (rect.height > 0 || rect.top !== 0)) return rect.top;
    }
    seen += len;
    node = walker.nextNode();
  }
  return preEl.getBoundingClientRect().top;
}

function lineStartOffsets(text) {
  const starts = [0];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '\n' && i + 1 < text.length) starts.push(i + 1);
  }
  return starts;
}

function capturePreviewAnchor() {
  const containerTop = el.previewScroll.getBoundingClientRect().top;
  const candidates = getPreviewAnchorElements();
  for (const node of candidates) {
    const rect = node.getBoundingClientRect();
    if (rect.bottom > containerTop + 4) {
      return {
        offset: Number(node.dataset.sourceOffset) || 0,
        delta: Math.max(0, Math.min(rect.top - containerTop, 80)),
      };
    }
  }
  return { offset: 0, delta: 0 };
}

function captureEditAnchor() {
  const containerTop = el.editorScroll.getBoundingClientRect().top;
  for (const item of renderedSegments) {
    if (item.seg.type === 'table' && item.rowEls) {
      for (const rowInfo of item.rowEls) {
        const rect = rowInfo.tr.getBoundingClientRect();
        if (rect.bottom > containerTop + 4) {
          return {
            offset: rowInfo.offset,
            delta: Math.max(0, Math.min(rect.top - containerTop, 80)),
          };
        }
      }
      continue;
    }
    if (item.pre && item.seg.raw.length > 0) {
      const rect = item.pre.getBoundingClientRect();
      if (rect.bottom <= containerTop + 4) continue;
      const starts = lineStartOffsets(item.seg.raw);
      let lo = 0;
      let hi = starts.length - 1;
      let best = 0;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        const y = measureLocalOffsetY(item.pre, starts[mid]);
        if (y <= containerTop + 4) {
          best = mid;
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      const lineTop = measureLocalOffsetY(item.pre, starts[best]);
      return {
        offset: item.seg.start + starts[best],
        delta: Math.max(0, Math.min(lineTop - containerTop, 80)),
      };
    }
  }
  return { offset: 0, delta: 0 };
}

function scrollToAnchorInPreview(anchor) {
  const candidates = getPreviewAnchorElements();
  const offsets = candidates.map((n) => Number(n.dataset.sourceOffset) || 0);
  const idx = findAnchorIndex(offsets, anchor.offset);
  if (idx < 0) return;
  const containerTop = el.previewScroll.getBoundingClientRect().top;
  const targetTop = candidates[idx].getBoundingClientRect().top;
  el.previewScroll.scrollTop = Math.max(0, el.previewScroll.scrollTop + (targetTop - containerTop - anchor.delta));
  if (store.activeTab) store.activeTab.scrollTop = el.previewScroll.scrollTop;
  updateScrollbar();
}

function scrollToAnchorInEdit(anchor) {
  if (renderedSegments.length === 0) return;
  const segOffsets = renderedSegments.map((item) => item.seg.start);
  const segIdx = findAnchorIndex(segOffsets, anchor.offset);
  const chosen = renderedSegments[Math.max(0, segIdx)];
  const containerTop = el.editorScroll.getBoundingClientRect().top;
  let targetTop = null;

  if (chosen.seg.type === 'table' && chosen.rowEls?.length) {
    const rIdx = findAnchorIndex(chosen.rowEls.map((r) => r.offset), anchor.offset);
    targetTop = chosen.rowEls[Math.max(0, rIdx)].tr.getBoundingClientRect().top;
  } else if (chosen.pre) {
    const localOffset = Math.max(0, Math.min(chosen.seg.raw.length, anchor.offset - chosen.seg.start));
    targetTop = measureLocalOffsetY(chosen.pre, localOffset);
  }

  if (targetTop !== null) {
    el.editorScroll.scrollTop = Math.max(0, el.editorScroll.scrollTop + (targetTop - containerTop - anchor.delta));
    if (store.activeTab) store.activeTab.scrollTop = el.editorScroll.scrollTop;
    updateScrollbar();
  }
}

function applyThemeAndLocale() {
  document.documentElement.dataset.theme = prefs.theme;
  document.documentElement.lang = prefs.lang;
  document.documentElement.dir = prefs.lang === 'fa' ? 'rtl' : 'ltr';
  document.documentElement.style.setProperty('--doc-font-size', `${prefs.fontSize}px`);
  document.documentElement.style.setProperty('--doc-line-height', `${Math.round(prefs.fontSize * 1.55 * 10) / 10}px`);
}

function showModal({ title, bodyNode, bodyText, buttons = [] }) {
  el.modalTitle.textContent = title;
  el.modalBody.replaceChildren();
  if (bodyNode) el.modalBody.appendChild(bodyNode);
  else if (bodyText) el.modalBody.textContent = bodyText;

  el.modalActions.replaceChildren();
  for (const btn of buttons) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'modal-btn';
    button.textContent = btn.label;
    button.addEventListener('click', () => {
      closeModal();
      btn.onClick?.();
    });
    el.modalActions.appendChild(button);
  }
  el.modalBackdrop.classList.remove('hidden');
}

function closeModal() {
  el.modalBackdrop.classList.add('hidden');
}

function updateWindowChromeTitle() {
  const tab = store.activeTab;
  if (!tab || store.home) {
    setWindowTitle(tr('app_name'));
    return;
  }
  const dot = tab.dirty ? '● ' : '';
  setWindowTitle(`${dot}${tab.title} — ${tr('app_name')}`);
}

function renderTabs() {
  el.tabStrip.replaceChildren();
  for (const tab of store.tabs) {
    const isActive = !store.home && tab.id === store.activeTabId;
    const chip = document.createElement('div');
    chip.className = `tab-chip${isActive ? ' active' : ''}`;
    chip.setAttribute('role', 'tab');
    chip.setAttribute('aria-selected', String(isActive));
    chip.title = tab.path || tab.title;
    chip.addEventListener('click', () => {
      if (tab.id === store.activeTabId && !store.home) return;
      saveActiveScroll();
      store.switchTab(tab.id);
      finding = false;
      renderAll();
      scheduleAutosave();
    });

    if (tab.dirty) {
      const dot = document.createElement('span');
      dot.className = 'tab-dirty-dot';
      chip.appendChild(dot);
    }

    const titleSpan = document.createElement('span');
    titleSpan.className = 'tab-title';
    titleSpan.textContent = tab.title;
    chip.appendChild(titleSpan);

    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'tab-close';
    closeBtn.textContent = '×';
    closeBtn.setAttribute('aria-label', tr('close'));
    closeBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      requestCloseTab(tab.id);
    });
    chip.appendChild(closeBtn);

    el.tabStrip.appendChild(chip);
    if (isActive) chip.scrollIntoView({ inline: 'nearest', block: 'nearest' });
  }
}

function renderRecent() {
  const showingRecent = store.home || store.tabs.length === 0;
  el.homeBtn.classList.toggle('active', showingRecent);
  el.recentPage.classList.toggle('hidden', !showingRecent);
  if (!showingRecent) return;

  el.recentAppName.textContent = tr('app_name');
  el.recentTitle.textContent = tr('recent_title');
  el.recentNewBtn.textContent = tr('ui_new');
  el.recentOpenBtn.textContent = tr('ui_open');

  const openPaths = new Set(store.tabs.map((tab) => (tab.path || '').toLowerCase()).filter(Boolean));
  const merged = [];
  const seen = new Set();

  for (const tab of store.tabs) {
    if (tab.path) {
      const key = tab.path.toLowerCase();
      if (!seen.has(key)) {
        seen.add(key);
        merged.push({ path: tab.path, title: tab.title, open: true });
      }
    }
  }
  for (const item of store.recent) {
    const key = (item.path || '').toLowerCase();
    if (key && !seen.has(key)) {
      seen.add(key);
      merged.push({ path: item.path, title: item.title, open: openPaths.has(key) });
    }
  }

  el.recentList.replaceChildren();
  if (merged.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'md-empty';
    empty.textContent = tr('recent_empty');
    el.recentList.appendChild(empty);
    return;
  }

  for (const entry of merged) {
    const row = document.createElement('div');
    row.className = 'recent-row';
    row.addEventListener('click', () => openFromPath(entry.path, { fromRecent: true }));

    const info = document.createElement('div');
    info.className = 'recent-row-info';

    const title = document.createElement('div');
    title.className = 'recent-row-title';
    title.textContent = entry.title || fileNameFromPath(entry.path);
    info.appendChild(title);

    const sub = document.createElement('div');
    sub.className = 'recent-row-sub';
    if (entry.open) {
      const dot = document.createElement('span');
      dot.className = 'recent-open-dot';
      sub.appendChild(dot);
      const openTxt = document.createElement('span');
      openTxt.textContent = `${tr('recent_open_now')} · ${entry.path}`;
      sub.appendChild(openTxt);
    } else {
      sub.textContent = entry.path;
    }
    info.appendChild(sub);
    row.appendChild(info);

    if (!entry.open) {
      const forgetBtn = document.createElement('button');
      forgetBtn.type = 'button';
      forgetBtn.className = 'recent-forget';
      forgetBtn.textContent = '×';
      forgetBtn.title = tr('recent_forget');
      forgetBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        store.forgetRecent(entry.path);
        renderRecent();
        scheduleAutosave();
      });
      row.appendChild(forgetBtn);
    }

    el.recentList.appendChild(row);
  }
}

function getMatchesForActiveTab() {
  const tab = store.activeTab;
  if (!finding || !tab) return [];
  return findMatches(tab.text, query);
}

// Recompute full tab.text after an in-place segment edit without tearing down DOM.
function syncTextFromSegments(tab, allowCoalescing = true) {
  let offset = 0;
  const parts = [];
  for (let i = 0; i < renderedSegments.length; i++) {
    const item = renderedSegments[i];
    const seg = item.seg;
    let piece = seg.raw;
    if (seg.placeholderOnly && piece.length > 0) {
      const nextItem = renderedSegments[i + 1];
      const prevItem = renderedSegments[i - 1];
      if (nextItem?.seg.type === 'table' && !piece.endsWith('\n')) {
        const caretStart = item.textarea ? item.textarea.selectionStart : piece.length;
        const caretEnd = item.textarea ? item.textarea.selectionEnd : piece.length;
        piece += '\n\n';
        seg.raw = piece;
        if (item.textarea && item.textarea.value !== piece) {
          item.textarea.value = piece;
          item.textarea.setSelectionRange(caretStart, caretEnd);
        }
        if (item.pre) renderSourceHighlight(piece, item.pre, { markdown: true, trailingSentinel: true });
      } else if (prevItem?.seg.type === 'table' && !prevItem.seg.trailingNewlines.includes('\n\n')) {
        const eol = prevItem.seg.eol || '\n';
        prevItem.seg.trailingNewlines = eol + eol;
        prevItem.seg.raw = serializeTableMarkdown(prevItem.seg);
        parts[parts.length - 1] = prevItem.seg.raw;
      }
      seg.placeholderOnly = false;
    }
    seg.start = offset;
    offset += piece.length;
    seg.end = offset;
    parts.push(piece);
  }

  const fullText = parts.join('');
  store.updateText(tab.id, fullText, { allowCoalescing });
  renderTabs();
  updateFindBadge();
  el.btnUndo.disabled = !tab.history.canUndo;
  el.btnRedo.disabled = !tab.history.canRedo;
  updateWindowChromeTitle();
  updateScrollbar();
  scheduleAutosave();
}

function updateFindBadge() {
  if (!finding) return;
  const matches = getMatchesForActiveTab();
  if (matches.length > 0 && matchIndex >= matches.length) matchIndex = matches.length - 1;
  el.findCount.textContent = matches.length > 0
    ? tr('match_count', matchIndex + 1, matches.length)
    : (query ? tr('no_matches') : '');
}

function createProseSegmentDom(tab, seg, rawIndex, onlySegment, activeMatch, isMd) {
  const dir = tab.rtl ? 'rtl' : 'ltr';
  const align = tab.rtl ? 'right' : 'left';
  const wrap = document.createElement('div');
  wrap.className = `prose-segment${onlySegment ? ' only-segment' : ''}`;

  if (rawIndex !== null) {
    const bar = document.createElement('div');
    bar.className = 'md-edit-table-bar';
    const backBtn = document.createElement('button');
    backBtn.type = 'button';
    backBtn.className = 'table-tool-btn';
    backBtn.textContent = tr('table_grid');
    backBtn.addEventListener('click', () => {
      getRawTableSet(tab.id).delete(rawIndex);
      renderDocument({ preserveScroll: true });
    });
    bar.appendChild(backBtn);
    wrap.appendChild(bar);
  }

  const stage = document.createElement('div');
  stage.className = 'prose-segment';

  const pre = document.createElement('pre');
  pre.className = 'source-highlight';
  pre.setAttribute('aria-hidden', 'true');
  pre.setAttribute('dir', dir);
  pre.style.textAlign = align;

  const ta = document.createElement('textarea');
  ta.className = 'editor-input';
  ta.spellcheck = false;
  ta.autocomplete = 'off';
  ta.autocapitalize = 'off';
  ta.setAttribute('wrap', 'soft');
  ta.setAttribute('dir', dir);
  ta.style.textAlign = align;
  if (onlySegment) ta.placeholder = tr('hint');
  ta.value = seg.raw;

  const localMatch =
    activeMatch && activeMatch.end > seg.start && activeMatch.start < seg.end
      ? {
          start: Math.max(0, activeMatch.start - seg.start),
          end: Math.min(seg.raw.length, activeMatch.end - seg.start),
        }
      : null;

  renderSourceHighlight(seg.raw, pre, {
    markdown: isMd,
    activeMatch: localMatch,
    trailingSentinel: true,
  });

  const item = { seg, pre, textarea: ta, root: wrap };

  ta.addEventListener('paste', () => {
    tab.history.breakCoalescing();
  });

  ta.addEventListener('input', () => {
    seg.raw = ta.value;
    renderSourceHighlight(seg.raw, pre, {
      markdown: isMd,
      activeMatch: null,
      trailingSentinel: true,
    });
    syncTextFromSegments(tab, true);
  });

  stage.append(pre, ta);
  wrap.appendChild(stage);
  return { root: wrap, item };
}

function createTableSegmentDom(tab, seg, segIndex, activeMatch) {
  const dir = tab.rtl ? 'rtl' : 'ltr';
  const wrap = document.createElement('div');
  wrap.className = 'md-edit-table-wrap';
  wrap.setAttribute('dir', dir);

  const bar = document.createElement('div');
  bar.className = 'md-edit-table-bar';

  const addRowBtn = document.createElement('button');
  addRowBtn.type = 'button';
  addRowBtn.className = 'table-tool-btn';
  addRowBtn.textContent = tr('table_add_row');

  const delRowBtn = document.createElement('button');
  delRowBtn.type = 'button';
  delRowBtn.className = 'table-tool-btn';
  delRowBtn.textContent = tr('table_del_row');
  delRowBtn.disabled = seg.rows.length === 0;

  const addColBtn = document.createElement('button');
  addColBtn.type = 'button';
  addColBtn.className = 'table-tool-btn';
  addColBtn.textContent = tr('table_add_col');

  const delColBtn = document.createElement('button');
  delColBtn.type = 'button';
  delColBtn.className = 'table-tool-btn';
  delColBtn.textContent = tr('table_del_col');
  delColBtn.disabled = seg.headers.length <= 1;

  const rawBtn = document.createElement('button');
  rawBtn.type = 'button';
  rawBtn.className = 'table-tool-btn';
  rawBtn.textContent = tr('table_raw');

  bar.append(addRowBtn, delRowBtn, addColBtn, delColBtn, rawBtn);
  wrap.appendChild(bar);

  const scroller = document.createElement('div');
  scroller.className = 'md-edit-table-scroll';
  const table = document.createElement('table');
  table.className = 'md-table md-edit-table';

  const rowEls = [];
  const cellGrid = [];
  const needle = finding && query ? query.toLowerCase() : '';
  const matchInTable = Boolean(activeMatch && activeMatch.end > seg.start && activeMatch.start < seg.end);
  let markedActiveCell = false;

  const makeCellInput = (value, rowIdx, colIdx, isHeader) => {
    const ta = document.createElement('textarea');
    ta.rows = 1;
    ta.className = 'table-cell-input';
    ta.spellcheck = false;
    ta.value = value;
    if (needle && String(value).toLowerCase().includes(needle)) {
      ta.classList.add('table-cell-match');
      if (matchInTable && !markedActiveCell) {
        ta.dataset.activeMatch = '1';
        markedActiveCell = true;
      }
    }

    ta.addEventListener('paste', () => {
      tab.history.breakCoalescing();
    });

    ta.addEventListener('input', () => {
      resizeTextarea(ta);
      if (isHeader) seg.headers[colIdx] = ta.value;
      else seg.rows[rowIdx][colIdx] = ta.value;
      seg.raw = serializeTableMarkdown(seg);
      syncTextFromSegments(tab, true);
    });

    ta.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        const flatRow = isHeader ? 0 : rowIdx + 1;
        const nextRow = cellGrid[flatRow + 1];
        if (nextRow?.[colIdx]) {
          nextRow[colIdx].focus();
        } else {
          // Last row Enter adds a new row and focuses the same column
          addRow(colIdx);
        }
      }
    });

    return ta;
  };

  const thead = document.createElement('thead');
  const headTr = document.createElement('tr');
  const headCells = [];
  seg.headers.forEach((cellVal, colIdx) => {
    const th = document.createElement('th');
    const align = seg.aligns[colIdx];
    if (align) th.style.textAlign = align;
    const input = makeCellInput(cellVal, -1, colIdx, true);
    headCells.push(input);
    th.appendChild(input);
    headTr.appendChild(th);
  });
  cellGrid.push(headCells);
  rowEls.push({ tr: headTr, offset: seg.rowOffsets?.[0] ?? seg.start });
  thead.appendChild(headTr);
  table.appendChild(thead);

  const tbody = document.createElement('tbody');
  seg.rows.forEach((rowVals, rowIdx) => {
    const tr = document.createElement('tr');
    const rowCells = [];
    seg.headers.forEach((_, colIdx) => {
      const td = document.createElement('td');
      const align = seg.aligns[colIdx];
      if (align) td.style.textAlign = align;
      const input = makeCellInput(rowVals[colIdx] ?? '', rowIdx, colIdx, false);
      rowCells.push(input);
      td.appendChild(input);
      tr.appendChild(td);
    });
    cellGrid.push(rowCells);
    rowEls.push({ tr, offset: seg.rowOffsets?.[1 + rowIdx] ?? seg.start });
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  scroller.appendChild(table);
  wrap.appendChild(scroller);

  const commitStructure = (focusTarget) => {
    tab.history.breakCoalescing();
    seg.raw = serializeTableMarkdown(seg);
    syncTextFromSegments(tab, false);
    const savedScroll = el.editorScroll.scrollTop;
    renderDocument({ preserveScroll: false });
    el.editorScroll.scrollTop = savedScroll;
    if (focusTarget) {
      const tables = el.editorStage.querySelectorAll('.md-edit-table');
      const targetTable = tables[focusTarget.tableOrder];
      const cell = targetTable?.querySelectorAll('tr')?.[focusTarget.flatRow]?.querySelectorAll('.table-cell-input')?.[focusTarget.col];
      cell?.focus();
    }
  };

  const tableOrderIndex = () => {
    let order = 0;
    for (let i = 0; i < renderedSegments.length; i++) {
      if (renderedSegments[i].seg === seg) return order;
      if (renderedSegments[i].seg.type === 'table' && renderedSegments[i].rowEls) order++;
    }
    return 0;
  };

  function addRow(focusCol = 0) {
    const order = tableOrderIndex();
    seg.rows.push(Array(seg.headers.length).fill(''));
    commitStructure({ tableOrder: order, flatRow: seg.rows.length, col: focusCol });
  }

  addRowBtn.addEventListener('click', () => addRow(0));
  delRowBtn.addEventListener('click', () => {
    if (seg.rows.length === 0) return;
    seg.rows.pop();
    commitStructure(null);
  });
  addColBtn.addEventListener('click', () => {
    const order = tableOrderIndex();
    const newCol = seg.headers.length;
    seg.headers.push(`Column ${newCol + 1}`);
    seg.aligns.push(null);
    seg.rows.forEach((r) => r.push(''));
    commitStructure({ tableOrder: order, flatRow: 0, col: newCol });
  });
  delColBtn.addEventListener('click', () => {
    if (seg.headers.length <= 1) return;
    seg.headers.pop();
    seg.aligns.pop();
    seg.rows.forEach((r) => r.pop());
    commitStructure(null);
  });
  rawBtn.addEventListener('click', () => {
    getRawTableSet(tab.id).add(segIndex);
    renderDocument({ preserveScroll: true });
  });

  return { root: wrap, item: { seg, rowEls, root: wrap } };
}

function renderEditorSegments(tab, activeMatch) {
  const isMd = isMarkdownName(tab.title);
  const rawSet = getRawTableSet(tab.id);
  el.editorStage.replaceChildren();
  renderedSegments = [];

  let segments = isMd
    ? splitMarkdownSegments(tab.text)
    : [{ type: 'text', start: 0, end: tab.text.length, raw: tab.text }];

  if (segments.length === 0) {
    segments = [{ type: 'text', start: 0, end: 0, raw: '' }];
  } else if (isMd) {
    if (segments[0].type === 'table') {
      segments.unshift({ type: 'text', start: 0, end: 0, raw: '', placeholderOnly: true });
    }
    const last = segments[segments.length - 1];
    if (last.type === 'table') {
      segments.push({ type: 'text', start: last.end, end: last.end, raw: '', placeholderOnly: true });
    }
  }

  const onlySegment = segments.length === 1 && segments[0].type === 'text';

  segments.forEach((seg, segIndex) => {
    if (seg.type === 'table' && !rawSet.has(segIndex)) {
      const built = createTableSegmentDom(tab, seg, segIndex, activeMatch);
      renderedSegments.push(built.item);
      el.editorStage.appendChild(built.root);
    } else {
      const rawToggleIndex = seg.type === 'table' ? segIndex : null;
      const built = createProseSegmentDom(tab, seg, rawToggleIndex, onlySegment, activeMatch, isMd);
      renderedSegments.push(built.item);
      el.editorStage.appendChild(built.root);
    }
  });

  // Resize table cell inputs after attachment so scrollHeight measures accurately.
  el.editorStage.querySelectorAll('.table-cell-input').forEach(resizeTextarea);
}

function renderDocument({ preserveScroll = true, scrollAnchor = null } = {}) {
  const tab = store.activeTab;
  const showingDoc = Boolean(tab) && !store.home;

  if (!showingDoc) {
    el.previewScroll.classList.add('hidden');
    el.editorScroll.classList.add('hidden');
    el.floatingActions.classList.add('hidden');
    el.findBar.classList.add('hidden');
    el.modePill.classList.add('hidden');
    el.fastScrollbar.classList.add('hidden');
    return;
  }

  const isMd = isMarkdownName(tab.title);
  const previewing = isMd && tab.mdPreview;

  el.modePill.classList.toggle('hidden', !isMd);
  if (isMd) {
    el.modePill.textContent = previewing ? tr('edit_short') : tr('preview_short');
  }

  el.findBar.classList.toggle('hidden', !finding);
  el.floatingActions.classList.toggle('hidden', finding || previewing);

  const dir = tab.rtl ? 'rtl' : 'ltr';

  if (previewing) {
    el.editorScroll.classList.add('hidden');
    el.previewScroll.classList.remove('hidden');
    el.previewContent.setAttribute('dir', dir);
    renderMarkdownPreview(tab.text, el.previewContent, {
      onLinkClick: (url) => openExternalUrl(url),
      emptyText: tr('preview_empty'),
    });
    if (scrollAnchor) {
      requestAnimationFrame(() => scrollToAnchorInPreview(scrollAnchor));
    } else if (preserveScroll) {
      requestAnimationFrame(() => {
        el.previewScroll.scrollTop = tab.scrollTop || 0;
        updateScrollbar();
      });
    }
  } else {
    el.previewScroll.classList.add('hidden');
    el.editorScroll.classList.remove('hidden');

    const matches = getMatchesForActiveTab();
    if (matches.length > 0 && matchIndex >= matches.length) matchIndex = matches.length - 1;
    const activeMatch = matches[matchIndex] || null;

    renderEditorSegments(tab, activeMatch);
    updateFindBadge();

    el.btnUndo.disabled = !tab.history.canUndo;
    el.btnRedo.disabled = !tab.history.canRedo;

    if (scrollAnchor) {
      requestAnimationFrame(() => scrollToAnchorInEdit(scrollAnchor));
    } else if (preserveScroll) {
      requestAnimationFrame(() => {
        el.editorScroll.scrollTop = tab.scrollTop || 0;
        updateScrollbar();
      });
    }
  }

  requestAnimationFrame(updateScrollbar);
}

function togglePreviewMode() {
  const tab = store.activeTab;
  if (!tab || store.home || !isMarkdownName(tab.title)) return;
  const anchor = tab.mdPreview ? capturePreviewAnchor() : captureEditAnchor();
  saveActiveScroll();
  store.togglePreview(tab.id);
  if (tab.mdPreview) finding = false;
  renderAll({ preserveScroll: false, scrollAnchor: anchor });
  scheduleAutosave();
}

function renderLabels() {
  const tab = store.activeTab;
  const showingDoc = Boolean(tab) && !store.home;
  const isMd = showingDoc && isMarkdownName(tab.title);

  el.menuThemeLabel.textContent = prefs.theme === 'dark' ? tr('theme_to_light') : tr('theme_to_dark');
  el.menuThemeIcon.src = prefs.theme === 'dark'
    ? 'assets/asora_theme_sun_minimal.png'
    : 'assets/asora_theme_moon_minimal.png';
  el.menuLangLabel.textContent = prefs.lang === 'fa' ? 'English' : 'فارسی';
  el.menuDirLabel.textContent = tr('dir_menu');
  el.menuPreviewLabel.textContent = (tab && tab.mdPreview) ? tr('edit_md') : tr('preview_md');
  el.menuFindLabel.textContent = tr('find');
  el.menuFontLabel.textContent = tr('font_size');
  el.fontVal.textContent = String(prefs.fontSize);
  el.fontDecBtn.disabled = prefs.fontSize <= MIN_FONT;
  el.fontIncBtn.disabled = prefs.fontSize >= MAX_FONT;
  el.menuDiscardLabel.textContent = tr('discard');
  el.menuHelpLabel.textContent = tr('help');
  el.menuAboutLabel.textContent = tr('about');

  el.popSaveAs.textContent = tr('ui_save_as');
  el.popExportTxt.textContent = tr('ui_export_txt');
  el.popNewTxt.textContent = tr('new_txt');
  el.popNewMd.textContent = tr('new_md');
  el.findInput.placeholder = tr('find_query');

  el.moreMenu.querySelectorAll('.doc-only').forEach((node) => node.classList.toggle('hidden', !showingDoc));
  el.moreMenu.querySelectorAll('.md-only').forEach((node) => node.classList.toggle('hidden', !isMd));
}

function maybeSuggestDirection(tab) {
  if (!tab || tab.sessionDirChecked || !tab.text.trim()) return;
  tab.sessionDirChecked = true;
  const detected = detectDirection(tab.text);
  if (detected === null || detected === tab.rtl) return;
  const toRtl = detected === true;
  showModal({
    title: tr(toRtl ? 'dir_sug_title_fa' : 'dir_sug_title_en'),
    bodyText: tr(toRtl ? 'dir_sug_text_fa' : 'dir_sug_text_en'),
    buttons: [
      { label: tr(toRtl ? 'dir_sug_keep_ltr' : 'dir_sug_keep_rtl') },
      {
        label: tr(toRtl ? 'dir_sug_go_rtl' : 'dir_sug_go_ltr'),
        onClick: () => {
          store.setDirection(tab.id, toRtl);
          renderDocument({ preserveScroll: false });
          scheduleAutosave();
        },
      },
    ],
  });
}

function renderAll({ preserveScroll = true, scrollAnchor = null } = {}) {
  applyThemeAndLocale();
  renderLabels();
  renderTabs();
  renderRecent();
  renderDocument({ preserveScroll, scrollAnchor });
  updateWindowChromeTitle();
}

function handleNewFile(markdown = false) {
  closeAllMenus();
  saveActiveScroll();
  const res = store.newDocument(markdown);
  if (!res.ok) {
    showToast(tr(res.reason));
    return;
  }
  finding = false;
  renderAll();
  focusFirstEditor();
  scheduleAutosave();
}

async function openFromPath(filePath, { fromRecent = false } = {}) {
  closeAllMenus();
  saveActiveScroll();
  const existing = store.tabs.find((t) => (t.path || '').toLowerCase() === filePath.toLowerCase());
  if (existing) {
    store.switchTab(existing.id);
    store.recordRecent(existing.path, existing.title);
    finding = false;
    renderAll();
    return;
  }
  try {
    const payload = await readTextFile(filePath);
    const res = store.openFile({ path: payload.path, title: payload.title, text: payload.text });
    if (!res.ok) {
      showToast(tr(res.reason));
      return;
    }
    finding = false;
    renderAll();
    maybeSuggestDirection(res.tab);
    scheduleAutosave();
  } catch (err) {
    if (fromRecent) {
      store.forgetRecent(filePath);
      renderRecent();
      scheduleAutosave();
    }
    const msg = String(err || '');
    showToast(msg.includes('5 MB') ? tr('err_file_too_large') : tr('err_open'));
  }
}

async function handleOpenPicker() {
  closeAllMenus();
  saveActiveScroll();
  try {
    const picked = await pickOpenFile();
    if (!picked) return;
    const res = store.openFile(picked);
    if (!res.ok) {
      showToast(tr(res.reason));
      return;
    }
    finding = false;
    renderAll();
    maybeSuggestDirection(res.tab);
    scheduleAutosave();
  } catch (err) {
    const msg = String(err || '');
    showToast(msg.includes('5 MB') ? tr('err_file_too_large') : tr('err_open'));
  }
}

async function saveTabAsFormat(tab, ext) {
  const defaultName = `${baseName(tab.title)}.${ext}`;
  const filterName = ext === 'md' ? 'Markdown (*.md)' : 'Text (*.txt)';
  const pickedPath = await pickSaveFile({ defaultName, filterName, extensions: [ext] });
  if (!pickedPath) {
    if (!window.__TAURI__) {
      downloadFallbackFile(defaultName, tab.text);
      showToast(tr('saved_copy', defaultName));
    }
    return false;
  }
  const textToWrite = tab.text;
  try {
    await writeTextFile(pickedPath, textToWrite);
    const hadPath = Boolean(tab.path);
    const saved = store.markSaved(tab.id, { path: pickedPath, writtenText: textToWrite });
    renderAll({ preserveScroll: false });
    scheduleAutosave();
    showToast(tr(saved.deduped ? 'saved_deduped' : (hadPath ? 'saved_copy' : 'saved'), saved.title));
    return true;
  } catch {
    showToast(tr('save_failed'));
    return false;
  }
}

function promptSaveAsFormat(tab) {
  const node = document.createElement('div');
  node.className = 'modal-choice-list';
  for (const [ext, key] of [['txt', 'saveas_txt'], ['md', 'saveas_md']]) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'modal-choice-btn';
    b.textContent = tr(key);
    b.addEventListener('click', () => {
      closeModal();
      saveTabAsFormat(tab, ext);
    });
    node.appendChild(b);
  }
  showModal({
    title: tr('saveas_title'),
    bodyNode: node,
    buttons: [{ label: tr('cancel') }],
  });
}

async function handleSave({ forceSaveAs = false } = {}) {
  closeAllMenus();
  const tab = store.activeTab;
  if (!tab || store.home) return false;
  tab.history.breakCoalescing();

  if (forceSaveAs || !tab.path) {
    promptSaveAsFormat(tab);
    return false;
  }

  const textToWrite = tab.text;
  try {
    await writeTextFile(tab.path, textToWrite);
    const saved = store.markSaved(tab.id, { path: tab.path, writtenText: textToWrite });
    renderTabs();
    updateWindowChromeTitle();
    scheduleAutosave();
    showToast(tr('saved', saved.title));
    return true;
  } catch {
    showToast(tr('err_read_only_save_as'));
    promptSaveAsFormat(tab);
    return false;
  }
}

async function handleExportPlainText() {
  closeAllMenus();
  const tab = store.activeTab;
  if (!tab || store.home) return;
  const plain = markdownToPlainText(tab.text);
  const defaultName = `${baseName(tab.title)}.txt`;
  const pickedPath = await pickSaveFile({ defaultName, filterName: 'Text (*.txt)', extensions: ['txt'] });
  if (!pickedPath) {
    if (!window.__TAURI__) {
      downloadFallbackFile(defaultName, plain);
      showToast(tr('exported', defaultName));
    }
    return;
  }
  try {
    await writeTextFile(pickedPath, plain);
    showToast(tr('exported', fileNameFromPath(pickedPath)));
  } catch {
    showToast(tr('save_failed'));
  }
}

function requestCloseTab(tabId) {
  const tab = store.tabs.find((t) => t.id === tabId);
  if (!tab) return;
  const finishClose = () => {
    rawTableModes.delete(tabId);
    store.closeTab(tabId);
    renderAll();
    scheduleAutosave();
  };
  if (!tab.dirty) {
    finishClose();
    return;
  }
  showModal({
    title: tr('discard_title'),
    bodyText: tr('discard_open'),
    buttons: [
      { label: tr('cancel') },
      { label: tr('discard'), onClick: finishClose },
    ],
  });
}

function handleUndo() {
  const tab = store.activeTab;
  if (!tab || store.home || (isMarkdownName(tab.title) && tab.mdPreview)) return;
  const prev = store.undo(tab.id);
  if (prev === null) return;
  renderTabs();
  renderDocument({ preserveScroll: true });
  updateWindowChromeTitle();
  scheduleAutosave();
}

function handleRedo() {
  const tab = store.activeTab;
  if (!tab || store.home || (isMarkdownName(tab.title) && tab.mdPreview)) return;
  const next = store.redo(tab.id);
  if (next === null) return;
  renderTabs();
  renderDocument({ preserveScroll: true });
  updateWindowChromeTitle();
  scheduleAutosave();
}

function scrollToActiveMatch() {
  const matches = getMatchesForActiveTab();
  const match = matches[matchIndex];
  if (!match) return;
  requestAnimationFrame(() => {
    const marker = el.editorStage.querySelector('[data-active-match="1"]');
    if (marker) {
      marker.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  });
}

function openFind() {
  closeAllMenus();
  const tab = store.activeTab;
  if (!tab || store.home) return;
  let anchor = null;
  if (isMarkdownName(tab.title) && tab.mdPreview) {
    anchor = capturePreviewAnchor();
    store.togglePreview(tab.id);
  }
  finding = true;
  renderAll({ preserveScroll: false, scrollAnchor: anchor });
  el.findInput.focus();
  el.findInput.select();
}

function closeFind() {
  finding = false;
  query = '';
  matchIndex = 0;
  el.findInput.value = '';
  renderDocument({ preserveScroll: true });
}

function applyFontSize(next) {
  const clamped = Math.max(MIN_FONT, Math.min(MAX_FONT, Math.round(next)));
  if (clamped === prefs.fontSize) return;
  prefs.fontSize = clamped;
  renderAll({ preserveScroll: true });
  scheduleAutosave();
}

function openDirectionDialog() {
  closeAllMenus();
  const tab = store.activeTab;
  if (!tab) return;
  const node = document.createElement('div');
  node.className = 'modal-choice-list';
  for (const [rtl, key] of [[false, 'dir_ltr'], [true, 'dir_rtl']]) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `modal-choice-btn${tab.rtl === rtl ? ' selected' : ''}`;
    btn.textContent = tr(key);
    btn.addEventListener('click', () => {
      closeModal();
      store.setDirection(tab.id, rtl);
      renderDocument({ preserveScroll: true });
      scheduleAutosave();
    });
    node.appendChild(btn);
  }
  showModal({
    title: tr('dir_menu'),
    bodyNode: node,
    buttons: [{ label: tr('cancel') }],
  });
}

function openHelpDialog() {
  closeAllMenus();
  const body = document.createElement('div');
  const intro = document.createElement('p');
  intro.textContent = tr('help_intro');
  body.appendChild(intro);

  const items = STRINGS[prefs.lang]?.help_items || STRINGS.en.help_items;
  for (const item of items) {
    const wrap = document.createElement('div');
    wrap.className = 'help-item';
    const title = document.createElement('div');
    title.className = 'help-item-title';
    title.textContent = item.title;
    const text = document.createElement('div');
    text.textContent = item.body;
    wrap.append(title, text);
    body.appendChild(wrap);
  }

  showModal({
    title: tr('help'),
    bodyNode: body,
    buttons: [{ label: tr('close') }],
  });
}

function openAboutDialog() {
  closeAllMenus();
  showModal({
    title: tr('app_name'),
    bodyText: tr('about_body', APP_VERSION),
    buttons: [{ label: tr('close') }],
  });
}

function setupEvents() {
  el.homeBtn.addEventListener('click', () => {
    closeAllMenus();
    saveActiveScroll();
    finding = false;
    store.goHome();
    renderAll();
    scheduleAutosave();
  });

  el.modePill.addEventListener('click', () => {
    closeAllMenus();
    togglePreviewMode();
  });

  el.moreBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    const willOpen = el.moreMenu.classList.contains('hidden');
    closeAllMenus();
    if (willOpen) {
      el.moreMenu.classList.remove('hidden');
      el.moreBtn.setAttribute('aria-expanded', 'true');
    }
  });

  el.moreMenu.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-action]');
    if (!btn) return;
    const action = btn.dataset.action;
    if (action === 'theme') {
      prefs.theme = prefs.theme === 'dark' ? 'light' : 'dark';
      closeAllMenus();
      renderAll({ preserveScroll: true });
      scheduleAutosave();
    } else if (action === 'lang') {
      prefs.lang = prefs.lang === 'fa' ? 'en' : 'fa';
      closeAllMenus();
      renderAll({ preserveScroll: true });
      scheduleAutosave();
    } else if (action === 'direction') {
      openDirectionDialog();
    } else if (action === 'toggle-preview') {
      closeAllMenus();
      togglePreviewMode();
    } else if (action === 'find') {
      openFind();
    } else if (action === 'discard') {
      closeAllMenus();
      showModal({
        title: tr('discard_title'),
        bodyText: tr('discard_clear'),
        buttons: [
          { label: tr('cancel') },
          {
            label: tr('discard'),
            onClick: () => {
              if (store.activeTab) rawTableModes.delete(store.activeTab.id);
              store.discardActiveTab();
              renderAll({ preserveScroll: false });
              scheduleAutosave();
            },
          },
        ],
      });
    } else if (action === 'help') {
      openHelpDialog();
    } else if (action === 'about') {
      openAboutDialog();
    }
  });

  el.fontDecBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    applyFontSize(prefs.fontSize - 1);
  });
  el.fontIncBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    applyFontSize(prefs.fontSize + 1);
  });

  el.editorScroll.addEventListener('click', (e) => {
    if (e.target === el.editorScroll || e.target === el.editorStage) {
      const inputs = el.editorStage.querySelectorAll('.editor-input');
      if (inputs.length > 0) inputs[inputs.length - 1].focus();
    }
  });

  el.editorScroll.addEventListener('scroll', () => {
    if (store.activeTab && !store.home && !store.activeTab.mdPreview) {
      store.activeTab.scrollTop = el.editorScroll.scrollTop;
    }
    updateScrollbar();
  });

  el.previewScroll.addEventListener('scroll', () => {
    if (store.activeTab && !store.home && store.activeTab.mdPreview) {
      store.activeTab.scrollTop = el.previewScroll.scrollTop;
    }
    updateScrollbar();
  });

  // Opposite-side draggable fast scrollbar
  const scrollFromPointer = (clientY) => {
    const scroller = activeScrollContainer();
    if (!scroller) return;
    const rect = el.fastScrollbarTrack.getBoundingClientRect();
    const maxScroll = Math.max(0, scroller.scrollHeight - scroller.clientHeight);
    if (rect.height <= 0 || maxScroll <= 0) return;
    const ratio = Math.min(1, Math.max(0, (clientY - rect.top) / rect.height));
    scroller.scrollTop = ratio * maxScroll;
  };

  el.fastScrollbarTrack.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    draggingScrollbar = true;
    el.fastScrollbar.classList.add('dragging');
    el.fastScrollbarTrack.setPointerCapture(e.pointerId);
    scrollFromPointer(e.clientY);
  });

  el.fastScrollbarTrack.addEventListener('pointermove', (e) => {
    if (!draggingScrollbar) return;
    scrollFromPointer(e.clientY);
  });

  const endDrag = () => {
    draggingScrollbar = false;
    el.fastScrollbar.classList.remove('dragging');
  };
  el.fastScrollbarTrack.addEventListener('pointerup', endDrag);
  el.fastScrollbarTrack.addEventListener('pointercancel', endDrag);

  // Horizontal mouse wheel scrolling for the tab strip
  el.tabStrip.addEventListener('wheel', (e) => {
    if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
      el.tabStrip.scrollLeft += e.deltaY;
      e.preventDefault();
    }
  }, { passive: false });

  // Ctrl + mouse wheel document zoom
  window.addEventListener('wheel', (e) => {
    if (e.ctrlKey && !store.home && store.activeTab) {
      e.preventDefault();
      applyFontSize(prefs.fontSize + (e.deltaY < 0 ? 1 : -1));
    }
  }, { passive: false });

  // Floating corner buttons + mini popovers
  el.btnSave.addEventListener('click', () => handleSave({ forceSaveAs: false }));
  el.btnSave.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    closeAllMenus();
    el.saveMenu.classList.remove('hidden');
  });
  el.btnSaveMore.addEventListener('click', (e) => {
    e.stopPropagation();
    const hidden = el.saveMenu.classList.contains('hidden');
    closeAllMenus();
    if (hidden) el.saveMenu.classList.remove('hidden');
  });
  el.popSaveAs.addEventListener('click', () => handleSave({ forceSaveAs: true }));
  el.popExportTxt.addEventListener('click', handleExportPlainText);

  el.btnNew.addEventListener('click', () => handleNewFile(false));
  el.btnNew.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    closeAllMenus();
    el.newMenu.classList.remove('hidden');
  });
  el.btnNewMore.addEventListener('click', (e) => {
    e.stopPropagation();
    const hidden = el.newMenu.classList.contains('hidden');
    closeAllMenus();
    if (hidden) el.newMenu.classList.remove('hidden');
  });
  el.popNewTxt.addEventListener('click', () => handleNewFile(false));
  el.popNewMd.addEventListener('click', () => handleNewFile(true));

  el.btnOpen.addEventListener('click', handleOpenPicker);
  el.btnUndo.addEventListener('click', handleUndo);
  el.btnRedo.addEventListener('click', handleRedo);

  el.recentNewBtn.addEventListener('click', () => handleNewFile(false));
  el.recentOpenBtn.addEventListener('click', handleOpenPicker);

  // Find bar events
  el.findInput.addEventListener('input', () => {
    query = el.findInput.value;
    matchIndex = 0;
    renderDocument({ preserveScroll: true });
    scrollToActiveMatch();
  });
  el.findPrevBtn.addEventListener('click', () => {
    const matches = getMatchesForActiveTab();
    if (matches.length === 0) return;
    matchIndex = (matchIndex - 1 + matches.length) % matches.length;
    renderDocument({ preserveScroll: true });
    scrollToActiveMatch();
  });
  el.findNextBtn.addEventListener('click', () => {
    const matches = getMatchesForActiveTab();
    if (matches.length === 0) return;
    matchIndex = (matchIndex + 1) % matches.length;
    renderDocument({ preserveScroll: true });
    scrollToActiveMatch();
  });
  el.findCloseBtn.addEventListener('click', closeFind);

  document.addEventListener('click', (e) => {
    if (!e.target.closest('#more-menu, #more-btn, #save-menu, #btn-save-more, #new-menu, #btn-new-more')) {
      closeAllMenus();
    }
  });

  el.modalBackdrop.addEventListener('click', (e) => {
    if (e.target === el.modalBackdrop) closeModal();
  });

  // Keyboard shortcuts
  window.addEventListener('keydown', (e) => {
    const ctrl = e.ctrlKey || e.metaKey;
    if (e.key === 'Escape') {
      if (!el.modalBackdrop.classList.contains('hidden')) {
        closeModal();
      } else if (finding) {
        closeFind();
      } else {
        closeAllMenus();
      }
      return;
    }

    if (!ctrl) return;
    const key = e.key.toLowerCase();

    if (key === 'n') {
      e.preventDefault();
      handleNewFile(e.shiftKey);
    } else if (key === 'o') {
      e.preventDefault();
      handleOpenPicker();
    } else if (key === 's') {
      e.preventDefault();
      handleSave({ forceSaveAs: e.shiftKey });
    } else if (key === 'e') {
      e.preventDefault();
      togglePreviewMode();
    } else if (key === 'w') {
      e.preventDefault();
      if (store.activeTab && !store.home) requestCloseTab(store.activeTab.id);
    } else if (key === 'f') {
      e.preventDefault();
      openFind();
    } else if (key === 'z') {
      e.preventDefault();
      if (e.shiftKey) handleRedo();
      else handleUndo();
    } else if (key === 'y') {
      e.preventDefault();
      handleRedo();
    } else if (key === '=' || key === '+') {
      e.preventDefault();
      applyFontSize(prefs.fontSize + 1);
    } else if (key === '-') {
      e.preventDefault();
      applyFontSize(prefs.fontSize - 1);
    } else if (key === '0') {
      e.preventDefault();
      applyFontSize(17);
    } else if (e.key === 'Tab' && store.tabs.length > 1) {
      e.preventDefault();
      saveActiveScroll();
      const curIdx = store.tabs.findIndex((t) => t.id === store.activeTabId);
      const delta = e.shiftKey ? -1 : 1;
      const nextIdx = ((curIdx < 0 ? 0 : curIdx) + delta + store.tabs.length) % store.tabs.length;
      store.switchTab(store.tabs[nextIdx].id);
      renderAll();
      scheduleAutosave();
    }
  });

  window.addEventListener('resize', () => {
    el.editorStage.querySelectorAll('.table-cell-input').forEach(resizeTextarea);
    updateScrollbar();
  });
}

async function init() {
  setupEvents();

  const saved = await loadAppState();
  const loadedPrefs = store.hydrate(saved);
  if (loadedPrefs.theme === 'light' || loadedPrefs.theme === 'dark') prefs.theme = loadedPrefs.theme;
  if (loadedPrefs.lang === 'en' || loadedPrefs.lang === 'fa') prefs.lang = loadedPrefs.lang;
  if (typeof loadedPrefs.fontSize === 'number') {
    prefs.fontSize = Math.max(MIN_FONT, Math.min(MAX_FONT, loadedPrefs.fontSize));
  }

  renderAll();

  const startupFiles = await getStartupFiles();
  for (const path of startupFiles) {
    await openFromPath(path);
  }

  await onOpenFiles(async (paths) => {
    for (const path of paths) {
      await openFromPath(path);
    }
  });

  await onCloseRequested(async () => {
    saveActiveScroll();
    await flushAutosave();
  });
}

init();
