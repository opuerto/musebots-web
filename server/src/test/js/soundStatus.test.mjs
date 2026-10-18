import assert from 'node:assert/strict';
import test from 'node:test';
import { describeSources } from '../../main/resources/public/soundStatus.js';

const samples = { kind: 'samples' };
const synth = (reason) => ({ kind: 'synth', reason });
const chosen = synth('chosen');

test('the default band: piano and bass recorded, sax and drums synthesized by choice', () => {
  assert.equal(describeSources({ SAX: chosen, PIANO: samples, BASS: samples, DRUMS: chosen }),
    'Playing with recordings of the piano and bass; the sax and drums are synthesized.');
});

test('the synthesizer by choice', () => {
  assert.equal(describeSources({ SAX: chosen, PIANO: chosen, BASS: chosen, DRUMS: chosen }), 'Playing with the synthesizer.');
});

test('nothing recorded because the recordings did not load: say why', () => {
  const reason = 'only 0 of 30 files of "Piano" could be loaded';
  assert.equal(describeSources({ SAX: chosen, PIANO: synth(reason), BASS: synth(reason), DRUMS: chosen }),
    `The recordings could not be loaded, so the band is using the synthesizer (${reason}).`);
});

test('one recording failing is named, with why, and the rest are not blamed', () => {
  assert.equal(describeSources({ SAX: chosen, PIANO: samples, BASS: synth('timed out'), DRUMS: chosen }),
    'Playing with a recording of the piano. The sax, bass and drums are synthesized (timed out).');
});

test('a single recording is "a recording", several are "recordings"', () => {
  assert.match(describeSources({ SAX: chosen, PIANO: samples, BASS: chosen, DRUMS: chosen }), /^Playing with a recording of the piano; the sax, bass and drums are synthesized\.$/);
  assert.match(describeSources({ SAX: chosen, PIANO: samples, BASS: samples, DRUMS: chosen }), /^Playing with recordings of the piano and bass;/);
});
