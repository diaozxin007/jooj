package com.xilidou.jooj.team;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Git CLI 调用的最小抽象 —— 让 {@link WorktreeService} 可被 mock 测试。
 *
 * <p>对应上游 s18 的 {@code run_git(args)} 函数。
 *
 * <p>jooj 不引入 JGit 依赖(JGit 不支持 worktree 命令),直接调系统 git CLI。
 * 接口抽出来后,测试可以注入一个 mock 实现验业务逻辑,不需要真 git repo。
 *
 * <p>默认实现 {@link DefaultGitClient} 用 {@link ProcessBuilder} 调 git。
 */
public interface GitClient {

    /**
     * 在 {@code cwd} 目录下运行 {@code git <args...>},返回 (success, output)。
     *
     * @param cwd  git 命令的工作目录
     * @param args git 子命令 + 参数(不含开头的 "git")
     * @return {@link GitResult},success=false 时 output 含 stderr
     */
    GitResult run(Path cwd, List<String> args);

    /** Git 命令调用结果。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class GitResult {
        /** 退出码 0 时 true。 */
        private boolean success;
        /** stdout + stderr 合并后的文本(已截断到 5000 字符)。 */
        private String output;

        public static GitResult ok(String out) {
            return new GitResult(true, out);
        }

        public static GitResult error(String out) {
            return new GitResult(false, out);
        }
    }

    /**
     * 生产实现:调系统 git CLI。
     *
     * <p>跟上游 {@code subprocess.run(["git"] + args, cwd=WORKDIR, ...)} 一致。
     * 30s 超时,最大输出 5000 字符。
     */
    class DefaultGitClient implements GitClient {

        private static final int TIMEOUT_SECONDS = 30;
        private static final int MAX_OUTPUT_CHARS = 5000;

        @Override
        public GitResult run(Path cwd, List<String> args) {
            java.util.List<String> cmd = new java.util.ArrayList<>(args.size() + 1);
            cmd.add("git");
            cmd.addAll(args);
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                if (cwd != null) pb.directory(cwd.toFile());

                Process p = pb.start();
                boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    return GitResult.error("Error: git timeout (" + TIMEOUT_SECONDS + "s)");
                }
                String out = new String(p.getInputStream().readAllBytes()).strip();
                if (out.isEmpty()) out = "(no output)";
                if (out.length() > MAX_OUTPUT_CHARS) out = out.substring(0, MAX_OUTPUT_CHARS);
                return p.exitValue() == 0 ? GitResult.ok(out) : GitResult.error(out);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return GitResult.error("Error: " + e.getMessage());
            }
        }
    }
}
