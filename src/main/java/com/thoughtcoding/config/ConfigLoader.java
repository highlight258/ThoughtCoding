package com.thoughtcoding.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thoughtcoding.mcp.MCPService;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载器，负责从文件系统或类路径加载YAML配置文件
 */
public class ConfigLoader {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConfigLoader.class);

    private MCPService mcpService;
    private MCPConfig mcpConfig;

    // 无参构造函数
    public ConfigLoader() {
    }

    //添加构造函数
    public ConfigLoader(MCPService mcpService, MCPConfig mcpConfig) {
        this.mcpService = mcpService;
        this.mcpConfig = mcpConfig;
    }

    private AppConfig appConfig;

    // 在配置加载后初始化 MCP
    public void initializeMCP() {
        if (mcpConfig != null && mcpConfig.isEnabled()) {
            log.info("初始化配置的MCP服务器...");
            for (MCPServerConfig serverConfig : mcpConfig.getServers()) {
                if (serverConfig.isEnabled()) {
                    mcpService.connectToServer(
                            serverConfig.getName(),
                            serverConfig.getCommand(),
                            serverConfig.getArgs()
                    );
                }
            }
        }
    }

    public AppConfig loadConfig(String configPath) {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

            // 尝试从文件系统加载
            Path filePath = Paths.get(configPath);
            if (Files.exists(filePath)) {
                String content = resolveEnvVars(Files.readString(filePath));
                return mapper.readValue(content, AppConfig.class);
            }

            // 尝试从类路径加载
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(configPath);
            if (inputStream != null) {
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                content = resolveEnvVars(content);
                return mapper.readValue(content, AppConfig.class);
            }

            // 加载默认配置
            return createDefaultConfig();

        } catch (Exception e) {
            System.err.println("Failed to load configuration: " + e.getMessage());
            return createDefaultConfig();
        }
    }

    /** 展开 ${ENV_VAR} 占位符为环境变量值；环境变量未设置时保留原占位符 */
    private String resolveEnvVars(String content) {
        Matcher matcher = Pattern.compile("\\$\\{([^}]+)}").matcher(content);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String envValue = System.getenv(envName);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    envValue != null ? envValue : matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private AppConfig createDefaultConfig() {
        AppConfig config = new AppConfig();
        config.setDefaultModel("deepseek-v3");
        return config;
    }
}