// Small conveniences for the Pavlov Explorer pages. Navigation itself is
// plain links; nothing here is needed to move through a program.
(function () {
  // Keep opened bthreads open from one position to the next.
  var KEY = "pavlov-explorer:open", open;
  try { open = JSON.parse(sessionStorage.getItem(KEY)) || {}; } catch (e) { open = {}; }
  document.querySelectorAll("details[data-keep]").forEach(function (d) {
    if (open[d.dataset.keep]) d.open = true;
    d.addEventListener("toggle", function () {
      open[d.dataset.keep] = d.open;
      try { sessionStorage.setItem(KEY, JSON.stringify(open)); } catch (e) {}
    });
  });

  // Keep the bthread view scrolled where it was.
  var drawer = document.querySelector(".drawer"), SCROLL = "pavlov-explorer:drawer-scroll";
  if (drawer) {
    drawer.scrollTop = Number(sessionStorage.getItem(SCROLL)) || 0;
    drawer.addEventListener("scroll", function () { sessionStorage.setItem(SCROLL, drawer.scrollTop); });
  }

  // Keep the current step of a long trace in view.
  var here = document.querySelector(".trail > li.here");
  if (here) here.scrollIntoView({ block: "nearest" });

  document.addEventListener("click", function (ev) {
    var t = ev.target.closest("[data-dialog],[data-close],[data-copy]");
    if (!t) {
      if (ev.target.tagName === "DIALOG") ev.target.close();
      return;
    }
    if (t.dataset.dialog) {
      document.getElementById(t.dataset.dialog).showModal();
    } else if (t.dataset.close !== undefined) {
      t.closest("dialog").close();
    } else if (t.dataset.copy !== undefined) {
      if (navigator.clipboard) navigator.clipboard.writeText(location.href).catch(function () {});
      var toast = document.createElement("div");
      toast.className = "toast";
      toast.textContent = "Link copied";
      document.body.appendChild(toast);
      setTimeout(function () { toast.remove(); }, 1400);
    }
  });
})();
