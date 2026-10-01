package com.thoughtcoding.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * 索引构建协调器 — 扫描项目目录、分块、向量化、持久化。
 */
public class CodebaseIndexer {
    private static final Logger log = LoggerFactory.getLogger(CodebaseIndexer.class);

    private final CodeChunker chunker;
    private final Vectorizer vectorizer;
    private final VectorStore store;

    public CodebaseIndexer(CodeChunker chunker, Vectorizer vectorizer, VectorStore store) {
        this.chunker = chunker;
        this.vectorizer = vectorizer;
        this.store = store;
    }

    /**
     * 全量构建索引
     */
    public void buildIndex(String projectRoot) throws IOException {
        log.info("开始全量构建代码库索引: {}", projectRoot);

        // 1. 扫描并分块
        List<CodeChunker.Chunk> allChunks = scanAndChunk(projectRoot);
        log.info("扫描完成，共 {} 个代码块", allChunks.size());

        if (allChunks.isEmpty()) {
            log.warn("未发现可索引的代码文件");
            return;
        }

        // 2. 提取所有文本
        List<String> texts = allChunks.stream()
                .map(CodeChunker.Chunk::content)
                .toList();

        // 3. 向量化
        vectorizer.fit(texts);
        List<double[]> vectors = vectorizer.encodeAll(texts);
        log.info("向量化完成，维度: {}", vectorizer.dimension());

        // 4. 构建索引条目
        List<VectorStore.IndexEntry> entries = new ArrayList<>();
        long now = System.currentTimeMillis();
        String projectRootAbs = Paths.get(projectRoot).toAbsolutePath().toString();

        for (int i = 0; i < allChunks.size(); i++) {
            CodeChunker.Chunk chunk = allChunks.get(i);
            double[] vec = vectors.get(i);

            // 转为相对路径
            String relativePath = makeRelative(projectRootAbs, chunk.filePath());
            List<Double> vecList = new ArrayList<>();
            for (double v : vec) {
                vecList.add(v);
            }

            entries.add(new VectorStore.IndexEntry(
                    UUID.randomUUID().toString().substring(0, 8),
                    relativePath,
                    chunk.startLine(),
                    chunk.endLine(),
                    chunk.content(),
                    chunk.type(),
                    chunk.name(),
                    vecList,
                    now
            ));
        }

        // 5. 持久化
        VectorStore.IndexData data = new VectorStore.IndexData(
                1, now, vectorizer.getState(), entries);
        store.save(data);
        log.info("索引已保存: {} ({} entries)", store.getIndexFilePath(), entries.size());
    }

    /**
     * 增量更新：仅重新索引修改过的文件
     */
    public void incrementalUpdate(String projectRoot) throws IOException {
        if (!store.indexExists()) {
            buildIndex(projectRoot);
            return;
        }

        VectorStore.IndexData existing = store.load();
        List<VectorStore.IndexEntry> entries = new ArrayList<>(existing.chunks);

        // 恢复向量化器状态
        vectorizer.setState(existing.vectorizer);

        List<String> changed = store.findChangedFiles(projectRoot, entries);
        if (changed.isEmpty()) {
            log.info("索引无需更新");
            return;
        }

        log.info("检测到 {} 个文件变更，增量更新索引", changed.size());

        // 移除变更文件的旧条目
        for (String file : changed) {
            entries = VectorStore.removeEntriesForFile(entries, file);
        }

        // 重新索引变更文件
        long now = System.currentTimeMillis();
        String projectRootAbs = Paths.get(projectRoot).toAbsolutePath().toString();

        int added = 0;
        for (String file : changed) {
            Path filePath = Paths.get(projectRoot, file);
            if (!Files.exists(filePath)) {
                continue;  // 文件已删除，直接跳过
            }
            try {
                String content = Files.readString(filePath);
                List<CodeChunker.Chunk> chunks = chunker.chunkFile(filePath.toString(), content);

                for (CodeChunker.Chunk chunk : chunks) {
                    double[] vec = vectorizer.encode(chunk.content());
                    List<Double> vecList = new ArrayList<>();
                    for (double v : vec) vecList.add(v);

                    entries.add(new VectorStore.IndexEntry(
                            UUID.randomUUID().toString().substring(0, 8),
                            makeRelative(projectRootAbs, chunk.filePath()),
                            chunk.startLine(), chunk.endLine(),
                            chunk.content(), chunk.type(), chunk.name(),
                            vecList, now
                    ));
                    added++;
                }
            } catch (IOException e) {
                log.warn("读取文件失败: {}", file, e);
            }
        }

        VectorStore.IndexData data = new VectorStore.IndexData(
                1, now, vectorizer.getState(), entries);
        store.save(data);
        log.info("增量更新完成: +{} entries, 总计 {} entries", added, entries.size());
    }

