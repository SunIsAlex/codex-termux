import { spawn, spawnSync } from 'node:child_process';
import { constants } from 'node:fs';
import { accessSync } from 'node:fs';
import { access, mkdir, open, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';

const upstream = 'https://github.com/miuuyy/codex-chatgpt-web.git';
const version = '5.0.6';
const revision = 'e85e3693fdb4e3e033348c08df0298c20fcdb612';
const tag = `v${version}`;

function paths(env = process.env) {
  const codexHome = resolve(env.CODEX_HOME || join(homedir(), '.codex'));
  const providerHome = join(codexHome, 'chatgpt-web');
  return {
    codexHome,
    providerHome,
    runtime: join(codexHome, 'providers', `codex-chatgpt-web-${version}`),
    config: join(providerHome, 'config.json'),
    journal: join(providerHome, 'codex', 'integration-journal.json'),
    termux: join(providerHome, 'termux.json'),
    logs: join(providerHome, 'logs'),
  };
}

async function exists(path) { return access(path).then(() => true, () => false); }

function executable(name, env = process.env) {
  for (const directory of (env.PATH || '').split(':').filter(Boolean)) {
    const candidate = join(directory, name);
    try { accessSync(candidate, constants.X_OK); return candidate; }
    catch { /* Keep searching PATH. */ }
  }
  return null;
}

function run(command, args, options = {}) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(command, args, { stdio: 'inherit', ...options });
    child.once('error', rejectRun);
    child.once('exit', (code, signal) => {
      if (signal) rejectRun(new Error(`${basename(command)} 被信号 ${signal} 终止`));
      else if (code === 0) resolveRun();
      else rejectRun(new Error(`${basename(command)} 退出码 ${code ?? 1}`));
    });
  });
}

function replaceOnce(text, before, after, path) {
  const first = text.indexOf(before);
  if (first < 0 || text.indexOf(before, first + before.length) >= 0) {
    throw new Error(`无法安全应用 Termux 补丁：${path}`);
  }
  return text.slice(0, first) + after + text.slice(first + before.length);
}

async function javascriptFiles(root) {
  const result = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) result.push(...await javascriptFiles(path));
    else if (entry.isFile() && entry.name.endsWith('.js')) result.push(path);
  }
  return result;
}

export async function patchRuntime(runtime) {
  const setupPath = join(runtime, 'src', 'setup.ts');
  let setup = await readFile(setupPath, 'utf8');
  setup = replaceOnce(
    setup,
    'if (!launcherOwned && process.platform !== "darwin") {',
    'if (!launcherOwned && process.platform !== "darwin" && process.platform !== "android") {',
    setupPath,
  );
  setup = replaceOnce(
    setup,
    `  if (!launcherOwned) {\n    saveConfig(config);\n    installService(config);\n    if (changedWhileLoaded && options.restartService && existing) await restartService(existing);\n    await waitForProxy(config);\n  }`,
    `  if (!launcherOwned) {\n    saveConfig(config);\n    if (process.platform !== "android") {\n      installService(config);\n      if (changedWhileLoaded && options.restartService && existing) await restartService(existing);\n      await waitForProxy(config);\n    }\n  }`,
    setupPath,
  );
  await writeFile(setupPath, setup);

  const playwright = join(runtime, 'node_modules', 'playwright-core', 'lib');
  let replacements = 0;
  for (const path of await javascriptFiles(playwright)) {
    const original = await readFile(path, 'utf8');
    const patched = original
      .replaceAll('process.platform === "linux"', '(process.platform === "linux" || process.platform === "android")')
      .replaceAll('process.platform !== "linux"', '(process.platform !== "linux" && process.platform !== "android")');
    if (patched !== original) { await writeFile(path, patched); replacements += 1; }
  }
  if (replacements === 0) throw new Error('Playwright Android 平台补丁未匹配当前依赖');
  await writeFile(join(runtime, '.termux-patched'), `${version}\n`);
}

function providerEnvironment(env = process.env) {
  const location = paths(env);
  return { ...env, CODEX_CHATGPT_WEB_HOME: location.providerHome, CODEX_HOME: location.codexHome };
}

async function environment(env = process.env) {
  const location = paths(env);
  let saved = {};
  try { saved = JSON.parse(await readFile(location.termux, 'utf8')); } catch { /* No saved display yet. */ }
  return {
    ...providerEnvironment(env),
    ...(env.DISPLAY ? {} : saved.display ? { DISPLAY: saved.display } : {}),
  };
}

