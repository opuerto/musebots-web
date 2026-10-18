// The band's sounds. Every instrument exposes play(note, when) and dispose(), so scheduling does not care what makes the sound.
//
// Two ways of making them:
//   mixed:  the piano and the acoustic bass as recordings (see sampleSets.js), played by Tone.js samplers that shift the pitch of the
//           nearest recorded note; the sax and drums synthesized. The default. A recorded sax (it sounded like a flute) and a recorded
//           kick and crash (the drums sounded like noise) were tried and judged worse than the synthesizer, so they are not used.
//   synth:  everything synthesized. Needs nothing downloaded, and is also the fallback for any recording that fails to load.
// Either way they go through the mixer (mixer.js): tone shaping, compression, stereo position and a shared room reverb.
import { buildDrumKit } from './drums.js';
import { buildMixer } from './mixer.js';
import { SAMPLE_SETS, loadSet, midiToNote } from './sampleSets.js';

/** MIDI velocity to loudness. Squaring-ish (rather than linear) makes soft notes really soft, as they are on a real instrument. */
export const loudness = (velocity) => Math.pow(Math.max(1, Math.min(127, velocity)) / 127, 1.3);

/**
 * Where each instrument sits and how it is shaped. The two columns differ because a recording and a synthesizer voice come out at
 * very different levels (the bass recording is about 20 dB quieter than the piano recording is loud), and were set by rendering each
 * with the real code and measuring it. The sax and drums are always synthesized.
 */
const MIX = {
  SAX: { synth: { gainDb: 10.5, pan: 0.25, send: 0.22, eq: { low: -2, mid: 0, high: 1 } } },
  PIANO: {
    samples: { gainDb: 9, pan: -0.3, send: 0.2, eq: { low: -1, mid: 0, high: 0.5 }, compressor: { threshold: -26, ratio: 2, attack: 0.02, release: 0.25 } },
    synth: { gainDb: 22.5, pan: -0.3, send: 0.22, eq: {} },
  },
  BASS: {
    samples: { gainDb: 9, pan: 0, send: 0.05, highpass: 35, eq: { low: 2, mid: 0, high: -1, lowFrequency: 150 }, compressor: { threshold: -30, ratio: 3, attack: 0.01, release: 0.15 } },
    synth: { gainDb: -3, pan: 0, send: 0.05, highpass: 35, eq: { low: 1 } },
  },
  DRUMS: { synth: { gainDb: 5.5, pan: 0, send: 0.14, eq: {}, compressor: { threshold: -20, ratio: 3, attack: 0.005, release: 0.12 } } },
};

// ---- the synthesizer voices ----

function synthSax(Tone, out) {
  // A breathy, slightly detuned saw with vibrato.
  const vibrato = new Tone.Vibrato({ frequency: 5, depth: 0.12 }).connect(out);
  const voice = new Tone.MonoSynth({
    oscillator: { type: 'fatsawtooth', count: 2, spread: 14 },
    envelope: { attack: 0.04, decay: 0.1, sustain: 0.8, release: 0.12 },
    filter: { type: 'lowpass', Q: 1 },
    filterEnvelope: { attack: 0.05, decay: 0.25, sustain: 0.55, baseFrequency: 500, octaves: 2.6 },
    volume: -10,
  }).connect(vibrato);
  return playing(voice, [voice, vibrato]);
}

function synthPiano(Tone, out) {
  const voice = new Tone.PolySynth(Tone.FMSynth, {
    harmonicity: 3, modulationIndex: 1.5,
    envelope: { attack: 0.005, decay: 0.4, sustain: 0.2, release: 0.5 },
    modulationEnvelope: { attack: 0.01, decay: 0.2, sustain: 0.1, release: 0.3 },
    volume: -14,
  }).connect(out);
  return playing(voice, [voice]);
}

