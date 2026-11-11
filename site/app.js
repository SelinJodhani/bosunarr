// Bosunarr website: theme + dynamic colour, device screenshots, hero slideshow and tilt,
// feature tour, light/dark compare, latest-release lookup, sticky download dock, reveals.
// Plain ES module, no dependencies. Every feature checks its elements exist, so the same file
// also runs on privacy.html and 404.html.

const root = document.documentElement;
const mqDark = matchMedia('(prefers-color-scheme: dark)');
const mqReduce = matchMedia('(prefers-reduced-motion: reduce)');
const mqFine = matchMedia('(hover: hover) and (pointer: fine)');
const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];

const store = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { value == null ? localStorage.removeItem(key) : localStorage.setItem(key, value); } catch { /* private mode */ } },
};

/* ------------------------------------------------------------------ */
/* Screens                                                             */
/* ------------------------------------------------------------------ */

// Real screenshots (demo mode, free-licensed content) live at assets/screens/<name>-<light|dark>.webp.
// Until a file exists, a labelled placeholder shows in its place. Screens marked `illo` have no screenshot
// (demo mode has no server to show) and render a drawn illustration instead, so no image is requested.
const SCREENS = {
  dashboard: { label: 'Dashboard', icon: 'dashboard' },
  library: { label: 'Library', icon: 'video_library' },
  detail: { label: 'Series details', icon: 'tv' },
  discover: { label: 'Discover', icon: 'explore' },
  media: { label: 'Title & requests', icon: 'movie' },
  calendar: { label: 'Calendar', icon: 'calendar_month' },
  activity: { label: 'Activity', icon: 'downloading' },
  torrents: { label: 'Torrents', icon: 'download' },
  'service-sonarr': { label: 'Sonarr service screen', icon: 'dns' },
  'onboarding-welcome': { label: 'Welcome', icon: 'sailing' },
  'onboarding-pick': { label: 'Pick your services', icon: 'sailing' },
  server: { label: 'Server (illustration)', icon: 'terminal', illo: serverIllo },
};
/** A drawn, schematic Server screen (CSS-only, follows the page colours). */
function serverIllo() {
  const stats = [['CPU', '18%', 0.18], ['Memory', '6.2 GB', 0.39], ['Disk', '71%', 0.71], ['Temp', '47°C', 0.47]];
  const groups = [
    ['media', [['sonarr', 'Up 23 days'], ['radarr', 'Up 23 days'], ['qbittorrent', 'Up 4 days'], ['sabnzbd', 'Up 4 days'], ['jellyfin', 'Up 23 days']]],
    ['tools', [['prowlarr', 'Up 23 days'], ['bazarr', 'Exited', true]]],
  ];
  return `<div class="illo">
    <div class="illo__bar"><span class="illo__title">Server</span><span class="illo__pill"><i class="illo__dot"></i>Connected</span></div>
    <div class="illo__host"><span class="ms">dns</span><span><b>homelab</b><small>Ubuntu · up 23 days</small></span></div>
    <div class="illo__fp"><span class="ms">fingerprint</span>Host key verified</div>
    <div class="illo__stats">${stats.map(([k, v, f]) => `<div class="illo__stat"><small>${k}</small><b>${v}</b><i style="--v:${f}"></i></div>`).join('')}</div>
    <div class="illo__net"><span>↓ 24.6 MB/s</span><span>↑ 1.2 MB/s</span></div>
    ${groups.map(([g, items]) => `<p class="illo__group">${g}<small>${items.length} containers</small></p>
    <ul class="illo__list">${items.map(([n, st, off]) => `<li><i class="illo__dot${off ? ' illo__dot--off' : ''}"></i>${n}<span>${st}</span></li>`).join('')}</ul>`).join('')}
    <span class="illo__note">Illustration</span>
  </div>`;
}

const screenFile = (name, theme) => `${name}-${theme}.webp`;
const screenSrc = (name, theme) => `assets/screens/${screenFile(name, theme)}`;
const pageTheme = () => root.dataset.theme || (mqDark.matches ? 'dark' : 'light');