function chrome(env = process.env) {
  return executable('chromium-browser', env) || executable('chromium', env) || executable('google-chrome', env);
}

function bun(env = process.env) { return executable('bun', env); }

async function installed(location = paths()) {
  return await exists(join(location.runtime, '.termux-patched'))
    && await exists(join(location.runtime, 'src', 'cli.ts'));
}

export async function installProvider({ env = process.env } = {}) {
  const location = paths(env);
  if (await installed(location)) return location.runtime;
  const git = executable('git', env), bunPath = bun(env), chromePath = chrome(env);
  if (!git) throw new Error('缺少 git，请先执行 pkg install git');
  if (!bunPath) throw new Error('缺少 Bun，请先执行 pkg install bun');
  if (!chromePath) throw new Error('缺少 Chromium，请先执行 pkg install x11-repo chromium');
  if (await exists(location.runtime)) throw new Error(`Provider 目录不完整，拒绝覆盖：${location.runtime}`);

  await mkdir(dirname(location.runtime), { recursive: true, mode: 0o700 });
  const staging = `${location.runtime}.install-${process.pid}`;
  await rm(staging, { recursive: true, force: true });
  try {
    await run(git, ['clone', '--depth', '1', '--branch', tag, upstream, staging], { env });
    const actual = spawnSync(git, ['rev-parse', 'HEAD'], { cwd: staging, encoding: 'utf8', env });
    if (actual.status !== 0 || actual.stdout.trim() !== revision) throw new Error('上游 Git revision 校验失败');
    await run(bunPath, ['install', '--frozen-lockfile', '--production', '--force', '--backend=copyfile'], { cwd: staging, env });
    await patchRuntime(staging);
    await rename(staging, location.runtime);
  } catch (error) {
    await rm(staging, { recursive: true, force: true });
    throw error;
  }
  return location.runtime;
}

async function providerCommand(args, { env = process.env } = {}) {
  const location = paths(env), bunPath = bun(env);
  if (!bunPath) throw new Error('缺少 Bun，请先执行 pkg install bun');
  if (!await installed(location)) throw new Error('ChatGPT Web Provider 尚未安装；请先执行 codex chatgpt-web install');
  return run(bunPath, [join(location.runtime, 'src', 'cli.ts'), '--home', location.providerHome, ...args], {
    cwd: location.runtime,
    env: await environment(env),
  });
}

async function readConfig(location = paths()) {
  try { return JSON.parse(await readFile(location.config, 'utf8')); }
  catch { return null; }
}

export async function routeActive(location = paths()) {
  try { return JSON.parse(await readFile(location.journal, 'utf8')).active === true; }
  catch { return false; }
}

async function health(config) {
  if (!config) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 1500);
  try {
    const response = await fetch(`http://${config.host}:${config.port}/healthz`, { signal: controller.signal });
    if (!response.ok) return null;
    const result = await response.json();
    return result.service === 'codex-chatgpt-web' ? result : null;
  } catch { return null; }
  finally { clearTimeout(timer); }
}

export async function startProvider({ env = process.env, quiet = false } = {}) {
  const location = paths(env);
  if (!await installed(location)) throw new Error('ChatGPT Web Provider 尚未安装');
  const bunPath = bun(env);
  if (!bunPath) throw new Error('缺少 Bun，请先执行 pkg install bun');
  const config = await readConfig(location);
  if (!config) throw new Error('ChatGPT Web Provider 尚未配置；请执行 codex chatgpt-web setup');
  const current = await health(config);
  if (current?.accepting_turns) return current;
  if (current) throw new Error('ChatGPT Web Provider 正在停止，请稍后重试');

  await mkdir(location.logs, { recursive: true, mode: 0o700 });
  const log = await open(join(location.logs, 'termux-daemon.log'), 'a', 0o600);
  const runtimeEnv = await environment(env);
  try {
    const child = spawn(bunPath, [join(location.runtime, 'src', 'cli.ts'), '--home', location.providerHome, 'serve'], {
      cwd: location.runtime,
      detached: true,
      stdio: ['ignore', log.fd, log.fd],
      env: runtimeEnv,
    });
    child.unref();
  } finally { await log.close(); }
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    const ready = await health(config);
    if (ready?.accepting_turns) { if (!quiet) console.log(`ChatGPT Web Provider 已启动（PID ${ready.pid}）`); return ready; }
    await new Promise(resolveWait => setTimeout(resolveWait, 250));
  }
  throw new Error(`ChatGPT Web Provider 启动超时，请检查 ${join(location.logs, 'termux-daemon.log')}`);
}

