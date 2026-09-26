import assert from 'node:assert/strict';
import { test } from 'node:test';
import net from 'node:net';
import { once } from 'node:events';
import { startService } from '../service/server.mjs';

const token = 'a'.repeat(64);
async function fixture(t) {
  const calls = [];
  let publish, closed = false;
  const requests = new Map([['7', { id: 7, method: 'item/commandExecution/requestApproval', params: { command: 'pwd' } }]]);
  const service = await startService({ port: 0, token, bridgeFactory: emit => {
    publish = emit;
    return { ready: true, requests, start: async () => {}, close: () => { closed = true; },
      call: async (method, params) => {
        calls.push({ method, params });
        if (method === 'thread/read') throw new Error('writer unavailable');
        return { data: [{ id: 'gpt-6-sol', text: '你好 🌱' }] };
      },
      answer: (id, result) => { assert.equal(id, 7); assert.deepEqual(result, { decision: 'decline' }); requests.delete(String(id)); },
      unsupported: id => requests.delete(String(id)),
    };
  } });
  t.after(() => service.close());
  async function connect() {
    const socket = net.connect(service.port, '127.0.0.1');
    socket.on('error', () => {});
    const queue = [], waiters = []; let text = '';
    socket.on('data', chunk => {
      text += chunk.toString();
      while (text.includes('\n')) {
        const end = text.indexOf('\n'), value = JSON.parse(text.slice(0, end)); text = text.slice(end + 1);
        if (waiters.length) waiters.shift()(value); else queue.push(value);
      }
    });
    await once(socket, 'connect'); t.after(() => socket.destroy());
    return { socket, send: value => socket.write(JSON.stringify(value) + '\n'),
      next: () => queue.length ? Promise.resolve(queue.shift()) : new Promise(resolve => waiters.push(resolve)) };
  }
  return { connect, calls, publish: message => publish(message), isClosed: () => closed };
}

test('rejects wrong credentials before any Codex call', { timeout: 3000 }, async t => {
  const f = await fixture(t), client = await f.connect();
  client.send({ token: 'b'.repeat(64) });
  assert.deepEqual(await client.next(), { error: 'Pairing code rejected' });
  assert.deepEqual(f.calls, []);
});

test('handles split UTF-8 frames and multiple RPCs in one packet', { timeout: 3000 }, async t => {
  const f = await fixture(t), client = await f.connect(); client.send({ token }); await client.next();
  const frame = Buffer.from(JSON.stringify({ id: 1, method: 'thread/start', params: { cwd: '/你好' } }) + '\n');
  const split = frame.indexOf(Buffer.from('你')) + 1;
  client.socket.write(frame.subarray(0, split)); client.socket.write(frame.subarray(split));
  assert.equal((await client.next()).id, 1);
  assert.deepEqual(f.calls, [{ method: 'thread/start', params: { cwd: '/你好' } }]);
  client.socket.write('{"id":2,"method":"native/ping"}\n{"id":3,"method":"model/list"}\n');
  assert.equal((await client.next()).id, 2); assert.equal((await client.next()).id, 3);
});

test('reconnect keeps the backend and restores unanswered approvals without resending work', { timeout: 3000 }, async t => {
  const f = await fixture(t), first = await f.connect(); first.send({ token }); await first.next();
  first.send({ id: 1, method: 'turn/start', params: { threadId: 't', input: [] } }); await first.next();
  first.socket.destroy();
  const second = await f.connect(); second.send({ token });
  const ready = await second.next(); assert.equal(ready.event.params.requests[0].id, 7);
  assert.equal(f.isClosed(), false); assert.equal(f.calls.length, 1);
  second.send({ id: 2, method: 'native/answer', params: { id: 7, result: { decision: 'decline' } } });
  assert.deepEqual(await second.next(), { id: 2, result: {} });
  f.publish({ method: 'turn/completed', params: { threadId: 't' } });
  assert.equal((await second.next()).event.method, 'turn/completed');
});

test('a paired replacement disconnects the previous controller', { timeout: 3000 }, async t => {
  const f = await fixture(t), first = await f.connect(); first.send({ token }); await first.next();
  const disconnected = once(first.socket, 'close');
  const second = await f.connect(); second.send({ token }); await second.next(); await disconnected;
  assert.equal(f.isClosed(), false);
});

test('RPC failures and unsupported methods are returned without killing the service', { timeout: 3000 }, async t => {
  const f = await fixture(t), client = await f.connect(); client.send({ token }); await client.next();
  client.send({ id: 1, method: 'thread/read' });
  assert.deepEqual(await client.next(), { id: 1, error: { message: 'writer unavailable' } });
  client.send({ id: 2, method: 'arbitrary/execute' });
  assert.deepEqual(await client.next(), { id: 2, error: { message: 'Unsupported method' } });
  client.send({ id: 3, method: 'native/ping' });
  assert.deepEqual(await client.next(), { id: 3, result: { ready: true } });
});

test('oversized and malformed input disconnects without forwarding to Codex', { timeout: 3000 }, async t => {
  const f = await fixture(t), client = await f.connect();
  const closed = once(client.socket, 'close'); client.socket.write('x'.repeat(1024 * 1024 + 1)); await closed;
  const other = await f.connect(), otherClosed = once(other.socket, 'close'); other.socket.write('invalid json\n'); await otherClosed;
  const invalid = await f.connect(), invalidClosed = once(invalid.socket, 'close'); invalid.socket.write('null\n'); await invalidClosed;
  assert.deepEqual(f.calls, []);
});
