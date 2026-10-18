// A fake Tone.js whose instruments take the same arguments as the real ones, so a call that passes its arguments in
// the wrong order fails in a test instead of silently misfiring in the browser.
//
//   MonoSynth, PolySynth, FMSynth, MembraneSynth, MetalSynth, Sampler: triggerAttackRelease(note, duration, time, velocity)
//   NoiseSynth:                                                        triggerAttackRelease(duration, time, velocity)
//
// Every trigger is pushed onto `calls` with the name of the instrument that received it. The audio graph is recorded too, so a test
// can ask whether something is connected through to the speakers, and signal automation (the vibrato's depth) is recorded.
//
//   makeFakeTone(calls, { badUrls }): URLs containing any of the `badUrls` strings fail to load (as a 404 or a corrupt file would).
export function makeFakeTone(calls = [], { badUrls = [], loadDelayMs = 0 } = {}) {
  const created = [];
  const loads = [];

  class Signal {
    constructor() { this.events = []; }
    setValueAtTime(value, time) { this.events.push({ type: 'set', value, time }); return this; }
    linearRampToValueAtTime(value, time) { this.events.push({ type: 'ramp', value, time }); return this; }
  }

  class Node {
    constructor(options = {}) {
      this.options = options;
      this.disposed = false;
      this.targets = [];
      this.toSpeakers = false;
      created.push(this);
    }
    toDestination() { this.toSpeakers = true; return this; }
    connect(target) { this.targets.push(target); return this; }
    dispose() { this.disposed = true; }
    /** Whether the signal from this node reaches the speakers. */
    reachesSpeakers(seen = new Set()) {
      if (this.toSpeakers) return true;
      if (seen.has(this)) return false;
      seen.add(this);
      return this.targets.some((target) => target.reachesSpeakers?.(seen));
    }
  }
  class Pitched extends Node {
    triggerAttackRelease(note, duration, time, velocity) {
      calls.push({ kind: this.constructor.name, note, duration, time, velocity, node: this });
    }
  }
  class NoiseSynth extends Node {
    triggerAttackRelease(duration, time, velocity) {
      calls.push({ kind: 'NoiseSynth', duration, time, velocity, node: this });
    }
  }
  class PolySynth extends Pitched {
    constructor(voice, options) { super(options); this.voice = voice; }
  }
  class Sampler extends Pitched {
    constructor(options) { super(options); this.urls = options.urls; this.attack = options.attack; this.release = options.release; }
  }
  class WaveShaper extends Node {
    constructor(mapping, length) { super({ mapping, length }); this.oversample = 'none'; }
  }
  class Vibrato extends Node {
    constructor(options) { super(options); this.depth = new Signal(); this.frequency = new Signal(); }
  }
  class Reverb extends Node {
    constructor(options) { super(options); this.ready = Promise.resolve(); }
  }
  const named = (name) => ({ [name]: class extends Node {} })[name];

  /** A decoded recording: just enough of an AudioBuffer to tell recordings apart. */
  class ToneAudioBuffer {
    static async fromUrl(url) {
      loads.push(url);
      if (loadDelayMs) await new Promise((resolve) => setTimeout(resolve, loadDelayMs));
      if (badUrls.some((bad) => url.includes(bad))) throw new Error(`could not load url: ${url}`);
      return { get: () => ({ url, fakeAudioBuffer: true }) };
    }
  }

  return {
    created,
    loads,
    MonoSynth: class MonoSynth extends Pitched {},
    FMSynth: class FMSynth extends Pitched {},
    MembraneSynth: class MembraneSynth extends Pitched {},
    MetalSynth: class MetalSynth extends Pitched {},
    PolySynth,
    NoiseSynth,
    Sampler,
    Vibrato,
    Reverb,
    ToneAudioBuffer,
    WaveShaper,
    Limiter: named('Limiter'),
    Compressor: named('Compressor'),
    EQ3: named('EQ3'),
    Panner: named('Panner'),
    Gain: named('Gain'),
    Filter: named('Filter'),
    dbToGain: (db) => Math.pow(10, db / 20),
    Frequency: (value, unit) => ({ value, unit }),
    start: async () => {},
    now: () => 100,
  };
}
