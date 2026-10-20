package com.thoughtcoding.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

/**
 * 代码库混合检索工具 — TF-IDF 精确命中 + embedding 语义召回，RRF 融合排序。
 *
 * 注册到 ToolRegistry 后，AI 可通过调用此工具进行代码搜索。
 */
public class CodebaseSearchTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(CodebaseSearchTool.class);

    private final VectorStore store;
    private final Vectorizer vectorizer;
    private final CodebaseIndexer indexer;
    private final EmbeddingService embeddingService;
    private final String projectRoot;
    private final AppConfig appConfig;

    private static final int DEFAULT_TOP_K = 5;
    private static final double RRF_K = 60.0;

    public CodebaseSearchTool(AppConfig appConfig, VectorStore store,
                               Vectorizer vectorizer, CodebaseIndexer indexer,
                               EmbeddingService embeddingService) {
        super("codebase_search",
              "Search the codebase for code snippets related to a natural language query. " +
              "Uses hybrid retrieval: TF-IDF exact match + embedding semantic recall.");
        this.appConfig = appConfig;
        this.store = store;
        this.vectorizer = vectorizer;
        this.indexer = indexer;
        this.embeddingService = embeddingService;
        this.projectRoot = System.getProperty("user.dir");
    }

    @Override
    public ToolResult execute(String query) {
        long startTime = System.currentTimeMillis();

        // 支持 JSON 格式输入（function calling）
        if (query != null && query.trim().startsWith("{")) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> params = mapper.readValue(query, Map.class);
                Object queryValue = params.get("query");
                if (queryValue != null) {
                    query = queryValue.toString();
                }
            } catch (Exception e) {
                log.debug("JSON 查询参数解析失败，按原始字符串处理: {}", e.getMessage());
            }
        }

        try {
            ensureIndex();

            VectorStore.IndexData data = store.load();
            if (data.chunks == null || data.chunks.isEmpty()) {
                return success("Index is empty. No code chunks found.", elapsed(startTime));
            }

            vectorizer.setState(data.vectorizer);

            // 1. TF-IDF 路（精确标识符命中）
            List<VectorStore.SearchResult> tfidfResults = tfidfSearch(query, data);

            // 2. embedding 路（语义召回）
            List<VectorStore.SearchResult> embedResults = embeddingSearch(query, data);

            // 3. RRF 融合
            List<VectorStore.SearchResult> results = rrfFuse(tfidfResults, embedResults, DEFAULT_TOP_K);

            if (results.isEmpty()) {
                return success("No relevant code found for query: " + query, elapsed(startTime));
            }

            return success(formatResults(results), elapsed(startTime));

        } catch (Exception e) {
            log.error("Codebase search failed", e);
            return error("Codebase search failed: " + e.getMessage(), elapsed(startTime));
        }
    }

    /** TF-IDF 精确检索 */
    private List<VectorStore.SearchResult> tfidfSearch(String query, VectorStore.IndexData data) {
        double[] queryVec = vectorizer.encode(query);
        List<Double> queryVecList = new ArrayList<>();
        for (double v : queryVec) queryVecList.add(v);
        return store.search(queryVecList, data.chunks, DEFAULT_TOP_K);
    }

    /** embedding 语义检索；embedding 未启用或 chunk 无 embedding 时返回空 */
    private List<VectorStore.SearchResult> embeddingSearch(String query, VectorStore.IndexData data) {
        if (!embeddingService.isEnabled()) {
            return List.of();
        }
        float[] queryEmb = embeddingService.embed(query);
        if (queryEmb == null) {
            return List.of();
        }

        PriorityQueue<VectorStore.SearchResult> heap = new PriorityQueue<>(
                Comparator.comparingDouble(VectorStore.SearchResult::score));
        for (VectorStore.IndexEntry entry : data.chunks) {
            if (entry.embedding() == null || entry.embedding().isEmpty()) {
                continue;
            }
            double score = cosineSimilarity(queryEmb, entry.embedding());
            if (heap.size() < DEFAULT_TOP_K) {
                heap.offer(new VectorStore.SearchResult(entry, score));
            } else if (score > heap.peek().score()) {
                heap.poll();
                heap.offer(new VectorStore.SearchResult(entry, score));
            }
        }
        List<VectorStore.SearchResult> results = new ArrayList<>(heap);
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    /** RRF 融合：score = Σ 1/(k + rank)，两路结果按 id 去重合并 */
    private List<VectorStore.SearchResult> rrfFuse(
            List<VectorStore.SearchResult> a,
            List<VectorStore.SearchResult> b,
            int k) {
        Map<String, Double> fusedScores = new HashMap<>();
        Map<String, VectorStore.IndexEntry> idToEntry = new HashMap<>();

        addRrfScores(a, fusedScores, idToEntry);
        addRrfScores(b, fusedScores, idToEntry);

        return fusedScores.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(k)
                .map(e -> new VectorStore.SearchResult(idToEntry.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }

    private void addRrfScores(List<VectorStore.SearchResult> results,
                              Map<String, Double> fusedScores,
                              Map<String, VectorStore.IndexEntry> idToEntry) {
        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult r = results.get(i);
            String id = r.entry().id();
            fusedScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            idToEntry.putIfAbsent(id, r.entry());
        }
    }

    /** 余弦相似度（query float[] vs entry List<Float>） */
    private double cosineSimilarity(float[] a, List<Float> b) {
        if (a == null || b == null || a.length == 0 || b.isEmpty()) {
            return 0.0;
        }
        int n = Math.max(a.length, b.size());
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < n; i++) {
            double va = i < a.length ? a[i] : 0.0;
            double vb = i < b.size() ? b.get(i) : 0.0;
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String formatResults(List<VectorStore.SearchResult> results) {
        StringBuilder output = new StringBuilder();
        output.append(String.format("Found %d relevant code snippets:\n\n", results.size()));

        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult r = results.get(i);
            VectorStore.IndexEntry e = r.entry();
            output.append(String.format("### Result %d (score: %.3f)\n", i + 1, r.score()));
            output.append(String.format("File: %s, Lines %d-%d, Type: %s",
                    e.filePath(), e.startLine(), e.endLine(), e.type()));
            if (e.name() != null && !e.name().isEmpty()) {
                output.append(String.format(", Name: %s", e.name()));
            }
            output.append("\n```\n");
            output.append(truncateContent(e.content(), 2000));
            output.append("\n```\n\n");
        }

        return output.toString();
    }

    private void ensureIndex() {
        if (!store.indexExists()) {
            log.debug("索引尚未构建，跳过本次检索");
            return;
        }
        try {
            indexer.incrementalUpdate(projectRoot);
        } catch (Exception e) {
            log.warn("增量索引更新失败，使用现有索引继续检索: {}", e.getMessage());
        }
    }

    private String truncateContent(String content, int maxLen) {
        if (content.length() <= maxLen) return content;
        return content.substring(0, maxLen) + "\n... [truncated]";
    }

    private long elapsed(long startTime) {
        return System.currentTimeMillis() - startTime;
    }

    @Override
    public String getCategory() {
        return "rag";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
