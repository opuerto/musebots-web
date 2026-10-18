// The About page is checked as text, like the header of the band page: what it says, in what order, and what it must not say.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = (name) => readFileSync(new URL(`../../main/resources/public/${name}`, import.meta.url), 'utf8');
const html = read('about.html');
const index = read('index.html');
const text = (fragment) => fragment.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
const section = (id) => new RegExp(`<section id="${id}"[^>]*>([\\s\\S]*?)</section>`).exec(html)?.[1] ?? assert.fail(`no section ${id}`);

test('the page is about Oscar and is titled for a visitor and a search engine', () => {
  assert.match(text(/<title>(.*?)<\/title>/s.exec(html)[1]), /^About Oscar Puerto/);
  assert.match(text(/<h1>(.*?)<\/h1>/s.exec(html)[1]), /^Oscar Puerto$/);
  assert.match(html, /<meta name="description" content="[^"]*Salling Group[^"]*">/);
  const structured = JSON.parse(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/.exec(html)[1]);
  assert.equal(structured['@type'], 'Person');
  assert.equal(structured.worksFor.name, 'Salling Group');
  assert.equal(structured.jobTitle, 'Senior Frontend Developer');
});

test('the hero shows Oscar\'s photo and his degree, without the specialisation', () => {
  const profile = /<section class="profile"[^>]*>([\s\S]*?)<\/section>/.exec(html)[1];
  assert.match(profile, /<img class="avatar" src="assets\/oscar\.jpg" alt="Portrait of Oscar Puerto" width="640" height="640">/);
  assert.match(text(profile), /Master in Computer Science/);
  assert.ok(!text(profile).includes('Artificial Intelligence'), 'the hero does not mention the specialisation');
  // The specialisation is still part of the story further down.
  assert.match(text(section('education')), /Specialised in Artificial Intelligence/);
});

test('the photo is a reasonably small web image of a square portrait', () => {
  const file = new URL('../../main/resources/public/assets/oscar.jpg', import.meta.url);
  const bytes = readFileSync(file);
  assert.deepEqual([...bytes.subarray(0, 3)], [0xff, 0xd8, 0xff], 'it is a JPEG');
  assert.ok(bytes.length < 200_000, `${bytes.length} bytes is small enough for a page`);
});

test('the sections are there, in the order the contents list gives them', () => {
  const contents = [...html.matchAll(/<li><a href="#(\w+)">/g)].map((m) => m[1]);
  assert.deepEqual(contents, ['summary', 'salling', 'skills', 'experience', 'education', 'research', 'outside']);
  const inPage = [...html.matchAll(/<section id="(\w+)" class="block">/g)].map((m) => m[1]);
  assert.deepEqual(inPage, contents);
});

test('the Salling Group section tells the career path in order, from developer to senior, and names the brands and the technologies', () => {
  const salling = section('salling');
  const titles = [...salling.matchAll(/<h3>([^<]+)<\/h3>/g)].map((m) => m[1]).slice(0, 4);
  assert.deepEqual(titles, ['Frontend Developer', 'Frontend Advanced Developer', 'Team Lead', 'Senior Frontend Developer']);
  assert.deepEqual([...salling.matchAll(/<span class="step-date">([^<]+)<\/span>/g)].map((m) => m[1]), ['2020', '2021', '2022', 'Now']);
  const words = text(salling);
  for (const word of ['Bilka', 'bilka.dk', 'føtex', 'foetex.dk', 'BR', 'br.dk', 'Vue', 'React', 'React Native', 'Nuxt', 'Next.js', 'Expo', 'mentoring', 'newcomers']) {
    assert.ok(words.toLowerCase().includes(word.toLowerCase()), `the Salling section should mention ${word}`);
  }
  assert.match(salling, /<li class="step current">/, 'the present role is marked as the current one');
});

test('the experience and education match the CV', () => {
  const experience = text(section('experience'));
  assert.match(experience, /Senior Frontend Developer 2020 – Today Salling Group · Aarhus, Denmark/);
  assert.match(experience, /Frontend Developer 2017 – 2019 Geysir Car Rental · Iceland/);
  assert.match(experience, /Web Master 2014 – 2015 ITB Travel \/ Hekla Travel · Iceland/);
  const education = text(section('education'));
  assert.match(education, /Master in Computer Science 2015 – 2017 Reykjavik University Specialised in Artificial Intelligence/);
  assert.match(education, /BSc in Computer Science 2006 – 2012 CEUTEC \(University of Honduras\)/);
});

test('the skills from the CV are listed', () => {
  const skills = [...section('skills').matchAll(/<li>([^<]+)<\/li>/g)].map((m) => m[1]);
  for (const skill of ['Vue', 'React', 'Next.js', 'JavaScript', 'TypeScript', 'Java', 'Sass', 'Git', 'React Native', 'Nuxt', 'Expo']) {
    assert.ok(skills.includes(skill), `${skill} is listed`);
  }
});

test('the paper is cited and linked as on the band page, and the page says what Oscar does outside work', () => {
  const research = section('research');
  assert.match(text(research), /Puerto, O\. and Thue, D\. \(2017\)/);
  assert.match(text(research), /A Model of Inter-musician Communication for Artificial Musical Intelligence/);
  assert.match(research, /href="https:\/\/computationalcreativity\.net\/iccc2017\/ICCC_17_accepted_submissions\/ICCC-17_paper_57\.pdf"/);
  const outside = text(section('outside'));
  for (const word of ['music', 'films', 'weight lifting']) assert.ok(outside.includes(word), `outside work mentions ${word}`);
});

test('nothing private from the CV is published: no phone numbers and no references', () => {
  for (const private_ of ['23415787', '+45', '825-6412', '659-1989', '+354', 'Hartøft', 'Pablo Santos', 'Islandbanki', 'phone', 'GSM']) {
    assert.ok(!html.includes(private_), `the page must not contain ${private_}`);
  }
  assert.ok(html.includes('mailto:oscar@puerto.is'), 'but the email address is there to be reached on');
});

test('links that open a new tab do not hand it this page, and every in-page link has somewhere to go', () => {
  assert.ok([...html.matchAll(/<a [^>]*target="_blank"[^>]*>/g)].every((m) => m[0].includes('rel="noopener noreferrer"')));
  const ids = new Set([...html.matchAll(/\bid="([^"]+)"/g)].map((m) => m[1]));
  for (const [, target] of html.matchAll(/href="#([^"]+)"/g)) assert.ok(ids.has(target), `#${target} exists`);
});

test('the two pages link to each other and share the theme toggle', () => {
  assert.match(index, /<a class="navlink" href="about\.html">About Oscar<\/a>/);
  assert.match(html, /<a class="navlink" href="about\.html" aria-current="page">About Oscar<\/a>/);
  assert.match(html, /<a class="btn primary" href="index\.html">/);
  assert.match(html, /<button id="themeToggle"[^>]*aria-pressed="true"/, 'dark is the default');
  assert.match(html, /<script src="theme-init\.js"><\/script>[\s\S]*<link rel="stylesheet" href="style\.css">/, 'the theme is chosen before the styles paint');
  assert.match(html, /<script type="module" src="about\.js"><\/script>/);
  assert.match(html, /<a class="skip" href="#main">/);
  assert.match(html, /<main id="main"/);
});