function synthBass(Tone, out) {
  const voice = new Tone.MonoSynth({
    oscillator: { type: 'triangle' },
    envelope: { attack: 0.01, decay: 0.25, sustain: 0.5, release: 0.15 },
    filterEnvelope: { attack: 0.01, decay: 0.2, sustain: 0.4, baseFrequency: 120, octaves: 2.5 },
    volume: -6,
  }).connect(out);
  return playing(voice, [voice]);
}

/** A note on a synthesizer voice, with the Tone.js argument order: note, duration, time, velocity. */
function playing(voice, nodes) {
  return {
    play: (note, when) => voice.triggerAttackRelease(midiToNote(note.midi), note.durationMs / 1000, when, note.velocity / 127),
    dispose: () => nodes.forEach((node) => node.dispose()),
  };
}

// ---- the recorded voices ----

/** A recorded instrument: a sampler playing from already-decoded buffers, so a rebuilt band reloads nothing. */
function recorded(Tone, buffers, set, out) {
  const voice = new Tone.Sampler({ urls: Object.fromEntries(buffers), attack: set.attack, release: set.release, curve: 'exponential' }).connect(out);
  return {
    play: (note, when) => voice.triggerAttackRelease(midiToNote(note.midi), note.durationMs / 1000, when, loudness(note.velocity)),
    dispose: () => voice.dispose(),
  };
}

// ---- putting the band together ----

/** Which recordings each way of building the band needs. */
const RECORDINGS = { mixed: ['PIANO', 'BASS'], synth: [] };

/**
 * @param sound       'mixed' or 'synth' (see above); a recording that fails to load falls back to the synthesizer
 * @param onProgress  called as recordings arrive: ({ done, total, label })
 * @returns { instruments, sources, dispose }: the instruments by name, what each one is made of ("samples" or "synth", with the reason
 *          for a synthesized one), and a function that takes the whole band down (which also silences any notes already scheduled)
 */
export async function buildInstruments(Tone, { sound = 'mixed', onProgress = () => {}, timeoutMs } = {}) {
  const wanted = RECORDINGS[sound];
  if (!wanted) throw new Error(`unknown sound '${sound}'`);

  const mixer = buildMixer(Tone);
  const loaded = {};
  const failures = {};
  let done = 0;
  await Promise.all(wanted.map(async (name) => {
    try {
      loaded[name] = await loadSet(Tone, SAMPLE_SETS[name], { timeoutMs });
    } catch (error) {
      failures[name] = error.message;
    } finally {
      done += 1;
      onProgress({ done, total: wanted.length, label: SAMPLE_SETS[name].label });
    }
  }));
  await mixer.ready;

  const instruments = {};
  const sources = {};
  // Synthesized because that was the choice, or because the recording could not be loaded.
  const whyNot = (instrument) => (wanted.includes(instrument) ? failures[instrument] ?? 'the recording did not load' : 'chosen');

  const make = (instrument, recordedVoice, synthVoice) => {
    const buffers = loaded[instrument];
    const kind = buffers ? 'samples' : 'synth';
    const out = mixer.channel(MIX[instrument][kind]).input;
    instruments[instrument] = buffers ? recordedVoice(out, buffers) : synthVoice(out);
    sources[instrument] = buffers ? { kind } : { kind, reason: whyNot(instrument) };
  };

  make('SAX', null, (out) => synthSax(Tone, out));
  make('PIANO', (out, buffers) => recorded(Tone, buffers, SAMPLE_SETS.PIANO, out), (out) => synthPiano(Tone, out));
  make('BASS', (out, buffers) => recorded(Tone, buffers, SAMPLE_SETS.BASS, out), (out) => synthBass(Tone, out));

  const kit = buildDrumKit(Tone, { out: mixer.channel(MIX.DRUMS.synth).input });
  instruments.DRUMS = kit;
  sources.DRUMS = { kind: 'synth', reason: 'chosen' };

  return {
    instruments,
    sources,
    dispose: () => {
      Object.values(instruments).forEach((instrument) => instrument.dispose());
      mixer.dispose();
    },
  };
}
