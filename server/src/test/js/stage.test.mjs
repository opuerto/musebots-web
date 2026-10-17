import assert from 'node:assert/strict';
import test from 'node:test';
import { findAll, installDocument, withClass } from './fake-dom.mjs';

const elements = installDocument();
const { buildStage } = await import('../../main/resources/public/stage.js');

const heard = [];
const stage = buildStage(elements.stage, {
  onMute: (instrument, muted) => heard.push({ mute: instrument, muted }),
  onVolume: (instrument, gain) => heard.push({ volume: instrument, gain }),
});
const cards = () => elements.stage.children;
const card = (instrument) => cards().find((c) => c.dataset.instrument === instrument);
const part = (instrument, className) => findAll(card(instrument), withClass(className))[0];
const playing = { role: { text: 'Soloing', kind: 'leading' }, notes: 'A4 · C5', level: 0.8 };

test('a card for each musician, in stage order, each with its picture', () => {
  assert.deepEqual(cards().map((c) => c.dataset.instrument), ['SAX', 'PIANO', 'BASS', 'DRUMS']);
  const pictures = findAll(elements.stage, (node) => node.tag === 'img');
  assert.deepEqual(pictures.map((p) => p.src), ['assets/sax.svg', 'assets/piano.svg', 'assets/bass.svg', 'assets/drums.svg']);
  assert.ok(pictures.every((p) => p.alt));
});

test('each card begins waiting, with the mix controls off until there is a performance', () => {
  assert.equal(part('SAX', 'role').textContent, 'Waiting');
  stage.setPerforming(false);
  assert.ok(['SAX', 'PIANO', 'BASS', 'DRUMS'].every((i) => part(i, 'mute').disabled && part(i, 'volume').disabled));
  stage.setPerforming(true);
  assert.ok(['SAX', 'PIANO', 'BASS', 'DRUMS'].every((i) => !part(i, 'mute').disabled && !part(i, 'volume').disabled));
});

test('shows what a musician is doing and the notes sounding now', () => {
  stage.update({ SAX: playing });
  assert.equal(part('SAX', 'role').textContent, 'Soloing');
  assert.equal(part('SAX', 'role').className, 'role leading');
  assert.equal(part('SAX', 'readout').textContent, 'A4 · C5');
  assert.ok(card('SAX').classList.contains('playing'));
  assert.equal(card('SAX').properties['--level'], '0.80');
  assert.equal(findAll(card('SAX'), (n) => n.tag === 'i')[0].properties.width, '80%');
});

test('a musician with nothing sounding is quiet, and one left out of the update is waiting', () => {
  assert.ok(!card('PIANO').classList.contains('playing'));
  assert.equal(part('PIANO', 'role').textContent, 'Waiting');
  stage.update({ SAX: { role: { text: 'Accompanying', kind: 'accompanying' }, notes: '', level: 0 } });
  assert.ok(!card('SAX').classList.contains('playing'));
  assert.equal(card('SAX').properties['--level'], '0');
  assert.equal(part('SAX', 'role').className, 'role accompanying');
});

test('muting tells the conductor, and the card says the musician is sitting out', async () => {
  heard.length = 0;
  await part('BASS', 'mute').fire('click');
  assert.deepEqual(heard, [{ mute: 'BASS', muted: true }]);
  assert.equal(part('BASS', 'mute').textContent, 'Unmute');
  assert.equal(part('BASS', 'mute').attributes['aria-pressed'], 'true');
  assert.ok(card('BASS').classList.contains('muted'));

  stage.update({ BASS: { role: { text: 'Accompanying', kind: 'accompanying' }, notes: 'F2', level: 0.5 } });
  assert.equal(part('BASS', 'role').textContent, 'Sitting out');
  assert.ok(!card('BASS').classList.contains('playing'), 'a muted musician does not light up');
  assert.equal(part('BASS', 'readout').textContent, ' ');

  await part('BASS', 'mute').fire('click');
  assert.deepEqual(heard.at(-1), { mute: 'BASS', muted: false });
  assert.equal(part('BASS', 'mute').textContent, 'Mute');
});

test('the volume slider shows its value as it moves and tells the conductor when released', async () => {
  heard.length = 0;
  const slider = part('PIANO', 'volume');
  slider.value = '60';
  await slider.fire('input');
  assert.equal(part('PIANO', 'volume-out').textContent, '60%');
  assert.deepEqual(heard, [], 'nothing is sent while dragging');
  await slider.fire('change');
  assert.deepEqual(heard, [{ volume: 'PIANO', gain: 0.6 }]);
});

test('musicians who are not in the band are dimmed', () => {
  stage.setBand(['PIANO', 'BASS']);
  assert.deepEqual(cards().map((c) => c.classList.contains('absent')), [true, false, false, true]);
  stage.setBand(['SAX', 'PIANO', 'BASS', 'DRUMS']);
  assert.ok(cards().every((c) => !c.classList.contains('absent')));
});

test('reset puts every card back to full volume, unmuted', async () => {
  await part('DRUMS', 'mute').fire('click');
  part('DRUMS', 'volume').value = '20';
  stage.reset();
  assert.ok(cards().every((c) => !c.classList.contains('muted') && !c.classList.contains('playing')));
  assert.equal(part('DRUMS', 'mute').textContent, 'Mute');
  assert.equal(part('DRUMS', 'volume').value, '100');
  assert.equal(part('DRUMS', 'volume-out').textContent, '100%');
});
