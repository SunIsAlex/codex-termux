import net from 'node:net';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { BridgeController } from '../../../npm-package/web-ui/bridge.mjs';

const methods = new Set(['model/list', 'account/read', 'account/rateLimits/read',
  'thread/list', 'thread/start', 'thread/resume', 'thread/fork', 'thread/read',
  'thread/items/list', 'thread/turns/list', 'turn/start', 'turn/interrupt']);
const interactions = new Set(['item/commandExecution/requestApproval',
  'item/fileChange/requestApproval', 'item/tool/requestUserInput', 'tool/requestUserInput',
  'mcpServer/elicitation/request']);
const maxFrame = 1024 * 1024;

// The process, writer leases and pending approvals outlive the Android socket.
// Only bounded notifications are streamed; reconnect fetches authoritative history.
export async function startService({ port = 8766, token, bridgeFactory } = {}) {
  if (!token || !/^[a-f0-9]{64}$/.test(token)) throw new Error('Invalid pairing token');
  const clients = new Set();
  let owner;
  const send = (socket, message) => {
    if (socket.destroyed) return;
    const line = JSON.stringify(message) + '\n';
    if (Buffer.byteLength(line) > 8 * maxFrame || socket.writableLength > 8 * maxFrame) {
      socket.destroy(); return;
    }
    socket.write(line);
  };
  const publish = message => {
    if (message.method && message.id !== undefined && !interactions.has(message.method)) {
      queueMicrotask(() => { try { bridge.unsupported(message.id); } catch {} });
      return;
    }
    if (owner) send(owner, { event: message });
  };
  const bridge = bridgeFactory ? bridgeFactory(publish) : new BridgeController(
    'codex', ['app-server', '--stdio'], process.env, publish,
    { initializeParams: { clientInfo: { name: 'codex_native_android', version: '0.1.0' },
      capabilities: { experimentalApi: true } } },
  );
  await bridge.start();
  const server = net.createServer(socket => {
    clients.add(socket);
    socket.setNoDelay(true);
    let authenticated = false, buffer = Buffer.alloc(0), inflight = 0;
    const timer = setTimeout(() => socket.destroy(), 5000);
    socket.on('error', () => {});
    socket.on('close', () => { clearTimeout(timer); clients.delete(socket); if (owner === socket) owner = null; });
    const handle = async message => {
      if (!authenticated) {
        const supplied = Buffer.from(typeof message.token === 'string' ? message.token : '');
        if (supplied.length !== token.length || !timingSafeEqual(supplied, Buffer.from(token))) {
          socket.end(JSON.stringify({ error: 'Pairing code rejected' }) + '\n'); return;
        }
        authenticated = true; clearTimeout(timer);
        owner?.destroy(); owner = socket;
        send(socket, { event: { method: 'native/ready', params: { ready: bridge.ready,
          cwd: process.cwd(), requests: [...bridge.requests.values()] } } });
        return;
      }
      const { id, method, params = {} } = message;
      if (!Number.isSafeInteger(id) || !params || typeof params !== 'object' || Array.isArray(params)) {
        socket.destroy(); return;
      }
      if (++inflight > 32) { socket.destroy(); return; }
      try {
        let result;
        if (method === 'native/ping') result = { ready: bridge.ready };
        else if (method === 'native/answer') {
          bridge.answer(params.id, params.result); result = {};
        } else {
          if (!methods.has(method)) throw new Error('Unsupported method');
          result = await bridge.call(method, params);
        }
        send(socket, { id, result });
      } catch (error) { send(socket, { id, error: { message: error.message } }); }
      finally { inflight--; }
    };
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      let newline;
      while ((newline = buffer.indexOf(10)) !== -1) {
        if (newline > maxFrame) { socket.destroy(); return; }
        const line = buffer.subarray(0, newline); buffer = buffer.subarray(newline + 1);
        try {
          const message = JSON.parse(line.toString('utf8'));
          if (!message || typeof message !== 'object' || Array.isArray(message)) { socket.destroy(); return; }
          void handle(message).catch(() => socket.destroy());
        }
        catch { socket.destroy(); return; }
      }
      if (buffer.length > maxFrame) socket.destroy();
    });
  });
  try {
    await new Promise((yes, no) => { server.once('error', no); server.listen(port, '127.0.0.1', yes); });
  } catch (error) { bridge.close(); throw error; }
  return { port: server.address().port, close: async () => {
    for (const socket of clients) socket.destroy();
    bridge.close();
    await new Promise(done => server.close(done));
  } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const root = join(process.env.CODEX_HOME || join(homedir(), '.codex'), 'native-android');
  await mkdir(root, { recursive: true, mode: 0o700 });
  const tokenPath = join(root, 'pairing-token');
  try { await writeFile(tokenPath, randomBytes(32).toString('hex'), { flag: 'wx', mode: 0o600 }); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  const token = (await readFile(tokenPath, 'utf8')).trim();
  const service = await startService({ token });
  console.log(`Codex Android service: 127.0.0.1:${service.port}\nPairing code (paste into app):\n${token}`);
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { void service.close(); });
}
