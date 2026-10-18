// Run with: node --test server/src/test/js/*.test.mjs
//
// Runs the real app.js against a fake page, a fake WebSocket and a fake Tone.js, and plays through a performance the way
// the server would drive it: hello, clock sync, Start, the chart, the parts of the tune, notes, the conductor, the end.
// A script error on the Start path (the page doing nothing when you press the button) fails here.
import assert from 'node:assert/strict';
import test from 'node:test';
import { forgetLoadedSets } from '../../main/resources/public/sampleSets.js';
import { findAll, installDocument, withClass } from './fake-dom.mjs';
import { makeFakeTone } from './fake-tone.mjs';

const page = installDocument();
globalThis.location = { protocol: 'http:', host: 'localhost:7070' };

class FakeSocket {
  constructor(url) { this.url = url; this.sent = []; FakeSocket.instance = this; }
  send(message) { this.sent.push(JSON.parse(message)); }
  receive(message) { this.onmessage({ data: JSON.stringify(message) }); }
}
globalThis.WebSocket = FakeSocket;

const calls = [];
const badUrls = [];
const tone = makeFakeTone(calls, { badUrls });
globalThis.Tone = tone;
// The bass recording is a JavaScript file fetched as text.
globalThis.fetch = async () => ({ ok: true, status: 200, text: async () =>
  `MIDI.Soundfont.acoustic_bass = {${['C2', 'D2', 'E2', 'F2', 'G2', 'A2', 'B2', 'C3'].map((n) => `"${n}": "data:audio/mp3;base64,AAAA"`).join(',')},}` });

await import('../../main/resources/public/app.js');
await new Promise((resolve) => setTimeout(resolve, 30)); // the page starts loading its recordings as it opens
const socket = FakeSocket.instance;
const sentOfType = (type) => socket.sent.filter((m) => m.type === type);
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const BLUES = { id: 'blues', title: 'Blues in F', form: 'ABC', tempoBpm: 120, beatsPerBar: 4, ending: 'F7', sections: [
  { name: 'A', bars: ['F7', 'Bb7', 'F7', 'F7'] }, { name: 'B', bars: ['Bb7', 'Bb7', 'F7', 'D7'] }, { name: 'C', bars: ['Gm7', 'C7', 'F7', 'C7'] }] };
const SONG1 = { id: 'song1', title: 'Song 1', form: 'AAB', tempoBpm: 120, beatsPerBar: 4, ending: 'CM7', sections: [
  { name: 'A', bars: ['Em7', 'A7', 'Dm7', 'G7'] }, { name: 'A', bars: ['Em7', 'A7', 'Dm7', 'G7'] }, { name: 'B', bars: ['CM7', 'CM7'] }] };

const cards = () => page.stage.children;
const card = (instrument) => cards().find((c) => c.dataset.instrument === instrument);
const part = (instrument, className) => findAll(card(instrument), withClass(className))[0];
const chartRows = () => findAll(page.chart, withClass('section-row'));
let headEnd; // when the head the test announces ends, so the solo that follows can start exactly then

test('the page loads the piano and bass recordings as it opens, and says what it is playing with', () => {
  assert.equal(page.soundStatus.textContent, 'Playing with recordings of the piano and bass; the sax and drums are synthesized.');
  assert.equal(tone.loads.length, 38, 'only the piano (30 files) and the bass (8 in its register) are downloaded');
  assert.equal(page.sound.value, 'mixed');
});

test('the page connects to the server over a websocket and syncs the clock', () => {
  assert.equal(socket.url, 'ws://localhost:7070/ws');
  socket.onopen();
  assert.ok(sentOfType('time.sync').length >= 1);
});

test('the band is on stage from the start, waiting', () => {
  assert.deepEqual(cards().map((c) => c.dataset.instrument), ['SAX', 'PIANO', 'BASS', 'DRUMS']);
  assert.ok(['SAX', 'PIANO', 'BASS', 'DRUMS'].every((i) => part(i, 'role').textContent === 'Waiting'));
  assert.ok(['SAX', 'PIANO', 'BASS', 'DRUMS'].every((i) => part(i, 'mute').disabled), 'no mixing without a performance');
});

