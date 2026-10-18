import assert from 'node:assert/strict';
import test from 'node:test';
import { FakeElement } from './fake-dom.mjs';
import { DEFAULT_THEME, initTheme, resolveTheme, THEME_KEY } from '../../main/resources/public/theme.js';

const memory = (initial = {}) => {
  const values = { ...initial };
  return { values, getItem: (key) => values[key] ?? null, setItem: (key, value) => { values[key] = value; } };
};
const setup = ({ stored, meta = null } = {}) => {
  const root = new FakeElement('html');
  const button = new FakeElement('button');
  const storage = memory(stored ? { [THEME_KEY]: stored } : {});
  const theme = initTheme({ button, root, storage, meta });
  return { root, button, storage, theme };
};

test('the page is dark unless the visitor has chosen light', () => {
  assert.equal(DEFAULT_THEME, 'dark');
  assert.equal(resolveTheme(null), 'dark');
  assert.equal(resolveTheme('light'), 'light');
  assert.equal(resolveTheme('dark'), 'dark');
  assert.equal(resolveTheme('sepia'), 'dark', 'an unknown value is ignored');
});

test('with nothing stored the page is dark, whatever the system prefers', () => {
  const { root, button } = setup();
  assert.equal(root.dataset.theme, 'dark');
  assert.equal(button.attributes['aria-pressed'], 'true');
  assert.equal(button.attributes['aria-label'], 'Switch to the light theme');
  assert.equal(button.attributes.title, 'Switch to the light theme');
});

test('a stored choice of light is applied, and the button says what pressing it will do', () => {
  const { root, button } = setup({ stored: 'light' });
  assert.equal(root.dataset.theme, 'light');
  assert.equal(button.attributes['aria-pressed'], 'false');
  assert.equal(button.attributes['aria-label'], 'Switch to the dark theme');
});

test('the button switches theme and remembers the choice', async () => {
  const { root, button, storage } = setup();
  await button.fire('click');
  assert.equal(root.dataset.theme, 'light');
  assert.equal(storage.values[THEME_KEY], 'light');
  assert.equal(button.attributes['aria-pressed'], 'false');
  await button.fire('click');
  assert.equal(root.dataset.theme, 'dark');
  assert.equal(storage.values[THEME_KEY], 'dark');
  assert.equal(button.attributes['aria-pressed'], 'true');
});

test('the browser bars follow the theme', async () => {
  const meta = new FakeElement('meta');
  const { button } = setup({ meta });
  assert.equal(meta.attributes.content, '#0b0e14');
  await button.fire('click');
  assert.equal(meta.attributes.content, '#f5f4f0');
});

test('blocked storage does not stop the toggle from working', async () => {
  const root = new FakeElement('html');
  const button = new FakeElement('button');
  const blocked = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); } };
  initTheme({ button, root, storage: blocked });
  assert.equal(root.dataset.theme, 'dark');
  await button.fire('click');
  assert.equal(root.dataset.theme, 'light');
});

test('it works without a toggle button', () => {
  const root = new FakeElement('html');
  const theme = initTheme({ root, storage: memory() });
  assert.equal(theme.current(), 'dark');
  theme.set('light');
  assert.equal(root.dataset.theme, 'light');
});
