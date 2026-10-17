// Browser client: keeps a clock offset to the server, then schedules each note on the Web Audio clock.

import { buildDrumKit } from './drums.js';

const SYNC_ROUNDS = 8;

const els = {
  tempo: document.getElementById('tempo'),
  tempoOut: document.getElementById('tempoOut'),
  soloChoruses: document.getElementById('soloChoruses'),
  ensemble: document.getElementById('ensemble'),
  start: document.getElementById('start'),
  stop: document.getElementById('stop'),
  status: document.getElementById('status'),
  conversation: document.getElementById('conversation'),
  conductor: document.getElementById('conductor'),
  mixer: document.getElementById('mixer'),
};

let socket;
let clockOffsetMs = 0; // serverTime - clientTime, from the lowest-latency sync round
let bestRtt = Infinity;
let instruments = null;
let timers = []; // pending "now playing" label changes
let performing = false; // whether this browser has a performance running

const ENSEMBLES = { quartet: ['SAX', 'PIANO', 'BASS', 'DRUMS'], duo: ['PIANO', 'BASS'] };

const serverNow = () => Date.now() + clockOffsetMs;
const setStatus = (text) => { els.status.textContent = text; };

function connect() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${scheme}://${location.host}/ws`);
  socket.onopen = syncClock;
  socket.onmessage = (event) => handle(JSON.parse(event.data));
  socket.onclose = () => { setStatus('Disconnected. Reload to reconnect.'); els.start.disabled = true; els.stop.disabled = true; };
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
    case 'time.sync': {
      const t1 = Date.now();
      const rtt = t1 - message.t0;
      if (rtt < bestRtt) {
        bestRtt = rtt;
        clockOffsetMs = message.serverMs + rtt / 2 - t1;
        els.start.disabled = false;
        setStatus(`Ready (round trip ${rtt} ms).`);
      }
      break;
    }
    case 'note': schedule(message); break;
    case 'conversation': log(message); break;
    case 'status': onStatus(message); break;
    case 'error':
      setStatus(`Error: ${message.message}`);
      if (message.source === 'start') { // the performance never began
        els.start.disabled = false;
        els.stop.disabled = true;
        setPerforming(false);
      }
      break;
  }
}

// Every instrument exposes play(note, when) and dispose(), so scheduling does not care what makes the sound.
function buildInstruments() {
  const reverb = new Tone.Reverb({ decay: 1.8, wet: 0.15 }).toDestination();

  const bass = new Tone.MonoSynth({
    oscillator: { type: 'triangle' },
    envelope: { attack: 0.01, decay: 0.25, sustain: 0.5, release: 0.15 },
    filterEnvelope: { attack: 0.01, decay: 0.2, sustain: 0.4, baseFrequency: 120, octaves: 2.5 },
    volume: -6,
  }).toDestination();

  const piano = new Tone.PolySynth(Tone.FMSynth, {
    harmonicity: 3, modulationIndex: 1.5,
    envelope: { attack: 0.005, decay: 0.4, sustain: 0.2, release: 0.5 },
    modulationEnvelope: { attack: 0.01, decay: 0.2, sustain: 0.1, release: 0.3 },
    volume: -14,
  }).connect(reverb);

  // A breathy, slightly detuned saw with vibrato stands in for the sax until we have real samples.
  const vibrato = new Tone.Vibrato({ frequency: 5, depth: 0.12 }).connect(reverb);
  const sax = new Tone.MonoSynth({
    oscillator: { type: 'fatsawtooth', count: 2, spread: 14 },
    envelope: { attack: 0.04, decay: 0.1, sustain: 0.8, release: 0.12 },
    filter: { type: 'lowpass', Q: 1 },
    filterEnvelope: { attack: 0.05, decay: 0.25, sustain: 0.55, baseFrequency: 500, octaves: 2.6 },
    volume: -10,
  }).connect(vibrato);

  return {
    BASS: pitched(bass),
    PIANO: pitched(piano),
    SAX: pitched(sax),
    DRUMS: buildDrumKit(Tone),
  };
}

