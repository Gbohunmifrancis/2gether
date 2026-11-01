// The page Bachs sends the browser to after paying (docs/website.md). Arriving here proves nothing: the server switches
// Plus on when Bachs' webhook confirms the payment, a few seconds later. So this asks the server for the plan every
// 3 seconds, for up to 90 seconds, with the session the Plus page kept for this tab.
(function () {
  "use strict";
  var EVERY_MS = 3000;
  var FOR_MS = 90000;
  var account = window.HeartpactAccount;
  function $(id) { return document.getElementById(id); }
  var activating = $("activating");
  var hint = $("activating-hint");
  var status = $("thanks-status");
  var timer = null;

  function only(id) {
    activating.hidden = hint.hidden = id !== "activating";
    ["done", "slow", "signed-out"].forEach(function (other) { $(other).hidden = other !== id; });
    var heading = id !== "activating" && $(id).querySelector("h2");
    if (heading) heading.focus();
  }

  function poll() {
    var session = account.load();
    if (!session) { only("signed-out"); return; }
    status.hidden = true;
    only("activating");
    var until = Date.now() + FOR_MS;
    clearTimeout(timer);
    (async function check() {
      var result = await account.request("GET", "/api/billing/entitlement", undefined, session.token);
      if (result.status === 401) { account.clear(); only("signed-out"); return; }
      if (result.status === 200 && result.data && result.data.plan === "plus") { only("done"); return; }
      // Offline, the server busy, or not confirmed yet: keep asking until the time is up.
      if (Date.now() + EVERY_MS > until) {
        only("slow");
        if (result.status === 0) { status.textContent = T("We couldn't reach Heartpact. Check your connection and try again."); status.hidden = false; }
        return;
      }
      timer = setTimeout(check, EVERY_MS);
    })();
  }

  $("check-again").addEventListener("click", poll);
  poll();
})();
