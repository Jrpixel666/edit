#!/usr/bin/env node
// Screenshots a route of the local Expo web dev server at phone dimensions,
// as a visual stand-in for the iOS Simulator (unavailable in this environment).
// Usage: node scripts/web-shot.js <route> <outFile> [--dark] [--width=393] [--height=852] [--base=http://127.0.0.1:8081]
const { chromium } = require('playwright');

function parseArgs(argv) {
  const [route, outFile, ...rest] = argv;
  const opts = { width: 393, height: 852, dark: false, base: 'http://127.0.0.1:8081' };
  for (const arg of rest) {
    if (arg === '--dark') opts.dark = true;
    else if (arg.startsWith('--width=')) opts.width = Number(arg.split('=')[1]);
    else if (arg.startsWith('--height=')) opts.height = Number(arg.split('=')[1]);
    else if (arg.startsWith('--base=')) opts.base = arg.split('=')[1];
  }
  return { route, outFile, ...opts };
}

async function main() {
  const { route, outFile, width, height, dark, base } = parseArgs(process.argv.slice(2));
  if (!route || !outFile) {
    console.error('Usage: node scripts/web-shot.js <route> <outFile> [--dark] [--width=393] [--height=852]');
    process.exit(1);
  }

  const url = new URL(route, base);
  url.searchParams.set('nocache', Date.now().toString());

  const browser = await chromium.launch({
    executablePath: '/opt/pw-browsers/chromium',
    headless: true,
    args: ['--no-sandbox'],
  });
  const page = await browser.newPage({
    viewport: { width, height },
    deviceScaleFactor: 2,
    colorScheme: dark ? 'dark' : 'light',
  });
  await page.goto(url.toString(), { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: outFile });
  await browser.close();
  console.log('saved', outFile);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
