// Light and dark themes. The page is dark for everyone; the toggle switches to light and remembers the choice.
export const THEME_KEY = 'musebots-theme';
export const DEFAULT_THEME = 'dark';

/** The theme to show: a stored choice of light or dark, otherwise the default (dark). */
export function resolveTheme(stored) {
  return stored === 'light' || stored === 'dark' ? stored : DEFAULT_THEME;
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
 * @param meta     the theme-color meta element, so the browser's own bars follow the theme; may be null
 */
export function initTheme({
  button,
  root = document.documentElement,
  storage = browserStorage(),
  meta = globalThis.document?.querySelector?.('meta[name="theme-color"]') ?? null,
} = {}) {
  const read = () => { try { return storage?.getItem(THEME_KEY) ?? null; } catch (error) { return null; } };
  const write = (theme) => { try { storage?.setItem(THEME_KEY, theme); } catch (error) { /* a choice that is not remembered still applies */ } };

  let current = resolveTheme(read());

  function apply(theme) {
    current = theme;
    root.dataset.theme = theme;
    meta?.setAttribute('content', theme === 'dark' ? '#0b0e14' : '#f5f4f0');
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

  return { current: () => current, set(theme) { write(theme); apply(theme); } };
}
