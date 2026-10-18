// Browser client: keeps a clock offset to the server, plays each note on the Web Audio clock, and shows where the band
// is in the tune.

import { buildChart } from './chart.js';
import { buildInstruments } from './instruments.js';
import { caption, leaderLabel } from './narrator.js';
import { ORDER, readout } from './notes.js';
import { buildStage } from './stage.js';
import { locate, positionTitle, roleOf, whereText } from './timeline.js';

const SYNC_ROUNDS = 8;
const RENDER_MS = 50;
const KEEP_PLAYED_MS = 2_000; // how long to remember a note after it has finished, for the display
const MIN_VISIBLE_MS = 150; // a drum hit lasts a few dozen milliseconds: keep it on screen long enough to be seen
const ENSEMBLES = { quartet: ['SAX', 'PIANO', 'BASS', 'DRUMS'], duo: ['PIANO', 'BASS'] };

const els = {
  song: document.getElementById('song'),
  soloComposer: document.getElementById('soloComposer'),
  credit: document.getElementById('credit'),
  tempo: document.getElementById('tempo'),
  tempoOut: document.getElementById('tempoOut'),
  soloChoruses: document.getElementById('soloChoruses'),
  ensemble: document.getElementById('ensemble'),
  start: document.getElementById('start'),
  stop: document.getElementById('stop'),
  status: document.getElementById('status'),
  stage: document.getElementById('stage'),
  songTitle: document.getElementById('songTitle'),
  chartPosition: document.getElementById('chartPosition'),
  chart: document.getElementById('chart'),
  caption: document.getElementById('caption'),
  where: document.getElementById('where'),
  conversation: document.getElementById('conversation'),
};

let socket;
let clockOffsetMs = 0; // serverTime - clientTime, from the lowest-latency sync round
let bestRtt = Infinity;
let instruments = null;
let performing = false;
let renderTimer = null;

let catalogue = {}; // the tunes the server offers, by id
let soloOptions = {}; // the ways of writing solos the server offers, by id
let song = null; // the tune being played (or previewed)
let chart = null;
let segments = []; // the parts of the tune announced so far
let played = freshPlayed(); // notes that have been scheduled, per instrument, for the display

const serverNow = () => Date.now() + clockOffsetMs;
const setStatus = (text) => { els.status.textContent = text; };
const conduct = (command, fields = {}) => socket.send(JSON.stringify({ type: 'conduct', command, ...fields }));

function freshPlayed() {
  return Object.fromEntries(ORDER.map((instrument) => [instrument, []]));
}

const stage = buildStage(els.stage, {
  onMute: (instrument, muted) => conduct(muted ? 'mute' : 'unmute', { instrument }),
  onVolume: (instrument, gain) => conduct('volume', { instrument, value: gain }),
});
stage.setBand(ENSEMBLES.quartet);
stage.setPerforming(false);

// ---- connection ----

function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${scheme}://${location.host}/ws`);
  socket.onopen = syncClock;
  socket.onmessage = (event) => handle(JSON.parse(event.data));
  socket.onclose = () => {
    setStatus('Disconnected. Reload to reconnect.');
    els.start.disabled = true;
    els.stop.disabled = true;
    stopRendering();
  };
}

function syncClock() {
  let round = 0;
  const ping = () => {
    socket.send(JSON.stringify({ type: 'time.sync', t0: Date.now() }));
    if (++round < SYNC_ROUNDS) setTimeout(ping, 80);
  };
  ping();
}

function handle(message) {
  switch (message.type) {
    case 'hello':
      catalogue = Object.fromEntries((message.songs ?? []).map((tune) => [tune.id, tune]));
      offerSoloComposers(message.soloComposers ?? []);
      preview();
      break;
    case 'time.sync': {
      const t1 = Date.now();
      const rtt = t1 - message.t0;
      if (rtt < bestRtt) {
        bestRtt = rtt;
        clockOffsetMs = message.serverMs + rtt / 2 - t1;
        if (!performing) els.start.disabled = false;
        if (!performing) setStatus(`Ready (round trip ${rtt} ms).`);
      }
      break;
    }
    case 'song': showSong(message); break;
    case 'segment': segments = [...segments, message].sort((a, b) => a.startMs - b.startMs); break;
    case 'note': if (schedule(message)) remember(message); break;
    case 'conversation': log(message); break;
    case 'status': onStatus(message); break;
    case 'error':
      setStatus(`Error: ${message.message}`);
      if (message.source === 'start') { // the performance never began
        endPerformance();
      }
      break;
  }
}

// ---- sound ----

function schedule(note) {
  const instrument = instruments?.[note.instrument];
  if (!instrument) return false;
  const delaySeconds = (note.startMs - serverNow()) / 1000;
  if (delaySeconds < -0.05) return false; // arrived too late to play in time
  instrument.play(note, Tone.now() + Math.max(0, delaySeconds));
  return true;
}

/** Keeps the note for the display, which shows what is sounding now. */
function remember(note) {
  played[note.instrument]?.push({
    start: note.startMs, end: note.startMs + Math.max(note.durationMs, MIN_VISIBLE_MS), midi: note.midi, velocity: note.velocity,
  });
}

// ---- display ----

function showSong(tune) {
  song = tune;
  els.songTitle.textContent = tune.title;
  chart = buildChart(els.chart, tune);
  chart.update(null);
}

