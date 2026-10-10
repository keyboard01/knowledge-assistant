package io.github.keyboard01.assistant.generation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用校验：把回答里的 [n] 拆出来，并判断每个编号是否落在本次证据范围内。
 *
 * <p>纯函数，因为"编造来源"是生成类系统最危险也最难靠人眼发现的缺陷：回答读起来毫无问题，
 * 引用编号却指向一份不存在的文档。只能靠测试钉死，不能指望模型自觉。</p>
 */
public final class CitationCheck {

    /**
     * 只认纯数字的方括号标记，半角 [1] 与全角【1】都收（中文模型经常输出全角），
     * 允许括号内有空格。`[1,2]`、`[一]`、`[1-3]` 一律不算引用——宁可判成"没有引用"，
     * 也不要去猜它想引用哪几条。
     */
    private static final Pattern MARKER = Pattern.compile("[\\[【]\\s*(\\d{1,3})\\s*[\\]】]");

    private CitationCheck() {
    }

    public static Result parse(String answer, int evidenceCount) {
        List<Integer> cited = new ArrayList<>();
        List<Integer> outOfRange = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        if (answer != null) {
            Matcher matcher = MARKER.matcher(answer);
            while (matcher.find()) {
                int rank = Integer.parseInt(matcher.group(1));
                if (rank < 1 || rank > evidenceCount) {
                    if (!outOfRange.contains(rank)) {
                        outOfRange.add(rank);
                    }
                    continue;
                }
                if (seen.add(rank)) {
                    cited.add(rank);
                }
            }
        }
        return new Result(List.copyOf(cited), List.copyOf(outOfRange));
    }

    /** {@code cited} 按首次出现顺序去重；{@code outOfRange} 只用于解释与记账，绝不用于裁剪答案。 */
    public record Result(List<Integer> cited, List<Integer> outOfRange) {

        public boolean hasCitation() {
            return !cited.isEmpty();
        }

        public boolean hasOutOfRange() {
            return !outOfRange.isEmpty();
        }
    }
}
