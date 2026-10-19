package com.thoughtcoding.tools;

import com.thoughtcoding.mcp.MCPToolAdapter;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 ToolRegistry 生成 LangChain4j function calling 所需的 ToolSpecification 列表。
 *
 * 内置工具集中声明参数 schema；MCP 工具从原始 inputSchema 动态转换。
 */
public class ToolSpecificationBuilder {

    public static List<ToolSpecification> build(ToolRegistry registry) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (BaseTool tool : registry.getAllTools()) {
            ToolSpecification spec = buildSpec(tool);
            if (spec != null) {
                specs.add(spec);
            }
        }
        return specs;
    }

    private static ToolSpecification buildSpec(BaseTool tool) {
        if (tool instanceof MCPToolAdapter mcp) {
            return buildMcpSpec(mcp);
        }
        return switch (tool.getName()) {
            case "file_manager" -> fileManagerSpec();
            case "command_executor" -> commandExecutorSpec();
            case "code_executor" -> codeExecutorSpec();
            case "grep_search" -> grepSearchSpec();
            case "codebase_search" -> codebaseSearchSpec();
            default -> null;
        };
    }

    private static ToolSpecification fileManagerSpec() {
        return ToolSpecification.builder()
                .name("file_manager")
                .description("文件管理工具，支持读取、写入、列出、创建、删除文件或目录，以及查询文件信息")
                .addParameter("command", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.enums("read", "write", "list", "create", "delete", "info"))
                .addParameter("path", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("文件或目录路径"))
                .addOptionalParameter("content", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("写入的内容（command=write 时使用）"))
                .build();
    }

    private static ToolSpecification commandExecutorSpec() {
        return ToolSpecification.builder()
                .name("command_executor")
                .description("在系统 shell 中执行命令，返回命令输出")
                .addParameter("command", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("要执行的 shell 命令"))
                .build();
    }

    private static ToolSpecification codeExecutorSpec() {
        return ToolSpecification.builder()
                .name("code_executor")
                .description("执行代码片段，支持 java、javascript、python")
                .addParameter("language", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("代码语言：java / javascript / python"))
                .addParameter("code", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("要执行的代码内容"))
                .build();
    }

    private static ToolSpecification grepSearchSpec() {
        return ToolSpecification.builder()
                .name("grep_search")
                .description("在文件或目录中按正则模式搜索文本")
                .addParameter("pattern", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("要搜索的正则表达式或文本"))
                .addParameter("path", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("要搜索的文件或目录路径"))
                .build();
    }

    private static ToolSpecification codebaseSearchSpec() {
        return ToolSpecification.builder()
                .name("codebase_search")
                .description("使用 RAG 语义检索代码库，查找与自然语言查询相关的代码片段")
                .addParameter("query", JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("自然语言查询，如「SessionService 如何保存会话」"))
                .build();
    }

    /** 从 MCP 工具的原始 inputSchema 动态转换为 ToolParameters。转换失败时降级为空 schema。 */
    private static ToolSpecification buildMcpSpec(MCPToolAdapter adapter) {
        Object schema = adapter.getOriginalTool().getInputSchema();
        ToolSpecification.Builder builder = ToolSpecification.builder()
                .name(adapter.getName())
                .description(adapter.getDescription());

        if (!(schema instanceof Map<?, ?> map)) {
            return builder.build();
        }

        try {
            Map<String, Map<String, Object>> properties = new HashMap<>();
            Object propsObj = map.get("properties");
            if (propsObj instanceof Map<?, ?> rawProps) {
                for (Map.Entry<?, ?> e : rawProps.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> propDef) {
                        Map<String, Object> prop = new HashMap<>();
                        propDef.forEach((k, v) -> prop.put(String.valueOf(k), v));
                        properties.put(String.valueOf(e.getKey()), prop);
                    }
                }
            }

            List<String> required = new ArrayList<>();
            Object requiredObj = map.get("required");
            if (requiredObj instanceof List<?> reqList) {
                for (Object r : reqList) {
                    required.add(String.valueOf(r));
                }
            }

            ToolParameters params = ToolParameters.builder()
                    .type(map.get("type") != null ? String.valueOf(map.get("type")) : "object")
                    .properties(properties)
                    .required(required)
                    .build();
            return builder.parameters(params).build();
        } catch (Exception e) {
            // 转换失败时降级为无参数 schema
            return builder.build();
        }
    }
}