/** One screen inside a device: placeholder underneath, screenshot on top. */
function makeLayer(name, { fixedTheme = null, eager = false } = {}, layer = document.createElement('div')) {
  const info = SCREENS[name] || { label: name, icon: 'smartphone' };
  layer.classList.add('layer');
  layer.dataset.name = name;
  if (fixedTheme) layer.dataset.theme = fixedTheme;

  if (info.illo) {
    layer.innerHTML = info.illo();
    layer.firstElementChild.setAttribute('aria-hidden', 'true');
    layer.classList.add('is-loaded');
    return layer;
  }

  const ph = document.createElement('div');
  ph.className = 'ph';
  ph.setAttribute('aria-hidden', 'true');
  ph.innerHTML = `<span class="ph__icon"><span class="ms">${info.icon}</span></span>
    <span class="ph__name"></span><span class="ph__note">Screenshot coming soon</span><span class="ph__file"></span>`;
  $('.ph__name', ph).textContent = info.label;

  const img = new Image();
  img.alt = '';
  img.width = 1280;
  img.height = 2856;
  img.decoding = 'async';
  if (eager) img.fetchPriority = 'high';
  else img.loading = 'lazy';
  img.addEventListener('load', () => layer.classList.replace('is-missing', 'is-loaded') || layer.classList.add('is-loaded'));
  img.addEventListener('error', () => { layer.classList.remove('is-loaded'); layer.classList.add('is-missing'); });

  layer.append(ph, img);
  setLayerTheme(layer);
  return layer;
}

function setLayerTheme(layer) {
  const theme = layer.dataset.theme || pageTheme();
  const img = $('img', layer);
  if (!img) return; // illustration: follows the page colours by itself
  const src = screenSrc(layer.dataset.name, theme);
  if (img.getAttribute('src') !== src) {
    layer.classList.remove('is-loaded', 'is-missing');
    img.src = src;
  }
  $('.ph__file', layer).textContent = screenFile(layer.dataset.name, theme);
}

class ScreenView {
  constructor(device, { eager = false } = {}) {
    this.device = device;
    this.screen = $('.device__screen', device);
    this.layers = new Map();
    this.current = null;
    this.eager = eager;
    this.show(device.dataset.screen, { instant: true });
  }

  show(name, { instant = false } = {}) {
    if (!name || name === this.current) return;
    let layer = this.layers.get(name);
    if (!layer) {
      layer = makeLayer(name, { eager: this.eager && !this.current });
      this.layers.set(name, layer);
    }
    this.screen.append(layer); // newest on top
    this.current = name;
    this.device.setAttribute('aria-label', `Bosunarr app screenshot: ${(SCREENS[name] || {}).label || name}`);

    const reveal = () => {
      if (this.current !== name) return;
      layer.classList.add('is-active');
      clearTimeout(this.cleanup);
      this.cleanup = setTimeout(() => {
        for (const l of this.layers.values()) if (l !== this.layers.get(this.current)) l.classList.remove('is-active');
      }, 700);
    };
    const settled = layer.classList.contains('is-loaded') || layer.classList.contains('is-missing');
    if (instant || settled) { requestAnimationFrame(reveal); return; }
    // Wait (briefly) for the image so the crossfade never shows a half-loaded frame.
    const img = $('img', layer);
    let done = false;
    const go = () => { if (!done) { done = true; reveal(); } };
    img.addEventListener('load', go, { once: true });
    img.addEventListener('error', go, { once: true });
    setTimeout(go, 1200);
  }

  retheme() { for (const l of this.layers.values()) setLayerTheme(l); }
}

const views = [];
function initScreens() {
  for (const device of $$('[data-screen-view]')) {
    const view = new ScreenView(device, { eager: device.dataset.screenView === 'hero' });
    device.view = view;
    views.push(view);
  }
  // Fixed-theme layers (light/dark compare).
  for (const el of $$('[data-fixed]')) {
    const [, name, theme] = el.dataset.fixed.match(/^(.+)-(light|dark)$/) || [];
    if (name) makeLayer(name, { fixedTheme: theme }, el);
  }
}

