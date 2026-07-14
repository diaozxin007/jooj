package com.xilidou.jooj.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 {@link EvalSet} 的加载、去重与分组行为。
 *
 * <p>覆盖:
 * <ul>
 *   <li>classpath 资源加载(读真实 golden_cases_v1.json,反证 Jackson 配置正确)</li>
 *   <li>本地文件加载(数组格式)</li>
 *   <li>本地文件加载(单对象格式)</li>
 *   <li>目录加载 + 排序</li>
 *   <li>重复 id 报错</li>
 *   <li>按 category 分组保序</li>
 * </ul>
 */
class EvalSetTest {

    @Test
    @DisplayName("loadFromClasspath: 读取真实 golden_cases_v1.json,17 个 case,4 个分类")
    void load_real_resource() {
        EvalSet set = EvalSet.loadFromClasspath("v1", "eval/golden_cases_v1.json");
        assertEquals(17, set.size());
        assertEquals(4, set.groupByCategory().size());
        assertTrue(set.groupByCategory().containsKey("data-accuracy"));
        assertTrue(set.groupByCategory().containsKey("hallucination-guard"));
    }

    @Test
    @DisplayName("loadFromPath: 单文件、数组格式")
    void load_single_file_array(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("cases.json");
        Files.writeString(p, """
                [
                  {"id":"a","input":"i1","expected":"16","scorerType":"EXACT_MATCH","weight":1.0},
                  {"id":"b","category":"fmt","input":"i2","expected":".*","scorerType":"REGEX_MATCH","weight":0.5}
                ]
                """);
        EvalSet set = EvalSet.loadFromPath("t", p.toString());
        assertEquals(2, set.size());
        assertEquals("general", set.cases().get(0).category()); // 缺省 category
        assertEquals("fmt", set.cases().get(1).category());
    }

    @Test
    @DisplayName("loadFromPath: 单文件、单对象格式(便于教学场景一 case 一文件)")
    void load_single_file_object(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path p = tmp.resolve("case-001.json");
        Files.writeString(p, """
                {"id":"a","input":"i1","expected":"16","scorerType":"EXACT_MATCH","weight":1.0}
                """);
        EvalSet set = EvalSet.loadFromPath("t", p.toString());
        assertEquals(1, set.size());
        assertEquals("a", set.cases().get(0).id());
    }

    @Test
    @DisplayName("loadFromDir: 加载目录所有 *.json,按文件名排序合并")
    void load_directory(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("case-002.json"), """
                {"id":"b","input":"i2","expected":".*","scorerType":"REGEX_MATCH","weight":0.5}
                """);
        Files.writeString(tmp.resolve("case-001.json"), """
                {"id":"a","input":"i1","expected":"16","scorerType":"EXACT_MATCH","weight":1.0}
                """);
        // 干扰文件不能被加载
        Files.writeString(tmp.resolve("readme.txt"), "not json");

        EvalSet set = EvalSet.loadFromPath("dir", tmp.toString());
        assertEquals(2, set.size());
        assertEquals("a", set.cases().get(0).id()); // 排序
        assertEquals("b", set.cases().get(1).id());
    }

    @Test
    @DisplayName("重复 id 应在构造时抛异常")
    void duplicate_id_throws() {
        List<GoldenCase> cases = List.of(
                GoldenCase.of("dup", null, "in", null, ScorerType.EXACT_MATCH, null, null),
                GoldenCase.of("dup", null, "in", null, ScorerType.EXACT_MATCH, null, null));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new EvalSet("bad", cases));
        assertTrue(ex.getMessage().contains("duplicate"));
    }

    @Test
    @DisplayName("groupByCategory 保持首次出现顺序,便于报告一致输出")
    void group_by_category_preserves_order() {
        List<GoldenCase> cases = List.of(
                GoldenCase.of("1", "cat-A", "i", "e", ScorerType.EXACT_MATCH, 1.0, null),
                GoldenCase.of("2", "cat-B", "i", "e", ScorerType.EXACT_MATCH, 1.0, null),
                GoldenCase.of("3", "cat-A", "i", "e", ScorerType.EXACT_MATCH, 1.0, null));
        EvalSet set = new EvalSet("t", cases);
        List<String> order = List.copyOf(set.groupByCategory().keySet());
        assertEquals(List.of("cat-A", "cat-B"), order);
    }
}
