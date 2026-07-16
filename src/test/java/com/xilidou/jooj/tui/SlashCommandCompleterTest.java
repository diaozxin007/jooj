package com.xilidou.jooj.tui;

import com.xilidou.jooj.slashcmd.SlashCommand;
import com.xilidou.jooj.slashcmd.SlashCommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SlashCommandCompleter 单元测试(s23 P6)。
 *
 * <p>验证 Tab 补全逻辑:
 * <ul>
 *   <li>行首以 {@code /} 开头 → 列出所有注册 slash 命令 + /help</li>
 *   <li>不以 {@code /} 开头 → 不补全</li>
 *   <li>不在行首(word 非首个) → 不补全(避免 URL 里的 {@code /} 触发)</li>
 * </ul>
 */
@DisplayName("SlashCommandCompleter 补全 (s23 P6)")
class SlashCommandCompleterTest {

    private TuiConfiguration.SlashCommandCompleter completer;

    private SlashCommandRegistry registryWith(String... names) {
        List<SlashCommand> beans = new ArrayList<>();
        for (String n : names) {
            beans.add(new SlashCommand() {
                @Override public String name() { return n; }
                @Override public String description() { return "test cmd " + n; }
                @Override public String execute(String args, String sessionId) { return ""; }
            });
        }
        return new SlashCommandRegistry(beans);
    }

    @Test
    @DisplayName("行首 / 开头 → 列出所有命令(含 /help)")
    void completes_slash_at_start() {
        completer = new TuiConfiguration.SlashCommandCompleter(
                registryWith("clear", "sessions"));

        ParsedLine line = Mockito.mock(ParsedLine.class);
        Mockito.when(line.word()).thenReturn("/");
        Mockito.when(line.wordIndex()).thenReturn(0);

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(Mockito.mock(LineReader.class), line, candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertThat(values).containsExactlyInAnyOrder("/help", "/clear", "/sessions");
    }

    @Test
    @DisplayName("行首 /cle 部分匹配 → JLine 会 filter,completer 提供全表")
    void completes_partial_slash() {
        completer = new TuiConfiguration.SlashCommandCompleter(
                registryWith("clear", "sessions"));

        // Completer 契约:提供**全部**候选,JLine 自己按 line.word 前缀 filter。
        // 所以 word="/cle" 时 candidates 仍是全表(JLine 后续 filter 出 /clear)。
        ParsedLine line = Mockito.mock(ParsedLine.class);
        Mockito.when(line.word()).thenReturn("/cle");
        Mockito.when(line.wordIndex()).thenReturn(0);

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(Mockito.mock(LineReader.class), line, candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertThat(values).contains("/clear").hasSize(3);
    }

    @Test
    @DisplayName("word 不以 / 开头 → 不补全(free-text query)")
    void skips_free_text() {
        completer = new TuiConfiguration.SlashCommandCompleter(
                registryWith("clear"));

        ParsedLine line = Mockito.mock(ParsedLine.class);
        Mockito.when(line.word()).thenReturn("hello");
        Mockito.when(line.wordIndex()).thenReturn(0);

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(Mockito.mock(LineReader.class), line, candidates);

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("word 在句中(wordIndex > 0) → 不补全,避免 URL 里的 / 触发")
    void skips_mid_line_slash() {
        completer = new TuiConfiguration.SlashCommandCompleter(
                registryWith("clear"));

        // 场景:用户输入 "check https://example.com/foo",光标在最后 word 上
        // 该 word 也以 / 开头(URL 里的 /foo 段),不应该补全
        ParsedLine line = Mockito.mock(ParsedLine.class);
        Mockito.when(line.word()).thenReturn("/foo");
        Mockito.when(line.wordIndex()).thenReturn(3);      // 第 4 个 word

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(Mockito.mock(LineReader.class), line, candidates);

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("空 registry(仅 /help 内建) → 只列 /help")
    void empty_registry_still_offers_help() {
        completer = new TuiConfiguration.SlashCommandCompleter(
                registryWith());

        ParsedLine line = Mockito.mock(ParsedLine.class);
        Mockito.when(line.word()).thenReturn("/");
        Mockito.when(line.wordIndex()).thenReturn(0);

        List<Candidate> candidates = new ArrayList<>();
        completer.complete(Mockito.mock(LineReader.class), line, candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertThat(values).containsExactly("/help");
    }
}
