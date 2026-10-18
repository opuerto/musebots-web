// The drum kit. Notes arrive as General MIDI percussion numbers, as written by the server's drum composer.
//
// Tone.js instruments do not share one call shape, and getting it wrong fails silently (the hit just fires at the
// wrong time or pitch), so keep these in step with the library:
//   MembraneSynth, MetalSynth: triggerAttackRelease(note, duration, time, velocity)
//   NoiseSynth:                triggerAttackRelease(duration, time, velocity)
// `time` is always an absolute audio-context time in seconds, and `velocity` is 0 to 1.

// A cymbal's "note" is the base frequency of its oscillators, so it picks the pitch of the metal.
const RIDE_HZ = 250;
const HAT_HZ = 400;
const CRASH_HZ = 200;

/** @param out  where the kit's sound goes (a mixer channel's input); the speakers if omitted */
export function buildDrumKit(Tone, { out } = {}) {
  const route = (node) => (out ? node.connect(out) : node.toDestination());

  const kick = route(new Tone.MembraneSynth({ pitchDecay: 0.04, octaves: 5, envelope: { attack: 0.001, decay: 0.3, sustain: 0 }, volume: -8 }));
  const tom = route(new Tone.MembraneSynth({ pitchDecay: 0.08, octaves: 2, envelope: { attack: 0.001, decay: 0.35, sustain: 0 }, volume: -10 }));
  // A snare is two things at once: the snares rattling (noise, with the low end taken off) and the drum's own body (a short pitched thump).
  const snareFilter = route(new Tone.Filter(1800, 'highpass'));
  const snare = new Tone.NoiseSynth({ noise: { type: 'white' }, envelope: { attack: 0.001, decay: 0.12, sustain: 0 }, volume: -18 }).connect(snareFilter);
  const snareBody = route(new Tone.MembraneSynth({ pitchDecay: 0.015, octaves: 2, envelope: { attack: 0.001, decay: 0.09, sustain: 0 }, volume: -16 }));
  const ride = route(new Tone.MetalSynth({ envelope: { attack: 0.001, decay: 0.35, release: 0.1 }, harmonicity: 5.1, modulationIndex: 24, resonance: 5200, octaves: 1.2, volume: -23 }));
  const hat = route(new Tone.MetalSynth({ envelope: { attack: 0.001, decay: 0.06, release: 0.02 }, harmonicity: 5.1, modulationIndex: 20, resonance: 7000, octaves: 1, volume: -24 }));
  const crash = route(new Tone.MetalSynth({ envelope: { attack: 0.001, decay: 1.2, release: 0.4 }, harmonicity: 5.1, modulationIndex: 32, resonance: 3800, octaves: 1.5, volume: -22 }));

  const velocity = (note) => note.velocity / 127;

  const pieces = {
    36: (note, when) => kick.triggerAttackRelease('C1', 0.2, when, velocity(note)),
    38: (note, when) => {
      snare.triggerAttackRelease(0.12, when, velocity(note));
      snareBody.triggerAttackRelease('G3', 0.08, when, velocity(note) * 0.8);
    },
    41: (note, when) => tom.triggerAttackRelease('C2', 0.3, when, velocity(note)),
    44: (note, when) => hat.triggerAttackRelease(HAT_HZ, 0.05, when, velocity(note)),
    45: (note, when) => tom.triggerAttackRelease('F2', 0.3, when, velocity(note)),
    47: (note, when) => tom.triggerAttackRelease('A2', 0.3, when, velocity(note)),
    49: (note, when) => crash.triggerAttackRelease(CRASH_HZ, 1.0, when, velocity(note)),
    50: (note, when) => tom.triggerAttackRelease('D3', 0.3, when, velocity(note)),
    51: (note, when) => ride.triggerAttackRelease(RIDE_HZ, 0.3, when, velocity(note)),
  };

  return {
    play: (note, when) => pieces[note.midi]?.(note, when),
    dispose: () => [kick, tom, snare, snareFilter, snareBody, ride, hat, crash].forEach((node) => node.dispose()),
  };
}

// The pieces a drum composer may write, so tests can check every one has a sound.
export const DRUM_PIECES = [36, 38, 41, 44, 45, 47, 49, 50, 51];
