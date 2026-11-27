import test from 'node:test';
import assert from 'node:assert/strict';

import {
  isMarkdownName,
  isSafeHttpUrl,
  detectDirection,
  markdownToPlainText,
  renderMarkdownPreview,
  renderSourceHighlight,
} from '../web/js/markdown.js';
import { EditorHistory, MAX_HISTORY_STEPS } from '../web/js/history.js';
import {
  TabStore,
  MAX_TABS,
  MAX_RECENT,
  findMatches,
} from '../web/js/state.js';

function createMockDom() {
  class MockNode {
    constructor(tagName) {
      this.tagName = tagName;
      this.children = [];
      this.attributes = {};
      this.dataset = {};
      this.style = {};
      this.className = '';
      this.listeners = {};
      this.checked = false;
      this.disabled = false;
      this._text = null;
    }
    setAttribute(k, v) {
      this.attributes[k] = String(v);
    }
    addEventListener(ev, fn) {
      this.listeners[ev] = fn;
    }
    appendChild(child) {
      this.children.push(child);
      return child;
    }
    append(...nodes) {
      for (const n of nodes) this.appendChild(n);
    }
    replaceChildren(...nodes) {
      this.children = [...nodes];
    }
    set textContent(v) {
      this._text = String(v);
      this.children = [];
    }
    get textContent() {
      if (this.tagName === '#text') return this._text || '';
      if (this._text !== null && this.children.length === 0) return this._text;
      return this.children.map((c) => c.textContent).join('');
    }
    findAll(pred, out = []) {
      if (pred(this)) out.push(this);
      for (const c of this.children) c.findAll?.(pred, out);
      return out;
    }
  }

  return {
    createElement(tag) {
      return new MockNode(tag.toLowerCase());
    },
    createTextNode(text) {
      const n = new MockNode('#text');
      n._text = String(text);
      return n;
    },
  };
}

test('isMarkdownName detects .md and .markdown case-insensitively', () => {
  assert.equal(isMarkdownName('notes.md'), true);
  assert.equal(isMarkdownName('SCRIPT.MARKDOWN'), true);
  assert.equal(isMarkdownName('readme.txt'), false);
});

test('isSafeHttpUrl allows only http/https and blocks javascript/file', () => {
  assert.equal(isSafeHttpUrl('https://github.com/sinach9995/Text-editor'), true);
  assert.equal(isSafeHttpUrl('http://127.0.0.1:4173'), true);
  assert.equal(isSafeHttpUrl('javascript:alert(1)'), false);
  assert.equal(isSafeHttpUrl('file:///C:/Windows/win.ini'), false);
  assert.equal(isSafeHttpUrl('data:text/html,hi'), false);
});

test('detectDirection detects Persian RTL, English LTR, and neutral short text', () => {
  assert.equal(detectDirection('این یک متن آزمایشی فارسی برای بررسی تشخیص جهت راست به چپ است.'), true);
  assert.equal(detectDirection('This is an English paragraph used to test left to right direction.'), false);
  assert.equal(detectDirection('short text'), null);
});

test('markdownToPlainText converts lists, task lists, code, and GFM tables cleanly', () => {
  const md = [
    '# Title',
    '',
    '- [x] Done task',
    '- [ ] Pending task',
    '  - Nested item',
    '',
    '| زمان | پرده | هدف |',
    '| :--- | :---: | ---: |',
    '| 0:00 | یک | قلاب مدرک |',
    '',
    '```js',
    'console.log("ok");',
    '```',
  ].join('\n');

  const plain = markdownToPlainText(md);
  assert.ok(plain.includes('Title'));
  assert.ok(plain.includes('[x] Done task'));
  assert.ok(plain.includes('[ ] Pending task'));
  assert.ok(plain.includes('  - Nested item'));
  assert.ok(plain.includes('زمان | پرده | هدف'));
  assert.ok(plain.includes('0:00 | یک | قلاب مدرک'));
  assert.ok(plain.includes('console.log("ok");'));
});

