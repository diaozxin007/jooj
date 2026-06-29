package cn.langchat.openclaw.weixin.util;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public class StreamingMarkdownFilter {
    private final StringBuilder buffer = new StringBuilder();

    public String feed(String delta) {
        if (delta != null) {
            buffer.append(delta);
        }
        return "";
    }

    public String flush() {
        String input = buffer.toString();
        buffer.setLength(0);
        return sanitize(input);
    }

    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String s = input;

        // fenced code block: remove fence markers, keep content
        s = s.replaceAll("(?ms)^```[^\\n]*\\n", "");
        s = s.replaceAll("(?m)^```\\s*$", "");

        // block quote markers
        s = s.replaceAll("(?m)^>\\s?", "");

        // heading markers (keep text)
        s = s.replaceAll("(?m)^#{1,6}\\s+", "");

        // horizontal rules
        s = s.replaceAll("(?m)^\\s*([-*_])\\s*\\1\\s*\\1[-*_\\s]*$", "");

        // markdown images ![alt](url) -> alt
        s = s.replaceAll("!\\[([^\\]]*)\\]\\(([^)]+)\\)", "$1");

        // markdown links [text](url) -> text
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1");

        // inline markers
        s = s.replace("***", "");
        s = s.replace("___", "");
        s = s.replace("~~", "");
        s = s.replace("`", "");
        s = s.replace("**", "");
        s = s.replace("__", "");

        // keep single * and _ literal as-is to avoid math/text corruption
        return s;
    }
}
