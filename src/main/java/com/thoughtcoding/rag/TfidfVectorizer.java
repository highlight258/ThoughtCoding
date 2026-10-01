package com.thoughtcoding.rag;

import java.util.*;

/**
 * 纯 Java TF-IDF 向量化器。
 *
 * 对代码文本做了特殊分词处理：camelCase / snake_case 拆分，
 * 过滤常见关键字，保留有意义的标识符。
 */
public class TfidfVectorizer implements Vectorizer {

    // ---- 拟合后的状态 ----
    private Map<String, Integer> vocabulary = new HashMap<>();  // word → index
    private double[] idf;                                        // index → idf value
    private int dimension;

    // ---- 过滤 ----
    private static final Set<String> STOP_WORDS = Set.of(
        // Java keywords / common noise
        "public", "private", "protected", "static", "final", "abstract",
        "class", "interface", "extends", "implements", "new", "return",
        "void", "int", "long", "double", "float", "boolean", "char",
        "byte", "short", "true", "false", "null", "this", "super",
        "if", "else", "while", "switch", "case", "break",
        "continue", "try", "catch", "finally", "throw", "throws",
        "import", "package", "synchronized", "volatile", "transient",
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "have", "has", "had", "do", "does", "did", "will", "would",
        "can", "could", "should", "may", "might", "to", "of", "in",
        "for", "on", "with", "at", "by", "from", "as", "into",
        "that", "it", "its", "and", "or", "not", "but", "we", "you",
        "he", "she", "they", "here", "there", "all", "each", "every"
    );

    @Override
    public void fit(List<String> documents) {
        // 1. 对所有文档分词
        List<List<String>> tokenizedDocs = new ArrayList<>();
        for (String doc : documents) {
            tokenizedDocs.add(tokenize(doc));
        }

        // 2. 构建词汇表
        Set<String> vocabSet = new LinkedHashSet<>();
        for (List<String> tokens : tokenizedDocs) {
            for (String token : tokens) {
                if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                    vocabSet.add(token);
                }
            }
        }

        vocabulary = new HashMap<>();
        int idx = 0;
        for (String word : vocabSet) {
            vocabulary.put(word, idx++);
        }
        dimension = vocabulary.size();

        // 3. 计算 IDF: log(N / df)
        int N = documents.size();
        int[] docFreq = new int[dimension];

        for (List<String> tokens : tokenizedDocs) {
            Set<String> uniqueTokens = new HashSet<>(tokens);
            for (String token : uniqueTokens) {
                Integer wordIdx = vocabulary.get(token);
                if (wordIdx != null) {
                    docFreq[wordIdx]++;
                }
            }
        }

        idf = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            idf[i] = Math.log((N + 1.0) / (docFreq[i] + 1.0)) + 1.0;  // 平滑 IDF
        }
    }

    @Override
    public double[] encode(String text) {
        if (vocabulary.isEmpty()) {
            throw new IllegalStateException("Vectorizer not fitted. Call fit() first.");
        }

        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return new double[dimension];
        }

        // 计算 TF
        double[] tf = new double[dimension];
        for (String token : tokens) {
            Integer idx = vocabulary.get(token);
            if (idx != null) {
                tf[idx]++;
            }
        }

        // 归一化 TF
        int totalTokens = tokens.size();
        for (int i = 0; i < dimension; i++) {
            tf[i] /= totalTokens;
        }

        // TF-IDF
        double[] vector = new double[dimension];
        double squaredSum = 0.0;
        for (int i = 0; i < dimension; i++) {
            vector[i] = tf[i] * idf[i];
            squaredSum += vector[i] * vector[i];
        }

        // L2 归一化
        double norm = Math.sqrt(squaredSum);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                vector[i] /= norm;
            }
        }

        return vector;
    }

    /**
     * 代码感知分词：camelCase/snake_case 拆分 → 小写 → 过滤。
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // Step 1: 按非字母数字和 _ 分割，保留 tokens
        String[] segments = text.split("[^a-zA-Z0-9]+");

        List<String> result = new ArrayList<>();

        for (String segment : segments) {
            if (segment.isEmpty() || segment.length() > 50) {
                continue;
            }

            // Step 2: 拆分 camelCase: "authenticateUser" → "authenticate", "user"
            // 同时处理连续大写: "HTTPSConnection" → "HTTPS", "Connection"
            List<String> subTokens = splitCamelCase(segment);

            for (String sub : subTokens) {
                String lower = sub.toLowerCase(Locale.ROOT);
                if (lower.isEmpty() || lower.length() > 50) {
                    continue;
                }
                if (lower.matches("\\d+")) {
                    continue;
                }
                if (lower.length() >= 2) {
                    result.add(lower);
                }
            }
        }

        return result;
    }

    /**
     * 拆分 camelCase 和 PascalCase：在大小写边界处断开。
     * "authenticateUser"  → ["authenticate", "User"]
     * "HTTPSConnection"   → ["HTTPS", "Connection"]
     * "getURL"            → ["get", "URL"]
     */
    private List<String> splitCamelCase(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        current.append(s.charAt(0));

        for (int i = 1; i < s.length(); i++) {
            char prev = s.charAt(i - 1);
            char curr = s.charAt(i);

            // 小写→大写 或者 大写→小写且前一个字符是大写且前前一个字符存在且是小写
            boolean boundary;
            if (Character.isUpperCase(curr) && Character.isLowerCase(prev)) {
                boundary = true;  // "eU" in authenticateUser
            } else if (Character.isLowerCase(curr) && Character.isUpperCase(prev)
                       && i >= 2 && Character.isUpperCase(s.charAt(i - 2))) {
                boundary = true;  // "TI" in HTTPSImage → split before I if preceded by upper
            } else {
                boundary = false;
            }

            if (boundary && current.length() > 0) {
                parts.add(current.toString());
                current = new StringBuilder();
            }
            current.append(curr);
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts;
    }

    @Override
    public Map<String, Object> getState() {
        Map<String, Object> state = new HashMap<>();
        state.put("type", "tfidf");
        state.put("vocabulary", vocabulary);
        state.put("dimension", dimension);
        // double[] → List<Double> for Jackson serialization
        List<Double> idfList = new ArrayList<>();
        if (idf != null) {
            for (double v : idf) {
                idfList.add(v);
            }
        }
        state.put("idf", idfList);
        return state;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setState(Map<String, Object> state) {
        this.vocabulary = new HashMap<>();
        Map<String, Object> rawVocab = (Map<String, Object>) state.get("vocabulary");
        if (rawVocab != null) {
            for (Map.Entry<String, Object> entry : rawVocab.entrySet()) {
                // Jackson may deserialize integer values as Integer, Long, etc.
                int idx = ((Number) entry.getValue()).intValue();
                this.vocabulary.put(entry.getKey(), idx);
            }
        }
        List<Number> idfList = (List<Number>) state.get("idf");
        if (idfList != null) {
            this.idf = new double[idfList.size()];
            for (int i = 0; i < idfList.size(); i++) {
                this.idf[i] = idfList.get(i).doubleValue();
            }
        }
        Number dim = (Number) state.getOrDefault("dimension", 0);
        this.dimension = dim.intValue();
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
