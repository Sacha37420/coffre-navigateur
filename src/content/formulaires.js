// Script de contenu du Coffre du lab : cadre principal des pages https, après activation par l'utilisateur.
//
// Ce qu'il fait : repérer les champs de connexion et de création de compte, y ajouter un bouton, ouvrir le
// cadre de l'extension (liste des comptes, génération) sous le champ, signaler une soumission de formulaire,
// remplir les champs quand le processus de fond le lui demande pour SA propre origine.
// Ce qu'il ne fait jamais : recevoir une clé, la liste des comptes ou un nom de compte ; remplir sans un clic
// de l'utilisateur dans le cadre de l'extension ; échanger avec le cadre par postMessage (la page le lirait).
(() => {
  'use strict';
  if (window.top !== window || window.__coffreDuLab) return;
  window.__coffreDuLab = true;

  const api = globalThis.browser ?? globalThis.chrome;
  const MENU_URL = api.runtime.getURL('inline/menu.html');
  const BAR_URL = api.runtime.getURL('inline/enregistrer.html');
  const TEXTUAL = new Set(['text', 'email', 'tel', '']);

  let state = { actif: false, locked: true, known: false };
  let current = null;        // { user, passwords: [] } du formulaire en cours
  let menuFor = null;        // champ sous lequel le menu est ouvert
  const decorated = new WeakSet();

  // ---------- Interface dans une racine d'ombre fermée (la page ne peut pas y toucher) ----------
  const host = document.createElement('coffre-du-lab');
  const shadow = host.attachShadow({ mode: 'closed' });
  shadow.innerHTML = `<style>
    :host { all: initial; }
    .btn { position: fixed; z-index: 2147483646; width: 26px; height: 26px; border: 0; border-radius: 6px;
           background: #b4532a; color: #fff; font: 14px/26px system-ui, sans-serif; text-align: center;
           cursor: pointer; padding: 0; box-shadow: 0 1px 3px rgba(0,0,0,.3); }
    iframe { position: fixed; z-index: 2147483647; border: 1px solid #c8c2b8; border-radius: 8px;
             background: #fff; box-shadow: 0 6px 24px rgba(0,0,0,.25); color-scheme: normal; }
  </style>`;
  document.documentElement.append(host);

  const visible = (el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)
    && getComputedStyle(el).visibility !== 'hidden' && !el.disabled && !el.readOnly;

  /** Contexte d'un champ mot de passe : son formulaire (ou le document), son identifiant, ses mots de passe. */
  function contextOf(pw) {
    const root = pw.form || pw.closest('[role="form"], form') || document;
    const passwords = [...root.querySelectorAll('input[type="password"]')].filter(visible);
    const before = [...root.querySelectorAll('input')].filter((i) => TEXTUAL.has((i.getAttribute('type') || '').toLowerCase())
      && visible(i) && (i.compareDocumentPosition(passwords[0] || pw) & Node.DOCUMENT_POSITION_FOLLOWING));
    const preferred = before.filter((i) => /user|mail|login|identif|compte|account/i.test(`${i.name} ${i.id} ${i.autocomplete}`));
    const user = (preferred.length ? preferred : before).at(-1) || null;
    const creation = passwords.length >= 2 || passwords.some((p) => p.autocomplete === 'new-password');
    return { root, user, passwords, creation };
  }

  // ---------- Boutons dans les champs ----------
  const buttons = new Map();   // champ → bouton

  function place(btn, field) {
    const r = field.getBoundingClientRect();
    btn.style.top = `${Math.round(r.top + (r.height - 26) / 2)}px`;
    btn.style.left = `${Math.round(r.right - 30)}px`;
    btn.style.display = r.width > 60 && r.height > 14 && visible(field) ? 'block' : 'none';
  }

  function decorate(field, ctx) {
    if (decorated.has(field)) return;
    decorated.add(field);
    const btn = document.createElement('button');
    btn.className = 'btn';
    btn.type = 'button';
    btn.textContent = '🔑';
    btn.title = 'Coffre du lab';
    btn.addEventListener('mousedown', (e) => e.preventDefault());   // garde le focus dans le champ
    btn.addEventListener('click', () => (menuFor === field ? closeMenu() : openMenu(field)));
    shadow.append(btn);
    buttons.set(field, btn);
    place(btn, field);
    field.addEventListener('focus', () => {
      current = contextOf(field.type === 'password' ? field : ctx.passwords[0]);
      // Proposition systématique : la liste s'ouvre d'elle-même si le site est connu (le remplissage, lui,
      // attend un clic dans le cadre de l'extension).
      if (state.known && !menuFor) openMenu(field);
    });
  }

  function scan() {
    if (!state.actif) return;
    for (const pw of document.querySelectorAll('input[type="password"]')) {
      if (!visible(pw)) continue;
      const ctx = contextOf(pw);
      for (const p of ctx.passwords) decorate(p, ctx);
      // Bouton dans l'identifiant d'un formulaire de connexion d'un site connu.
      if (ctx.user && !ctx.creation && state.known) decorate(ctx.user, ctx);
    }
    for (const [field, btn] of buttons) {
      if (!field.isConnected) { btn.remove(); buttons.delete(field); } else place(btn, field);
    }
    if (menuFor) positionMenu();
  }

  // ---------- Cadre de l'extension sous le champ ----------
  let menu = null;

  function positionMenu() {
    if (!menu || !menuFor) return;
    const r = menuFor.getBoundingClientRect();
    const width = Math.max(280, Math.min(360, r.width));
    menu.style.width = `${width}px`;
    menu.style.height = '260px';
    menu.style.left = `${Math.round(Math.min(Math.max(8, r.left), innerWidth - width - 8))}px`;
    const below = r.bottom + 4;
    menu.style.top = `${Math.round(below + 260 > innerHeight && r.top > 268 ? r.top - 264 : below)}px`;
  }

  function openMenu(field) {
    closeMenu();
    menuFor = field;
    const pw = field.type === 'password' ? field
      : [...(field.form || document).querySelectorAll('input[type="password"]')].find(visible);
    current = pw ? contextOf(pw) : { user: field, passwords: [], creation: false };
    if (field.type !== 'password') current.user = field;
    menu = document.createElement('iframe');
    menu.src = `${MENU_URL}?creation=${current.creation ? 1 : 0}`;
    menu.title = 'Coffre du lab';
    menu.setAttribute('allow', 'clipboard-write');
    shadow.append(menu);
    positionMenu();
  }

  function closeMenu() {
    menu?.remove();
    menu = null;
    menuFor = null;
  }

  document.addEventListener('mousedown', (e) => {
    if (menuFor && e.target !== menuFor && e.target !== host) closeMenu();
  }, true);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeMenu(); closeBar(); } }, true);

  // ---------- Barre « Enregistrer ce mot de passe ? » ----------
  let bar = null;

  function showBar() {
    if (bar) return;
    bar = document.createElement('iframe');
    bar.src = BAR_URL;
    bar.title = 'Coffre du lab : enregistrer le mot de passe';
    Object.assign(bar.style, { top: '12px', right: '12px', width: '360px', height: '190px' });
    shadow.append(bar);
  }

  function closeBar() {
    bar?.remove();
    bar = null;
  }

  // ---------- Remplissage (sur ordre du processus de fond, pour cette origine seulement) ----------
  function setValue(input, value) {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    input.focus();
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function fill({ username, password, nouveau }) {
    const ctx = current || (() => { const p = document.querySelector('input[type="password"]'); return p && contextOf(p); })();
    if (!ctx) return;
    if (nouveau) {
      // Création de compte : le nouveau mot de passe va dans tous les champs, sauf un « mot de passe actuel ».
      const targets = ctx.passwords.filter((p) => p.autocomplete !== 'current-password');
      for (const p of (targets.length ? targets : ctx.passwords)) setValue(p, password);
    } else {
      if (ctx.user && username) setValue(ctx.user, username);
      if (ctx.passwords[0]) setValue(ctx.passwords[0], password);
    }
  }

  api.runtime.onMessage.addListener((msg) => {
    if (!msg || typeof msg.type !== 'string' || !msg.type.startsWith('coffre:')) return;
    if (msg.type === 'coffre:remplir') {
      if (msg.origin !== location.origin) return;   // la page a changé entre-temps : on ne remplit pas
      fill(msg);
      closeMenu();
    } else if (msg.type === 'coffre:fermer-menu') closeMenu();
    else if (msg.type === 'coffre:fermer-barre') closeBar();
    else if (msg.type === 'coffre:etat') { state = { ...state, ...msg.etat }; scan(); }
  });

  // ---------- Soumission : proposer d'enregistrer ----------
  let lastSent = '';

  function submitted() {
    const pw = [...document.querySelectorAll('input[type="password"]')].find((p) => p.value);
    if (!pw) return;
    const ctx = contextOf(pw);
    const password = (ctx.creation ? ctx.passwords.find((p) => p.autocomplete !== 'current-password' && p.value) : pw)?.value || pw.value;
    const username = ctx.user?.value || '';
    const key = `${username}\u0000${password}`;
    if (key === lastSent) return;
    lastSent = key;
    api.runtime.sendMessage({ type: 'cs:soumis', username, password }).then((r) => {
      if (r?.ok && r.proposer) showBar();
    }).catch(() => {});
  }

  document.addEventListener('submit', submitted, true);
  document.addEventListener('click', (e) => {
    const b = e.target instanceof Element ? e.target.closest('button, input[type="submit"], input[type="image"]') : null;
    if (!b || !b.closest('form') || !b.closest('form').querySelector('input[type="password"]')) return;
    if (b.matches('button') && b.type && b.type !== 'submit') return;
    submitted();
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target instanceof HTMLInputElement && e.target.type === 'password') submitted();
  }, true);

  // ---------- Démarrage ----------
  let pending = false;
  const schedule = () => {
    if (pending) return;
    pending = true;
    setTimeout(() => { pending = false; scan(); }, 300);
  };

  api.runtime.sendMessage({ type: 'cs:etat' }).then((r) => {
    if (!r?.ok || !r.actif) return;
    state = { actif: true, locked: r.locked, known: r.known };
    scan();
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['type', 'style', 'class', 'hidden'] });
    addEventListener('scroll', schedule, true);
    addEventListener('resize', schedule);
    return api.runtime.sendMessage({ type: 'cs:proposition' });
  }).then((r) => { if (r?.ok && r.afficher) showBar(); }).catch(() => {});
})();
