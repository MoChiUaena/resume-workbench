import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EditHistory } from '../src/editHistory.ts';

test('typing in one field forms a step while changing fields or pausing starts another', () => {
  const history = new EditHistory(); history.reset('original');
  history.record('n', 'name', 1000); history.record('na', 'name', 1100); history.record('name', 'name', 1200);
  history.record('headline', 'headline', 1300);
  assert.equal(history.undo(), 'name'); assert.equal(history.undo(), 'original');
  assert.equal(history.redo(), 'name'); assert.equal(history.redo(), 'headline');
  history.record('later', 'headline', 3000);
  assert.equal(history.undo(), 'headline');
});
test('blur and discrete operations preserve independent steps', () => {
  const history = new EditHistory(); history.reset('original');
  history.record('first', 'field', 1000); history.endGroup(); history.record('second', 'field', 1100);
  history.record('hidden', null, 1150); history.record('deleted', null, 1200);
  assert.equal(history.undo(), 'hidden'); assert.equal(history.undo(), 'second'); assert.equal(history.undo(), 'first');
});
test('editing after undo discards redo without merging over the retained state', () => {
  const history = new EditHistory(); history.reset('original');
  history.record('a', 'field', 1000); history.endGroup(); history.record('b', 'field', 1100);
  assert.equal(history.undo(), 'a');
  history.record('c', 'field', 1200);
  assert.equal(history.canRedo, false); assert.equal(history.undo(), 'a'); assert.equal(history.undo(), 'original');
});
test('acknowledging the same draft creates no new step and reset separates resumes', () => {
  const history = new EditHistory(); history.reset('original'); history.record('changed');
  history.record('changed'); assert.equal(history.undo(), 'original');
  history.reset('another resume'); assert.equal(history.canUndo, false); assert.equal(history.canRedo, false);
});
test('bounded history retains the reachable edits and handles either end safely', () => {
  const history = new EditHistory(4); history.reset('0');
  for (let i = 1; i <= 10; i++) history.record(String(i));
  assert.equal(history.entries.length, 4);
  assert.equal(history.undo(), '9'); assert.equal(history.undo(), '8'); assert.equal(history.undo(), '7');
  assert.equal(history.undo(), undefined);
  assert.equal(history.redo(), '8'); assert.equal(history.redo(), '9'); assert.equal(history.redo(), '10'); assert.equal(history.redo(), undefined);
});
