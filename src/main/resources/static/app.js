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

  // ── Sidebar (Skills / Memory / 状态) ──
  // 默认收起。点 hamburger 切 body.sidebar-open。第一次打开时 lazy 加载 3 个 panel,
  // 之后不主动重抓 — 用户点 ↻ 或重启页面才刷新。

  const sidebarToggle = $('sidebarToggle');
  const sidebar = $('sidebar');
  const panelLoaded = { skills: false, memory: false, status: false };

  function openSidebar() {
    document.body.classList.add('sidebar-open');
    // 第一次打开时把 3 个 panel 都拉一遍(并发,不阻塞 UI)
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

  // 刷新按钮:每个 panel-header 里的 ↻ 都走这条
  // skills 走 POST /api/skills/rescan(强制重扫盘),其他走默认 GET
  document.querySelectorAll('.refresh-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const which = btn.dataset.panel;
      panelLoaded[which] = false;   // 强制重拉
      loadPanel(which, /*forceRescan=*/ which === 'skills');
    });
  });

  /**
   * 加载一个 panel 的内容到 #panel-<name>。
   * 每个 panel 独立失败:网络错或 5xx 时显示 error 文字,不影响其他 panel。
   *
   * @param name      panel 名(skills / memory / status)
   * @param forceRescan true 时调对应 rescan 接口(POST + side effect),
   *                  仅 skills 支持;false 走默认 GET。用户点 ↻ 时传 true。
   */
  async function loadPanel(name, forceRescan = false) {
    const target = $(`panel-${name}`);
    if (!target) return;
    target.innerHTML = '<p class="panel-loading">加载中...</p>';
    try {
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

  /**
   * 把后端 JSON 渲染成 HTML 片段。
   * Skills / Memory / Status 三种格式分别处理。
   */
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
      // memory catalog 本来就是 markdown,直接用 .markdown 样式
      return `<div class="markdown">${renderMarkdown(catalog)}</div>`;
    }
    if (name === 'status') {
      // 平铺 dt/dd 网格。memoryCharCount 显示成"~K chars"更易读
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

  // 初始
  loadHistory();
  input.focus();
})();
