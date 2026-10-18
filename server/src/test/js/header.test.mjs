import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const html = readFileSync(new URL('../../main/resources/public/index.html', import.meta.url), 'utf8');
const text = (fragment) => fragment.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();

test('the page is titled with the project and its author', () => {
  assert.match(text(/<title>(.*?)<\/title>/s.exec(html)[1]), /^Musebot Agents by Oscar Puerto$/);
  assert.match(text(/<h1>(.*?)<\/h1>/s.exec(html)[1]), /^Musebot Agents by Oscar Puerto$/);
});

test('the page says it is research and cites the paper, linking to it', () => {
  const research = text(/<p class="research">(.*?)<\/p>/s.exec(html)[1]);
  assert.match(research, /research/i);
  assert.match(research, /Reykjavik University/);

  const paper = /<p class="paper">(.*?)<\/p>/s.exec(html)[1];
  const citation = text(paper);
  assert.match(citation, /Puerto, O\. and Thue, D\. \(2017\)/);
  assert.match(citation, /A Model of Inter-musician Communication for Artificial Musical Intelligence/);
  assert.match(citation, /International Conference on Computational Creativity \(ICCC 2017\)/);

  const link = /<a\s+href="([^"]+)"([^>]*)>/.exec(paper);
  assert.equal(link[1], 'https://computationalcreativity.net/iccc2017/ICCC_17_accepted_submissions/ICCC-17_paper_57.pdf');
  assert.match(link[2], /rel="noopener noreferrer"/, 'a link that opens a new tab must not hand it this page');
});

test('every recording used is credited on the page, with its licence and where it came from, and nothing else is', async () => {
  const { SAMPLE_SETS } = await import('../../main/resources/public/sampleSets.js');
  const credits = /<details class="credits">(.*?)<\/details>/s.exec(html)[1];
  const text = credits.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');

  for (const word of ['Salamander', 'Alexander Holm', 'FluidR3', 'Tone.js']) assert.ok(text.includes(word), `the credits should name ${word}`);
  assert.ok(text.includes('MIT'), 'Tone.js is MIT licensed');
  assert.ok(text.includes('sax and the drums are synthesized'), 'and says what is not a recording');
  // The recordings that were tried and removed must not still be credited.
  for (const removed of ['Karoryfer', 'Berklee']) assert.ok(!text.includes(removed), `${removed} is no longer used`);

  assert.deepEqual(Object.keys(SAMPLE_SETS), ['PIANO', 'BASS']);
  assert.ok(Object.values(SAMPLE_SETS).every((set) => set.license === 'CC BY 3.0'));
  const links = [...credits.matchAll(/href="([^"]+)"/g)].map((match) => match[1]);
  for (const expected of ['https://creativecommons.org/licenses/by/3.0/', 'https://creativecommons.org/licenses/by/3.0/us/',
    'https://github.com/Tonejs/audio/tree/master/salamander', 'https://github.com/gleitz/midi-js-soundfonts']) {
    assert.ok(links.includes(expected), `missing link ${expected}`);
  }
  assert.ok([...credits.matchAll(/<a [^>]*target="_blank"[^>]*>/g)].every((match) => match[0].includes('rel="noopener noreferrer"')));
});
