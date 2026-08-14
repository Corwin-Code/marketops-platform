#!/usr/bin/env node
/**
 * Prove that only variables named for this console reach the built bundle.
 *
 * The check is a canary: a value is placed in the environment under a name the
 * prefix does not cover, the application is built, and the output is searched
 * for it. A bundle is a public artefact, and a configuration mistake that
 * published the shell's environment would otherwise be invisible until someone
 * opened the file.
 *
 * The canary value is generated per run and never written to disk outside the
 * build directory, so nothing here is a credential.
 */
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { fileURLToPath, URL } from 'node:url';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));
const canary = `canary-${randomUUID()}`;

/** Names that must never reach the bundle, each carrying the canary value. */
const withheld = {
  MARKETOPS_DB_APP_PASSWORD: canary,
  MARKETOPS_POSTGRES_SUPERUSER_PASSWORD: canary,
  VITE_UNRELATED_SETTING: canary,
  MARKETOPS_INTERNAL_HOST: canary,
};

/** Approved public names that must reach the bundle, proving the search is live. */
const published = {
  VITE_MARKETOPS_API_BASE_URL: 'http://127.0.0.1:8080',
  VITE_MARKETOPS_ENVIRONMENT: `published-${randomUUID()}`,
};

function build() {
  execFileSync('npm', ['run', 'build'], {
    cwd: projectRoot,
    env: { ...process.env, ...withheld, ...published },
    stdio: 'inherit',
  });
}

function* bundleFiles(directory) {
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) {
      yield* bundleFiles(path);
    } else {
      yield path;
    }
  }
}

function main() {
  build();

  const distribution = join(projectRoot, 'dist');
  let publishedValueSeen = false;
  const leaked = [];

  for (const file of bundleFiles(distribution)) {
    const contents = readFileSync(file, 'utf8');
    if (contents.includes(canary)) {
      leaked.push(file);
    }
    if (contents.includes(published.VITE_MARKETOPS_ENVIRONMENT)) {
      publishedValueSeen = true;
    }
  }

  if (leaked.length > 0) {
    process.stderr.write(
      `bundle isolation FAILED: a withheld value reached ${leaked.join(', ')}\n`,
    );
    process.exit(1);
  }

  if (!publishedValueSeen) {
    // Without this the check would pass on a build that inlined nothing at all,
    // which proves the search worked rather than that the prefix holds.
    process.stderr.write(
      'bundle isolation INCONCLUSIVE: the prefixed value did not reach the bundle, ' +
        'so the search could not distinguish a correct prefix from a broken build\n',
    );
    process.exit(1);
  }

  process.stdout.write('bundle isolation PASS: only prefixed values reached the bundle\n');
}

main();
