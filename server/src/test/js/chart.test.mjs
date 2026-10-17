import assert from 'node:assert/strict';
import test from 'node:test';
import { findAll, installDocument, withClass } from './fake-dom.mjs';

const elements = installDocument();
const { buildChart } = await import('../../main/resources/public/chart.js');

const song1 = {
  title: 'Song 1', form: 'AAB', beatsPerBar: 4, ending: 'CM7',
  sections: [
    { name: 'A', bars: ['Em7', 'A7', 'Dm7', 'G7'] },
    { name: 'A', bars: ['Em7', 'A7', 'Dm7', 'G7'] },
    { name: 'B', bars: ['CM7', 'CM7'] },
  ],
};

const chart = buildChart(elements.chart, song1);
const rows = () => findAll(elements.chart, withClass('section-row'));
const cellsOf = (row) => findAll(row, withClass('bar'));
const text = (node, className) => findAll(node, withClass(className))[0].textContent;
const located = (section, barInSection, progress = 0) => ({ section, barInSection, progress });

test('draws a row per section with a cell per bar, like a lead sheet', () => {
  assert.equal(rows().length, 3);
  assert.deepEqual(rows().map((row) => text(row, 'badge')), ['A', 'A', 'B']);
  assert.deepEqual(rows().map((row) => cellsOf(row).length), [4, 4, 2]);
  assert.deepEqual(cellsOf(rows()[0]).map((cell) => text(cell, 'chord')), ['Em7', 'A7', 'Dm7', 'G7']);
  assert.deepEqual(cellsOf(rows()[2]).map((cell) => text(cell, 'chord')), ['CM7', 'CM7']);
});

test('lines the bars up in as many columns as the longest section', () => {
  assert.equal(elements.chart.properties['--columns'], '4');
});

test('nothing is highlighted until the band is somewhere in the tune', () => {
  assert.equal(findAll(elements.chart, withClass('active')).length, 0);
  assert.equal(findAll(elements.chart, withClass('current')).length, 0);
});

test('highlights the section being played and the bar within it', () => {
  chart.update(located(1, 2, 0.25));
  assert.deepEqual(rows().map((row) => row.classList.contains('active')), [false, true, false]);
  const current = findAll(elements.chart, withClass('current'));
  assert.equal(current.length, 1);
  assert.equal(text(current[0], 'chord'), 'Dm7');
  assert.equal(current[0].properties['--progress'], '0.250');
});

test('moves the highlight, and only one bar and one section are ever lit', () => {
  chart.update(located(2, 1, 0.9));
  assert.deepEqual(rows().map((row) => row.classList.contains('active')), [false, false, true]);
  assert.equal(findAll(elements.chart, withClass('current')).length, 1);
  // The bar that is no longer current has its progress reset.
  assert.equal(cellsOf(rows()[1])[2].properties['--progress'], '0');
});

test('clears the highlight when nothing is playing', () => {
  chart.update(located(0, 0, 0.5));
  chart.update(null);
  assert.equal(findAll(elements.chart, withClass('active')).length, 0);
  assert.equal(findAll(elements.chart, withClass('current')).length, 0);
});

test('building a chart for another tune replaces the first', () => {
  buildChart(elements.chart, { ...song1, sections: [{ name: 'A', bars: ['F7', 'Bb7', 'F7', 'F7'] }] });
  assert.equal(rows().length, 1);
  assert.equal(text(rows()[0], 'chord'), 'F7');
});
