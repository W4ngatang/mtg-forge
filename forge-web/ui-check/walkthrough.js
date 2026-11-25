const { chromium, devices } = require('playwright');
const SHOTS = process.argv[2];
const BASE = process.env.FORGE_URL || 'http://localhost:8080/';
(async () => {
  const browser = await chromium.launch();
  const phone = { ...devices['iPhone 13'] };
  const errors = [];
  const host = await browser.newContext(phone);
  const page = await host.newPage();
  page.on('console', m => { if (m.type() === 'error') errors.push('host: ' + m.text()); });
  page.on('pageerror', e => errors.push('host pageerror: ' + e.message));
  const shot = async (p, name) => { await p.screenshot({ path: `${SHOTS}/${name}.png` }); console.log('shot', name); };

  // the site password, when the server has one (FORGE_SITE_PASSWORD)
  const signIn = async p => {
    await p.goto(BASE);
    await p.waitForSelector('.gate-card, input[type=password]');
    if (await p.$('.gate-card')) {
      await p.fill('.gate-card input[type=password]', process.env.FORGE_SITE_PASSWORD || '');
      await p.click('.gate-card button.btn');
      await p.waitForSelector('text=Enter the host password');
    }
  };
  // password, home
  await signIn(page);
  await page.fill('input[type=password]', (process.env.FORGE_WEB_PASSWORD || ''));
  await page.click('text=Continue');
  await page.waitForSelector('text=New game');
  await shot(page, '01-home');

  // shared deck: paste, check, save
  await page.click('text=Shared decks');
  await page.click('text=New deck');
  await page.fill('input[placeholder="Deck name"]', 'Mini DanDan');
  await page.fill('textarea', '12 Dandan\n24 Island\n4 Brainstorm\n4 Memory Lapse\n4 Accumulated Knowledge\n2 Nonexistent Card');
  await page.click('text=Check');
  await page.waitForSelector('.error:has-text("Unknown card")');
  await shot(page, '02-deck-check-error');
  await page.fill('textarea', '12 Dandan\n24 Island\n4 Brainstorm\n4 Memory Lapse\n4 Accumulated Knowledge\n4 Mental Note');
  await page.click('text=Check');
  await page.waitForSelector('text=52 cards, all recognised');
  await page.click('button:has-text("Save")');
  await page.waitForSelector('text=Mini DanDan');
  await shot(page, '03-decks');

  // new DanDan game vs computer with the saved deck
  await page.goto(BASE + '#/new');
  await page.waitForSelector('text=Format');
  await page.click('button:has-text("DanDan")');
  await page.click('button:has-text("Computer")');
  await page.click('button:has-text("A saved deck")');
  await page.fill('input[placeholder="Your name"]', 'Alex');
  await shot(page, '04-new-game');
  await page.click('text=Start game');
  await page.waitForSelector('.game', { timeout: 60000 });
  await page.waitForTimeout(1500);
  await shot(page, '05-game-start');

  // play: answer dialogs, keep hand, pass until my main phase
  let firstDialog = true;
  for (let i = 0; i < 60; i++) {
    if (await page.$('.ask-sheet')) {
      if (firstDialog) { await shot(page, '06-first-dialog'); firstDialog = false; }
      const done = await page.$('.ask-sheet .btn.primary:not([disabled])');
      if (done) { await done.click(); } else {
        const opt = await page.$('.ask-sheet .opt, .ask-sheet .grid .card');
        if (opt) await opt.click();
        const d2 = await page.$('.ask-sheet .btn.primary:not([disabled])');
        if (d2) await d2.click();
      }
      await page.waitForTimeout(400);
      continue;
    }
    const phase = await page.textContent('.middle .phase').catch(() => '');
    if (/Your turn.*main phase/.test(phase)) break;
    const ok = await page.$('.action-bar .btn.primary:not([disabled])');
    if (ok) await ok.click();
    await page.waitForTimeout(500);
  }
  await shot(page, '07-my-main-phase');
  const handCards = await page.$$('.hand .card');
  console.log('hand cards', handCards.length);
  for (const c of handCards) {
    const t = (await c.textContent()) + (await c.$eval('img', i => i.alt).catch(() => ''));
    if (/Island/.test(t)) { await c.click(); break; }
  }
  await page.waitForTimeout(1200);
  await shot(page, '08-after-land');
  const first = await page.$('.hand .card');
  if (first) {
    const box = await first.boundingBox();
    await page.mouse.move(box.x + 10, box.y + 10);
    await page.mouse.down(); await page.waitForTimeout(650); await page.mouse.up();
    await page.waitForTimeout(300);
    await shot(page, '09-zoom');
    await page.click('.zoom');
  }
  await page.click('.menu-btn');
  await page.waitForTimeout(300);
  await shot(page, '10-menu-log');
  await page.click('.sheet .btn:has-text("Close")');

  // friend game: host creates, friend joins from the link
  const host2 = await browser.newContext(phone);
  const hp = await host2.newPage();
  hp.on('pageerror', e => errors.push('host2 pageerror: ' + e.message));
  await signIn(hp);
  await hp.fill('input[type=password]', (process.env.FORGE_WEB_PASSWORD || ''));
  await hp.click('text=Continue');
  await hp.click('text=New game');
  await hp.waitForSelector('text=Format');
  await hp.click('button:has-text("Momir Basic")');
  await hp.click('button:has-text("A friend")');
  await hp.fill('input[placeholder="Your name"]', 'Host');
  await hp.click('text=Create and get the invite link');
  await hp.waitForSelector('.invite-link', { timeout: 30000 });
  await shot(hp, '11-waiting-for-friend');
  const link = await hp.textContent('.invite-link');
  console.log('invite', link);
  const friendCtx = await browser.newContext(phone);
  const fp = await friendCtx.newPage();
  fp.on('pageerror', e => errors.push('friend pageerror: ' + e.message));
  await fp.goto(link);
  await fp.waitForSelector('text=invited you');
  await shot(fp, '12-friend-join');
  await fp.fill('input[placeholder="Your name"]', 'Sam');
  await fp.click('button:has-text("Join")');
  await fp.waitForSelector('.game', { timeout: 60000 });
  await hp.waitForSelector('.game', { timeout: 60000 });
  await fp.waitForTimeout(1500);
  await shot(fp, '13-friend-game');
  await shot(hp, '14-host-game');

  console.log('ERRORS', JSON.stringify(errors));
  await browser.close();
})().catch(e => { console.error('FAILED', e); process.exit(1); });
