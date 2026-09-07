import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ProgressNotifications } from './notifications.mjs';

test('running, plan, approval, reply, completion and cleanup update one notification', async () => {
  const calls = [];
  const notifier = new ProgressNotifications('notify', 'remove', async (command, args) => calls.push([command, args]));
  const emit = async (method, params, id) => {
    notifier.event({ method, params, ...(id === undefined ? {} : { id }) });
    await notifier.worker;
  };
  await emit('turn/started', { threadId: 'a' });
  await emit('turn/plan/updated', { threadId: 'a', plan: [{ status: 'completed' }, { status: 'pending' }] });
  await emit('item/commandExecution/requestApproval', { threadId: 'a' }, 12);
  await emit('serverRequest/resolved', { requestId: 12 });
  await emit('turn/completed', { threadId: 'a' });
  await notifier.close();
  assert.deepEqual(calls.slice(0, -1).map(([, args]) => args[args.indexOf('--content') + 1]), [
    'Working', 'Working · Tasks 1/2', 'Waiting for your input', 'Working · Tasks 1/2', 'Ready',
  ]);
  assert.deepEqual(calls.slice(0, -1).map(([, args]) => args.includes('--ongoing')), [true, true, false, true, false]);
  assert.deepEqual(calls.at(-1), ['remove', [notifier.id]]);
});

test('coalesces updates and preserves other active turns when one completes', async () => {
  const calls = [];
  const notifier = new ProgressNotifications('notify', 'remove', async (command, args) => calls.push([command, args]));
  notifier.event({ method: 'turn/started', params: { threadId: 'a' } });
  notifier.event({ method: 'turn/started', params: { threadId: 'b' } });
  notifier.event({ method: 'turn/completed', params: { threadId: 'a' } });
  await notifier.worker;
  assert.equal(calls.length, 1);
  assert.equal(calls[0][1].includes('--ongoing'), true);
  await notifier.close();
});

test('unavailable API disables notifications without rejecting event processing', async () => {
  const calls = [];
  const notifier = new ProgressNotifications('notify', 'remove', async command => {
    calls.push(command); if (command === 'notify') throw new Error('unavailable');
  });
  notifier.event({ method: 'turn/started', params: { threadId: 'a' } });
  await notifier.worker;
  notifier.event({ method: 'turn/started', params: { threadId: 'b' } });
  assert.deepEqual(calls, ['notify', 'remove']);
});

test('Ready is delivered after a duplicate Working update finishes synchronously', async () => {
  const contents = [];
  const notifier = new ProgressNotifications('notify', 'remove', async (command, args) => {
    if (command === 'notify') contents.push(args[args.indexOf('--content') + 1]);
  });
  notifier.event({ method: 'turn/started', params: { threadId: 'a' } });
  await notifier.worker;
  // An unrecognized item keeps the status at Working, triggering deduplication
  // while no previous worker is running.
  notifier.event({ method: 'item/started', params: { threadId: 'a', item: { type: 'other' } } });
  await notifier.worker;
  assert.equal(notifier.worker, null);
  notifier.event({ method: 'turn/completed', params: { threadId: 'a' } });
  await notifier.worker;
  assert.deepEqual(contents, ['Working', 'Ready']);
  await notifier.close();
});
