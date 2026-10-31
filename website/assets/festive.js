// The Christmas look (docs/website.md): when the admin dashboard's Christmas flag is on, GET /api/config answers
// {"christmas": true} and this adds class="christmas" to <html>; site.css does the rest. The answer is kept in
// sessionStorage for the visit, so later pages dress up before first paint without asking again. A failed or slow
// call changes nothing, and without JavaScript the site looks as it always does. The snow only falls for visitors
// who haven't asked for reduced motion (site.css checks prefers-reduced-motion).
(function () {
  var KEY = "hp-christmas";
  var root = document.documentElement;
  var saved = null;
  try { saved = window.sessionStorage.getItem(KEY); } catch (e) { saved = null; }
  if (saved === "1") root.classList.add("christmas");
  if (saved === "1" || saved === "0") return;

  var api = String(window.HEARTPACT_API || "").replace(/\/+$/, "");
  if (!api || !window.fetch) return;
  var controller = window.AbortController ? new AbortController() : null;
  var timer = setTimeout(function () { if (controller) controller.abort(); timer = null; }, 2500);
  window.fetch(api + "/api/config", { credentials: "omit", signal: controller ? controller.signal : undefined })
    .then(function (response) { return response.ok ? response.json() : null; })
    .then(function (config) {
      if (timer === null || !config || typeof config.christmas !== "boolean") return;
      clearTimeout(timer);
      try { window.sessionStorage.setItem(KEY, config.christmas ? "1" : "0"); } catch (e) { /* private mode */ }
      root.classList.toggle("christmas", config.christmas);
    })
    .catch(function () { /* offline, blocked or timed out: stay as we are */ });
})();