test('renderMarkdownPreview builds GFM tables with alignment, neutralizes raw HTML & unsafe links', () => {
  const doc = createMockDom();
  const root = doc.createElement('article');
  const clicked = [];

  const md = [
    '| زمان | پرده |',
    '| :--- | ---: |',
    '| 00:35 | **شروع** |',
    '',
    '[Safe](https://example.com) and [Bad](javascript:alert(1))',
    '',
    '<script>alert("xss")</script>',
  ].join('\n');

  renderMarkdownPreview(md, root, {
    doc,
    onLinkClick: (url) => clicked.push(url),
  });

  const tables = root.findAll((n) => n.tagName === 'table');
  assert.equal(tables.length, 1);
  const headers = tables[0].findAll((n) => n.tagName === 'th');
  assert.equal(headers.length, 2);
  assert.equal(headers[0].textContent, 'زمان');
  assert.equal(headers[0].style.textAlign, 'left');
  assert.equal(headers[1].style.textAlign, 'right');

  const anchors = root.findAll((n) => n.tagName === 'a');
  assert.equal(anchors.length, 1);
  assert.equal(anchors[0].attributes.href, 'https://example.com');
  let prevented = false;
  anchors[0].listeners.click({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.deepEqual(clicked, ['https://example.com']);

  const scripts = root.findAll((n) => n.tagName === 'script');
  assert.equal(scripts.length, 0);
  assert.ok(root.textContent.includes('<script>alert("xss")</script>'));
});

test('renderSourceHighlight preserves exact source text verbatim and marks active Find match', () => {
  const doc = createMockDom();
  const pre = doc.createElement('pre');
  const src = '# Heading\n\nSome **bold** and `code` with [link](https://example.com)\n- item\n';
  const idx = src.indexOf('bold');

  renderSourceHighlight(src, pre, {
    doc,
    markdown: true,
    activeMatch: { start: idx, end: idx + 4 },
    trailingSentinel: false,
  });

  assert.equal(pre.textContent, src);
  const active = pre.findAll((n) => n.dataset?.activeMatch === '1');
  assert.equal(active.length, 1);
  assert.equal(active[0].textContent, 'bold');
});

test('EditorHistory coalesces rapid typing, breaks on paste, and respects cap', () => {
  const h = new EditorHistory('a');
  h.record('ab', 100, true);
  h.record('abc', 300, true);
  assert.equal(h.undo(), 'a');
  assert.equal(h.redo(), 'abc');

  h.breakCoalescing();
  h.record('abcd', 350, true);
  assert.equal(h.undo(), 'abc');

  const big = new EditorHistory('');
  for (let i = 1; i <= MAX_HISTORY_STEPS + 20; i++) {
    big.breakCoalescing();
    big.record(`v${i}`, i * 2000, true);
  }
  assert.equal(big.undoStack.length, MAX_HISTORY_STEPS);
});

test('TabStore enforces caps, Windows path deduplication, preview defaults, and serialize/hydrate', () => {
  const store = new TabStore();
  const openMd = store.openFile({ path: 'D:\\Docs\\Script.MD', title: 'Script.MD', text: '| a | b |\n|---|---|\n| 1 | 2 |' });
  assert.equal(openMd.ok, true);
  assert.equal(openMd.tab.mdPreview, true);

  // Re-opening with different casing or slashes focuses existing tab instead of duplicating
  const reopen = store.openFile({ path: 'd:/docs/script.md', title: 'Script.MD', text: 'changed?' });
  assert.equal(reopen.switched, true);
  assert.equal(store.tabs.length, 1);

  const newMd = store.newDocument(true);
  assert.equal(newMd.ok, true);
  assert.equal(newMd.tab.mdPreview, false);
  // Repeating newDocument(true) while already on an untouched untitled.md reuses it
  assert.equal(store.newDocument(true).reused, true);
  store.updateText(newMd.tab.id, '# draft');

  for (let i = 2; i < MAX_TABS; i++) {
    const next = store.newDocument(false);
    assert.equal(next.ok, true);
    store.updateText(next.tab.id, `tab ${i}`);
  }
  assert.equal(store.newDocument(false).reason, 'tab_limit_reached');

  // Recent keeps up to MAX_RECENT and forgets cleanly
  for (let i = 0; i < MAX_RECENT + 5; i++) {
    store.recordRecent(`C:\\files\\f${i}.md`, `f${i}.md`);
  }
  assert.equal(store.recent.length, MAX_RECENT);
  store.forgetRecent(`C:\\files\\f${MAX_RECENT + 4}.md`);
  assert.equal(store.recent.length, MAX_RECENT - 1);

  const snap = store.serialize({ theme: 'light', lang: 'fa', fontSize: 20 });
  const restored = new TabStore();
  const prefs = restored.hydrate(snap);
  assert.equal(prefs.theme, 'light');
  assert.equal(restored.tabs.length, MAX_TABS);
  assert.equal(restored.tabs[0].mdPreview, true);
});

test('findMatches performs case-insensitive search without modifying text', () => {
  const hits = findMatches('Hermes Markdown markdown table', 'Markdown');
  assert.deepEqual(hits, [
    { start: 7, end: 15 },
    { start: 16, end: 24 },
  ]);
});
