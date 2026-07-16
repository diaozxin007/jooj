package com.xilidou.jooj.tui;

import com.xilidou.jooj.agent.AgentControl;
import com.xilidou.jooj.agent.control.Answer;
import com.xilidou.jooj.agent.control.ChoiceAnswer;
import com.xilidou.jooj.agent.control.ClarifyQuestion;
import com.xilidou.jooj.agent.control.DenyAnswer;
import com.xilidou.jooj.agent.control.PendingQuestion;
import com.xilidou.jooj.agent.control.PermissionQuestion;
import com.xilidou.jooj.channel.AnswerParser;
import com.xilidou.jooj.channel.InboundDispatcher;
import com.xilidou.jooj.tool.ExecutionContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TuiQueryDispatcher —— TUI 内部 query 队列 + 单 worker(s23 P5)。
 *
 * <h3>为什么单独抽这层</h3>
 *
 * <p>P5 的核心问题:{@link TuiCliRunner} 主线程被 stdin 占管,而 agent 在跑期间可能挂起
 * 等 user answer(pending question)—— 如果主线程还阻塞在 dispatchSync 里,就死锁。
 * 解耦方案:主线程只做 {@code readLine + 路由};query 交给本类的 queue → worker 顺序处理。
 *
 * <p>Queue 只服务 TUI channel —— 不下沉到 InboundDispatcher(见 s23 audit 决策:web / weixin
 * 各自的并发模型不同,统一队列会破坏 15+ 现有测试和 SESSION_BUSY 语义)。
 *
 * <h3>数据流</h3>
 *
 * <pre>
 *   TuiCliRunner.readLine
 *          ↓ (三分支)
 *   ├─ 有 pending question   → answerPending(text)  → agentControl.answer → worker 恢复
 *   ├─ worker 忙 + queue 满  → offer 返 false → CliRunner 提示 "队列已满"
 *   └─ 空闲 / queue 有空     → offer 入队 → worker.take → dispatcher.dispatchSync
 *                                                              │
 *                              (assistant 输出走 TuiTurnRenderer 事件监听打屏)
 * </pre>
 *
 * <h3>并发语义</h3>
 *
 * <ul>
 *   <li>Queue: bounded {@link LinkedBlockingQueue}(容量 = jooj.tui.queue-capacity,默认 5)</li>
 *   <li>Worker: 1 个 daemon 线程,{@code queue.take()} 阻塞</li>
 *   <li>Per-session lock 由 dispatchSync 内部 {@code AgentLockProvider.tryLock} 保护 —— worker 顺序调
 *       dispatchSync 天然不会撞 SESSION_BUSY(除非有其他 channel 同时向 cli-default session 发)</li>
 * </ul>
 *
 * <h3>Pending answer 一体化路径</h3>
 *
 * <p>用户看到 modal 后不需要额外命令,下一次 readLine 的输入自动尝试当 answer 解析
 * ({@link AnswerParser#tryParse} for Clarify;a/d 简单匹配 for Permission)。
 * parse 失败 → CliRunner 提示"未识别请重试",输入**不入队** —— 避免"想问的问题被吞成 answer"。
 */
@Component
@Profile("tui & !test")
public class TuiQueryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TuiQueryDispatcher.class);

    /** Cli-default session:与 legacy CLI 共享,TUI 不做多 session(P9 stretch 才加)。 */
    public static final String SESSION_ID = com.xilidou.jooj.session.Session.CLI_DEFAULT_ID;

    private final InboundDispatcher dispatcher;
    private final AgentControl agentControl;
    private final LinkedBlockingQueue<String> queue;
    private volatile boolean running = false;
    private Thread worker;

    /** worker 当前正在跑的 query,null 表示 idle。CliRunner 提示"正在处理 XXX"用。 */
    private volatile String inFlight;

    public TuiQueryDispatcher(InboundDispatcher dispatcher,
                              AgentControl agentControl,
                              TuiProperties props) {
        this.dispatcher = dispatcher;
        this.agentControl = agentControl;
        int cap = Math.max(1, props.getQueueCapacity());
        this.queue = new LinkedBlockingQueue<>(cap);
    }

    // ─────────────────────────────────────────────────────────────
    //  生命周期
    // ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "tui-worker");
        worker.setDaemon(true);
        worker.start();
        log.info("[TUI] worker started (queue capacity={})", queue.remainingCapacity() + queue.size());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    private void runLoop() {
        while (running) {
            String query;
            try {
                query = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            inFlight = query;
            try {
                InboundDispatcher.DispatchRequest req = new InboundDispatcher.DispatchRequest(
                        SESSION_ID,
                        query,
                        /* autoCreate = */ true,
                        /* title     = */ "tui",
                        new ExecutionContext.DeliveryHint(TuiChannel.NAME, "local"));
                InboundDispatcher.DispatchResult r = dispatcher.dispatchSync(req);
                // TuiCliRunner 观察不到 result —— 事件驱动打屏(TuiTurnRenderer)已经处理
                // OK/AssistantResponseCompleted;非 OK 状态需要另一条路径通知 CliRunner
                if (r.status() != InboundDispatcher.Status.OK) {
                    reportNonOk(r);
                }
            } catch (Throwable t) {
                // 保护 worker loop 不因单个 query 崩掉
                log.error("[TUI] worker dispatch failed for query='{}': {}",
                        truncate(query, 60), t.toString());
            } finally {
                inFlight = null;
            }
        }
        log.info("[TUI] worker exiting");
    }

    /**
     * 非 OK 状态(SESSION_BUSY / AGENT_FAILED / HOOK_BLOCKED / SLASH_HANDLED 等)—— worker 拿到的
     * result 需要通知用户。这里以 log 为主,同时把 message 挂到 pending 供 CliRunner 下轮读取。
     *
     * <p>实现折衷:P5 不新加"worker → CliRunner"消息通道 —— 用 log warn 兜底
     * (用户开 --debug 能看到),真正显示交给下一步做。SLASH_HANDLED 属于 slash 命令 reply,
     * dispatchSync 层会走 sendReply → channel.sendOutbound(TUI channel no-op)—— 意味着 slash
     * 命令在 TUI 里目前失去可视反馈。**这个已知 gap 由 s23 §9 记录**,不阻塞 P5。
     */
    private void reportNonOk(InboundDispatcher.DispatchResult r) {
        String reply = r.reply();
        String err = r.errorMessage();
        log.info("[TUI] non-OK status={} reply='{}' err='{}'",
                r.status(),
                reply == null ? "" : truncate(reply, 100),
                err == null ? "" : truncate(err, 100));
    }

    // ─────────────────────────────────────────────────────────────
    //  Main-thread API — CliRunner 调用
    // ─────────────────────────────────────────────────────────────

    /**
     * 尝试入队一条 query。**非阻塞** —— 队列满立即返 false。
     *
     * @return true = 入队成功;false = 队列满,caller 提示用户
     */
    public boolean offer(String query) {
        return queue.offer(query);
    }

    /** 当前队列深度(不含 inFlight)。 */
    public int queueSize() {
        return queue.size();
    }

    /** worker 正在跑的 query;null = idle。 */
    public String inFlightQuery() {
        return inFlight;
    }

    /** 队列 + inFlight 都空。CliRunner 判 idle 用。 */
    public boolean isIdle() {
        return inFlight == null && queue.isEmpty();
    }

    // ─────────────────────────────────────────────────────────────
    //  Pending question answer 路由(一体化)
    // ─────────────────────────────────────────────────────────────

    /**
     * 尝试把 text 作为 pending question 的 answer 解析并提交。
     *
     * @return true = 已识别并提交 answer(agent 恢复);false = 无 pending 或 parse 失败
     */
    public AnswerResult tryAnswerPending(String text) {
        List<PendingQuestion> pending = agentControl.listPending(SESSION_ID);
        if (pending.isEmpty()) return AnswerResult.NO_PENDING;

        // 用最新一个 pending(通常也就 1 个)
        PendingQuestion q = pending.get(pending.size() - 1);

        Optional<Answer> parsed = parse(text, q);
        if (parsed.isEmpty()) return AnswerResult.PARSE_FAILED;

        boolean ok = agentControl.answer(SESSION_ID, q.askId(), parsed.get());
        return ok ? AnswerResult.ANSWERED : AnswerResult.ASK_ID_STALE;
    }

    /**
     * 用户主动放弃 pending answer(比如 Ctrl-C 或输入 /cancel)—— 全 deny 兜底。
     */
    public int denyAllPending(String reason) {
        List<PendingQuestion> pending = agentControl.listPending(SESSION_ID);
        int denied = 0;
        for (PendingQuestion q : pending) {
            if (agentControl.answer(SESSION_ID, q.askId(),
                    reason == null ? DenyAnswer.userRejected() : new DenyAnswer(reason))) {
                denied++;
            }
        }
        return denied;
    }

    private Optional<Answer> parse(String text, PendingQuestion q) {
        if (text == null) return Optional.empty();
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return Optional.empty();

        if (q instanceof PermissionQuestion) {
            // a/allow / y/yes / 允许 → Allow;其他任何 → Deny(严格白名单)
            String low = trimmed.toLowerCase();
            if (low.equals("a") || low.equals("allow") || low.equals("y") || low.equals("yes")
                    || low.equals("允许") || low.equals("同意")) {
                return Optional.of(new com.xilidou.jooj.agent.control.AllowAnswer());
            }
            if (low.equals("d") || low.equals("deny") || low.equals("n") || low.equals("no")
                    || low.equals("拒绝") || low.equals("不同意")) {
                return Optional.of(DenyAnswer.userRejected());
            }
            return Optional.empty();
        }

        if (q instanceof ClarifyQuestion cq) {
            Optional<ChoiceAnswer> ans = AnswerParser.tryParse(trimmed, cq);
            return ans.map(a -> (Answer) a);
        }

        return Optional.empty();
    }

    /** 三态:input 是否被吸收为 answer。 */
    public enum AnswerResult {
        /** 没 pending,input 不是 answer,应走"新 query"路径 */
        NO_PENDING,
        /** 有 pending 但 parse 失败(比如输入不像 a/d 或 1A/2B),CliRunner 提示重试 */
        PARSE_FAILED,
        /** 已 answer,agent 恢复 */
        ANSWERED,
        /** 有 pending 但 askId 找不到(已 timeout 或 cancel);CliRunner 视同 NO_PENDING */
        ASK_ID_STALE
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
