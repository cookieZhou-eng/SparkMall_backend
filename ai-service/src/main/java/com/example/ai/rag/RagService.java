package com.example.ai.rag;

import com.example.ai.config.AiProperties;
import com.example.ai.config.ResourceTextLoader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 语料分块检索(RAG 第一阶段: 关键词打分, 零额外依赖)。
 *
 * 阶段设计(对应 docs/ai-agent/07-roadmap.md 阶段2):
 *  1. 启动时把 FAQ 语料按「## 大节 > ### 小节」切成独立知识块;
 *  2. 检索时把用户问题分词(中文 bigram 滑窗 + 英文单词), 统计每个块命中词频归一化打分;
 *  3. 取 topK 且分数 >= similarityThreshold 的块, 拼成「知识库参考片段」注入 system message。
 *
 * 升级路线(阶段4): 把打分函数换成 Embedding 余弦相似度, 分块结构与接口不变。
 */
@Component
public class RagService {

    @Resource
    private AiProperties props;
    @Resource
    private ResourceTextLoader textLoader;

    /** 切好的知识块列表(启动时加载一次, 语料量级 KB 级内存占用可忽略) */
    private final List<Chunk> chunks = new ArrayList<>();

    /** 单个知识块 */
    @Data
    private static class Chunk {
        private String text;   // 块内容(含标题行)
        private Set<String> tokens; // 预分词结果, 避免每次检索重复切分
    }

    /** 服务启动时加载语料并分块 */
    @PostConstruct
    public void init() {
        String corpus = textLoader.load(props.getRag().getCorpusPath());
        synchronized (chunks) {
            chunks.clear();
            chunks.addAll(split(corpus));
        }
    }

    /**
     * 分块: 按「## 」切大节; 节内若有「### 」再按小节切, 块首带 "## 父标题 > ### 小标题"。
     * 文件头的 "> 用途..." 引言(在第一个 ## 之前)不参与检索。
     */
    static List<Chunk> split(String corpus) {
        List<Chunk> result = new ArrayList<>();
        String[] sections = corpus.split("(?m)^## ");
        for (String section : sections) {
            if (section.isBlank() || !section.contains("\n")) {
                continue; // 第一个分隔片段是文件引言, 跳过
            }
            String[] lines = section.split("\n", 2);
            String parentTitle = "## " + lines[0].trim();
            String body = lines[1];
            if (body.contains("\n### ")) {
                String[] subs = body.split("(?m)^### ");
                for (String sub : subs) {
                    if (sub.isBlank() || !sub.contains("\n")) {
                        continue;
                    }
                    String[] subLines = sub.split("\n", 2);
                    String text = parentTitle + " > ### " + subLines[0].trim() + "\n" + subLines[1].trim();
                    result.add(buildChunk(text));
                }
            } else {
                // 大节下没有小节, 整节作为一个块
                String text = parentTitle + "\n" + body.trim();
                result.add(buildChunk(text));
            }
        }
        return result;
    }

    /**
     * 检索: 返回注入 system message 的参考片段; 无命中返回空串(不注入, 由模型自由作答)。
     */
    public String searchContext(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<Scored> hits = search(query);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【知识库参考片段】回答平台规则类问题时依据以下内容:\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append(i + 1).append(". ").append(hits.get(i).chunk.getText()).append("\n");
        }
        return sb.toString();
    }

    /** 检索打分 + 排序 + topK 过滤 */
    public List<Scored> search(String query) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        synchronized (chunks) {
            for (Chunk chunk : chunks) {
                int hits = 0;
                for (String token : queryTokens) {
                    // 统计该词在块中出现的次数(粗略词频, 单个词最多计 3 次防长块刷分)
                    int count = countOccurrences(chunk.getText(), token);
                    hits += Math.min(count, 3);
                }
                double score = (double) hits / queryTokens.size();
                if (score > 0) {
                    scored.add(new Scored(chunk, score));
                }
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::getScore).reversed());
        int topK = props.getRag().getTopK() == null ? 4 : props.getRag().getTopK();
        double threshold = props.getRag().getSimilarityThreshold() == null
                ? 0.2 : props.getRag().getSimilarityThreshold();
        return scored.stream()
                .filter(s -> s.getScore() >= threshold)
                .limit(topK)
                .toList();
    }

    /** 检索结果(块 + 相关度得分) */
    public record Scored(Chunk chunk, double score) {
        public double getScore() {
            return score;
        }
    }

    /**
     * 分词: 中文按 2 字滑窗 bigram(如"退款多久"-> 退款/款多/多久),
     * 英文/数字取连续段(长度>=2), 全部小写去重。
     */
    static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushAscii(ascii, tokens);
                if (i + 1 < text.length() && isCjk(text.charAt(i + 1))) {
                    tokens.add("" + c + text.charAt(i + 1));
                }
            } else if (Character.isLetterOrDigit(c)) {
                ascii.append(Character.toLowerCase(c));
            } else {
                flushAscii(ascii, tokens);
            }
        }
        flushAscii(ascii, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder ascii, Set<String> tokens) {
        if (ascii.length() >= 2) {
            tokens.add(ascii.toString());
        }
        ascii.setLength(0);
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    private static Chunk buildChunk(String text) {
        Chunk chunk = new Chunk();
        chunk.setText(text);
        chunk.setTokens(tokenize(text));
        return chunk;
    }
}