/* ------------------------------------------------------------------ */
/* Theme (system / light / dark)                                       */
/* ------------------------------------------------------------------ */

const THEMES = {
  system: { icon: 'brightness_auto', label: 'Theme: follow system' },
  light: { icon: 'light_mode', label: 'Theme: light' },
  dark: { icon: 'dark_mode', label: 'Theme: dark' },
};

/** Browser UI colour = the page's --surface token (same formula as styles.css), as hex. */
function updateThemeColorMeta() {
  const meta = $('meta[name="theme-color"]');
  if (!meta) return;
  const s = currentSeed();
  const dark = pageTheme() === 'dark';
  meta.setAttribute('content', oklchToHex(dark ? 0.165 : 0.985, (dark ? 0.012 : 0.008) * s.c, s.h));
}

function applyTheme(mode, save = true) {
  if (mode === 'light' || mode === 'dark') root.dataset.theme = mode;
  else { delete root.dataset.theme; mode = 'system'; }
  if (save) store.set('bosunarr-theme', mode === 'system' ? null : mode);
  const btn = $('#theme-toggle');
  if (btn) {
    $('.ms', btn).textContent = THEMES[mode].icon;
    btn.setAttribute('aria-label', `${THEMES[mode].label}. Click to change.`);
    btn.title = THEMES[mode].label;
  }
  views.forEach((v) => v.retheme());
  updateThemeColorMeta();
}

function initTheme() {
  const order = ['system', 'light', 'dark'];
  const current = () => root.dataset.theme || 'system';
  applyTheme(current(), false);
  $('#theme-toggle')?.addEventListener('click', () => {
    applyTheme(order[(order.indexOf(current()) + 1) % order.length]);
  });
  mqDark.addEventListener('change', () => { if (!root.dataset.theme) applyTheme('system', false); });
}

/* ------------------------------------------------------------------ */
/* Dynamic colour (wallpaper swatches + custom picker)                 */
/* ------------------------------------------------------------------ */

const SEEDS = [
  { id: 'lavender', name: 'Lavender', h: 292, c: 1 },
  { id: 'ocean', name: 'Ocean', h: 250, c: 1 },
  { id: 'lagoon', name: 'Lagoon', h: 200, c: 0.85 },
  { id: 'fern', name: 'Fern', h: 148, c: 0.85 },
  { id: 'citrus', name: 'Citrus', h: 100, c: 0.8 },
  { id: 'ember', name: 'Ember', h: 45, c: 1 },
  { id: 'rose', name: 'Rose', h: 5, c: 0.95 },
  { id: 'graphite', name: 'Graphite', h: 260, c: 0.12 },
];

/** sRGB hex → OKLCH chroma + hue. */
function hexToOklch(hex) {
  const n = parseInt(hex.slice(1), 16);
  const lin = (v) => { v /= 255; return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  const r = lin(n >> 16), g = lin((n >> 8) & 255), b = lin(n & 255);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  const A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
  const B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
  const C = Math.hypot(A, B);
  const H = (Math.atan2(B, A) * 180 / Math.PI + 360) % 360;
  return { c: C, h: H };
}

function oklchToHex(L, C, H) {
  const a = C * Math.cos(H * Math.PI / 180), b = C * Math.sin(H * Math.PI / 180);
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.2914855480 * b) ** 3;
  const toS = (v) => { v = Math.min(1, Math.max(0, v)); v = v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055; return Math.round(v * 255); };
  const rgb = [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
  ].map(toS);
  return '#' + rgb.map((v) => v.toString(16).padStart(2, '0')).join('');
}

let seed = { id: 'lavender', h: 292, c: 1 };
function currentSeed() {
  const cs = getComputedStyle(root);
  const h = parseFloat(cs.getPropertyValue('--h'));
  const c = parseFloat(cs.getPropertyValue('--c'));
  return { h: Number.isFinite(h) ? h : seed.h, c: Number.isFinite(c) ? c : seed.c };
}

