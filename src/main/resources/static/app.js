// marvis WebUI — vanilla JS,无前端框架。
// 三个 endpoint:POST /api/chat / GET /api/history / POST /api/clear

(function () {
  const $ = (id) => document.getElementById(id);
  const messages = $('messages');
  const form = $('chatForm');
  const input = $('queryInput');
  const sendBtn = $('sendBtn');
  const clearBtn = $('clearBtn');
  const status = $('status');
  const histSizeEl = $('historySize');

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

  // ── 渲染 ──

  function renderEmptyState() {
    messages.innerHTML = `
      <div class="empty-state">
        <h2>欢迎用 marvis</h2>
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
    clearEmptyState();
    const bubble = document.createElement('div');
    bubble.className = 'bubble ' + role + (isError ? ' error' : '');

    const initial = role === 'user' ? '你' : 'M';
    let metaHtml = '';
    if (toolCalls && toolCalls.length > 0) {
      metaHtml = '<div class="meta">' +
        toolCalls.map(t => `<span class="tool-tag">${escapeHtml(t)}</span>`).join('') +
        '</div>';
    }

    bubble.innerHTML = `
      <div class="row">
        ${role === 'assistant' ? `<div class="avatar">${initial}</div>` : ''}
        <div>
          <div class="content">${escapeHtml(text || '(empty)')}</div>
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

  // ── 启动:加载历史 ──

  async function loadHistory() {
    try {
      const data = await getJson('/api/history');
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

  // ── 提交一条对话 ──

  async function sendQuery(query) {
    appendBubble({ role: 'user', text: query });
    const loading = appendLoadingBubble();
    setBusy(true);
    try {
      const data = await postJson('/api/chat', { query });
      loading.remove();
      appendBubble({
        role: 'assistant',
        text: data.reply || '(no reply)',
        toolCalls: data.toolCalls || [],
      });
      updateHistorySize(data.historySize);
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
    if (!confirm('清空对话历史?(marvis 共享同一份 history,CLI 那边也会被清)')) return;
    setBusy(true);
    try {
      await postJson('/api/clear');
      messages.innerHTML = '';
      renderEmptyState();
      updateHistorySize(0);
    } catch (e) {
      console.error(e);
      alert('清空失败:' + (e.message || 'unknown'));
    } finally {
      setBusy(false);
      input.focus();
    }
  });

  // 初始
  loadHistory();
  input.focus();
})();
