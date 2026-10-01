package com.thoughtcoding.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * 向量存储 — 基于 JSON 文件持久化，支持余弦相似度 Top-K 检索。
 *
 * 文件结构：.thoughtcoding/codebase_index.json
 */
public class VectorStore {

    public record IndexEntry(
            String id,
            String filePath,
            int startLine,
            int endLine,
            String content,
            String type,
            String name,
            List<Double> vector,
            long indexedAt
    ) {}

    public record SearchResult(IndexEntry entry, double score) {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final String indexFilePath;

    public VectorStore() {
        this.indexFilePath = Paths.get(System.getProperty("user.dir"),
                ".thoughtcoding", "codebase_index.json").toString();
    }

    public VectorStore(String projectRoot) {
        this.indexFilePath = Paths.get(projectRoot,
                ".thoughtcoding", "codebase_index.json").toString();
    }

    public String getIndexFilePath() {
        return indexFilePath;
    }

    // ==================== 读写 ====================

    public void save(IndexData data) throws IOException {
        File file = new File(indexFilePath);
        file.getParentFile().mkdirs();
        MAPPER.writeValue(file, data);
    }

    public IndexData load() throws IOException {
        File file = new File(indexFilePath);
        if (!file.exists()) {
            return new IndexData(1, System.currentTimeMillis(), new HashMap<>(), new ArrayList<>());
        }
        return MAPPER.readValue(file, IndexData.class);
    }

    public boolean indexExists() {
        return new File(indexFilePath).exists();
    }

    // ==================== 检索 ====================

    /**
     * 余弦相似度 Top-K 检索
     */
    public List<SearchResult> search(List<Double> queryVector, List<IndexEntry> entries, int k) {
        if (entries.isEmpty() || queryVector == null || queryVector.isEmpty()) {
            return Collections.emptyList();
        }

        // 最小堆维护 Top-K
        PriorityQueue<SearchResult> heap = new PriorityQueue<>(
                Comparator.comparingDouble(SearchResult::score));

        for (IndexEntry entry : entries) {
            if (entry.vector() == null || entry.vector().isEmpty()) continue;
            double score = cosineSimilarity(queryVector, entry.vector());
            if (heap.size() < k) {
                heap.offer(new SearchResult(entry, score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.offer(new SearchResult(entry, score));
            }
        }

        // 降序排列
        List<SearchResult> results = new ArrayList<>(heap);
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    /**
     * 余弦相似度 = (A·B) / (|A| × |B|)
     */
    private double cosineSimilarity(List<Double> a, List<Double> b) {
        int n = Math.max(a.size(), b.size());
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < n; i++) {
            double va = i < a.size() ? a.get(i) : 0.0;
            double vb = i < b.size() ? b.get(i) : 0.0;
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ==================== 增量更新辅助 ====================

    /**
     * 检查哪些文件需要重新索引（基于文件修改时间）
     */
    public List<String> findChangedFiles(String projectRoot, List<IndexEntry> existing) {
        List<String> changed = new ArrayList<>();
        Map<String, Long> existingMtimes = new HashMap<>();
        for (IndexEntry entry : existing) {
            existingMtimes.putIfAbsent(entry.filePath(), entry.indexedAt());
        }

        for (Map.Entry<String, Long> e : existingMtimes.entrySet()) {
            Path filePath = Paths.get(projectRoot, e.getKey());
            try {
                long currentMtime = Files.getLastModifiedTime(filePath).toMillis();
                if (currentMtime > e.getValue()) {
                    changed.add(e.getKey());
                }
            } catch (IOException ex) {
                // 文件已被删除，移除其条目
                changed.add(e.getKey());
            }
        }

        return changed;
    }

    /**
     * 删除指定文件的所有索引条目
     */
    public static List<IndexEntry> removeEntriesForFile(List<IndexEntry> entries, String filePath) {
        return entries.stream()
                .filter(e -> !e.filePath().equals(filePath))
                .collect(java.util.stream.Collectors.toList());
    }

    // ==================== 数据模型 ====================

    /**
     * 索引文件顶层结构
     */
    public static class IndexData {
        public int version;
        public long indexedAt;
        public Map<String, Object> vectorizer;  // Vectorizer.getState()
        public List<IndexEntry> chunks;

        public IndexData() {}

        public IndexData(int version, long indexedAt,
                         Map<String, Object> vectorizerState,
                         List<IndexEntry> chunks) {
            this.version = version;
            this.indexedAt = indexedAt;
            this.vectorizer = vectorizerState;
            this.chunks = chunks;
        }
    }
}
