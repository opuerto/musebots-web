import assert from 'node:assert/strict';
import test from 'node:test';
import { barsPerChorus, locate, positionTitle, roleOf, sectionOf, whereText } from '../../main/resources/public/timeline.js';

const song1 = {
  title: 'Song 1', form: 'AAB', beatsPerBar: 4, ending: 'CM7',
  sections: [
    { name: 'A', bars: ['Em7', 'A7', 'Dm7', 'G7'] },
    { name: 'A', bars: ['Em7', 'A7', 'Dm7', 'G7'] },
    { name: 'B', bars: ['CM7', 'CM7'] },
  ],
};

// 120 bpm in 4/4: a bar is 2000 ms. Ten bars (one chorus of song 1) is 20 000 ms.
const BAR = 2000;
const part = (id, kind, leader, startBars, bars, tempoBpm = 120, label = id) => ({
  id, kind, leader, label, tempoBpm, startMs: startBars * BAR, endMs: (startBars + bars) * BAR,
});
const segments = [
  part('INTRO', 'INTRO', 'PIANO', 0, 4),
  part('HEAD', 'HEAD', 'SAX', 4, 10),
  part('SOLO-SAX', 'SOLO', 'SAX', 14, 20), // two choruses
  part('SOLO-DRUMS', 'SOLO', 'DRUMS', 34, 10),
  part('ENDING', 'ENDING', 'DRUMS', 44, 1),
];
const at = (bars) => locate(segments, song1, bars * BAR);

test('bars map to sections and to the bar within their section', () => {
  assert.equal(barsPerChorus(song1), 10);
  assert.deepEqual(sectionOf(song1, 0), { section: 0, bar: 0 });
  assert.deepEqual(sectionOf(song1, 3), { section: 0, bar: 3 });
  assert.deepEqual(sectionOf(song1, 4), { section: 1, bar: 0 });
  assert.deepEqual(sectionOf(song1, 9), { section: 2, bar: 1 });
  assert.equal(sectionOf(song1, 10), null);
});

test('the head runs through the chart from the top', () => {
  const start = at(4);
  assert.equal(start.kind, 'HEAD');
  assert.equal(start.barInChorus, 0);
  assert.equal(start.section, 0);
  assert.equal(start.barInSection, 0);
  assert.equal(at(7).barInSection, 3);
  assert.equal(at(8).section, 1);
  assert.equal(at(12).section, 2);
  assert.equal(at(13).barInSection, 1);
});

test('the intro is the end of the chart', () => {
  // Four bars of intro play the last four bars of the form: Dm7 G7 CM7 CM7.
  const first = at(0);
  assert.equal(first.kind, 'INTRO');
  assert.equal(first.barInChorus, 6);
  assert.deepEqual([first.section, first.barInSection], [1, 2]);
  assert.deepEqual([at(2).section, at(2).barInSection], [2, 0]);
  assert.deepEqual([at(3).section, at(3).barInSection], [2, 1]);
});

test('a two chorus solo goes round the chart twice', () => {
  assert.deepEqual([at(14).chorus, at(14).chorusCount, at(14).barInChorus], [1, 2, 0]);
  assert.deepEqual([at(23).chorus, at(23).barInChorus], [1, 9]);
  assert.deepEqual([at(24).chorus, at(24).barInChorus], [2, 0]);
  assert.deepEqual([at(33).chorus, at(33).barInChorus], [2, 9]);
});

test('the ending has no place in the chart', () => {
  const ending = at(44);
  assert.equal(ending.kind, 'ENDING');
  assert.equal(ending.barInChorus, null);
  assert.equal(ending.section, null);
});

test('progress is how far through the bar the music is', () => {
  assert.equal(locate(segments, song1, 4 * BAR + BAR / 2).progress, 0.5);
  assert.equal(locate(segments, song1, 4 * BAR).progress, 0);
});

test('outside every announced part there is nothing to show', () => {
  assert.equal(locate(segments, song1, -1), null);
  assert.equal(locate(segments, song1, 45 * BAR), null);
  assert.equal(locate([], song1, 0), null);
});

test('a part keeps the tempo it was announced at', () => {
  // After a tempo change the next part is arranged at 240 bpm: a bar is 1000 ms, so 10 bars take 10 s.
  const quick = { id: 'SOLO-BASS', kind: 'SOLO', leader: 'BASS', label: 'bass solo', tempoBpm: 240, startMs: 100_000, endMs: 110_000 };
  const located = locate([...segments, quick], song1, 100_000 + 4_500);
  assert.equal(located.barInChorus, 4);
  assert.equal(located.section, 1);
  assert.equal(located.progress, 0.5);
});

test('the leader leads and the others back them up', () => {
  assert.deepEqual(roleOf('SAX', at(5)), { text: 'Playing the head', kind: 'leading' });
  assert.deepEqual(roleOf('BASS', at(5)), { text: 'Accompanying', kind: 'accompanying' });
  assert.deepEqual(roleOf('PIANO', at(0)), { text: 'Playing the intro', kind: 'leading' });
  assert.deepEqual(roleOf('SAX', at(0)), { text: 'Listening', kind: 'waiting' });
  assert.deepEqual(roleOf('SAX', at(15)), { text: 'Soloing', kind: 'leading' });
  assert.deepEqual(roleOf('PIANO', at(15)), { text: 'Accompanying', kind: 'accompanying' });
});

test('behind a drum solo the rest of the band plays softly', () => {
  assert.deepEqual(roleOf('DRUMS', at(35)), { text: 'Soloing', kind: 'leading' });
  for (const instrument of ['SAX', 'PIANO', 'BASS']) {
    assert.deepEqual(roleOf(instrument, at(35)), { text: 'Playing softly', kind: 'soft' });
  }
});

test('everyone plays the ending, and nobody has a role when nothing is playing', () => {
  for (const instrument of ['SAX', 'PIANO', 'BASS', 'DRUMS']) {
    assert.deepEqual(roleOf(instrument, at(44)), { text: 'Final chord', kind: 'ending' });
    assert.deepEqual(roleOf(instrument, null), { text: 'Waiting', kind: 'waiting' });
  }
});

test('the heading says which part and which chorus, like a lead sheet', () => {
  assert.equal(positionTitle(at(5), song1, 'Sax'), 'Head · First chorus (AAB)');
  assert.equal(positionTitle(at(25), song1, 'Sax'), 'Sax solo · Second chorus (AAB)');
  assert.equal(positionTitle(at(35), song1, 'Drums'), 'Drums solo · First chorus (AAB)');
  assert.equal(positionTitle(at(0), song1, ''), 'Intro · the last bars of the tune');
  assert.equal(positionTitle(at(44), song1, 'Drums'), 'Ending · CM7');
  assert.equal(positionTitle(null, song1, ''), '');
});

test('the current chord is named with its section and bar', () => {
  assert.equal(whereText(at(6), song1), 'Section A · bar 3 · Dm7');
  assert.equal(whereText(at(13), song1), 'Section B · bar 2 · CM7');
  assert.equal(whereText(at(44), song1), '');
  assert.equal(whereText(null, song1), '');
});
