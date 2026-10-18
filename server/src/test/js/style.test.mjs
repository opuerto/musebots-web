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

const DARK = ':root,\n:root[data-theme="dark"]';
const LIGHT = ':root[data-theme="light"]';
const dark = declared(block(DARK));
const light = declared(block(LIGHT));

test('dark is the default and light defines every colour dark does', () => {
  const colours = (set) => [...set].filter((name) => !/^--(font|text|space|radius|ease|measure)/.test(name)).sort();
  assert.deepEqual(colours(light), colours(dark), 'the two themes should define the same tokens');
  for (const token of ['--bg', '--surface', '--ink', '--muted', '--line', '--primary', '--primary-ink', '--focus', '--shadow', '--chevron']) {
    assert.ok(dark.has(token), `${token} is defined for the default theme`);
  }
  assert.match(block(DARK), /color-scheme:\s*dark/);
  assert.match(block(LIGHT), /color-scheme:\s*light/);
});

test('the system theme is not followed: dark for everyone until light is chosen', () => {
  assert.ok(!css.includes('prefers-color-scheme'), 'no rule may switch to light for a light system');
  assert.match(html, /<meta name="color-scheme" content="dark light">/);
  assert.match(html, /<meta name="theme-color" content="#0b0e14">/);
});

test('every token the page uses is defined, or set on the element that uses it', () => {
  const root = new Set([...dark, ...declared(block(':root {'))]);
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
  for (const [theme, text] of [['light', block(LIGHT)], ['dark', block(DARK)]]) {
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
  assert.match(html, /<button id="themeToggle"[^>]*aria-pressed="true"[^>]*aria-label="Switch to the light theme"/, 'it starts as dark, so it offers light');
  assert.match(html, /<p id="status"[^>]*role="status"[^>]*aria-live="polite"/);
  assert.match(html, /<script src="theme-init\.js"><\/script>[\s\S]*<link rel="stylesheet" href="style\.css">/, 'the theme is chosen before the styles paint');
});
