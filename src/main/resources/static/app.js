// jooj WebUI — vanilla JS,无前端框架。
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
    // 双重保险:空文本不渲染气泡(后端 /api/history 已过滤一次,但 reply 路径可能也空)
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

    // 只有 hasToolCalls 没文本时,把工具列表当 content 显示;
    // 大部分场景仍是 text + meta(tool tags)分两行
    const displayText = hasText ? text : (hasToolCalls ? '(调用了工具)' : '');

    // 关键:assistant 文本走 markdown 渲染(LLM 经常用 ** ` ``` # 等),
    // user 输入保留 plain text(用户没写 markdown 习惯,意外 ** 也别被加粗)。
    // error 气泡也走 plain,错误信息直白比较好。
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

  // ── Markdown 渲染(只对 assistant 用,防 XSS) ──
  // 配置 marked:GFM(table、strikethrough)+ breaks(单换行=<br>,贴近 LLM 输出习惯)
  if (window.marked) {
    window.marked.setOptions({
      gfm: true,
      breaks: true,
      headerIds: false,    // 不生成 id,避免气泡里 anchor 链接干扰
      mangle: false,       // 关闭 email 混淆(GFM 没要求)
    });
  }

  /** 渲染 markdown 到 HTML 字符串。CDN 没加载时 fallback 到 escapeHtml + 保留换行。 */
  function renderMarkdown(text) {
    if (!text) return '';
    if (!window.marked || !window.DOMPurify) {
      // 降级:CDN 挂了就 plain text + 保换行
      return escapeHtml(text).replace(/\n/g, '<br>');
    }
    const rawHtml = window.marked.parse(String(text));
    // 关键:DOMPurify 清洗,白名单标签 + 强制链接安全
    const clean = window.DOMPurify.sanitize(rawHtml, {
      ADD_ATTR: ['target', 'rel'],          // 允许 target/rel(下面 hook 加上)
      FORBID_TAGS: ['style', 'iframe', 'form', 'input'],
      FORBID_ATTR: ['onerror', 'onload', 'onclick', 'style'],
    });
    return clean;
  }

  // 给所有 markdown 链接强制加 target=_blank + rel=noopener noreferrer
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
    if (!confirm('清空对话历史?(jooj 共享同一份 history,CLI 那边也会被清)')) return;
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
