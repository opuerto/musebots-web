import assert from 'node:assert/strict';
import test from 'node:test';
import { reactionTo, who } from '../../main/resources/public/reactions.js';

const talk = (from, to, summary, performative = 'REQUEST') => ({ from: `s1-${from}`, to: to.map((t) => `s1-${t}`), performative, summary });

test('names the instrument and part behind an agent name', () => {
  assert.deepEqual(who('s1a2b3-piano-musician'), { instrument: 'PIANO', part: 'musician' });
  assert.deepEqual(who('s1a2b3-bass-composer'), { instrument: 'BASS', part: 'composer' });
  assert.equal(who('ams'), null);
  assert.equal(who('s1-banjo-musician'), null);
});

test('the leader asking for an intro makes the leader speak and the others listen', () => {
  const reaction = reactionTo(talk('sax-musician', ['piano-musician', 'bass-musician', 'drums-musician'], 'who wants to play the intro? (attempt 1)', 'CFP'));
  assert.equal(reaction.speaker, 'SAX');
  assert.equal(reaction.text, 'Who will play the intro?');
  assert.deepEqual(reaction.listeners, ['PIANO', 'BASS', 'DRUMS']);
});

test('handing over the lead and asking for backing are said in the robot\'s own words', () => {
  assert.equal(reactionTo(talk('sax-musician', ['bass-musician'], 'your turn, bass: the lead is yours for SOLO-BASS')).text, 'Your turn!');
  assert.equal(reactionTo(talk('sax-musician', ['bass-musician'], 'back me up in SOLO-SAX')).text, 'Back me up!');
  assert.equal(reactionTo(talk('bass-musician', ['sax-musician'], 'I have the lead for SOLO-BASS', 'AGREE')).text, 'I have the lead.');
  assert.equal(reactionTo(talk('piano-musician', ['sax-musician'], 'I can play a 2-bar intro', 'PROPOSE')).text, 'I will play the intro!');
});

test('what a robot does not know how to say, it says as it is', () => {
  assert.equal(reactionTo(talk('piano-musician', ['sax-musician'], 'something new')).text, 'something new');
});

test('shop talk between a musician and its own composer or synchronizer stays silent', () => {
  assert.equal(reactionTo(talk('piano-composer', ['piano-musician'], 'here are the notes')), null);
  assert.equal(reactionTo(talk('piano-synchronizer', ['bass-synchronizer'], 'where is the solo?')), null);
});

test('only musicians listen, and nobody listens to themselves', () => {
  const reaction = reactionTo(talk('piano-musician', ['piano-musician', 'bass-synchronizer', 'bass-musician', 'bass-musician'], 'back me up in HEAD'));
  assert.deepEqual(reaction.listeners, ['BASS']);
});
