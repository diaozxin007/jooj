package cn.langchat.openclaw.weixin.monitor;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class WeixinMonitorStream implements AutoCloseable {
    private final OpenClawWeixinSdk sdk;
    private final String accountId;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private InboundMessageHandler messageHandler = message -> {
    };
    private InboundMessageEventHandler eventHandler = event -> {
    };
    private MonitorLogHandler logHandler = (level, message) -> {
    };
    private Consumer<Throwable> errorHandler = err -> {
    };
    private Runnable startHandler = () -> {
    };
    private Runnable stopHandler = () -> {
    };

    private volatile WeixinLongPollMonitor monitor;
    private volatile Thread worker;

    public WeixinMonitorStream(OpenClawWeixinSdk sdk, String accountId) {
        this.sdk = Objects.requireNonNull(sdk, "sdk");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
    }

    public WeixinMonitorStream onMessage(InboundMessageHandler listener) {
        this.messageHandler = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public WeixinMonitorStream onEvent(InboundMessageEventHandler listener) {
        this.eventHandler = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public WeixinMonitorStream onLog(MonitorLogHandler listener) {
        this.logHandler = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public WeixinMonitorStream onError(Consumer<Throwable> listener) {
        this.errorHandler = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public WeixinMonitorStream onStart(Runnable listener) {
        this.startHandler = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public WeixinMonitorStream onStop(Runnable listener) {
        this.stopHandler = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public WeixinMonitorStream startAsync() {
        ensureNotStarted();
        this.monitor = sdk.createMonitorWithMedia(accountId, messageHandler, eventHandler, logHandler);

        Thread thread = new Thread(this::runLoopSafely, "weixin-monitor-stream-" + accountId);
        thread.setDaemon(true);
        this.worker = thread;
        thread.start();
        return this;
    }

    public void start() {
        ensureNotStarted();
        this.monitor = sdk.createMonitorWithMedia(accountId, messageHandler, eventHandler, logHandler);
        runLoopSafely();
    }

    public WeixinMonitorStream stop() {
        WeixinLongPollMonitor current = monitor;
        if (current != null) {
            current.stop();
        }
        return this;
    }

    public boolean awaitStop(Duration timeout) {
        Thread t = worker;
        if (t == null) {
            return true;
        }

        long waitMillis = timeout == null ? 0L : Math.max(0L, timeout.toMillis());
        try {
            if (waitMillis <= 0L) {
                t.join();
                return true;
            }
            t.join(waitMillis);
            return !t.isAlive();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isRunning() {
        Thread t = worker;
        if (t != null) {
            return t.isAlive();
        }
        return started.get() && monitor != null;
    }

    @Override
    public void close() {
        stop();
        awaitStop(Duration.ofSeconds(2));
    }

    private void runLoopSafely() {
        Runnable startFn = startHandler;
        Runnable stopFn = stopHandler;
        Consumer<Throwable> errorFn = errorHandler;

        try {
            startFn.run();
            WeixinLongPollMonitor current = monitor;
            if (current != null) {
                current.runLoop();
            }
        } catch (Throwable ex) {
            errorFn.accept(ex);
        } finally {
            stopFn.run();
        }
    }

    private void ensureNotStarted() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("monitor stream already started");
        }
    }
}