function applySeed(next, save = true) {
  seed = next;
  root.style.setProperty('--h', String(Math.round(next.h * 10) / 10));
  root.style.setProperty('--c', String(Math.round(next.c * 100) / 100));
  if (save) store.set('bosunarr-seed', JSON.stringify(next));
  for (const radio of $$('.swatch[role="radio"]')) {
    const on = radio.dataset.id === next.id;
    radio.setAttribute('aria-checked', String(on));
    radio.tabIndex = on ? 0 : -1;
  }
  // If nothing is checked (custom colour), keep the first radio reachable by Tab.
  for (const group of $$('[data-swatches]')) {
    if (!$('[aria-checked="true"]', group)) { const first = $('.swatch', group); if (first) first.tabIndex = 0; }
  }
  for (const custom of $$('.swatch--custom')) {
    custom.classList.toggle('is-checked', next.id === 'custom');
    const input = $('input', custom);
    if (next.id !== 'custom') input.value = oklchToHex(0.55, 0.15 * next.c, next.h);
  }
  updateThemeColorMeta();
}

function initColour() {
  const groups = $$('[data-swatches]');
  if (!groups.length) return;

  for (const group of groups) {
    const compact = !!group.closest('.swatch-row--compact');
    for (const s of SEEDS) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'swatch';
      btn.setAttribute('role', 'radio');
      btn.setAttribute('aria-checked', 'false');
      btn.setAttribute('aria-label', s.name);
      btn.dataset.id = s.id;
      btn.style.setProperty('--sh', s.h);
      btn.style.setProperty('--sc', Math.max(s.c, 0.15));
      btn.innerHTML = '<span class="ms" aria-hidden="true">check</span>';
      btn.addEventListener('click', () => applySeed({ id: s.id, h: s.h, c: s.c }));
      if (compact) {
        btn.title = s.name;
        group.append(btn);
      } else {
        const item = document.createElement('span');
        item.className = 'swatch-item';
        const name = document.createElement('span');
        name.className = 'swatch-name';
        name.textContent = s.name;
        name.setAttribute('aria-hidden', 'true');
        item.append(btn, name);
        group.append(item);
      }
    }
    // Radio-group keyboard: arrows move the selection.
    group.addEventListener('keydown', (e) => {
      const radios = $$('.swatch', group);
      const i = radios.indexOf(document.activeElement);
      if (i < 0) return;
      const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[e.key];
      if (!step) return;
      e.preventDefault();
      const next = radios[(i + step + radios.length) % radios.length];
      next.focus();
      next.click();
    });

    // Custom colour picker (a sibling of the radio group).
    const label = document.createElement('label');
    label.className = 'swatch swatch--custom';
    label.title = 'Pick any colour';
    label.innerHTML = '<input type="color" value="#6750a4"><span class="ms" aria-hidden="true">colors</span><span class="sr-only">Pick any colour</span>';
    let raf = 0;
    $('input', label).addEventListener('input', (e) => {
      cancelAnimationFrame(raf);
      const value = e.target.value;
      raf = requestAnimationFrame(() => {
        const { c, h } = hexToOklch(value);
        applySeed({ id: 'custom', h, c: Math.min(1.2, Math.max(0.08, c / 0.15)) });
      });
    });
    if (compact) {
      const item = document.createElement('span');
      item.className = 'custom-item';
      const text = document.createElement('span');
      text.textContent = 'Any colour';
      text.setAttribute('aria-hidden', 'true');
      item.append(label, text);
      group.parentElement.append(item);
    }
    else {
      const item = document.createElement('span');
      item.className = 'swatch-item';
      const name = document.createElement('span');
      name.className = 'swatch-name';
      name.textContent = 'Custom';
      name.setAttribute('aria-hidden', 'true');
      item.append(label, name);
      group.parentElement.append(item);
    }
  }

  let saved = null;
  try { saved = JSON.parse(store.get('bosunarr-seed') || 'null'); } catch { /* ignore */ }
  applySeed(saved && Number.isFinite(saved.h) && Number.isFinite(saved.c) ? saved : seed, false);
}

