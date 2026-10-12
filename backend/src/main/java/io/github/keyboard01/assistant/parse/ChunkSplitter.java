package io.github.keyboard01.assistant.parse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义块 → 检索片段。切分粒度是检索质量的第一变量，所以这一段必须是**纯函数**、有单测：
 * 实测（余弦）同一句"一线城市指北京、上海、广州、深圳。"，埋在一整页 802 字里只有 0.21，
 * 独立成块 0.75。粒度错了，后面怎么调阈值都是换一种错法。
 *
 * <p>预算用 {@link ChunkOptions}（**估算 token**而非字符）：中文近似 1 字 1 token、英文按词，
 * 按字符比大小会让中英混排的实际长度差出一截。规则：</p>
 * <ol>
 *   <li><b>结构优先</b>：段落、列表项、表格行各自是语义单元，先按单元成块，不做"跨单元硬拼"；</li>
 *   <li><b>长段落按句打包</b>：超过 {@code maxTokens} 的段落按 。；！？ 拆句，贪心打包到
 *       {@code targetTokens} 附近（单片仍受 {@code maxTokens} 约束；超长单句按字符硬切）；</li>
 *   <li><b>相邻短块合并</b>：同一章节路径、同一类型、同一页的相邻块，若已攒内容不足
 *       {@code minTokens}，并入下一块——把"注 2：……"这种短句与紧随其后的内容放在一起，
 *       避免一片只有半句话；</li>
 *   <li><b>overlap</b>：相邻片段保留 {@code overlapTokens} 的尾部重叠，防止答案正好卡在边界被切两半；</li>
 *   <li><b>表格重复表头</b>：表格行被拆分时，把表头（DOCX 解析时拼在行首、"："前）加到每一片前。</li>
 * </ol>
 *
 * <p>跨章节、跨页码、或类型不同（正文 vs 表格行）的块**绝不合并**——那正是把不相关的两句粘在一起的元凶。
 * 上下文不足的问题放在检索阶段解决（命中后把相邻块一起给模型），不在切分阶段用"多带一点"去换召回精度。</p>
 */
public final class ChunkSplitter {

    /** 低于这个 token 数视为"碎",并入上一片——它自己承载不了语义。 */
    static final int MIN_STANDALONE_TOKENS = 15;

