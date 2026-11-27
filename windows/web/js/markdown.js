import { marked } from '../vendor/marked.esm.js';

export function isMarkdownName(name) {
  return /\.(md|markdown)$/i.test(String(name || ''));
}

export function isSafeHttpUrl(url) {
  if (typeof url !== 'string' || !url.trim()) return false;
  try {
    const parsed = new URL(url.trim());
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

export function detectDirection(sample) {
  let arabic = 0;
  let letters = 0;
  for (const ch of String(sample || '')) {
    if (letters >= 500) break;
    if (/\s/.test(ch)) continue;
    const code = ch.charCodeAt(0);
    const isAr =
      (code >= 0x0600 && code <= 0x06ff) ||
      (code >= 0x0750 && code <= 0x077f) ||
      (code >= 0x08a0 && code <= 0x08ff) ||
      (code >= 0xfb50 && code <= 0xfdff) ||
      (code >= 0xfe70 && code <= 0xfeff);
    const isLat = (code >= 65 && code <= 90) || (code >= 97 && code <= 122);
    if (!isAr && !isLat) continue;
    letters++;
    if (isAr) arabic++;
  }
  if (letters < 20) return null;
  const ratio = arabic / letters;
  if (ratio >= 0.6) return true;
  if (ratio <= 0.4) return false;
  return null;
}

function defaultLexer(src) {
  return marked.lexer(src, { gfm: true, breaks: false });
}

function inlinePlainText(tokens, out) {
  if (!Array.isArray(tokens)) return;
  for (const tok of tokens) {
    switch (tok.type) {
      case 'text':
        if (Array.isArray(tok.tokens)) inlinePlainText(tok.tokens, out);
        else out.push(tok.text || '');
        break;
      case 'codespan':
      case 'escape':
      case 'html':
        out.push(tok.text || '');
        break;
      case 'br':
        out.push('\n');
        break;
      case 'image':
        if (tok.text) out.push(tok.text);
        break;
      case 'strong':
      case 'em':
      case 'del':
      case 'link':
        if (Array.isArray(tok.tokens)) inlinePlainText(tok.tokens, out);
        else out.push(tok.text || '');
        break;
      default:
        if (Array.isArray(tok.tokens)) inlinePlainText(tok.tokens, out);
        else if (tok.text) out.push(tok.text);
        break;
    }
  }
}

export function markdownToPlainText(src, lexerFn = defaultLexer) {
  if (!src || !src.trim()) return '';
  const tokens = lexerFn(src);
  const out = [];

  function walkList(listToken, depth) {
    const start = Number(listToken.start || 1);
    const indent = '  '.repeat(depth);
    listToken.items.forEach((item, index) => {
      let prefix;
      if (item.task) prefix = `[${item.checked ? 'x' : ' '}] `;
      else if (listToken.ordered) prefix = `${start + index}. `;
      else prefix = '- ';
      out.push(indent, prefix);

      const nested = [];
      let wroteInline = false;
      for (const child of item.tokens || []) {
        if (child.type === 'list') {
          nested.push(child);
        } else if (child.type === 'text' || child.type === 'paragraph') {
          if (wroteInline) out.push(' ');
          inlinePlainText(child.tokens || [child], out);
          wroteInline = true;
        } else if (child.type === 'code') {
          out.push((child.text || '').trim());
          wroteInline = true;
        } else if (Array.isArray(child.tokens)) {
          inlinePlainText(child.tokens, out);
          wroteInline = true;
        }
      }
      out.push('\n');
      for (const sub of nested) walkList(sub, depth + 1);
    });
    if (depth === 0) out.push('\n');
  }

  function walkBlocks(list) {
    for (const tok of list) {
      switch (tok.type) {
        case 'space':
          break;
        case 'paragraph':
        case 'heading':
          inlinePlainText(tok.tokens || [{ type: 'text', text: tok.text || '' }], out);
          out.push('\n\n');
          break;
        case 'code':
          out.push((tok.text || '').trim(), '\n\n');
          break;
        case 'blockquote':
          walkBlocks(tok.tokens || []);
          break;
        case 'list':
          walkList(tok, 0);
          break;
        case 'table': {
          const rows = [tok.header, ...(tok.rows || [])];
          for (const row of rows) {
            row.forEach((cell, colIdx) => {
              if (colIdx > 0) out.push(' | ');
              inlinePlainText(cell.tokens || [{ type: 'text', text: cell.text || '' }], out);
            });
            out.push('\n');
          }
          out.push('\n');
          break;
        }
        case 'hr':
          out.push('---\n\n');
          break;
        default:
          if (Array.isArray(tok.tokens)) walkBlocks(tok.tokens);
          else if (tok.text) out.push(tok.text, '\n\n');
          break;
      }
    }
  }

  walkBlocks(tokens);
  return out.join('').trim();
}

export function renderMarkdownPreview(src, container, options = {}) {
  const doc = options.doc || container.ownerDocument;
  const lexerFn = options.lexer || defaultLexer;
  const onLinkClick = options.onLinkClick;
  const emptyText = options.emptyText || 'Nothing to preview.';

  container.replaceChildren();
  if (!src || !src.trim()) {
    const empty = doc.createElement('p');
    empty.className = 'md-empty';
    empty.textContent = emptyText;
    container.appendChild(empty);
    return;
  }

  function appendInline(tokens, parent) {
    if (!Array.isArray(tokens)) return;
    for (const tok of tokens) {
      switch (tok.type) {
        case 'text':
          if (Array.isArray(tok.tokens) && tok.tokens.length > 0) {
            appendInline(tok.tokens, parent);
          } else {
            parent.appendChild(doc.createTextNode(tok.text || ''));
          }
          break;
        case 'escape':
          parent.appendChild(doc.createTextNode(tok.text || ''));
          break;
        case 'html': {
          const literal = doc.createElement('code');
          literal.className = 'md-raw-inline';
          literal.textContent = tok.text || '';
          parent.appendChild(literal);
          break;
        }
        case 'br':
          parent.appendChild(doc.createElement('br'));
          break;
        case 'codespan': {
          const code = doc.createElement('code');
          code.className = 'md-inline-code';
          code.textContent = tok.text || '';
          parent.appendChild(code);
          break;
        }
        case 'strong': {
          const el = doc.createElement('strong');
          appendInline(tok.tokens, el);
          parent.appendChild(el);
          break;
        }
        case 'em': {
          const el = doc.createElement('em');
          appendInline(tok.tokens, el);
          parent.appendChild(el);
          break;
        }
        case 'del': {
          const el = doc.createElement('del');
          appendInline(tok.tokens, el);
          parent.appendChild(el);
          break;
        }
        case 'link': {
          if (isSafeHttpUrl(tok.href)) {
            const a = doc.createElement('a');
            a.setAttribute('href', tok.href.trim());
            a.setAttribute('rel', 'noopener noreferrer');
            a.addEventListener('click', (e) => {
              e.preventDefault();
              if (onLinkClick) onLinkClick(tok.href.trim());
            });
            if (Array.isArray(tok.tokens) && tok.tokens.length > 0) appendInline(tok.tokens, a);
            else a.textContent = tok.text || tok.href;
            parent.appendChild(a);
          } else {
            const span = doc.createElement('span');
            span.className = 'md-blocked-link';
            if (Array.isArray(tok.tokens) && tok.tokens.length > 0) appendInline(tok.tokens, span);
            else span.textContent = tok.text || '';
            parent.appendChild(span);
          }
          break;
        }
        case 'image': {
          // Offline invariant: never fetch remote images inside Preview.
          const label = doc.createElement('span');
          label.className = 'md-image-placeholder';
          label.textContent = tok.text ? `[${tok.text}]` : '[image]';
          parent.appendChild(label);
          break;
        }
        default:
          if (Array.isArray(tok.tokens)) appendInline(tok.tokens, parent);
          else if (tok.text) parent.appendChild(doc.createTextNode(tok.text));
          break;
      }
    }
  }

  function appendBlocks(tokens, parent) {
    for (const tok of tokens) {
      switch (tok.type) {
        case 'space':
          break;
        case 'hr':
          parent.appendChild(doc.createElement('hr'));
          break;
        case 'heading': {
          const depth = Math.min(6, Math.max(1, Number(tok.depth || 1)));
          const h = doc.createElement(`h${depth}`);
          if (Array.isArray(tok.tokens)) appendInline(tok.tokens, h);
          else h.textContent = tok.text || '';
          parent.appendChild(h);
          break;
        }
        case 'paragraph': {
          const p = doc.createElement('p');
          if (Array.isArray(tok.tokens)) appendInline(tok.tokens, p);
          else p.textContent = tok.text || '';
          parent.appendChild(p);
          break;
        }
        case 'text': {
          const p = doc.createElement('p');
          if (Array.isArray(tok.tokens)) appendInline(tok.tokens, p);
          else p.textContent = tok.text || '';
          parent.appendChild(p);
          break;
        }
        case 'code': {
          const pre = doc.createElement('pre');
          pre.className = 'md-code-block';
          const code = doc.createElement('code');
          if (tok.lang) code.className = `language-${tok.lang}`;
          code.textContent = tok.text || '';
          pre.appendChild(code);
          parent.appendChild(pre);
          break;
        }
        case 'html': {
          const pre = doc.createElement('pre');
          pre.className = 'md-raw-html';
          pre.textContent = tok.raw || tok.text || '';
          parent.appendChild(pre);
          break;
        }
        case 'blockquote': {
          const bq = doc.createElement('blockquote');
          appendBlocks(tok.tokens || [], bq);
          parent.appendChild(bq);
          break;
        }
        case 'list': {
          const listEl = doc.createElement(tok.ordered ? 'ol' : 'ul');
          if (tok.ordered && tok.start && tok.start !== 1) {
            listEl.setAttribute('start', String(tok.start));
          }
          for (const item of tok.items || []) {
            const li = doc.createElement('li');
            if (item.task) {
              li.className = 'md-task-item';
              const box = doc.createElement('input');
              box.setAttribute('type', 'checkbox');
              box.disabled = true;
              box.checked = Boolean(item.checked);
              li.appendChild(box);
            }
            const itemTokens = item.tokens || [];
            if (itemTokens.length === 1 && itemTokens[0].type === 'text') {
              const span = doc.createElement('span');
              appendInline(itemTokens[0].tokens || [itemTokens[0]], span);
              li.appendChild(span);
            } else {
              for (const sub of itemTokens) {
                if (sub.type === 'text') {
                  const span = doc.createElement('span');
                  appendInline(sub.tokens || [sub], span);
                  li.appendChild(span);
                } else {
                  appendBlocks([sub], li);
                }
              }
            }
            listEl.appendChild(li);
          }
          parent.appendChild(listEl);
          break;
        }
        case 'table': {
          const wrap = doc.createElement('div');
          wrap.className = 'md-table-wrap';
          const table = doc.createElement('table');
          table.className = 'md-table';
          const thead = doc.createElement('thead');
          const headRow = doc.createElement('tr');
          (tok.header || []).forEach((cell, colIndex) => {
            const th = doc.createElement('th');
            const align = tok.align?.[colIndex];
            if (align) th.style.textAlign = align;
            if (Array.isArray(cell.tokens)) appendInline(cell.tokens, th);
            else th.textContent = cell.text || '';
            headRow.appendChild(th);
          });
          thead.appendChild(headRow);
          table.appendChild(thead);

          const tbody = doc.createElement('tbody');
          for (const row of tok.rows || []) {
            const tr = doc.createElement('tr');
            row.forEach((cell, colIndex) => {
              const td = doc.createElement('td');
              const align = tok.align?.[colIndex];
              if (align) td.style.textAlign = align;
              if (Array.isArray(cell.tokens)) appendInline(cell.tokens, td);
              else td.textContent = cell.text || '';
              tr.appendChild(td);
            });
            tbody.appendChild(tr);
          }
          table.appendChild(tbody);
          wrap.appendChild(table);
          parent.appendChild(wrap);
          break;
        }
        default:
          if (Array.isArray(tok.tokens)) appendBlocks(tok.tokens, parent);
          break;
      }
    }
  }

  const tokens = lexerFn(src);
  appendBlocks(tokens, container);
}

/**
 * Renders live-styled Markdown source + active search match into a <pre>
 * without modifying a single character of `src`, keeping caret positions aligned.
 */
export function renderSourceHighlight(src, container, options = {}) {
  const doc = options.doc || container.ownerDocument;
  const markdown = options.markdown !== false;
  const activeMatch = options.activeMatch || null;
  container.replaceChildren();

  if (!src) {
    if (options.trailingSentinel) container.appendChild(doc.createTextNode('\n'));
    return;
  }

  const classes = Array(src.length).fill('');
  const markRange = (start, end, cls) => {
    const lo = Math.max(0, start);
    const hi = Math.min(src.length, end);
    for (let i = lo; i < hi; i++) classes[i] = cls;
  };

  if (markdown) {
    const lines = src.split('\n');
    let offset = 0;
    let inFence = false;
    for (let li = 0; li < lines.length; li++) {
      const line = lines[li];
      const trimmed = line.trimStart();
      if (/^(```|~~~)/.test(trimmed)) {
        markRange(offset, offset + line.length, 'src-code');
        inFence = !inFence;
      } else if (inFence) {
        markRange(offset, offset + line.length, 'src-code');
      } else if (/^ {0,3}#{1,6}(?:\s|$)/.test(line)) {
        markRange(offset, offset + line.length, 'src-heading');
      } else if (/^ {0,3}>/.test(line)) {
        markRange(offset, offset + line.length, 'src-quote');
      } else {
        const listMatch = /^ {0,3}(?:[-+*]|[0-9]{1,9}[.)])(?=\s|$)/.exec(line);
        if (listMatch) {
          markRange(offset, offset + listMatch[0].length, 'src-marker');
        }
        for (const m of line.matchAll(/\[[^\]\n]+\]\([^)\n]+\)/g)) {
          markRange(offset + m.index, offset + m.index + m[0].length, 'src-link');
        }
        for (const m of line.matchAll(/\*\*[^*\n]+\*\*|__[^_\n]+__/g)) {
          markRange(offset + m.index, offset + m.index + m[0].length, 'src-strong');
        }
        for (const m of line.matchAll(/(?<!\*)\*[^*\n]+\*(?!\*)/g)) {
          markRange(offset + m.index, offset + m.index + m[0].length, 'src-em');
        }
        for (const m of line.matchAll(/`[^`\n]+`/g)) {
          markRange(offset + m.index, offset + m.index + m[0].length, 'src-code');
        }
      }
      offset += line.length + 1;
    }
  }

  if (activeMatch && activeMatch.end > activeMatch.start) {
    const s = Math.max(0, Math.min(src.length, activeMatch.start));
    const e = Math.max(s, Math.min(src.length, activeMatch.end));
    for (let i = s; i < e; i++) {
      classes[i] = classes[i] ? `${classes[i]} src-match` : 'src-match';
    }
  }

  let runStart = 0;
  for (let i = 1; i <= src.length; i++) {
    if (i === src.length || classes[i] !== classes[runStart]) {
      const slice = src.slice(runStart, i);
      const cls = classes[runStart];
      if (cls) {
        const span = doc.createElement('span');
        span.className = cls;
        if (cls.includes('src-match')) span.dataset.activeMatch = '1';
        span.textContent = slice;
        container.appendChild(span);
      } else {
        container.appendChild(doc.createTextNode(slice));
      }
      runStart = i;
    }
  }

  if (options.trailingSentinel && src.endsWith('\n')) {
    container.appendChild(doc.createTextNode('\n'));
  }
}
