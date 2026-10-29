// Deleting an account without the app: sign in, then the same call the app makes (docs/account-deletion.md).
// The token lives only in this page's memory and is gone when the tab closes.
(function () {
  "use strict";
  var api = (window.HEARTPACT_API || "").replace(/\/+$/, "");
  var form = document.getElementById("delete-form");
  var status = document.getElementById("delete-status");
  var renewBox = document.getElementById("renew-box");
  var renewAck = document.getElementById("renew-ack");
  var button = form.querySelector("button");

  function show(text, kind) {
    status.textContent = text;
    status.className = "status " + (kind || "");
    status.hidden = false;
    status.focus();
  }

  async function call(path, body, token) {
    // The API answers in the page's language.
    var headers = { "Content-Type": "application/json", "Accept-Language": document.documentElement.lang || "en" };
    if (token) headers.Authorization = "Bearer " + token;
    var response = await fetch(api + path, { method: "POST", headers: headers, body: JSON.stringify(body), credentials: "omit" });
    var data = null;
    if (response.status !== 204) { try { data = await response.json(); } catch (e) { data = null; } }
    return { status: response.status, data: data };
  }

  form.addEventListener("submit", async function (event) {
    event.preventDefault();
    var email = form.email.value.trim();
    var password = form.password.value;
    if (!form.sure.checked) { show(T("Tick the box to confirm you understand this can't be undone."), "error"); return; }
    button.disabled = true;
    show(T("Checking your account…"));
    try {
      var login = await call("/api/auth/login", { email: email, password: password });
      if (login.status === 429) { show(T("Too many attempts. Wait a few minutes and try again."), "error"); return; }
      if (login.status !== 200 || !login.data || !login.data.accessToken) {
        show(T("Email or password is incorrect. If you signed up with Google, email support@heartpact.app from that address and we\u2019ll delete it."), "error");
        return;
      }
      var result = await call("/api/account/delete", {
        password: password,
        confirmEmail: null,
        acknowledgeActiveSubscription: !renewBox.hidden && renewAck.checked
      }, login.data.accessToken);
      if (result.status === 204) {
        form.hidden = true;
        show(T("Your account and everything in it has been deleted. If you were linked, your partner has been unlinked."), "done");
        return;
      }
      if (result.status === 409 && result.data && result.data.code === "account.subscription_renews") {
        renewBox.hidden = false;
        show(T("You have a Heartpact Plus subscription that will keep renewing. Cancel it in Google Play first, or tick the box below and delete anyway."), "error");
        return;
      }
      show((result.data && result.data.message) || T("Something went wrong. Try again, or email support@heartpact.app."), "error");
    } catch (e) {
      show(T("We couldn't reach Heartpact. Check your connection and try again."), "error");
    } finally {
      button.disabled = false;
    }
  });
})();
