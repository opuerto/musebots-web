import assert from 'node:assert/strict';
import test from 'node:test';
import { INSTRUMENTS, ORDER, noteName, readout } from '../../main/resources/public/notes.js';

test('pitches are named from middle C', () => {
  assert.equal(noteName('PIANO', 60), 'C4');
  assert.equal(noteName('SAX', 69), 'A4');
  assert.equal(noteName('BASS', 41), 'F2');
  assert.equal(noteName('BASS', 46), 'Bb2');
});

test('drums are named by the piece, not the pitch', () => {
  assert.equal(noteName('DRUMS', 36), 'Kick');
  assert.equal(noteName('DRUMS', 51), 'Ride');
  assert.equal(noteName('DRUMS', 49), 'Crash');
  assert.equal(noteName('DRUMS', 99), 'Drum 99');
});

test('every piece the server can write has a name', () => {
  for (const midi of [36, 38, 41, 44, 45, 47, 49, 50, 51]) {
    assert.ok(!noteName('DRUMS', midi).startsWith('Drum '), `piece ${midi}`);
  }
});

test('a chord reads from the lowest note up, with no repeats and at most four', () => {
  assert.equal(readout('PIANO', [76, 64, 67, 64]), 'E4 · G4 · E5');
  assert.equal(readout('PIANO', [60, 62, 64, 65, 67]), 'C4 · D4 · E4 · F4');
  assert.equal(readout('SAX', []), '');
});

test('drums keep the order they were hit in', () => {
  assert.equal(readout('DRUMS', [51, 36]), 'Ride · Kick');
});

test('every instrument has a picture, a colour and a place in the band', () => {
  assert.deepEqual(ORDER, ['SAX', 'PIANO', 'BASS', 'DRUMS']);
  for (const instrument of ORDER) {
    assert.match(INSTRUMENTS[instrument].asset, /^assets\/\w+\.svg$/);
    assert.match(INSTRUMENTS[instrument].color, /^#[0-9a-f]{6}$/i);
  }
});