/* ------------------------------------------------------------------ */
/* Hero: slideshow, dots, tilt + parallax                              */
/* ------------------------------------------------------------------ */

function initHero() {
  const device = $('[data-screen-view="hero"]');
  const dotsWrap = $('.hero__dots');
  if (!device?.view || !dotsWrap) return;
  const view = device.view;
  const dots = $$('.dot[data-show]', dotsWrap);
  const order = dots.map((d) => d.dataset.show);
  const playBtn = $('#hero-play');
  const interval = parseInt(getComputedStyle(dotsWrap).getPropertyValue('--cycle'), 10) || 4200;

  let index = 0;
  let timer = 0;
  let userPaused = mqReduce.matches; // no auto-advance for reduced motion
  let onScreen = true;

  const running = () => !userPaused && onScreen && !document.hidden;
  const setDots = () => dots.forEach((d, i) => d.setAttribute('aria-pressed', String(i === index)));
  // Create the next screen's layer early so its crossfade is instant.
  const warmNext = () => {
    const next = order[(index + 1) % order.length];
    if (!view.layers.has(next)) {
      const layer = makeLayer(next);
      view.layers.set(next, layer);
      view.screen.prepend(layer);
    }
  };
  const go = (i) => {
    index = (i + order.length) % order.length;
    // Re-trigger the progress animation on the active dot.
    dots.forEach((d) => d.setAttribute('aria-pressed', 'false'));
    void dotsWrap.offsetWidth;
    setDots();
    view.show(order[index]);
    warmNext();
  };
  const schedule = () => {
    clearTimeout(timer);
    dotsWrap.classList.toggle('is-paused', !running());
    if (running()) timer = setTimeout(() => { go(index + 1); schedule(); }, interval);
  };
  const syncPlay = () => {
    $('.ms', playBtn).textContent = userPaused ? 'play_arrow' : 'pause';
    playBtn.setAttribute('aria-label', userPaused ? 'Play slideshow' : 'Pause slideshow');
  };

  dots.forEach((d, i) => d.addEventListener('click', () => { go(i); schedule(); }));
  playBtn?.addEventListener('click', () => { userPaused = !userPaused; syncPlay(); schedule(); });
  document.addEventListener('visibilitychange', schedule);
  new IntersectionObserver(([entry]) => { onScreen = entry.isIntersecting; schedule(); }, { threshold: 0.25 }).observe(device);

  setDots();
  syncPlay();
  schedule();
  if (!userPaused) addEventListener('load', warmNext, { once: true });

  // Tilt + parallax: only for a fine pointer, never with reduced motion.
  const zone = $('[data-tilt-zone]');
  const tilt = $('[data-tilt]');
  const orbit = $('.orbit');
  if (!zone || !tilt || !mqFine.matches || mqReduce.matches) return;
  let px = 0, py = 0, frame = 0;
  const paint = () => {
    frame = 0;
    tilt.style.setProperty('--rx', `${(-py * 6).toFixed(2)}deg`);
    tilt.style.setProperty('--ry', `${(px * 9).toFixed(2)}deg`);
    device.style.setProperty('--gx', `${(50 + px * 40).toFixed(1)}%`);
    device.style.setProperty('--gy', `${(10 + py * 30).toFixed(1)}%`);
    orbit?.style.setProperty('--px', px.toFixed(3));
    orbit?.style.setProperty('--py', py.toFixed(3));
  };
  zone.addEventListener('pointermove', (e) => {
    const r = zone.getBoundingClientRect();
    px = Math.max(-1, Math.min(1, ((e.clientX - r.left) / r.width) * 2 - 1));
    py = Math.max(-1, Math.min(1, ((e.clientY - r.top) / r.height) * 2 - 1));
    if (!frame) frame = requestAnimationFrame(paint);
  });
  zone.addEventListener('pointerleave', () => { px = 0; py = 0; if (!frame) frame = requestAnimationFrame(paint); });
}

/* ------------------------------------------------------------------ */
/* Feature tour (ARIA tabs, automatic activation)                      */
/* ------------------------------------------------------------------ */