test('Start stays disabled until the clock has synced with the server', () => {
  assert.equal(page.start.disabled, true);
  socket.receive({ type: 'time.sync', t0: Date.now() - 4, serverMs: Date.now() });
  assert.equal(page.start.disabled, false);
});

test('the server offers its tunes, and the page previews the chart of the one chosen', async () => {
  socket.receive({ type: 'hello', serverMs: Date.now(), songs: [BLUES, SONG1] });
  assert.equal(page.songTitle.textContent, 'Blues in F');
  assert.equal(chartRows().length, 3);

  page.song.value = 'song1';
  await page.song.fire('change');
  assert.equal(page.songTitle.textContent, 'Song 1');
  assert.deepEqual(chartRows().map((row) => findAll(row, withClass('badge'))[0].textContent), ['A', 'A', 'B']);
  assert.deepEqual(chartRows().map((row) => findAll(row, withClass('bar')).length), [4, 4, 2]);
});

test('the song menu lists every tune the server offers with its style, and choosing one offers its tempo', async () => {
  const BALLAD = { id: 'ballad', title: 'Ballad in Eb', style: 'Slow ballad', suggestedTempo: 66, form: 'AB', tempoBpm: 120, beatsPerBar: 4, ending: 'EbM7',
    sections: [{ name: 'A', bars: ['EbM7', 'Cm7', 'Fm7', 'Bb7'] }, { name: 'B', bars: ['Gm7', 'C7', 'Fm7', 'Bb7'] }] };
  socket.receive({ type: 'hello', serverMs: Date.now(), songs: [{ ...BLUES, style: 'Medium swing blues', suggestedTempo: 120 }, BALLAD] });
  assert.deepEqual(page.song.children.map((o) => [o.value, o.textContent]),
    [['blues', 'Blues in F · Medium swing blues · 12 bars'], ['ballad', 'Ballad in Eb · Slow ballad · 8 bars']]);

  page.song.value = 'ballad';
  await page.song.fire('change');
  assert.equal(page.songTitle.textContent, 'Ballad in Eb');
  assert.equal(page.tempo.value, 66);
  assert.equal(page.tempoOut.textContent, '66');

  // Put the page back as the other tests expect it.
  page.tempo.value = '120';
  socket.receive({ type: 'hello', serverMs: Date.now(), songs: [BLUES, SONG1] });
  page.song.value = 'song1';
  await page.song.fire('change');
});

test('the server offers ways of writing solos, and a learned one is credited to its data', async () => {
  assert.equal(page.credit.hidden, true, 'nothing to credit for the hand-written rules');
  socket.receive({ type: 'hello', serverMs: Date.now(), songs: [BLUES, SONG1], soloComposers: [
    { id: 'rules', label: 'Rules (hand-written)', credit: '' },
    { id: 'markov', label: 'Markov model (learned from jazz solos)', credit: 'Solos learned from 200 transcribed solos of the Weimar Jazz Database, used under the ODbL.' },
  ] });
  assert.deepEqual(page.soloComposer.children.map((o) => [o.value, o.textContent]),
    [['rules', 'Rules (hand-written)'], ['markov', 'Markov model (learned from jazz solos)']]);
  assert.equal(page.soloComposer.value, 'rules');
  assert.equal(page.credit.hidden, true);

  page.soloComposer.value = 'markov';
  await page.soloComposer.fire('change');
  assert.equal(page.credit.hidden, false);
  assert.match(page.credit.textContent, /Weimar Jazz Database.*ODbL/);

  page.soloComposer.value = 'rules';
  await page.soloComposer.fire('change');
  assert.equal(page.credit.hidden, true);
  assert.equal(page.credit.textContent, '');
});

test('a server that offers only the rules leaves the menu as it was', () => {
  const before = page.soloComposer.children.length;
  socket.receive({ type: 'hello', serverMs: Date.now(), songs: [BLUES, SONG1] }); // an older server: no soloComposers at all
  assert.equal(page.soloComposer.children.length, before);
});

