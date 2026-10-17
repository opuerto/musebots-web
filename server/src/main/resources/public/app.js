// Browser client: keeps a clock offset to the server, then schedules each note on the Web Audio clock.

const SYNC_ROUNDS = 8;

const els = {
  tempo: document.getElementById('tempo'),
  tempoOut: document.getElementById('tempoOut'),
  choruses: document.getElementById('choruses'),
  start: document.getElementById('start'),
  stop: document.getElementById('stop'),
  status: document.getElementById('status'),
  conversation: document.getElementById('conversation'),
};

let socket;
let clockOffsetMs = 0; // serverTime - clientTime, from the lowest-latency sync round
let bestRtt = Infinity;
let instruments = null;

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
    case 'error': setStatus(`Error: ${message.message}`); els.start.disabled = false; break;
  }
}

function buildInstruments() {
  const reverb = new Tone.Reverb({ decay: 1.8, wet: 0.15 }).toDestination();
  return {
    BASS: new Tone.MonoSynth({
      oscillator: { type: 'triangle' },
      envelope: { attack: 0.01, decay: 0.25, sustain: 0.5, release: 0.15 },
      filterEnvelope: { attack: 0.01, decay: 0.2, sustain: 0.4, baseFrequency: 120, octaves: 2.5 },
      volume: -6,
    }).toDestination(),
    PIANO: new Tone.PolySynth(Tone.FMSynth, {
      harmonicity: 3, modulationIndex: 1.5,
      envelope: { attack: 0.005, decay: 0.4, sustain: 0.2, release: 0.5 },
      modulationEnvelope: { attack: 0.01, decay: 0.2, sustain: 0.1, release: 0.3 },
      volume: -14,
    }).connect(reverb),
  };
}

function schedule(note) {
  const instrument = instruments?.[note.instrument];
  if (!instrument) return;
  const delaySeconds = (note.startMs - serverNow()) / 1000;
  if (delaySeconds < -0.05) return; // arrived too late to play in time
  const when = Tone.now() + Math.max(0, delaySeconds);
  const frequency = Tone.Frequency(note.midi, 'midi');
  instrument.triggerAttackRelease(frequency, note.durationMs / 1000, when, note.velocity / 127);
}

function log(message) {
  const li = document.createElement('li');
  li.dataset.performative = message.performative;
  const left = document.createElement('div');
  left.innerHTML = `<div class="who"></div><div class="performative"></div>`;
  left.querySelector('.who').textContent = `${shortName(message.from)} → ${message.to.map(shortName).join(', ')}`;
  left.querySelector('.performative').textContent = message.performative;
  const right = document.createElement('div');
  right.textContent = message.summary;
  li.append(left, right);
  els.conversation.append(li);
}

// Agent names look like "s1a2b3c4d5e6-piano"; the instrument is what matters to a reader.
const shortName = (name) => name.split('-').pop();

function onStatus(message) {
  switch (message.kind) {
    case 'ENSEMBLE_READY': setStatus('The ensemble is assembled…'); break;
    case 'STARTED': setStatus('Intro…'); break;
    case 'FINISHED': setStatus('Finished.'); els.start.disabled = false; els.stop.disabled = true; break;
    case 'FAILED': setStatus(`Failed: ${message.message}`); els.start.disabled = false; els.stop.disabled = true; break;
  }
}

els.tempo.addEventListener('input', () => { els.tempoOut.textContent = els.tempo.value; });

els.start.addEventListener('click', async () => {
  await Tone.start(); // browsers only allow audio after a user gesture
  instruments ??= buildInstruments();
  els.conversation.replaceChildren();
  els.start.disabled = true;
  els.stop.disabled = false;
  setStatus('Asking the musicians to gather…');
  socket.send(JSON.stringify({ type: 'start', tempo: Number(els.tempo.value), choruses: Number(els.choruses.value) }));
});

els.stop.addEventListener('click', () => {
  socket.send(JSON.stringify({ type: 'stop' }));
  // Notes are scheduled ahead on the audio clock; disposing the synths silences whatever is still queued.
  Object.values(instruments ?? {}).forEach((i) => i.dispose());
  instruments = null;
  els.start.disabled = false;
  els.stop.disabled = true;
  setStatus('Stopped.');
});

connect();
