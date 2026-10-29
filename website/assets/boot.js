// Runs before first paint: turns on the hidden-until-revealed states only when the page can reveal them,
// and only for visitors who haven't asked for reduced motion.

// The page's language (docs/translation.md). An English page sends a first-time visitor whose browser prefers one of
// our other languages to that version; choosing a language in the footer is remembered and wins from then on.
(function () {
  var supported = ["en", "fr", "pt", "es", "ar", "hi"];
  var page = document.documentElement.lang || "en";
  var saved = null;
  try { saved = window.localStorage.getItem("hp-lang"); } catch (e) { saved = null; }
  // A language picked in the footer is remembered, so English pages stop sending this visitor elsewhere.
  document.addEventListener("click", function (event) {
    var link = event.target.closest && event.target.closest("a[data-lang]");
    if (link) { try { window.localStorage.setItem("hp-lang", link.getAttribute("data-lang")); } catch (e) { /* private mode */ } }
  });
  if (page !== "en") return;
  var wanted = saved;
  if (!wanted) {
    var preferred = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || "en"];
    for (var i = 0; i < preferred.length && !wanted; i++) {
      var code = String(preferred[i]).slice(0, 2).toLowerCase();
      if (supported.indexOf(code) >= 0) wanted = code;
    }
  }
  if (wanted && wanted !== "en" && supported.indexOf(wanted) >= 0) {
    window.location.replace("/" + wanted + window.location.pathname + window.location.search + window.location.hash);
  }
})();

/** The scripts' words in the page's language: website/i18n/build.py puts them in #strings. English is the key. */
window.T = (function () {
  var strings = null;
  return function (english) {
    if (strings === null) {
      var block = document.getElementById("strings");
      try { strings = block ? JSON.parse(block.textContent) : {}; } catch (e) { strings = {}; }
    }
    return strings[english] || english;
  };
})();

document.documentElement.classList.add("js");
if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) document.documentElement.classList.add("motion");

// Which region's images to show (docs/regional-images.md), from the time zone this device reports: nothing is sent
// anywhere and no location is looked up. ?region=<name> forces one, for ads aimed at a region or for checking.
(function () {
  var MENA = ["Africa/Cairo", "Africa/Casablanca", "Africa/El_Aaiun", "Africa/Algiers", "Africa/Tunis", "Africa/Tripoli",
    "Africa/Khartoum", "Asia/Riyadh", "Asia/Dubai", "Asia/Qatar", "Asia/Bahrain", "Asia/Kuwait", "Asia/Muscat", "Asia/Aden",
    "Asia/Baghdad", "Asia/Amman", "Asia/Beirut", "Asia/Damascus", "Asia/Gaza", "Asia/Hebron", "Asia/Jerusalem", "Asia/Tehran"];
  var SOUTH_ASIA = ["Asia/Kolkata", "Asia/Calcutta", "Asia/Karachi", "Asia/Dhaka", "Asia/Dacca", "Asia/Kathmandu",
    "Asia/Katmandu", "Asia/Colombo", "Asia/Thimphu", "Asia/Thimbu", "Asia/Kabul", "Indian/Maldives"];
  var EAST_ASIA = ["Asia/Tokyo", "Asia/Seoul", "Asia/Pyongyang", "Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking",
    "Asia/Harbin", "Asia/Urumqi", "Asia/Hong_Kong", "Asia/Macau", "Asia/Taipei", "Asia/Ulaanbaatar", "Asia/Singapore",
    "Asia/Kuala_Lumpur", "Asia/Kuching", "Asia/Bangkok", "Asia/Ho_Chi_Minh", "Asia/Saigon", "Asia/Phnom_Penh",
    "Asia/Vientiane", "Asia/Yangon", "Asia/Rangoon", "Asia/Jakarta", "Asia/Pontianak", "Asia/Makassar", "Asia/Jayapura",
    "Asia/Manila", "Asia/Brunei", "Asia/Dili"];
  var LATAM = ["Buenos_Aires", "Cordoba", "Catamarca", "Jujuy", "Mendoza", "Mexico_City", "Monterrey", "Merida", "Matamoros", "Cancun", "Chihuahua", "Ciudad_Juarez", "Ojinaga",
    "Hermosillo", "Mazatlan", "Bahia_Banderas", "Tijuana", "Bogota", "Lima", "Santiago", "Punta_Arenas", "Caracas",
    "La_Paz", "Asuncion", "Montevideo", "Sao_Paulo", "Bahia", "Fortaleza", "Recife", "Belem", "Manaus", "Maceio",
    "Araguaina", "Cuiaba", "Campo_Grande", "Porto_Velho", "Boa_Vista", "Rio_Branco", "Eirunepe", "Santarem", "Noronha",
    "Guayaquil", "Panama", "Costa_Rica", "El_Salvador", "Guatemala", "Tegucigalpa", "Managua", "Belize", "Havana",
    "Santo_Domingo", "Puerto_Rico", "Port-au-Prince", "Guyana", "Paramaribo", "Cayenne"];
  var AFRICA_ISLANDS = ["Atlantic/Cape_Verde", "Indian/Mauritius", "Indian/Antananarivo", "Indian/Comoro", "Indian/Mayotte",
    "Indian/Reunion", "Indian/Mahe"];

  /** The region for an IANA time zone: "africa", "mena", "southasia", "eastasia", "latam", or "default". */
  function regionFor(zone) {
    if (!zone) return "default";
    if (MENA.indexOf(zone) >= 0) return "mena";
    if (zone.indexOf("Africa/") === 0 || AFRICA_ISLANDS.indexOf(zone) >= 0) return "africa";
    if (SOUTH_ASIA.indexOf(zone) >= 0) return "southasia";
    if (EAST_ASIA.indexOf(zone) >= 0) return "eastasia";
    if (zone.indexOf("America/Argentina/") === 0 || (zone.indexOf("America/") === 0 && LATAM.indexOf(zone.slice(8)) >= 0) ||
        zone === "Pacific/Galapagos" || zone === "Pacific/Easter") return "latam";
    return "default";
  }

  var region = "default";
  try {
    var forced = new URLSearchParams(window.location.search).get("region");
    region = forced && /^[a-z]{2,12}$/.test(forced) ? forced : regionFor(Intl.DateTimeFormat().resolvedOptions().timeZone);
  } catch (e) {
    region = "default";
  }
  document.documentElement.setAttribute("data-region", region);
})();
