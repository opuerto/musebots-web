// The signal path after the instruments: what a mixing desk does. Every instrument gets a channel (tone shaping, gentle
// compression, a place in the stereo picture, and a send to a shared room reverb), and everything goes through a master
// compressor that glues the band together and a limiter that stops the sum from ever clipping.
//
//   instrument -> [high-pass] -> EQ -> [compressor] -> pan -> fader -+-> master compressor -> limiter -> soft clipper -> speakers
//                                                                    +-> send -> reverb -> (back into the master)
//
// Tone's Limiter is a fast compressor, and a fast compressor overshoots on a sharp transient, so the limiter alone can let a peak
// past 0 dBFS (and a recording of the synthesizer did). The soft clipper after it is the guarantee: it leaves everything up to 0.8
// (-1.9 dBFS) untouched and rounds off what is above, so nothing the band plays can clip.

/** Unchanged up to the knee, then a smooth curve: the loudest possible output, for any input, is 0.952 (-0.4 dBFS). */
export function softClip(x, knee = 0.8) {
  const size = Math.min(Math.abs(x), 1); // nothing above full scale is any louder than full scale
  if (size <= knee) return x;
  const room = 1 - knee;
  return Math.sign(x) * (knee + room * Math.tanh((size - knee) / room));
}

export function buildMixer(Tone) {
  const nodes = [];
  const own = (node) => { nodes.push(node); return node; };

  const clipper = own(new Tone.WaveShaper((x) => softClip(x), 4096));
  clipper.oversample = '4x'; // so rounding off a peak does not alias
  clipper.toDestination();
  const limiter = own(new Tone.Limiter(-1));
  limiter.connect(clipper);
  const master = own(new Tone.Compressor({ threshold: -14, ratio: 3, attack: 0.02, release: 0.25 }));
  master.connect(limiter);

  // One shared room: a reverb is expensive, and instruments sound like they are in the same place when they share one.
  const reverb = own(new Tone.Reverb({ decay: 1.7, preDelay: 0.02 }));
  const reverbReturn = own(new Tone.Gain(0.5));
  reverb.connect(reverbReturn);
  reverbReturn.connect(master);

  /**
   * @param gainDb      the fader, in decibels
   * @param pan         -1 (left) to 1 (right)
   * @param send        how much of the channel goes to the room reverb (0 to 1)
   * @param eq          { low, mid, high } in decibels, with optional lowFrequency and highFrequency (Hz) where the bands split
   * @param compressor  { threshold, ratio, attack, release } or null for none
   * @param highpass    a frequency (Hz) below which to cut, or null
   * @returns an object whose `input` is where an instrument connects
   */
  function channel({ gainDb = 0, pan = 0, send = 0.15, eq = {}, compressor = null, highpass = null } = {}) {
    const input = own(new Tone.Gain(1));
    let tail = input;
    if (highpass) {
      const filter = own(new Tone.Filter(highpass, 'highpass'));
      tail.connect(filter);
      tail = filter;
    }
    const equaliser = own(new Tone.EQ3({
      low: eq.low ?? 0, mid: eq.mid ?? 0, high: eq.high ?? 0,
      lowFrequency: eq.lowFrequency ?? 400, highFrequency: eq.highFrequency ?? 2500,
    }));
    tail.connect(equaliser);
    tail = equaliser;
    if (compressor) {
      const dynamics = own(new Tone.Compressor(compressor));
      tail.connect(dynamics);
      tail = dynamics;
    }
    const panner = own(new Tone.Panner(pan));
    tail.connect(panner);
    const fader = own(new Tone.Gain(Tone.dbToGain(gainDb)));
    panner.connect(fader);
    fader.connect(master);
    const toRoom = own(new Tone.Gain(send));
    fader.connect(toRoom);
    toRoom.connect(reverb);
    return { input };
  }

  return {
    channel,
    /** Resolves when the reverb's room has been made. */
    ready: reverb.ready ?? Promise.resolve(),
    dispose() { nodes.forEach((node) => node.dispose()); },
  };
}