async function stopProvider({ env = process.env } = {}) {
  const config = await readConfig(paths(env)), current = await health(config);
  if (!current) { console.log('ChatGPT Web Provider 未运行'); return; }
  const headers = { authorization: `Bearer ${config.controlToken}` };
  const drain = await fetch(`http://${config.host}:${config.port}/admin/drain`, { method: 'POST', headers });
  const state = await drain.json();
  if (!drain.ok || state.active_http_turns || state.active_browser_turns) {
    await fetch(`http://${config.host}:${config.port}/admin/resume`, { method: 'POST', headers }).catch(() => {});
    throw new Error('Provider 仍有活动任务，拒绝停止');
  }
  const stopped = await fetch(`http://${config.host}:${config.port}/admin/shutdown`, { method: 'POST', headers });
  if (!stopped.ok) throw new Error(`Provider 拒绝停止（HTTP ${stopped.status}）`);
  console.log('ChatGPT Web Provider 已停止');
}

export async function ensureProvider({ env = process.env } = {}) {
  const location = paths(env);
  if (!await routeActive(location)) return null;
  return startProvider({ env, quiet: true });
}

function usage() {
  console.log(`用法: codex chatgpt-web <命令>\n\n命令:\n  install   安装固定版本的 ChatGPT Web bridge\n  setup     登录并启用 Browser-only Provider（需要 Termux:X11）\n  login     刷新 ChatGPT 网页登录\n  start     启动本地 Responses bridge\n  stop      安全停止 bridge\n  status    显示安装、路由和运行状态\n  doctor    运行上游诊断\n  route ... 管理 Codex 路由\n\n这是非官方网页自动化；当前 Android 版本不支持 Full harness。`);
}

export async function main(args = process.argv.slice(2), { env = process.env } = {}) {
  const command = args.shift() || 'help';
  if (command === 'help' || command === '--help' || command === '-h') return usage();
  if (command === 'install') {
    if (args.length) throw new Error('install 不接受其他参数');
    const runtime = await installProvider({ env }); console.log(`ChatGPT Web Provider ${version} 已安装：${runtime}`); return;
  }
  if (command === 'setup') {
    if (args.includes('--full')) throw new Error('Android 暂不支持 Full harness；请使用 Browser-only 模式');
    args = args.filter(arg => arg !== '--browser-only');
    const display = env.DISPLAY;
    if (!display) throw new Error('setup 需要 Termux:X11；请先启动 X11 并设置 DISPLAY（通常为 :0）');
    await installProvider({ env });
    const location = paths(env); await mkdir(location.providerHome, { recursive: true, mode: 0o700 });
    await writeFile(location.termux, `${JSON.stringify({ display })}\n`, { mode: 0o600 });
    await providerCommand(['setup', '--browser-only', '--chrome', chrome(env), '--acknowledge-unofficial', ...args], { env });
    await startProvider({ env }); return;
  }
  if (command === 'login') {
    if (args.length) throw new Error('login 不接受其他参数');
    if (!env.DISPLAY) throw new Error('login 需要 Termux:X11 和 DISPLAY');
    await providerCommand(['login'], { env }); return;
  }
  if (command === 'start') { if (args.length) throw new Error('start 不接受其他参数'); await startProvider({ env }); return; }
  if (command === 'stop') { if (args.length) throw new Error('stop 不接受其他参数'); await stopProvider({ env }); return; }
  if (command === 'status') {
    if (args.length) throw new Error('status 不接受其他参数');
    const location = paths(env), config = await readConfig(location), running = await health(config);
    console.log(JSON.stringify({ installed: await installed(location), configured: Boolean(config), routeActive: await routeActive(location), running: Boolean(running), health: running }, null, 2)); return;
  }
  if (command === 'doctor') { await providerCommand(['doctor', ...args], { env }); return; }
  if (command === 'route') { await providerCommand(['route', ...args], { env }); return; }
  throw new Error(`未知的 chatgpt-web 命令：${command}`);
}
