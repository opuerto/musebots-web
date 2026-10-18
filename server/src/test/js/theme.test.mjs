import assert from 'node:assert/strict';
import test from 'node:test';
import { FakeElement } from './fake-dom.mjs';
import { initTheme, resolveTheme, THEME_KEY } from '../../main/resources/public/theme.js';

const memory = (initial = {}) => {
  const values = { ...initial };
  return { values, getItem: (key) => values[key] ?? null, setItem: (key, value) => { values[key] = value; } };
};
const mediaQuery = (matches) => {
  const listeners = [];
  return { matches, addEventListener: (type, listener) => listeners.push(listener), change(now) { listeners.forEach((l) => l({ matches: now })); } };
};
const setup = ({ stored, dark = false } = {}) => {
  const root = new FakeElement('html');
  const button = new FakeElement('button');
  const storage = memory(stored ? { [THEME_KEY]: stored } : {});
  const media = mediaQuery(dark);
  const theme = initTheme({ button, root, storage, media });
  return { root, button, storage, media, theme };
};

test('a stored choice wins over the system, and the system decides when nothing is stored', () => {
  assert.equal(resolveTheme('dark', false), 'dark');
  assert.equal(resolveTheme('light', true), 'light');
  assert.equal(resolveTheme(null, true), 'dark');
  assert.equal(resolveTheme(null, false), 'light');
  assert.equal(resolveTheme('sepia', true), 'dark', 'an unknown value is ignored');
});

test('with nothing stored the page follows the system', () => {
  assert.equal(setup({ dark: true }).root.dataset.theme, 'dark');
  assert.equal(setup({ dark: false }).root.dataset.theme, 'light');
});

test('a stored choice is applied, and the button says what pressing it will do', () => {
  const { root, button } = setup({ stored: 'dark' });
  assert.equal(root.dataset.theme, 'dark');
  assert.equal(button.attributes['aria-pressed'], 'true');
  assert.equal(button.attributes['aria-label'], 'Switch to the light theme');
  assert.equal(button.attributes.title, 'Switch to the light theme');
});

test('the button switches theme and remembers the choice', async () => {
  const { root, button, storage } = setup({ dark: false });
  assert.equal(button.attributes['aria-label'], 'Switch to the dark theme');
  await button.fire('click');
  assert.equal(root.dataset.theme, 'dark');
  assert.equal(storage.values[THEME_KEY], 'dark');
  assert.equal(button.attributes['aria-pressed'], 'true');
  await button.fire('click');
  assert.equal(root.dataset.theme, 'light');
  assert.equal(storage.values[THEME_KEY], 'light');
  assert.equal(button.attributes['aria-pressed'], 'false');
});

test('a change of the system theme is followed only until the visitor chooses', async () => {
  const { root, button, media } = setup({ dark: false });
  media.change(true);
  assert.equal(root.dataset.theme, 'dark', 'followed, as nothing was chosen');
  await button.fire('click'); // the visitor chooses light
  assert.equal(root.dataset.theme, 'light');
  media.change(true);
  assert.equal(root.dataset.theme, 'light', 'a choice is not overridden by the system');
});

test('blocked storage does not stop the toggle from working', async () => {
  const root = new FakeElement('html');
  const button = new FakeElement('button');
  const blocked = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); } };
  initTheme({ button, root, storage: blocked, media: mediaQuery(false) });
  assert.equal(root.dataset.theme, 'light');
  await button.fire('click');
  assert.equal(root.dataset.theme, 'dark');
});

test('it works without a toggle button or a media query', () => {
  const root = new FakeElement('html');
  const theme = initTheme({ root, storage: memory(), media: null });
  assert.equal(theme.current(), 'light');
  theme.set('dark');
  assert.equal(root.dataset.theme, 'dark');
});
