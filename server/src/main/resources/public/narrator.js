// Says in plain words what the band is doing, the way a talk through the tune would.
import { capital, lower } from './notes.js';

/**
 * @param located  what timeline.locate returned
 * @param previous the part that came before this one, if any (to say who handed over the lead)
 */
export function caption(located, previous) {
  if (!located) return '';
  const leader = located.leader;
  switch (located.kind) {
    case 'INTRO':
      return `The ${lower(leader)} won the call for the intro and plays the last bars of the tune on its own. `
        + 'The leader waits for the intro to end before starting the head.';
    case 'HEAD':
      return `The leader (${lower(leader)}) plays the head, the melody of the tune. Each accompanist asks its `
        + 'synchronizer when the head starts, then plays along.';
    case 'SOLO': {
      const handover = previous && previous.leader && previous.leader !== leader
        ? `The ${lower(previous.leader)} hands the lead to the ${lower(leader)}. ` : '';
      const chorus = located.chorusCount > 1 ? `, chorus ${located.chorus} of ${located.chorusCount}` : '';
      const behind = leader === 'DRUMS' ? ' Behind the drums the band plays softly so the fills can be heard.' : '';
      const solos = leader === 'DRUMS' ? 'solo' : 'solos'; // "the drums solo", "the sax solos"
      return `${handover}The ${lower(leader)} ${solos}${chorus} while the others accompany.${behind}`;
    }
    case 'ENDING':
      return 'The leader asks everyone to finish together: one last chord.';
    default:
      return '';
  }
}

export const leaderLabel = (located) => (located && located.leader ? capital(located.leader) : '');