    /** DOCX 解析器把表头拼在各行行首的分隔符（见 {@code DocxBlockParser}）。 */
    private static final String TABLE_HEADER_SEPARATOR = " —— ";

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。；！？!?])");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+(?:\\.\\d+)*)");
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff]");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern OTHER = Pattern.compile("[^\\u3400-\\u9fffA-Za-z0-9_\\s]");

    private ChunkSplitter() {
    }

    public static List<ParsedChunk> split(List<ParsedBlock> blocks) {
        return split(blocks, ChunkOptions.DEFAULT);
    }

    public static List<ParsedChunk> split(List<ParsedBlock> blocks, ChunkOptions options) {
        List<ParsedChunk> chunks = new ArrayList<>();
        Deque<String> section = new ArrayDeque<>();
        Pending pending = null;
        String headingOnly = null;
        Integer headingPage = null;
        boolean sectionHasBody = true;

        for (ParsedBlock block : blocks) {
            if (!block.hasText()) {
                continue;
            }
            if (block.kind() == ParsedBlock.Kind.HEADING) {
                pending = flush(chunks, pending);
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
            for (String piece : pieces(block.text(), block.kind(), options)) {
                pending = offer(chunks, pending, block.kind(), section, piece, page(block), options);
            }
            sectionHasBody = true;
        }
        flush(chunks, pending);
        if (!sectionHasBody && headingOnly != null) {
            add(chunks, ParsedBlock.Kind.HEADING, section, headingOnly, headingPage);
        }
        return chunks;
    }

    /**
     * 收下一个片段：能并进 pending 就并，否则先冲掉 pending 再起新的。
     * 攒到 {@code targetTokens} 就冲掉——再等下去只会越攒越大。
     */
    private static Pending offer(List<ParsedChunk> chunks, Pending pending, ParsedBlock.Kind kind,
                                 Deque<String> section, String text, Integer page, ChunkOptions options) {
        String path = String.join(" > ", section);
        String sectionPath = path.isEmpty() ? null : path;
        if (pending != null && canMerge(pending, kind, sectionPath, page, text, options)) {
            pending.append(text, page);
            if (estimateTokens(pending.text) >= options.targetTokens()) {
                add(chunks, pending);
                return null;
            }
            return pending;
        }
        flush(chunks, pending);
        Pending fresh = new Pending(kind, sectionPath, section.size(), page, text);
        if (estimateTokens(text) >= options.targetTokens()) {
            add(chunks, fresh);
            return null;
        }
        return fresh;
    }

    /** 只有同一章节路径、同一类型、同一页的文本类相邻块才合并；pending 已够长就不再并。 */
    private static boolean canMerge(Pending pending, ParsedBlock.Kind kind, String sectionPath,
                                    Integer page, String text, ChunkOptions options) {
        if (!mergeableKind(pending.kind) || pending.kind != kind) {
            return false;
        }
        if (!Objects.equals(pending.sectionPath, sectionPath) || !Objects.equals(pending.pageFrom, page)) {
            return false;
        }
        if (estimateTokens(pending.text) >= options.minTokens()) {
            return false;
        }
        return estimateTokens(pending.text) + estimateTokens(text) <= options.maxTokens();
    }

    private static boolean mergeableKind(ParsedBlock.Kind kind) {
        return kind == ParsedBlock.Kind.PARAGRAPH || kind == ParsedBlock.Kind.BULLET;
    }

    private static Pending flush(List<ParsedChunk> chunks, Pending pending) {
        if (pending != null) {
            add(chunks, pending);
        }
        return null;
    }

    private static void add(List<ParsedChunk> chunks, Pending pending) {
        chunks.add(new ParsedChunk(chunks.size(), pending.kind.name(), pending.level,
                pending.sectionPath, pending.pageFrom, pending.pageTo, pending.text));
    }

    /** 标题下什么都没有时，标题自己也得留下——否则"某章讲了什么"这类问题连候选都没有。 */
    private static void add(List<ParsedChunk> chunks, ParsedBlock.Kind kind, Deque<String> section,
                            String text, Integer page) {
        String path = String.join(" > ", section);
        chunks.add(new ParsedChunk(chunks.size(), kind.name(), section.size(),
                path.isEmpty() ? null : path, page, page, text));
    }

    /**
     * 把一个块切成片：够短就原样；长段落按句拆后贪心打包到 target 附近，片间留 overlap，
     * 碎尾并回上一片；表格行拆片时重复表头。只在同一个块内操作，绝不跨块——跨块合并是
     * {@link #offer} 的职责，且只在同章节路径、同类、同页时才发生。
     */
    static List<String> pieces(String text, ParsedBlock.Kind kind, ChunkOptions options) {
        String value = HeadingRules.clean(text);
        if (value.isEmpty()) {
            return List.of();
        }
        if (estimateTokens(value) <= options.maxTokens()) {
            return List.of(value);
        }
        List<String> pieces = pack(fragments(value, options), options);
        return repeatTableHeader(pieces, kind);
    }

    /** 拆句；单句超过 maxTokens 时按字符硬切，保证不丢内容（记 whole=false，之后不加 overlap）。 */
    private static List<Fragment> fragments(String value, ChunkOptions options) {
        List<Fragment> fragments = new ArrayList<>();
        for (String part : SENTENCE_END.split(value)) {
            String sentence = part.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            if (estimateTokens(sentence) > options.maxTokens()) {
                int step = Math.max(1, options.maxTokens());
                for (int start = 0; start < sentence.length(); start += step) {
                    fragments.add(new Fragment(
                            sentence.substring(start, Math.min(sentence.length(), start + step)), false));
                }
            } else {
                fragments.add(new Fragment(sentence, true));
            }
        }
        return fragments;
    }

    /** 贪心打包：累到 target 附近出一片；收尾若只剩碎句就并回上一片，不另起一片。 */
    private static List<String> pack(List<Fragment> fragments, ChunkOptions options) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < fragments.size(); i++) {
            Fragment fragment = fragments.get(i);
            if (current.length() > 0) {
                int merged = estimateTokens(current.toString()) + estimateTokens(fragment.text);
                if (merged > options.targetTokens()) {
                    int remaining = remainingTokens(fragments, i);
                    if (remaining < MIN_STANDALONE_TOKENS
                            && estimateTokens(current.toString()) + remaining <= options.maxTokens()) {
                        current.append(fragment.text);
                        continue;
                    }
                    String tail = fragment.whole
                            ? tailByTokens(current.toString(), options.overlapTokens()) : "";
                    if (estimateTokens(tail) + estimateTokens(fragment.text) > options.maxTokens()) {
                        tail = "";
                    }
                    pieces.add(current.toString());
                    current.setLength(0);
                    current.append(tail).append(fragment.text);
                    continue;
                }
            }
            current.append(fragment.text);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private static int remainingTokens(List<Fragment> fragments, int fromIndex) {
        int total = 0;
        for (int i = fromIndex; i < fragments.size(); i++) {
            total += estimateTokens(fragments.get(i).text);
        }
        return total;
    }

    /** 取尾部约 {@code tokens} 个 token 的文本做 overlap，尽量从句子的开头起步（借鉴 movo 的行尾截取）。 */
    private static String tailByTokens(String text, int tokens) {
        if (tokens <= 0 || text.isBlank()) {
            return "";
        }
        String trimmed = text.strip();
        if (estimateTokens(trimmed) <= tokens) {
            return trimmed;
        }
        int start = Math.max(0, trimmed.length() - tokens);
        for (int i = start; i < trimmed.length() - 1; i++) {
            char ch = trimmed.charAt(i);
            if (ch == '。' || ch == '；' || ch == '！' || ch == '？' || ch == '!' || ch == '?') {
                start = i + 1;
                break;
            }
        }
        return trimmed.substring(start).strip();
    }

    /** 表格行被拆成多片时，把表头（首个分隔符之前）加到第 2 片起每一片前；PDF 无表头则原样返回。 */
    private static List<String> repeatTableHeader(List<String> pieces, ParsedBlock.Kind kind) {
        if (kind != ParsedBlock.Kind.TABLE_ROW || pieces.size() < 2) {
            return pieces;
        }
        int index = pieces.get(0).indexOf(TABLE_HEADER_SEPARATOR);
        if (index <= 0) {
            return pieces;
        }
        String header = pieces.get(0).substring(0, index);
        List<String> repeated = new ArrayList<>(pieces.size());
        repeated.add(pieces.get(0));
        for (int i = 1; i < pieces.size(); i++) {
            repeated.add(header + TABLE_HEADER_SEPARATOR + pieces.get(i));
        }
        return repeated;
    }

    /**
     * 本地 token 估算（移植自 movo 的轻量 tokenizer，不引依赖）：CJK 每字 1、`[A-Za-z0-9_]+` 每段 1、
     * 其余非空白字符每 2 个算 1。够切分比大小用，不追求与真实分词器一致。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = count(CJK, text);
        int words = count(WORD, text);
        int other = count(OTHER, text);
        return Math.max(1, cjk + words + Math.max(1, other / 2));
    }

    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int total = 0;
        while (matcher.find()) {
            total++;
        }
        return total;
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

    /** 句子/硬切片段：{@code whole=false} 表示是超长单句硬切出来的半句，之后不参与 overlap 截取。 */
    private record Fragment(String text, boolean whole) {
    }

    /** 跨块合并的缓冲：攒够之前先不落库，攒到 target 或遇到不该并的块再冲出去。 */
    private static final class Pending {
        private final ParsedBlock.Kind kind;
        private final String sectionPath;
        private final int level;
        private final Integer pageFrom;
        private Integer pageTo;
        private String text;

        private Pending(ParsedBlock.Kind kind, String sectionPath, int level, Integer page, String text) {
            this.kind = kind;
            this.sectionPath = sectionPath;
            this.level = level;
            this.pageFrom = page;
            this.pageTo = page;
            this.text = text;
        }

        private void append(String more, Integer page) {
            this.text = this.text + "\n\n" + more;
            if (page != null) {
                this.pageTo = page;
            }
        }
    }
}
