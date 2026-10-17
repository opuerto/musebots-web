// A small fake page, enough for the app's modules: elements with classes, styles, children and listeners, and a
// `document` that knows the ids declared in the real index.html (with their initial disabled/hidden state).
import { readFileSync } from 'node:fs';

export class FakeElement {
  constructor(tag = 'div', id = null) {
    this.tag = tag;
    this.id = id;
    this.className = '';
    this.dataset = {};
    this.attributes = {};
    this.children = [];
    this.listeners = {};
    this.properties = {};
    this.textContent = '';
    this.disabled = false;
    this.hidden = false;
    this.value = '';
    const properties = this.properties;
    this.style = { setProperty: (name, value) => { properties[name] = value; } };
    const element = this;
    this.classList = {
      contains: (name) => element.className.split(' ').includes(name),
      add: (...names) => names.forEach((name) => { if (!element.classList.contains(name)) element.className = `${element.className} ${name}`.trim(); }),
      remove: (...names) => { element.className = element.className.split(' ').filter((c) => c && !names.includes(c)).join(' '); },
      toggle: (name, force) => {
        const on = force === undefined ? !element.classList.contains(name) : force;
        if (on) element.classList.add(name); else element.classList.remove(name);
        return on;
      },
    };
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  addEventListener(type, listener) { (this.listeners[type] ??= []).push(listener); }
  async fire(type, event = {}) { for (const listener of this.listeners[type] ?? []) await listener({ target: this, ...event }); }
  append(...nodes) { this.children.push(...nodes); }
  appendChild(node) { this.children.push(node); }
  replaceChildren(...nodes) { this.children = nodes; }
}

/** Every element under `root` (including it) for which `test` is true. */
export function findAll(root, test) {
  const found = [];
  const visit = (node) => {
    if (test(node)) found.push(node);
    node.children.forEach(visit);
  };
  visit(root);
  return found;
}

export const withClass = (name) => (node) => node.classList.contains(name);

/** Installs `document`, taking the ids and their initial state from the real page. */
export function installDocument() {
  const elements = {};
  const html = readFileSync(new URL('../../main/resources/public/index.html', import.meta.url), 'utf8');
  for (const [, tag, attributes] of html.matchAll(/<(\w+)([^>]*\bid="[^"]+"[^>]*)>/g)) {
    const id = /\bid="([^"]+)"/.exec(attributes)[1];
    const element = new FakeElement(tag, id);
    element.disabled = /\sdisabled\b/.test(attributes);
    element.hidden = /\shidden\b/.test(attributes);
    const value = /\bvalue="([^"]*)"/.exec(attributes);
    if (value) element.value = value[1];
    elements[id] = element;
  }
  // The page starts with the first option of each menu selected.
  elements.song.value = 'blues';
  elements.ensemble.value = 'quartet';
  globalThis.document = {
    getElementById: (id) => elements[id],
    createElement: (tag) => new FakeElement(tag),
  };
  return elements;
}
