import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

export class Bridge {
  constructor(command, args, env, publish) {
    this.pending = new Map(); this.requests = new Map(); this.sequence = 0;
    this.child = spawn(command, args, { env, stdio: ['pipe', 'pipe', 'inherit'] });
    this.publish = publish;
    createInterface({ input: this.child.stdout }).on('line', line => {
      let message;
      try { message = JSON.parse(line); } catch { return; }
      if (message.method) {
        if (message.id !== undefined) this.requests.set(String(message.id), message);
        if (message.method === 'serverRequest/resolved') this.requests.delete(String(message.params.requestId));
        publish(message);
      } else {
        const entry = this.pending.get(message.id);
        if (!entry) return;
        clearTimeout(entry.timer); this.pending.delete(message.id);
        message.error ? entry.reject(new Error(message.error.message)) : entry.resolve(message.result);
      }
    });
    const fail = error => {
      this.dead = true;
      for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(error); }
      this.pending.clear(); this.requests.clear();
      publish({ method: 'bridge/error', params: { message: error.message } });
    };
    this.child.on('error', fail);
    this.child.on('exit', () => fail(new Error('Codex 服务已退出，请重新启动 codex-web')));
    this.child.stdin.on('error', () => {});
  }
  write(message) {
    if (this.dead) throw new Error('Codex 服务不可用');
    this.child.stdin.write(JSON.stringify(message) + '\n');
  }
  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.sequence;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('请求超时；请刷新确认状态，不要重复发送')); }, 120000);
      this.pending.set(id, { resolve, reject, timer });
      try { this.write({ id, method, params }); } catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
    });
  }
  answer(id, result) {
    const request = this.requests.get(String(id));
    if (!request) throw new Error('请求已处理或失效');
    this.write({ id: request.id, result }); this.requests.delete(String(id));
    this.publish({ method: 'serverRequest/resolved', params: { requestId: request.id } });
  }
  unsupported(id) {
    this.write({ id, error: { code: -32601, message: 'This GUI does not support this interaction' } });
    this.requests.delete(String(id));
  }
  close() { this.child.stdin.end(); setTimeout(() => this.child.kill(), 2000).unref(); }
}
