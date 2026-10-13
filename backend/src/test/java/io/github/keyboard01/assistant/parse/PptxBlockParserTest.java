package io.github.keyboard01.assistant.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PPTX 文本解析：正文成块、表格整行成块、页码 = 幻灯片序号。 */
class PptxBlockParserTest {

    @Test
    @DisplayName("文本与表格都能成块，且页码是幻灯片序号")
    void parsesTextAndTable(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deck.pptx");
        try (XMLSlideShow deck = new XMLSlideShow()) {
            XSLFSlide first = deck.createSlide();
            XSLFTextBox box = first.createTextBox();
            box.setText("液压站维护要点");

            XSLFSlide second = deck.createSlide();
            XSLFTextBox title = second.createTextBox();
            title.setText("点检清单");
            XSLFTable table = second.createTable(2, 2);
            table.getRows().get(0).getCells().get(0).setText("项目");
            table.getRows().get(0).getCells().get(1).setText("周期");
            table.getRows().get(1).getCells().get(0).setText("油位");
            table.getRows().get(1).getCells().get(1).setText("每周");
            deck.write(Files.newOutputStream(file));
        }

        List<ParsedBlock> blocks = PptxBlockParser.parse(file);

        assertTrue(blocks.stream().anyMatch(block -> block.page() == 1 && block.text().contains("液压站维护要点")),
                "第 1 张幻灯片的正文要能成块");
        assertTrue(blocks.stream().anyMatch(block -> block.page() == 2
                        && block.kind() == ParsedBlock.Kind.TABLE_ROW
                        && block.text().contains("油位") && block.text().contains("每周")),
                "第 2 张幻灯片里的表格行要能成块且带页码");
    }

    @Test
    @DisplayName("空幻灯片不产生空块")
    void emptySlideProducesNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.pptx");
        try (XMLSlideShow deck = new XMLSlideShow()) {
            deck.createSlide();
            deck.write(Files.newOutputStream(file));
        }
        assertEquals(0, PptxBlockParser.parse(file).size());
    }
}
