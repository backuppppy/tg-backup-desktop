// Starts the real engine and server (`./gradlew :desktop:devServer`), waits for its `DEV_URL <url>` line, opens
// that URL once so the session cookie is set, and saves it for every test (storageState).
// With TGB_DEV_URL set, a server that is already running is used instead.
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { chromium } from '@playwright/test';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const START_TIMEOUT_MS = 300_000;

function startDevServer() {
  const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  const child = spawn(gradlew, [':desktop:devServer', '--console=plain', '-q'], {
    cwd: repo,
    detached: process.platform !== 'win32',
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: process.platform === 'win32',
  });
  return new Promise((resolve, reject) => {
    let out = '';
    const timer = setTimeout(() => reject(new Error(`DEV_URL not printed in ${START_TIMEOUT_MS / 1000} s:\n${out}`)), START_TIMEOUT_MS);
    const onData = (chunk) => {
      out += chunk;
      process.stdout.write(chunk);
      const m = out.match(/^DEV_URL (\S+)/m);
      if (m) {
        clearTimeout(timer);
        resolve({ child, url: m[1] });
      }
    };
    child.stdout.setEncoding('utf8').on('data', onData);
    child.stderr.setEncoding('utf8').on('data', (c) => process.stderr.write(c));
    child.on('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`devServer exited (${code}) before DEV_URL:\n${out}`));
    });
  });
}

function stop(child) {
  if (!child || child.exitCode !== null) return;
  try {
    // The whole group: the Gradle client and what it started.
    if (process.platform === 'win32') child.kill();
    else process.kill(-child.pid, 'SIGTERM');
  } catch { /* already gone */ }
}

export default async function globalSetup() {
  let child = null;
  let url = process.env.TGB_DEV_URL;
  if (!url) {
    ({ child, url } = await startDevServer());
    process.on('exit', () => stop(child));
  }
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(url);
  mkdirSync(path.join(here, '.auth'), { recursive: true });
  await context.storageState({ path: path.join(here, '.auth', 'state.json') });
  await browser.close();
  return () => stop(child);
}
