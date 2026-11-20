#!/usr/bin/env node
// Runs one workflow job's shell steps the way GitHub does, so the workflow itself can be
// checked on a machine instead of only in CI.
//
//   node scripts/workflow-steps.mjs <workflow file> <job> [--step <name>] [--cwd <dir>]
//
// Each `run:` step is written to a script and executed with `bash -eo pipefail`, which is
// GitHub's default shell, in order, stopping at the first failure. Steps that need the
// runner's own actions (`uses:`) are skipped and named. `${{ ... }}` expressions are
// replaced from --set name=value and from the environment, so a step that reads
// `steps.name.outputs.path` gets a path the caller chose.
//
// This is a verification aid, not part of the workflow: it exists so that a change to a
// step's shell can be run locally before it is pushed.
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import YAML from 'yaml';

const [file, job, ...rest] = process.argv.slice(2);
if (!file || !job) {
  console.error('usage: workflow-steps.mjs <workflow> <job> [--step <name>] [--set k=v]...');
  process.exit(2);
}
const values = { ...process.env };
let only = null;
let cwd = process.cwd();
for (let i = 0; i < rest.length; i += 1) {
  if (rest[i] === '--step') only = rest[(i += 1)];
  else if (rest[i] === '--cwd') cwd = rest[(i += 1)];
  else if (rest[i] === '--set') {
    const [k, ...v] = rest[(i += 1)].split('=');
    values[k] = v.join('=');
  }
}

const doc = YAML.parse(readFileSync(file, 'utf8'));
const spec = doc.jobs[job];
if (!spec) {
  console.error(`${file} has no job ${job}`);
  process.exit(2);
}

// `${{ expr }}`: a known name, else the text itself. Good enough for the expressions these
// steps use, which are outputs and a handful of literals.
function expand(text) {
  return text.replace(/\$\{\{([^}]*)\}\}/g, (_, expr) => {
    const whole = expr.trim();
    // `secrets.X != ''` is how these workflows ask "is this secret set at all": a runner
    // answers with the string `true` or `false`, and a step's `if:` compares against it.
    // Without this it expanded to nothing, so every guarded step skipped here while running
    // in CI, and a local run of the signing path proved nothing about it.
    if (isCondition(whole)) return String(evaluate(whole));
    return value(whole);
  });
}

// A single comparison of a name against a quoted literal, or a job/outputs name.
const COMPARISON = /^\s*([\w.-]+)\s*(==|!=)\s*'([^']*)'\s*$/;

function isCondition(text) {
  return COMPARISON.test(text) || /(\|\||&&)/.test(text);
}

// GitHub's expression grammar, in the part these workflows use: comparisons joined by `&&`
// and `||`, `&&` binding tighter. Parentheses are not used here and are refused rather than
// mis-evaluated, as is any form this does not understand: silently answering `false` would
// skip a step and hide exactly the kind of mistake this tool exists to catch.
function evaluate(text) {
  const ors = splitTop(text, '||');
  if (ors.length > 1) return ors.some(evaluate);
  const ands = splitTop(text, '&&');
  if (ands.length > 1) return ands.every(evaluate);
  const m = COMPARISON.exec(text);
  if (!m) {
    console.error(`workflow-steps: cannot evaluate condition: ${text.trim()}`);
    process.exit(2);
  }
  const [, name, op, literal] = m;
  const actual = value(name);
  return op === '==' ? actual === literal : actual !== literal;
}

// Splits on an operator that is not inside a quoted string. `||` and `&&` cannot appear
// inside one of these literals, but a quoted string can contain a single `|` or `&`.
function splitTop(text, operator) {
  const parts = [];
  let quote = false;
  let start = 0;
  for (let i = 0; i < text.length; i += 1) {
    if (text[i] === "'") quote = !quote;
    else if (!quote && text.startsWith(operator, i)) {
      parts.push(text.slice(start, i));
      i += operator.length - 1;
      start = i + 1;
    }
  }
  parts.push(text.slice(start));
  return parts;
}

