export const MAX_HISTORY_STEPS = 100;
export const COALESCE_WINDOW_MS = 1000;

export class EditorHistory {
  constructor(initialText = '') {
    this.undoStack = [];
    this.redoStack = [];
    this.current = initialText;
    this.lastEditAt = 0;
    this.canCoalesce = false;
  }

  get canUndo() {
    return this.undoStack.length > 0;
  }

  get canRedo() {
    return this.redoStack.length > 0;
  }

  reset(text = '') {
    this.undoStack = [];
    this.redoStack = [];
    this.current = text;
    this.lastEditAt = 0;
    this.canCoalesce = false;
  }

  breakCoalescing() {
    this.canCoalesce = false;
  }

  record(nextText, now = Date.now(), allowCoalescing = true) {
    if (nextText === this.current) return false;
    const isRapidSingleEdit =
      allowCoalescing &&
      this.canCoalesce &&
      now - this.lastEditAt <= COALESCE_WINDOW_MS &&
      Math.abs(nextText.length - this.current.length) <= 1;

    if (!isRapidSingleEdit) {
      this.undoStack.push(this.current);
      if (this.undoStack.length > MAX_HISTORY_STEPS) {
        this.undoStack.shift();
      }
    }

    this.current = nextText;
    this.redoStack = [];
    this.lastEditAt = now;
    this.canCoalesce = allowCoalescing;
    return true;
  }

  undo() {
    if (!this.canUndo) return null;
    this.redoStack.push(this.current);
    this.current = this.undoStack.pop();
    this.canCoalesce = false;
    return this.current;
  }

  redo() {
    if (!this.canRedo) return null;
    this.undoStack.push(this.current);
    this.current = this.redoStack.pop();
    this.canCoalesce = false;
    return this.current;
  }
}