/** Lists the ways of writing solos the server offers (the first is the default) and credits the data of a learned one. */
function offerSoloComposers(options) {
  if (!options.length) return;
  soloOptions = Object.fromEntries(options.map((option) => [option.id, option]));
  els.soloComposer.replaceChildren(...options.map((option) => {
    const entry = document.createElement('option');
    entry.value = option.id;
    entry.textContent = option.label;
    return entry;
  }));
  els.soloComposer.value = options[0].id;
  showCredit();
}

function showCredit() {
  const credit = soloOptions[els.soloComposer.value]?.credit ?? '';
  els.credit.textContent = credit;
  els.credit.hidden = !credit;
}

/** Before the band starts, show the chart of the chosen tune. */
function preview() {
  if (!performing && catalogue[els.song.value]) showSong(catalogue[els.song.value]);
}

function render() {
  const now = serverNow();
  const located = song ? locate(segments, song, now) : null;
  const previous = located ? segments[segments.indexOf(located.segment) - 1] : undefined;

  chart?.update(located);
  els.chartPosition.textContent = song ? positionTitle(located, song, leaderLabel(located)) : '';
  els.caption.textContent = located ? caption(located, previous) : (segments.length ? 'The band is counting in…' : 'The musicians are getting ready…');
  els.where.textContent = song ? whereText(located, song) : '';
  if (located) setStatus(located.label.charAt(0).toUpperCase() + located.label.slice(1));

  const states = {};
  for (const instrument of ORDER) {
    const notes = played[instrument];
    while (notes.length && notes[0].end < now - KEEP_PLAYED_MS) notes.shift();
    const sounding = notes.filter((note) => note.start <= now && now < note.end);
    states[instrument] = {
      role: roleOf(instrument, located),
      notes: readout(instrument, sounding.map((note) => note.midi)),
      level: sounding.length ? Math.max(...sounding.map((note) => note.velocity)) / 127 : 0,
    };
  }
  stage.update(states);
}

function startRendering() {
  stopRendering();
  renderTimer = setInterval(render, RENDER_MS);
}

function stopRendering() {
  if (renderTimer !== null) clearInterval(renderTimer);
  renderTimer = null;
}

/** Whatever ended the performance, put the page back so another can start. */
function endPerformance() {
  performing = false;
  stopRendering();
  els.start.disabled = false;
  els.stop.disabled = true;
  stage.setPerforming(false);
  stage.update({});
  chart?.update(null);
  els.chartPosition.textContent = '';
  els.caption.textContent = '';
  els.where.textContent = '';
}

function log(message) {
  const li = document.createElement('li');
  li.dataset.performative = message.performative;
  li.dataset.part = partOf(message.from);
  const left = document.createElement('div');
  const who = document.createElement('div');
  who.className = 'who';
  who.textContent = `${shortName(message.from)} → ${message.to.map(shortName).join(', ')}`;
  const performative = document.createElement('div');
  performative.className = 'performative';
  performative.textContent = message.performative;
  left.append(who, performative);
  const right = document.createElement('div');
  right.textContent = message.summary;
  li.append(left, right);
  els.conversation.append(li);
}

// Agent names look like "s1a2b3c4d5e6-piano-musician": show the instrument, and the part when it is not the musician.
const partOf = (name) => name.split('-')[2];
const shortName = (name) => {
  const [, instrument, part] = name.split('-');
  return !part || part === 'musician' ? instrument : `${instrument} ${part}`;
};

function onStatus(message) {
  switch (message.kind) {
    case 'ENSEMBLE_READY': setStatus('The ensemble is assembled…'); break;
    case 'FINISHED': endPerformance(); setStatus('Finished.'); break;
    case 'FAILED': endPerformance(); setStatus(`Failed: ${message.message}`); break;
  }
}

// ---- controls ----

els.tempo.addEventListener('input', () => { els.tempoOut.textContent = els.tempo.value; });
// Before the start this chooses the tempo; during a performance it is the Conductor changing it.
els.tempo.addEventListener('change', () => { if (performing) conduct('tempo', { value: Number(els.tempo.value) }); });
els.song.addEventListener('change', preview);
els.soloComposer.addEventListener('change', showCredit);
els.ensemble.addEventListener('change', () => { if (!performing) stage.setBand(ENSEMBLES[els.ensemble.value]); });

els.start.addEventListener('click', async () => {
  await Tone.start(); // browsers only allow audio after a user gesture
  instruments ??= buildInstruments(Tone);
  els.conversation.replaceChildren();
  segments = [];
  played = freshPlayed();
  performing = true;
  els.start.disabled = true;
  els.stop.disabled = false;
  stage.reset();
  stage.setBand(ENSEMBLES[els.ensemble.value]);
  stage.setPerforming(true);
  setStatus('Asking the musicians to gather…');
  startRendering();
  socket.send(JSON.stringify({
    type: 'start',
    song: els.song.value,
    soloComposer: els.soloComposer.value,
    tempo: Number(els.tempo.value),
    soloChoruses: Number(els.soloChoruses.value),
    ensemble: els.ensemble.value,
  }));
});

els.stop.addEventListener('click', () => {
  socket.send(JSON.stringify({ type: 'stop' }));
  // Notes are scheduled ahead on the audio clock; disposing the synths silences whatever is still queued.
  Object.values(instruments ?? {}).forEach((i) => i.dispose());
  instruments = null;
  endPerformance();
  setStatus('Stopped.');
});

connect();
