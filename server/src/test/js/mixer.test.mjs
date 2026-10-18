import assert from 'node:assert/strict';
import test from 'node:test';
import { buildMixer, softClip } from '../../main/resources/public/mixer.js';
import { makeFakeTone } from './fake-tone.mjs';

const setup = () => {
  const tone = makeFakeTone();
  return { tone, mixer: buildMixer(tone) };
};
const ofKind = (tone, name) => tone.created.filter((node) => node.constructor.name === name);

test('the master chain is a compressor, then a limiter, then a soft clipper, and only the clipper feeds the speakers', () => {
  const { tone } = setup();
  const [limiter] = ofKind(tone, 'Limiter');
  const [clipper] = ofKind(tone, 'WaveShaper');
  const [master] = ofKind(tone, 'Compressor');
  assert.equal(limiter.options, -1, 'the limiter holds the sum below -1 dBFS');
  assert.ok(master.targets.includes(limiter), 'the master compressor feeds the limiter');
  assert.ok(limiter.targets.includes(clipper), 'the limiter feeds the clipper');
  assert.equal(clipper.toSpeakers, true);
  assert.equal(tone.created.filter((node) => node.toSpeakers).length, 1, 'nothing else reaches the speakers');
  assert.equal(clipper.oversample, '4x');
});

test('the soft clipper leaves everything up to its knee alone and can never reach full scale', () => {
  for (let x = -0.8; x <= 0.8; x += 0.01) assert.equal(softClip(x), x);
  let previous = -Infinity;
  for (let x = -3; x <= 3; x += 0.01) {
    const y = softClip(x);
    assert.ok(y >= previous - 1e-12, 'it must never turn a louder input into a quieter output');
    assert.ok(Math.abs(y) < 0.953, `softClip(${x}) = ${y}`);
    previous = y;
  }
  assert.ok(Math.abs(softClip(1) - 0.952) < 0.001, 'full scale comes out at about -0.4 dBFS');
  assert.equal(softClip(-0.9), -softClip(0.9), 'the same for the negative half of the wave');
  assert.ok(Math.abs(softClip(0.8000001) - 0.8) < 1e-6, 'and no step at the knee');
  const curve = (new (makeFakeTone().WaveShaper)(softClip, 4)).options.mapping;
  assert.equal(curve(0.5), 0.5);
});

test('an instrument connected to a channel reaches the speakers', () => {
  const { tone, mixer } = setup();
  const { input } = mixer.channel({ gainDb: 3, pan: 0.2 });
  assert.equal(input.reachesSpeakers(), true);
});

test('a channel is tone shaping, then compression, then pan, then a fader, in that order', () => {
  const { tone, mixer } = setup();
  const before = tone.created.length;
  const { input } = mixer.channel({ highpass: 40, eq: { low: 2 }, compressor: { threshold: -20 }, pan: -0.5 });
  const made = tone.created.slice(before).map((node) => node.constructor.name);
  assert.deepEqual(made, ['Gain', 'Filter', 'EQ3', 'Compressor', 'Panner', 'Gain', 'Gain']);
  const chain = [];
  for (let node = input; node; node = node.targets[0]) {
    chain.push(node.constructor.name);
    if (chain.length > 8) break;
  }
  assert.deepEqual(chain.slice(0, 6), ['Gain', 'Filter', 'EQ3', 'Compressor', 'Panner', 'Gain']);
});

test('the high-pass filter and compressor are only added when asked for', () => {
  const { tone, mixer } = setup();
  const before = tone.created.length;
  mixer.channel({});
  const made = tone.created.slice(before).map((node) => node.constructor.name);
  assert.deepEqual(made, ['Gain', 'EQ3', 'Panner', 'Gain', 'Gain']);
});

test('the fader is the gain in decibels, the pan is where it is put, and the equaliser is set as asked', () => {
  const { tone, mixer } = setup();
  const before = tone.created.length;
  mixer.channel({ gainDb: -6, pan: 0.4, eq: { low: -2, mid: 1, high: 3, lowFrequency: 150 } });
  const nodes = tone.created.slice(before);
  const fader = nodes.filter((node) => node.constructor.name === 'Gain')[1];
  assert.ok(Math.abs(fader.options - Math.pow(10, -6 / 20)) < 1e-9);
  assert.equal(nodes.find((node) => node.constructor.name === 'Panner').options, 0.4);
  assert.deepEqual(nodes.find((node) => node.constructor.name === 'EQ3').options, { low: -2, mid: 1, high: 3, lowFrequency: 150, highFrequency: 2500 });
});

test('every channel sends to the one shared room reverb, by the amount asked for', () => {
  const { tone, mixer } = setup();
  const [reverb] = ofKind(tone, 'Reverb');
  mixer.channel({ send: 0.3 });
  mixer.channel({ send: 0.05 });
  assert.equal(ofKind(tone, 'Reverb').length, 1, 'a reverb is expensive, and one room sounds like one place');
  const sends = tone.created.filter((node) => node.targets.includes(reverb) && node.constructor.name === 'Gain');
  assert.deepEqual(sends.map((node) => node.options), [0.3, 0.05]);
  assert.equal(reverb.reachesSpeakers(), true, 'the reverb comes back into the master');
});

test('the reverb room is ready before the band plays', async () => {
  const { mixer } = setup();
  await mixer.ready;
});

test('taking the mixer down disposes everything it made', () => {
  const { tone, mixer } = setup();
  mixer.channel({ highpass: 40, compressor: { threshold: -20 } });
  mixer.dispose();
  assert.ok(tone.created.every((node) => node.disposed));
});
