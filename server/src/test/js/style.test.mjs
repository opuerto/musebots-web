// The stylesheet is checked as text: nothing is rendered here, but a missing token or a theme that lacks one would be a
// visible bug (a transparent panel, black text on black) that no other test would notice.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const css = readFileSync(new URL('../../main/resources/public/style.css', import.meta.url), 'utf8');
const html = readFileSync(new URL('../../main/resources/public/index.html', import.meta.url), 'utf8');

/** The declarations of the first rule whose selector starts with this text. */
function block(selector) {
  const start = css.indexOf(selector);
  assert.ok(start >= 0, `no rule for ${selector}`);
  const open = css.indexOf('{', start);
  let depth = 1;
  let end = open + 1;
  while (depth > 0) {
    if (css[end] === '{') depth++;
    if (css[end] === '}') depth--;
    end++;
  }
  return css.slice(open + 1, end - 1);
}
const declared = (text) => new Set([...text.matchAll(/(--[\w-]+)\s*:/g)].map((match) => match[1]));

const light = declared(block(':root,\n:root[data-theme="light"]'));
const dark = declared(block(':root[data-theme="dark"]'));
const systemDark = declared(block(':root:not([data-theme])'));

test('the dark theme defines every colour the light theme does, and the system-dark fallback matches it', () => {
  const colours = (set) => [...set].filter((name) => !/^--(font|text|space|radius|ease|measure)/.test(name)).sort();
  assert.deepEqual(colours(dark), colours(light), 'the dark theme and the light theme should define the same tokens');
  assert.deepEqual(colours(systemDark), colours(dark), 'following a dark system should look the same as choosing dark');
  for (const token of ['--bg', '--surface', '--ink', '--muted', '--line', '--primary', '--primary-ink', '--focus', '--shadow', '--chevron']) {
    assert.ok(light.has(token), `${token} is defined`);
  }
});

test('the dark and system-dark themes agree on every value', () => {
  const values = (text) => Object.fromEntries([...text.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
  const explicit = values(block(':root[data-theme="dark"]'));
  const fallback = values(block(':root:not([data-theme])'));
  for (const [name, value] of Object.entries(explicit)) {
    assert.equal(fallback[name], value, `${name} should be the same whichever way dark was chosen`);
  }
});

test('every token the page uses is defined, or set on the element that uses it', () => {
  const root = new Set([...light, ...declared(block(':root {'))]);
  const perElement = new Set(['--accent', '--level', '--progress', '--columns', '--perf']);
  const used = [...css.matchAll(/var\((--[\w-]+)/g)].map((match) => match[1]);
  const missing = [...new Set(used)].filter((name) => !root.has(name) && !perElement.has(name));
  assert.deepEqual(missing, [], `undefined tokens: ${missing}`);
});

test('text and surfaces keep a readable contrast in both themes', () => {
  const hex = (text, name) => /#([0-9a-f]{6})/i.exec(new RegExp(`${name}:\\s*(#[0-9a-f]{6})`, 'i').exec(text)?.[1] ?? '')?.[1];
  const luminance = (rgb) => {
    const channel = (i) => { const c = parseInt(rgb.slice(i, i + 2), 16) / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
  };
  const ratio = (a, b) => { const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x); return (hi + 0.05) / (lo + 0.05); };
  for (const [theme, text] of [['light', block(':root,\n:root[data-theme="light"]')], ['dark', block(':root[data-theme="dark"]')]]) {
    assert.ok(ratio(hex(text, '--ink'), hex(text, '--surface')) >= 7, `${theme}: body text on a panel`);
    assert.ok(ratio(hex(text, '--ink'), hex(text, '--bg')) >= 7, `${theme}: body text on the page`);
    assert.ok(ratio(hex(text, '--muted'), hex(text, '--surface')) >= 4.5, `${theme}: secondary text on a panel (WCAG AA)`);
    assert.ok(ratio(hex(text, '--primary-ink'), hex(text, '--primary')) >= 4.5, `${theme}: text on the primary button (WCAG AA)`);
    assert.ok(ratio(hex(text, '--primary'), hex(text, '--surface')) >= 3, `${theme}: the accent against a panel (links, focus)`);
  }
});

test('the page respects reduced motion, shows focus, and has a way to the content', () => {
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  assert.match(css, /:focus-visible/);
  assert.match(html, /<a class="skip" href="#main">/);
  assert.match(html, /<main id="main">/);
});

test('the page offers the toggle and announces its status politely', () => {
  assert.match(html, /<button id="themeToggle"[^>]*aria-pressed="false"[^>]*aria-label="Switch to the dark theme"/);
  assert.match(html, /<p id="status"[^>]*role="status"[^>]*aria-live="polite"/);
  assert.match(html, /<script src="theme-init\.js"><\/script>[\s\S]*<link rel="stylesheet" href="style\.css">/, 'the theme is chosen before the styles paint');
  assert.match(html, /<meta name="color-scheme" content="light dark">/);
});
