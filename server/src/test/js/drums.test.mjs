// Run with: node --test server/src/test/js
//
// Uses the fake Tone.js in fake-tone.mjs, which takes the same arguments as the real one (see drums.js).
import assert from 'node:assert/strict';
import test from 'node:test';
import { buildDrumKit, DRUM_PIECES } from '../../main/resources/public/drums.js';
import { makeFakeTone } from './fake-tone.mjs';

const WHEN = 100.5; // an absolute audio time, well after any plausible velocity or duration

function hit(midi, velocity = 90) {
  const calls = [];
  buildDrumKit(makeFakeTone(calls)).play({ midi, velocity }, WHEN);
  return calls;
}

test('every piece the server can write makes a sound, and a snare is two layers', () => {
  for (const midi of DRUM_PIECES) {
    assert.equal(hit(midi).length, midi === 38 ? 2 : 1, `piece ${midi}`);
  }
  assert.deepEqual(hit(38).map((call) => call.kind).sort(), ['MembraneSynth', 'NoiseSynth'], 'the rattle of the snares and the body of the drum');
});

test('every hit fires at the requested time with a normalised velocity and a short duration', () => {
  for (const midi of DRUM_PIECES) {
    const [call] = hit(midi, 127);
    assert.equal(call.time, WHEN, `piece ${midi} was not scheduled at the audio time`);
    assert.equal(call.velocity, 1, `piece ${midi} velocity`);
    assert.ok(call.duration > 0 && call.duration <= 1, `piece ${midi} duration ${call.duration}`);
  }
});

test('the kit sends everything to the channel it is given, and to the speakers if it is given none', () => {
  const direct = makeFakeTone([]);
  buildDrumKit(direct);
  assert.ok(direct.created.filter((node) => !node.options?.noise).every((node) => node.toSpeakers || node.targets.length), 'every voice goes somewhere');
  assert.ok(direct.created.some((node) => node.toSpeakers));

  const routed = makeFakeTone([]);
  const out = new (class Channel { constructor() { this.reached = 0; } reachesSpeakers() { this.reached += 1; return true; } })();
  buildDrumKit(routed, { out });
  const voices = routed.created.filter((node) => node.targets.includes(out));
  assert.ok(voices.length >= 6, `kick, toms, snare, ride, hat and crash all feed the channel (${voices.length})`);
  assert.ok(routed.created.every((node) => !node.toSpeakers), 'nothing bypasses the mixer');
});

test('disposing the kit disposes every voice', () => {
  const tone = makeFakeTone([]);
  buildDrumKit(tone).dispose();
  assert.ok(tone.created.every((node) => node.disposed));
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
