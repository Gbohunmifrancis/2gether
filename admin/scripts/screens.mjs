// Screenshots of every page, signed in through the real sign-in form, for review.
//
//   ADMIN_EMAIL=ops@heartpact.me ADMIN_PASSWORD=... ADMIN_TOTP_SECRET=... npm run screens
//
// Needs the dashboard running (npm run dev, default http://localhost:5173) against an API with data
// (scripts/seed.py). Writes PNGs to screens/. CHROMIUM_PATH picks the browser binary; SCREEN_USER whose
// profile to open.
import { createHmac } from "node:crypto";
import { existsSync, mkdirSync } from "node:fs";
import { chromium } from "playwright-core";

const BASE = (process.env.ADMIN_URL ?? "http://localhost:5173").replace(/\/$/, "");
const EMAIL = process.env.ADMIN_EMAIL ?? "ops@heartpact.me";
const PASSWORD = process.env.ADMIN_PASSWORD;
const SECRET = process.env.ADMIN_TOTP_SECRET;
if (!PASSWORD || !SECRET) {
  console.error("Set ADMIN_PASSWORD and ADMIN_TOTP_SECRET.");
  process.exit(2);
}

function base32(text) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of text.replace(/=+$/, "").toUpperCase()) bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
  const bytes = [];
  for (let index = 0; index + 8 <= bits.length; index += 8) bytes.push(Number.parseInt(bits.slice(index, index + 8), 2));
  return Buffer.from(bytes);
}

function totp(secret, step = Math.floor(Date.now() / 30_000)) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const digest = createHmac("sha1", base32(secret)).update(counter).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  return String((digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}

const executablePath =
  process.env.CHROMIUM_PATH ??
  ["/opt/pw-browsers/chromium-1194/chrome-linux/chrome", "/opt/pw-browsers/chromium/chrome-linux/chrome"].find((path) => existsSync(path));
const browser = await chromium.launch({ executablePath });
mkdirSync("screens", { recursive: true });

async function signIn(page) {
  await page.goto(`${BASE}/sign-in`);
  await page.screenshot({ path: "screens/01-sign-in.png" });
  await page.getByLabel("Email").fill(EMAIL);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByLabel("Code").waitFor();
  // A code works once: if this window's code was already used, wait for the next one.
  for (let attempt = 0; attempt < 2; attempt++) {
    await page.getByLabel("Code").fill(totp(SECRET));
    await page.getByRole("button", { name: /Sign in|Finish set-up/ }).click();
    const outcome = await Promise.race([
      page
        .getByRole("heading", { name: "Overview" })
        .waitFor()
        .then(() => "in"),
      page
        .getByText(/didn't work/)
        .waitFor()
        .then(() => "retry"),
    ]);
    if (outcome === "in") return;
    await page.waitForTimeout(31_000 - (Date.now() % 30_000));
  }
  throw new Error("Couldn't sign in.");
}

// Every page keeps the live feed open (Server-Sent Events: a request that never ends), so Playwright's "networkidle"
// never comes. A page is settled once no other request of the current document has been in flight for half a second.
const STREAMING = /\/api\/admin\/analytics\/live\/events/;
const pending = new WeakMap();
function watch(page) {
  const open = new Set();
  pending.set(page, open);
  page.on("request", (request) => STREAMING.test(request.url()) || open.add(request));
  page.on("requestfinished", (request) => open.delete(request));
  page.on("requestfailed", (request) => open.delete(request));
  // Requests the previous document left in flight are cancelled by the navigation without either event firing.
  page.on("framenavigated", (frame) => frame === page.mainFrame() && open.clear());
}
async function settled(page, timeout = 30_000) {
  const open = pending.get(page) ?? new Set();
  const end = Date.now() + timeout;
  let quietSince = Date.now();
  while (Date.now() < end) {
    if (open.size > 0) quietSince = Date.now();
    else if (Date.now() - quietSince >= 500) return;
    await page.waitForTimeout(100);
  }
  throw new Error(`Still loading after ${timeout / 1000} s: ${[...open].map((request) => request.url()).join(", ")}`);
}

async function shoot(page, name, path, ready) {
  await page.goto(`${BASE}${path}`);
  await ready(page);
  await settled(page);
  await page.screenshot({ path: `screens/${name}.png`, fullPage: true });
  console.log(`screens/${name}.png`);
}

const heading = (name) => (page) => page.getByRole("heading", { name, level: 1 }).waitFor();

const desktop = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
const page = await desktop.newPage();
watch(page);
await signIn(page);
await shoot(page, "02-overview", "/?days=30", heading("Overview"));
await shoot(page, "03-engagement", "/engagement?days=30", heading("Engagement"));
await shoot(page, "04-revenue", "/revenue?days=90", heading("Revenue"));
await shoot(page, "05-users", "/users", (p) => p.locator("table.data tbody tr.link").first().waitFor());
// SCREEN_USER picks whose profile to show (an email or name search); otherwise the newest account.
await page.goto(`${BASE}/users?query=${encodeURIComponent(process.env.SCREEN_USER ?? "")}`);
await page.locator("table.data tbody tr.link").first().waitFor();
const firstUser = page.locator("table.data tbody tr.link").first();
await firstUser.click();
await page.getByRole("heading", { name: "Account", level: 2, exact: true }).waitFor();
await settled(page);
await page.screenshot({ path: "screens/06-user.png", fullPage: true });
console.log("screens/06-user.png");
await page.getByRole("button", { name: "Disable" }).click();
await page.getByLabel(/Reason/).fill("Reported for spam");
await page.screenshot({ path: "screens/07-confirm.png" });
await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();
await shoot(page, "08-payments", "/payments", (p) => p.getByRole("tab", { name: "Subscriptions" }).waitFor());
await shoot(page, "09-grants", "/payments?tab=grants&all=true", (p) => p.getByRole("heading", { name: "Every grant" }).waitFor());
await shoot(page, "10-audit", "/audit", (p) => p.locator("table.data tbody tr").first().waitFor());
await shoot(page, "11-system", "/system", heading("System"));

const sessionState = await desktop.storageState();
const storage = await page.evaluate(() => sessionStorage.getItem("heartpact.admin.session"));
for (const [name, scheme] of [["12-overview-dark", "dark"]]) {
  const dark = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: scheme, storageState: sessionState });
  const darkPage = await dark.newPage();
  watch(darkPage);
  await darkPage.addInitScript((value) => sessionStorage.setItem("heartpact.admin.session", value), storage);
  await shoot(darkPage, name, "/?days=90", heading("Overview"));
  await dark.close();
}

const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
const phonePage = await phone.newPage();
watch(phonePage);
await phonePage.addInitScript((value) => sessionStorage.setItem("heartpact.admin.session", value), storage);
await shoot(phonePage, "13-phone-overview", "/?days=30", heading("Overview"));
await shoot(phonePage, "14-phone-users", "/users", (p) => p.locator("table.data tbody tr.link").first().waitFor());

await browser.close();
