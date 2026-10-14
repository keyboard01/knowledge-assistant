package io.github.keyboard01.assistant.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.keyboard01.assistant.security.AccessFilter;
import io.github.keyboard01.assistant.security.AccessSubject;

/** 找图检索 SQL 的形状守卫（纯逻辑，不连库）。 */
class ImageAssetRepositoryTest {

    private static final AccessFilter VIEWER =
            AccessFilter.of(AccessSubject.of("demo", "viewer", null), "d");

    private static void assertBalanced(ImageAssetRepository.Query query) {
        long marks = query.sql().chars().filter(c -> c == '?').count();
        assertEquals(marks, query.params().length,
                "占位符与绑定参数必须一一对应，否则只在运行期炸：" + query.sql());
    }

    @Test
    @DisplayName("向量查询：占位符数 == 绑定参数数")
    void vectorQueryBalanced() {
        assertBalanced(ImageAssetRepository.vectorQuery(VIEWER, "policy", "[0.1,0.2]", 5, 0.2));
    }

    @Test
    @DisplayName("关键词查询：占位符数 == 绑定参数数")
    void keywordQueryBalanced() {
        assertBalanced(ImageAssetRepository.keywordQuery(VIEWER, "policy", "结构示意图", 5, 0.12));
    }

    @Test
    @DisplayName("两条查询都下推了文档级 ACL、租户范围与「现行」状态门")
    void queriesCarryAclAndStatus() {
        String vector = ImageAssetRepository.vectorQuery(VIEWER, "policy", "[0]", 5, 0).sql();
        String keyword = ImageAssetRepository.keywordQuery(VIEWER, "policy", "x", 5, 0).sql();
        assertTrue(vector.contains("document_acl"));
        assertTrue(vector.contains("doc_status like"));
        assertTrue(vector.contains("tenant_member"), "找图的向量通道必须带租户谓词：" + vector);
        assertTrue(keyword.contains("document_acl"));
        assertTrue(keyword.contains("doc_status like"));
        assertTrue(keyword.contains("tenant_member"), "找图的关键词通道必须带租户谓词：" + keyword);
    }

    @Test
    @DisplayName("视觉向量查询：占位符数 == 绑定参数数，且下推 ACL、租户与现行状态")
    void visionVectorQueryBalanced() {
        ImageAssetRepository.Query query =
                ImageAssetRepository.visionVectorQuery(VIEWER, "policy", "[0.1,0.2]", 5, 0.2);
        assertBalanced(query);
        assertTrue(query.sql().contains("vision_embedding"));
        assertTrue(query.sql().contains("document_acl"));
        assertTrue(query.sql().contains("doc_status like"));
        assertTrue(query.sql().contains("tenant_member"), "找图的视觉通道必须带租户谓词：" + query.sql());
    }

    @Test
    @DisplayName("幂等指纹形状：内容:文本模型:视觉模型（视觉缺省用 -）")
    void fingerprintShape() {
        assertEquals("abc:txt:-", ImageAssetRepository.fingerprint("abc", "txt", null));
        assertEquals("abc:txt:vis", ImageAssetRepository.fingerprint("abc", "txt", "vis"));
    }

    @Test
    @DisplayName("upsert 是覆盖写（on conflict do update），不是删除重建")
    void upsertIsUpsert() {
        assertTrue(ImageAssetRepository.UPSERT_SQL.contains("on conflict (document_id, asset_index) do update"));
        assertFalse(ImageAssetRepository.UPSERT_SQL.toLowerCase().contains("delete"));
    }

    @Test
    @DisplayName("向量字面量拒绝 NaN / Infinity")
    void literalRejectsNonFinite() {
        assertEquals("[0.5,1.0]", ImageAssetRepository.literal(new float[] {0.5f, 1.0f}));
        assertThrows(IllegalArgumentException.class,
                () -> ImageAssetRepository.literal(new float[] {Float.NaN}));
        assertThrows(IllegalArgumentException.class,
                () -> ImageAssetRepository.literal(new float[] {Float.POSITIVE_INFINITY}));
    }
}
