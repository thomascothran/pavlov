// Shared behaviour for the Pavlov Explorer mock-ups.
//
// Each candidate page loads data.js, then this file, then defines
// PX.candidate = { id, render(model) -> html, after?(model) } and calls PX.start().
// The position lives in the page address: #/<program-id>/<choice>.<choice>...
// where each <choice> is the index of the event chosen at that step.
(function () {
  const D = window.EXPLORER_DATA;
  const CANDIDATES = [
    ["a-trail.html", "A · Trail"],
    ["b-columns.html", "B · Columns"],
    ["c-map.html", "C · Map"],
  ];
  const ROLE_ORDER = ["rule", "environment", "safety check"];
  const ui = { bthreads: false, open: {}, code: null, toast: null };

  D.programs.forEach((p) => {
    p.out = {};
    p.edges.forEach((e) => (p.out[e.from] = p.out[e.from] || []).push(e));
    p.role = {};
    p.bthreads.forEach((b) => (p.role[b.name] = b.role));
  });

  const esc = (s) =>
    String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const out = (p, node) => p.out[node] || [];
  const hashFor = (p, idx) => "#/" + p.id + "/" + idx.join(".");
  const roleClass = (role) => (role === "safety check" ? "safety" : role);
  const pill = (role) => `<span class="pill ${roleClass(role)}">${esc(role)}</span>`;

  function eventData(edge) {
    return edge.summary;
  }
  // The fields of a map event, one per row. Compact: each value on one line,
  // cut short if long. Full: nested values pretty-printed.
  function fieldsHtml(edge, full) {
    if (!edge.fields.length) return "";
    return `<div class="fields ${full ? "full" : ""}">` + edge.fields.map((f) =>
      `<span class="k">${esc(f.k)}</span><span class="v ${f.nested ? "nested" : ""}">${esc(full ? f.pretty : f.v)}</span>`).join("") + `</div>`;
  }
  function eventHtml(edge) {
    const data = eventData(edge);
    return `<span class="ev-type">${esc(edge.type)}</span>` + (data ? `<span class="ev-data">${esc(data)}</span>` : "");
  }
  function requestedBy(p, edge) {
    return edge.requestedBy.map((n) => `${pill(p.role[n] || "rule")} <span class="mono">${nameHtml(n)}</span>`).join(" ");
  }
  function nameHtml(name) {
    const i = name.lastIndexOf("/");
    return i < 0 ? esc(name) : `<span class="ns">${esc(name.slice(0, i + 1))}</span>${esc(name.slice(i + 1))}`;
  }

  // ---- model: everything the page needs to know about the current position
  function model() {
    const m = location.hash.match(/^#\/([^/]+)\/?([\d.]*)$/);
    if (!m) return { view: "catalogue" };
    const p = D.programs.find((x) => x.id === m[1]);
    if (!p) return { view: "catalogue", notice: `The program "${m[1]}" is not available. Choose one of the programs below.` };
    const wanted = m[2] ? m[2].split(".").filter((x) => x !== "").map(Number) : [];
    let node = p.root;
    const steps = [];
    let stale = false;
    for (const i of wanted) {
      const options = out(p, node);
      if (!options[i]) { stale = true; break; }
      steps.push({ edge: options[i], from: node, to: options[i].to, i, options });
      node = options[i].to;
    }
    const idx = steps.map((s) => s.i);
    const visited = [p.root].concat(steps.map((s) => s.to));
    const last = steps[steps.length - 1];
    const choices = out(p, node);
    const prev = last ? p.nodes[last.from] : null;
    const now = p.nodes[node];
    const changed = {};
    if (prev) Object.keys(now).forEach((n) => { if (JSON.stringify(now[n]) !== JSON.stringify(prev[n])) changed[n] = true; });

    const banners = [];
    if (stale) banners.push({ kind: "notice", title: "This link no longer matches the program", text: "You have been placed at the last step of the link that is still possible." });
    if (last && last.edge.violation)
      banners.push({ kind: "violation", title: "A rule has been violated", text: "Reported by the safety check " + last.edge.requestedBy.join(", ") + "." });
    if (last && last.edge.terminal)
      banners.push({ kind: "finished", title: "The program has finished", text: last.edge.type + " ended the program. There are no further choices." });
    else if (!choices.length) {
      if (p.truncated) banners.push({ kind: "edge", title: "The program was only explored this far", text: "Nothing is known beyond this point." });
      else if (p.checkDeadlock) banners.push({ kind: "stuck", title: "The program is stuck here", text: "Nothing can happen next, and the program has not finished." });
      else banners.push({ kind: "nothing", title: "Nothing more can happen here", text: "There are no further choices." });
    }
    const seenAt = visited.indexOf(node);
    if (seenAt < steps.length)
      banners.push({ kind: "revisit", title: "You have been here before", text: "The program is in the same situation it was in ", seenAt });

    return { view: "program", p, steps, idx, node, choices, last, changed, banners, now, visited };
  }

  // hash for: keep the first `keep` steps, then optionally take choice `i`
  const navTo = (m, keep, i) => hashFor(m.p, m.idx.slice(0, keep).concat(i === undefined ? [] : [i]));
  const stepName = (m, k) => (k === 0 ? "the start" : "step " + k + " (" + m.steps[k - 1].edge.type + ")");

  function bannersHtml(m) {
    return m.banners.map((b) => {
      const extra = b.seenAt === undefined ? "" : `at <button class="link" data-nav="${navTo(m, b.seenAt)}">${esc(stepName(m, b.seenAt))}</button>.`;
      return `<div class="banner ${b.kind}"><h3>${esc(b.title)}</h3><p>${esc(b.text)}${extra}</p></div>`;
    }).join("");
  }

  // A choice button with an optional "show the whole event" toggle.
  function choiceHtml(m, edge, hash, key, extraHtml) {
    const open = ui.open["ev:" + key];
    return `<div class="choice-wrap">
      <button class="choice" data-nav="${hash}"><span class="ev-type">${esc(edge.type)}</span>${fieldsHtml(edge, false)}
        <div class="by">requested by ${requestedBy(m.p, edge)}</div>${extraHtml || ""}</button>
      <button class="more" data-toggle="ev:${esc(key)}">${open ? "hide" : "details"}</button>
      ${open ? `<div class="edn">${esc(edge.pretty)}</div>` : ""}</div>`;
  }

  // ---- bthread drawer
  function drawerHtml(m) {
    const chips = (xs, cls) => xs.length ? xs.map((x) => `<span class="chip ${cls}">${esc(x)}</span>`).join("") : `<span class="dim">nothing</span>`;
    const groups = ROLE_ORDER.map((role) => {
      const items = m.p.bthreads.filter((b) => b.role === role).map((b) => {
        const s = m.now[b.name] || { request: [], waitOn: [], block: [], state: "nil" };
        const finished = !s.request.length && !s.waitOn.length && !s.block.length;
        const open = ui.open["bt:" + b.name];
        const sum = finished ? `<span class="pill done">finished</span>` :
          [s.request.length && `requesting ${s.request.length}`, s.waitOn.length && `waiting for ${s.waitOn.length}`, s.block.length && `blocking ${s.block.length}`].filter(Boolean).join(" · ");
        return `<div class="bt ${m.changed[b.name] ? "changed" : ""}">
          <button data-toggle="bt:${esc(b.name)}"><div class="bt-name">${nameHtml(b.name)}</div>
            <div class="bt-sum">${m.changed[b.name] ? `<span class="pill changed">changed by last event</span>` : ""}${sum}</div></button>
          ${open ? `<dl class="bt-body">
            <dt class="label">Requesting</dt><dd>${chips(s.request, "req")}</dd>
            <dt class="label">Waiting for</dt><dd>${chips(s.waitOn, "wait")}</dd>
            <dt class="label">Blocking</dt><dd>${chips(s.block, "block")}</dd>
            <dt class="label">Internal state</dt><dd><span class="chip state">${esc(s.state)}</span></dd>
            ${b.source ? `<dd style="margin-top:10px"><button class="btn" data-code="${esc(b.name)}">View code</button></dd>` : ""}
          </dl>` : ""}</div>`;
      }).join("");
      return items ? `<div class="group">${pill(role)}</div>${items}` : "";
    }).join("");
    return `<aside class="drawer"><h2>Bthreads <button class="btn" style="margin-left:auto" data-ui="bthreads">Close</button></h2>
      <p class="muted" style="margin:0">What each part of the program is asking for, waiting for and preventing at your position.</p>${groups}</aside>`;
  }

  function codeHtml(m) {
    const b = m.p.bthreads.find((x) => x.name === ui.code);
    return `<div class="modal" data-ui="close-code"><div class="panel bracket" data-stop>
      <h2><span class="mono" style="text-transform:none;letter-spacing:0;color:var(--text)">${nameHtml(b.name)}</span> ${pill(b.role)} <button class="btn" style="margin-left:auto" data-ui="close-code">Close</button></h2>
      <pre>${esc(b.source)}</pre></div></div>`;
  }

  function topbar(m) {
    const cand = PX.candidate.id;
    const tabs = CANDIDATES.map(([file, name]) => `<a class="${file.startsWith(cand) ? "on" : ""}" href="${file}${location.hash}">${name}</a>`).join("");
    const inProgram = m.view === "program";
    return `<div class="mock-note">MOCK-UP · canned data, not wired to a running program · <a href="index.html">back to the candidate list</a></div>
    <header class="topbar">
      <a class="brand" href="#">PAVLOV_EXPLORER</a>
      <div class="crumbs"><a href="#">Catalogue</a>${inProgram ? ` / <b>${esc(m.p.name)}</b>` : ""}</div>
      <nav class="tabs">${tabs}</nav>
      ${inProgram ? `<div class="tools">
        <button class="btn" data-nav="${navTo(m, Math.max(0, m.steps.length - 1))}" ${m.steps.length ? "" : "disabled"}>◀ Step back</button>
        <button class="btn" data-nav="${navTo(m, 0)}" ${m.steps.length ? "" : "disabled"}>Start over</button>
        <button class="btn" data-ui="copy">Copy link</button>
        <button class="btn ${ui.bthreads ? "on" : ""}" data-ui="bthreads">Bthreads</button>
      </div>` : ""}
    </header>`;
  }

  function catalogueHtml(m) {
    const cards = D.programs.map((p) => `<div class="card"><h3>${esc(p.name)}</h3><p>${esc(p.description)}</p>
      <button class="btn primary" style="align-self:flex-start" data-nav="${hashFor(p, [])}">Explore</button></div>`).join("");
    return `<div class="catalogue">${m.notice ? `<div class="banner notice"><h3>Program not available</h3><p>${esc(m.notice)}</p></div>` : ""}
      <div class="label" style="color:var(--green)">■ Programs on offer</div>
      <h1>CHOOSE A <span>PROGRAM</span></h1>
      <p class="muted">Pick a program to walk through it one event at a time.</p>
      <div class="cards">${cards || `<p class="muted">There are no programs to explore.</p>`}</div></div>`;
  }

  function render() {
    const m = model();
    const body = m.view === "catalogue" ? catalogueHtml(m)
      : `<div class="frame"><main class="stage">${PX.candidate.render(m)}</main>${ui.bthreads ? drawerHtml(m) : ""}</div>${ui.code ? codeHtml(m) : ""}`;
    const drawer = document.querySelector(".drawer");
    const keep = drawer ? drawer.scrollTop : 0;
    document.getElementById("app").innerHTML = topbar(m) + body + (ui.toast ? `<div class="toast">${esc(ui.toast)}</div>` : "");
    const d2 = document.querySelector(".drawer");
    if (d2) d2.scrollTop = keep;
    if (m.view === "program" && PX.candidate.after) PX.candidate.after(m);
  }

  document.addEventListener("click", (ev) => {
    const t = ev.target.closest("[data-nav],[data-toggle],[data-ui],[data-code],[data-stop]");
    if (!t) return;
    if (t.dataset.stop !== undefined) return;
    if (t.dataset.nav !== undefined) {
      const h = t.dataset.nav;
      if (location.hash === h) render(); else location.hash = h;
    } else if (t.dataset.toggle) {
      ui.open[t.dataset.toggle] = !PX.isOpen(t.dataset.toggle);
      render();
    } else if (t.dataset.code) {
      ui.code = t.dataset.code; render();
    } else if (t.dataset.ui === "bthreads") {
      ui.bthreads = !ui.bthreads; render();
    } else if (t.dataset.ui === "close-code") {
      ui.code = null; render();
    } else if (t.dataset.ui === "copy") {
      if (navigator.clipboard) navigator.clipboard.writeText(location.href).catch(() => {});
      ui.toast = "Link copied"; render();
      setTimeout(() => { ui.toast = null; render(); }, 1400);
    }
  });
  window.addEventListener("hashchange", render);

  window.PX = {
    ui, esc, out, eventHtml, eventData, fieldsHtml, requestedBy, nameHtml, pill, navTo, hashFor, bannersHtml, choiceHtml, stepName,
    defaults: {},
    isOpen: (key) => (key in ui.open ? ui.open[key] : !!PX.defaults[key]),
    start: render,
  };
})();
