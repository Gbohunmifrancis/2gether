// Heartpact site motion: reveals on entering the viewport, a count-up, and a few scroll-linked effects.
// Nothing here is needed to read the page; it only runs with .motion (see boot.js).
(function () {
  "use strict";
  var root = document.documentElement;
  var motion = root.classList.contains("motion");

  // The slim bar appears once the hero is gone, so the Play button is always one tap away.
  var hero = document.querySelector(".hero");
  var topbar = document.querySelector(".topbar");
  if (hero && topbar && "IntersectionObserver" in window) {
    new IntersectionObserver(function (entries) {
      var hidden = !entries[0].isIntersecting;
      topbar.classList.toggle("shown", hidden);
      topbar.setAttribute("aria-hidden", hidden ? "false" : "true");
      topbar.inert = !hidden;
    }, { rootMargin: "-64px 0px 0px 0px" }).observe(hero);
  }

  if (!motion || !("IntersectionObserver" in window)) {
    countAll(true);
    return;
  }

  // Reveal once, a little before the element is fully in view.
  var reveal = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("in");
      if (entry.target.matches(".counter")) count(entry.target, false);
      reveal.unobserve(entry.target);
    });
  }, { rootMargin: "0px 0px -12% 0px", threshold: 0.12 });
  document.querySelectorAll("[data-reveal], [data-stagger], .counter, .stage").forEach(function (el) { reveal.observe(el); });

  // The statement lights up word by word as it scrolls through the screen.
  var wordBlocks = [];
  document.querySelectorAll("[data-words]").forEach(function (el) {
    var words = el.textContent.trim().split(/\s+/);
    el.setAttribute("aria-label", el.textContent.trim());
    el.textContent = "";
    var spans = words.map(function (word, i) {
      var span = document.createElement("span");
      span.className = "w";
      span.setAttribute("aria-hidden", "true");
      span.textContent = word;
      el.appendChild(span);
      if (i < words.length - 1) el.appendChild(document.createTextNode(" "));
      return span;
    });
    wordBlocks.push({ el: el, spans: spans });
  });

  var parallax = Array.prototype.slice.call(document.querySelectorAll("[data-parallax]"));
  var progress = Array.prototype.slice.call(document.querySelectorAll("[data-progress]"));
  var queued = false;

  function clamp(v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

  function frame() {
    queued = false;
    var vh = window.innerHeight;
    wordBlocks.forEach(function (block) {
      var r = block.el.getBoundingClientRect();
      var p = clamp((vh * 0.85 - r.top) / (r.height + vh * 0.3));
      var lit = Math.round(p * block.spans.length);
      block.spans.forEach(function (span, i) { span.classList.toggle("lit", i < lit); });
    });
    parallax.forEach(function (el) {
      var r = el.getBoundingClientRect();
      if (r.bottom < -200 || r.top > vh + 200) return;
      var speed = parseFloat(el.getAttribute("data-parallax")) || 0.1;
      el.style.setProperty("--py", ((r.top + r.height / 2 - vh / 2) * -speed).toFixed(1) + "px");
    });
    progress.forEach(function (el) {
      var r = el.getBoundingClientRect();
      el.style.setProperty("--p", clamp((vh - r.top) / (vh * 0.65)).toFixed(3));
    });
  }
  function request() { if (!queued) { queued = true; window.requestAnimationFrame(frame); } }
  window.addEventListener("scroll", request, { passive: true });
  window.addEventListener("resize", request);
  frame();

  // The rep counter counts up like the camera does, filling a bar per rep or so.
  function count(card, instant) {
    var number = card.querySelector("[data-count]");
    if (!number) return;
    var target = parseInt(number.getAttribute("data-count"), 10) || 0;
    var bars = card.querySelectorAll(".bars i");
    var start = null;
    var duration = instant ? 0 : 2200;
    function step(t) {
      if (start === null) start = t;
      var p = duration ? clamp((t - start) / duration) : 1;
      var eased = 1 - Math.pow(1 - p, 3);
      var value = Math.round(eased * target);
      number.textContent = value;
      var filled = Math.round((value / target) * bars.length);
      bars.forEach(function (bar, i) { bar.classList.toggle("done", i < filled); });
      if (p < 1) window.requestAnimationFrame(step);
    }
    window.requestAnimationFrame(step);
  }
  function countAll(instant) { document.querySelectorAll(".counter").forEach(function (card) { count(card, instant); }); }
})();
