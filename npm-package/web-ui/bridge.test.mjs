import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BridgeController } from './bridge.mjs';

const tick = () => new Promise(resolve => setTimeout(resolve, 5));

async function waitFor(predicate) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (predicate()) return;
    await tick();
  }
  throw new Error('condition was not reached');
}

class FakeBridge {
  static instances = [];
  static initialization = [];

  constructor(command, args, env, publish, onFailure) {
    this.requests = new Map(); this.publish = publish; this.onFailure = onFailure;
    this.initializes = FakeBridge.initialization.shift() ?? true;
    FakeBridge.instances.push(this);
  }
  call(method, params) {
    this.lastCall = { method, params };
    if (method === 'initialize' && !this.initializes) return Promise.reject(new Error('offline'));
    if (this.callError) return Promise.reject(this.callError);
    return Promise.resolve({ method, params });
  }
  write(message) { this.lastWrite = message; }
  answer(id, result) { this.lastAnswer = { id, result }; }
  unsupported(id) { this.lastUnsupported = id; }
  close() { this.closed = true; }
  fail(error = new Error('lost')) { this.onFailure(error); }
}

function controller(events, resets = () => {}) {
  return new BridgeController('codex', [], {}, message => events.push(message), {
    BridgeClass: FakeBridge,
    initializeParams: { clientInfo: { name: 'test' } },
    onReset: resets,
    restartDelays: [0],
  });
}

test('restarts a failed bridge and publishes authoritative sync', async () => {
  FakeBridge.instances = []; FakeBridge.initialization = [true, true];
  const events = []; let resets = 0;
  const bridge = controller(events, () => { resets += 1; });
  await bridge.start();
  const first = FakeBridge.instances[0];

  first.fail();
  await waitFor(() => bridge.ready && FakeBridge.instances.length === 2);

  assert.equal(resets, 1);
  assert.deepEqual(events.map(event => event.method), ['bridge/error', 'bridge/reconnecting', 'bridge/sync']);
  assert.deepEqual(FakeBridge.instances[1].lastWrite, { method: 'initialized' });
  assert.deepEqual(await bridge.call('account/read'), { method: 'account/read', params: {} });
  bridge.close();
});

test('keeps retrying when initialization fails during recovery', async () => {
  FakeBridge.instances = []; FakeBridge.initialization = [true, false, true];
  const events = [];
  const bridge = controller(events);
  await bridge.start();

  FakeBridge.instances[0].fail(new Error('network lost'));
  await waitFor(() => bridge.ready && FakeBridge.instances.length === 3);

  assert.equal(events.filter(event => event.method === 'bridge/sync').length, 1);
  assert.equal(events.filter(event => event.method === 'bridge/reconnecting').length, 2);
  assert.equal(FakeBridge.instances[1].closed, true);
  bridge.close();
});

test('does not restart after an intentional close', async () => {
  FakeBridge.instances = []; FakeBridge.initialization = [true];
  const events = [];
  const bridge = controller(events);
  await bridge.start();
  const child = FakeBridge.instances[0];

  bridge.close();
  child.fail();
  await tick();

  assert.equal(FakeBridge.instances.length, 1);
  assert.deepEqual(events, []);
});

test('recycles an unresponsive bridge after an RPC timeout', async () => {
  FakeBridge.instances = []; FakeBridge.initialization = [true, true];
  const events = [];
  const bridge = controller(events);
  await bridge.start();
  const first = FakeBridge.instances[0];
  const error = new Error('request timeout'); error.code = 'BRIDGE_REQUEST_TIMEOUT';
  first.callError = error;

  await assert.rejects(bridge.call('model/list'), error);
  await waitFor(() => bridge.ready && FakeBridge.instances.length === 2);

  assert.equal(first.closed, true);
  assert.deepEqual(events.map(event => event.method), ['bridge/error', 'bridge/reconnecting', 'bridge/sync']);
  bridge.close();
});
