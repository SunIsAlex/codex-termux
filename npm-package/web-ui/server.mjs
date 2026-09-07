import http from 'node:http';
import { randomBytes, randomUUID } from 'node:crypto';
import { promises as fs, createReadStream, createWriteStream } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { Bridge } from './bridge.mjs';
import { createNotifications } from './notifications.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const limit = 10 * 1024 * 1024;
const allowed = new Set(['account/rateLimits/read', 'account/rateLimitResetCredit/consume', 'account/read', 'model/list', 'thread/list', 'thread/read', 'thread/turns/list', 'thread/items/list', 'thread/resume', 'thread/fork', 'thread/start', 'turn/start', 'turn/interrupt']);
function options(args) {
  const result = { open: true, port: 0 };
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--no-open') result.open = false;
    else if (arg === '--port') {
      const port = Number(args[++index]);
      if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('--port 必须是 0 到 65535 的整数');
      result.port = port;
    } else if (arg === '-h' || arg === '--help') result.help = true;
    else throw new Error(`未知的 codex web 参数：${arg}`);
  }
  return result;
}
function usage() { console.log('用法: codex web [--no-open] [--port PORT]\n\n启动仅监听本机的轻量 Codex Web GUI。'); }
async function jsonBody(req) {
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > 65536) throw new Error('请求过大'); chunks.push(chunk); }
  return JSON.parse(Buffer.concat(chunks).toString());
}
function imageType(b) {
  if (b.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))) return 'image/png';
  if (b[0] === 255 && b[1] === 216 && b[2] === 255) return 'image/jpeg';
  if (b.toString('ascii', 0, 4) === 'RIFF' && b.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  throw new Error('只支持 JPEG、PNG、WebP');
}
export async function start(args = []) {
  const launch = options(args);
  if (launch.help) { usage(); return; }
  const root = join(process.env.CODEX_HOME || join(homedir(), '.codex'), 'web-ui');
  await fs.mkdir(root, { recursive: true, mode: 0o700 });
  const attachments = new Map();
  // Track successful writer acquisition, including restored threads.
  const ownedThreads = new Set();
  for (const name of await fs.readdir(root)) {
    if (!/^[a-f0-9-]+\.json$/.test(name)) continue;
    try {
      const value = JSON.parse(await fs.readFile(join(root, name), 'utf8'));
      const id = name.slice(0, -5);
      if (!value.sent && Date.now() - value.created > 86400000) {
        await fs.unlink(join(root, id)).catch(() => {}); await fs.unlink(join(root, name));
      } else attachments.set(id, value);
    } catch { /* Ignore incomplete metadata left by an interrupted upload. */ }
  }
  const bootToken = randomBytes(32).toString('hex');
  const cookie = randomBytes(32).toString('hex');
  const csrf = randomBytes(32).toString('hex');
  let origin, stream, owner, lastSeen = 0, eventId = 0, bridge;
  const events = []; let eventBytes = 0;
  const notifications = createNotifications();
  const publish = message => {
    if (message.id !== undefined && message.method && !['item/commandExecution/requestApproval', 'item/fileChange/requestApproval', 'item/tool/requestUserInput', 'tool/requestUserInput', 'mcpServer/elicitation/request'].includes(message.method)) {
      queueMicrotask(() => bridge.unsupported(message.id));
      message = { method: 'bridge/unsupported', params: { message: `不支持的交互：${message.method}` } };
    }
    notifications?.event(message);
    const data = JSON.stringify(message);
    const event = `id: ${++eventId}\ndata: ${data}\n\n`;
    events.push(event); eventBytes += Buffer.byteLength(event);
    while (events.length > 256 || eventBytes > 1024 * 1024) eventBytes -= Buffer.byteLength(events.shift());
    if (stream && !stream.write(event)) { stream.end(); stream = null; }
  };
  const save = async id => fs.writeFile(join(root, id + '.json'), JSON.stringify(attachments.get(id)), { mode: 0o600 });
  const server = http.createServer(async (req, res) => {
    const reply = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); };
    try {
      if (req.headers.host !== new URL(origin).host) return reply(403, { error: 'Invalid host' });
      const url = new URL(req.url, origin);
      res.setHeader('Content-Security-Policy', "default-src 'self'; img-src 'self' blob:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
      res.setHeader('Referrer-Policy', 'no-referrer'); res.setHeader('X-Content-Type-Options', 'nosniff');
      if (url.pathname === '/auth' && req.method === 'POST') {
        if (req.headers.origin !== origin || (await jsonBody(req)).token !== bootToken) return reply(403, { error: '启动链接无效' });
        res.setHeader('Set-Cookie', `codex_web=${cookie}; HttpOnly; SameSite=Strict; Path=/`);
        return reply(200, { ok: true });
      }
      const asset = { '/': ['index.html', 'text/html'], '/app.js': ['app.js', 'text/javascript'], '/usage.js': ['usage.js', 'text/javascript'], '/markdown.js': ['markdown.js', 'text/javascript'], '/vendor/marked.js': ['vendor/marked.js', 'text/javascript'], '/vendor/purify.js': ['vendor/purify.js', 'text/javascript'], '/style.css': ['style.css', 'text/css'] }[url.pathname];
      if (asset && req.method === 'GET') { res.setHeader('Content-Type', asset[1]); return res.end(await fs.readFile(join(here, asset[0]))); }
      if (!(req.headers.cookie || '').split(';').some(v => v.trim() === `codex_web=${cookie}`)) return reply(401, { error: '请使用 Termux 显示的启动链接打开' });
      if (req.method === 'GET' && url.pathname === '/session') return reply(200, { csrf, cwd: process.cwd() });
      if (req.method === 'GET' && url.pathname === '/events') {
        const client = url.searchParams.get('client');
        if (!client || (owner && owner !== client && Date.now() - lastSeen < 15000)) return reply(409, { error: '另一页面正在控制会话' });
        owner = client; lastSeen = Date.now(); stream?.end(); stream = res;
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
        // Reconcile authoritative state on every reconnect rather than replaying partial deltas.
        res.write(`data: ${JSON.stringify({ method: 'bridge/sync', params: { requests: [...bridge.requests.values()] } })}\n\n`);
        const heartbeat = setInterval(() => { if (stream === res) { lastSeen = Date.now(); res.write(': heartbeat\n\n'); } }, 5000);
        req.on('close', () => { clearInterval(heartbeat); if (stream === res) stream = null; }); return;
      }
      if (req.method === 'GET' && url.pathname.startsWith('/images/')) {
        const id = url.pathname.slice(8), entry = attachments.get(id);
        if (!entry) return reply(404, { error: '图片已清理' });
        res.setHeader('Content-Type', entry.type); createReadStream(join(root, id)).on('error', () => res.destroy()).pipe(res); return;
      }
      if (req.method !== 'POST' || req.headers.origin !== origin || req.headers['x-csrf-token'] !== csrf || req.headers['x-client-id'] !== owner || Date.now() - lastSeen > 15000) return reply(403, { error: '页面未连接或由其他页面控制' });
      if (url.pathname === '/upload') {
        const id = randomUUID(), path = join(root, id); let size = 0, head = Buffer.alloc(0);
        try {
          await pipeline(req, new Transform({ transform(chunk, encoding, done) {
            size += chunk.length; if (size > limit) return done(new Error('每张图片最大 10 MiB'));
            if (head.length < 12) head = Buffer.concat([head, chunk.subarray(0, 12 - head.length)]);
            done(null, chunk);
          } }), createWriteStream(path, { flags: 'wx', mode: 0o600 }));
          attachments.set(id, { type: imageType(head), created: Date.now(), sent: false }); await save(id);
          return reply(200, { id });
        } catch (error) { await fs.unlink(path).catch(() => {}); throw error; }
      }
      const body = await jsonBody(req);
      if (url.pathname === '/answer') { bridge.answer(body.id, body.result); return reply(200, {}); }
      if (url.pathname === '/cleanup') {
        for (const [id] of attachments) { await fs.unlink(join(root, id)).catch(() => {}); await fs.unlink(join(root, id + '.json')).catch(() => {}); attachments.delete(id); }
        return reply(200, {});
      }
      if (url.pathname !== '/rpc' || !allowed.has(body.method)) return reply(400, { error: '不支持的操作' });
      const params = body.params || {};
      if (body.method === 'thread/resume') {
        try {
          const result = await bridge.call('thread/resume', params);
          ownedThreads.add(result.thread.id);
          return reply(200, { ...result, webReadOnly: false });
        } catch (error) {
          ownedThreads.delete(params.threadId);
          // Only a writer lease conflict warrants read-only fallback. Surface
          // missing threads, configuration failures, and timeouts unchanged.
          if (!error.message.includes(`thread ${params.threadId} already has an active writer`)) throw error;
          const result = await bridge.call('thread/read', { threadId: params.threadId, includeTurns: false });
          return reply(200, { ...result, webReadOnly: true });
        }
      }
      if (['turn/start', 'turn/interrupt'].includes(body.method) && !ownedThreads.has(params.threadId)) {
        throw new Error('此会话属于其他客户端，请先在 Web 中创建分支');
      }
      if (body.method === 'thread/start') {
        params.cwd = resolve(params.cwd || process.cwd());
        if (!(await fs.stat(params.cwd)).isDirectory()) throw new Error('项目路径不是目录');
      }
      if (body.method === 'turn/start') {
        const ids = body.images || []; if (!Array.isArray(ids) || ids.length > 4) throw new Error('最多 4 张图片');
        if (!Array.isArray(params.input) || params.input.some(i => i.type !== 'text' || typeof i.text !== 'string')) throw new Error('输入格式无效');
        for (const id of ids) {
          const entry = attachments.get(id); if (!entry) throw new Error('图片不存在，请重新选择');
          entry.sent = true; await save(id); params.input.push({ type: 'localImage', path: join(root, id) });
        }
      }
      const result = await bridge.call(body.method, params);
      if (body.method === 'thread/start' || body.method === 'thread/fork') ownedThreads.add(result.thread.id);
      return reply(200, result);
    } catch (error) { if (!res.headersSent && !res.destroyed) reply(400, { error: error.message }); }
  });
  const launcher = join(here, '..', 'bin', 'codex.js');
  const bundled = await fs.access(join(here, '..', 'bin', 'codex.bin')).then(() => true, () => false);
  bridge = new Bridge(bundled ? process.execPath : 'codex', bundled ? [launcher, 'app-server', '--stdio'] : ['app-server', '--stdio'], process.env, publish);
  try { await bridge.call('initialize', { clientInfo: { name: 'codex_termux_web', version: '0.1.0' }, capabilities: { experimentalApi: true } }); }
  catch (error) { bridge.close(); throw error; }
  bridge.write({ method: 'initialized' });
  await new Promise((yes, no) => { server.once('error', no); server.listen(launch.port, '127.0.0.1', yes); });
  origin = `http://127.0.0.1:${server.address().port}`;
  const link = `${origin}/#${bootToken}`; console.log(`Codex Web: ${link}`);
  if (launch.open) { const opener = spawn('termux-open-url', [link], { stdio: 'ignore' }); opener.on('error', () => {}); opener.unref(); }
  const stop = () => { stream?.end(); server.close(); bridge.close(); void notifications?.close(); };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
}
