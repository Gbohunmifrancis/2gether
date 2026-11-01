// An invite link (heartpact.me/invite/{token}) opened where the app isn't installed, or isn't set to open such links:
// open the app if it's there, otherwise Google Play, and say who sent it (docs/pairing.md).
(function () {
  var T = window.T || function (s) { return s; };
  var match = location.pathname.match(/\/invite\/([A-Za-z0-9_-]{8,128})\/?$/);
  var token = match ? match[1] : null;
  var status = document.getElementById("invite-status");
  if (!token) return;

  var link = "https://heartpact.me/invite/" + token;
  var store = "https://play.google.com/store/apps/details?id=app.heartpact&referrer=" + encodeURIComponent("invite=" + token);
  document.getElementById("get-app").href = store;
  var open = document.getElementById("open-app");
  // Android: an intent link opens the app on this invite when it's installed, and Google Play when it isn't.
  if (/Android/i.test(navigator.userAgent)) {
    open.href = "intent://heartpact.me/invite/" + token + "#Intent;scheme=https;package=app.heartpact;S.browser_fallback_url=" + encodeURIComponent(store) + ";end";
  } else {
    open.href = link;
    open.hidden = true;
  }

  fetch((window.HEARTPACT_API || "https://api.heartpact.me") + "/api/couple/invites/preview", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: token }),
  }).then(function (r) {
    if (r.status === 404) {
      status.textContent = T("This invitation has expired or was already used. Ask your partner to send a new one from the app.");
      status.hidden = false;
      return null;
    }
    return r.ok ? r.json() : null;
  }).then(function (preview) {
    if (!preview || !preview.inviterFirstName) return;
    document.getElementById("invite-title").textContent = T("{name} invited you to Heartpact").replace("{name}", preview.inviterFirstName);
  }).catch(function () { /* offline: the page still works without the name */ });
})();
