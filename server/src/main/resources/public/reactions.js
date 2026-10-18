// What the robots say and who listens, worked out from the messages the agents send each other. Only the musicians
// themselves speak aloud (a composer or a synchronizer talking to its own musician is shop talk); a robot that is
// addressed turns to listen.
const INSTRUMENTS = ['SAX', 'PIANO', 'BASS', 'DRUMS'];

/** "s1a2b3c4d5e6-piano-musician" → { instrument: 'PIANO', part: 'musician' }, or null if it is not one of ours. */
export function who(agentName) {
  const [, instrument, part] = String(agentName).split('-');
  const upper = instrument?.toUpperCase();
  return INSTRUMENTS.includes(upper) ? { instrument: upper, part: part ?? 'musician' } : null;
}

const SAYINGS = [
  [/^who wants to play the intro/, 'Who will play the intro?'],
  [/^I can play a/, 'I will play the intro!'],
  [/^you play the intro/, 'The intro is yours.'],
  [/^thanks, someone else/, 'Thanks, next time.'],
  [/^the intro will start/, 'Intro ready!'],
  [/^the song is/, 'Here is the tune.'],
  [/^got the structure/, 'Got the tune.'],
  [/^your turn/, 'Your turn!'],
  [/^I have the lead/, 'I have the lead.'],
  [/^back me up/, 'Back me up!'],
  [/^playing softly/, 'Softly…'],
  [/^playing along/, 'Playing along.'],
  [/^I don't know the song/, 'What is the song?'],
];

/**
 * @param message a conversation message from the server: { from, to: [], performative, summary }
 * @returns {{ speaker: string, text: string, listeners: string[] } | null} null when no robot should react
 */
export function reactionTo(message) {
  const from = who(message.from);
  if (!from || from.part !== 'musician') return null;
  const summary = String(message.summary ?? '');
  const saying = SAYINGS.find(([pattern]) => pattern.test(summary));
  const listeners = [...new Set((message.to ?? [])
    .map(who).filter((to) => to && to.part === 'musician' && to.instrument !== from.instrument).map((to) => to.instrument))];
  return { speaker: from.instrument, text: saying ? saying[1] : summary, listeners };
}
