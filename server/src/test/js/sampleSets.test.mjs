import assert from 'node:assert/strict';
import test from 'node:test';
import { SAMPLE_SETS, fileToNote, forgetLoadedSets, loadSet, midiToNote, noteToMidi, parseSoundfontJs, urlsOf }
  from '../../main/resources/public/sampleSets.js';
import { makeFakeTone } from './fake-tone.mjs';

const SOUNDFONT_JS = `if (typeof(MIDI) === 'undefined') var MIDI = {};
if (typeof(MIDI.Soundfont) === 'undefined') MIDI.Soundfont = {};
MIDI.Soundfont.acoustic_bass = {
"A0": "data:audio/mp3;base64,AAAA",
"C2": "data:audio/mp3;base64,BBBB",
"E1": "data:audio/mp3;base64,CCCC",
"C6": "data:audio/mp3;base64,DDDD",
"C3": "data:audio/mp3;base64,EEEE",
}
`;

test('note names and MIDI numbers agree both ways for every pitch', () => {
  for (let midi = 12; midi <= 120; midi += 1) assert.equal(noteToMidi(midiToNote(midi)), midi);
  assert.equal(midiToNote(60), 'C4');
  assert.equal(midiToNote(61), 'C#4');
  assert.equal(noteToMidi('Bb1'), 34);
  assert.equal(noteToMidi('Db4'), noteToMidi('C#4'));
  assert.throws(() => noteToMidi('H4'), /not a note name/);
});

test('the Tone.js repositories name sharps with an "s"', () => {
  assert.equal(fileToNote('Ds1'), 'D#1');
  assert.equal(fileToNote('Fs4'), 'F#4');
  assert.equal(fileToNote('C4'), 'C4');
  assert.equal(fileToNote('As3'), 'A#3');
});

test('only the piano and the bass are recordings: the sax and the drums were tried and judged worse than the synthesizer', () => {
  assert.deepEqual(Object.keys(SAMPLE_SETS), ['PIANO', 'BASS']);
});

test('every recording is credited and under an open licence that asks for attribution', () => {
  for (const [name, set] of Object.entries(SAMPLE_SETS)) {
    assert.equal(set.license, 'CC BY 3.0', name);
    assert.ok(set.credit.length > 10, `${name} should say whose recording it is`);
    assert.ok(set.label.length > 5, name);
  }
});

