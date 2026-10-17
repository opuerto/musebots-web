// Where the band is in the tune. Pure functions of the song, the announced parts and the time, so they can be tested
// without a browser.

/** Which section (and which bar of it) a bar of the chorus falls in. */
export function sectionOf(song, barInChorus) {
  let first = 0;
  for (let section = 0; section < song.sections.length; section += 1) {
    const length = song.sections[section].bars.length;
    if (barInChorus < first + length) return { section, bar: barInChorus - first };
    first += length;
  }
  return null;
}

export const barsPerChorus = (song) => song.sections.reduce((sum, section) => sum + section.bars.length, 0);

/**
 * What is happening at `nowMs`, or null if no announced part covers it.
 *
 * The intro is the last bars of the form, so it points at the end of the chart; the head and the solos run through
 * the chart from the top, once per chorus; the ending has no place in the chart.
 */
export function locate(segments, song, nowMs) {
  const segment = segments.find((part) => nowMs >= part.startMs && nowMs < part.endMs);
  if (!segment) return null;

  const total = barsPerChorus(song);
  const barMs = (60000 / segment.tempoBpm) * song.beatsPerBar;
  const elapsed = (nowMs - segment.startMs) / barMs;
  const wholeBars = Math.floor(elapsed);
  const bars = Math.max(1, Math.round((segment.endMs - segment.startMs) / barMs));

  let barInChorus = null;
  let chorus = 1;
  let chorusCount = 1;
  if (segment.kind === 'INTRO') {
    barInChorus = total - bars + wholeBars;
  } else if (segment.kind === 'HEAD' || segment.kind === 'SOLO') {
    barInChorus = wholeBars % total;
    chorus = Math.floor(wholeBars / total) + 1;
    chorusCount = Math.max(1, Math.round(bars / total));
  }

  const where = barInChorus === null ? null : sectionOf(song, barInChorus);
  return {
    segment,
    kind: segment.kind,
    leader: segment.leader,
    label: segment.label,
    chorus,
    chorusCount,
    barInChorus,
    section: where ? where.section : null,
    barInSection: where ? where.bar : null,
    progress: elapsed - wholeBars,
  };
}

/** What a musician is doing right now: a label for the card and a kind for its colour. */
export function roleOf(instrument, located) {
  if (!located) return { text: 'Waiting', kind: 'waiting' };
  switch (located.kind) {
    case 'INTRO':
      return instrument === located.leader ? { text: 'Playing the intro', kind: 'leading' } : { text: 'Listening', kind: 'waiting' };
    case 'HEAD':
      return instrument === located.leader ? { text: 'Playing the head', kind: 'leading' } : { text: 'Accompanying', kind: 'accompanying' };
    case 'SOLO':
      if (instrument === located.leader) return { text: 'Soloing', kind: 'leading' };
      return located.leader === 'DRUMS' ? { text: 'Playing softly', kind: 'soft' } : { text: 'Accompanying', kind: 'accompanying' };
    case 'ENDING':
      return { text: 'Final chord', kind: 'ending' };
    default:
      return { text: 'Waiting', kind: 'waiting' };
  }
}

const ORDINALS = ['First', 'Second', 'Third', 'Fourth', 'Fifth', 'Sixth'];

/** The heading over the chart, e.g. "Piano solo · First chorus (AAB)". */
export function positionTitle(located, song, capitalLeader) {
  if (!located) return '';
  switch (located.kind) {
    case 'INTRO': return 'Intro · the last bars of the tune';
    case 'HEAD': return `Head · ${ORDINALS[located.chorus - 1] ?? located.chorus} chorus (${song.form})`;
    case 'SOLO': return `${capitalLeader} solo · ${ORDINALS[located.chorus - 1] ?? located.chorus} chorus (${song.form})`;
    case 'ENDING': return `Ending · ${song.ending}`;
    default: return '';
  }
}

/** The chord and section being played, e.g. "Section A · bar 3 · Dm7". */
export function whereText(located, song) {
  if (!located || located.section === null) return '';
  const section = song.sections[located.section];
  return `Section ${section.name} · bar ${located.barInSection + 1} · ${section.bars[located.barInSection]}`;
}
