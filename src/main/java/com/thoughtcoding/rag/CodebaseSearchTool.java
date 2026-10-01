package com.thoughtcoding.rag;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码库语义搜索工具 — 使用 RAG 从代码库中检索相关代码片段。
 *
 * 注册到 ToolRegistry 后，AI 可通过调用此工具进行语义级代码搜索。
 */
public class CodebaseSearchTool extends BaseTool {
    private static final Logger log = LoggerFactory.getLogger(CodebaseSearchTool.class);

    private final VectorStore store;
    private final Vectorizer vectorizer;
    private final CodebaseIndexer indexer;
    private final String projectRoot;
    private final AppConfig appConfig;

    private static final int DEFAULT_TOP_K = 5;

    public CodebaseSearchTool(AppConfig appConfig, VectorStore store,
                               Vectorizer vectorizer, CodebaseIndexer indexer) {
        super("codebase_search",
              "Search the codebase semantically using RAG. " +
              "Finds code snippets related to a natural language query. " +
              "Use this when the user asks about code structure, implementations, " +
              "or you need to find relevant code.");
        this.appConfig = appConfig;
        this.store = store;
        this.vectorizer = vectorizer;
        this.indexer = indexer;
        this.projectRoot = System.getProperty("user.dir");
    }

    @Override
    public ToolResult execute(String query) {
        long startTime = System.currentTimeMillis();

        try {
            // 确保索引存在
            ensureIndex();

            // 加载索引
            VectorStore.IndexData data = store.load();
            if (data.chunks == null || data.chunks.isEmpty()) {
                return success("Index is empty. No code chunks found.", elapsed(startTime));
            }

            // 恢复向量化器状态
            vectorizer.setState(data.vectorizer);

            // 向量化查询
            double[] queryVec = vectorizer.encode(query);
            List<Double> queryVecList = new ArrayList<>();
            for (double v : queryVec) queryVecList.add(v);

            // 检索
            List<VectorStore.SearchResult> results = store.search(queryVecList, data.chunks, DEFAULT_TOP_K);

            if (results.isEmpty()) {
                return success("No relevant code found for query: " + query, elapsed(startTime));
            }

            // 格式化结果
            StringBuilder output = new StringBuilder();
            output.append(String.format("Found %d relevant code snippets:\n\n", results.size()));

            for (int i = 0; i < results.size(); i++) {
                VectorStore.SearchResult r = results.get(i);
                VectorStore.IndexEntry e = r.entry();
                output.append(String.format("### Result %d (score: %.2f)\n", i + 1, r.score()));
                output.append(String.format("File: %s, Lines %d-%d, Type: %s",
                        e.filePath(), e.startLine(), e.endLine(), e.type()));
                if (e.name() != null && !e.name().isEmpty()) {
                    output.append(String.format(", Name: %s", e.name()));
                }
                output.append("\n```\n");
                output.append(truncateContent(e.content(), 2000));
                output.append("\n```\n\n");
            }

            return success(output.toString(), elapsed(startTime));

        } catch (Exception e) {
            log.error("Codebase search failed", e);
            return error("Codebase search failed: " + e.getMessage(), elapsed(startTime));
        }
    }

    private void ensureIndex() {
        // 索引不存在 → 跳过本次检索，由启动时的异步构建线程负责
        if (!store.indexExists()) {
            log.debug("索引尚未构建，跳过本次检索");
            return;
        }

        // 索引已存在 → 增量更新变更文件
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
