/** Bounded, tab-local draft history. Revisions and server acknowledgements stay outside it. */
export class EditHistory {
  entries: string[] = [];
  cursor = -1;
  private group: unknown = null;
  private changedAt = 0;
  readonly limit: number;

  constructor(limit = 101) { this.limit = Math.max(2, limit); }
  get current() { return this.entries[this.cursor]; }
  get canUndo() { return this.cursor > 0; }
  get canRedo() { return this.cursor >= 0 && this.cursor < this.entries.length - 1; }

  reset(state: string) {
    this.entries = [state]; this.cursor = 0; this.endGroup();
  }
  endGroup() { this.group = null; this.changedAt = 0; }
  record(state: string, group: unknown = null, now = Date.now()) {
    if (state === this.current) return;
    const merge = group !== null && group === this.group && now - this.changedAt <= 800
      && this.cursor > 0 && !this.canRedo;
    this.entries = this.entries.slice(0, this.cursor + 1);
    if (merge) this.entries[this.cursor] = state;
    else { this.entries.push(state); this.cursor++; }
    if (this.entries.length > this.limit) {
      this.entries.shift(); this.cursor--;
    }
    this.group = group; this.changedAt = now;
  }
  undo() {
    this.endGroup();
    if (!this.canUndo) return undefined;
    return this.entries[--this.cursor];
  }
  redo() {
    this.endGroup();
    if (!this.canRedo) return undefined;
    return this.entries[++this.cursor];
  }
}
