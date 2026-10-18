// Where the instrument recordings come from, what licence they are under, and how to load them.
//
// Only the recordings that were judged better than the synthesizer are here: the piano and the acoustic bass. A recorded saxophone
// (it sounded like a flute) and a recorded kick and crash (the drums sounded like noise) were tried and taken out.
//
// Every source below is pinned to a commit, so an upstream change cannot alter the sound or break the page, and is served by
// jsDelivr (a CDN for GitHub) with CORS enabled and a long cache, so the second visit loads from the browser's cache.
// Each set lists only files that were checked to decode and to be in tune (within a few cents of their note name); a file
// that is missing or broken is skipped at load time, and an instrument with too few files falls back to the synthesizer.

const CDN = 'https://cdn.jsdelivr.net/gh';
const TONE_AUDIO = 'efd8296360f9526e379bfbe5c1698ff54d6a1d34'; // github.com/Tonejs/audio
const TONE_INSTRUMENTS = '622c2f1c32c8cfce4158ddc3eb26e518ddef37e5'; // github.com/nbrosowsky/tonejs-instruments
const MIDI_SOUNDFONTS = '044fab8e1456bfafc5776e86dfd6bb8697149aef'; // github.com/gleitz/midi-js-soundfonts

const SHARPS = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
const LETTERS = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };

/** The name of a MIDI pitch: 60 is "C4". */
export const midiToNote = (midi) => `${SHARPS[midi % 12]}${Math.floor(midi / 12) - 1}`;

/** The MIDI pitch of "C4", "D#3" or "Bb1". */
export function noteToMidi(note) {
  const match = /^([A-G])([#b]?)(-?\d)$/.exec(note);
  if (!match) throw new Error(`not a note name: ${note}`);
  return 12 * (Number(match[3]) + 1) + LETTERS[match[1]] + (match[2] === '#' ? 1 : match[2] === 'b' ? -1 : 0);
}

/** The Tone.js repositories name sharps with an "s": "Ds1" is D#1. */
export const fileToNote = (file) => file.replace(/^([A-G])s(-?\d)$/, '$1#$2');

const salamanderFiles = ['A0', 'C8'];
for (let octave = 1; octave <= 7; octave += 1) salamanderFiles.push(`C${octave}`, `Ds${octave}`, `Fs${octave}`, `A${octave}`);

export const SAMPLE_SETS = {
  PIANO: {
    id: 'salamander-piano',
    label: 'Piano: Salamander Grand Piano V2 (Yamaha C5)',
    credit: 'Alexander Holm (Salamander Grand Piano V2), hosted by the Tone.js project',
    license: 'CC BY 3.0',
    kind: 'files',
    baseUrl: `${CDN}/Tonejs/audio@${TONE_AUDIO}/salamander/`,
    files: salamanderFiles,
    attack: 0,
    release: 0.8,
  },
  BASS: {
    id: 'fluidr3-acoustic-bass',
    label: 'Acoustic bass: FluidR3 GM soundfont',
    credit: 'FluidR3 GM soundfont, as converted by midi-js-soundfonts (gleitz)',
    license: 'CC BY 3.0',
    kind: 'soundfont-js',
    url: `${CDN}/gleitz/midi-js-soundfonts@${MIDI_SOUNDFONTS}/FluidR3_GM/acoustic_bass-mp3.js`,
    soundfont: 'acoustic_bass',
    // The band's bass plays from C2 to C4; keep a little either side, not the whole eight octaves.
    keep: (note) => noteToMidi(note) >= 33 && noteToMidi(note) <= 72,
    attack: 0.002,
    release: 0.14,
  },
};

/** The note-to-URL map of a set that is a folder of MP3 files. */
export function urlsOf(set) {
  const files = Array.isArray(set.files) ? Object.fromEntries(set.files.map((file) => [fileToNote(file), file])) : set.files;
  return Object.fromEntries(Object.entries(files).map(([note, file]) => [note, `${set.baseUrl}${file}.mp3`]));
}

/**
 * The notes of a midi-js-soundfonts file. It is JavaScript, not JSON: `MIDI.Soundfont.acoustic_bass = {"A0": "data:audio/mp3;base64,...",},`
 * with a trailing comma. Each value is a data: URL that can be loaded like any other.
 */
export function parseSoundfontJs(text, name) {
  const assignment = text.indexOf(`Soundfont.${name}`);
  const start = assignment < 0 ? -1 : text.indexOf('{', assignment);
  const end = text.lastIndexOf('}');
  if (start < 0 || end < start) throw new Error(`no soundfont named ${name} in that file`);
  const notes = JSON.parse(text.slice(start, end + 1).replace(/,\s*}$/, '}'));
  if (Object.values(notes).some((value) => typeof value !== 'string' || !value.startsWith('data:audio'))) {
    throw new Error(`that soundfont is not made of audio data`);
  }
  return notes;
}

const decoded = new Map(); // set id -> Map(note -> AudioBuffer), so a rebuilt band never downloads anything again

/** Rejects if the promise has not settled in time. */
function withTimeout(promise, ms, what) {
  let timer;
  const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${what} took longer than ${ms / 1000} s`)), ms); });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/**
 * Loads a set into decoded buffers, one file at a time, so a missing or broken file costs one note rather than the instrument.
 *
 * @param options.fetchText  how to fetch a soundfont .js file (for tests)
 * @param options.onFile     called each time a file has loaded
 * @param options.minShare   the share of a set's files that must load, below which the set is rejected
 */
export async function loadSet(Tone, set, { fetchText = defaultFetchText, onFile = () => {}, timeoutMs = 45_000, minShare = 0.6 } = {}) {
  if (decoded.has(set.id)) return decoded.get(set.id);

  const urls = set.kind === 'soundfont-js'
    ? Object.fromEntries(Object.entries(parseSoundfontJs(await withTimeout(fetchText(set.url), timeoutMs, set.label), set.soundfont))
      .filter(([note]) => set.keep(note)))
    : urlsOf(set);
  const entries = Object.entries(urls);

  const settled = await withTimeout(Promise.allSettled(entries.map(async ([note, url]) => {
    const buffer = await Tone.ToneAudioBuffer.fromUrl(url);
    onFile();
    return [note, buffer.get()];
  })), timeoutMs, set.label);

  const loaded = settled.filter((result) => result.status === 'fulfilled').map((result) => result.value);
  if (loaded.length < Math.max(1, Math.ceil(entries.length * minShare))) {
    throw new Error(`only ${loaded.length} of ${entries.length} files of "${set.label}" could be loaded`);
  }
  const buffers = new Map(loaded);
  decoded.set(set.id, buffers);
  return buffers;
}

async function defaultFetchText(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`could not load ${url} (${response.status})`);
  return response.text();
}

/** Forgets what was loaded (for tests). */
export function forgetLoadedSets() {
  decoded.clear();
}
