import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

export class Bridge {
  constructor(command, args, env, publish, onFailure = () => {}) {
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
      if (this.dead || this.closed) return;
      this.dead = true;
      for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(error); }
      this.pending.clear(); this.requests.clear();
      onFailure(error);
    };
    this.child.on('error', fail);
    this.child.on('exit', (code, signal) => {
      const reason = signal ? `（信号 ${signal}）` : code === null ? '' : `（退出码 ${code}）`;
      fail(new Error(`Codex 服务已退出${reason}`));
    });
    this.child.stdin.on('error', () => {});
  }
  write(message) {
    if (this.dead) throw new Error('Codex 服务不可用');
    this.child.stdin.write(JSON.stringify(message) + '\n');
  }
  call(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.sequence;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        const error = new Error('请求超时；请刷新确认状态，不要重复发送');
        error.code = 'BRIDGE_REQUEST_TIMEOUT';
        reject(error);
      }, 120000);
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
  close() {
    if (this.closed) return;
    this.closed = true; this.dead = true;
    const error = new Error('Codex 服务已关闭');
    for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(error); }
    this.pending.clear(); this.requests.clear();
    this.child.stdin.end(); setTimeout(() => this.child.kill(), 2000).unref();
  }
}

export class BridgeController {
  constructor(command, args, env, publish, options = {}) {
    this.command = command; this.args = args; this.env = env; this.publish = publish;
    this.initializeParams = options.initializeParams || {};
    this.onReset = options.onReset || (() => {});
    this.restartDelays = options.restartDelays || [1000, 2000, 5000, 10000, 30000];
    this.BridgeClass = options.BridgeClass || Bridge;
    this.retryCount = 0; this.ready = false; this.closed = false;
  }
  get requests() { return this.bridge?.requests || new Map(); }
  async start() {
    try { await this.connect(false); }
    catch (error) { this.close(); throw error; }
  }
  async connect(recovery) {
    if (this.closed) return;
    let candidate;
    candidate = new this.BridgeClass(
      this.command,
      this.args,
      this.env,
      message => { if (this.bridge === candidate) this.publish(message); },
      error => this.failed(candidate, error),
    );
    this.bridge = candidate; this.ready = false;
    try {
      await candidate.call('initialize', this.initializeParams);
      candidate.write({ method: 'initialized' });
    } catch (error) {
      candidate.close();
      if (recovery && this.bridge === candidate && !this.closed) this.schedule(error);
      throw error;
    }
    if (this.bridge !== candidate || this.closed) { candidate.close(); return; }
    this.ready = true; this.retryCount = 0;
    if (recovery) {
      this.publish({ method: 'bridge/sync', params: { requests: [...candidate.requests.values()] } });
    }
  }
  failed(candidate, error) {
    if (this.closed || this.bridge !== candidate) return;
    const wasReady = this.ready;
    this.ready = false;
    if (wasReady) this.onReset();
    this.publish({ method: 'bridge/error', params: { message: error.message } });
    this.schedule(error);
  }
  schedule(error) {
    if (this.closed || this.retryTimer) return;
    const index = Math.min(this.retryCount, this.restartDelays.length - 1);
    const delayMs = this.restartDelays[index];
    this.retryCount += 1;
    this.publish({ method: 'bridge/reconnecting', params: { message: error.message, attempt: this.retryCount, delayMs } });
    this.retryTimer = setTimeout(async () => {
      this.retryTimer = null;
      try { await this.connect(true); } catch { /* The failed attempt schedules the next retry. */ }
    }, delayMs);
    this.retryTimer.unref?.();
  }
  unavailable() {
    if (!this.ready || !this.bridge) throw new Error('Codex 服务正在重新连接，请稍后重试');
    return this.bridge;
  }
  async call(method, params = {}) {
    const candidate = this.unavailable();
    try { return await candidate.call(method, params); }
    catch (error) {
      if (error.code === 'BRIDGE_REQUEST_TIMEOUT' && this.bridge === candidate && this.ready) {
        candidate.close();
        this.failed(candidate, error);
      }
      throw error;
    }
  }
  write(message) { this.unavailable().write(message); }
  answer(id, result) { this.unavailable().answer(id, result); }
  unsupported(id) { this.unavailable().unsupported(id); }
  close() {
    if (this.closed) return;
    this.closed = true; this.ready = false;
    clearTimeout(this.retryTimer); this.retryTimer = null;
    this.bridge?.close();
  }
}
