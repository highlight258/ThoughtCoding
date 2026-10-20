package com.thoughtcoding.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Embedding 服务：调用 DashScope 的 OpenAI 兼容 embedding API，把文本转成向量。
 *
 * 未配置 API key 时自动禁用（模型为 null），混合检索退化为纯 TF-IDF。
 */
public class EmbeddingService {
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String MODEL_NAME = "text-embedding-v3";
    // DashScope 批量限制：每批最多 10 条
    private static final int BATCH_SIZE = 10;

    private final EmbeddingModel model;

    public EmbeddingService(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            this.model = null;
        } else {
            this.model = OpenAiEmbeddingModel.builder()
                    .baseUrl(BASE_URL)
                    .apiKey(apiKey)
                    .modelName(MODEL_NAME)
                    .build();
        }
    }

    /** 是否启用（配置了 API key 才启用） */
    public boolean isEnabled() {
        return model != null;
    }

    /** 单条文本转向量；禁用时返回 null */
    public float[] embed(String text) {
        if (model == null) {
            return null;
        }
        Response<Embedding> response = model.embed(text);
        return response.content().vector();
    }

    /** 批量文本转向量；禁用时返回空列表。按 DashScope 每批 10 条限制分批调用。 */
    public List<float[]> embedAll(List<String> texts) {
        if (model == null) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, texts.size());
            List<TextSegment> segments = texts.subList(i, end).stream()
                    .map(TextSegment::from)
                    .collect(Collectors.toList());
            Response<List<Embedding>> response = model.embedAll(segments);
            for (Embedding emb : response.content()) {
                result.add(emb.vector());
            }
        }
        return result;
    }
}