test('choosing a smaller band dims the musicians who will not play', async () => {
  page.ensemble.value = 'duo';
  await page.ensemble.fire('change');
  assert.deepEqual(cards().map((c) => c.classList.contains('absent')), [true, false, false, true]);
  page.ensemble.value = 'quartet';
  await page.ensemble.fire('change');
  assert.ok(cards().every((c) => !c.classList.contains('absent')));
});

test('pressing Start sends the performance to the server and gets the mixing controls going', async () => {
  page.tempo.value = '140';
  page.soloChoruses.value = '2';
  await page.start.fire('click');

  assert.deepEqual(sentOfType('start'), [{ type: 'start', song: 'song1', soloComposer: 'rules', feel: 'human', tempo: 140, soloChoruses: 2, ensemble: 'quartet' }]);
  assert.equal(page.stop.disabled, false);
  assert.equal(page.start.disabled, true);
  assert.ok(['SAX', 'PIANO', 'BASS', 'DRUMS'].every((i) => !part(i, 'mute').disabled));
});

test('while a performance runs the sound cannot be changed', () => {
  assert.equal(page.sound.disabled, true);
});

test('the server\'s conversation appears in the log', () => {
  socket.receive({ type: 'conversation', from: 's1-sax-musician', to: ['s1-piano-musician'], performative: 'CFP',
    protocol: 'fipa-contract-net', conversationId: 'intro', summary: 'who wants to play the intro?', atMs: 1 });
  socket.receive({ type: 'conversation', from: 's1-sax-assistant', to: ['s1-sax-musician'], performative: 'INFORM',
    protocol: null, conversationId: 'tempo', summary: 'the tempo is 140 bpm', atMs: 2 });
  assert.equal(page.conversation.children.length, 2);
  assert.equal(page.conversation.children[1].dataset.part, 'assistant');
});

test('the robots react to the conversation: the speaker talks, the others listen, and shop talk stays quiet', () => {
  socket.receive({ type: 'conversation', from: 's1-sax-musician', to: ['s1-piano-musician', 's1-bass-musician'], performative: 'REQUEST',
    protocol: 'fipa-request', conversationId: 'accompany', summary: 'back me up in HEAD', atMs: 3 });
  assert.ok(card('SAX').classList.contains('talking'));
  assert.equal(part('SAX', 'bubble').textContent, 'Back me up!');
  assert.ok(card('PIANO').classList.contains('listening'));
  assert.ok(card('BASS').classList.contains('listening'));
  assert.ok(!card('DRUMS').classList.contains('listening'));

  socket.receive({ type: 'conversation', from: 's1-drums-composer', to: ['s1-drums-musician'], performative: 'INFORM',
    protocol: null, conversationId: 'notes', summary: 'here are the notes', atMs: 4 });
  assert.ok(!card('DRUMS').classList.contains('talking'));
});

test('as the band plays, the page shows where it is in the chart and what each musician is doing', async () => {
  const now = Date.now();
  socket.receive({ type: 'song', ...SONG1 });
  // The head started 3 s ago at 120 bpm (a bar is 2 s), so we are one bar and a half in: section A, bar 2 (A7).
  socket.receive({ type: 'segment', id: 'INTRO', kind: 'INTRO', leader: 'PIANO', startMs: now - 11_000, endMs: now - 3_000, tempoBpm: 120, label: 'Intro' });
  headEnd = now + 2_000;
  socket.receive({ type: 'segment', id: 'HEAD', kind: 'HEAD', leader: 'SAX', startMs: now - 3_000, endMs: headEnd, tempoBpm: 120, label: 'The head' });
  await pause(150);

  assert.equal(page.status.textContent, 'The head');
  assert.equal(page.chartPosition.textContent, 'Head · First chorus (AAB)');
  assert.equal(page.where.textContent, 'Section A · bar 2 · A7');
  assert.match(page.caption.textContent, /The leader \(sax\) plays the head/);

  assert.deepEqual(chartRows().map((row) => row.classList.contains('active')), [true, false, false]);
  const current = findAll(page.chart, withClass('current'));
  assert.equal(current.length, 1);
  assert.equal(findAll(current[0], withClass('chord'))[0].textContent, 'A7');

  assert.equal(part('SAX', 'role').textContent, 'Playing the head');
  assert.equal(part('PIANO', 'role').textContent, 'Accompanying');
});

