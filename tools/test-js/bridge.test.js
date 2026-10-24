// Loads assets/bridge.js into real Chromium with a recording stand-in for the Android interface.
const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const bridge = fs.readFileSync(path.join(__dirname, "../../android/app/src/main/assets/bridge.js"), "utf8");
let passed = 0;
const failures = [];

function check(name, ok, detail) {
  if (ok) passed++;
  else failures.push(name + (detail ? " " + detail : ""));
}

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || undefined, args: ["--no-sandbox"] });
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.setContent(`<!doctype html><html><body style="background:rgb(22,22,22)">
    <div id="root"><div data-component="terminal-panel"><textarea id="term"></textarea></div><textarea id="other"></textarea></div></body></html>`);

  await page.evaluate(() => {
    window.calls = [];
    window.files = {};
    let n = 0;
    window.OpenCodeAndroid = {
      saveBegin: (name, mime) => { const id = "s" + ++n; window.files[id] = { name, mime, parts: [], ended: false }; return id; },
      saveChunk: (id, b64) => { window.files[id].parts.push(b64); return true; },
      saveEnd: (id) => { window.files[id].ended = true; },
      saveAbort: (id) => { window.files[id].aborted = true; },
      setBars: (dark, color) => window.calls.push(["bars", dark, color]),
      terminalFocus: (f) => window.calls.push(["terminal", f]),
      copyText: (t) => window.calls.push(["copy", t]),
      openLogin: () => window.calls.push(["login"]),
      hasAccount: () => window.accountConnected === true,
    };
  });
  await page.evaluate(bridge);
  check("flag set", await page.evaluate(() => window.__ocAndroidBridge === true));

  // Evaluating it twice must not double-wrap anything.
  await page.evaluate(bridge);

  // 1. A blob download through a detached anchor (what FileSaver-style code does), larger than one chunk.
  const result = await page.evaluate(async () => {
    const bytes = new Uint8Array(700 * 1024);
    for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 31 + 7) & 255;
    const blob = new Blob([bytes], { type: "application/zip" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "report.zip";
    a.click();
    await new Promise((r) => setTimeout(r, 500));
    const f = window.files.s1;
    const joined = f.parts.map((p) => atob(p)).join("");
    let ok = joined.length === bytes.length;
    for (let i = 0; ok && i < bytes.length; i += 997) if (joined.charCodeAt(i) !== bytes[i]) ok = false;
    return { name: f.name, mime: f.mime, chunks: f.parts.length, ended: f.ended, ok };
  });
  check("blob name", result.name === "report.zip", JSON.stringify(result));
  check("blob mime", result.mime === "application/zip");
  check("blob chunked", result.chunks === 4, String(result.chunks));
  check("blob ended", result.ended === true);
  check("blob bytes identical", result.ok === true);

  // 2. A data URL through a real click in the document.
  const data = await page.evaluate(async () => {
    const a = document.createElement("a");
    a.href = "data:text/plain;base64,aGVsbG8gd29ybGQ=";
    a.textContent = "x";
    document.body.appendChild(a);
    a.click();
    await new Promise((r) => setTimeout(r, 300));
    const f = window.files.s2;
    return f && { text: f.parts.map(atob).join(""), mime: f.mime, name: f.name };
  });
  check("data url saved", data && data.text === "hello world", JSON.stringify(data));
  check("data url mime", data && data.mime === "text/plain");

  // 3. dispatchEvent on a detached anchor.
  const dispatched = await page.evaluate(async () => {
    const blob = new Blob(["abc"], { type: "text/plain" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "d.txt";
    a.dispatchEvent(new MouseEvent("click"));
    await new Promise((r) => setTimeout(r, 300));
    return window.files.s3 && window.files.s3.parts.map(atob).join("");
  });
  check("dispatchEvent download", dispatched === "abc", String(dispatched));

  // 4. Normal links are left alone.
  const normal = await page.evaluate(() => {
    const a = document.createElement("a");
    a.href = "#plain";
    document.body.appendChild(a);
    let prevented = false;
    a.addEventListener("click", (e) => { prevented = e.defaultPrevented; e.preventDefault(); });
    a.click();
    return prevented === false && !window.files.s4;
  });
  check("plain link untouched", normal);

  // 5. Theme to system bars.
  await page.waitForTimeout(400);
  let bars = await page.evaluate(() => window.calls.filter((c) => c[0] === "bars"));
  check("dark bars reported", bars.length >= 1 && bars[0][1] === true && bars[0][2] === "#161616", JSON.stringify(bars));
  await page.evaluate(() => { document.body.style.background = "rgb(250,250,250)"; });
  await page.waitForTimeout(500);
  bars = await page.evaluate(() => window.calls.filter((c) => c[0] === "bars"));
  const last = bars[bars.length - 1];
  check("light bars after theme change", last && last[1] === false && last[2] === "#fafafa", JSON.stringify(bars));
  const before = bars.length;
  await page.waitForTimeout(2500);
  const after = await page.evaluate(() => window.calls.filter((c) => c[0] === "bars").length);
  check("bars not repeated", after === before, `${before} -> ${after}`);

  // 6. Terminal focus.
  await page.focus("#term");
  await page.waitForTimeout(100);
  await page.focus("#other");
  await page.waitForTimeout(200);
  const term = await page.evaluate(() => window.calls.filter((c) => c[0] === "terminal").map((c) => c[1]));
  check("terminal focus then blur", JSON.stringify(term) === "[true,false]", JSON.stringify(term));

  // 7. Clipboard fallback.
  const copy = await page.evaluate(async () => {
    Object.defineProperty(navigator, "clipboard", { value: { writeText: () => Promise.reject(new Error("denied")) }, configurable: true });
    return true;
  });
  await page.evaluate(() => { window.__ocAndroidBridge = false; });
  await page.evaluate(bridge);
  await page.evaluate(async () => { await navigator.clipboard.writeText("copied text"); });
  const copied = await page.evaluate(() => window.calls.filter((c) => c[0] === "copy").map((c) => c[1]));
  check("clipboard fallback", copied.includes("copied text"), JSON.stringify(copied));

  // 8. A limit error is shown exactly as the server sent it. Under it the page adds one hint with a sign-in button.
  // Quota errors are provider answers: the page must NOT ask the app to rebuild any tunnel over them.
  const quota = await page.evaluate(async (src) => {
    window.__ocAndroidBridge = false;
    (0, eval)(src);
    const host = document.createElement("div");
    host.setAttribute("role", "alert");
    const p = document.createElement("p");
    p.textContent = "Error: insufficient quota";
    host.appendChild(p);
    document.body.appendChild(host);
    await new Promise((r) => setTimeout(r, 900));
    // The hint goes next to the message block, never inside it.
    const hint = p.nextElementSibling;
    const button = hint && hint.querySelector("[data-oc-signin]");
    const callsBefore = window.calls.length;
    if (button) button.click();
    await new Promise((r) => setTimeout(r, 100));
    // Several more scan cycles: the hint must neither multiply nor be scanned as an error itself.
    await new Promise((r) => setTimeout(r, 1500));
    const hintsWhileError = host.parentElement.querySelectorAll("[data-oc-hint]").length;
    // The error goes away (retry succeeded): the hint goes with it.
    p.textContent = "All good";
    await new Promise((r) => setTimeout(r, 1500));
    const hintsAfterFix = document.querySelectorAll("[data-oc-hint]").length;
    p.textContent = "Error: insufficient quota";
    await new Promise((r) => setTimeout(r, 1500));
    const hintsWhenBack = document.querySelectorAll("[data-oc-hint]").length;
    return {
      text: p.textContent,
      hasHint: !!(hint && hint.hasAttribute("data-oc-hint")),
      buttonText: button && button.textContent,
      opened: window.calls.slice(callsBefore).some((c) => c[0] === "login"),
      rotated: window.calls.some((c) => c[0] === "warp"),
      hintsWhileError,
      hintsAfterFix,
      hintsWhenBack,
    };
  }, bridge);
  check("error text kept as is", quota.text === "Error: insufficient quota", JSON.stringify(quota));
  check("hint added after the error", quota.hasHint === true, JSON.stringify(quota));
  // The page in this harness is English, so the offer follows the page language (the old label was hardcoded Russian).
  check("quota button text", quota.buttonText === "Connect account", String(quota.buttonText));
  check("quota button opens sign-in", quota.opened === true);
  check("quota never asks for a WARP rotation", quota.rotated === false);
  check("one hint per error", quota.hintsWhileError === 1, JSON.stringify(quota));
  check("hint removed when the error is gone", quota.hintsAfterFix === 0, JSON.stringify(quota));
  check("hint returns when the limit returns", quota.hintsWhenBack === 1, JSON.stringify(quota));

  // 8a. Russian limit wording and plain "429" status text are recognised too; a bare mention of 429 is not.
  // The English cases are the texts OpenCode Zen actually sends (see Limits.kt): "Free usage exceeded,
  // subscribe to Go" and the FreeUsageLimitError form used by the gateway.
  const wording = await page.evaluate(async () => {
    const make = (text) => {
      const host = document.createElement("div");
      host.setAttribute("role", "alert");
      const p = document.createElement("p");
      p.textContent = text;
      host.appendChild(p);
      document.body.appendChild(host);
      return p;
    };
    const ru = make("\u041b\u0438\u043c\u0438\u0442 \u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d");
    const status = make("Request failed: status 429");
    const other = make("Error 4290: file not found");
    const zen = make("Free usage exceeded, subscribe to Go");
    const camel = make("FreeUsageLimitError: daily free usage limit");
    // A Russian-language page gets a Russian offer, matching the hint text.
    document.documentElement.lang = "ru";
    const ruBtn = make("Insufficient quota");
    document.documentElement.lang = "";
    await new Promise((r) => setTimeout(r, 900));
    const has = (p) => !!(p.nextElementSibling && p.nextElementSibling.hasAttribute("data-oc-hint"));
    const ruBox = ruBtn.nextElementSibling;
    const ruLabel = ruBox && ruBox.querySelector("[data-oc-signin]");
    return {
      ru: has(ru),
      status: has(status),
      other: has(other),
      zen: has(zen),
      camel: has(camel),
      ruBtnLabel: ruLabel && ruLabel.textContent,
      ruBtnHint: has(ruBtn),
      ruText: ru.textContent,
      otherText: other.textContent,
      zenText: zen.textContent,
    };
  });
  check("russian limit gets a hint", wording.ru === true && wording.ruText === "\u041b\u0438\u043c\u0438\u0442 \u0438\u0441\u0447\u0435\u0440\u043f\u0430\u043d", JSON.stringify(wording));
  check("status 429 gets a hint", wording.status === true, JSON.stringify(wording));
  check("unrelated number gets no hint", wording.other === false && wording.otherText === "Error 4290: file not found", JSON.stringify(wording));
  check("zen free usage exceeded gets a hint", wording.zen === true && wording.zenText === "Free usage exceeded, subscribe to Go", JSON.stringify(wording));
  check("FreeUsageLimitError gets a hint", wording.camel === true, JSON.stringify(wording));
  check("russian page gets a russian offer", wording.ruBtnHint === true && wording.ruBtnLabel === "\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0438\u0442\u044c \u0430\u043a\u043a\u0430\u0443\u043d\u0442", JSON.stringify(wording));

  // 8c. A profile with a connected account: the hint explains the personal limit and offers no sign-in button.
  const signed = await page.evaluate(async (src) => {
    window.accountConnected = true;
    window.__ocAndroidBridge = false;
    (0, eval)(src);
    const host = document.createElement("div");
    host.setAttribute("role", "alert");
    const p = document.createElement("p");
    p.textContent = "Error: Rate limit exceeded. Please try again later.";
    host.appendChild(p);
    document.body.appendChild(host);
    await new Promise((r) => setTimeout(r, 900));
    const hint = p.nextElementSibling;
    const result = {
      hint: !!(hint && hint.hasAttribute("data-oc-hint")),
      button: hint ? !!hint.querySelector("[data-oc-signin]") : null,
      text: p.textContent,
      hintText: hint && hint.textContent,
    };
    window.accountConnected = false;
    return result;
  }, bridge);
  check("signed-in error keeps text", signed.text === "Error: Rate limit exceeded. Please try again later.", JSON.stringify(signed));
  check("signed-in hint without button", signed.hint === true && signed.button === false, JSON.stringify(signed));
  check("signed-in hint speaks of the personal limit", signed.hintText.includes("another model") && !signed.hintText.includes("Connect account"), String(signed.hintText));

  // 8b. The watcher must not touch ordinary text or terminal output that merely mentions a limit.
  const negative = await page.evaluate(async () => {
    const plain = document.createElement("p");
    plain.textContent = "I wrote: the request limit was reached last week";
    document.body.appendChild(plain);
    const term = document.createElement("div");
    term.setAttribute("data-component", "terminal-panel");
    const line = document.createElement("span");
    line.textContent = "insufficient quota for this team";
    term.appendChild(line);
    document.body.appendChild(term);
    await new Promise((r) => setTimeout(r, 900));
    return {
      plain: plain.textContent,
      line: line.textContent,
      // The watcher inserts a hint as the paragraph's next sibling; look there, not anywhere in the body.
      plainHint: !!(plain.nextElementSibling && plain.nextElementSibling.hasAttribute("data-oc-hint")),
      termHint: !!term.querySelector("[data-oc-hint]") || !!(term.nextElementSibling && term.nextElementSibling.hasAttribute("data-oc-hint")),
    };
  });
  check("plain text untouched", negative.plain.includes("request limit") && !negative.plainHint, JSON.stringify(negative));
  check("terminal untouched", negative.line === "insufficient quota for this team" && !negative.termHint, JSON.stringify(negative));

  // 9. Without the native interface the script does nothing and does not throw.
  const quiet = await context.newPage();
  await quiet.setContent("<html><body></body></html>");
  const threw = await quiet.evaluate((src) => { try { (0, eval)(src); return false; } catch (e) { return true; } }, bridge);
  check("no interface, no effect", threw === false && (await quiet.evaluate(() => !window.__ocAndroidBridge)));

  await browser.close();
  console.log(`passed: ${passed}, failed: ${failures.length}`);
  failures.forEach((f) => console.log("FAIL: " + f));
  process.exit(failures.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
