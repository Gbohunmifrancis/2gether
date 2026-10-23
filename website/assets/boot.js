// Runs before first paint: turns on the hidden-until-revealed states only when the page can reveal them,
// and only for visitors who haven't asked for reduced motion.
document.documentElement.classList.add("js");
if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) document.documentElement.classList.add("motion");
