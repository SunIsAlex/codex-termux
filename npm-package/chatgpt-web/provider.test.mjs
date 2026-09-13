import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { patchRuntime, routeActive } from './provider.mjs';

test('patches upstream setup and Playwright for Android', async () => {
  const runtime = await mkdtemp(join(tmpdir(), 'chatgpt-web-provider-'));
  const setupPath = join(runtime, 'src', 'setup.ts');
  const playwrightPath = join(runtime, 'node_modules', 'playwright-core', 'lib', 'core.js');
  await mkdir(join(runtime, 'src'), { recursive: true });
  await mkdir(join(runtime, 'node_modules', 'playwright-core', 'lib'), { recursive: true });
  await writeFile(setupPath, `if (!launcherOwned && process.platform !== "darwin") {\n}\n  if (!launcherOwned) {\n    saveConfig(config);\n    installService(config);\n    if (changedWhileLoaded && options.restartService && existing) await restartService(existing);\n    await waitForProxy(config);\n  }\n`);
  await writeFile(playwrightPath, 'if (process.platform === "linux") ok();\nif (process.platform !== "linux") no();\n');

  await patchRuntime(runtime);

  assert.match(await readFile(setupPath, 'utf8'), /process\.platform !== "android"/);
  assert.equal(await readFile(playwrightPath, 'utf8'), 'if ((process.platform === "linux" || process.platform === "android")) ok();\nif ((process.platform !== "linux" && process.platform !== "android")) no();\n');
});

test('reads the upstream reversible-route journal', async () => {
  const root = await mkdtemp(join(tmpdir(), 'chatgpt-web-journal-'));
  const location = { journal: join(root, 'journal.json') };
  assert.equal(await routeActive(location), false);
  await writeFile(location.journal, JSON.stringify({ active: true }));
  assert.equal(await routeActive(location), true);
  await writeFile(location.journal, JSON.stringify({ active: false }));
  assert.equal(await routeActive(location), false);
});
