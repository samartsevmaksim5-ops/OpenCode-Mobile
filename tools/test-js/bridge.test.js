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
      rotateWarp: () => window.calls.push(["warp"]),
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

  // 8. A bare quota error becomes an explanation about the WARP refresh and the sign-in offer, and the page asks the
  // app to rebuild the tunnel (fresh egress IP) at most once per cooldown.
  const quota = await page.evaluate(async (src) => {
    window.__ocAndroidBridge = false;
    (0, eval)(src);
    const p = document.createElement("p");
    p.textContent = "Error: insufficient quota";
    document.body.appendChild(p);
    await new Promise((r) => setTimeout(r, 900));
    // The button goes inside the paragraph when the message block is the last element, next to it otherwise.
    const button = p.querySelector("button") || p.nextElementSibling;
    const callsBefore = window.calls.length;
    if (button) button.click();
    await new Promise((r) => setTimeout(r, 100));
    return {
      text: p.textContent,
      buttonText: button && button.textContent,
      opened: window.calls.slice(callsBefore).some((c) => c[0] === "login"),
      rotated: window.calls.some((c) => c[0] === "warp"),
    };
  }, bridge);
  check("quota text replaced", quota.text.includes("WARP") && quota.text.includes("IP"), JSON.stringify(quota));
  check("quota button text", quota.buttonText === "\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0438\u0442\u044c Gmail", String(quota.buttonText));
  check("quota button opens sign-in", quota.opened === true);
  check("quota asks for a WARP rotation", quota.rotated === true);

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
