package io.github.keyboard01.assistant.media;

import java.io.IOException;

/**
 * 图片抽取的出口。**流式**：抽一张交一张，避免把整份扫描件的所有图片同时堆在内存里
 * （一张 10000×10000 的位图展开就是几百 MB）。
 */
@FunctionalInterface
public interface ImageSink {

    void accept(ExtractedImage image) throws IOException;
}
