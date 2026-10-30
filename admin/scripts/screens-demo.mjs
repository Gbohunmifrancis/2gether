// Screenshots of every page in demo mode (no backend): light and dark, desk (1440) and phone (390) widths, plus a few
// frames caught mid-animation.
//
//   npm run screens:demo            # starts its own demo server on a free port from 5174
//   ADMIN_URL=http://localhost:5174 npm run screens:demo   # or uses one already running (npm run dev:demo)
//
// Writes PNGs to screens/demo/. CHROMIUM_PATH picks the browser; ONLY=overview,live limits the pages.
import { existsSync, mkdirSync } from "node:fs";
import { chromium } from "playwright-core";
import { createServer } from "vite";

const OUT = "screens/demo";
mkdirSync(OUT, { recursive: true });

let server = null;
let base = process.env.ADMIN_URL?.replace(/\/$/, "");
if (!base) {
  server = await createServer({ mode: "demo", server: { port: Number(process.env.PORT ?? 5174), strictPort: false }, logLevel: "warn" });
  await server.listen();
  base = server.resolvedUrls.local[0].replace(/\/$/, "");
}
console.log(`demo at ${base}`);

const executablePath =
  process.env.CHROMIUM_PATH ??
  [
    "/opt/pw-browsers/chromium-1243/chrome-linux64/chrome",
    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
    "/opt/pw-browsers/chromium/chrome-linux/chrome",
  ].find((path) => existsSync(path));
const browser = await chromium.launch({ executablePath });

const PAGES = [
  ["overview", "/?days=30", "Overview"],
  ["live", "/live", "Live"],
  ["alerts", "/alerts", "Alerts"],
  ["engagement", "/engagement?days=30", "Engagement"],
  ["funnel", "/funnel?days=30", "Funnel"],
  ["retention", "/retention?weeks=12", "Retention"],
  ["rhythm", "/rhythm?days=30", "Rhythm"],
  ["dares", "/dares?days=30", "Dares"],
  ["games", "/games?days=30", "Games"],
  ["growth", "/growth?days=90", "Growth"],
  ["revenue", "/revenue?days=90", "Revenue"],
  ["world", "/world", "World"],
  ["couples", "/couples?days=30", "Top couples"],
  ["users", "/users", "Users"],
  ["user", null, null],
  ["payments", "/payments", "Payments"],
  ["audit", "/audit", "Audit log"],
  ["health", "/health", "Health"],
  ["features", "/features", "Features"],
  ["system", "/system", "System"],
];
const only = process.env.ONLY?.split(",");

// Sign in once through the demo button, then hand the session to every other browser context.
const first = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const signIn = await first.newPage();
await signIn.goto(`${base}/sign-in`);
await signIn.getByRole("heading", { name: "Sign in" }).waitFor();
await signIn.waitForTimeout(1200);
await signIn.screenshot({ path: `${OUT}/00-sign-in.png` });
await signIn.getByRole("button", { name: "Try the demo" }).click();
await signIn.getByRole("heading", { name: "Overview", level: 1 }).waitFor();
const session = await signIn.evaluate(() => sessionStorage.getItem("heartpact.admin.session"));
const userId = await signIn.evaluate(async () => {
  const response = await fetch("/api/admin/users?page=2&pageSize=600");
  const page = await response.json();
  const old = (user) => Date.now() - Date.parse(user.createdAtUtc) > 120 * 86_400_000;
  const recent = (user) => user.lastActiveAtUtc && Date.now() - Date.parse(user.lastActiveAtUtc) < 3_600_000;
  return (page.items.find((user) => user.coupleStatus === "active" && old(user) && recent(user)) ?? page.items[0]).id;
});
await first.close();

async function context(width, scheme) {
  const narrow = width < 600;
  const ctx = await browser.newContext({
    viewport: { width, height: narrow ? 844 : 900 },
    deviceScaleFactor: narrow ? 2 : 1,
    isMobile: narrow,
    hasTouch: narrow,
    colorScheme: scheme,
  });
  await ctx.addInitScript((value) => {
    try {
      sessionStorage.setItem("heartpact.admin.session", value);
    } catch {
      // about:blank has no storage.
    }
  }, session);
  ctx.on("page", (page) => {
    page.on("pageerror", (error) => console.error("page error:", error.message));
    page.on("console", (message) => message.type() === "error" && console.error("console:", message.text()));
  });
  return ctx;
}

async function shoot(page, name, path, heading) {
  await page.goto(`${base}${path}`);
  if (heading) await page.getByRole("heading", { name: heading, level: 1 }).waitFor();
  else await page.getByRole("heading", { name: "Journey", level: 2 }).waitFor();
  await page.waitForLoadState("networkidle").catch(() => {});
  // Let the entrance animations land (charts drawing on, numbers counting up).
  await page.waitForTimeout(2200);
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });
  console.log(`${OUT}/${name}.png`);
}

for (const [width, label] of [
  [1440, "desk"],
  [390, "phone"],
]) {
  for (const scheme of ["light", "dark"]) {
    const ctx = await context(width, scheme);
    const page = await ctx.newPage();
    for (const [name, path, heading] of PAGES) {
      if (only && !only.includes(name)) continue;
      await shoot(page, `${name}-${label}-${scheme}`, path ?? `/users/${userId}`, heading);
    }
    await ctx.close();
  }
}

// Moments, caught mid-animation, and the dialogs.
if (!only || only.includes("moments")) {
  const ctx = await context(1440, "light");
  const page = await ctx.newPage();
  await page.goto(`${base}/live`);
  await page.getByRole("heading", { name: "Live", level: 1 }).waitFor();
  await page.evaluate(() => globalThis.heartpactDemo.pause());
  await page.waitForTimeout(5000);
  for (const [kind, at] of [
    ["coupleLinked", 380],
    ["plusStarted", 420],
    ["dareCompleted", 300],
    ["alert", 650],
  ]) {
    await page.evaluate((k) => globalThis.heartpactDemo.emit(k), kind);
    await page.waitForTimeout(at);
    await page.screenshot({ path: `${OUT}/moment-${kind}.png` });
    console.log(`${OUT}/moment-${kind}.png`);
    await page.waitForTimeout(1600);
  }
  await page.keyboard.press("Control+k");
  await page.getByRole("combobox").fill("maya");
  await page.waitForTimeout(900);
  await page.screenshot({ path: `${OUT}/palette.png` });
  await page.keyboard.press("Escape");

  await page.goto(`${base}/users/${userId}`);
  await page.getByRole("heading", { name: "Journey", level: 2 }).waitFor();
  await page.getByRole("button", { name: "Disable" }).click();
  await page.getByLabel(/Reason/).fill("Reported for spam");
  await page.getByRole("button", { name: "Disable account" }).click();
  await page.getByLabel("Authenticator code").waitFor();
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${OUT}/step-up.png` });
  await page.getByLabel("Authenticator code").fill("000000");
  await page.waitForTimeout(900);
  await page.screenshot({ path: `${OUT}/step-up-wrong.png` });
  await page.getByLabel("Authenticator code").fill("123456");
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${OUT}/step-up-open.png` });
  console.log(`${OUT}/step-up*.png`);
  await ctx.close();
}

await browser.close();
await server?.close();
