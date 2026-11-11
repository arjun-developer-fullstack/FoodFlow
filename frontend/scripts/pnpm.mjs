// Re-invokes the pnpm that is running the current package.json script, so root scripts work even when
// no `pnpm` command is on PATH, e.g. `corepack pnpm dev` without `corepack enable` (which needs admin
// rights on Windows). pnpm exposes its own entry point as npm_execpath: a native binary in pnpm 11+,
// a JavaScript file in older versions. CI (`corepack enable`) behaves exactly as before.
//
//   "dev": "node scripts/pnpm.mjs --filter @foodflow/customer-web dev"
import { spawnSync } from 'node:child_process';

const pnpm = process.env.npm_execpath;
if (!pnpm) {
  console.error('scripts/pnpm.mjs must be started by pnpm (npm_execpath is not set). Run e.g. `corepack pnpm dev`.');
  process.exit(1);
}

const args = process.argv.slice(2);
const isJavaScript = /\.[cm]?js$/i.test(pnpm);
const result = spawnSync(isJavaScript ? process.execPath : pnpm, isJavaScript ? [pnpm, ...args] : args, {
  stdio: 'inherit',
});

if (result.error) {
  console.error(`Could not start pnpm (${pnpm}): ${result.error.message}`);
  process.exit(1);
}
process.exit(result.status ?? 1);
