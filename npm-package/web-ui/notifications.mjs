import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { basename, join } from 'node:path';

const clean = value => String(value).replace(/[\x00-\x1f\x7f]/g, ' ').replace(/\s+/g, ' ').slice(0, 240);

// One subprocess at a time, with intermediate updates replaced by the latest.
export class ProgressNotifications {
  constructor(notify, remove, run = runCommand) {
    this.notify = notify; this.remove = remove; this.run = run;
    this.id = `codex-web-progress-${process.pid}`;
    this.turns = new Map(); this.requests = new Map();
    this.pending = null; this.last = null; this.worker = null; this.closed = false;
  }

  event(message) {
    if (this.closed) return;
    const p = message.params || {}, method = message.method;
    if (method === 'bridge/error') { void this.close(); return; }
    if (method === 'turn/started') this.turns.set(p.threadId, { status: 'Working', tasks: '' });
    if (method === 'turn/plan/updated' && this.turns.has(p.threadId)) {
      const plan = p.plan || [];
      this.turns.get(p.threadId).tasks = plan.length ? ` · Tasks ${plan.filter(task => task.status === 'completed').length}/${plan.length}` : '';
    }
    if (method === 'item/started' && this.turns.has(p.threadId)) {
      this.turns.get(p.threadId).status = ({ commandExecution: 'Running command', fileChange: 'Editing files', mcpToolCall: 'Calling tool', reasoning: 'Thinking', agentMessage: 'Responding' })[p.item.type] || 'Working';
    }
    if (message.id !== undefined && method) this.requests.set(String(message.id), p.threadId);
    if (method === 'serverRequest/resolved') this.requests.delete(String(p.requestId));
    if (method === 'turn/completed') {
      this.turns.delete(p.threadId);
      for (const [id, thread] of this.requests) if (thread === p.threadId) this.requests.delete(id);
    }
    if (!['turn/started', 'turn/completed', 'turn/plan/updated', 'item/started', 'serverRequest/resolved'].includes(method) && message.id === undefined) return;
    if (!this.turns.size && !this.requests.size && !this.last && !this.worker) return;
    const current = [...this.turns.values()].at(-1);
    const waiting = this.requests.size > 0;
    const status = waiting ? 'Waiting for your input' : current ? current.status + current.tasks : 'Ready';
    const args = ['--id', this.id, '--title', clean(`Codex Web · ${basename(process.cwd())}`), '--content', clean(status), '--priority', 'low', '--alert-once'];
    if (current && !waiting) args.push('--ongoing');
    this.pending = args;
    if (!this.worker) this.worker = this.flush();
  }

  async flush() {
    try {
      while (this.pending && !this.closed) {
        const args = this.pending; this.pending = null;
        if (JSON.stringify(args) === this.last) continue;
        this.last = JSON.stringify(args);
        await this.run(this.notify, args);
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
    } catch {
      this.closed = true;
      await this.run(this.remove, [this.id]).catch(() => {});
    } finally { this.worker = null; }
  }

  async close() {
    this.closed = true; this.pending = null;
    await this.worker;
    if (this.last) await this.run(this.remove, [this.id]).catch(() => {});
    this.turns.clear(); this.requests.clear();
  }
}

export function createNotifications() {
  if (process.platform !== 'android' || !process.env.TERMUX_VERSION || process.env.CODEX_TERMUX_PROGRESS === '0') return null;
  const prefix = process.env.PREFIX || '/data/data/com.termux/files/usr';
  const notify = join(prefix, 'bin/termux-notification'), remove = join(prefix, 'bin/termux-notification-remove');
  return existsSync(notify) && existsSync(remove) ? new ProgressNotifications(notify, remove) : null;
}

function runCommand(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'ignore', detached: true });
    const timer = setTimeout(() => {
      try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); }
      reject(new Error('Termux API timed out'));
    }, 2000);
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('exit', code => { clearTimeout(timer); code === 0 ? resolve() : reject(new Error('Termux API failed')); });
  });
}
