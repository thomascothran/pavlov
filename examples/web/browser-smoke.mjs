import { spawn } from "node:child_process";
import { access } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import puppeteer from "puppeteer-core";

const exampleRoot = path.dirname(new URL(import.meta.url).pathname);
const repoRoot = path.resolve(exampleRoot, "../..");
const baseUrl = process.env.PAVLOV_WEB_BASE_URL ?? "http://127.0.0.1:4980";
const externalServer = Boolean(process.env.PAVLOV_WEB_BASE_URL);

async function executablePath() {
  const candidates = [
    process.env.CHROME_BIN,
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/google-chrome",
    "/etc/profiles/per-user/default/bin/chromium",
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      await access(candidate);
      return candidate;
    } catch (_) {
      // Try the next conventional location.
    }
  }
  throw new Error("No Chromium executable found; set CHROME_BIN");
}

async function waitForServer() {
  let lastError;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(`${baseUrl}/browser-only-squint`);
      if (response.ok) return;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Pavlov Web server did not start: ${lastError ?? "timeout"}`);
}

async function goto(page, pathname) {
  await page.goto(`${baseUrl}${pathname}`, {
    waitUntil: "domcontentloaded",
    timeout: 60_000,
  });
}

let server;
let browser;
try {
  if (!externalServer) {
    server = spawn("clojure", ["-M:dev", "-m", "pavlov-web-example.app"], {
      cwd: repoRoot,
      stdio: ["ignore", "inherit", "inherit"],
    });
  }
  await waitForServer();

  browser = await puppeteer.launch({
    executablePath: await executablePath(),
    headless: true,
    args: ["--no-sandbox", "--disable-dev-shm-usage"],
  });
  const page = await browser.newPage();
  const pageErrors = [];
  const browserLogs = [];
  page.on("pageerror", (error) => pageErrors.push(error));
  page.on("console", (message) => {
    browserLogs.push(message.text());
    console.log(`[browser:${message.type()}] ${message.text()}`);
  });
  page.on("requestfailed", (request) =>
    console.error(`[browser:requestfailed] ${request.url()} ${request.failure()?.errorText ?? ""}`),
  );

  await goto(page, "/browser-only-squint");
  await page.waitForSelector("#simple-web-page-input");
  await page.$eval("#simple-web-page-input", (input) => {
    input.value = "squint smoke";
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await page.waitForFunction(
    () => document.querySelector("[data-browser-only-status]")?.textContent.trim() === "squint smoke",
  );

  await page.click("#sort-latency");
  await page.waitForFunction(
    () => document.querySelector("#telemetry-grid-body tr td")?.textContent.trim() === "SYNTH-NODE-099",
  );

  await page.waitForFunction(
    () => globalThis.pavlovWebExample?.transport?.["!websocket"]?.val?.readyState === WebSocket.OPEN,
  );
  await page.click("#browser-only-initialize-button");
  await new Promise((resolve) => setTimeout(resolve, 200));
  if (!browserLogs.some((line) =>
    line.includes("send! explicit-socket") && line.includes("browser-only/initialize-clicked"))) {
    throw new Error("browser-only websocket did not send the initialize event");
  }
  await page.evaluate(() => {
    const socket = globalThis.pavlovWebExample.transport["!websocket"].val;
    socket.onmessage({
      data: '{:type :pavlov.web.dom/op :selector "#browser-only-initialize-button" :kind :set :member "textContent" :value "initialized"}',
    });
  });
  await page.waitForFunction(
    () => document.querySelector("#browser-only-initialize-button")?.textContent.trim() === "initialized",
  );

  browserLogs.length = 0;
  await goto(page, "/game-of-life-squint");
  await page.waitForSelector("[data-game-of-life-cell]");
  await page.waitForFunction(
    () => globalThis.pavlovWebExample?.transport?.["!websocket"]?.val?.readyState === WebSocket.OPEN,
  );
  await page.$eval("[data-game-of-life-cell]", (cell) =>
    cell.dispatchEvent(new MouseEvent("click", { bubbles: true })),
  );
  await new Promise((resolve) => setTimeout(resolve, 200));
  if (!browserLogs.some((line) =>
    line.includes("send! explicit-socket") && line.includes("game-of-life/cell-clicked"))) {
    throw new Error("game-of-life websocket did not send the cell event");
  }
  if (!browserLogs.some((line) =>
    line.includes("received server event type=pavlov.web.dom/ops"))) {
    throw new Error("game-of-life websocket did not receive the server board update");
  }
  await page.evaluate(() => {
    const socket = globalThis.pavlovWebExample.transport["!websocket"].val;
    socket.onmessage({
      data: '{:type :pavlov.web.dom/op :selector "[data-game-of-life-cell]" :kind :call :member "setAttribute" :args ["data-cell-state" "alive"]}',
    });
  });
  await page.waitForFunction(
    () => document.querySelector("[data-game-of-life-cell]")?.getAttribute("data-cell-state") === "alive",
  );

  if (pageErrors.length) {
    throw new AggregateError(pageErrors, "Browser page errors occurred");
  }
  console.log("Pavlov Web Squint browser smoke test passed");
} finally {
  await browser?.close();
  if (server) {
    server.kill("SIGTERM");
  }
}
