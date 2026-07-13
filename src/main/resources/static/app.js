// jooj WebUI — vanilla JS,无前端框架。
// 端点:
//   - POST /api/chat            { sessionId, query }
//   - GET  /api/history?sessionId=xxx
//   - POST /api/clear?sessionId=xxx
//   - POST/GET/PATCH/DELETE /api/sessions[/{id}]

(function () {
  const $ = (id) => document.getElementById(id);
  const messages = $('messages');
  const form = $('chatForm');
  const input = $('queryInput');
  const sendBtn = $('sendBtn');
  const stopBtn = $('stopBtn');
  const clearBtn = $('clearBtn');
  const status = $('status');
  const histSizeEl = $('historySize');
  const sessionHint = $('sessionHint');
  const currentSessionTag = $('currentSessionTag');
  const newSessionBtn = $('newSessionBtn');

  // 当前 sessionId(从 localStorage 恢复;默认 "default")
  const SESSION_KEY = 'jooj.sessionId';
  let currentSessionId = localStorage.getItem(SESSION_KEY) || 'default';
  // session 列表 cache,渲染 + 找 title 用
  let sessionsCache = [];

  // ── 状态切换 ──

  function setStatus(state, text) {
    status.className = 'status ' + state;
    status.textContent = text;
  }

  function setBusy(busy) {
    input.disabled = busy;
    // s22 D-8:busy 时藏 send 显示 stop,idle 时反过来 —— 用户永远只看到一个可点按钮
    sendBtn.hidden = busy;
    sendBtn.disabled = busy;
    clearBtn.disabled = busy;
    if (stopBtn) {
      stopBtn.hidden = !busy;
      stopBtn.disabled = false;  // 每次进入 busy 都恢复可点
    }
    setStatus(busy ? 'busy' : 'idle', busy ? 'thinking' : 'idle');
  }

  function updateSessionDisplay() {
    const found = sessionsCache.find(s => s.id === currentSessionId);
    const label = found ? found.title : currentSessionId;
    if (sessionHint) sessionHint.textContent = `session: ${label}`;
    if (currentSessionTag) currentSessionTag.textContent = label;
  }

  // ── 渲染 ──

  function renderEmptyState() {
    messages.innerHTML = `
      <div class="empty-state">
        <h2>欢迎用 jooj</h2>
        <p>试试这些:</p>
        <div class="examples">
          <code>列出当前目录下所有 .java 文件</code>
          <code>读 README.md 然后总结一下</code>
          <code>schedule 一个 cron,每分钟跑一次 date</code>
        </div>
      </div>`;
  }

  function clearEmptyState() {
    const empty = messages.querySelector('.empty-state');
    if (empty) empty.remove();
  }

  function appendBubble({ role, text, toolCalls = [], isError = false }) {
    const hasText = text != null && String(text).trim().length > 0;
    const hasToolCalls = toolCalls && toolCalls.length > 0;
    if (!hasText && !hasToolCalls && !isError) return null;

    clearEmptyState();
    const bubble = document.createElement('div');
    bubble.className = 'bubble ' + role + (isError ? ' error' : '');

    const initial = role === 'user' ? '你' : 'M';
    let metaHtml = '';
    if (hasToolCalls) {
      metaHtml = '<div class="meta">' +
        toolCalls.map(t => `<span class="tool-tag">${escapeHtml(t)}</span>`).join('') +
        '</div>';
    }

    const displayText = hasText ? text : (hasToolCalls ? '(调用了工具)' : '');

    const contentHtml = (role === 'assistant' && !isError && hasText)
      ? `<div class="content markdown">${renderMarkdown(displayText)}</div>`
      : `<div class="content">${escapeHtml(displayText)}</div>`;

    bubble.innerHTML = `
      <div class="row">
        ${role === 'assistant' ? `<div class="avatar">${initial}</div>` : ''}
        <div>
          ${contentHtml}
          ${metaHtml}
        </div>
        ${role === 'user' ? `<div class="avatar">${initial}</div>` : ''}
      </div>`;
    messages.appendChild(bubble);
    bubble.scrollIntoView({ behavior: 'smooth', block: 'end' });
    return bubble;
  }

  function appendLoadingBubble() {
    clearEmptyState();
    const bubble = document.createElement('div');
    bubble.className = 'bubble assistant loading';
    bubble.innerHTML = `
      <div class="row">
        <div class="avatar">M</div>
        <div>
          <div class="content">
            <span class="dots"><span></span><span></span><span></span></span>
            <span class="loading-status">thinking...</span>
          </div>
        </div>
      </div>`;
    messages.appendChild(bubble);
    bubble.scrollIntoView({ behavior: 'smooth', block: 'end' });
    // s22 D-11:暴露 setter 让 poll /events 循环更新气泡文字
    bubble.setStatus = (text) => {
      const el = bubble.querySelector('.loading-status');
      if (el) el.textContent = text;
    };
    return bubble;
  }

  /**
   * s22 D-11:turn 期间每 800ms poll /api/chat/{sid}/events,拿到 tool 摘要事件
   * 更新 loading bubble 的 status 文字("正在: $ mvn test")。
   *
   * 返回 stop() 函数,调用它取消 poll(sendQuery finally 里必调)。
   */
  function startEventPolling(sessionId, loadingBubble) {
    let sinceSeq = 0;
    let stopped = false;
    const tick = async () => {
      if (stopped) return;
      try {
        const url = `/api/chat/${encodeURIComponent(sessionId)}/events?since=${sinceSeq}`;
        const data = await getJson(url);
        if (stopped) return;
        if (data.events && data.events.length > 0) {
          const last = data.events[data.events.length - 1];
          sinceSeq = last.seq;
          loadingBubble.setStatus(last.summary);
        }
      } catch (e) {
        // poll 失败静默 —— 不影响主 request,console 记一下就够
        console.debug('[events poll]', e.message || e);
      }
      if (!stopped) setTimeout(tick, 800);
    };
    // 首次立即 tick,让 UI 尽早显示第一条(不用等 800ms)
    setTimeout(tick, 200);
    return () => { stopped = true; };
  }
    return bubble;
  }

  function escapeHtml(s) {
    const div = document.createElement('div');
    div.textContent = s == null ? '' : String(s);
    return div.innerHTML;
  }

  // ── Markdown 渲染 ──
  if (window.marked) {
    window.marked.setOptions({
      gfm: true,
      breaks: true,
      headerIds: false,
      mangle: false,
    });
  }

  function renderMarkdown(text) {
    if (!text) return '';
    if (!window.marked || !window.DOMPurify) {
      return escapeHtml(text).replace(/\n/g, '<br>');
    }
    const rawHtml = window.marked.parse(String(text));
    const clean = window.DOMPurify.sanitize(rawHtml, {
      ADD_ATTR: ['target', 'rel'],
      FORBID_TAGS: ['style', 'iframe', 'form', 'input'],
      FORBID_ATTR: ['onerror', 'onload', 'onclick', 'style'],
    });
    return clean;
  }

  if (window.DOMPurify) {
    window.DOMPurify.addHook('afterSanitizeAttributes', (node) => {
      if (node.tagName === 'A') {
        const href = node.getAttribute('href') || '';
        // Demo 15.1 修复:Memory panel markdown 里的链接形如 [name](xxx.md) 是相对路径,
        // 不是真要跳页面 —— 点击会去 /xxx.md 然后 404。
        // 解法:只对绝对 URL(http://、https://、mailto: 等带协议的)保留可点击 target=blank;
        // 相对路径 / 锚点 / 空 href 一律降级成纯文本(去掉 href 让浏览器不当链接处理)。
        const isAbsolute = /^(https?:|mailto:|tel:)/i.test(href);
        if (isAbsolute) {
          node.setAttribute('target', '_blank');
          node.setAttribute('rel', 'noopener noreferrer');
        } else {
          // 去掉 href,保留文字。视觉上仍是 <a> 元素(可以加样式区分),但点击无效。
          node.removeAttribute('href');
          node.setAttribute('data-inert', 'true');   // CSS 钩子:让其不像可点链接
        }
      }
    });
  }

  function updateHistorySize(n) {
    histSizeEl.textContent = `${n} 条历史`;
  }

  // ── HTTP ──

  async function postJson(url, body) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {}),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error(data.error || `HTTP ${res.status}`);
      err.status = res.status;
      err.data = data;
      throw err;
    }
    return data;
  }

  async function getJson(url) {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  }

  async function patchJson(url, body) {
    const res = await fetch(url, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {}),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `HTTP ${res.status}`);
    }
    return data;
  }

  async function deleteReq(url) {
    const res = await fetch(url, { method: 'DELETE' });
    if (res.status === 204) return null;
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
    return data;
  }

  // ── 加载历史 ──

  async function loadHistory() {
    try {
      const data = await getJson(`/api/history?sessionId=${encodeURIComponent(currentSessionId)}`);
      messages.innerHTML = '';
      if (!data.messages || data.messages.length === 0) {
        renderEmptyState();
        updateHistorySize(0);
        return;
      }
      for (const m of data.messages) {
        appendBubble({ role: m.role, text: m.text });
      }
      updateHistorySize(data.messages.length);
    } catch (e) {
      console.error('load history failed', e);
      renderEmptyState();
    }
  }

  // ── 提交对话 ──

  async function sendQuery(query) {
    appendBubble({ role: 'user', text: query });
    const loading = appendLoadingBubble();
    setBusy(true);
    // s22 D-11:启动 events poll,tool 摘要实时更新到 loading 气泡
    const stopPolling = startEventPolling(currentSessionId, loading);
    try {
      const data = await postJson('/api/chat', {
        sessionId: currentSessionId,
        query,
      });
      stopPolling();
      loading.remove();
      appendBubble({
        role: 'assistant',
        text: data.reply || '(no reply)',
        toolCalls: data.toolCalls || [],
      });
      updateHistorySize(data.historySize);
      // 发完一条更新一下 session list(messageCount 变了)
      loadSessionsList();
    } catch (e) {
      stopPolling();
      loading.remove();
      appendBubble({
        role: 'assistant',
        text: e.message || 'Request failed',
        isError: true,
      });
      setStatus('error', 'error');
      setTimeout(() => setStatus('idle', 'idle'), 3000);
    } finally {
      setBusy(false);
      input.focus();
    }
  }

  // ── 事件绑定 ──

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const query = input.value.trim();
    if (!query) return;
    input.value = '';
    sendQuery(query);
  });

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      form.requestSubmit();
    }
  });

  clearBtn.addEventListener('click', async () => {
    if (!confirm(`清空当前 session (${currentSessionId}) 的对话历史?`)) return;
    setBusy(true);
    try {
      await postJson(`/api/clear?sessionId=${encodeURIComponent(currentSessionId)}`);
      messages.innerHTML = '';
      renderEmptyState();
      updateHistorySize(0);
      loadSessionsList();
    } catch (e) {
      console.error(e);
      alert('清空失败:' + (e.message || 'unknown'));
    } finally {
      setBusy(false);
      input.focus();
    }
  });

  // s22 D-8:stop 按钮 —— 打断当前正跑的 turn。
  // 语义:立即 POST /api/chat/{sid}/interrupt(不等 turn 真结束);
  // agentLoop 会在下一个检查点抛 AgentInterruptedException,进入 processOneQuery
  // 的 catch 分支 append [Interrupted by user] + publish TurnInterrupted 事件。
  // 原先那个 POST /api/chat 请求会继续等,但拿到的 response 是"打断到检查点"的状态。
  if (stopBtn) {
    stopBtn.addEventListener('click', async () => {
      if (!currentSessionId) return;
      stopBtn.disabled = true;   // 防连点,server 端幂等但 UI 反馈更清晰
      try {
        const res = await fetch(`/api/chat/${encodeURIComponent(currentSessionId)}/interrupt`, {
          method: 'POST',
        });
        if (!res.ok) {
          console.warn('[interrupt] server returned', res.status);
        }
        // 不改变 busy 状态 —— 等 /api/chat 那个 request 自己返回 + finally 里的 setBusy(false)
      } catch (e) {
        console.error('[interrupt] failed', e);
        stopBtn.disabled = false;  // 请求发不出去,恢复可点
      }
    });
  }

  // ── Sessions panel ──

  /** 切换到指定 sessionId,重新加载 history。 */
  async function switchSession(sessionId) {
    if (!sessionId || sessionId === currentSessionId) return;
    currentSessionId = sessionId;
    localStorage.setItem(SESSION_KEY, sessionId);
    updateSessionDisplay();
    messages.innerHTML = '';
    await loadHistory();
    renderSessionsPanel();
  }

  /** 创建新 session 并切过去。 */
  async function createNewSession() {
    try {
      const created = await postJson('/api/sessions', {});
      sessionsCache.push(created);
      await switchSession(created.id);
    } catch (e) {
      alert('创建失败:' + (e.message || 'unknown'));
    }
  }

  /** 删除 session。如果删的是当前 session,切回 default。 */
  async function deleteSession(sessionId) {
    if (!confirm(`删除 session "${sessionLabel(sessionId)}" 及其所有对话历史?`)) return;
    try {
      await deleteReq(`/api/sessions/${encodeURIComponent(sessionId)}`);
      sessionsCache = sessionsCache.filter(s => s.id !== sessionId);
      if (sessionId === currentSessionId) {
        await switchSession('default');
      } else {
        renderSessionsPanel();
      }
    } catch (e) {
      alert('删除失败:' + (e.message || 'unknown'));
    }
  }

  function sessionLabel(sessionId) {
    const found = sessionsCache.find(s => s.id === sessionId);
    return found ? found.title : sessionId;
  }

  /** 拉取 session 列表 + 重渲染 panel。 */
  async function loadSessionsList() {
    try {
      const list = await getJson('/api/sessions');
      sessionsCache = Array.isArray(list) ? list : [];
      renderSessionsPanel();
      updateSessionDisplay();
    } catch (e) {
      const target = $('panel-sessions');
      if (target) target.innerHTML = `<p class="panel-error">加载失败:${escapeHtml(e.message)}</p>`;
    }
  }

  /** 把 sessionsCache 渲染到 #panel-sessions。 */
  function renderSessionsPanel() {
    const target = $('panel-sessions');
    if (!target) return;
    if (sessionsCache.length === 0) {
      target.innerHTML = '<p class="panel-empty">还没有 session</p>';
      return;
    }
    target.innerHTML = sessionsCache.map(s => {
      const isCurrent = s.id === currentSessionId;
      const reserved = isReserved(s.id);
      const lastActive = s.lastActiveAt ? formatTime(s.lastActiveAt) : '';
      return `
        <div class="session-item ${isCurrent ? 'current' : ''}" data-id="${escapeHtml(s.id)}">
          <div class="session-main">
            <div class="session-title" title="${escapeHtml(s.title)}">${escapeHtml(s.title)}</div>
            <div class="session-meta">
              <span class="session-count">${s.messageCount || 0} 条</span>
              ${lastActive ? `<span class="session-time">${escapeHtml(lastActive)}</span>` : ''}
              ${reserved ? '<span class="session-tag">reserved</span>' : ''}
            </div>
          </div>
          ${reserved ? '' : `<button class="icon-btn session-delete" data-id="${escapeHtml(s.id)}" type="button" title="删除">✕</button>`}
        </div>
      `;
    }).join('');

    // 绑定点击
    target.querySelectorAll('.session-item').forEach(el => {
      el.addEventListener('click', (ev) => {
        if (ev.target.classList.contains('session-delete')) return;
        switchSession(el.dataset.id);
      });
    });
    target.querySelectorAll('.session-delete').forEach(btn => {
      btn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        deleteSession(btn.dataset.id);
      });
    });
  }

  function isReserved(id) {
    return id === 'default' || id === 'cli-default' || id === 'cron-default';
  }

  function formatTime(iso) {
    try {
      const d = new Date(iso);
      const now = new Date();
      const sameDay = d.toDateString() === now.toDateString();
      if (sameDay) {
        return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
      }
      return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
    } catch (_) {
      return '';
    }
  }

  if (newSessionBtn) {
    newSessionBtn.addEventListener('click', createNewSession);
  }

  // ── Sidebar 主开关(Sessions / Skills / Memory / Status)──

  const sidebarToggle = $('sidebarToggle');
  const sidebar = $('sidebar');
  const panelLoaded = { sessions: false, skills: false, memory: false, status: false };

  function openSidebar() {
    document.body.classList.add('sidebar-open');
    if (!panelLoaded.sessions) loadPanel('sessions');
    if (!panelLoaded.skills) loadPanel('skills');
    if (!panelLoaded.memory) loadPanel('memory');
    if (!panelLoaded.channels) loadPanel('channels');
    if (!panelLoaded.status) loadPanel('status');
  }
  function closeSidebar() {
    document.body.classList.remove('sidebar-open');
  }
  function toggleSidebar() {
    document.body.classList.contains('sidebar-open') ? closeSidebar() : openSidebar();
  }

  sidebarToggle.addEventListener('click', toggleSidebar);

  document.querySelectorAll('.refresh-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const which = btn.dataset.panel;
      panelLoaded[which] = false;
      loadPanel(which, /*forceRescan=*/ which === 'skills');
    });
  });

  /** 加载一个 panel 的内容到 #panel-<name>。 */
  async function loadPanel(name, forceRescan = false) {
    const target = $(`panel-${name}`);
    if (!target) return;
    target.innerHTML = '<p class="panel-loading">加载中...</p>';
    try {
      if (name === 'sessions') {
        await loadSessionsList();
        panelLoaded.sessions = true;
        return;
      }
      // Demo 15: channels panel 走聚合多渠道,目前只有 weixin
      // 以后接 Discord/Telegram 时可以改成 /api/channels 一次性拿,现阶段直接打 weixin status
      let data;
      if (name === 'channels') {
        // weixin 可能 disabled(jooj.weixin.enabled=false) → /api/weixin/status 返回 404 / 不存在
        // 用 try-catch + 标志位区分,避免整个 panel 报错
        try {
          const weixin = await getJson('/api/weixin/status');
          data = { channels: [{ name: 'weixin', ...weixin }] };
        } catch (e) {
          data = { channels: [] };   // 视作"没启用任何 channel"
        }
      } else {
        data = (forceRescan && name === 'skills')
            ? await postJson(`/api/skills/rescan`)
            : await getJson(`/api/${name}`);
      }
      target.innerHTML = renderPanel(name, data);
      panelLoaded[name] = true;
      // channels panel 渲染后要绑定扫码按钮事件
      if (name === 'channels') bindChannelActions();
    } catch (e) {
      console.error(`load ${name} failed`, e);
      target.innerHTML = `<p class="panel-error">加载失败:${escapeHtml(e.message || 'unknown')}</p>`;
    }
  }

  function renderPanel(name, data) {
    if (name === 'skills') {
      const list = data.skills || [];
      if (list.length === 0) {
        return '<p class="panel-empty">未加载到任何 skill</p>';
      }
      return list.map(s => `
        <div class="skill-item">
          <div class="skill-name">${escapeHtml(s.name)}</div>
          <div class="skill-desc">${escapeHtml(s.description || '(无描述)')}</div>
        </div>
      `).join('');
    }
    if (name === 'memory') {
      const catalog = (data.catalog || '').trim();
      if (!catalog) {
        return '<p class="panel-empty">还没有 memory(LLM 在对话中会逐步沉淀)</p>';
      }
      return `<div class="markdown">${renderMarkdown(catalog)}</div>`;
    }
    if (name === 'status') {
      const kbytes = (data.memoryCharCount / 1024).toFixed(1);
      return `
        <dl class="status-grid">
          <dt>model</dt>      <dd>${escapeHtml(data.model || '(未设)')}</dd>
          <dt>workspace</dt>  <dd>${escapeHtml(data.workspace || '')}</dd>
          <dt>tools</dt>      <dd>${data.toolCount}</dd>
          <dt>skills</dt>     <dd>${data.skillCount}</dd>
          <dt>cron jobs</dt>  <dd>${data.cronJobCount}</dd>
          <dt>memory</dt>     <dd>${data.memoryCharCount} chars (${kbytes} KB)</dd>
        </dl>`;
    }
    if (name === 'channels') {
      const list = data.channels || [];
      if (list.length === 0) {
        return `
          <p class="panel-empty">
            没有启用任何 channel。<br>
            <small>启用 weixin:application.yml 加 <code>jooj.weixin.enabled: true</code> 后重启 jooj。</small>
          </p>`;
      }
      return list.map(c => renderChannelItem(c)).join('');
    }
    return '<p class="panel-error">未知 panel</p>';
  }

  /** 单个渠道的渲染:已登录 vs 未登录两种状态。 */
  function renderChannelItem(c) {
    if (c.name !== 'weixin') {
      // 未来扩展位:其他 channel 的渲染
      return `<div class="channel-item"><div class="channel-name">${escapeHtml(c.name)}</div></div>`;
    }
    if (c.loggedIn) {
      const runningBadge = c.channelRunning
          ? '<span class="badge-ok">running</span>'
          : '<span class="badge-warn">not running</span>';
      return `
        <div class="channel-item">
          <div class="channel-header">
            <span class="channel-name">weixin</span>
            ${runningBadge}
          </div>
          <div class="channel-meta">
            account: ${escapeHtml(c.accountId || '')}<br>
            user: ${escapeHtml(c.userId || '(unknown)')}<br>
            ${c.savedAt ? `since: ${escapeHtml(c.savedAt)}` : ''}
          </div>
          ${c.channelRunning ? '' : '<div class="channel-hint">扫码后请重启 jooj 让 channel 起来</div>'}
          <div class="channel-actions">
            <button class="btn-link" data-action="weixin-rescan" type="button">重新扫码登录</button>
          </div>
        </div>`;
    }
    return `
      <div class="channel-item">
        <div class="channel-header">
          <span class="channel-name">weixin</span>
          <span class="badge-warn">not logged in</span>
        </div>
        <div class="channel-meta">account: ${escapeHtml(c.accountId || 'default')}</div>
        <div class="channel-actions">
          <button class="btn-primary" data-action="weixin-login" type="button">📱 扫码登录</button>
        </div>
      </div>`;
  }

  /** 给 channels panel 里的扫码按钮绑事件。loadPanel 渲染后调一次。 */
  function bindChannelActions() {
    document.querySelectorAll('#panel-channels [data-action]').forEach(btn => {
      btn.addEventListener('click', () => {
        const action = btn.getAttribute('data-action');
        if (action === 'weixin-login' || action === 'weixin-rescan') {
          startWeixinQrFlow();
        }
      });
    });
  }

  /**
   * 启动微信扫码流程:
   *   1. POST /api/weixin/qr/start 拿 qrcodeUrl
   *   2. 弹层显示二维码图(用免费 QR API 把 URL 渲染成图)
   *   3. POST /api/weixin/qr/wait 长轮询等用户扫码确认(最多 ~2min)
   *   4. 成功 → 关弹层 + 刷新 panel
   *
   * 注:用户扫成功后,channelRunning 仍是 false(jooj 还没重启)。
   * panel 上会有"请重启 jooj"提示。这跟 jooj 当前实现一致(WeixinChannel hot-start 是迭代方向 #8)。
   */
  async function startWeixinQrFlow() {
    let session;
    try {
      session = await postJson('/api/weixin/qr/start', {});
    } catch (e) {
      alert('启动二维码失败:' + (e.message || 'unknown'));
      return;
    }
    showQrModal(session.qrcodeUrl);

    let result;
    try {
      result = await postJson('/api/weixin/qr/wait', {});
    } catch (e) {
      hideQrModal();
      alert('扫码确认失败:' + (e.message || 'unknown'));
      return;
    }
    hideQrModal();
    if (result.connected) {
      alert(`✓ 扫码成功(userId=${result.userId || ''})\n\n` +
            `请重启 jooj 让 weixin channel 起来:\n  ^C 然后 ./mvnw spring-boot:run`);
    } else {
      alert('扫码超时或失败:' + (result.message || '请重试'));
    }
    // 刷新 panel 显示最新状态
    panelLoaded.channels = false;
    loadPanel('channels');
  }

  /** 用第三方 QR 生成 API 把 URL 渲染成图,套个 modal 显示。 */
  function showQrModal(qrcodeUrl) {
    let modal = document.getElementById('qr-modal');
    if (modal) modal.remove();   // 残留清掉
    const apiUrl = `https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=${encodeURIComponent(qrcodeUrl)}`;
    modal = document.createElement('div');
    modal.id = 'qr-modal';
    modal.className = 'qr-modal-backdrop';
    modal.innerHTML = `
      <div class="qr-modal-box">
        <h3>用微信扫码登录</h3>
        <img src="${escapeHtml(apiUrl)}" alt="QR" class="qr-modal-img"/>
        <p class="qr-modal-hint">手机微信 → 扫一扫 → 确认登录<br>等待中... (最多 2 分钟)</p>
        <p class="qr-modal-fallback">扫不到?复制链接到手机:<br>
          <code class="qr-modal-url">${escapeHtml(qrcodeUrl)}</code></p>
        <button type="button" class="btn-secondary" id="qr-modal-cancel">取消</button>
      </div>`;
    document.body.appendChild(modal);
    document.getElementById('qr-modal-cancel').addEventListener('click', hideQrModal);
  }

  function hideQrModal() {
    const modal = document.getElementById('qr-modal');
    if (modal) modal.remove();
  }

  // ── 初始化 ──
  loadSessionsList().then(() => updateSessionDisplay());
  loadHistory();
  input.focus();
})();
