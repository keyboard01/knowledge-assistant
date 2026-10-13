package io.github.keyboard01.assistant.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.github.keyboard01.assistant.settings.SettingService;

/**
 * 图片资产落盘（内容寻址）：`{assetRoot}/{sha[0:2]}/{sha[2:4]}/{sha}.{ext}`，缩略图同目录 `{sha}.thumb.jpg`。
 *
 * <p>内容寻址的好处：跨文档、跨重传天然去重（同一张图只存一份、只描述一次）；重传时路径不变。</p>
 *
 * <p>目录解析**不依赖 IngestService**（那会形成循环依赖），自己读配置。并强制约束
 * {@code assetRoot} 不在 {@code sourceRoot} 之内——否则目录扫描会把资产当成未登记文件刷任务。</p>
 */
@Component
public class AssetStore {

    private static final Set<String> READABLE_EXT = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private final SettingService settingService;

    public AssetStore(SettingService settingService) {
        this.settingService = settingService;
    }

    public Path root() {
        Path root = resolve("assetRoot", "knowledge-assets");
        Path source = resolve("sourceRoot", "knowledge");
        if (root.startsWith(source)) {
            throw new IllegalStateException("图片资产目录不能位于文档根目录之内（会被目录扫描误处理）：" + root);
        }
        return root;
    }

    public Path assetPath(String contentHash, String ext) {
        String safeExt = READABLE_EXT.contains(ext == null ? "" : ext.toLowerCase(Locale.ROOT))
                ? ext.toLowerCase(Locale.ROOT) : "png";
        return shard(contentHash).resolve(contentHash + "." + safeExt);
    }

    public Path thumbPath(String contentHash) {
        return shard(contentHash).resolve(contentHash + ".thumb.jpg");
    }

    public void writeIfAbsent(Path path, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    /** 读资产字节。带路径越界校验（对齐文档查看的 `resolveSource`）。 */
    public byte[] read(Path path) throws IOException {
        Path root = root();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("资产路径越界");
        }
        return Files.readAllBytes(normalized);
    }

    private Path shard(String contentHash) {
        return root().resolve(contentHash.substring(0, 2)).resolve(contentHash.substring(2, 4));
    }

    private Path resolve(String key, String fallback) {
        Map<String, String> cfg = settingService.effective(SettingService.GROUP_INGEST);
        String configured = cfg.get(key);
        String value = configured == null || configured.isBlank() ? fallback : configured.trim();
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
        }
        return path.normalize().toAbsolutePath();
    }
}