test('notes light up the musician playing them, with their names, and every instrument makes a sound', async () => {
  calls.length = 0;
  const now = Date.now();
  const sounding = (instrument, midi, durationMs = 2_000) =>
    socket.receive({ type: 'note', instrument, midi, startMs: now, durationMs, velocity: 100 });
  sounding('SAX', 69);
  sounding('PIANO', 64); sounding('PIANO', 60); sounding('PIANO', 67);
  sounding('BASS', 41);
  sounding('DRUMS', 51, 40); // a hit that is over in 40 ms
  sounding('DRUMS', 36, 40);
  await pause(120);

  assert.equal(calls.length, 7, 'each of the seven notes was scheduled once');
  assert.equal(part('SAX', 'readout').textContent, 'A4');
  assert.equal(part('PIANO', 'readout').textContent, 'C4 · E4 · G4');
  assert.equal(part('BASS', 'readout').textContent, 'F2');
  assert.ok(['SAX', 'PIANO', 'BASS'].every((i) => card(i).classList.contains('playing')));

  // A drum hit is far shorter than the display's refresh, but stays on screen long enough to be seen.
  assert.equal(part('DRUMS', 'readout').textContent, 'Ride · Kick');
  assert.ok(card('DRUMS').classList.contains('playing'));
});

test('late notes are neither played nor shown', async () => {
  calls.length = 0;
  socket.receive({ type: 'note', instrument: 'BASS', midi: 43, startMs: Date.now() - 5_000, durationMs: 9_000, velocity: 90 });
  await pause(120);
  assert.equal(calls.length, 0);
  assert.notEqual(part('BASS', 'readout').textContent, 'G2');
});

test('muting and the volume are sent to the conductor from the musician\'s own card', async () => {
  await part('BASS', 'mute').fire('click');
  assert.deepEqual(sentOfType('conduct').at(-1), { type: 'conduct', command: 'mute', instrument: 'BASS' });
  await pause(120);
  assert.equal(part('BASS', 'role').textContent, 'Sitting out');
  await part('BASS', 'mute').fire('click');
  assert.deepEqual(sentOfType('conduct').at(-1), { type: 'conduct', command: 'unmute', instrument: 'BASS' });

  const slider = part('SAX', 'volume');
  slider.value = '60';
  await slider.fire('change');
  assert.deepEqual(sentOfType('conduct').at(-1), { type: 'conduct', command: 'volume', instrument: 'SAX', value: 0.6 });
});

test('moving the tempo slider during the performance is the conductor changing it', async () => {
  page.tempo.value = '100';
  await page.tempo.fire('change');
  assert.deepEqual(sentOfType('conduct').at(-1), { type: 'conduct', command: 'tempo', value: 100 });
});

test('the page follows the band from one part to the next, and says who handed over the lead', async () => {
  // Parts run back to back: the solo starts the moment the head ends.
  socket.receive({ type: 'segment', id: 'SOLO-PIANO', kind: 'SOLO', leader: 'PIANO', startMs: headEnd, endMs: headEnd + 20_000, tempoBpm: 120, label: 'piano solo' });
  await pause(Math.max(0, headEnd - Date.now()) + 200);
  assert.equal(page.chartPosition.textContent, 'Piano solo · First chorus (AAB)');
  assert.match(page.caption.textContent, /^The sax hands the lead to the piano/);
  assert.equal(part('PIANO', 'role').textContent, 'Soloing');
  assert.equal(part('SAX', 'role').textContent, 'Accompanying');
});

test('a conduct command the server refuses does not end the performance', () => {
  socket.receive({ type: 'error', source: 'conduct', message: 'volume must be between 0 and 1.5' });
  assert.equal(page.start.disabled, true);
  assert.equal(page.stop.disabled, false);
  assert.match(page.status.textContent, /volume must be/);
});

