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
