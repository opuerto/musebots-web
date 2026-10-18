import assert from 'node:assert/strict';
import test, { beforeEach } from 'node:test';
import { buildInstruments, loudness } from '../../main/resources/public/instruments.js';
import { forgetLoadedSets } from '../../main/resources/public/sampleSets.js';
import { makeFakeTone } from './fake-tone.mjs';

// The bass soundfont is a JavaScript file fetched as text; give the loader one with notes inside the band's bass register.
const BASS_NOTES = ['C2', 'D2', 'E2', 'F2', 'G2', 'A2', 'B2', 'C3'];
const SOUNDFONT_JS = `MIDI.Soundfont.acoustic_bass = {${BASS_NOTES.map((n) => `"${n}": "data:audio/mp3;base64,AAAA"`).join(',')},}`;
globalThis.fetch = async () => ({ ok: true, status: 200, text: async () => SOUNDFONT_JS });

beforeEach(() => forgetLoadedSets());

const note = (midi, durationMs = 450, velocity = 90) => ({ midi, durationMs, velocity });
const samplers = (tone) => tone.created.filter((node) => node.constructor.name === 'Sampler');
const piano = (tone) => samplers(tone).find((sampler) => 'A0' in sampler.urls);
const bass = (tone) => samplers(tone).find((sampler) => 'F2' in sampler.urls); // F2 is only in the bass set (the piano has C, D#, F# and A of each octave)
const callsTo = (calls, node) => calls.filter((call) => call.node === node);

test('loudness: soft notes are really soft, and the curve never leaves 0 to 1', () => {
  assert.equal(loudness(127), 1);
  assert.ok(loudness(64) < 0.5, 'half the velocity is less than half the loudness');
  assert.ok(loudness(1) > 0 && loudness(1) < 0.01);
  assert.equal(loudness(500), 1);
  assert.equal(loudness(-5), loudness(1));
  let previous = 0;
  for (let velocity = 1; velocity <= 127; velocity += 1) {
    assert.ok(loudness(velocity) > previous, 'louder velocity, louder note');
    previous = loudness(velocity);
  }
});

test('the default band: piano and bass are recordings, the sax and drums are synthesized by choice', async () => {
  const calls = [];
  const tone = makeFakeTone(calls);
  const band = await buildInstruments(tone); // no sound chosen: the default
  assert.equal(samplers(tone).length, 2);
  assert.equal(tone.loads.length, 30 + BASS_NOTES.length, 'only the piano and bass recordings are downloaded');
  assert.deepEqual(band.sources.PIANO, { kind: 'samples' });
  assert.deepEqual(band.sources.BASS, { kind: 'samples' });
  assert.deepEqual(band.sources.SAX, { kind: 'synth', reason: 'chosen' });
  assert.deepEqual(band.sources.DRUMS, { kind: 'synth', reason: 'chosen' });
  assert.deepEqual(Object.keys(band.instruments).sort(), ['BASS', 'DRUMS', 'PIANO', 'SAX']);

  band.instruments.SAX.play(note(69), 2);
  assert.equal(calls.at(-1).kind, 'MonoSynth', 'the sax is the synthesizer: the recorded one sounded like a flute');
  band.instruments.DRUMS.play({ midi: 36, velocity: 80 }, 2);
  assert.equal(calls.at(-1).kind, 'MembraneSynth', 'and so is the kick');
  band.instruments.DRUMS.play({ midi: 51, velocity: 80 }, 2);
  assert.equal(calls.at(-1).kind, 'MetalSynth');
});

test('the samplers are given decoded recordings (not addresses) and their set\'s attack and release', async () => {
  const tone = makeFakeTone();
  await buildInstruments(tone);
  for (const sampler of samplers(tone)) {
    assert.ok(Object.values(sampler.urls).every((buffer) => buffer.fakeAudioBuffer), 'already-decoded buffers, so a rebuilt band reloads nothing');
  }
  assert.equal(Object.keys(piano(tone).urls).length, 30);
  assert.equal(piano(tone).release, 0.8, 'the piano rings on');
  assert.equal(bass(tone).release, 0.14, 'the bass is plucked: a short release');
});

test('a piano note is played with the Tone.js argument order: note name, seconds, time, loudness', async () => {
  const calls = [];
  const tone = makeFakeTone(calls);
  const band = await buildInstruments(tone);
  band.instruments.PIANO.play(note(64, 450, 100), 12.5);
  const [call] = callsTo(calls, piano(tone));
  assert.deepEqual({ note: call.note, duration: call.duration, time: call.time, velocity: call.velocity },
    { note: 'E4', duration: 0.45, time: 12.5, velocity: loudness(100) });
});