function initTour() {
  const list = $('.tour__tabs');
  const device = $('[data-screen-view="tour"]');
  if (!list || !device?.view) return;
  const tabs = $$('[role="tab"]', list);
  const stage = $('.tour__phone');
  let multiTimer = 0;

  const select = (tab, { focus = false } = {}) => {
    tabs.forEach((t, i) => {
      const on = t === tab;
      t.setAttribute('aria-selected', String(on));
      t.tabIndex = on ? 0 : -1;
      const panel = document.getElementById(t.getAttribute('aria-controls'));
      if (panel) panel.hidden = !on;
      if (on) stage?.style.setProperty('--turn', `${i * 33}deg`);
    });
    if (focus) tab.focus();
    if (list.scrollWidth > list.clientWidth) {
      tab.scrollIntoView({ block: 'nearest', inline: 'center', behavior: mqReduce.matches ? 'auto' : 'smooth' });
    }

    // Some tabs show a short sequence of screens (e.g. onboarding).
    clearInterval(multiTimer);
    const seq = (tab.dataset.screens || tab.dataset.screen).split(/\s+/);
    let k = 0;
    device.view.show(seq[0]);
    if (seq.length > 1 && !mqReduce.matches) {
      multiTimer = setInterval(() => { k = (k + 1) % seq.length; device.view.show(seq[k]); }, 2600);
    }
  };

  tabs.forEach((t) => t.addEventListener('click', () => select(t)));
  list.addEventListener('keydown', (e) => {
    const i = tabs.indexOf(document.activeElement);
    if (i < 0) return;
    const to = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[e.key];
    if (to === undefined) return;
    e.preventDefault();
    select(tabs[(to + tabs.length) % tabs.length], { focus: true });
  });
}

/* ------------------------------------------------------------------ */
/* Light / dark compare slider                                         */
/* ------------------------------------------------------------------ */

function initCompare() {
  const wrap = $('[data-compare]');
  const range = $('.compare__range', wrap || undefined);
  if (!wrap || !range) return;
  const set = () => wrap.style.setProperty('--split', range.value);
  range.addEventListener('input', set);
  set();
}

/* ------------------------------------------------------------------ */
/* Latest release from GitHub (version, size, direct APK link)         */
/* ------------------------------------------------------------------ */

const REPO = 'SelinJodhani/bosunarr';

async function initRelease() {
  if (!$('[data-apk], [data-release-meta]')) return;
  const KEY = 'bosunarr-release-v2';
  let data = null;
  try {
    const cached = JSON.parse(sessionStorage.getItem(KEY) || 'null');
    if (cached && Date.now() - cached.t < 5 * 60 * 1000) data = cached.d; // short: new releases show up fast
  } catch { /* ignore */ }

  if (!data) {
    try {
      const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
        headers: { Accept: 'application/vnd.github+json' },
      });
      if (!res.ok) return; // rate-limited or no release: keep the releases-page fallback
      const json = await res.json();
      const asset = (json.assets || []).find((a) => /^bosunarr-[\w.+-]+\.apk$/i.test(a.name));
      data = {
        tag: String(json.tag_name || ''),
        page: json.html_url,
        apk: asset?.browser_download_url,
        size: asset?.size,
        notes: String(json.body || '').slice(0, 6000),
      };
      try { sessionStorage.setItem(KEY, JSON.stringify({ t: Date.now(), d: data })); } catch { /* ignore */ }
    } catch { return; }
  }

  const prefix = `https://github.com/${REPO}/releases/`;
  const version = data.tag.replace(/^v/i, '');
  if (!/^[\w.+-]{1,40}$/.test(version)) return;
  const size = Number.isFinite(data.size) && data.size > 0 ? `${(data.size / 1048576).toFixed(1)} MB` : '';
  const meta = `v${version}${size ? ` · ${size}` : ''}`;

  $$('[data-release-meta]').forEach((el) => { el.textContent = meta; });
  if (typeof data.apk === 'string' && data.apk.startsWith(prefix)) {
    $$('[data-apk]').forEach((a) => { a.href = data.apk; });
  }
  if (typeof data.page === 'string' && data.page.startsWith(prefix)) {
    $$('[data-release-page]').forEach((a) => { a.href = data.page; });
  }
  renderWhatsNew(version, data.notes);
}

