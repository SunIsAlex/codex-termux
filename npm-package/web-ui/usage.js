const $ = id => document.getElementById(id);
const date = value => value == null ? '未知' : new Date(value * 1000).toLocaleString();

export function setupUsage(rpc) {
  let loading = false, redeeming = false, snapshot = null;
  let pending = JSON.parse(sessionStorage.getItem('codex-web-reset-attempt') || 'null');
  const note = value => { $('usageStatus').textContent = value; };
  function buttons() {
    $('usageRefresh').disabled = loading || redeeming;
    $('resetCredit').disabled = loading || redeeming || !!pending;
    $('redeemReset').disabled = loading || redeeming || !snapshot || (!pending && !(snapshot.rateLimitResetCredits?.availableCount > 0));
    $('redeemReset').textContent = pending ? '重试上次兑换' : 'Redeem reset';
  }
  async function refresh() {
    if (loading || redeeming) return;
    loading = true; buttons(); note('正在读取用量…');
    try {
      snapshot = await rpc('account/rateLimits/read');
      $('usageData').replaceChildren();
      const buckets = Object.values(snapshot.rateLimitsByLimitId || {});
      if (!buckets.length && snapshot.rateLimits) buckets.push(snapshot.rateLimits);
      for (const bucket of buckets) {
        const section = document.createElement('section');
        const title = document.createElement('h3');
        title.textContent = `${bucket.limitName || bucket.limitId || 'Codex'} · ${bucket.planType || '账户'}`;
        section.append(title);
        for (const [label, window] of [['主要额度', bucket.primary], ['次要额度', bucket.secondary]]) {
          if (!window) continue;
          const line = document.createElement('p');
          line.textContent = `${label}${window.windowDurationMins ? `（${window.windowDurationMins / 60} 小时）` : ''}：已用 ${window.usedPercent}% · 重置 ${date(window.resetsAt)}`;
          const meter = document.createElement('progress'); meter.max = 100; meter.value = window.usedPercent;
          meter.setAttribute('aria-label', label); section.append(line, meter);
        }
        if (bucket.credits) {
          const line = document.createElement('p');
          line.textContent = `Credits：${bucket.credits.unlimited ? '无限' : bucket.credits.balance ?? '未知'}`;
          section.append(line);
        }
        $('usageData').append(section);
      }
      const summary = snapshot.rateLimitResetCredits;
      $('resetCount').textContent = `可用 reset：${summary?.availableCount ?? '不可用'}`;
      $('resetCredit').replaceChildren(new Option('自动选择可用 reset', ''));
      for (const credit of summary?.credits || []) {
        if (credit.status !== 'available') continue;
        const option = new Option(`${credit.title || credit.resetType} · 到期 ${date(credit.expiresAt)}`, credit.id);
        option.title = credit.description || ''; $('resetCredit').append(option);
      }
      note(pending ? '上次兑换结果未确认；重试将使用同一请求标识，避免重复兑换。' : '用量已更新');
    } catch (error) { snapshot = null; note(`无法读取用量：${error.message}`); }
    finally { loading = false; buttons(); }
  }
  $('usageRefresh').onclick = refresh;
  $('redeemReset').onclick = async () => {
    if (redeeming || loading || !snapshot) return;
    if (pending && pending.accountId !== snapshot.accountId) { note('账户已变化，请切回原账户确认上次兑换结果。'); return; }
    if (!pending) {
      if (!confirm('消耗一个可用 reset，重置对应额度？')) return;
      pending = { accountId: snapshot.accountId, params: { idempotencyKey: crypto.randomUUID(), creditId: $('resetCredit').value || null } };
      sessionStorage.setItem('codex-web-reset-attempt', JSON.stringify(pending));
    }
    redeeming = true; buttons(); note('正在兑换…');
    let result;
    try {
      result = await rpc('account/rateLimitResetCredit/consume', pending.params);
      pending = null; sessionStorage.removeItem('codex-web-reset-attempt');
    } catch (error) { note(`兑换结果未确认：${error.message}。可重试上次兑换。`); }
    finally { redeeming = false; buttons(); }
    if (result) {
      await refresh();
      const label = ({ reset: '兑换成功，额度已重置', nothingToReset: '当前没有需要重置的额度', noCredit: '没有可用 reset', alreadyRedeemed: '该 reset 已兑换' })[result.outcome] || result.outcome;
      note(`${label}${snapshot ? '' : '；用量刷新失败，请手动刷新'}`);
    }
  };
  buttons();
}
