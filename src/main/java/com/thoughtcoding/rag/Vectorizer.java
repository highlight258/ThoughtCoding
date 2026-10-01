package com.thoughtcoding.rag;

import java.util.List;
import java.util.Map;

/**
 * 向量化策略接口 — 将文本转为稠密或稀疏向量。
 * 当前默认实现为 TF-IDF，后续可替换为 embedding 模型（ONNX / MCP sidecar）。
 */
public interface Vectorizer {

    /** 在语料库上拟合，构建词汇表及统计信息 */
    void fit(List<String> documents);

    /** 将单个文本编码为向量（依赖 fit 后的状态） */
    double[] encode(String text);

    /** 批量编码 */
    default List<double[]> encodeAll(List<String> texts) {
        List<double[]> result = new java.util.ArrayList<>();
        for (String text : texts) {
            result.add(encode(text));
        }
        return result;
    }

    /** 导出拟合状态，供持久化 */
    Map<String, Object> getState();

    /** 从持久化状态恢复 */
    void setState(Map<String, Object> state);

    /** 返回向量维度 */
    int dimension();
}
