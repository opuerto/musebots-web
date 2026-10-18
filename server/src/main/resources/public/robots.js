// The band are robots, each playing its own instrument. They are drawn as inline SVG so that style.css can move their
// parts: an arm is a group that swings about its shoulder, a key lights up, a bulb on the antenna blinks when the
// robot speaks. Nothing here animates by itself; the card's classes (playing, talking, listening) decide that.
const SVG = 'http://www.w3.org/2000/svg';

function node(tag, attributes = {}, ...children) {
  const element = document.createElementNS(SVG, tag);
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value);
  element.append(...children);
  return element;
}

const group = (className, ...children) => node('g', { class: className }, ...children);
const rect = (className, x, y, width, height, radius = 0) => node('rect', { class: className, x, y, width, height, rx: radius });
const circle = (className, cx, cy, r) => node('circle', { class: className, cx, cy, r });
const ellipse = (className, cx, cy, rx, ry) => node('ellipse', { class: className, cx, cy, rx, ry });
const line = (className, x1, y1, x2, y2) => node('line', { class: className, x1, y1, x2, y2 });
const path = (className, d) => node('path', { class: className, d });

/** An arm hanging from a shoulder, ending in a hand at (handX, handY) relative to the shoulder. The group swings about the shoulder. */
function arm(side, shoulderX, shoulderY, handX, handY, ...held) {
  const dx = handX - shoulderX;
  const dy = handY - shoulderY;
  const elbowX = shoulderX + dx * 0.45 + (side === 'left' ? -6 : 6);
  const elbowY = shoulderY + dy * 0.55;
  const swing = group(`arm ${side}`,
    node('polyline', { class: 'limb', points: `${shoulderX},${shoulderY} ${elbowX},${elbowY} ${handX},${handY}` }),
    circle('joint', elbowX, elbowY, 4),
    circle('hand', handX, handY, 6.5),
    ...held);
  swing.setAttribute('style', `transform-origin: ${shoulderX}px ${shoulderY}px`);
  return swing;
}

function head() {
  return group('head',
    line('antenna', 100, 40, 100, 27),
    circle('bulb', 100, 22, 5.5),
    rect('shell', 70, 40, 60, 48, 15),
    rect('visor', 78, 49, 44, 20, 9),
    group('eyes', ellipse('eye', 91, 59, 5, 5.5), ellipse('eye', 109, 59, 5, 5.5)),
    group('mouth', line('grille', 88, 78, 112, 78), line('grille', 90, 82, 110, 82)),
    rect('ear', 62, 54, 8, 16, 3),
    rect('ear', 130, 54, 8, 16, 3));
}

function body() {
  return group('torso',
    rect('shell body', 68, 94, 64, 58, 14),
    circle('chest', 100, 112, 7),
    rect('belt', 68, 134, 64, 6, 0));
}

function legs() {
  return group('legs', rect('limb-fill', 77, 150, 15, 28, 5), rect('limb-fill', 108, 150, 15, 28, 5));
}

/** The robots' instruments, each with the robot's arms placed where they play it. */
const PLAYERS = {
  SAX() {
    const keys = group('keys',
      circle('key k1', 143, 96, 4.5), circle('key k2', 143, 111, 4.5), circle('key k3', 143, 126, 4.5));
    const sax = group('prop sax',
      path('tube', 'M128 74 C150 70 152 92 148 120 L146 150'),
      path('bell', 'M146 146 C140 176 184 176 176 142 L168 138 C170 156 158 160 156 146 Z'),
      line('mouthpiece', 124 + 2, 75, 132, 73),
      keys);
    return {
      back: [],
      front: [
        sax,
        arm('left', 74, 104, 140, 100),
        arm('right', 126, 104, 143, 124),
      ],
    };
  },

  PIANO() {
    const whites = [];
    const blacks = [];
    for (let i = 0; i < 9; i++) {
      whites.push(rect(`white w${i}`, 22 + i * 17, 144, 16, 30, 2));
      if (i % 7 !== 2 && i % 7 !== 6 && i < 8) blacks.push(rect(`black b${i}`, 22 + i * 17 + 11, 144, 12, 18, 2));
    }
    return {
      back: [],
      front: [
        group('prop piano', rect('case', 14, 134, 172, 12, 4), ...whites, ...blacks),
        arm('left', 76, 106, 62, 142),
        arm('right', 124, 106, 138, 142),
      ],
    };
  },

  BASS() {
    const strings = group('strings',
      line('string s1', 132, 52, 132, 150), line('string s2', 138, 52, 138, 150),
      line('string s3', 144, 52, 144, 150), line('string s4', 150, 52, 150, 150));
    return {
      back: [],
      front: [
        group('prop bass',
          rect('neck', 134, 40, 12, 90, 3),
          path('scroll', 'M134 40 C134 28 148 28 146 40'),
          path('belly', 'M140 92 C112 96 108 120 120 130 C106 142 112 178 142 178 C172 178 178 142 164 130 C176 120 170 96 140 92 Z'),
          circle('hole', 126, 146, 3),
          circle('hole', 156, 146, 3),
          strings),
        arm('left', 74, 104, 138, 84),
        arm('right', 126, 108, 150, 134),
      ],
    };
  },

  DRUMS() {
    const stick = (x1, y1, x2, y2) => line('stick', x1, y1, x2, y2);
    return {
      back: [
        group('prop cymbals',
          line('stand', 36, 176, 36, 112), ellipse('cymbal hat top', 36, 110, 17, 3.5), ellipse('cymbal hat bottom', 36, 116, 17, 3.5),
          line('stand', 170, 176, 170, 98), ellipse('cymbal ride', 170, 96, 24, 5)),
      ],
      front: [
        group('prop kit',
          ellipse('drum kick', 100, 168, 30, 10),
          ellipse('drum snare', 68, 150, 22, 7), rect('drum-side', 46, 150, 44, 10, 3),
          ellipse('drum tom', 134, 150, 20, 6.5), rect('drum-side', 114, 150, 40, 10, 3)),
        arm('left', 74, 104, 62, 142, stick(62, 142, 52, 128)),
        arm('right', 126, 104, 134, 138, stick(134, 138, 146, 122)),
      ],
    };
  },
};

/** A robot playing this instrument, as an SVG element. Its parts carry classes that style.css animates. */
export function buildRobot(instrument, label) {
  const kit = PLAYERS[instrument]();
  const svg = node('svg', {
    class: `robot ${instrument.toLowerCase()}`,
    viewBox: '0 0 200 200',
    role: 'img',
    'aria-label': `${label} robot`,
  },
  group('bob', ...kit.back, legs(), body(), head(), ...kit.front));
  return svg;
}
