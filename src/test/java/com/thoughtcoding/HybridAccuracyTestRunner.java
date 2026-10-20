package com.thoughtcoding;

import com.thoughtcoding.rag.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索 vs 纯 TF-IDF 准确率对比测试。
 * 运行前需 export DASHSCOPE_API_KEY。
 */
public class HybridAccuracyTestRunner {
    private static final int TOP_K = 5;
    private static final double RRF_K = 60.0;

    public static void main(String[] args) throws Exception {
        String key = System.getenv("DASHSCOPE_API_KEY");
        String root = System.getProperty("user.dir");
        System.out.println("key: " + (key != null && !key.isBlank() ? "已设置" : "未设置"));

        TfidfVectorizer vectorizer = new TfidfVectorizer();
        VectorStore store = new VectorStore(root);
        CodeChunker chunker = new CodeChunker();
        EmbeddingService es = new EmbeddingService(key);
        CodebaseIndexer indexer = new CodebaseIndexer(chunker, vectorizer, store, es);

        long t0 = System.currentTimeMillis();
        indexer.buildIndex(root);
        System.out.println("索引构建: " + (System.currentTimeMillis() - t0) + "ms");

        VectorStore.IndexData data = store.load();
        vectorizer.setState(data.vectorizer);
        System.out.println("chunks: " + data.chunks.size() + ", 含 embedding: " +
                data.chunks.stream().filter(e -> e.embedding() != null).count());

        int total = AccuracyTestRunner.RAG_CASES.size();
        int tf1 = 0, tf3 = 0, tf5 = 0;
        int hy1 = 0, hy3 = 0, hy5 = 0;
        Map<String, int[]> byCat = new LinkedHashMap<>();

        System.out.println("\n  查询 | TFIDF-Top1 | 混合-Top1 | 类别");
        System.out.println("  " + "─".repeat(70));
        for (var tc : AccuracyTestRunner.RAG_CASES) {
            List<VectorStore.SearchResult> tfidf = tfidfSearch(vectorizer, store, data, tc.query());
            List<VectorStore.SearchResult> hybrid = hybridSearch(vectorizer, store, es, data, tc.query());

            boolean tf1h = hitTopK(tfidf, tc.expectedFile(), 1);
            boolean tf3h = hitTopK(tfidf, tc.expectedFile(), 3);
            boolean tf5h = hitTopK(tfidf, tc.expectedFile(), 5);
            boolean hy1h = hitTopK(hybrid, tc.expectedFile(), 1);
            boolean hy3h = hitTopK(hybrid, tc.expectedFile(), 3);
            boolean hy5h = hitTopK(hybrid, tc.expectedFile(), 5);

            if (tf1h) tf1++; if (tf3h) tf3++; if (tf5h) tf5++;
            if (hy1h) hy1++; if (hy3h) hy3++; if (hy5h) hy5++;

            byCat.computeIfAbsent(tc.category(), k -> new int[]{0, 0, 0});
            int[] c = byCat.get(tc.category());
            c[0]++; if (tf1h) c[1]++; if (hy1h) c[2]++;

            System.out.printf("  %-30s | %s | %s | %s\n",
                    truncate(tc.query(), 30),
                    tf1h ? "  ✅" : "  ❌",
                    hy1h ? "  ✅" : "  ❌",
                    tc.category());
        }

        System.out.println("\n  分组 Top-1（TFIDF vs 混合）:");
        for (var e : byCat.entrySet()) {
            int[] c = e.getValue();
            System.out.printf("    %s: TFIDF %d/%d  vs  混合 %d/%d\n",
                    e.getKey(), c[1], c[0], c[2], c[0]);
        }

        System.out.println("\n  ───────────── 汇总 ─────────────");
        System.out.printf("  纯 TF-IDF   Top-1/Top-3/Top-5: %d / %d / %d  (%.0f%% / %.0f%% / %.0f%%)\n",
                tf1, tf3, tf5, 100.0 * tf1 / total, 100.0 * tf3 / total, 100.0 * tf5 / total);
        System.out.printf("  混合检索     Top-1/Top-3/Top-5: %d / %d / %d  (%.0f%% / %.0f%% / %.0f%%)\n",
                hy1, hy3, hy5, 100.0 * hy1 / total, 100.0 * hy3 / total, 100.0 * hy5 / total);
    }

