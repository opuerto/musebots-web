// The About page has no band to run: only the theme toggle needs wiring.
import { initTheme } from './theme.js';

initTheme({ button: document.getElementById('themeToggle') });
