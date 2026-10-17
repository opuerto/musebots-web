// A fake Tone.js whose instruments take the same arguments as the real ones, so a call that passes its arguments in
// the wrong order fails in a test instead of silently misfiring in the browser.
//
//   MonoSynth, PolySynth, FMSynth, MembraneSynth, MetalSynth: triggerAttackRelease(note, duration, time, velocity)
//   NoiseSynth:                                               triggerAttackRelease(duration, time, velocity)
//
// Every call is pushed onto `calls` with the name of the instrument that received it.
export function makeFakeTone(calls = []) {
  class Node {
    constructor(options = {}) { this.options = options; this.disposed = false; }
    toDestination() { return this; }
    connect() { return this; }
    dispose() { this.disposed = true; }
  }
  class Pitched extends Node {
    triggerAttackRelease(note, duration, time, velocity) {
      calls.push({ kind: this.constructor.name, note, duration, time, velocity });
    }
  }
  class NoiseSynth extends Node {
    triggerAttackRelease(duration, time, velocity) {
      calls.push({ kind: 'NoiseSynth', duration, time, velocity });
    }
  }
  class PolySynth extends Pitched {
    constructor(voice, options) { super(options); this.voice = voice; }
  }
  const named = (name) => ({ [name]: class extends Node {} })[name];

  return {
    MonoSynth: class MonoSynth extends Pitched {},
    FMSynth: class FMSynth extends Pitched {},
    MembraneSynth: class MembraneSynth extends Pitched {},
    MetalSynth: class MetalSynth extends Pitched {},
    PolySynth,
    NoiseSynth,
    Reverb: named('Reverb'),
    Vibrato: named('Vibrato'),
    Filter: named('Filter'),
    Frequency: (value, unit) => ({ value, unit }),
    start: async () => {},
    now: () => 100,
  };
}