    private static List<VectorStore.SearchResult> tfidfSearch(TfidfVectorizer vectorizer, VectorStore store, VectorStore.IndexData data, String query) {
        double[] qv = vectorizer.encode(query);
        List<Double> qList = new ArrayList<>();
        for (double v : qv) qList.add(v);
        return store.search(qList, data.chunks, TOP_K);
    }

    private static List<VectorStore.SearchResult> hybridSearch(TfidfVectorizer vectorizer, VectorStore store, EmbeddingService es, VectorStore.IndexData data, String query) {
        List<VectorStore.SearchResult> tfidf = tfidfSearch(vectorizer, store, data, query);
        List<VectorStore.SearchResult> embed = embeddingSearch(es, data, query);
        List<VectorStore.SearchResult> exact = identifierMatch(query, data.chunks);
        return rrfFuse(List.of(tfidf, embed, exact), TOP_K);
    }

    private static List<VectorStore.SearchResult> embeddingSearch(EmbeddingService es, VectorStore.IndexData data, String query) {
        if (!es.isEnabled()) return List.of();
        float[] qEmb = es.embed(query);
        if (qEmb == null) return List.of();
        PriorityQueue<VectorStore.SearchResult> heap = new PriorityQueue<>(Comparator.comparingDouble(VectorStore.SearchResult::score));
        for (VectorStore.IndexEntry entry : data.chunks) {
            if (entry.embedding() == null || entry.embedding().isEmpty()) continue;
            double score = cosineSimilarity(qEmb, entry.embedding());
            if (heap.size() < TOP_K) {
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

    private static List<VectorStore.SearchResult> rrfFuse(List<List<VectorStore.SearchResult>> paths, int k) {
        Map<String, Double> fused = new HashMap<>();
        Map<String, VectorStore.IndexEntry> idToEntry = new HashMap<>();
        for (List<VectorStore.SearchResult> path : paths) {
            addRrf(path, fused, idToEntry);
        }
        return fused.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(k)
                .map(e -> new VectorStore.SearchResult(idToEntry.get(e.getKey()), e.getValue()))
                .collect(Collectors.toList());
    }

    private static List<VectorStore.SearchResult> identifierMatch(String query, List<VectorStore.IndexEntry> entries) {
        if (query == null || !query.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return List.of();
        String lower = query.toLowerCase();
        Map<String, VectorStore.IndexEntry> matched = new LinkedHashMap<>();
        for (VectorStore.IndexEntry entry : entries) {
            boolean nameHit = entry.name() != null && entry.name().toLowerCase().equals(lower);
            String filePath = entry.filePath();
            int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
            String base = slash >= 0 ? filePath.substring(slash + 1) : filePath;
            int dot = base.lastIndexOf('.');
            String stem = dot >= 0 ? base.substring(0, dot) : base;
            boolean fileHit = stem.toLowerCase().equals(lower);
            if (nameHit || fileHit) {
                matched.putIfAbsent(entry.id(), entry);
            }
        }
        return matched.values().stream()
                .map(e -> new VectorStore.SearchResult(e, 1.0))
                .collect(Collectors.toList());
    }

    private static void addRrf(List<VectorStore.SearchResult> results, Map<String, Double> fused, Map<String, VectorStore.IndexEntry> idToEntry) {
        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult r = results.get(i);
            String id = r.entry().id();
            fused.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            idToEntry.putIfAbsent(id, r.entry());
        }
    }

    private static double cosineSimilarity(float[] a, List<Float> b) {
        if (a == null || b == null || a.length == 0 || b.isEmpty()) return 0.0;
        int n = Math.max(a.length, b.size());
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < n; i++) {
            double va = i < a.length ? a[i] : 0.0;
            double vb = i < b.size() ? b.get(i) : 0.0;
            dot += va * vb; normA += va * va; normB += vb * vb;
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static boolean hitTopK(List<VectorStore.SearchResult> results, String expectedFile, int k) {
        for (int i = 0; i < results.size() && i < k; i++) {
            if (results.get(i).entry().filePath().contains(expectedFile)) return true;
        }
        return false;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
