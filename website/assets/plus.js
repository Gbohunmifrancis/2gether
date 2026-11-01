// Heartpact Plus on the web (docs/website.md, docs/billing.md): sign in, read the plan, and either show it (with Bachs'
// billing page for a card subscription) or send the browser to Bachs' checkout. Plus itself is switched on by the
// server when Bachs tells it the payment went through, never by this page.
(function () {
  "use strict";
  var GOOGLE_CLIENT_ID = "191463812507-sq2e86uilggg5t3hvb1m06uonug3nebe.apps.googleusercontent.com";
  var account = window.HeartpactAccount;
  var lang = document.documentElement.lang || "en";
  function $(id) { return document.getElementById(id); }
  var signin = $("signin");
  var form = $("signin-form");
  var signinStatus = $("signin-status");
  var accountBox = $("account");
  var plusStatus = $("plus-status");
  var loading = $("loading");
  var session = account.load();
  var googleRequested = false;

  function say(element, text, kind) {
    element.textContent = text;
    element.className = "status " + (kind || "");
    element.hidden = false;
    element.focus();
  }

  function fill(text, values) {
    return text.replace(/\{(\w+)\}/g, function (all, name) { return Object.prototype.hasOwnProperty.call(values, name) ? values[name] : all; });
  }

  function date(iso) {
    try { return new Intl.DateTimeFormat(lang, { day: "numeric", month: "long", year: "numeric" }).format(new Date(iso)); } catch (e) { return String(iso).slice(0, 10); }
  }

  var OFFLINE = function () { return T("We couldn't reach Heartpact. Check your connection and try again."); };
  var GENERIC = function () { return T("Something went wrong. Try again, or email support@heartpact.me."); };
  var ENDED = function () { return T("Your session has ended. Sign in again."); };

  // ---------- Signing in ----------

  function showSignIn(message) {
    account.clear();
    session = null;
    accountBox.hidden = true;
    signin.hidden = false;
    if (message) say(signinStatus, message, "error"); else signinStatus.hidden = true;
    loadGoogle();
  }

  /** The answer to a sign-in call, by email or by Google. */
  function signedIn(result, wrongDetails) {
    if (result.status === 200 && result.data && result.data.accessToken) {
      session = account.save(result.data);
      signinStatus.hidden = true;
      form.reset();
      showAccount();
      return;
    }
    if (result.status === 0) say(signinStatus, OFFLINE(), "error");
    else if (result.status === 429) say(signinStatus, T("Too many attempts. Wait a few minutes and try again."), "error");
    else say(signinStatus, wrongDetails(result), "error");
  }

  form.addEventListener("submit", async function (event) {
    event.preventDefault();
    var email = form.email.value.trim();
    var password = form.password.value;
    if (!email || !password) { say(signinStatus, T("Enter your email and password."), "error"); return; }
    var button = form.querySelector("button");
    button.disabled = true;
    say(signinStatus, T("Checking your account…"));
    try {
      signedIn(await account.signIn(email, password), function () {
        return T("Email or password is incorrect. Signed up with Google? Use the Google button below.");
      });
    } finally {
      button.disabled = false;
    }
  });

  async function onGoogle(response) {
    if (!response || !response.credential) return;
    say(signinStatus, T("Checking your account…"));
    signedIn(await account.signInWithGoogle(response.credential), function (result) {
      var code = account.code(result);
      // A Google account with no Heartpact account: making one needs the app (birthday, and agreeing to the terms).
      if (code === "auth.profile_required") return T("There's no Heartpact account for this Google account yet. Sign up in the app first, then come back here.");
      if (code === "auth.google_mismatch") return account.message(result) || T("This email is linked to a different Google account. Use that one, or your email and password.");
      if (result.status === 503) return T("Google sign-in isn't available right now. Use your email and password, or try again later.");
      return account.message(result) || GENERIC();
    });
  }

  /** Google's sign-in button, loaded only when someone needs to sign in, and only on this page. */
  function loadGoogle() {
    if (googleRequested) return;
    googleRequested = true;
    var holder = $("google-button");
    var script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.onload = function () {
      if (!window.google || !google.accounts || !google.accounts.id) return;
      google.accounts.id.initialize({ client_id: GOOGLE_CLIENT_ID, callback: onGoogle, ux_mode: "popup", context: "signin" });
      google.accounts.id.renderButton(holder, { type: "standard", theme: "outline", size: "large", shape: "pill", text: "continue_with", locale: lang, width: Math.min(320, holder.clientWidth || 320) });
    };
    script.onerror = function () {
      googleRequested = false;
      holder.textContent = T("Google sign-in couldn't load. Check your connection, or use your email and password.");
    };
    document.head.appendChild(script);
  }

  $("sign-out").addEventListener("click", function () {
    showSignIn();
    form.email.focus();
  });

  // ---------- The plan ----------

  function hideAll() {
    ["plus-on", "choose", "no-card", "bachs-fix", "partner-pays"].forEach(function (id) { $(id).hidden = true; });
    plusStatus.hidden = true;
  }

  async function showAccount(afterMessage) {
    signin.hidden = true;
    accountBox.hidden = false;
    $("signed-in-as").textContent = fill(T("Signed in as {name}."), { name: session.name || session.email || "" });
    hideAll();
    loading.hidden = false;
    var answers = await Promise.all([
      account.request("GET", "/api/billing/entitlement", undefined, session.token),
      account.request("GET", "/api/couple", undefined, session.token)
    ]);
    loading.hidden = true;
    var entitlement = answers[0];
    if (entitlement.status === 401) { showSignIn(ENDED()); return; }
    if (entitlement.status === 0) { say(plusStatus, OFFLINE(), "error"); return; }
    if (entitlement.status !== 200 || !entitlement.data) { say(plusStatus, account.message(entitlement) || GENERIC(), "error"); return; }
    var couple = answers[1];
    var partner = null;
    if (couple.status === 200 && couple.data && couple.data.members) {
      couple.data.members.forEach(function (member) { if (member.userId !== session.id) partner = member.displayName; });
    }
    render(entitlement.data, partner, couple.status === 404);
    if (afterMessage) say(plusStatus, afterMessage, "error");
  }

  function render(e, partner, unlinked) {
    var sub = e.subscription;
    var mine = !!(sub && sub.purchasedByMe);
    var bachs = !!(sub && sub.store === "bachs");
    if (e.plan === "plus") {
      var covers;
      if (sub && mine) covers = partner ? fill(T("It covers you and {partner}."), { partner: partner }) : T("It covers you both.");
      else if (sub) covers = partner ? fill(T("{partner} pays for Plus. It covers you both."), { partner: partner }) : T("Your partner pays for Plus. It covers you both.");
      else if (e.complimentary && e.complimentaryUntilUtc) covers = fill(T("Plus is on us until {date}. It covers you both."), { date: date(e.complimentaryUntilUtc) });
      else if (e.complimentary) covers = T("Plus is on us. It covers you both.");
      else covers = T("You both have Plus.");
      $("plus-covers").textContent = covers;
      var renews = "";
      if (sub) {
        var until = date(sub.expiresAtUtc);
        if (sub.status === "inGracePeriod") renews = fill(mine ? T("The last payment didn't go through. Update the payment by {date} to keep Plus.") : T("The last payment didn't go through. Plus stays until {date} while it's fixed."), { date: until });
        else if (sub.status === "canceled" || !sub.autoRenewing) renews = fill(T("It won't renew. Plus ends on {date}."), { date: until });
        else renews = fill(T("It renews on {date}."), { date: until });
      }
      $("plus-renews").textContent = renews;
      $("plus-renews").hidden = !renews;
      $("bachs-manage").hidden = !(bachs && mine);
      $("play-manage").hidden = !(sub && mine && !bachs);
      $("partner-manages").hidden = !(sub && !mine);
      $("plus-on").hidden = false;
      return;
    }
    // On Free with my own card subscription (a payment that failed): billing is where it's fixed.
    if (bachs && mine) { $("bachs-fix").hidden = false; return; }
    if (!e.canPurchase) { $("partner-pays").hidden = false; return; }
    if (e.webCheckout !== true) { $("no-card").hidden = false; return; }
    $("no-partner").hidden = !unlinked;
    $("choose").hidden = false;
  }

  function planButtons(disabled) {
    Array.prototype.forEach.call(document.querySelectorAll("[data-plan], .manage"), function (button) { button.disabled = disabled; });
  }

  /** Goes to a Bachs page the server made for this person: only ever an https address. */
  function go(url) {
    if (typeof url === "string" && /^https:\/\//.test(url)) { window.location.href = url; return true; }
    return false;
  }

  async function checkout(plan) {
    planButtons(true);
    say(plusStatus, T("Opening secure checkout…"));
    var result = await account.request("POST", "/api/billing/bachs/checkout", { plan: plan, from: "web" }, session.token);
    if (result.status === 200 && result.data && go(result.data.checkoutUrl)) return;
    planButtons(false);
    var code = account.code(result);
    if (result.status === 401) showSignIn(ENDED());
    else if (result.status === 0) say(plusStatus, OFFLINE(), "error");
    else if (code === "billing.bachs_unavailable") say(plusStatus, T("Card payment isn't available right now. Try again later, or get Plus in the app with Google Play."), "error");
    else if (code === "billing.unknown_plan") say(plusStatus, T("That plan isn't on sale right now. Reload the page and try again."), "error");
    else if (code === "billing.already_plus") showAccount(T("You already have Plus, so there's nothing to pay."));
    else say(plusStatus, account.message(result) || GENERIC(), "error");
  }

  async function portal() {
    planButtons(true);
    say(plusStatus, T("Opening billing…"));
    var result = await account.request("POST", "/api/billing/bachs/portal", undefined, session.token);
    if (result.status === 200 && result.data && go(result.data.url)) return;
    planButtons(false);
    if (result.status === 401) showSignIn(ENDED());
    else if (result.status === 0) say(plusStatus, OFFLINE(), "error");
    else if (account.code(result) === "billing.no_bachs_customer") say(plusStatus, T("We couldn't find a card subscription on your account. If you pay with Google Play, manage it there."), "error");
    else say(plusStatus, account.message(result) || GENERIC(), "error");
  }

  Array.prototype.forEach.call(document.querySelectorAll("[data-plan]"), function (button) {
    button.addEventListener("click", function () { checkout(button.getAttribute("data-plan")); });
  });
  Array.prototype.forEach.call(document.querySelectorAll(".manage"), function (button) {
    button.addEventListener("click", portal);
  });

  // Back from Bachs with the browser's back button: the page may come from the cache with its buttons still off.
  window.addEventListener("pageshow", function (event) {
    if (event.persisted && session) { planButtons(false); showAccount(); }
  });

  if (session) showAccount(); else showSignIn();
})();
