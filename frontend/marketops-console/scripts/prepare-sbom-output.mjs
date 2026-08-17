#!/usr/bin/env node
/** Create the ignored root output directory before the CycloneDX CLI writes. */
import { mkdirSync } from 'node:fs';
import { URL } from 'node:url';

mkdirSync(new URL('../../../build/supply-chain/', import.meta.url), { recursive: true });
