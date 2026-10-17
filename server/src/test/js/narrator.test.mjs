import assert from 'node:assert/strict';
import test from 'node:test';
import { caption, leaderLabel } from '../../main/resources/public/narrator.js';

const located = (kind, leader, extra = {}) => ({ kind, leader, chorus: 1, chorusCount: 1, ...extra });

test('says who plays the intro and that the leader waits', () => {
  const text = caption(located('INTRO', 'PIANO'), undefined);
  assert.match(text, /The piano won the call for the intro/);
  assert.match(text, /leader waits/);
});

test('says who leads the head and what the others do', () => {
  assert.match(caption(located('HEAD', 'SAX')), /The leader \(sax\) plays the head/);
  assert.match(caption(located('HEAD', 'SAX')), /synchronizer/);
});

test('a solo names who handed over the lead', () => {
  const text = caption(located('SOLO', 'PIANO'), { leader: 'SAX' });
  assert.match(text, /^The sax hands the lead to the piano\. The piano solos while the others accompany/);
});

test('a musician who keeps the lead for their own solo is not said to receive it from themselves', () => {
  assert.doesNotMatch(caption(located('SOLO', 'SAX'), { leader: 'SAX' }), /hands the lead/);
  assert.doesNotMatch(caption(located('SOLO', 'SAX'), undefined), /hands the lead/);
});

test('a solo of several choruses says which one this is', () => {
  assert.match(caption(located('SOLO', 'BASS', { chorus: 2, chorusCount: 3 }), undefined), /bass solos, chorus 2 of 3/);
  assert.doesNotMatch(caption(located('SOLO', 'BASS'), undefined), /chorus/);
});

test('the drums solo (plural), and the band plays softly behind them', () => {
  const text = caption(located('SOLO', 'DRUMS'), { leader: 'BASS' });
  assert.match(text, /The drums solo while the others accompany/);
  assert.match(text, /plays softly/);
});

test('the ending asks for one last chord', () => {
  assert.match(caption(located('ENDING', 'DRUMS')), /one last chord/);
});

test('nothing playing means nothing to say', () => {
  assert.equal(caption(null), '');
  assert.equal(leaderLabel(null), '');
  assert.equal(leaderLabel(located('SOLO', 'BASS')), 'Bass');
});
