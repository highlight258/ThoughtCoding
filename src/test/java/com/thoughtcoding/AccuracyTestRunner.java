package com.thoughtcoding;

import com.thoughtcoding.rag.*;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

/**
 * 量化指标测试工具。
 * 运行: mvn exec:java -Dexec.mainClass="com.thoughtcoding.AccuracyTestRunner" -Dexec.classpathScope=test
 */
public class AccuracyTestRunner {

    record RagTestCase(String query, String expectedFile, String category) {}

    static List<RagTestCase> RAG_CASES = List.of(
        // A组: 标识符精确查询 — 用户搜类名(最常用场景)
        tc("AgentLoop", "AgentLoop.java", "A-类名"),
        tc("ToolRegistry", "ToolRegistry.java", "A-类名"),
        tc("MCPClient", "MCPClient.java", "A-类名"),
        tc("ContextManager", "ContextManager.java", "A-类名"),
        tc("CommandExecutorTool", "CommandExecutorTool.java", "A-类名"),
        tc("LangChainService", "LangChainService.java", "A-类名"),
        tc("FileManagerTool", "FileManagerTool.java", "A-类名"),
        tc("TfidfVectorizer", "TfidfVectorizer.java", "A-类名"),
        tc("VectorStore", "VectorStore.java", "A-类名"),
        tc("CodeChunker", "CodeChunker.java", "A-类名"),

        // B组: 技术关键词 — 用户搜概念
        tc("JSON-RPC 通信", "MCPClient.java", "B-关键词"),
        tc("ReAct 循环", "AgentLoop.java", "B-关键词"),
        tc("shell 注入检测", "CommandExecutorTool.java", "B-关键词"),
        tc("camelCase 分词", "TfidfVectorizer.java", "B-关键词"),
        tc("余弦相似度搜索", "VectorStore.java", "B-关键词"),

        // C组: 方法/字段名 — 用户搜具体符号
        tc("pendingToolCall", "AgentLoop.java", "C-符号"),
        tc("buildProjectContextMessage", "ContextManager.java", "C-符号"),
        tc("streamingChat", "LangChainService.java", "C-符号"),
        tc("detectShellInjection", "CommandExecutorTool.java", "C-符号"),
        tc("attemptReconnect", "MCPService.java", "C-符号"),

        // D组: 代码模式 — 用户搜 API 或模式
        tc("extends BaseTool", "CodebaseSearchTool.java", "D-模式"),
        tc("implements Vectorizer", "TfidfVectorizer.java", "D-模式"),
        tc("ProcessBuilder", "MCPClient.java", "D-模式")
    );

    static RagTestCase tc(String q, String f, String c) { return new RagTestCase(q, f, c); }

    static void runRagTest(String projectRoot) throws IOException {
        System.out.println("\n  RAG 检索准确率测试\n");

        long t0 = System.currentTimeMillis();
        TfidfVectorizer vectorizer = new TfidfVectorizer();
        VectorStore store = new VectorStore(projectRoot);
        CodeChunker chunker = new CodeChunker();
        CodebaseIndexer indexer = new CodebaseIndexer(chunker, vectorizer, store);
        indexer.buildIndex(projectRoot);
        long buildMs = System.currentTimeMillis() - t0;

        VectorStore.IndexData data = store.load();
        vectorizer.setState(data.vectorizer);

        long fileCount = data.chunks.stream().map(VectorStore.IndexEntry::filePath).distinct().count();
        System.out.printf("  索引: %d 文件 → %d chunks → %d 维向量 → %,dms\n\n",
                fileCount, data.chunks.size(), vectorizer.dimension(), buildMs);

        // 跑测试
        int total = RAG_CASES.size();
        int top1 = 0, top3 = 0, top5 = 0;
        Map<String, int[]> byCategory = new LinkedHashMap<>();

        System.out.println("  查询                                            期望命中         Top-1结果");
        System.out.println("  ──────────────────────────────────────────────────────────────────────────");

        for (var tc : RAG_CASES) {
            double[] qv = vectorizer.encode(tc.query());
            List<Double> qList = new ArrayList<>();
            for (double v : qv) qList.add(v);
            var results = store.search(qList, data.chunks, 5);

            boolean h1 = false, h3 = false, h5 = false;
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).entry().filePath().contains(tc.expectedFile())) {
                    if (i == 0) h1 = true;
                    if (i < 3) h3 = true;
                    if (i < 5) h5 = true;
                    break;
                }
            }
            if (h1) top1++; if (h3) top3++; if (h5) top5++;

            // 按组统计
            byCategory.computeIfAbsent(tc.category(), k -> new int[]{0, 0});
            byCategory.get(tc.category())[0]++;
            if (h1) byCategory.get(tc.category())[1]++;

            String top1File = results.isEmpty() ? "—" : results.get(0).entry().filePath();
            if (top1File.length() > 35) top1File = "…" + top1File.substring(top1File.length() - 34);

            System.out.printf("  %-1s %-42s → %-18s  %-6s %s\n",
                    h1 ? "✅" : h3 ? "🟡" : "❌",
                    truncate(tc.query(), 42),
                    tc.expectedFile(),
                    top1File,
                    tc.category());
        }

        System.out.println("\n  ──────────────────────────────────────────────────────────────────────────");

        // 分组统计
        for (var entry : byCategory.entrySet()) {
            int[] v = entry.getValue();
            System.out.printf("  %s: %d/%d = %.0f%%\n", entry.getKey(), v[1], v[0], 100.0 * v[1] / v[0]);
        }

        System.out.printf("\n  总计 Top-1: %d/%d = %.0f%%\n", top1, total, 100.0 * top1 / total);
        System.out.printf("  总计 Top-3: %d/%d = %.0f%%\n", top3, total, 100.0 * top3 / total);
        System.out.printf("  总计 Top-5: %d/%d = %.0f%%\n", top5, total, 100.0 * top5 / total);
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static void main(String[] args) throws IOException {
        String root = System.getProperty("projectRoot", System.getProperty("user.dir"));
        runRagTest(root);

        System.out.println("\n  ══════════════════════════════════════");
        System.out.println("  简历可用表述:");
        System.out.println("  • RAG 标识符搜索 Top-1 准确率 XX% (类名/方法名/字段名)");
        System.out.println("  • RAG 整体 Top-5 准确率 XX% (4 类查询场景综合)");
        System.out.println("  • XX 文件 → XX chunks, XXms 索引构建");
        System.out.println("  • 纯 Java TF-IDF, 零外部依赖");
        System.out.println("  ══════════════════════════════════════\n");
    }
}
