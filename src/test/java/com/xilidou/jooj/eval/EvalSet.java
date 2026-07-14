package com.xilidou.jooj.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Golden Cases 的集合。
 *
 * <p>职责:
 * <ul>
 *   <li>加载(classpath / 文件 / 目录)</li>
 *   <li>校验(重复 id 一次拦截,提早暴露)</li>
 *   <li>按 category 聚合(为报告服务)</li>
 * </ul>
 *
 * <p>不做打分,打分是 {@link BenchmarkRunner} 的事。
 *
 * <p>Jackson 的 ObjectMapper 复用一份:线程安全,创建开销大。
 */
public class EvalSet {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<GoldenCase>> LIST_TYPE = new TypeReference<>() {};

    private final String name;
    private final List<GoldenCase> cases;

    public EvalSet(String name, List<GoldenCase> cases) {
        this.name = Objects.requireNonNull(name, "name");
        this.cases = Collections.unmodifiableList(new ArrayList<>(cases));
        validateNoDuplicateIds();
    }

    private void validateNoDuplicateIds() {
        long distinct = cases.stream().map(GoldenCase::id).distinct().count();
        if (distinct != cases.size()) {
            throw new IllegalArgumentException("duplicate case id found in EvalSet " + name);
        }
    }

    public String name() { return name; }
    public List<GoldenCase> cases() { return cases; }
    public int size() { return cases.size(); }

    /** 按 category 分组,用于分类报告。保持首次出现顺序。 */
    public Map<String, List<GoldenCase>> groupByCategory() {
        Map<String, List<GoldenCase>> map = new LinkedHashMap<>();
        for (GoldenCase c : cases) {
            map.computeIfAbsent(c.category(), k -> new ArrayList<>()).add(c);
        }
        return map;
    }

    // ============================================================
    // Loader:三种加载入口,通吃 classpath / 单文件 / 目录
    // ============================================================

    /**
     * 从 classpath 资源加载 —— 单文件,内容是 GoldenCase 数组。
     *
     * <p>典型路径:{@code "eval/golden_cases_v1.json"}(会去 src/main/resources 下找)。
     * Spring Boot 打成 fat jar 后仍能工作,这是推荐入口。
     */
    public static EvalSet loadFromClasspath(String name, String resourcePath) {
        try (InputStream in = EvalSet.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath resource not found: " + resourcePath);
            }
            List<GoldenCase> loaded = MAPPER.readValue(in, LIST_TYPE);
            return new EvalSet(name, loaded);
        } catch (IOException e) {
            throw new RuntimeException("failed to load eval set from " + resourcePath, e);
        }
    }

    /** 从任意文件系统路径加载,支持指向单文件或目录。 */
    public static EvalSet loadFromPath(String name, String path) {
        Path p = Paths.get(path);
        if (Files.isDirectory(p)) return loadFromDir(name, p);
        if (Files.isRegularFile(p)) return new EvalSet(name, readFile(p));
        throw new IllegalArgumentException("path not found: " + path);
    }

    /** 加载一个目录里所有 *.json,按文件名排序合并。 */
    public static EvalSet loadFromDir(String name, Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            List<GoldenCase> all = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                  .sorted()
                  .forEach(p -> all.addAll(readFile(p)));
            return new EvalSet(name, all);
        } catch (IOException e) {
            throw new RuntimeException("failed to list dir " + dir, e);
        }
    }

    /** 单文件:内容可以是 GoldenCase 数组或单个对象。 */
    private static List<GoldenCase> readFile(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] bytes = in.readAllBytes();
            // 用第一个非空白字符判断是数组还是对象
            String s = new String(bytes).stripLeading();
            if (s.startsWith("[")) {
                return MAPPER.readValue(bytes, LIST_TYPE);
            }
            return List.of(MAPPER.readValue(bytes, GoldenCase.class));
        } catch (IOException e) {
            throw new RuntimeException("failed to read " + p, e);
        }
    }
}
