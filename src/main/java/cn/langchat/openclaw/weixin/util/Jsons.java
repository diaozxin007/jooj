package cn.langchat.openclaw.weixin.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class Jsons {
    private Jsons() {
    }

    public static Object parse(String json) {
        return new Parser(json).parse();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("JSON root is not an object");
    }

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String s) {
            writeString(sb, s);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, element);
            }
            sb.append(']');
            return;
        }
        if (value instanceof byte[] bytes) {
            writeString(sb, java.util.Base64.getEncoder().encodeToString(bytes));
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int j = hex.length(); j < 4; j++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * @since 2026-04-20
     * @author LangChat Team
     */
    private static final class Parser {
        private final String json;
        private int i;

        private Parser(String json) {
            this.json = json;
            this.i = 0;
        }

        private Object parse() {
            skipWs();
            Object value = parseValue();
            skipWs();
            if (i != json.length()) {
                throw error("Trailing characters");
            }
            return value;
        }

        private Object parseValue() {
            skipWs();
            if (i >= json.length()) {
                throw error("Unexpected end");
            }
            char c = json.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            skipWs();
            Map<String, Object> out = new LinkedHashMap<>();
            if (peek('}')) {
                expect('}');
                return out;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                out.put(key, val);
                skipWs();
                if (peek('}')) {
                    expect('}');
                    return out;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            skipWs();
            List<Object> out = new ArrayList<>();
            if (peek(']')) {
                expect(']');
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWs();
                if (peek(']')) {
                    expect(']');
                    return out;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < json.length()) {
                char c = json.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= json.length()) {
                        throw error("Invalid escape");
                    }
                    char e = json.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > json.length()) {
                                throw error("Invalid unicode escape");
                            }
                            String hex = json.substring(i, i + 4);
                            i += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw error("Unsupported escape: " + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Object parseNumber() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            if (peek('0')) {
                i++;
            } else {
                consumeDigits();
            }
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                i++;
                consumeDigits();
            }
            if (peek('e') || peek('E')) {
                decimal = true;
                i++;
                if (peek('+') || peek('-')) {
                    i++;
                }
                consumeDigits();
            }
            String raw = json.substring(start, i);
            if (raw.isEmpty()) {
                throw error("Invalid number");
            }
            try {
                if (decimal) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                throw error("Invalid number: " + raw);
            }
        }

        private void consumeDigits() {
            if (i >= json.length() || !Character.isDigit(json.charAt(i))) {
                throw error("Expected digit");
            }
            while (i < json.length() && Character.isDigit(json.charAt(i))) {
                i++;
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (json.startsWith(literal, i)) {
                i += literal.length();
                return value;
            }
            throw error("Expected literal: " + literal);
        }

        private void expect(char c) {
            if (i >= json.length() || json.charAt(i) != c) {
                throw error("Expected '" + c + "'");
            }
            i++;
        }

        private boolean peek(char c) {
            return i < json.length() && json.charAt(i) == c;
        }

        private void skipWs() {
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + i);
        }
    }
}
