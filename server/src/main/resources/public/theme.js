// Light and dark themes. With nothing chosen the page follows the system; the toggle makes a choice and remembers it.
export const THEME_KEY = 'musebots-theme';

/** The theme to show: a stored choice wins, otherwise the system's. */
export function resolveTheme(stored, systemPrefersDark) {
  if (stored === 'light' || stored === 'dark') return stored;
  return systemPrefersDark ? 'dark' : 'light';
}

function browserStorage() {
  try {
    return globalThis.localStorage ?? null;
  } catch (error) {
    return null; // storage can be blocked
  }
}

/**
 * Puts the theme on the page and wires the toggle button.
 *
 * @param button   the toggle; it reports the state through aria-pressed and its label
 * @param root     the element that carries data-theme
 * @param storage  anything with getItem and setItem (localStorage); may be null
 * @param media    a MediaQueryList for prefers-color-scheme: dark; may be null
 */
export function initTheme({
  button,
  root = document.documentElement,
  storage = browserStorage(),
  media = globalThis.matchMedia?.('(prefers-color-scheme: dark)') ?? null,
} = {}) {
  const read = () => { try { return storage?.getItem(THEME_KEY) ?? null; } catch (error) { return null; } };
  const write = (theme) => { try { storage?.setItem(THEME_KEY, theme); } catch (error) { /* a choice that is not remembered still applies */ } };

  let current = resolveTheme(read(), media?.matches ?? false);

  function apply(theme) {
    current = theme;
    root.dataset.theme = theme;
    if (button) {
      const label = theme === 'dark' ? 'Switch to the light theme' : 'Switch to the dark theme';
      button.setAttribute('aria-pressed', String(theme === 'dark'));
      button.setAttribute('aria-label', label);
      button.setAttribute('title', label);
    }
  }

  apply(current);
  button?.addEventListener('click', () => {
    const next = current === 'dark' ? 'light' : 'dark';
    write(next);
    apply(next);
  });
  // Until the visitor chooses, a change of the system theme is followed.
  media?.addEventListener?.('change', (event) => {
    if (read() === null) apply(resolveTheme(null, event.matches));
  });

  return { current: () => current, set(theme) { write(theme); apply(theme); } };
}
