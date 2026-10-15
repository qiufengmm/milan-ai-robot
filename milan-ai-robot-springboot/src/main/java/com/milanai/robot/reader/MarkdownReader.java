package com.milanai.robot.reader;

import cn.hutool.core.collection.CollUtil;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: 米兰AI
 * @Date: 2025/7/21 16:09
 * @Version: v1.0.0
 * @Description: Markdown 文件读取
 **/
@Component
public class MarkdownReader {

    /**
     * 匹配 Markdown 图片语法的正则：![替代文本](图片地址)
     * 分组 1 是替代文字，分组 2 是图片地址
     */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)");

    /**
     * 读取 Markdown 文件为文档集合
     * @param resource
     * @param metadatas
     * @return
     */
    public List<Document> loadMarkdown(Resource resource, Map<String, Object> metadatas) {
        // 1. 读取 Markdown 原始内容
        String content;
        try (InputStream inputStream = resource.getInputStream()) {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取 Markdown 文件失败", e);
        }

        // 2. 修复图片地址丢失的问题
        // 官方 MarkdownDocumentReader 解析文档时只收集文字，图片语法中的图片地址不会被收集
        // （它的 DocumentVisitor 没有实现 visit(Image)，官方 issue: spring-projects/spring-ai#5144 至今未修）
        // 导致向量化后的文档内容里没有图片链接，AI 也就无法把二维码等图片发给用户了。
        // 修复方式：在图片语法后面追加一段 "行内代码" 形式的图片地址，行内代码（Code 节点）的内容是会被官方 Reader 收集的。
        //
        // 注意：Matcher.replaceAll(Function) 的返回值里 $ 和 \ 会被当成替换语法的特殊字符，
        // 图片地址万一含这两个字符就会被吃掉，所以用 quoteReplacement() 包裹，确保按字面量处理。
        String patchedContent = IMAGE_PATTERN.matcher(content)
                .replaceAll(matchResult -> Matcher.quoteReplacement(
                        matchResult.group() + "`" + matchResult.group(2) + "`"));

        // 3. 用修复后的内容构建新的 Resource（保留原文件名）
        ByteArrayResource patchedResource = new ByteArrayResource(
                patchedContent.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return resource.getFilename();
            }
        };

        // MarkdownDocumentReader 阅读器配置类
        MarkdownDocumentReaderConfig.Builder configBuilder = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true) // 遇到水平线 ---，则创建新文档
                .withIncludeCodeBlock(false) // 排除代码块（代码块生成单独文档）
                .withIncludeBlockquote(false); // 排除块引用（块引用生成单独文档）

        // 添加自定义元数据，如文件名称
        if (CollUtil.isNotEmpty(metadatas)) {
            configBuilder.withAdditionalMetadata(metadatas);
        }

        // 新建 MarkdownDocumentReader 阅读器
        MarkdownDocumentReader reader = new MarkdownDocumentReader(patchedResource, configBuilder.build());

        // 读取并转换为 Document 文档集合
        return reader.get();
    }
}

