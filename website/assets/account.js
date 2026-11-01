// Signing in on the website (the deletion page and Heartpact Plus) with the same API calls the app makes.
// The deletion page keeps its token in memory only. The Plus pages keep theirs in sessionStorage, so the page Bachs
// sends the browser back to after paying can check the plan: it is gone when the tab closes, and nothing is kept in
// cookies or localStorage. Nothing here is translated: the pages' own scripts word what went wrong.
window.HeartpactAccount = (function () {
  "use strict";
  var api = String(window.HEARTPACT_API || "").replace(/\/+$/, "");
  var KEY = "hp-session";
  /** The session when sessionStorage can't keep it (some private modes): this page only. */
  var memory = null;

  /** {status, data} for a call to the API; status 0 means it couldn't be reached. The API answers in the page's language. */
  async function request(method, path, body, token) {
    var headers = { "Accept-Language": document.documentElement.lang || "en" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (token) headers.Authorization = "Bearer " + token;
    var response;
    try {
      response = await fetch(api + path, { method: method, headers: headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "omit" });
    } catch (e) {
      return { status: 0, data: null };
    }
    var data = null;
    if (response.status !== 204) { try { data = await response.json(); } catch (e) { data = null; } }
    return { status: response.status, data: data };
  }

  /** The error code the API sent ({code, message}), or "". */
  function code(result) { return (result && result.data && typeof result.data.code === "string") ? result.data.code : ""; }

  /** The API's own sentence for a failure, when it sent one. */
  function message(result) { return (result && result.data && typeof result.data.message === "string" && result.data.message) || ""; }

  function signIn(email, password) { return request("POST", "/api/auth/login", { email: email, password: password }); }

  /** A Google ID token from Google Identity Services. Without a date of birth the server never creates an account. */
  function signInWithGoogle(idToken) { return request("POST", "/api/auth/google", { idToken: idToken }); }

  /** {token, id, name, email} kept for this tab, or null. */
  function load() {
    try {
      var saved = JSON.parse(window.sessionStorage.getItem(KEY) || "null");
      if (saved && typeof saved.token === "string") return saved;
    } catch (e) { /* blocked or unreadable: fall back to this page's memory */ }
    return memory;
  }

  /** Keeps a signed-in answer ({accessToken, user}) for this tab and returns the session. */
  function save(auth) {
    var user = auth.user || {};
    var session = { token: auth.accessToken, id: user.id || "", name: user.displayName || "", email: user.email || "" };
    memory = session;
    try { window.sessionStorage.setItem(KEY, JSON.stringify(session)); } catch (e) { /* private mode: this page only */ }
    return session;
  }

  function clear() {
    memory = null;
    try { window.sessionStorage.removeItem(KEY); } catch (e) { /* nothing kept */ }
  }

  return { request: request, code: code, message: message, signIn: signIn, signInWithGoogle: signInWithGoogle, load: load, save: save, clear: clear };
})();
