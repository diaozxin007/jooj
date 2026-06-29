package cn.langchat.openclaw.weixin.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class MapValues {
    private MapValues() {
    }

    public static String string(Map<String, Object> map, String key) {
        return optionalString(map, key).orElse(null);
    }

    public static Optional<String> optionalString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(v));
    }

    public static Integer integer(Map<String, Object> map, String key) {
        return optionalInteger(map, key).orElse(null);
    }

    public static Optional<Integer> optionalInteger(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return Optional.of(n.intValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            return Optional.of(Integer.parseInt(s));
        }
        return Optional.empty();
    }

    public static Long longValue(Map<String, Object> map, String key) {
        return optionalLong(map, key).orElse(null);
    }

    public static Optional<Long> optionalLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return Optional.of(n.longValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            return Optional.of(Long.parseLong(s));
        }
        return Optional.empty();
    }

    public static Map<String, Object> map(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) m;
            return cast;
        }
        return null;
    }

    public static List<Map<String, Object>> mapList(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                out.add(cast);
            }
        }
        return List.copyOf(out);
    }

    public static List<Object> list(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (v instanceof List<?> list) {
            return List.copyOf(list);
        }
        return List.of();
    }
}
