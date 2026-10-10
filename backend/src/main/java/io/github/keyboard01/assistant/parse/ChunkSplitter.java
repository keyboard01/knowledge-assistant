package io.github.keyboard01.assistant.parse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义块 → 检索片段。切分粒度是检索质量的第一变量，所以这一段必须是**纯函数**、有单测：
 * 实测（余弦）同一句"一线城市指北京、上海、广州、深圳。"，埋在一整页 802 字里只有 0.21，
 * 独立成块 0.75。粒度错了，后面怎么调阈值都是换一种错法。
 *
 * <p>三条规则：</p>
 * <ol>
 *   <li><b>不跨语义单元合并</b>：段落、列表项、表格行各自成块，不做"攒够多少字再切"的打包——
 *       打包正是把定义句重新埋掉的元凶；</li>
 *   <li><b>长段落按句子拆</b>：超过 {@code maxChars} 的段落按 。；！？ 拆成句级块；</li>
 *   <li><b>碎句（不足 15 字）并入上一块</b>：像"不适用本办法。"这种独立成块没有意义。</li>
 * </ol>
 *
 * <p>上下文不足的问题放在检索阶段解决（命中后把相邻块一起给模型），不在切分阶段解决——
 * 切分阶段的"为了上下文多带一点"会静默地毁掉召回精度。</p>
 */
public final class ChunkSplitter {

    public static final int DEFAULT_MAX_CHARS = 200;
    /** 比这还短的句子并入上一块：它自己承载不了语义。 */
    static final int MIN_STANDALONE_CHARS = 15;

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。；！？!?])");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)*)");

    private ChunkSplitter() {
    }

    public static List<ParsedChunk> split(List<ParsedBlock> blocks) {
        return split(blocks, DEFAULT_MAX_CHARS);
    }

    public static List<ParsedChunk> split(List<ParsedBlock> blocks, int maxChars) {
        List<ParsedChunk> chunks = new ArrayList<>();
        Deque<String> section = new ArrayDeque<>();
        String headingOnly = null;
        Integer headingPage = null;
        boolean sectionHasBody = true;

        for (ParsedBlock block : blocks) {
            if (!block.hasText()) {
                continue;
            }
            if (block.kind() == ParsedBlock.Kind.HEADING) {
                if (!sectionHasBody && headingOnly != null) {
                    add(chunks, ParsedBlock.Kind.HEADING, section, headingOnly, headingPage);
                }
                int level = levelOf(block.text(), section.size());
                while (section.size() >= level) {
                    section.removeLast();
                }
                section.addLast(HeadingRules.clean(block.text()));
                headingOnly = block.text();
                headingPage = page(block);
                sectionHasBody = false;
                continue;
            }
            for (String piece : pieces(block.text(), maxChars)) {
                add(chunks, block.kind(), section, piece, page(block));
                sectionHasBody = true;
            }
        }
        if (!sectionHasBody && headingOnly != null) {
            add(chunks, ParsedBlock.Kind.HEADING, section, headingOnly, headingPage);
        }
        return chunks;
    }

    /** 标题下什么都没有时，标题自己也得留下——否则"某章讲了什么"这类问题连候选都没有。 */
    private static void add(List<ParsedChunk> chunks, ParsedBlock.Kind kind, Deque<String> section,
                            String text, Integer page) {
        String path = String.join(" > ", section);
        chunks.add(new ParsedChunk(chunks.size(), kind.name(), section.size(),
                path.isEmpty() ? null : path, page, page, text));
    }

    /**
     * 把一个块切成片：够短就原样；长段落按句切；切出来的碎句并回上一片。
     * 只在同一个块内合并，绝不跨块——跨块合并就是"把不相关的两句粘在一起"。
     */
    static List<String> pieces(String text, int maxChars) {
        String value = HeadingRules.clean(text);
        if (value.isEmpty()) {
            return List.of();
        }
        if (value.length() <= maxChars) {
            return List.of(value);
        }
        List<String> sentences = new ArrayList<>();
        for (String part : SENTENCE_END.split(value)) {
            String sentence = part.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > maxChars * 2) {
                for (int start = 0; start < sentence.length(); start += maxChars) {
                    sentences.add(sentence.substring(start, Math.min(sentence.length(), start + maxChars)));
                }
            } else {
                sentences.add(sentence);
            }
        }
        List<String> pieces = new ArrayList<>();
        for (String sentence : sentences) {
            if (!pieces.isEmpty() && sentence.length() < MIN_STANDALONE_CHARS
                    && pieces.get(pieces.size() - 1).length() + sentence.length() <= maxChars) {
                pieces.set(pieces.size() - 1, pieces.get(pieces.size() - 1) + sentence);
                continue;
            }
            pieces.add(sentence);
        }
        return pieces.isEmpty() ? List.of(value) : pieces;
    }

    /** "3.4.1 xxx" → 3 级；"第一章 xxx" / "一、xxx" → 1 级；没编号的按当前深度 +1。 */
    static int levelOf(String heading, int currentDepth) {
        String text = HeadingRules.clean(heading);
        Matcher matcher = NUMBERED.matcher(text);
        if (matcher.find()) {
            String number = matcher.group(1);
            int level = 1;
            for (int i = 0; i < number.length(); i++) {
                if (number.charAt(i) == '.') {
                    level++;
                }
            }
            return level;
        }
        if (text.matches("^第[一二三四五六七八九十百]+[章节条].*$")) {
            return 1;
        }
        return Math.min(currentDepth + 1, 3);
    }

    private static Integer page(ParsedBlock block) {
        return block.page() <= 0 ? null : block.page();
    }
}