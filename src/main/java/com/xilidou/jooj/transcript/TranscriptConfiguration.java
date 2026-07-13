package com.xilidou.jooj.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xilidou.jooj.bootstrap.JoojHome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Transcript 模块的 Spring 装配。
 *
 * <p>跟 {@link com.xilidou.jooj.session.SessionConfiguration} 同模式 ——
 * model 层({@link TranscriptStore} / {@link TranscriptService})不自己 {@code @Component},
 * 测试可以 {@code new ...} 不依赖容器。
 *
 * <h3>目录:{@code ~/.jooj/transcripts/}</h3>
 *
 * <p>由 {@link JoojHome#ensureSubdir} 幂等创建。{@code .deleted/} 子目录在 D6 软归档触发时
 * 由 {@link TranscriptStore#softDelete} 自动 lazy 创建。
 */
@Configuration
@Slf4j
public class TranscriptConfiguration {

    /** {@code ~/.jooj/} 下的子目录名。 */
    public static final String TRANSCRIPTS_SUBDIR = "transcripts";

    @Bean
    public TranscriptStore transcriptStore(
            @Qualifier("joojObjectMapper") ObjectMapper json) throws IOException {
        Path home = JoojHome.getHomePath();
        JoojHome.ensureHome(home);
        Path transcriptsDir = JoojHome.ensureSubdir(home, TRANSCRIPTS_SUBDIR);
        log.info("[Transcript] transcripts dir: {}", transcriptsDir);
        return new TranscriptStore(transcriptsDir, json);
    }

    @Bean
    public TranscriptService transcriptService(TranscriptStore store) {
        return new TranscriptService(store);
    }
}
