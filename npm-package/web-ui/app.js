import { markdown } from './markdown.js';
import { setupUsage } from './usage.js';
const $ = id => document.getElementById(id);
const client = crypto.randomUUID();
let csrf, thread = localStorage.getItem('codex-web-thread'), turn, busy = false, sending = false, connected = false, cursor, models = [], images = [];
const items = new Map();
let historyCursor;
let uncertain = false;
let readOnly = false;
const older = document.createElement('button'); text(older, '查看更早记录'); older.hidden = true; $('messages').before(older);
async function historyPage(cursor = null) {
  const page = await rpc('thread/items/list', { threadId: thread, limit: 100, sortDirection: 'desc', cursor });
  items.clear(); $('messages').replaceChildren();
  for (const entry of page.data.reverse()) render(entry.item);
  historyCursor = page.nextCursor; older.hidden = !historyCursor;
}
older.onclick = () => { if (busy) return fail(new Error('任务完成后可浏览更早记录')); historyPage(historyCursor).catch(fail); };
const fail = error => { $('error').textContent = error.message; };
async function post(path, body, binary = false) {
  const response = await fetch(path, { method: 'POST', headers: { 'Content-Type': binary ? 'application/octet-stream' : 'application/json', 'X-CSRF-Token': csrf || '', 'X-Client-Id': client }, body: binary ? body : JSON.stringify(body) });
  const value = await response.json(); if (!response.ok) throw new Error(value.error || '请求失败'); return value;
}
const rpc = (method, params = {}, extra = {}) => post('/rpc', { method, params, ...extra });
setupUsage(rpc);
function state() { $('send').disabled = !connected || busy || sending || uncertain; $('model').disabled = !connected || busy || sending || uncertain || !models.length; $('effort').disabled = $('model').disabled; $('stop').hidden = !busy; text($('send'), readOnly ? '分支并发送' : '发送'); }
function text(node, value) { node.textContent = value || ''; }
function render(item) {
  if (item.type === 'reasoning') return;
  let row = items.get(item.id);
  if (!row) { row = document.createElement('article'); row.className = item.type; items.set(item.id, row); $('messages').append(row); }
  if (item.type === 'agentMessage') { row.dataset.raw = item.text || ''; markdown(row, item.text || ''); }
  else if (item.type === 'userMessage') {
    row.replaceChildren();
    for (const input of item.content || []) {
      if (input.type === 'text') { const p = document.createElement('p'); text(p, input.text); row.append(p); }
      if (input.type === 'localImage') {
        const id = input.path.split('/').pop();
        if (/^[a-f0-9-]{36}$/.test(id)) { const img = document.createElement('img'); img.src = '/images/' + id; img.alt = '上传的图片'; img.loading = 'lazy'; row.append(img); }
        else { const p = document.createElement('p'); text(p, '图片：' + input.path); row.append(p); }
      }
    }
  } else {
    row.replaceChildren(); const detail = document.createElement('details'), summary = document.createElement('summary'), pre = document.createElement('pre');
    text(summary, `${item.type} · ${item.status || ''}`); text(pre, JSON.stringify(item, null, 2).slice(0, 16000)); detail.append(summary, pre); row.append(detail);
  }
  while (items.size > 160) { const key = items.keys().next().value; items.get(key).remove(); items.delete(key); }
  if ($('messages').scrollHeight - $('messages').scrollTop - $('messages').clientHeight < 500) row.scrollIntoView({ block: 'end' });
}
async function restore() {
  if (!thread) return;
  const result = await rpc('thread/resume', { threadId: thread, excludeTurns: true, initialTurnsPage: { limit: 20, sortDirection: 'desc', itemsView: 'summary' } });
  readOnly = !!result.webReadOnly;
  items.clear(); $('messages').replaceChildren();
  const turns = result.initialTurnsPage?.data || [];
  await historyPage();
  const active = turns.find(t => t.status === 'inProgress'); turn = active?.id; busy = !!active;
  $('cwd').value = result.thread.cwd || $('cwd').value;
  text($('permissions'), readOnly ? '此会话正被其他客户端占用，当前只读；发送时创建独立分支。占用结束后刷新页面即可重试恢复原会话。' : '权限：' + JSON.stringify(result.approvalPolicy ?? '沿用配置') + ' / ' + JSON.stringify(result.sandbox ?? '沿用配置'));
  state();
  // Wait for the restored history to be laid out before jumping to its end.
  // Keep this out of historyPage so browsing older pages does not jump down.
  requestAnimationFrame(() => {
    if (thread === result.thread.id) $('messages').scrollTop = $('messages').scrollHeight;
  });
}
async function list(reset = true) {
  if (reset) { cursor = null; $('threads').replaceChildren(); }
  const result = await rpc('thread/list', { limit: 20, cursor }); cursor = result.nextCursor;
  for (const t of result.data) {
    const button = document.createElement('button'); text(button, t.name || t.preview || t.id);
    button.onclick = async () => { if (busy) return fail(new Error('请先停止当前任务再切换')); thread = t.id; localStorage.setItem('codex-web-thread', thread); $('history').hidden = true; await restore().catch(fail); }; $('threads').append(button);
  }
  $('more').hidden = !cursor;
}
function approval(message) {
  if (document.getElementById('request-' + message.id)) return;
  const box = document.createElement('section'); box.id = 'request-' + message.id;
  const title = document.createElement('p'); text(title, message.method); box.append(title);
  const detail = document.createElement('pre'); text(detail, JSON.stringify(message.params, null, 2).slice(0, 16000)); box.append(detail);
  const answer = async result => { try { await post('/answer', { id: message.id, result }); box.remove(); } catch (e) { fail(e); } };
  const button = (label, result) => { const b = document.createElement('button'); text(b, label); b.onclick = () => answer(typeof result === 'function' ? result() : result); box.append(b); };
  if (message.method.endsWith('/requestApproval')) {
    const decisions = message.params.availableDecisions || ['accept', 'decline', 'cancel'];
    for (const decision of decisions) button(({ accept: '允许一次', acceptForSession: '本会话允许', decline: '拒绝', cancel: '取消' })[decision] || JSON.stringify(decision), { decision });
  } else if (message.method === 'item/tool/requestUserInput' || message.method === 'tool/requestUserInput') {
    const fields = [];
    for (const q of message.params.questions || []) { const label = document.createElement('label'), input = document.createElement('input'); text(label, q.question + (q.options?.length ? '\n' + q.options.map(o => o.label).join(' / ') : '')); label.append(input); box.append(label); fields.push([q.id, input]); }
    button('提交回答', () => ({ answers: Object.fromEntries(fields.map(([id, input]) => [id, { answers: [input.value] }])) }));
  } else if (message.method === 'mcpServer/elicitation/request') {
    const p = message.params;
    text(title, p.message || 'MCP 请求');
    if (p.mode === 'url' && /^https?:\/\//.test(p.url)) {
      const link = document.createElement('a'); link.href = p.url; link.target = '_blank'; link.rel = 'noopener noreferrer'; text(link, '打开授权页面'); box.append(link); button('已完成', { action: 'accept', content: null });
    } else if (p.mode === 'form') {
      const fields = [], schema = p.requestedSchema || {}; let supported = true;
      for (const [key, property] of Object.entries(schema.properties || {})) {
        if (!['string', 'number', 'integer', 'boolean'].includes(property.type)) { supported = false; continue; }
        const label = document.createElement('label'); text(label, property.title || key);
        const input = property.enum ? document.createElement('select') : document.createElement('input');
        if (property.enum) property.enum.forEach(v => input.append(new Option(String(v), String(v))));
        else input.type = property.type === 'boolean' ? 'checkbox' : ['integer', 'number'].includes(property.type) ? 'number' : 'text';
        input.required = (schema.required || []).includes(key); label.append(input); box.append(label); fields.push([key, property, input]);
      }
      if (supported) button('提交', () => ({ action: 'accept', content: Object.fromEntries(fields.map(([key, p, input]) => [key, p.type === 'boolean' ? input.checked : ['integer', 'number'].includes(p.type) ? Number(input.value) : input.value])) }));
      else { const note = document.createElement('p'); text(note, '此表单包含不支持的字段，请拒绝或取消'); box.append(note); }
    }
    button('拒绝', { action: 'decline', content: null }); button('取消', { action: 'cancel', content: null });
  } else { text(title, '当前界面不支持此请求，请停止任务'); button('停止任务', () => { rpc('turn/interrupt', { threadId: thread, turnId: turn }).catch(fail); return {}; }); }
  $('requests').append(box);
}
function previews() {
  $('images').replaceChildren(); images.forEach((image, index) => { const b = document.createElement('button'), img = document.createElement('img'); img.src = image.preview; img.alt = '点击移除图片'; b.append(img); b.onclick = () => { URL.revokeObjectURL(image.preview); images.splice(index, 1); previews(); }; $('images').append(b); });
}
$('files').onchange = () => {
  for (const file of $('files').files) {
    if (images.length >= 4 || file.size > 10485760 || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) { fail(new Error('最多 4 张 JPEG/PNG/WebP，每张不超过 10 MiB')); continue; }
    images.push({ file, preview: URL.createObjectURL(file) });
  }
  $('files').value = ''; previews();
};
$('composer').onsubmit = async event => {
  event.preventDefault(); if (busy || sending || uncertain || !connected) return;
  const prompt = $('prompt').value; if (!prompt.trim() && !images.length) return;
  sending = true; state(); text($('error'), '');
  try {
    if (thread && readOnly) {
      const result = await rpc('thread/fork', { threadId: thread, excludeTurns: true, deferGoalContinuation: true });
      thread = result.thread.id; readOnly = false; localStorage.setItem('codex-web-thread', thread);
    }
    if (!thread) { const result = await rpc('thread/start', { cwd: $('cwd').value }); thread = result.thread.id; localStorage.setItem('codex-web-thread', thread); }
    const ids = []; for (const image of images) { if (!image.id) image.id = (await post('/upload', image.file, true)).id; ids.push(image.id); }
    const params = { threadId: thread, input: prompt ? [{ type: 'text', text: prompt }] : [], clientUserMessageId: crypto.randomUUID() };
    if ($('model').value) params.model = $('model').value;
    if ($('effort').value) params.effort = $('effort').value;
    uncertain = true;
    const result = await rpc('turn/start', params, { images: ids }); uncertain = false; turn = result.turn.id; busy = result.turn.status === 'inProgress';
    $('prompt').value = ''; localStorage.removeItem('codex-web-draft'); images.forEach(i => URL.revokeObjectURL(i.preview)); images = []; previews();
  } catch (e) { fail(new Error(e.message + (uncertain ? '。发送状态不确定，请刷新核对历史后再操作。' : ''))); } finally { sending = false; state(); }
};
$('prompt').value = localStorage.getItem('codex-web-draft') || '';
$('prompt').oninput = () => localStorage.setItem('codex-web-draft', $('prompt').value);
$('prompt').onkeydown = e => { if (e.ctrlKey && e.key === 'Enter' && !e.isComposing) { e.preventDefault(); $('composer').requestSubmit(); } };
$('stop').onclick = () => rpc('turn/interrupt', { threadId: thread, turnId: turn }).catch(fail);
$('historyButton').onclick = () => { $('history').hidden = !$('history').hidden; if (!$('history').hidden) list().catch(fail); };
$('settingsButton').onclick = () => { $('settings').hidden = !$('settings').hidden; };
$('more').onclick = () => list(false).catch(fail);
const fresh = () => { if (busy) return fail(new Error('请先停止当前任务')); thread = null; readOnly = false; state(); localStorage.removeItem('codex-web-thread'); items.clear(); $('messages').replaceChildren(); $('requests').replaceChildren(); $('history').hidden = true; };
$('newThread').onclick = fresh; $('project').onclick = () => { if (busy || sending) return fail(new Error('请先完成当前任务')); fresh(); const paths = [...new Set([$('cwd').value, ...JSON.parse(localStorage.getItem('codex-web-projects') || '[]')])].slice(0, 10); localStorage.setItem('codex-web-projects', JSON.stringify(paths)); $('recent').replaceChildren(...paths.map(p => new Option(p, p))); $('settings').hidden = true; };
$('cleanup').onclick = () => { if (busy || sending) return fail(new Error('任务完成后才能清理图片')); if (confirm('删除全部上传图片？历史图片将不可用。')) post('/cleanup', {}).catch(fail); };
function selectModel() {
  const selected = $('model').value;
  if (selected) localStorage.setItem('codex-web-model', selected);
  else localStorage.removeItem('codex-web-model');
  const model = models.find(candidate => candidate.id === selected);
  text($('modelDescription'), model?.description || '未指定模型时，Codex 使用配置中的默认模型。');
  $('model').title = model?.description || '';
  const effective = model || models.find(candidate => candidate.isDefault);
  $('effort').replaceChildren(new Option('沿用会话 / 配置', ''));
  for (const option of effective?.supportedReasoningEfforts || []) {
    const entry = new Option(option.reasoningEffort, option.reasoningEffort);
    entry.title = option.description; $('effort').append(entry);
  }
  const saved = localStorage.getItem('codex-web-effort:' + (effective?.id || 'default'));
  $('effort').value = [...$('effort').options].some(option => option.value === saved) ? saved : '';
}
$('effort').onchange = () => {
  const model = models.find(candidate => candidate.id === $('model').value) || models.find(candidate => candidate.isDefault);
  localStorage.setItem('codex-web-effort:' + (model?.id || 'default'), $('effort').value);
};
$('model').onchange = selectModel;
function populateModels(data) {
  models = data;
  $('model').replaceChildren(new Option('默认模型', ''));
  models.forEach(model => $('model').append(new Option(model.displayName || model.id, model.id)));
  const saved = localStorage.getItem('codex-web-model');
  $('model').value = models.some(model => model.id === saved) ? saved : '';
  selectModel();
  state();
}
async function init() {
  if (location.hash) { const token = location.hash.slice(1); history.replaceState(null, '', '/'); await post('/auth', { token }); }
  const response = await fetch('/session'), session = await response.json(); if (!response.ok) throw new Error(session.error);
  csrf = session.csrf; $('cwd').value = session.cwd;
  $('recent').replaceChildren(...JSON.parse(localStorage.getItem('codex-web-projects') || '[]').map(p => new Option(p, p)));
  const source = new EventSource('/events?client=' + client);
  source.onerror = () => { connected = false; text($('connection'), '连接断开 / 其他页面控制中'); state(); };
  source.onmessage = async event => {
    const message = JSON.parse(event.data), p = message.params || {};
    if (message.method === 'bridge/sync') {
      connected = true; text($('connection'), '已连接'); state();
      try { await restore(); $('requests').replaceChildren(); p.requests.forEach(approval); const result = await rpc('model/list'); populateModels(result.data); const account = await rpc('account/read'); if (!account.account && account.requiresOpenaiAuth) fail(new Error('请先在 Termux 执行 codex login')); } catch (e) { fail(e); }
      return;
    }
    if (message.method === 'bridge/error') { connected = false; fail(new Error(p.message)); state(); return; }
    if (message.method === 'bridge/unsupported') { fail(new Error(p.message)); return; }
    if (message.id !== undefined) return approval(message);
    if (message.method === 'serverRequest/resolved') document.getElementById('request-' + p.requestId)?.remove();
    if (p.threadId && p.threadId !== thread) return;
    if (message.method === 'turn/started') { turn = p.turn.id; busy = true; state(); }
    if (message.method === 'turn/completed') { busy = false; state(); if (p.turn.error) fail(new Error(p.turn.error.message)); }
    if (message.method === 'item/started' || message.method === 'item/completed') render(p.item);
    if (message.method === 'item/agentMessage/delta') { const row = items.get(p.itemId); const value = (row?.dataset.raw || '') + p.delta; render({ id: p.itemId, type: 'agentMessage', text: value }); items.get(p.itemId).dataset.raw = value; }
  };
}
init().catch(fail);
