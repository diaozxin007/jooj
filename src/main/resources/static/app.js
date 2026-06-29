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
    sendBtn.disabled = busy;
    clearBtn.disabled = busy;
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
            <span>thinking...</span>
          </div>
        </div>
      </div>`;
    messages.appendChild(bubble);
    bubble.scrollIntoView({ behavior: 'smooth', block: 'end' });
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
        node.setAttribute('target', '_blank');
        node.setAttribute('rel', 'noopener noreferrer');
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
    try {
      const data = await postJson('/api/chat', {
        sessionId: currentSessionId,
        query,
      });
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
      const data = (forceRescan && name === 'skills')
          ? await postJson(`/api/skills/rescan`)
          : await getJson(`/api/${name}`);
      target.innerHTML = renderPanel(name, data);
      panelLoaded[name] = true;
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
    return '<p class="panel-error">未知 panel</p>';
  }

  // ── 初始化 ──
  loadSessionsList().then(() => updateSessionDisplay());
  loadHistory();
  input.focus();
})();
