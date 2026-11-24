const { chromium, devices } = require('playwright');
const SHOTS = process.argv[2];
const BASE = process.env.FORGE_URL || 'http://localhost:8080/';
(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ ...devices['iPhone 13'] });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const shot = async name => { await page.screenshot({ path: `${SHOTS}/${name}.png` }); console.log('shot', name); };
  await page.goto(BASE);
  await page.fill('input[type=password]', (process.env.FORGE_WEB_PASSWORD || ''));
  await page.click('text=Continue');
  await page.click('text=New game');
  await page.waitForSelector('text=Format');
  await page.click('button:has-text("Momir Basic")');
  await page.click('button:has-text("Computer")');
  await page.click('text=Start game');
  await page.waitForSelector('.game', { timeout: 60000 });

  let activated = 0, dialogsSeen = [], tokenSeen = false, lastTurnTried = -1, landTurn = -1;
  for (let i = 0; i < 400 && !tokenSeen; i++) {
    if (await page.$('.over')) break;
    const sheet = await page.$('.ask-sheet');
    if (sheet) {
      const title = await sheet.$eval('h3', e => e.textContent).catch(() => '');
      if (!dialogsSeen.includes(title)) { dialogsSeen.push(title); await shot('m-dialog-' + dialogsSeen.length); }
      const input = await sheet.$('input[type=number], input[type=text]:not([placeholder="Filter…"])');
      if (input) await input.fill('1');
      if (!(await sheet.$('.btn.primary:not([disabled])'))) {
        // pick X = 2 when choosing a number, otherwise the first option
        const opt = (await sheet.$('.opt:text-is("2")')) || (await sheet.$('.opt, .grid .card'));
        if (opt) await opt.click();
      }
      const done = await sheet.$('.btn.primary:not([disabled])');
      if (done) await done.click();
      await page.waitForTimeout(400);
      continue;
    }
    tokenSeen = !!(await page.$('.field.me .badge.tag:has-text("token")'));
    if (tokenSeen) break;
    const phase = await page.textContent('.middle .phase').catch(() => '');
    const turn = parseInt((phase.match(/T(\d+)/) || [0, 0])[1], 10);
    const prompt = await page.textContent('.prompt').catch(() => '');
    // on my main phase: play a land from hand first, then use the avatar once per turn
    if (/Your main phase/.test(phase) && /Your move/.test(prompt)) {
      const handLand = await page.$('.hand .card');
      if (handLand && turn !== landTurn) {
        landTurn = turn;
        await handLand.click(); await page.waitForTimeout(600); continue;
      }
      if (turn !== lastTurnTried && turn >= 3) {
        lastTurnTried = turn;
        await page.click('.field.me .badge.tag:has-text("command")');
        activated++;
        await page.waitForTimeout(800);
        await shot('m-after-avatar-tap-' + activated);
        continue;
      }
    }
    // paying mana: tap a land if the prompt asks to pay
    if (/Pay|pay/.test(prompt)) {
      const land = await page.$('.field.me .cards.lands .card:not(.tapped)');
      if (land) { await land.click(); await page.waitForTimeout(400); continue; }
    }
    const ok = await page.$('.action-bar .btn.primary:not([disabled])');
    if (ok) { await ok.click(); }
    else {
      // a cost or effect wants a card picked: the valid ones are highlighted
      const pick = await page.$('.card.target:not(.chosen)');
      if (pick) { await shot('m-pick-highlighted'); await pick.click(); }
    }
    await page.waitForTimeout(350);
  }
  await shot('m-final');
  console.log('activated', activated, 'dialogs', JSON.stringify(dialogsSeen), 'token', tokenSeen, 'errors', JSON.stringify(errors));
  await browser.close();
})().catch(e => { console.error('FAILED', e); process.exit(1); });
