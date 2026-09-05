#!/usr/bin/env node
/** Generate the frontend CycloneDX inventory and require real JSON validation. */

import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import process from 'node:process';
import { fileURLToPath, URL } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const cli = fileURLToPath(
  new URL('../node_modules/@cyclonedx/cyclonedx-npm/bin/cyclonedx-npm-cli.js', import.meta.url),
);
const output = fileURLToPath(
  new URL('../../../build/supply-chain/frontend-sbom.json', import.meta.url),
);

const result = spawnSync(
  process.execPath,
  [cli, '--output-file', output, '--spec-version', '1.6', '--output-reproducible', '--validate'],
  { cwd: projectRoot, encoding: 'utf8' },
);

process.stdout.write(result.stdout ?? '');
process.stderr.write(result.stderr ?? '');

if (result.error) {
  throw result.error;
}
if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

const diagnostics = `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
if (/skipped validating BOM|No JsonValidator available/i.test(diagnostics)) {
  process.stderr.write('CycloneDX JSON schema validation FAILED: validator was unavailable\n');
  process.exit(1);
}

const bom = JSON.parse(readFileSync(output, 'utf8'));
if (bom.bomFormat !== 'CycloneDX' || bom.specVersion !== '1.6') {
  process.stderr.write('CycloneDX JSON schema validation FAILED: unexpected format/version\n');
  process.exit(1);
}

process.stdout.write('CycloneDX JSON schema validation PASS\n');