    /**
     * 异步全量构建索引，避免阻塞主线程。
     */
    public CompletableFuture<Void> asyncBuildIndex(String projectRoot) {
        return CompletableFuture.runAsync(() -> {
            try {
                long start = System.currentTimeMillis();
                buildIndex(projectRoot);
                log.info("异步全量索引构建完成 ({}ms)", System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("异步构建索引失败", e);
            }
        });
    }

    /**
     * 异步增量更新索引，避免阻塞主线程。
     */
    public CompletableFuture<Void> asyncIncrementalUpdate(String projectRoot) {
        return CompletableFuture.runAsync(() -> {
            try {
                long start = System.currentTimeMillis();
                incrementalUpdate(projectRoot);
                log.info("异步增量索引更新完成 ({}ms)", System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("异步增量更新索引失败", e);
            }
        });
    }

    /**
     * 扫描项目目录，对所有代码文件分块。
     * 逐个遍历顶层子目录，遇系统保护目录自动跳过。
     */
    private List<CodeChunker.Chunk> scanAndChunk(String projectRoot) throws IOException {
        List<CodeChunker.Chunk> allChunks = new ArrayList<>();
        Path root = Paths.get(projectRoot);

        try (Stream<Path> children = Files.list(root)) {
            children
                .filter(Files::isDirectory)
                .filter(dir -> !chunker.shouldExcludeDir(dir.getFileName().toString()))
                .forEach(dir -> {
                    try (Stream<Path> stream = Files.walk(dir)) {
                        stream
                            .filter(Files::isRegularFile)
                            .filter(p -> !isExcluded(p, root))
                            .filter(p -> chunker.isCodeFile(p.getFileName().toString()))
                            .forEach(filePath -> {
                                try {
                                    String content = Files.readString(filePath);
                                    List<CodeChunker.Chunk> chunks = chunker.chunkFile(
                                            filePath.toString(), content);
                                    allChunks.addAll(chunks);
                                } catch (IOException e) {
                                    log.debug("跳过文件: {}", filePath, e);
                                }
                            });
                    } catch (IOException e) {
                        log.debug("跳过目录: {}", dir, e);
                    }
                });
        } catch (IOException e) {
            // 如果连 root 都列不了（不太可能但兜底），返回空
            log.warn("无法扫描项目目录: {}", projectRoot, e);
        }

        // 也扫描根目录下的代码文件（如 pom.xml, config.yaml 等）
        try (Stream<Path> rootFiles = Files.list(root)) {
            rootFiles
                .filter(Files::isRegularFile)
                .filter(p -> chunker.isCodeFile(p.getFileName().toString()))
                .forEach(filePath -> {
                    try {
                        String content = Files.readString(filePath);
                        List<CodeChunker.Chunk> chunks = chunker.chunkFile(
                                filePath.toString(), content);
                        allChunks.addAll(chunks);
                    } catch (IOException e) {
                        log.debug("跳过文件: {}", filePath, e);
                    }
                });
        } catch (IOException e) {
            log.warn("无法扫描根目录文件: {}", projectRoot, e);
        }

        return allChunks;
    }

    private boolean isExcluded(Path filePath, Path root) {
        // 检查路径中是否包含需排除的目录
        Path relative = root.relativize(filePath);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (chunker.shouldExcludeDir(relative.getName(i).toString())) {
                return true;
            }
        }
        // 过大文件跳过 (> 1MB)
        try {
            if (Files.size(filePath) > 1_000_000) {
                return true;
            }
        } catch (IOException e) {
            return true;
        }
        return false;
    }

    private String makeRelative(String root, String absolutePath) {
        String absRoot = root.replace('\\', '/');
        String absPath = absolutePath.replace('\\', '/');
        if (absPath.startsWith(absRoot)) {
            String rel = absPath.substring(absRoot.length());
            if (rel.startsWith("/") || rel.startsWith("\\")) {
                rel = rel.substring(1);
            }
            return rel;
        }
        return absPath;
    }
}
