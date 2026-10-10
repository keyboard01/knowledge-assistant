package com.hykj.assistant.parse;

/**
 * 切分后要落库的片段。
 *
 * <p>{@code content} 是给人看、给关键词检索用的正文；{@code sectionPath} 是它所在的章节路径，
 * 向量化时会把路径拼在正文前面一起嵌入（否则"在哪个章节下"这层语义就丢了），
 * 但展示与引用只用正文——路径是定位信息，不是原文。</p>
 */
public record ParsedChunk(int index, String kind, int level, String sectionPath,
                          Integer pageFrom, Integer pageTo, String content) {

    /** 向量化的输入：章节路径 + 正文。 */
    public String embedText() {
        return sectionPath == null || sectionPath.isBlank() ? content : sectionPath + "\n" + content;
    }
}