test('when the performance finishes the page is ready to start another', async () => {
  socket.receive({ type: 'status', kind: 'FINISHED', message: 'the performance is over', atMs: Date.now() });
  assert.equal(page.start.disabled, false);
  assert.equal(page.stop.disabled, true);
  assert.equal(page.status.textContent, 'Finished.');
  assert.ok(['SAX', 'PIANO', 'BASS', 'DRUMS'].every((i) => part(i, 'mute').disabled));
  assert.equal(findAll(page.chart, withClass('active')).length, 0, 'the chart is cleared');
  assert.equal(page.caption.textContent, '');

  await pause(120); // the display has stopped refreshing: nothing changes any more
  assert.ok(['SAX', 'PIANO', 'BASS', 'DRUMS'].every((i) => part(i, 'role').textContent === 'Waiting'));
});

test('a start the server refuses puts the page back so it can be tried again', async () => {
  await page.start.fire('click');
  assert.equal(page.stop.disabled, false);
  socket.receive({ type: 'error', source: 'start', message: 'tempo must be 60-200' });
  assert.equal(page.start.disabled, false);
  assert.equal(page.stop.disabled, true);
  assert.match(page.status.textContent, /tempo must be/);
});

test('Stop ends the performance, silences the band, and has a fresh one ready for the next start', async () => {
  await page.start.fire('click');
  const downloads = tone.loads.length;
  await page.stop.fire('click');
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.ok(sentOfType('stop').length >= 1);
  assert.equal(page.start.disabled, false);
  assert.equal(page.status.textContent, 'Stopped.');
  assert.equal(page.sound.disabled, false);
  assert.equal(tone.loads.length, downloads, 'the new band was built from what was already loaded');
  assert.match(page.soundStatus.textContent, /^Playing with recordings of the piano and bass/);

  // A note still on its way from the server when Stop was pressed must not sound on the new band.
  calls.length = 0;
  socket.receive({ type: 'note', instrument: 'SAX', midi: 69, startMs: Date.now() + 100, durationMs: 400, velocity: 90 });
  assert.equal(calls.length, 0);
});

test('choosing the synthesizer rebuilds the band without downloading anything, and notes then play on synthesizer voices', async () => {
  const downloads = tone.loads.length;
  page.sound.value = 'synth';
  await page.sound.fire('change');
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.equal(page.soundStatus.textContent, 'Playing with the synthesizer.');
  assert.equal(tone.loads.length, downloads);

  await page.start.fire('click');
  calls.length = 0;
  socket.receive({ type: 'note', instrument: 'SAX', midi: 69, startMs: Date.now() + 300, durationMs: 400, velocity: 90 });
  socket.receive({ type: 'note', instrument: 'PIANO', midi: 64, startMs: Date.now() + 300, durationMs: 400, velocity: 90 });
  assert.deepEqual(calls.map((call) => call.kind).sort(), ['MonoSynth', 'PolySynth']);
  await page.stop.fire('click');
  await new Promise((resolve) => setTimeout(resolve, 30));
});

test('going back to the recordings needs no download, as they are already loaded', async () => {
  const downloads = tone.loads.length;
  page.sound.value = 'mixed';
  await page.sound.fire('change');
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.match(page.soundStatus.textContent, /^Playing with recordings of the piano and bass/);
  assert.equal(tone.loads.length, downloads);
});

test('if the recordings cannot be loaded the page falls back to the synthesizer and says why, and still plays', async () => {
  forgetLoadedSets();
  badUrls.push('jsdelivr', 'data:audio');
  page.sound.value = 'mixed';
  await page.sound.fire('change');
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.match(page.soundStatus.textContent, /^The recordings could not be loaded, so the band is using the synthesizer \(only 0 of/);

  await page.start.fire('click');
  assert.equal(page.start.disabled, true, 'the performance starts anyway');
  calls.length = 0;
  socket.receive({ type: 'note', instrument: 'BASS', midi: 41, startMs: Date.now() + 300, durationMs: 400, velocity: 90 });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].kind, 'MonoSynth');
  await page.stop.fire('click');
  badUrls.length = 0;
});
