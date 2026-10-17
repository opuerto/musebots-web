// Run with: node --test server/src/test/js
//
// A fake Tone.js whose instruments take the same arguments as the real ones (see drums.js), so a hit that
// passes its arguments in the wrong order fails here instead of silently misfiring in the browser.
import assert from 'node:assert/strict';
import test from 'node:test';
import { buildDrumKit, DRUM_PIECES } from '../../main/resources/public/drums.js';

const WHEN = 100.5; // an absolute audio time, well after any plausible velocity or duration

function fakeTone(calls) {
  class Instrument {
    constructor(options = {}) { this.options = options; }
    toDestination() { return this; }
    connect() { return this; }
    dispose() {}
  }
  // note, duration, time, velocity
  class Pitched extends Instrument {
    triggerAttackRelease(note, duration, time, velocity) {
      calls.push({ kind: this.constructor.name, note, duration, time, velocity });
    }
  }
  class MembraneSynth extends Pitched {}
  class MetalSynth extends Pitched {}
  // duration, time, velocity
  class NoiseSynth extends Instrument {
    triggerAttackRelease(duration, time, velocity) {
      calls.push({ kind: 'NoiseSynth', duration, time, velocity });
    }
  }
  class Filter extends Instrument {}
  return { MembraneSynth, MetalSynth, NoiseSynth, Filter };
}

function hit(midi, velocity = 90) {
  const calls = [];
  buildDrumKit(fakeTone(calls)).play({ midi, velocity }, WHEN);
  return calls;
}

test('every piece the server can write makes exactly one sound', () => {
  for (const midi of DRUM_PIECES) {
    assert.equal(hit(midi).length, 1, `piece ${midi}`);
  }
});

test('every hit fires at the requested time with a normalised velocity and a short duration', () => {
  for (const midi of DRUM_PIECES) {
    const [call] = hit(midi, 127);
    assert.equal(call.time, WHEN, `piece ${midi} was not scheduled at the audio time`);
    assert.equal(call.velocity, 1, `piece ${midi} velocity`);
    assert.ok(call.duration > 0 && call.duration <= 1, `piece ${midi} duration ${call.duration}`);
  }
});

test('cymbals are given a frequency, not a duration, as their note', () => {
  for (const midi of [44, 49, 51]) {
    const [call] = hit(midi);
    assert.equal(call.kind, 'MetalSynth');
    assert.ok(typeof call.note === 'number' && call.note >= 100, `piece ${midi} note ${call.note}`);
  }
});

test('drums and toms are given a pitch name, the snare has no pitch', () => {
  assert.equal(hit(36)[0].note, 'C1');
  assert.equal(hit(47)[0].note, 'A2');
  assert.equal('note' in hit(38)[0], false);
});

test('an unknown piece is ignored rather than throwing', () => {
  assert.deepEqual(hit(99), []);
});
