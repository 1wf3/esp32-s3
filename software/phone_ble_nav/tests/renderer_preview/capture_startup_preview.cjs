const { chromium } = require('playwright');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');

(async () => {
  const output = path.resolve(__dirname, '../../../../output/previews');
  const browser = await chromium.launch({
    executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true,
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1340, height: 1000 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(pathToFileURL(path.join(output, '开机与手机连接预览.html')).href + '?still=1');
    await page.locator('#idle svg').waitFor();
    await page.screenshot({ path: path.join(output, '开机与手机连接预览.png'), fullPage: true });
    await page.locator('#idle').screenshot({ path: path.join(output, '等待手机连接.png') });
    if (process.argv.includes('--gif')) {
      const capturedFrames = await page.evaluate(() => [...frames.boot, ...frames.idle]);
      const frameDir = path.resolve(__dirname, '../../../../build/startup_gif');
      fs.mkdirSync(frameDir, { recursive: true });
      const capture = await browser.newPage({ viewport: { width: 480, height: 320 }, deviceScaleFactor: 1 });
      await capture.setContent('<style>body{margin:0;background:#000408}svg{display:block;width:480px;height:320px}</style><div id="frame"></div>');
      for (let i = 0; i < capturedFrames.length; i++) {
        await capture.evaluate(svg => document.querySelector('#frame').innerHTML = svg, capturedFrames[i]);
        await capture.screenshot({ path: path.join(frameDir, String(i).padStart(3, '0') + '.png') });
      }
      await capture.close();
    }
    const firstDots = await page.locator('#idle').innerHTML();
    await page.waitForTimeout(300);
    if (firstDots === await page.locator('#idle').innerHTML()) throw new Error('Idle dots did not animate');
    for (const [mode, title] of [['pairing', '等待蓝牙配对'], ['handshake', '正在建立连接'], ['ready', '手机已连接'], ['lost', '请重新连接']]) {
      await page.selectOption('#state', mode);
      await page.waitForFunction(title => document.querySelector('#idle').textContent.includes(title), title);
    }
    if (errors.length) throw new Error(errors.join('\n'));
    await page.goto(pathToFileURL(path.join(output, '开机与手机连接预览.html')).href);
    await page.waitForFunction(() => document.querySelector('#boot').textContent.includes('等待手机连接'));
    await page.click('#replay');
    await page.waitForFunction(() => !document.querySelector('#boot').textContent.includes('等待手机连接'));
    console.log('Preview: animation, four connection states, automatic finish, replay and browser console passed');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
