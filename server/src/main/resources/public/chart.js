// The chord chart: one row per section, one cell per bar. The row being played is highlighted, and so is the bar,
// which also fills as the bar goes by.

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

export function buildChart(root, song) {
  root.replaceChildren();
  const widest = Math.max(...song.sections.map((section) => section.bars.length));
  root.style.setProperty('--columns', String(widest));

  const rows = song.sections.map((section) => {
    const row = element('div', 'section-row');
    const badge = element('span', 'badge', section.name);
    const barsBox = element('div', 'bars');
    const cells = section.bars.map((chord) => {
      const cell = element('div', 'bar');
      cell.append(element('i', 'fill'), element('span', 'chord', chord));
      return cell;
    });
    barsBox.append(...cells);
    row.append(badge, barsBox);
    root.append(row);
    return { row, cells };
  });

  return {
    /** Highlights where the band is; null clears the highlight. */
    update(located) {
      rows.forEach(({ row, cells }, section) => {
        const active = located !== null && located.section === section;
        row.classList.toggle('active', active);
        cells.forEach((cell, bar) => {
          const current = active && located.barInSection === bar;
          cell.classList.toggle('current', current);
          cell.style.setProperty('--progress', current ? located.progress.toFixed(3) : '0');
        });
      });
    },
  };
}