test('a bass note is played the same way', async () => {
  const calls = [];
  const tone = makeFakeTone(calls);
  const band = await buildInstruments(tone);
  band.instruments.BASS.play(note(41, 300, 95), 3);
  const [call] = callsTo(calls, bass(tone));
  assert.deepEqual({ note: call.note, duration: call.duration, time: call.time, velocity: call.velocity },
    { note: 'F2', duration: 0.3, time: 3, velocity: loudness(95) });
});

test('if the piano cannot be loaded it falls back to the synthesizer and says why, and the bass is not affected', async () => {
  const calls = [];
  const tone = makeFakeTone(calls, { badUrls: ['salamander'] });
  const band = await buildInstruments(tone);
  assert.equal(band.sources.PIANO.kind, 'synth');
  assert.match(band.sources.PIANO.reason, /only 0 of 30 files of "Piano.*could be loaded/);
  assert.equal(band.sources.BASS.kind, 'samples');
  band.instruments.PIANO.play(note(64), 2);
  assert.equal(calls.at(-1).kind, 'PolySynth');
  band.instruments.BASS.play(note(41), 2);
  assert.equal(calls.at(-1).kind, 'Sampler');
});

test('if the bass cannot be loaded it falls back to the synthesizer', async () => {
  const calls = [];
  const tone = makeFakeTone(calls, { badUrls: ['data:audio'] });
  const band = await buildInstruments(tone);
  assert.equal(band.sources.BASS.kind, 'synth');
  assert.equal(band.sources.PIANO.kind, 'samples');
  band.instruments.BASS.play(note(41), 2);
  assert.equal(calls.at(-1).kind, 'MonoSynth');
});

test('when nothing can be loaded the whole band plays on the synthesizer, and still plays', async () => {
  const calls = [];
  const tone = makeFakeTone(calls, { badUrls: ['jsdelivr', 'data:audio'] }); // the bass is loaded from data: URLs
  const band = await buildInstruments(tone);
  assert.ok(Object.values(band.sources).every((source) => source.kind === 'synth'));
  assert.ok(band.sources.PIANO.reason !== 'chosen' && band.sources.BASS.reason !== 'chosen', 'the recordings say why they are missing');
  assert.equal(samplers(tone).length, 0);
  for (const [name, midi] of [['SAX', 69], ['PIANO', 64], ['BASS', 41]]) {
    band.instruments[name].play(note(midi), 2);
    assert.notEqual(calls.at(-1).kind, 'Sampler', name);
  }
});

test('choosing the synthesizer downloads nothing', async () => {
  const tone = makeFakeTone([]);
  const band = await buildInstruments(tone, { sound: 'synth' });
  assert.equal(tone.loads.length, 0);
  assert.equal(samplers(tone).length, 0);
  assert.ok(Object.values(band.sources).every((source) => source.kind === 'synth' && source.reason === 'chosen'));
});

test('a sound that does not exist is refused, including the recordings that were taken out', async () => {
  await assert.rejects(() => buildInstruments(makeFakeTone(), { sound: 'bagpipes' }), /unknown sound 'bagpipes'/);
  await assert.rejects(() => buildInstruments(makeFakeTone(), { sound: 'samples' }), /unknown sound 'samples'/);
});

test('everything the band can play goes through the mixer to the speakers, and nothing bypasses it', async () => {
  for (const sound of ['mixed', 'synth']) {
    forgetLoadedSets();
    const tone = makeFakeTone([]);
    await buildInstruments(tone, { sound });
    const voices = tone.created.filter((node) => /Synth$|Sampler$/.test(node.constructor.name));
    assert.ok(voices.length >= 8, `${sound}: found ${voices.length} voices`);
    for (const voice of voices) assert.equal(voice.reachesSpeakers(), true, `${sound}: ${voice.constructor.name} never reaches the speakers`);
    assert.equal(tone.created.filter((node) => node.toSpeakers).length, 1, `${sound}: only the master clipper feeds the speakers`);
  }
});

test('taking the band down disposes everything, which silences what was scheduled', async () => {
  const tone = makeFakeTone([]);
  const band = await buildInstruments(tone);
  band.dispose();
  assert.ok(tone.created.every((node) => node.disposed), tone.created.filter((node) => !node.disposed).map((node) => node.constructor.name).join(', '));
});

test('progress is reported as each recording finishes', async () => {
  const seen = [];
  await buildInstruments(makeFakeTone([]), { onProgress: (progress) => seen.push(progress) });
  assert.deepEqual(seen.map((p) => [p.done, p.total]), [[1, 2], [2, 2]]);
  assert.ok(seen.every((p) => p.label.length > 5));
});

test('a band built again reuses the recordings it already loaded', async () => {
  const tone = makeFakeTone([]);
  const first = await buildInstruments(tone);
  const downloads = tone.loads.length;
  assert.equal(downloads, 30 + BASS_NOTES.length);
  first.dispose();
  const second = await buildInstruments(tone);
  assert.equal(tone.loads.length, downloads, 'nothing was downloaded again');
  assert.equal(second.sources.PIANO.kind, 'samples');
});
