// The band on stage: a card per instrument with its picture, what it is doing, the notes it is playing, and its
// volume and mute.
import { INSTRUMENTS, ORDER } from './notes.js';

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

export function buildStage(root, { onMute, onVolume }) {
  const cards = {};
  root.replaceChildren();

  for (const instrument of ORDER) {
    const info = INSTRUMENTS[instrument];
    const card = element('article', 'card');
    card.dataset.instrument = instrument;
    card.style.setProperty('--accent', info.color);

    const art = element('div', 'art');
    const picture = element('img');
    picture.src = info.asset;
    picture.alt = info.label;
    art.append(picture);

    const role = element('p', 'role waiting', 'Waiting');
    const readout = element('p', 'readout', ' ');
    const level = element('div', 'level');
    const levelFill = element('i');
    level.append(levelFill);

    const mute = element('button', 'mute', 'Mute');
    mute.setAttribute('aria-pressed', 'false');
    const volume = element('input', 'volume');
    volume.type = 'range';
    volume.min = '0';
    volume.max = '150';
    volume.value = '100';
    volume.setAttribute('aria-label', `${info.label} volume`);
    const volumeOut = element('output', 'volume-out', '100%');
    const mix = element('div', 'mix');
    mix.append(mute, volume, volumeOut);

    card.append(art, element('h3', 'name', info.label), role, readout, level, mix);
    root.append(card);

    const state = { muted: false, card, role, readout, levelFill, mute, volume, volumeOut };
    cards[instrument] = state;

    mute.addEventListener('click', () => {
      state.muted = !state.muted;
      mute.setAttribute('aria-pressed', String(state.muted));
      mute.textContent = state.muted ? 'Unmute' : 'Mute';
      card.classList.toggle('muted', state.muted);
      onMute(instrument, state.muted);
    });
    volume.addEventListener('input', () => { volumeOut.textContent = `${volume.value}%`; });
    volume.addEventListener('change', () => onVolume(instrument, Number(volume.value) / 100));
  }

  return {
    /** Dims the musicians who are not in this band. */
    setBand(instruments) {
      for (const [instrument, state] of Object.entries(cards)) {
        state.card.classList.toggle('absent', !instruments.includes(instrument));
      }
    },

    /** The mix controls only mean something while a performance is running. */
    setPerforming(performing) {
      for (const state of Object.values(cards)) {
        state.mute.disabled = !performing;
        state.volume.disabled = !performing;
      }
    },

    /** Back to full volume, unmuted, ready for a new performance. */
    reset() {
      for (const state of Object.values(cards)) {
        state.muted = false;
        state.card.classList.remove('muted', 'playing');
        state.mute.setAttribute('aria-pressed', 'false');
        state.mute.textContent = 'Mute';
        state.volume.value = '100';
        state.volumeOut.textContent = '100%';
      }
    },

    /**
     * @param states per instrument: { role: {text, kind}, notes: string, level: 0 to 1 }
     */
    update(states) {
      for (const [instrument, state] of Object.entries(cards)) {
        const now = states[instrument] ?? { role: { text: 'Waiting', kind: 'waiting' }, notes: '', level: 0 };
        const role = state.muted ? { text: 'Sitting out', kind: 'out' } : now.role;
        const playing = !state.muted && now.level > 0;
        state.role.textContent = role.text;
        state.role.className = `role ${role.kind}`;
        state.readout.textContent = playing && now.notes ? now.notes : ' ';
        state.card.classList.toggle('playing', playing);
        state.card.style.setProperty('--level', playing ? now.level.toFixed(2) : '0');
        state.levelFill.style.setProperty('width', `${Math.round((playing ? now.level : 0) * 100)}%`);
      }
    },
  };
}