/** "What's new": the latest release's top-level bullets (`- **Title** — text`), rendered as plain text. */
function renderWhatsNew(version, notes) {
  const box = $('[data-whatsnew]');
  const list = $('[data-whatsnew-list]');
  if (!box || !list || typeof notes !== 'string') return;
  const strip = (t) => t.replace(/\*\*|__|`/g, '').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').trim();
  const items = notes.split('\n')
    .map((l) => l.trim())
    .filter((l) => /^[-*] /.test(l))
    .map((l) => {
      const m = l.slice(2).match(/^\*\*(.+?)\*\*\s*[—–:-]?\s*(.*)$/);
      return m ? { title: strip(m[1]), text: strip(m[2]) } : { title: '', text: strip(l.slice(2)) };
    })
    .filter((i) => i.title || i.text)
    .slice(0, 6);
  if (!items.length) return;
  list.replaceChildren(...items.map(({ title, text }) => {
    const li = document.createElement('li');
    if (title) {
      const b = document.createElement('strong');
      b.textContent = title;
      li.append(b, ' ');
    }
    const cut = text.length > 140 ? text.slice(0, 137).replace(/\s+\S*$/, '') + '…' : text;
    li.append(cut);
    return li;
  }));
  $$('[data-whatsnew-version]').forEach((el) => { el.textContent = `v${version}`; });
  box.hidden = false;
}

/* ------------------------------------------------------------------ */
/* Top bar, sticky dock, scroll reveals                                */
/* ------------------------------------------------------------------ */

function initChrome() {
  const bar = $('.top-bar');
  if (bar) {
    const onScroll = () => bar.classList.toggle('is-scrolled', scrollY > 8);
    addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }

  // Close the colour popover after picking on touch screens.
  const pop = $('#palette-pop');
  pop?.addEventListener('click', (e) => {
    if (e.target.closest('.swatch[role="radio"]') && !mqFine.matches) pop.hidePopover?.();
  });

  const dock = $('#dock');
  const heroCta = $('#hero-actions');
  const download = $('#download');
  if (dock && heroCta && download) {
    let heroGone = false, atDownload = false;
    const update = () => {
      const show = heroGone && !atDownload;
      dock.classList.toggle('is-shown', show);
      dock.setAttribute('aria-hidden', String(!show));
      $$('a', dock).forEach((a) => { a.tabIndex = show ? 0 : -1; });
    };
    new IntersectionObserver(([e]) => {
      heroGone = !e.isIntersecting && e.boundingClientRect.top < 0;
      update();
    }).observe(heroCta);
    new IntersectionObserver(([e]) => { atDownload = e.isIntersecting; update(); }, { threshold: 0.15 }).observe(download);
  }
}

function initReveal() {
  const items = $$('.reveal, .reveal-stagger');
  items.forEach((el) => {
    if (el.classList.contains('reveal-stagger')) [...el.children].forEach((c, i) => c.style.setProperty('--i', i));
  });
  const finish = (el) => {
    el.classList.add('is-in');
    // Drop the reveal classes afterwards so elements get their own transitions back.
    setTimeout(() => {
      el.classList.remove('reveal', 'reveal-stagger', 'is-in');
      [...el.children].forEach((c) => c.style.removeProperty('--i'));
    }, 1600);
  };
  if (!('IntersectionObserver' in window) || mqReduce.matches) { items.forEach(finish); return; }
  const io = new IntersectionObserver((entries) => {
    for (const e of entries) if (e.isIntersecting) { io.unobserve(e.target); finish(e.target); }
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
  items.forEach((el) => io.observe(el));
}

/* ------------------------------------------------------------------ */

initScreens();
initTheme();
initColour();
initHero();
initTour();
initCompare();
initChrome();
initReveal();
initRelease();
