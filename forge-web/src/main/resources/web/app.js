"use strict";
/* Forge web client: lobby pages and the phone game screen. Talks to /api (lobby) and /ws (games).
   All text from the server or from players goes into the page as text, never as HTML. */

// ---------- small helpers ----------
const app = document.getElementById("app");

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "text") el.textContent = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem("forge." + key); return v === null ? fallback : JSON.parse(v); } catch (e) { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem("forge." + key, JSON.stringify(value)); } catch (e) { /* private mode: nothing remembered */ }
  },
};

function seats() {
  const all = store.get("seats", {});
  const weekAgo = Date.now() - 7 * 864e5;
  for (const [g, s] of Object.entries(all)) if (!s.at || s.at < weekAgo) delete all[g];
  return all;
}
function rememberSeat(game, seat, label) {
  const all = seats();
  all[game] = { seat, at: Date.now(), label };
  store.set("seats", all);
}
function forgetSeat(game) {
  const all = seats();
  delete all[game];
  store.set("seats", all);
  try { localStorage.removeItem("forge.order." + game); } catch (e) { /* nothing stored */ }
}

async function api(method, path, body) {
  const headers = { "Content-Type": "application/json" };
  const pw = store.get("password", "");
  if (pw) headers["X-Host-Password"] = pw;
  const res = await fetch("/api" + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  let data = {};
  try { data = await res.json(); } catch (e) { /* empty body */ }
  if (res.status === 401 && data.locked) {
    siteOk = false;
    loginPage();
  }
  if (!res.ok) {
    const err = new Error(data.error || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return data;
}

let toastTimer;
function toast(text) {
  document.querySelectorAll(".toast").forEach(t => t.remove());
  const t = h("div", { class: "toast", onclick: () => t.remove() }, text);
  document.body.append(t);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.remove(), 6000);
}

function go(hash) { location.hash = hash; }

// ---------- routing ----------
window.addEventListener("hashchange", route);
async function route() {
  const parts = location.hash.replace(/^#\/?/, "").split("/").map(decodeURIComponent);
  if (parts[0] !== "play") leaveGame();
  // an invite link lets a friend in without the site password
  if (parts[0] !== "join" && !(await siteOpen())) return loginPage();
  switch (parts[0]) {
    case "new": return newGamePage();
    case "decks": return decksPage();
    case "deck": return deckEditorPage(parts[1] || "");
    case "join": return joinPage(parts[1], parts[2]);
    case "play": return playPage(parts[1]);
    default: return homePage();
  }
}

function page(...children) {
  app.textContent = "";
  const p = h("div", { class: "page" }, ...children);
  app.append(p);
  window.scrollTo(0, 0);
  return p;
}
function back(label, hash) {
  return h("div", { class: "top-nav" }, h("button", { class: "btn small", onclick: () => go(hash) }, "‹ " + label));
}

// ---------- password fields ----------
/** A password box with a button to show what was typed. The hidden username keeps password managers
    from mixing up the site password and the host password, which share this site. */
function passwordField(placeholder, username) {
  const input = h("input", { type: "password", placeholder, autocomplete: "current-password", autocapitalize: "off", autocorrect: "off", spellcheck: "false" });
  const eye = h("button", { type: "button", class: "eye", "aria-label": "Show password", title: "Show password" }, "👁");
  eye.addEventListener("click", () => {
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    eye.classList.toggle("on", show);
    eye.setAttribute("aria-label", show ? "Hide password" : "Show password");
    eye.title = show ? "Hide password" : "Show password";
    input.focus();
  });
  const user = h("input", { type: "text", name: "username", autocomplete: "username", value: username, hidden: true, readonly: true, tabindex: "-1" });
  return { input, el: h("div", { class: "pw-field" }, user, input, eye) };
}

// ---------- site password ----------
let siteOk = false;
async function siteOpen() {
  if (siteOk) return true;
  try {
    const res = await fetch("/api/session");
    siteOk = res.ok && (await res.json()).site !== false;
  } catch (e) { siteOk = true; } // offline: let the page say so later
  return siteOk;
}
function loginPage() {
  leaveGame();
  const { input, el: field } = passwordField("Site password", "forge-site");
  const msg = h("div", { class: "error" });
  const button = h("button", { class: "btn primary block" }, "Enter");
  const submit = async e => {
    if (e) e.preventDefault();
    msg.textContent = ""; button.disabled = true;
    try {
      const res = await fetch("/api/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ password: input.value.trim() }) });
      if (!res.ok) { msg.textContent = "That's not the password."; input.select(); return; }
      siteOk = true;
      route();
    } catch (err) { msg.textContent = "Can't reach the server."; }
    finally { button.disabled = false; }
  };
  app.textContent = "";
  app.append(h("div", { class: "gate" },
    h("form", { class: "gate-card", onsubmit: submit },
      h("div", { class: "brand" }, h("span", { class: "brand-mark" }, "✦"), "Forge"),
      h("p", { class: "muted" }, "This table is private. Enter the password to sit down."),
      field, msg, button)));
  input.focus();
}

// ---------- host password ----------
async function hostReady() {
  try { return (await api("GET", "/session")).host; } catch (e) { return true; } // can't tell: let the next step say
}
function passwordPanel(after) {
  const { input, el: field } = passwordField("Host password", "forge-host");
  const msg = h("div", { class: "error" });
  const submit = async () => {
    store.set("password", input.value.trim());
    if (await hostReady()) after(); else msg.textContent = "That's not the host password.";
  };
  input.addEventListener("keydown", e => { if (e.key === "Enter") submit(); });
  return h("div", { class: "panel stack-v" },
    h("div", {}, "Enter the host password to start games and manage decks. Friends you invite don't need it."),
    field, msg, h("button", { class: "btn primary", onclick: submit }, "Continue"));
}

// ---------- home ----------
async function homePage() {
  const p = page(h("h1", {}, "Forge"), h("p", { class: "muted tagline" }, "Magic with friends, straight from the browser."));
  if (!(await hostReady())) { p.append(passwordPanel(homePage)); return; }
  p.append(h("div", { class: "stack-v" },
    h("button", { class: "btn primary block", onclick: () => go("#/new") }, "New game"),
    h("button", { class: "btn block", onclick: () => go("#/decks") }, "Shared decks")));
  const mine = Object.entries(seats()).sort((a, b) => b[1].at - a[1].at);
  if (mine.length) {
    p.append(h("h2", {}, "Your games"));
    const list = h("div", { class: "panel", style: "padding:0" });
    for (const [game, s] of mine) {
      list.append(h("div", { class: "list-item", onclick: () => go("#/play/" + encodeURIComponent(game)) },
        h("span", {}, s.label || "Game"), h("span", { class: "muted" }, "Continue ›")));
    }
    p.append(list);
  }
}

// ---------- new game ----------
async function newGamePage() {
  const p = page(back("Home", "#/"), h("h1", {}, "New game"));
  if (!(await hostReady())) { p.append(passwordPanel(newGamePage)); return; }
  let formats = [], decks = [];
  try { [formats, decks] = await Promise.all([api("GET", "/formats"), api("GET", "/decks")]); }
  catch (e) { p.append(h("div", { class: "error" }, e.message)); return; }

  const choice = { format: store.get("lastFormat", formats[0].id), vs: store.get("lastVs", "computer"), deck: "standard" };
  const name = h("input", { type: "text", value: store.get("name", ""), placeholder: "Your name", maxlength: 20 });
  const paste = h("textarea", { placeholder: "10 Dandân\n20 Island\n…" });
  const pasteMsg = h("div", { class: "muted" });
  const savedSelect = h("select", {}, decks.map(d => h("option", { value: d.name }, `${d.name} (${d.cards} cards)`)));
  const err = h("div", { class: "error" });
  const body = h("div", { class: "stack-v" });

  function pick(list, key, rerender) {
    return h("div", { class: "choice" }, list.map(o => h("button", {
      class: "btn" + (choice[key] === o.id ? " on" : ""),
      onclick: () => { choice[key] = o.id; rerender(); },
    }, o.label, o.small ? h("small", {}, o.small) : null)));
  }
  function render() {
    body.textContent = "";
    const fmt = formats.find(f => f.id === choice.format) || formats[0];
    choice.format = fmt.id;
    body.append(h("h2", {}, "Format"),
      pick(formats.map(f => ({ id: f.id, label: f.label, small: f.description })), "format", render),
      h("h2", {}, "Opponent"),
      pick([{ id: "computer", label: "Computer" }, { id: "friend", label: "A friend", small: "You'll get a link to send" }], "vs", render));
    if (fmt.hostPicksDeck) {
      const deckOpts = [{ id: "standard", label: "Standard list" }];
      if (decks.length) deckOpts.push({ id: "saved", label: "A saved deck" });
      deckOpts.push({ id: "text", label: "Paste a list" });
      body.append(h("h2", {}, "Shared deck"), pick(deckOpts, "deck", render));
      if (choice.deck === "saved") body.append(savedSelect);
      if (choice.deck === "text") body.append(paste, h("div", { class: "row" },
        h("button", { class: "btn small", onclick: checkPaste }, "Check list"), pasteMsg));
    }
    body.append(h("h2", {}, "Your name"), name, err,
      h("button", { class: "btn primary block", onclick: create }, choice.vs === "friend" ? "Create and get the invite link" : "Start game"));
  }
  async function checkPaste() {
    try {
      const r = await api("POST", "/decks/check", { text: paste.value });
      pasteMsg.className = r.problems.length ? "error" : "muted";
      pasteMsg.textContent = r.problems.length ? r.problems.join("\n") : `${r.cards} cards, all recognised`;
    } catch (e) { pasteMsg.className = "error"; pasteMsg.textContent = e.message; }
  }
  async function create(ev) {
    err.textContent = "";
    ev.target.disabled = true;
    store.set("name", name.value.trim());
    store.set("lastFormat", choice.format);
    store.set("lastVs", choice.vs);
    const deck = choice.deck === "saved" ? { kind: "saved", name: savedSelect.value }
      : choice.deck === "text" ? { kind: "text", text: paste.value } : { kind: "standard" };
    try {
      const r = await api("POST", "/games", { format: choice.format, vs: choice.vs, name: name.value, deck });
      const fmt = formats.find(f => f.id === choice.format);
      rememberSeat(r.game, r.seat, `${fmt.label} vs ${choice.vs === "friend" ? "a friend" : "the computer"}`);
      if (r.invite) store.set("invite." + r.game, r.invite);
      go("#/play/" + encodeURIComponent(r.game));
    } catch (e) { err.textContent = e.message; ev.target.disabled = false; }
  }
  render();
  p.append(body);
}

// ---------- join from an invite ----------
async function joinPage(game, invite) {
  const p = page(h("h1", {}, "Join a game"));
  let info;
  try { info = await api("GET", `/games/${encodeURIComponent(game)}?invite=${encodeURIComponent(invite)}`); }
  catch (e) {
    if (seats()[game]) { go("#/play/" + encodeURIComponent(game)); return; } // already joined on this phone
    p.append(h("div", { class: "panel" }, e.message)); return;
  }
  const name = h("input", { type: "text", value: store.get("name", ""), placeholder: "Your name", maxlength: 20 });
  const err = h("div", { class: "error" });
  p.append(h("div", { class: "panel stack-v" },
    h("div", {}, `${info.host} invited you to a game of ${info.format}.`),
    h("label", { class: "field" }, "Your name", name), err,
    h("button", { class: "btn primary", onclick: async ev => {
      ev.target.disabled = true;
      store.set("name", name.value.trim());
      try {
        const r = await api("POST", `/games/${encodeURIComponent(game)}/join`, { invite, name: name.value });
        rememberSeat(r.game, r.seat, `${info.format} vs ${info.host}`);
        go("#/play/" + encodeURIComponent(r.game));
      } catch (e) { err.textContent = e.message; ev.target.disabled = false; }
    } }, "Join")));
}

// ---------- shared decks ----------
async function decksPage() {
  const p = page(back("Home", "#/"), h("h1", {}, "Shared decks"));
  if (!(await hostReady())) { p.append(passwordPanel(decksPage)); return; }
  p.append(h("div", { class: "muted", style: "margin-bottom:12px" },
    "Deck lists the host can pick as the shared library in DanDan. Build one by searching Scryfall, or paste a list."));
  p.append(h("button", { class: "btn primary block", onclick: () => go("#/deck/") }, "New deck"));
  let decks;
  try { decks = await api("GET", "/decks"); } catch (e) { p.append(h("div", { class: "error" }, e.message)); return; }
  if (!decks.length) { p.append(h("p", { class: "muted" }, "No saved decks yet.")); return; }
  const list = h("div", { class: "panel", style: "padding:0;margin-top:12px" });
  for (const d of decks) {
    list.append(h("div", { class: "list-item", onclick: () => go("#/deck/" + encodeURIComponent(d.name)) },
      h("span", {}, d.name), h("span", { class: "muted" }, `${d.cards} cards ›`)));
  }
  p.append(list);
}

// ---------- deck builder ----------
const SCRYFALL = "https://api.scryfall.com";
const BASICS = [["W", "Plains"], ["U", "Island"], ["B", "Swamp"], ["R", "Mountain"], ["G", "Forest"]];

/** "4 Brainstorm" lines to [name, count] pairs; a line without a count is one copy. */
function parseList(text) {
  const out = [];
  for (const raw of (text || "").split(/\r?\n/)) {
    const line = raw.replace(/\s+#.*$/, "").trim();
    if (!line || /^(deck|main|mainboard|sideboard|commander|companion)\s*:?$/i.test(line)) continue;
    const m = line.match(/^(\d+)\s*x?\s+(.+)$/i);
    const name = (m ? m[2] : line).replace(/\s+\([A-Z0-9]{2,6}\)(\s+\S+)?$/, "").trim(); // drop "(SET) 123"
    if (name) out.push([name, m ? Math.max(1, Math.min(99, parseInt(m[1], 10))) : 1]);
  }
  return out;
}

/** A mana cost like "{2}{U}{U}" as small coloured symbols. */
function manaPips(cost) {
  const syms = (cost || "").match(/\{[^}]+\}/g) || [];
  return syms.map(sym => {
    const k = sym.slice(1, -1).toUpperCase();
    const color = /^[WUBRG]$/.test(k) ? k : (/^[WUBRG]\/[WUBRGP]$/.test(k) ? k[0] : "C");
    return h("span", { class: `pip pip-${color}`, title: sym }, k.length > 2 ? k.replace("/", "") : k);
  });
}

/** Scryfall's image for a card, front face for double-faced cards. */
function scryImage(card, size) {
  const uris = card.image_uris || (card.card_faces && card.card_faces[0] && card.card_faces[0].image_uris);
  return uris ? uris[size] : null;
}

async function deckEditorPage(deckName) {
  const p = page(back("Decks", "#/decks"), h("h1", {}, deckName ? "Edit deck" : "New deck"));
  if (!(await hostReady())) { p.append(passwordPanel(() => deckEditorPage(deckName))); return; }
  p.classList.add("wide");
  let text = "";
  if (deckName) {
    try { const d = (await api("GET", "/decks")).find(x => x.name.toLowerCase() === deckName.toLowerCase()); text = d ? d.text : ""; }
    catch (e) { p.append(h("div", { class: "error" }, e.message)); return; }
  }

  // the deck: Forge name -> count, in the order cards were added; info: Forge name -> {type, cost, mv} or null
  const deck = new Map();
  const info = new Map();
  const known = new Map(); // any name as asked (Scryfall's spelling) -> Forge's card, or null
  async function lookUp(names) {
    const ask = [...new Set(names)].filter(n => !known.has(n));
    for (let i = 0; i < ask.length; i += 200) {
      const r = await api("POST", "/cards/known", { names: ask.slice(i, i + 200) });
      for (const [n, c] of Object.entries(r.cards)) {
        known.set(n, c);
        if (c) info.set(c.name, c);
      }
    }
  }
  async function loadText(t) {
    const pairs = parseList(t);
    await lookUp(pairs.map(x => x[0]));
    deck.clear();
    for (const [n, k] of pairs) {
      const c = known.get(n);
      const key = c ? c.name : n; // unknown names stay, marked, so nothing typed is lost
      deck.set(key, (deck.get(key) || 0) + k);
      if (!c) info.set(key, null);
    }
  }
  function deckText() { return [...deck].map(([n, k]) => `${k} ${n}`).join("\n") + (deck.size ? "\n" : ""); }
  function total() { let t = 0; for (const k of deck.values()) t += k; return t; }
  function add(name, d) {
    const k = (deck.get(name) || 0) + d;
    if (k <= 0) deck.delete(name); else deck.set(name, Math.min(99, k));
    drawDeck(); drawResultCounts();
  }

  const name = h("input", { type: "text", value: deckName, placeholder: "Deck name", maxlength: 40 });
  const msg = h("div", { class: "muted builder-msg" });
  const show = (ok, t) => { msg.className = (ok ? "muted" : "error") + " builder-msg"; msg.textContent = t; };

  // ---- search ----
  const q = h("input", { type: "search", placeholder: "Card name, or Scryfall search: t:creature c:u mv<=2", autocomplete: "off", autocapitalize: "off", spellcheck: "false" });
  const results = h("div", { class: "results" });
  const more = h("button", { class: "btn block", hidden: true }, "More results");
  const status = h("div", { class: "muted small search-status" }, "Type to search Scryfall. Plain words match any part of a card's name.");
  let seq = 0, nextPage = null, timer = null;
  async function search(url, append) {
    const mine = ++seq;
    status.textContent = "Searching…";
    let data;
    try {
      const res = await fetch(url, { headers: { Accept: "application/json" } });
      data = await res.json();
      if (mine !== seq) return;
      if (!res.ok) {
        if (!append) results.textContent = "";
        more.hidden = true;
        status.textContent = res.status === 404 ? "No cards match." : (data.details || "Scryfall couldn't run that search.");
        return;
      }
    } catch (e) {
      if (mine === seq) status.textContent = "Can't reach Scryfall right now.";
      return;
    }
    const cards = data.data || [];
    try { await lookUp(cards.map(c => c.name)); } catch (e) { /* shown below as unknown */ }
    if (mine !== seq) return;
    if (!append) { results.textContent = ""; results.scrollTop = 0; }
    for (const c of cards) results.append(resultEl(c));
    nextPage = data.has_more ? data.next_page : null;
    more.hidden = !nextPage;
    const playable = cards.filter(c => known.get(c.name)).length;
    status.textContent = `${data.total_cards} ${data.total_cards === 1 ? "card" : "cards"} on Scryfall` +
      (playable < cards.length ? ` · ${cards.length - playable} shown here aren't in Forge yet` : "");
    if (data.warnings && data.warnings.length) status.textContent += " · " + data.warnings.join(" ");
    drawResultCounts();
  }
  function runSearch() {
    const text = q.value.trim();
    if (text.length < 2) { seq++; results.textContent = ""; more.hidden = true; status.textContent = "Type at least two letters."; return; }
    search(`${SCRYFALL}/cards/search?unique=cards&order=name&q=${encodeURIComponent(text)}`, false);
  }
  q.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(runSearch, 350); });
  q.addEventListener("keydown", e => { if (e.key === "Enter") { clearTimeout(timer); runSearch(); } });
  more.addEventListener("click", () => { if (nextPage) search(nextPage, true); });

  function resultEl(c) {
    const forge = known.get(c.name);
    const img = scryImage(c, "normal");
    const el = h("button", { class: "result" + (forge ? "" : " unknown"), "data-name": forge ? forge.name : "", title: forge ? `Add ${forge.name}` : `${c.name}: not in Forge yet` },
      img ? h("img", { src: img, alt: c.name, loading: "lazy", draggable: "false" }) : h("div", { class: "result-text" }, h("b", {}, c.name), h("span", {}, c.type_line || "")),
      h("span", { class: "result-count", hidden: true }),
      forge ? null : h("span", { class: "result-flag" }, "Not in Forge"));
    // hold to see the card large; the tap that ends a hold doesn't add it
    let holdTimer = null, held = false, hx = 0, hy = 0;
    el.addEventListener("pointerdown", e => {
      held = false; hx = e.clientX; hy = e.clientY;
      if (e.pointerType === "mouse" || !img) return;
      holdTimer = setTimeout(() => { held = true; if (navigator.vibrate) navigator.vibrate(12); showLarge(img, c.name); }, 400);
    });
    el.addEventListener("pointermove", e => { if (Math.abs(e.clientX - hx) + Math.abs(e.clientY - hy) > 10) clearTimeout(holdTimer); });
    for (const t of ["pointerup", "pointercancel"]) el.addEventListener(t, () => clearTimeout(holdTimer));
    el.addEventListener("click", e => {
      if (held) { e.preventDefault(); held = false; return; }
      if (!forge) { toast(`${c.name} isn't in Forge yet, so it can't go in a deck.`); return; }
      add(forge.name, 1);
      el.classList.remove("bump"); void el.offsetWidth; el.classList.add("bump");
    });
    el.addEventListener("contextmenu", e => { e.preventDefault(); if (img) showLarge(img, c.name); });
    if (canHover && img) {
      el.addEventListener("pointerenter", e => { if (e.pointerType === "mouse") hoverImg(el, img); });
      el.addEventListener("pointerleave", () => hidePreview(el));
    }
    return el;
  }
  function drawResultCounts() {
    for (const el of results.querySelectorAll(".result")) {
      const n = el.dataset.name ? deck.get(el.dataset.name) || 0 : 0;
      const badge = el.querySelector(".result-count");
      badge.hidden = !n; badge.textContent = n ? "×" + n : "";
      el.classList.toggle("in-deck", n > 0);
    }
  }
  function hoverImg(el, src) {
    hidePreview();
    const r = el.getBoundingClientRect();
    const w = Math.min(300, window.innerWidth * 0.42), ht = w * 88 / 63;
    let left = r.right + 12;
    if (left + w > window.innerWidth - 8) left = r.left - w - 12;
    left = Math.max(8, left);
    const top = Math.min(Math.max(8, r.top + r.height / 2 - ht / 2), Math.max(8, window.innerHeight - ht - 8));
    preview = h("div", { class: "card preview" }, h("img", { src, alt: "" }));
    Object.assign(preview.style, { width: w + "px", left: left + "px", top: top + "px" });
    preview.anchor = el;
    document.body.append(preview);
  }
  function showLarge(src, alt) {
    hidePreview();
    zoomOverlay(h("div", { class: "card" }, h("img", { src, alt })), h("div", { class: "muted" }, "Tap anywhere to close"));
  }

  // ---- the deck ----
  const deckBox = h("div", { class: "deck-list" });
  const countEl = h("span", { class: "deck-count" });
  const GROUPS = [["Creatures", t => /Creature/.test(t)], ["Instants and sorceries", t => /Instant|Sorcery/.test(t)],
    ["Other spells", t => !/Land/.test(t)], ["Lands", t => /Land/.test(t)]];
  function drawDeck() {
    deckBox.textContent = "";
    const n = total();
    countEl.textContent = `${n} ${n === 1 ? "card" : "cards"}`;
    tabDeck.textContent = `Deck (${n})`;
    if (!deck.size) { deckBox.append(h("div", { class: "muted empty" }, "No cards yet. Search, then tap a card to add it.")); return; }
    const left = new Set(deck.keys());
    const unknown = [...left].filter(nm => !info.get(nm));
    unknown.forEach(nm => left.delete(nm));
    const sections = [];
    for (const [title, test] of GROUPS) {
      const names = [...left].filter(nm => test(info.get(nm).type));
      names.forEach(nm => left.delete(nm));
      if (names.length) sections.push([title, names]);
    }
    if (unknown.length) sections.unshift(["Not found in Forge", unknown]);
    for (const [title, names] of sections) {
      names.sort((a, b) => ((info.get(a) || {}).mv || 0) - ((info.get(b) || {}).mv || 0) || a.localeCompare(b));
      const k = names.reduce((s, nm) => s + deck.get(nm), 0);
      deckBox.append(h("div", { class: "deck-group" + (title === "Not found in Forge" ? " bad" : "") }, h("span", {}, title), h("span", { class: "muted" }, k)));
      for (const nm of names) {
        const c = info.get(nm);
        deckBox.append(h("div", { class: "deck-row" },
          h("button", { class: "step-btn", "aria-label": "One fewer", onclick: () => add(nm, -1) }, "−"),
          h("b", { class: "qty" }, deck.get(nm)),
          h("button", { class: "step-btn", "aria-label": "One more", onclick: () => add(nm, 1) }, "+"),
          h("span", { class: "row-name", title: c ? c.type : "Forge doesn't know this card. Fix the name or remove it." }, nm),
          h("span", { class: "row-cost" }, c ? manaPips(c.cost) : "?")));
      }
    }
  }
  const basics = h("div", { class: "basics" }, h("span", { class: "muted small" }, "Add a basic:"),
    BASICS.map(([sym, land]) => h("button", { class: `chip mana mana-${sym}`, title: `Add ${land}`, onclick: async () => {
      if (!info.has(land)) { try { await lookUp([land]); } catch (e) { return; } }
      add(land, 1);
    } }, `+ ${land}`)));

  // ---- paste and copy ----
  const area = h("textarea", { placeholder: "One card per line, count first:\n10 Dandân\n20 Island" });
  const listPanel = h("details", { class: "list-io" }, h("summary", {}, "Paste or copy a list"),
    h("div", { class: "stack-v", style: "margin-top:10px" }, area,
      h("div", { class: "row wrap" },
        h("button", { class: "btn", onclick: async () => {
          try { await loadText(area.value); drawDeck(); drawResultCounts(); const bad = [...deck.keys()].filter(nm => !info.get(nm)); show(!bad.length, bad.length ? `Forge doesn't know: ${bad.join(", ")}` : `Loaded ${total()} cards.`); }
          catch (e) { show(false, e.message); }
        } }, "Load into the deck"),
        h("button", { class: "btn", onclick: async () => {
          area.value = deckText();
          try { await navigator.clipboard.writeText(area.value); show(true, "Copied the list."); } catch (e) { area.select(); show(true, "Select all and copy."); }
        } }, "Copy the deck"))));
  listPanel.addEventListener("toggle", () => { if (listPanel.open && !area.value.trim()) area.value = deckText(); });

  // ---- save ----
  const save = h("button", { class: "btn primary", onclick: async () => {
    const bad = [...deck.keys()].filter(nm => !info.get(nm));
    if (bad.length) { show(false, `Fix or remove first: ${bad.join(", ")}`); return; }
    try {
      const r = await api("PUT", "/decks", { name: name.value, text: deckText() });
      if (deckName && deckName.toLowerCase() !== r.name.toLowerCase()) await api("DELETE", "/decks?name=" + encodeURIComponent(deckName));
      go("#/decks");
    } catch (e) { show(false, e.message); }
  } }, "Save deck");

  // ---- layout: search and deck side by side, or as tabs on a phone ----
  const searchPane = h("section", { class: "pane search-pane" }, q, status, results, more);
  const deckPane = h("section", { class: "pane deck-pane" },
    h("div", { class: "deck-head" }, h("b", {}, "Deck"), countEl), basics, deckBox, listPanel);
  const tabSearch = h("button", { class: "tab on", onclick: () => setTab("search") }, "Search");
  const tabDeck = h("button", { class: "tab", onclick: () => setTab("deck") }, "Deck (0)");
  function setTab(t) {
    builder.dataset.tab = t;
    tabSearch.classList.toggle("on", t === "search");
    tabDeck.classList.toggle("on", t === "deck");
  }
  const builder = h("div", { class: "builder", "data-tab": "search" }, h("div", { class: "tabs" }, tabSearch, tabDeck), searchPane, deckPane);
  p.append(h("div", { class: "stack-v" },
    h("label", { class: "field" }, "Name", name),
    builder, msg,
    h("div", { class: "row wrap builder-actions" }, save,
      deckName ? h("button", { class: "btn danger", onclick: async () => {
        if (!confirm(`Delete "${deckName}"?`)) return;
        try { await api("DELETE", "/decks?name=" + encodeURIComponent(deckName)); go("#/decks"); } catch (e) { show(false, e.message); }
      } }, "Delete deck") : null)));
  try { if (text) await loadText(text); } catch (e) { show(false, e.message); }
  drawDeck();
  if (!deckName) q.focus();
}

// =====================================================================================
// The game
// =====================================================================================
const G = {};
function leaveGame() {
  if (G.ws) { G.closing = true; G.ws.close(); }
  for (const k of Object.keys(G)) delete G[k];
  document.querySelectorAll(".banner,.toast").forEach(b => b.remove());
}

function playPage(game) {
  leaveGame();
  const mine = seats()[game];
  if (!mine) {
    page(back("Home", "#/"), h("div", { class: "panel" }, "This browser isn't seated in that game. Open your invite link again, or start a new game."));
    return;
  }
  Object.assign(G, { game, seat: mine.seat, cards: new Map(), order: store.get("order." + game, {}), failedImages: store.get("noImage", {}), retry: 0, hide: {} });
  app.textContent = "";
  app.append(h("div", { class: "page" }, h("p", { class: "muted" }, "Connecting…")));
  connect();
}

function connect() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${location.host}/ws?game=${encodeURIComponent(G.game)}&seat=${encodeURIComponent(G.seat)}`);
  G.ws = ws;
  ws.onopen = () => { G.retry = 0; document.querySelectorAll(".banner").forEach(b => b.remove()); };
  ws.onmessage = e => onMessage(JSON.parse(e.data));
  ws.onclose = e => {
    if (G.ws !== ws || G.closing) return;
    if (e.code === 4000) {
      forgetSeat(G.game);
      page(back("Home", "#/"), h("div", { class: "panel" }, e.reason || "That game has ended."));
      return;
    }
    if (G.over) return;
    if (!document.querySelector(".banner")) document.body.append(h("div", { class: "banner" }, "Reconnecting…"));
    G.retry = Math.min(G.retry + 1, 5);
    setTimeout(() => { if (G.ws === ws) connect(); }, 1000 * Math.pow(2, G.retry - 1));
  };
}
document.addEventListener("visibilitychange", () => {
  // phones drop sockets while asleep; come straight back when the page is visible again
  if (!document.hidden && G.ws && G.ws.readyState > 1 && !G.over) connect();
});

function send(o) { if (G.ws && G.ws.readyState === 1) G.ws.send(JSON.stringify(o)); }

function onMessage(m) {
  switch (m.t) {
    case "joined": Object.assign(G, { format: m.format, youName: m.you, oppName: m.opponent }); sendStops(); break;
    case "waiting": G.invite = m.invite; if (!G.state) renderWaiting(); return;
    case "state":
      G.state = m;
      if (m.gameOver) G.over = true;
      break;
    case "prompt": G.prompt = m; break;
    case "buttons": G.buttons = m; maybeAutoTap(); break;
    case "selection": G.selection = m; break;
    case "ask": G.ask = m; G.askHidden = false; G.askPicks = []; break;
    case "notice": toast((m.title ? m.title + ": " : "") + m.text); return;
    case "invalid": {
      const bar = document.querySelector(".action-bar");
      if (bar) { bar.classList.remove("shake"); void bar.offsetWidth; bar.classList.add("shake"); }
      if (navigator.vibrate) navigator.vibrate(60);
      return;
    }
    case "alert": if (navigator.vibrate) navigator.vibrate(30); return;
    case "error": toast(m.text); return;
    case "gameOver": G.over = true; forgetSeat(G.game); break;
    default: return;
  }
  if (G.state) scheduleRender();
}

let renderQueued = false;
function scheduleRender() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    if (G.dragging) { G.renderWanted = true; return; } // a render would drop the card being dragged
    if (G.state) renderGame();
  });
}

function renderWaiting() {
  const link = `${location.origin}${location.pathname}#/join/${encodeURIComponent(G.game)}/${encodeURIComponent(G.invite)}`;
  const p = page(back("Home", "#/"), h("h1", {}, "Waiting for your friend"));
  p.append(h("div", { class: "panel stack-v" },
    h("div", {}, `Send this link to the friend you want to play ${G.format || ""} with. The game starts when they join.`),
    h("div", { class: "invite-link" }, link),
    h("div", { class: "row" },
      navigator.share ? h("button", { class: "btn primary", onclick: () => navigator.share({ title: "Play Forge with me", url: link }).catch(() => {}) }, "Share link") : null,
      h("button", { class: "btn", onclick: async () => {
        try { await navigator.clipboard.writeText(link); toast("Link copied."); } catch (e) { toast("Copy the link above."); }
      } }, "Copy link")),
    h("div", { class: "muted" }, "Anyone with the link can take the seat, once. Keep this page open, or come back to it from Home.")));
}

// ---------- reading the state ----------
function me() { return G.state.players.find(p => p.id === G.state.you); }
function opp() { return G.state.players.find(p => p.id !== G.state.you); }
function isTarget(kind, id) {
  const s = G.selection;
  if (!s) return false;
  if (kind === "card") return s.cards.includes(id);
  return s.targets.some(t => t.kind === kind && t.id === id);
}
function isChosen(kind, id) {
  return !!G.selection && G.selection.chosen.some(t => t.kind === kind && t.id === id);
}
function combatRole(id) {
  for (const c of G.state.combat) {
    if (c.attacker === id) return "attacking";
    if (c.blockers.includes(id)) return "blocking";
  }
  return null;
}
const PHASES = {
  UNTAP: "Untap", UPKEEP: "Upkeep", DRAW: "Draw", MAIN1: "Main phase", COMBAT_BEGIN: "Beginning of combat",
  COMBAT_DECLARE_ATTACKERS: "Declare attackers", COMBAT_DECLARE_BLOCKERS: "Declare blockers",
  COMBAT_FIRST_STRIKE_DAMAGE: "First-strike damage", COMBAT_DAMAGE: "Combat damage", COMBAT_END: "End of combat",
  MAIN2: "Second main phase", END_OF_TURN: "End step", CLEANUP: "Cleanup",
};

// ---------- cards ----------
function imageUrl(c, size) {
  return `/img?name=${encodeURIComponent(c.name)}${c.img ? "&set=" + encodeURIComponent(c.img) : ""}&size=${size}`;
}
function textFace(c) {
  return h("div", { class: "text-face" },
    h("b", {}, c.name), h("span", { class: "muted" }, [c.cost, c.type].filter(Boolean).join(" · ")),
    c.text ? h("span", {}, c.text) : null);
}
/** The card's text, with its image laid over it once the image loads; the text stays if it never does. */
function cardFace(c, size) {
  if (c.hidden) return null;
  if (G.failedImages[c.name]) return textFace(c);
  const img = h("img", { src: imageUrl(c, size), alt: c.name, loading: "lazy", draggable: "false" });
  img.addEventListener("error", () => {
    G.failedImages[c.name] = 1;
    store.set("noImage", G.failedImages);
    img.remove();
  });
  return [textFace(c), img];
}
function badges(c) {
  const out = [];
  if (c.power !== undefined) out.push(h("span", { class: "badge pt" + (c.damage ? " hurt" : "") }, `${c.power}/${c.toughness}` + (c.damage ? ` (−${c.damage})` : "")));
  if (c.counters) out.push(h("span", { class: "badge counters" }, Object.entries(c.counters).map(([k, v]) => `${v} ${k}`).join(" ")));
  const blocks = G.state && G.state.combat ? G.state.combat.filter(a => a.blockers.includes(c.id)) : [];
  const blockTag = blocks.length ? "blocks " + blocks.map(a => cardName(a.attacker)).join(", ") : null;
  const tags = [c.token && "token", c.sick && "sick", blockTag, c.zoneTag].filter(Boolean);
  if (tags.length) out.push(h("span", { class: "badge tag" }, tags.join(" · ")));
  return out;
}

/** A card element, reused between renders when nothing about it changed, so images don't reload. */
function cardEl(c, opts = {}) {
  const role = c.hidden ? null : combatRole(c.id);
  const classes = ["card",
    c.hidden && "hidden-card", c.tapped && "tapped", c.sick && "sick", role,
    !c.hidden && isTarget("card", c.id) && "target", !c.hidden && isChosen("card", c.id) && "chosen",
    opts.on && "on"].filter(Boolean).join(" ");
  const key = JSON.stringify([c, classes, opts.size || "small"]);
  const cacheKey = (opts.cacheTag || "") + c.id;
  const cached = !opts.noCache && G.cards.get(cacheKey);
  if (cached && cached.key === key) return cached.el;
  const el = h("div", { class: classes, "data-id": c.id }, cardFace(c, opts.size || "small"), c.hidden ? null : badges(c), opts.extra || null);
  attachPress(el, () => (opts.onTap ? opts.onTap(c) : tapCard(c)), () => zoom(c), c);
  if (!opts.noCache) G.cards.set(cacheKey, { key, el });
  return el;
}

/** Tap acts; a long press (or right click) shows the card large. In a sortable row (data-sort on the
    parent), a mouse drags as soon as it moves, and a finger drags after a long press: lifting it
    without moving still shows the card large. With a mouse, hovering shows a large preview. */
function attachPress(el, onTap, onLong, card) {
  let timer = null, long = false, armed = false, down = false, dragging = false, sx = 0, sy = 0, type = "";
  const sortable = () => !!(el.parentElement && el.parentElement.dataset.sort);
  const clear = () => { clearTimeout(timer); timer = null; };
  const disarm = () => { armed = false; el.classList.remove("lifted"); };
  el.addEventListener("pointerdown", e => {
    if (e.button > 0) return;
    down = true; long = false; dragging = false; disarm();
    sx = e.clientX; sy = e.clientY; type = e.pointerType;
    timer = setTimeout(() => {
      timer = null; long = true;
      // holding shows the card large at once; on a finger, sliding on from there drags it instead
      if (type !== "mouse" && sortable()) { armed = true; el.classList.add("lifted"); }
      if (type !== "mouse" && navigator.vibrate) navigator.vibrate(12);
      onLong();
    }, 400);
  });
  el.addEventListener("pointermove", e => {
    if (!down || dragging) return;
    const dist = Math.abs(e.clientX - sx) + Math.abs(e.clientY - sy);
    if ((type === "mouse" && !long && dist > 6 && sortable()) || (armed && dist > 10)) {
      if (armed) closeZoom(); // the finger moved on: it wants to drag, not look
      clear(); disarm(); dragging = true; long = true; // the click after a drag does nothing
      startDrag(el, e, () => { dragging = false; down = false; });
    } else if (dist > 10) clear();
  });
  el.addEventListener("pointerup", () => {
    clear(); down = false;
    if (armed && !dragging) disarm(); // the large card stays up until a tap closes it
  });
  el.addEventListener("pointercancel", () => { clear(); down = false; disarm(); });
  // the browser must not scroll the row while a lifted card is being dragged
  el.addEventListener("touchmove", e => { if (armed || dragging) e.preventDefault(); }, { passive: false });
  el.addEventListener("contextmenu", e => {
    e.preventDefault();
    if (type === "touch" || type === "pen") return; // a long press already handled it
    clear(); long = true; onLong();
  });
  el.addEventListener("click", e => { if (long) { e.preventDefault(); return; } onTap(); });
  if (card && canHover) {
    el.addEventListener("pointerenter", e => { if (e.pointerType === "mouse" && !G.dragging) showPreview(el, card); });
    el.addEventListener("pointerleave", () => hidePreview(el));
  }
}

// ---------- drag to reorder ----------
/** Moves the card itself through its row as a placeholder while a copy follows the pointer. */
function startDrag(el, e, done) {
  const box = el.parentElement, key = box.dataset.sort;
  hidePreview();
  const r = el.getBoundingClientRect();
  const ox = e.clientX - r.left, oy = e.clientY - r.top;
  const ghost = el.cloneNode(true);
  ghost.classList.add("drag-ghost");
  Object.assign(ghost.style, { width: r.width + "px", height: r.height + "px", left: r.left + "px", top: r.top + "px" });
  document.body.append(ghost);
  el.classList.add("drag-source");
  G.dragging = true;
  const move = ev => {
    ev.preventDefault();
    ghost.style.left = (ev.clientX - ox) + "px";
    ghost.style.top = (ev.clientY - oy) + "px";
    const br = box.getBoundingClientRect();
    if (box.scrollWidth > box.clientWidth) {
      if (ev.clientX < br.left + 32) box.scrollLeft -= 12;
      else if (ev.clientX > br.right - 32) box.scrollLeft += 12;
    }
    // where the card goes: before the first card the pointer is above, or left of the middle of
    const y = Math.min(Math.max(ev.clientY, br.top + 1), br.bottom - 1);
    let before = null;
    for (const s of box.children) {
      if (s === el || !s.classList.contains("card")) continue;
      const b = s.getBoundingClientRect();
      if (y < b.top || (y <= b.bottom && ev.clientX < b.left + b.width / 2)) { before = s; break; }
    }
    if (before !== el.nextElementSibling || (!before && box.lastElementChild !== el)) box.insertBefore(el, before);
  };
  const up = () => {
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", up);
    window.removeEventListener("pointercancel", up);
    ghost.remove();
    el.classList.remove("drag-source");
    G.dragging = false;
    G.order[key] = [...box.children].map(c => c.dataset.id).filter(Boolean);
    store.set("order." + G.game, G.order);
    done();
    if (G.renderWanted) { G.renderWanted = false; scheduleRender(); }
  };
  window.addEventListener("pointermove", move, { passive: false });
  window.addEventListener("pointerup", up);
  window.addEventListener("pointercancel", up);
}

/** The cards in the order the player dragged them into; cards new to the row go at the end. */
function arranged(key, cards) {
  const ord = G.order && G.order[key];
  if (!ord) return cards;
  const pos = new Map(ord.map((id, i) => [id, i]));
  const at = (c, i) => pos.has(String(c.id)) ? pos.get(String(c.id)) : 1e6 + i;
  return cards.map((c, i) => [c, at(c, i)]).sort((a, b) => a[1] - b[1]).map(x => x[0]);
}

// ---------- hover preview (mouse only) ----------
const canHover = !!(window.matchMedia && matchMedia("(hover: hover) and (pointer: fine)").matches);
let preview = null;
function showPreview(el, c) {
  if (c.hidden) return;
  hidePreview();
  const r = el.getBoundingClientRect();
  const w = Math.min(300, window.innerWidth * 0.42), ht = w * 88 / 63;
  let left = r.right + 12;
  if (left + w > window.innerWidth - 8) left = r.left - w - 12;
  left = Math.max(8, left);
  const top = Math.min(Math.max(8, r.top + r.height / 2 - ht / 2), Math.max(8, window.innerHeight - ht - 8));
  preview = h("div", { class: "card preview" }, cardFace(c, "normal"), badges(c));
  Object.assign(preview.style, { width: w + "px", left: left + "px", top: top + "px" });
  preview.anchor = el;
  document.body.append(preview);
}
/** Hides the preview; given a card element, only if the preview is that card's. */
function hidePreview(el) {
  if (preview && (!el || preview.anchor === el)) { preview.remove(); preview = null; }
}

function tapCard(c) {
  if (c.hidden) return;
  send({ t: "card", id: c.id });
}

function closeZoom() { document.querySelectorAll(".zoom").forEach(z => z.remove()); }
/** A full-screen overlay. Only a tap that starts on it closes it, so lifting the finger that opened
    it (however long it was held) leaves it up. */
function zoomOverlay(...children) {
  closeZoom();
  let touched = false;
  const z = h("div", { class: "zoom", onpointerdown: () => { touched = true; }, onclick: () => { if (touched) z.remove(); } }, ...children);
  document.body.append(z);
  return z;
}

function zoom(c) {
  if (c.hidden) return;
  hidePreview();
  zoomOverlay(
    h("div", { class: "card" }, cardFace(c, "normal"), badges(c)),
    h("div", { class: "details" }, [c.name, [c.cost, c.type].filter(Boolean).join(" · "), c.text].filter(Boolean).join("\n")),
    h("div", { class: "muted" }, "Tap anywhere to close"));
}

function cardName(id) {
  for (const p of (G.state ? G.state.players : [])) for (const c of p.battlefield) if (c.id === id) return c.name;
  return "an attacker";
}

// ---------- the board ----------
function splitBattlefield(p) {
  const lands = [], others = [];
  for (const c of p.battlefield) {
    const isLand = !c.hidden && /\bLand\b/.test(c.type || "") && !/\bCreature\b/.test(c.type || "");
    (isLand ? lands : others).push(c);
  }
  const command = p.command.map(c => Object.assign({}, c, { zoneTag: "command" }));
  return { lands, others: command.concat(others) };
}

function fieldEl(p, cls) {
  const { lands, others } = splitBattlefield(p);
  const mine = p.id === G.state.you;
  return h("div", { class: "field " + cls },
    h("div", { class: "cards", "data-sort": mine ? "field" : null }, (mine ? arranged("field", others) : others).map(c => cardEl(c))),
    h("div", { class: "cards lands", "data-sort": mine ? "lands" : null }, (mine ? arranged("lands", lands) : lands).map(c => cardEl(c))));
}

function sameIds(a, b) {
  return a.length === b.length && a.every((c, i) => c.id === b[i].id);
}

function playerBar(p, cls) {
  const st = G.state;
  const mine = p.id === st.you;
  const other = mine ? opp() : me();
  const sharedGy = other && p.graveyard.length && sameIds(p.graveyard, other.graveyard);
  const sharedLib = G.format === "DanDan";
  const chips = [];
  for (const [color, n] of Object.entries(p.mana || {})) {
    chips.push(h("button", { class: `chip mana mana-${color}`, onclick: e => { e.stopPropagation(); if (mine) send({ t: "mana", color }); } }, `${color} ${n}`));
  }
  if (!mine) chips.push(h("span", { class: "chip" }, `Hand ${p.handCount}`));
  chips.push(h("span", { class: "chip" }, `${sharedLib ? "Shared library" : "Library"} ${p.libraryCount}`));
  if (p.graveyard.length) chips.push(h("button", { class: "chip", onclick: e => { e.stopPropagation(); zoneSheet(sharedGy ? "Shared graveyard" : `${mine ? "Your" : p.name + "'s"} graveyard`, p.graveyard); } }, `GY ${p.graveyard.length}`));
  if (p.exile.length) chips.push(h("button", { class: "chip", onclick: e => { e.stopPropagation(); zoneSheet(`${mine ? "Your" : p.name + "'s"} exile`, p.exile); } }, `Exile ${p.exile.length}`));
  const active = st.activePlayer === p.id;
  const classes = ["player-bar", cls, active && "active", isTarget("player", p.id) && "target", isChosen("player", p.id) && "chosen"].filter(Boolean).join(" ");
  return h("div", { class: classes, onclick: () => send({ t: "player", id: p.id }) },
    // the menu (log, concede, home) stays in the top-left corner, above the opponent
    mine ? null : h("button", { class: "menu-btn", "aria-label": "Menu", title: "Game log and options", onclick: e => { e.stopPropagation(); menuSheet(); } }, "☰"),
    h("span", { class: "life", title: "Life" }, p.life),
    h("span", { class: "name" }, mine ? "You" : p.name),
    active ? h("span", { class: "turn-pill" }, mine ? "Your turn" : "Their turn") : null,
    !mine && p.priority && !st.gameOver && !waitingOnMe() ? h("span", { class: "chip thinking" }, "thinking…") : null,
    p.poison ? h("span", { class: "chip" }, `☠ ${p.poison}`) : null,
    h("div", { class: "chips" }, chips));
}

/** Whether the game is waiting for this player: a dialog, or a button to press. */
function waitingOnMe() {
  const b = G.buttons;
  const sel = G.selection;
  return !!(G.ask || (b && ((b.ok && b.ok.enabled) || (b.cancel && b.cancel.enabled)))
    || (sel && ((sel.cards && sel.cards.length) || (sel.targets && sel.targets.length))));
}

/** The steps of a turn as the strip shows them; each lights up for the engine phases it covers. */
const STEPS = [
  { key: "UPKEEP", label: "Upkeep", short: "Upk", phases: ["UNTAP", "UPKEEP"] },
  { key: "DRAW", label: "Draw", short: "Draw", phases: ["DRAW"] },
  { key: "MAIN1", label: "Main 1", short: "Main", phases: ["MAIN1"] },
  { key: "COMBAT_BEGIN", label: "Combat", short: "Cmbt", phases: ["COMBAT_BEGIN"] },
  { key: "COMBAT_DECLARE_ATTACKERS", label: "Attack", short: "Atk", phases: ["COMBAT_DECLARE_ATTACKERS"] },
  { key: "COMBAT_DECLARE_BLOCKERS", label: "Block", short: "Blk", phases: ["COMBAT_DECLARE_BLOCKERS"] },
  { key: "COMBAT_DAMAGE", label: "Damage", short: "Dmg", phases: ["COMBAT_FIRST_STRIKE_DAMAGE", "COMBAT_DAMAGE", "COMBAT_END"] },
  { key: "MAIN2", label: "Main 2", short: "Main 2", phases: ["MAIN2"] },
  { key: "END_OF_TURN", label: "End", short: "End", phases: ["END_OF_TURN", "CLEANUP"] },
];

function middleEl() {
  const st = G.state;
  const mineTurn = st.activePlayer === st.you;
  const whose = mineTurn ? "Your turn" : (opp() ? opp().name + "'s turn" : "");
  const stops = stopsFor(mineTurn);
  const strip = h("div", { class: "phase-strip", "data-scroll": "phases" },
    STEPS.map(step => h("span", {
      class: ["step", step.phases.includes(st.phase) && "now", stops.includes(step.key) && "stop"].filter(Boolean).join(" "),
      title: step.label + (stops.includes(step.key) ? " (the game stops here for you)" : ""),
    }, h("span", { class: "long" }, step.label), h("span", { class: "short" }, step.short))));
  const middle = h("div", { class: "middle " + (st.turn ? (mineTurn ? "mine-turn" : "opp-turn") : "") },
    h("div", { class: "turn-row" },
      st.turn
        ? h("span", { class: "phase" }, h("b", {}, whose), h("span", { class: "turn-no" }, `T${st.turn}`),
            h("span", { class: "sr" }, ` · ${(PHASES[st.phase] || st.phase).toLowerCase()}`))
        : h("span", { class: "phase" }, "Starting…"),
      st.turn ? strip : null),
    st.stack.length ? h("div", { class: "stack-row" }, st.stack.map(s => h("button", {
      class: ["stack-item", isTarget("stack", s.id) && "target", isChosen("stack", s.id) && "chosen"].filter(Boolean).join(" "),
      onclick: () => send({ t: "stack", id: s.id }),
    }, s.text))) : null);
  // keep the lit step in view on narrow screens
  requestAnimationFrame(() => {
    const now = middle.querySelector(".step.now");
    if (now && now.offsetParent) {
      const sc = now.parentElement;
      if (now.offsetLeft < sc.scrollLeft || now.offsetLeft + now.offsetWidth > sc.scrollLeft + sc.clientWidth) sc.scrollLeft = now.offsetLeft - 24;
    }
  });
  return middle;
}

// ---------- settings: where the game stops for you, auto-tap ----------
const DEFAULT_STOPS = {
  own: ["MAIN1", "COMBAT_DECLARE_BLOCKERS", "MAIN2"],
  opp: ["COMBAT_DECLARE_ATTACKERS", "COMBAT_DECLARE_BLOCKERS", "END_OF_TURN"],
};
function stops() { return store.get("stops", DEFAULT_STOPS); }
function stopsFor(ownTurn) { return stops()[ownTurn ? "own" : "opp"] || []; }
function sendStops() { send(Object.assign({ t: "stops" }, stops())); }
function autoTap() { return store.get("autoTap", false); }

/** With auto-tap on, a mana cost the game can pay by itself is paid at once (Forge's Auto button). */
function maybeAutoTap() {
  const b = G.buttons;
  if (!autoTap() || !b || !b.ok || !b.ok.enabled || b.ok.label !== "Auto") return;
  const key = JSON.stringify(b) + (G.prompt ? G.prompt.text : "");
  if (G.autoTapped === key) return; // pressed it already for this payment
  G.autoTapped = key;
  setTimeout(() => { if (G.buttons === b) send({ t: "ok" }); }, 120);
}

function settingsEl(onChange) {
  const box = h("div", { class: "settings" });
  const draw = () => {
    box.textContent = "";
    const at = autoTap();
    box.append(
      h("div", { class: "setting-row" },
        h("div", {}, h("b", {}, "Auto-tap mana"), h("div", { class: "muted small" }, "Pay costs with the lands the game picks, without tapping them yourself.")),
        h("button", { class: "switch" + (at ? " on" : ""), role: "switch", "aria-checked": String(at), onclick: () => { store.set("autoTap", !at); draw(); onChange && onChange(); } },
          h("span", { class: "knob" }))),
      h("div", { class: "muted small", style: "margin-top:12px" }, "Stop and give me priority in these steps (with an empty stack; you always get to answer spells):"));
    for (const [side, label] of [["own", "On my turn"], ["opp", "On their turn"]]) {
      const cur = stops();
      box.append(h("div", { class: "stop-row" }, h("span", { class: "stop-label" }, label),
        h("div", { class: "stop-pills" }, STEPS.map(step => {
          const on = (cur[side] || []).includes(step.key);
          return h("button", { class: "step-toggle" + (on ? " on" : ""), onclick: () => {
            const next = Object.assign({}, cur, { [side]: on ? cur[side].filter(k => k !== step.key) : [...(cur[side] || []), step.key] });
            store.set("stops", next); sendStops(); draw(); onChange && onChange();
          } }, step.label);
        }))));
    }
  };
  draw();
  return box;
}

/** Forge's priority prompt repeats the turn and phase the strip above already shows; say what to do instead. */
function promptText() {
  const text = G.prompt ? G.prompt.text : "";
  if (!/^Priority:/.test(text)) return text;
  const st = G.state;
  if (st.stack.length) return "Respond with an instant or ability, or OK to let it resolve.";
  return st.activePlayer === st.you ? "Your move: play a card, or OK to pass." : "Play an instant, or OK to pass.";
}

function actionBar() {
  const b = G.buttons;
  const prompt = h("div", { class: "prompt", onclick: e => e.currentTarget.classList.toggle("open") }, promptText());
  const button = (spec, type, cls) => spec && spec.label
    ? h("button", { class: "btn " + cls, disabled: !spec.enabled, onclick: () => send({ t: type }) }, spec.label) : null;
  return h("div", { class: "action-bar" }, prompt,
    b ? button(b.cancel, "cancel", "") : h("span"),
    b ? button(b.ok, "ok", "primary") : h("span"));
}

function renderGame() {
  const st = G.state;
  const meP = me(), oppP = opp();
  if (!meP || !oppP) return;
  // keep scroll positions across re-renders
  const scroll = {};
  document.querySelectorAll("[data-scroll]").forEach(el => { scroll[el.dataset.scroll] = [el.scrollLeft, el.scrollTop]; });

  const hand = h("div", { class: "hand", "data-scroll": "hand", "data-sort": "hand" }, arranged("hand", meP.hand).map(c => cardEl(c, { size: "small" })));
  const oppField = fieldEl(oppP, "opp"); oppField.dataset.scroll = "opp";
  const myField = fieldEl(meP, "me"); myField.dataset.scroll = "me";
  const game = h("div", { class: "game" },
    playerBar(oppP, "opp"), oppField, middleEl(), myField, playerBar(meP, "me"), hand, actionBar(), offBoardTray(meP, oppP));

  app.textContent = "";
  app.append(game);
  document.querySelectorAll("[data-scroll]").forEach(el => {
    const s = scroll[el.dataset.scroll];
    if (s) { el.scrollLeft = s[0]; el.scrollTop = s[1]; }
  });
  if (preview && !preview.anchor.isConnected) hidePreview();
  const turnKey = st.turn + ":" + st.activePlayer;
  if (st.turn && G.turnKey !== turnKey && !st.gameOver) turnSplash(st.activePlayer === st.you);
  G.turnKey = turnKey;
  renderAsk();
  renderOver();
}

/** Cards the game shows or offers that the board doesn't draw: the opponent's hand when it's revealed
    (a Swamp in the Basic Land Game, Thoughtseize-style effects), or offered cards in a graveyard,
    library or exile. Tapping one picks it, as on the board. */
function offBoardTray(meP, oppP) {
  const onBoard = new Set();
  for (const c of [...meP.hand, ...meP.battlefield, ...meP.command, ...oppP.battlefield, ...oppP.command]) onBoard.add(String(c.id));
  const offered = new Set(((G.selection && G.selection.cards) || []).map(String));
  const all = new Map();
  for (const p of [meP, oppP]) for (const z of ["hand", "graveyard", "exile", "library"]) for (const c of p[z] || []) if (!c.hidden) all.set(String(c.id), c);
  const picks = [...offered].filter(id => !onBoard.has(id) && all.has(id)).map(id => all.get(id));
  const revealed = oppP.hand.filter(c => !c.hidden);
  if (!picks.length && !revealed.length) return null;
  const pickIds = new Set(picks.map(c => String(c.id)));
  const shown = picks.concat(revealed.filter(c => !pickIds.has(String(c.id))));
  const title = picks.length ? (picks.every(c => oppP.hand.includes(c)) ? `Pick from ${oppP.name}'s hand` : "Pick a card")
    : `${oppP.name}'s hand`;
  return h("div", { class: "tray" },
    h("div", { class: "tray-title" }, title, h("span", { class: "muted" }, picks.length ? " · tap a card" : " · revealed")),
    h("div", { class: "tray-cards", "data-scroll": "tray" }, shown.map(c => cardEl(c, {
      cacheTag: "tray", size: "small",
      onTap: pickIds.has(String(c.id)) ? tapCard : zoom, // a card only on show isn't a choice
    }))));
}

/** A short banner across the board when the turn passes. */
function turnSplash(mine) {
  document.querySelectorAll(".turn-splash").forEach(s => s.remove());
  const s = h("div", { class: "turn-splash " + (mine ? "mine" : "theirs") }, h("span", {}, mine ? "Your turn" : `${opp() ? opp().name : "Opponent"}'s turn`));
  document.body.append(s);
  setTimeout(() => s.remove(), 1600);
}

// ---------- sheets ----------
function closeSheets() { document.querySelectorAll(".sheet-backdrop").forEach(s => s.remove()); }
function sheet(content, onBackdrop) {
  const backdrop = h("div", { class: "sheet-backdrop", onclick: e => { if (e.target === backdrop && onBackdrop) onBackdrop(); } },
    h("div", { class: "sheet" }, content));
  document.body.append(backdrop);
  return backdrop;
}

function zoneSheet(title, cards) {
  closeSheets();
  const s = sheet([h("h3", {}, title), h("div", { class: "grid" }, cards.map(c => cardEl(c, { noCache: true, size: "small", onTap: () => tapCard(c) }))),
    h("div", { class: "actions" }, h("button", { class: "btn", onclick: () => s.remove() }, "Close"))], () => s.remove());
}

function menuSheet() {
  closeSheets();
  const log = (G.state.log || []).slice().reverse();
  const s = sheet([
    h("h3", {}, "Settings"),
    settingsEl(() => scheduleRender()),
    h("h3", { style: "margin-top:18px" }, "Game log"),
    h("div", {}, log.length ? log.map(l => h("div", { class: "log-line" }, l)) : h("div", { class: "muted" }, "Nothing yet.")),
    h("div", { class: "actions" },
      h("button", { class: "btn danger", onclick: () => { if (confirm("Concede this game?")) { send({ t: "concede" }); s.remove(); } } }, "Concede"),
      h("button", { class: "btn", onclick: () => go("#/") }, "Home"),
      h("button", { class: "btn", onclick: () => s.remove() }, "Close")),
  ], () => s.remove());
}

// ---------- dialogs the engine waits on ----------
function answer(fields) {
  send(Object.assign({ t: "answer", id: G.ask.id }, fields));
  G.ask = null;
  document.querySelectorAll(".ask-sheet,.ask-pill").forEach(e => e.remove());
}

function renderAsk() {
  document.querySelectorAll(".ask-sheet,.ask-pill").forEach(e => e.remove());
  const q = G.ask;
  if (!q) return;
  if (G.askHidden) {
    const pill = h("button", { class: "btn primary ask-pill", style: "position:fixed;left:12px;right:12px;bottom:calc(84px + var(--safe-bottom));z-index:21",
      onclick: () => { G.askHidden = false; renderAsk(); } }, "Answer: " + (q.title || q.text || "the game is asking"));
    document.body.append(pill);
    return;
  }
  const picks = G.askPicks;
  const head = [h("h3", {}, q.title || "Choose"), (q.text || q.top) ? h("div", { class: "sub" }, [q.text, q.top && q.kind === "order" ? `First pick goes ${q.top.toLowerCase()}.` : ""].filter(Boolean).join("\n")) : null];
  const body = h("div", {});
  const actions = h("div", { class: "actions" });
  const hide = h("button", { class: "btn", onclick: () => { G.askHidden = true; renderAsk(); } }, "See board");

  if (q.reveal) body.append(h("div", { class: "sub" }, q.revealText || "Revealed:"), h("div", { class: "grid" }, q.reveal.map(c => cardEl(c, { noCache: true }))));

  const options = q.options || [];
  const hasCards = options.some(o => o.card);
  let filter = "";
  const list = h("div", {});
  function optionEl(o, i) {
    const on = picks.includes(i);
    const num = q.kind === "order" && on ? h("span", { class: "order-num" }, picks.indexOf(i) + 1) : null;
    const toggle = () => {
      if (q.kind === "choose" && q.max === 1) { picks.length = 0; if (!on) picks.push(i); }
      else if (on) picks.splice(picks.indexOf(i), 1);
      else if (q.kind === "order" || picks.length < (q.max ?? 1)) picks.push(i);
      draw();
    };
    if (o.card && !o.card.hidden) return cardEl(o.card, { noCache: true, on, onTap: toggle, extra: num });
    return h("button", { class: "btn opt" + (on ? " on" : ""), onclick: toggle }, (num ? `${picks.indexOf(i) + 1}. ` : "") + o.label);
  }
  function draw() {
    list.textContent = "";
    const shown = options.map((o, i) => [o, i]).filter(([o]) => !filter || o.label.toLowerCase().includes(filter));
    const els = shown.map(([o, i]) => optionEl(o, i));
    list.append(hasCards ? h("div", { class: "grid" }, els) : h("div", {}, els));
    // "Done" needs a pick; choosing nothing is its own, secondary button so it can't be hit by mistake
    done.disabled = !(picks.length >= Math.max(1, q.min ?? 1) && picks.length <= (q.max ?? 1));
    done.textContent = picks.length > 1 || q.kind === "order" ? `Done (${picks.length})` : "Done";
  }
  const done = h("button", { class: "btn primary" }, "Done");
  const none = h("button", { class: "btn", onclick: () => answer(q.kind === "order" ? { order: [] } : { choice: [] }) }, "None");

  switch (q.kind) {
    case "reveal":
      body.append(h("div", { class: hasCards ? "grid" : "" }, options.map(o => o.card ? cardEl(o.card, { noCache: true }) : h("div", { class: "log-line" }, o.label))));
      actions.append(h("button", { class: "btn primary", onclick: () => answer({}) }, "OK"));
      break;
    case "option":
      body.append(options.map((o, i) => h("button", { class: "btn opt", onclick: () => answer({ choice: [i] }) }, o.label)));
      actions.append(hide);
      break;
    case "text": {
      const input = h("input", { type: q.numeric ? "number" : "text", inputmode: q.numeric ? "numeric" : null, value: q.initial || "" });
      const ok = () => answer({ value: input.value });
      input.addEventListener("keydown", e => { if (e.key === "Enter") ok(); });
      body.append(input);
      actions.append(hide, h("button", { class: "btn primary", onclick: ok }, "Done"));
      setTimeout(() => input.focus(), 50);
      break;
    }
    case "amounts": {
      const amounts = options.map(() => 0);
      if (q.atLeastOne) amounts.fill(1);
      const left = h("div", { class: "sub" });
      const submit = h("button", { class: "btn primary", onclick: () => answer({ amounts }) }, "Done");
      const rows = options.map((o, i) => {
        const val = h("b", {}, amounts[i]);
        const set = d => { amounts[i] = Math.max(q.atLeastOne ? 1 : 0, amounts[i] + d); val.textContent = amounts[i]; update(); };
        return h("div", { class: "stepper" }, h("span", { class: "label" }, o.label),
          h("button", { class: "btn", onclick: () => set(-1) }, "−"), val, h("button", { class: "btn", onclick: () => set(1) }, "+"));
      });
      function update() {
        const rest = q.total - amounts.reduce((a, b) => a + b, 0);
        left.textContent = rest === 0 ? "All assigned." : rest > 0 ? `${rest} left to assign` : `${-rest} too many`;
        submit.disabled = rest !== 0;
      }
      body.append(left, rows);
      actions.append(hide, submit);
      update();
      break;
    }
    default: { // choose, order
      if (options.length > 12) {
        const search = h("input", { type: "text", placeholder: "Filter…" });
        search.addEventListener("input", () => { filter = search.value.toLowerCase(); draw(); });
        body.append(search);
      }
      body.append(list);
      done.onclick = () => answer(q.kind === "order" ? { order: picks.slice() } : { choice: picks.slice() });
      const reset = h("button", { class: "btn", onclick: () => { picks.length = 0; draw(); } }, "Clear");
      actions.append(hide, q.min === 0 ? none : reset, done);
      draw();
    }
  }
  const s = sheet([head, body, actions]);
  s.classList.add("ask-sheet");
}

function renderOver() {
  document.querySelectorAll(".over").forEach(e => e.remove());
  if (!G.over) return;
  const st = G.state;
  const iWon = st.winner && st.winner === (me() && me().name);
  document.body.append(h("div", { class: "over" },
    h("h2", {}, st.winner ? (iWon ? "You won!" : `${st.winner} won`) : "Game over"),
    h("div", { class: "row" },
      h("button", { class: "btn primary", onclick: () => go("#/new") }, "New game"),
      h("button", { class: "btn", onclick: () => { document.querySelectorAll(".over").forEach(e => e.remove()); } }, "See board"),
      h("button", { class: "btn", onclick: () => go("#/") }, "Home"))));
}

route();
