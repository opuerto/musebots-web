// What to tell the person about the sound they are getting.
import { lower } from './notes.js';

const NAMES = { SAX: 'sax', PIANO: 'piano', BASS: 'bass', DRUMS: 'drums' };
const list = (items) => (items.length <= 1 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`);

/** One sentence about how the band was built: recordings where they loaded, the synthesizer where they did not (or by choice), and why. */
export function describeSources(sources) {
  const entries = Object.entries(sources).map(([instrument, source]) => ({ instrument: NAMES[instrument] ?? lower(instrument), ...source }));
  const recorded = entries.filter((entry) => entry.kind === 'samples');
  const synthesized = entries.filter((entry) => entry.kind === 'synth');
  const names = (group) => list(group.map((entry) => entry.instrument));

  if (!recorded.length) {
    return synthesized.every((entry) => entry.reason === 'chosen')
      ? 'Playing with the synthesizer.'
      : `The recordings could not be loaded, so the band is using the synthesizer (${synthesized.find((entry) => entry.reason !== 'chosen').reason}).`;
  }
  const verb = synthesized.length === 1 ? 'is' : 'are';
  const opening = `Playing with ${recorded.length === 1 ? 'a recording' : 'recordings'} of the ${names(recorded)}`;
  if (!synthesized.length) return `${opening}.`;
  const unexpected = synthesized.find((entry) => entry.reason !== 'chosen');
  return unexpected
    ? `${opening}. The ${names(synthesized)} ${verb} synthesized (${unexpected.reason}).`
    : `${opening}; the ${names(synthesized)} ${verb} synthesized.`;
}
