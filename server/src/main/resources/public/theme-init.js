// Runs before the page is painted, so a visitor who chose a theme never sees the other one flash. The rest of the
// theme handling (the toggle, following the system) is in theme.js.
(function () {
  try {
    var stored = localStorage.getItem('musebots-theme');
    if (stored === 'light' || stored === 'dark') document.documentElement.dataset.theme = stored;
  } catch (error) {
    // Storage can be blocked; the page then follows the system setting.
  }
})();
