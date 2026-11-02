import { readConfig, sessionOptions, trackingUrl } from './config.js';

const config = readConfig(process.env);
const baseUrl = 'https://api.hyperbrowser.ai/api/session';
let sessionId;

async function api(path, init = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: { 'content-type': 'application/json', 'x-api-key': config.apiKey, ...init.headers }
  });
  if (!response.ok) throw new Error(`Hyperbrowser request failed with HTTP ${response.status}`);
  return response.json();
}

try {
  const session = await api('', { method: 'POST', body: JSON.stringify(sessionOptions(config.timeoutMinutes)) });
  sessionId = session.id;
  if (!sessionId || !session.liveUrl || !session.wsEndpoint) throw new Error('Session response missing required fields');

  // The Live View URL is shown only in this local interactive terminal so the operator can open it.
  // Never print the CDP endpoint, response bodies, or provider credentials.
  console.log(`Session created: ${sessionId}. Treat the following Live View URL as a password; do not share it:\n${session.liveUrl}`);
  console.log('Complete any verification manually in Live View. The runner will not click or solve challenges.');

  const { chromium } = await import('playwright-core');
  const browser = await chromium.connectOverCDP(session.wsEndpoint);
  try {
    const pages = browser.contexts().flatMap(context => context.pages());
    if (pages.length === 0) throw new Error('No page found in remote browser session');
    const page = pages[0];
    await page.goto(trackingUrl(config.bl), { waitUntil: 'domcontentloaded', timeout: 60_000 });
    console.log('CDP attached and tracking page opened. Complete any verification manually in Live View; then press Enter here to inspect the page.');
    await new Promise(resolve => process.stdin.once('data', resolve));
    const pageInfo = await page.evaluate(() => ({
      title: document.title,
      url: location.origin + location.pathname,
      bodyTextLength: document.body?.innerText?.length ?? 0,
      tableCount: document.querySelectorAll('table').length
    }));
    console.log(JSON.stringify(pageInfo));
    console.log('Diagnostic only: no DOM content or tracking events are persisted.');
  } finally {
    await browser.close();
  }
} finally {
  if (sessionId) {
    try {
      await api(`/${encodeURIComponent(sessionId)}/stop`, { method: 'POST' });
      console.log('Remote session stopped.');
    } catch {
      console.error('Could not confirm remote session cleanup; check Hyperbrowser session dashboard.');
    }
  }
}
