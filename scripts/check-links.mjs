#!/usr/bin/env node
// node scripts/check-links.mjs
//
// Every relative link and image in this repository's Markdown has to resolve. A restructure
// moves files, and a link to a file that is no longer there reads as documentation until
// somebody clicks it, so this is a check rather than a habit.
//
// It checks the repository's own Markdown only: a link to another project (the reference
// client, GitHub, a licence) is left alone, because it is not this tree's to resolve.
import { readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { globSync } from 'node:fs';

const root = resolve(import.meta.dirname, '..');
const files = globSync('**/*.md', { cwd: root, exclude: (p) => p.startsWith('node_modules/') });

// `[text](target)` and `![alt](target)`, with an optional title after the target.
const LINK = /!?\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;
// A bare angle-bracket URL is a link too, but never a relative one.
const EXTERNAL = /^(?:[a-z][a-z0-9+.-]*:|#|\/\/)/i;

const problems = [];
let checked = 0;

for (const file of files) {
  const text = readFileSync(join(root, file), 'utf8');
  for (const match of text.matchAll(LINK)) {
    const target = match[1];
    if (EXTERNAL.test(target)) continue;
    // A link may carry a fragment; the file part is what has to exist.
    const path = decodeURIComponent(target.split('#')[0]);
    if (!path) continue;
    checked += 1;
    const from = dirname(join(root, file));
    const resolved = resolve(from, path);
    let ok = false;
    try {
      statSync(resolved);
      ok = true;
    } catch {
      ok = false;
    }
    if (!ok) {
      problems.push(`${file}: ${target} does not resolve (${relative(root, resolved)})`);
    }
  }
}

if (problems.length) {
  console.error(`${problems.length} broken link(s):`);
  for (const problem of problems) console.error(`  ${problem}`);
  process.exit(1);
}
console.log(`${checked} relative links in ${files.length} Markdown files resolve.`);
