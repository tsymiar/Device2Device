package com.tsymiar.device2device.utils;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

/**
 * 把模型回复里的 Markdown 渲染成 Spanned，直接塞给 TextView。
 *
 * 没有引第三方库（Markwon 那类）：离线构建下拿不到依赖，而且聊天气泡要的只是
 * 常见的那几种格式，自己处理一小段文本反而更可控。
 *
 * 支持：``` 代码块、` 行内代码、**粗体**、*斜体*、~~删除线~~、
 *       # 标题、> 引用、- 无序列表、[文字](链接)
 *
 * 逐行处理块状语法，行内语法交给 renderInline() 递归处理。
 */
public final class MarkdownUtils {

    private static final int SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
    /** 代码底色：气泡是浅灰，用一层很淡的黑压一下就够 */
    private static final int CODE_BG = 0x1E000000;

    private MarkdownUtils() {
    }

    public static Spanned render(String src) {
        if (src == null || src.isEmpty()) return new SpannableStringBuilder("");

        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = src.replace("\r\n", "\n").split("\n", -1);

        boolean inFence = false;
        StringBuilder fence = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!inFence) {
                    inFence = true;
                    fence.setLength(0);
                } else {
                    inFence = false;
                    appendCodeBlock(out, fence.toString());
                    fence.setLength(0);
                }
                continue;
            }
            if (inFence) {
                if (fence.length() > 0) fence.append('\n');
                fence.append(line);
                continue;
            }
            appendLine(out, line);
        }
        // 回复被截断时 ``` 可能没闭合，剩下的一并按代码块处理
        if (inFence) appendCodeBlock(out, fence.toString());

        // 末尾多出来的换行（分隔行块时加的）去掉，气泡底部不会空一截
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private static void appendLine(SpannableStringBuilder out, String line) {
        int start = out.length();
        String head = line.trim();

        // 标题：# 到 ######，必须跟着空格，避免把 "#tag" 这种当标题
        if (head.startsWith("#")) {
            int level = 0;
            while (level < head.length() && head.charAt(level) == '#') level++;
            if (level <= 6 && head.length() > level + 1 && head.charAt(level) == ' ') {
                out.append(head.substring(level + 1)).append('\n');
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), SPAN);
                out.setSpan(new RelativeSizeSpan(1.25f - 0.05f * level), start, out.length(), SPAN);
                return;
            }
        }

        if (head.startsWith(">")) {
            out.append(head.substring(1).trim()).append('\n');
            out.setSpan(new QuoteSpan(), start, out.length(), SPAN);
            out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), SPAN);
            return;
        }

        if (head.startsWith("- ") || head.startsWith("* ") || head.startsWith("+ ")) {
            out.append(renderInline(head.substring(2))).append('\n');
            out.setSpan(new BulletSpan(20), start, out.length(), SPAN);
            return;
        }

        // 行内语法先解析成 Spanned 再 append：SpannableStringBuilder.append()
        // 会把插入文本上带的 span 一起搬过来
        out.append(renderInline(line)).append('\n');
    }

    private static void appendCodeBlock(SpannableStringBuilder out, String code) {
        if (code.isEmpty()) return;
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        int start = out.length();
        out.append(code).append('\n');
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(), SPAN);
        out.setSpan(new BackgroundColorSpan(CODE_BG), start, out.length(), SPAN);
        out.setSpan(new RelativeSizeSpan(0.9f), start, out.length(), SPAN);
        out.setSpan(new LeadingMarginSpan.Standard(16), start, out.length(), SPAN);
    }

    private static SpannableStringBuilder renderInline(String src) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0;
        final int n = src.length();

        while (i < n) {
            char c = src.charAt(i);

            // 行内代码：优先级最高，里面的内容不再解析
            if (c == '`') {
                int end = findEnd(src, i + 1, "`");
                if (end > 0) {
                    int start = sb.length();
                    sb.append(src, i + 1, end);
                    sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(), SPAN);
                    sb.setSpan(new BackgroundColorSpan(CODE_BG), start, sb.length(), SPAN);
                    i = end + 1;
                    continue;
                }
            }

            if (c == '*' || c == '_') {
                boolean dbl = i + 1 < n && src.charAt(i + 1) == c;
                String marker = dbl ? src.substring(i, i + 2) : String.valueOf(c);
                int end = findEnd(src, i + marker.length(), marker);
                // 单个 * / _ 紧跟在字母数字后面时不算斜体：some_var_name 不该被切开
                boolean ok = dbl || i == 0
                        || !Character.isLetterOrDigit(src.charAt(i - 1));
                if (end > 0 && ok) {
                    int start = sb.length();
                    sb.append(renderInline(src.substring(i + marker.length(), end)));
                    sb.setSpan(new StyleSpan(dbl ? Typeface.BOLD : Typeface.ITALIC),
                            start, sb.length(), SPAN);
                    i = end + marker.length();
                    continue;
                }
            }

            if (c == '~' && i + 1 < n && src.charAt(i + 1) == '~') {
                int end = findEnd(src, i + 2, "~~");
                if (end > 0) {
                    int start = sb.length();
                    sb.append(renderInline(src.substring(i + 2, end)));
                    sb.setSpan(new StrikethroughSpan(), start, sb.length(), SPAN);
                    i = end + 2;
                    continue;
                }
            }

            if (c == '[') {
                int close = src.indexOf("](", i);
                if (close > i) {
                    int urlEnd = src.indexOf(')', close + 2);
                    if (urlEnd > close) {
                        int start = sb.length();
                        sb.append(src, i + 1, close);
                        sb.setSpan(new URLSpan(src.substring(close + 2, urlEnd)),
                                start, sb.length(), SPAN);
                        i = urlEnd + 1;
                        continue;
                    }
                }
            }

            sb.append(c);
            i++;
        }
        return sb;
    }

    /** 找配对的结束标记；紧挨着的（** 这种空的）不算 */
    private static int findEnd(String src, int from, String marker) {
        int idx = src.indexOf(marker, from);
        return idx > from ? idx : -1;
    }
}
