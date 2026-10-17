// Run with: node --test server/src/test/js/*.test.mjs
//
// Runs the real app.js against a fake page, a fake WebSocket and a fake Tone.js, and clicks through a performance:
// connect, sync the clock, press Start, receive the server's messages, conduct. A script error on the Start path
// (the page doing nothing when you press the button) fails here.
import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { makeFakeTone } from './fake-tone.mjs';

// ---- a fake page ----
class FakeElement {
  constructor(id) {
    this.id = id;
    this.listeners = {};
    this.children = [];
    this.dataset = {};
    this.hidden = false;
    this.disabled = false;
    this.textContent = '';
    this.value = '';
    this.checked = false;
    this.found = {};
  }
  addEventListener(type, listener) { (this.listeners[type] ??= []).push(listener); }
  async fire(type, event = {}) { for (const listener of this.listeners[type] ?? []) await listener({ target: this, ...event }); }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  querySelector(selector) { return (this.found[selector] ??= new FakeElement(selector)); }
}

// Start with the attributes the real page declares (the Start button is disabled until the clock has synced, the
// conductor is hidden until a performance runs), so the fake begins in the state a browser would.
const elements = {};
const html = readFileSync(new URL('../../main/resources/public/index.html', import.meta.url), 'utf8');
for (const [, attributes] of html.matchAll(/<\w+([^>]*\bid="[^"]+"[^>]*)>/g)) {
  const id = /\bid="([^"]+)"/.exec(attributes)[1];
  elements[id] = new FakeElement(id);
  elements[id].disabled = /\sdisabled\b/.test(attributes);
  elements[id].hidden = /\shidden\b/.test(attributes);
}
globalThis.document = {
  getElementById: (id) => (elements[id] ??= new FakeElement(id)),
  createElement: () => new FakeElement('created'),
};
globalThis.location = { protocol: 'http:', host: 'localhost:7070' };

class FakeSocket {
  constructor(url) { this.url = url; this.sent = []; FakeSocket.instance = this; }
  send(message) { this.sent.push(JSON.parse(message)); }
  receive(message) { this.onmessage({ data: JSON.stringify(message) }); }
}
globalThis.WebSocket = FakeSocket;

const calls = [];
globalThis.Tone = makeFakeTone(calls);

await import('../../main/resources/public/app.js');
const socket = FakeSocket.instance;
const page = (id) => elements[id];
const sentOfType = (type) => socket.sent.filter((m) => m.type === type);

test('the page connects to the server over a websocket', () => {
  assert.equal(socket.url, 'ws://localhost:7070/ws');
  socket.onopen();
  assert.ok(sentOfType('time.sync').length >= 1);
});

test('Start stays disabled until the clock has synced with the server', () => {
  assert.equal(page('start').disabled, true);
  socket.receive({ type: 'time.sync', t0: Date.now() - 4, serverMs: Date.now() });
  assert.equal(page('start').disabled, false);
});

test('pressing Start sends the performance to the server and shows the conductor', async () => {
  page('tempo').value = '140';
  page('soloChoruses').value = '2';
  page('ensemble').value = 'quartet';
  await page('start').fire('click');

  assert.deepEqual(sentOfType('start'), [{ type: 'start', tempo: 140, soloChoruses: 2, ensemble: 'quartet' }]);
  assert.equal(page('stop').disabled, false);
  assert.equal(page('start').disabled, true);
  assert.equal(page('conductor').hidden, false);
  assert.equal(page('mixer').children.length, 4, 'a channel per musician in the quartet');
});

test('the server\'s conversation appears in the log', () => {
  socket.receive({ type: 'conversation', from: 's1-sax-musician', to: ['s1-piano-musician'], performative: 'CFP',
    protocol: 'fipa-contract-net', conversationId: 'intro', summary: 'who wants to play the intro?', atMs: 1 });
  assert.equal(page('conversation').children.length, 1);
});

test('every instrument makes a sound when its notes arrive', () => {
  calls.length = 0;
  const soon = Date.now() + 500;
  for (const [instrument, midi] of [['SAX', 69], ['PIANO', 64], ['BASS', 41], ['DRUMS', 36], ['DRUMS', 51], ['DRUMS', 38]]) {
    socket.receive({ type: 'note', instrument, midi, startMs: soon, durationMs: 400, velocity: 90 });
  }
  assert.equal(calls.length, 6, 'each of the six notes should have been scheduled once');
  assert.ok(calls.every((call) => Number.isFinite(call.time) && call.time >= 100), 'scheduled on the audio clock');
});

test('late notes are dropped instead of playing out of time', () => {
  calls.length = 0;
  socket.receive({ type: 'note', instrument: 'BASS', midi: 41, startMs: Date.now() - 5_000, durationMs: 400, velocity: 90 });
  assert.equal(calls.length, 0);
});

test('the conductor sends tempo, volume and mute while performing', async () => {
  page('tempo').value = '100';
  await page('tempo').fire('change');
  assert.deepEqual(sentOfType('conduct').at(-1), { type: 'conduct', command: 'tempo', value: 100 });

  const bass = page('mixer').children[2]; // sax, piano, bass, drums
  const slider = bass.querySelector('input[type=range]');
  slider.value = '60';
  await slider.fire('change');
  assert.deepEqual(sentOfType('conduct').at(-1), { type: 'conduct', command: 'volume', instrument: 'BASS', value: 0.6 });

  await bass.querySelector('input[type=checkbox]').fire('change', { target: { checked: true } });
  assert.deepEqual(sentOfType('conduct').at(-1), { type: 'conduct', command: 'mute', instrument: 'BASS' });
  await bass.querySelector('input[type=checkbox]').fire('change', { target: { checked: false } });
  assert.deepEqual(sentOfType('conduct').at(-1), { type: 'conduct', command: 'unmute', instrument: 'BASS' });
});

test('when the performance finishes the page is ready to start another', () => {
  socket.receive({ type: 'status', kind: 'FINISHED', message: 'the performance is over', atMs: Date.now() });
  assert.equal(page('start').disabled, false);
  assert.equal(page('stop').disabled, true);
  assert.equal(page('conductor').hidden, true);
});

test('a start the server refuses puts the page back so it can be tried again', async () => {
  await page('start').fire('click');
  assert.equal(page('conductor').hidden, false);
  socket.receive({ type: 'error', source: 'start', message: 'tempo must be 60-200' });
  assert.equal(page('start').disabled, false);
  assert.equal(page('conductor').hidden, true);
  assert.match(page('status').textContent, /tempo must be/);
});

test('an error from a conduct command does not end the performance', async () => {
  await page('start').fire('click');
  socket.receive({ type: 'error', source: 'conduct', message: 'volume must be between 0 and 1.5' });
  assert.equal(page('conductor').hidden, false);
  assert.equal(page('start').disabled, true);
  await page('stop').fire('click');
  assert.equal(page('conductor').hidden, true);
  // Stop silences whatever was queued by throwing the instruments away; they are rebuilt on the next Start.
  assert.ok(sentOfType('stop').length >= 1);
});
