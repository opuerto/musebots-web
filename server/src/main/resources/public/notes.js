// What the band is called, how it looks, and what its notes are called.

export const ORDER = ['SAX', 'PIANO', 'BASS', 'DRUMS'];

export const INSTRUMENTS = {
  SAX: { label: 'Sax', asset: 'assets/sax.svg', color: '#d99a1e' },
  PIANO: { label: 'Piano', asset: 'assets/piano.svg', color: '#4f74d9' },
  BASS: { label: 'Bass', asset: 'assets/bass.svg', color: '#b45a1c' },
  DRUMS: { label: 'Drums', asset: 'assets/drums.svg', color: '#c2352a' },
};

const PITCH_NAMES = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B'];

// General MIDI percussion numbers, as written by the server's drum composer.
const DRUM_NAMES = { 36: 'Kick', 38: 'Snare', 41: 'Floor tom', 44: 'Hi-hat', 45: 'Low tom', 47: 'Mid tom', 49: 'Crash', 50: 'High tom', 51: 'Ride' };

/** "Ab4" for a pitch, "Ride" for a drum. Middle C (MIDI 60) is C4. */
export function noteName(instrument, midi) {
  if (instrument === 'DRUMS') return DRUM_NAMES[midi] ?? `Drum ${midi}`;
  return `${PITCH_NAMES[midi % 12]}${Math.floor(midi / 12) - 1}`;
}

/** What to show for the notes sounding now: lowest first, no repeats, at most four. */
export function readout(instrument, midis) {
  const unique = [...new Set(midis)];
  if (instrument !== 'DRUMS') unique.sort((a, b) => a - b);
  return unique.slice(0, 4).map((midi) => noteName(instrument, midi)).join(' · ');
}

export const lower = (instrument) => INSTRUMENTS[instrument].label.toLowerCase();
export const capital = (instrument) => INSTRUMENTS[instrument].label;
