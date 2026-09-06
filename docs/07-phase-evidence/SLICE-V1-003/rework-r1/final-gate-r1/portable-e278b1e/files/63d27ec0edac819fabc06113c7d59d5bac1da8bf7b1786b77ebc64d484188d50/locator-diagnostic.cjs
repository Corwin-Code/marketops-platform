const { createRequire } = require('node:module');
const { readFileSync, writeFileSync } = require('node:fs');
const { createHash } = require('node:crypto');
const root = '/Users/chzhengx/Code/personal/marketops-platform/frontend/marketops-console';
const localRequire = createRequire(root + '/package.json');
const { chromium } = localRequire('@playwright/test');
const source = readFileSync(root + '/src/advertising/AdvertisingManualControls.tsx', 'utf8');
// Exact label/option text from the real TSX; omit only React event/value attributes.
const labels = [...source.matchAll(/<label>\s*(Observation (?:source|completeness))\s*<select[\s\S]*?>\s*([\s\S]*?)<\/select>\s*<\/label>/g)]
  .map(match => ({ name: match[1], options: [...match[2].matchAll(/<option\b[^>]*>[\s\S]*?<\/option>/g)].map(option => option[0]).join('') }));
if (labels.length !== 2) throw new Error('Expected both exact source controls');
(async () => {
  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage();
    await page.setContent(labels.map(x => `<label>${x.name}<select>${x.options}</select></label>`).join(''));
    const controls = [];
    for (const {name} of labels) {
      controls.push({ name, exactLabelMatches: await page.getByLabel(name,{exact:true}).count(), exactRoleMatches: await page.getByRole('combobox',{name,exact:true}).count() });
    }
    const selections = [];
    for (const [name,value] of [['Observation source','SCREENSHOT'], ['Observation completeness','INCOMPLETE'], ['Observation source','DIRECT_OFFICIAL_CONSOLE'], ['Observation completeness','COMPLETE']]) {
      const select = page.getByRole('combobox',{name,exact:true});
      await select.selectOption(value);
      const actual = await select.inputValue();
      if (actual !== value) throw new Error('Selection mismatch');
      selections.push({name,requested:value,actual});
    }
    const snapshot = await page.locator('body').ariaSnapshot();
    const result = {sourceSha256:createHash('sha256').update(source).digest('hex'), controls, selections, snapshot, scope:'Isolated exact label/option markup diagnostic only; not full application acceptance.'};
    writeFileSync('/tmp/slice3-manual-select-locator-diagnostic-r2.json', JSON.stringify(result,null,2)+'\n');
    console.log(JSON.stringify(result,null,2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