// The value a `${{ ... }}` name refers to: an exact --set, or its last one or two
// segments, so `needs.build.outputs.sha256` can be given as `sha256=...`.
function value(name) {
  if (values[name] !== undefined) return values[name];
  const segments = name.split('.');
  const last = segments[segments.length - 1];
  if (values[last] !== undefined) return values[last];
  const joined = segments.slice(-2).join('.');
  if (values[joined] !== undefined) return values[joined];
  return '';
}

const dir = mkdtempSync(join(tmpdir(), 'harmony-steps-'));
// The runner's own files: GITHUB_OUTPUT is appended to by the steps, and GITHUB_STEP_SUMMARY
// is where a step writes what the run page shows.
const outputs = join(dir, 'github-output');
const summary = join(dir, 'github-step-summary');
writeFileSync(outputs, '');
writeFileSync(summary, '');
let index = 0;
let ran = 0;
values.GITHUB_OUTPUT = outputs;
values.GITHUB_STEP_SUMMARY = summary;

// The job's own `env:` applies to every step, as it does on a runner. Without this a step
// guarded by `if: env.HAS_TEST_KEYSTORE == 'true'` skipped here while running in CI, and a
// local check of the signing path proved nothing.
for (const [k, v] of Object.entries(spec.env || {})) {
  if (values[k] === undefined) values[k] = expand(String(v));
}

// A step's `if:` is not a full expression engine here; the forms these workflows use are
// comparisons joined by `&&` and `||`, which are evaluated so the right steps run. A form
// this does not understand stops the run rather than quietly skipping a step. Note that a
// step's `if:` is a condition even when it holds no `${{ }}`: GitHub treats it as one.
function stepRuns(when) {
  if (when === undefined) return true;
  return evaluate(String(when));
}

for (const step of spec.steps || []) {
  const name = step.name || step.uses || 'run';
  if (only && name !== only) continue;
  if (!step.run) {
    console.log(`skip  ${name} (uses: ${step.uses})`);
    continue;
  }
  if (!stepRuns(step.if)) {
    console.log(`skip  ${name} (if: ${step.if})`);
    continue;
  }
  index += 1;
  const path = join(dir, `step-${index}.sh`);
  writeFileSync(path, expand(step.run));
  const env = { ...process.env, ...values };
  for (const [k, v] of Object.entries(step.env || {})) env[k] = expand(String(v));
  // Each step gets a fresh GITHUB_OUTPUT, as a runner does, and what it writes becomes
  // `steps.<id>.outputs.<name>` for the steps after it. Without this, a job could only be
  // run one step at a time with the outputs supplied by hand, and a whole-job run failed at
  // the first step that reads an earlier one's output (`steps.name.outputs.path`).
  const stepOutput = join(dir, `step-${index}.output`);
  writeFileSync(stepOutput, '');
  env.GITHUB_OUTPUT = stepOutput;
  console.log(`run   ${name}`);
  try {
    const out = execFileSync('bash', ['-eo', 'pipefail', path], {
      env,
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    if (out.trim()) console.log(out.trim());
    ran += 1;
  } catch (error) {
    console.error(`FAILED ${name}`);
    if (error.stdout) console.error(error.stdout);
    if (error.stderr) console.error(error.stderr);
    process.exit(1);
  }
  if (step.id) {
    for (const line of readFileSync(stepOutput, 'utf8').split('\n')) {
      const eq = line.indexOf('=');
      if (eq > 0) values[`steps.${step.id}.outputs.${line.slice(0, eq)}`] = line.slice(eq + 1);
    }
  }
  appendFileSync(outputs, readFileSync(stepOutput, 'utf8'));
}
console.log(`ran ${ran} step(s) of ${file} ${job}`);
const written = readFileSync(outputs, 'utf8').trim();
if (written) console.log(`GITHUB_OUTPUT:\n${written}`);
const shown = readFileSync(summary, 'utf8').trim();
if (shown) console.log(`GITHUB_STEP_SUMMARY:\n${shown}`);