test('every source is pinned to a commit, so an upstream change cannot alter the sound', () => {
  for (const [name, set] of Object.entries(SAMPLE_SETS)) {
    const where = set.kind === 'soundfont-js' ? set.url : set.baseUrl;
    assert.match(where, /^https:\/\/cdn\.jsdelivr\.net\/gh\/[^@]+@[0-9a-f]{40}\//, `${name}: ${where}`);
    assert.ok(!where.includes('@master') && !where.includes('@main'), name);
  }
});

test('the piano is thirty Salamander recordings, a minor third apart, from the lowest A to the highest C', () => {
  const urls = urlsOf(SAMPLE_SETS.PIANO);
  const notes = Object.keys(urls);
  assert.equal(notes.length, 30);
  assert.ok(notes.includes('A0') && notes.includes('C8') && notes.includes('D#1') && notes.includes('F#7'));
  assert.equal(new Set(notes).size, 30);
  assert.equal(urls['D#1'].split('/').pop(), 'Ds1.mp3', 'the sharp is spelled the way the file is');
  const midis = notes.map(noteToMidi).sort((a, b) => a - b);
  for (let i = 1; i < midis.length; i += 1) assert.ok(midis[i] - midis[i - 1] <= 3, 'never further than a minor third apart');
});

test('a soundfont .js file is read despite not being JSON (a trailing comma, a script around it)', () => {
  const notes = parseSoundfontJs(SOUNDFONT_JS, 'acoustic_bass');
  assert.deepEqual(Object.keys(notes), ['A0', 'C2', 'E1', 'C6', 'C3']);
  assert.equal(notes.C2, 'data:audio/mp3;base64,BBBB');
});

test('a soundfont file that is not the one asked for, or not audio, is rejected', () => {
  assert.throws(() => parseSoundfontJs(SOUNDFONT_JS, 'alto_sax'), /no soundfont named alto_sax/);
  assert.throws(() => parseSoundfontJs('MIDI.Soundfont.x = {"C4": "http://evil.example/a.js"}', 'x'), /not made of audio data/);
  assert.throws(() => parseSoundfontJs('nothing here', 'x'), /no soundfont/);
});

test('a set of files is loaded one by one into decoded buffers, naming each by its note', async () => {
  forgetLoadedSets();
  const tone = makeFakeTone();
  let files = 0;
  const buffers = await loadSet(tone, SAMPLE_SETS.PIANO, { onFile: () => { files += 1; } });
  assert.equal(buffers.size, 30);
  assert.equal(files, 30);
  assert.ok(buffers.has('D#1'));
  assert.match(buffers.get('D#1').url, /Ds1\.mp3$/);
  assert.equal(tone.loads.length, 30);
});

test('a missing file costs one note, not the instrument', async () => {
  forgetLoadedSets();
  const tone = makeFakeTone([], { badUrls: ['/C4.mp3', '/A3.mp3'] });
  const buffers = await loadSet(tone, SAMPLE_SETS.PIANO);
  assert.equal(buffers.size, 28);
  assert.ok(!buffers.has('C4') && !buffers.has('A3'));
});

test('a set with too few files loaded is refused, so the band uses the synthesizer instead', async () => {
  forgetLoadedSets();
  const tone = makeFakeTone([], { badUrls: ['salamander'] });
  await assert.rejects(() => loadSet(tone, SAMPLE_SETS.PIANO), /only 0 of 30 files of "Piano.*could be loaded/);
  forgetLoadedSets();
  const mostlyBad = makeFakeTone([], { badUrls: ['C1', 'C2', 'C3', 'C4', 'C5', 'C6', 'C7', 'Ds', 'Fs'] });
  await assert.rejects(() => loadSet(mostlyBad, SAMPLE_SETS.PIANO), /could be loaded/);
});

test('what is loaded is remembered, so a rebuilt band downloads nothing', async () => {
  forgetLoadedSets();
  const tone = makeFakeTone();
  const first = await loadSet(tone, SAMPLE_SETS.PIANO);
  const downloads = tone.loads.length;
  assert.equal(downloads, 30);
  const second = await loadSet(tone, SAMPLE_SETS.PIANO);
  assert.equal(tone.loads.length, downloads);
  assert.equal(second, first);
});

test('a soundfont is fetched once, filtered to the register the band uses, and loaded from its data URLs', async () => {
  forgetLoadedSets();
  const tone = makeFakeTone();
  const fetched = [];
  const buffers = await loadSet(tone, SAMPLE_SETS.BASS, { fetchText: async (url) => { fetched.push(url); return SOUNDFONT_JS; } });
  assert.deepEqual(fetched, [SAMPLE_SETS.BASS.url]);
  // A0 (21), E1 (28) and C6 (84) are outside 33 to 72, the register around the bass the band plays (C2 to C4): they are not loaded.
  assert.deepEqual([...buffers.keys()].sort(), ['C2', 'C3']);
  assert.ok(tone.loads.every((url) => url.startsWith('data:audio/mp3;base64,')));
});

test('a set that takes too long is given up on', async () => {
  forgetLoadedSets();
  const slow = makeFakeTone([], { loadDelayMs: 200 });
  await assert.rejects(() => loadSet(slow, SAMPLE_SETS.PIANO, { timeoutMs: 20 }), /took longer than 0\.02 s/);
});

test('a soundfont file that cannot be fetched is an error, not a hang', async () => {
  forgetLoadedSets();
  await assert.rejects(() => loadSet(makeFakeTone(), SAMPLE_SETS.BASS, { fetchText: async () => { throw new Error('could not load (503)'); } }), /503/);
});