function schedule(note) {
  const instrument = instruments?.[note.instrument];
  if (!instrument) return;
  const delaySeconds = (note.startMs - serverNow()) / 1000;
  if (delaySeconds < -0.05) return; // arrived too late to play in time
  instrument.play(note, Tone.now() + Math.max(0, delaySeconds));
}

function log(message) {
  const li = document.createElement('li');
  li.dataset.performative = message.performative;
  li.dataset.part = partOf(message.from);
  const left = document.createElement('div');
  left.innerHTML = `<div class="who"></div><div class="performative"></div>`;
  left.querySelector('.who').textContent = `${shortName(message.from)} → ${message.to.map(shortName).join(', ')}`;
  left.querySelector('.performative').textContent = message.performative;
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

// ---- Conductor: tempo, volume and mute, sent to the Ensemble Assistants ----

const conduct = (command, fields = {}) => socket.send(JSON.stringify({ type: 'conduct', command, ...fields }));

function buildMixer(instruments) {
  els.mixer.replaceChildren(...instruments.map((instrument) => {
    const row = document.createElement('div');
    row.className = 'channel';
    row.innerHTML = `<strong></strong><input type="range" min="0" max="150" value="100" aria-label="volume"><output>100%</output>
      <label><input type="checkbox"> mute</label>`;
    row.querySelector('strong').textContent = instrument.toLowerCase();
    const slider = row.querySelector('input[type=range]');
    const output = row.querySelector('output');
    slider.addEventListener('input', () => { output.textContent = `${slider.value}%`; });
    slider.addEventListener('change', () => conduct('volume', { instrument, value: Number(slider.value) / 100 }));
    row.querySelector('input[type=checkbox]').addEventListener('change', (event) =>
      conduct(event.target.checked ? 'mute' : 'unmute', { instrument }));
    return row;
  }));
}

function setPerforming(value) {
  performing = value;
  els.conductor.hidden = !value;
}

// The server announces a part of the tune when it arranges it, a little before it is heard: show it on time.
function showAt(startMs, text) {
  timers.push(setTimeout(() => setStatus(text), Math.max(0, startMs - serverNow())));
}

function clearTimers() {
  timers.forEach(clearTimeout);
  timers = [];
}

function onStatus(message) {
  switch (message.kind) {
    case 'ENSEMBLE_READY': setStatus('The ensemble is assembled…'); break;
    case 'STARTED': showAt(message.atMs, 'Intro'); break;
    case 'SECTION': showAt(message.atMs, message.message); break;
    case 'FINISHED': setStatus('Finished.'); els.start.disabled = false; els.stop.disabled = true; setPerforming(false); break;
    case 'FAILED': clearTimers(); setStatus(`Failed: ${message.message}`); els.start.disabled = false; els.stop.disabled = true; setPerforming(false); break;
  }
}

els.tempo.addEventListener('input', () => { els.tempoOut.textContent = els.tempo.value; });
// Before the start this chooses the tempo; during a performance it is the Conductor changing it.
els.tempo.addEventListener('change', () => { if (performing) conduct('tempo', { value: Number(els.tempo.value) }); });

els.start.addEventListener('click', async () => {
  await Tone.start(); // browsers only allow audio after a user gesture
  instruments ??= buildInstruments();
  clearTimers();
  els.conversation.replaceChildren();
  els.start.disabled = true;
  els.stop.disabled = false;
  buildMixer(ENSEMBLES[els.ensemble.value]);
  setPerforming(true);
  setStatus('Asking the musicians to gather…');
  socket.send(JSON.stringify({ type: 'start', tempo: Number(els.tempo.value), soloChoruses: Number(els.soloChoruses.value), ensemble: els.ensemble.value }));
});

els.stop.addEventListener('click', () => {
  clearTimers();
  socket.send(JSON.stringify({ type: 'stop' }));
  // Notes are scheduled ahead on the audio clock; disposing the synths silences whatever is still queued.
  Object.values(instruments ?? {}).forEach((i) => i.dispose());
  instruments = null;
  els.start.disabled = false;
  els.stop.disabled = true;
  setPerforming(false);
  setStatus('Stopped.');
});

connect();
