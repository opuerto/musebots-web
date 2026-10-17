// The band's sounds. Every instrument exposes play(note, when) and dispose(), so scheduling does not care what makes the sound.
import { buildDrumKit } from './drums.js';

export function buildInstruments(Tone) {
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

  const pitched = (synth) => ({
    play: (note, when) => synth.triggerAttackRelease(Tone.Frequency(note.midi, 'midi'), note.durationMs / 1000, when, note.velocity / 127),
    dispose: () => synth.dispose(),
  });

  return {
    BASS: pitched(bass),
    PIANO: pitched(piano),
    SAX: pitched(sax),
    DRUMS: buildDrumKit(Tone),
  };
}
