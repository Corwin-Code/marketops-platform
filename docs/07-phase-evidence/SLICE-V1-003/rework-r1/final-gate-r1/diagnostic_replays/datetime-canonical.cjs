const { createRequire } = require('node:module');
const { writeFileSync } = require('node:fs');
const { mkdtempSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');
// Run from the repository root; each invocation owns a private temporary directory.
const outputDirectory = mkdtempSync(join(tmpdir(), 'slice3-diagnostic-'));
const outputPath = join(outputDirectory, 'result.json');
const localRequire = createRequire(resolve('frontend/marketops-console/package.json'));
const { chromium } = localRequire('@playwright/test');
(async () => {
  const browser = await chromium.launch({headless:true});
  const results = [];
  try {
    for (const timezoneId of ['UTC', 'Asia/Taipei']) {
      const context = await browser.newContext({timezoneId});
      const page = await context.newPage();
      await page.setContent('<label>Observation time<input type="datetime-local" step="0.001"></label>');
      const input = page.getByLabel('Observation time', {exact:true});
      let originalRejected = false;
      try { await input.fill('2026-09-06T09:34:48.230'); }
      catch (error) { if (!String(error).includes('Malformed value')) throw error; originalRejected = true; }
      if (!originalRejected) throw new Error('Original CI normalization failure did not reproduce');
      const matrix = await page.evaluate(() => {
        const rows = [];
        for (const seconds of [0, 48]) for (let millis = 0; millis < 1000; millis++) {
          const actual = new Date(Date.UTC(2026,8,6,9,34,seconds,millis));
          const local = new Date(actual.getTime() - actual.getTimezoneOffset() * 60_000);
          const control = document.createElement('input');
          control.type = 'datetime-local'; control.step = '0.001';
          control.value = local.toISOString().slice(0,23);
          if (!control.value || new Date(control.value).toISOString() !== actual.toISOString()) throw new Error('Observation instant changed');
          rows.push({iso:actual.toISOString(),local:control.value,millis,seconds});
        }
        return rows;
      });
      const samples = matrix.filter(x => [0,1,10,100,230,900,999].includes(x.millis));
      for (const sample of samples) {
        await input.fill(sample.local);
        if (await input.inputValue() !== sample.local) throw new Error('Canonical fill changed');
      }
      results.push({timezoneId,originalRejected,canonicalInstantsVerified:matrix.length,actualPlaywrightFills:samples.length,samples});
      await context.close();
    }
    const report = {kind:'TEMPORARY_BROWSER_TIME_NORMALIZATION_DIAGNOSTIC',results,totalInstants:4000,totalFills:28,
      scope:'Detached native datetime-local canonicalization plus actual Playwright fill only. No application, PostgreSQL, Provider, or full integration acceptance claim.'};
    writeFileSync(outputPath,JSON.stringify(report,null,2)+'\n', { flag: 'wx', mode: 0o600 });
    console.log(JSON.stringify({ outputPath }));
    console.log(JSON.stringify({totalInstants:4000,totalFills:28,originalRejectedInBothTimezones:true,allCanonicalInstantsAndFillsVerified:true}));
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
