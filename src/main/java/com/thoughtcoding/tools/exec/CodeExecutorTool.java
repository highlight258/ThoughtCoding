package com.thoughtcoding.tools.exec;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tools.BaseTool;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Map;

/**
 * 代码执行工具，支持多种编程语言的代码片段执行
 */
public class CodeExecutorTool extends BaseTool {

    private final AppConfig appConfig;
    public CodeExecutorTool(AppConfig appConfig) {
        super("code_executor", "Execute code snippets in various programming languages");
        this.appConfig = appConfig;
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();

        try {
            String language;
            String code;

            // 支持 JSON 格式输入（function calling）
            if (input.trim().startsWith("{")) {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> params = mapper.readValue(input, Map.class);

                Object languageValue = params.get("language");
                Object codeValue = params.get("code");
                if (languageValue == null || codeValue == null) {
                    return error("JSON格式错误: 需要 'language' 和 'code' 字段",
                            System.currentTimeMillis() - startTime);
                }

                language = languageValue.toString().toLowerCase();
                code = codeValue.toString();
            } else {
                // 空格分隔格式（向后兼容）
                String[] parts = input.split(" ", 2);
                if (parts.length < 2) {
                    return error("Invalid format. Use: <language> <code>", System.currentTimeMillis() - startTime);
                }

                language = parts[0].toLowerCase();
                code = parts[1];
            }

            switch (language) {
                case "java":
                    return executeJavaCode(code, startTime);
                case "javascript":
                case "js":
                    return executeJavaScript(code, startTime);
                case "python":
                case "py":
                    return executePython(code, startTime);
                default:
                    return error("Unsupported language: " + language + ". Supported: java, javascript, python",
                            System.currentTimeMillis() - startTime);
            }

        } catch (Exception e) {
            return error("Code execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult executeJavaCode(String code, long startTime) {
        try {
            // 通过编译和执行临时 Java 文件的方式执行 Java 代码
            // 创建临时文件
            File tempDir = Files.createTempDirectory("thoughtcoding_java_").toFile();

            // 提取类名（如果代码中包含类定义）
            String className = extractClassName(code);
            if (className == null) {
                // 如果没有类定义，包装成一个临时类
                className = "TempJavaCode";
                code = "public class " + className + " {\n" +
                       "    public static void main(String[] args) {\n" +
                       "        " + code + "\n" +
                       "    }\n" +
                       "}";
            }

            File javaFile = new File(tempDir, className + ".java");
            Files.writeString(javaFile.toPath(), code);

            try {
                // 编译 Java 代码
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    return error("Java compiler not available. Please ensure JDK (not JRE) is installed.",
                                System.currentTimeMillis() - startTime);
                }

                int compilationResult = compiler.run(null, null, null, javaFile.getAbsolutePath());
                if (compilationResult != 0) {
                    return error("Java compilation failed", System.currentTimeMillis() - startTime);
                }

                // 执行编译后的类
                ToolResult result = runProcess(
                    new ProcessBuilder("java", "-cp", tempDir.getAbsolutePath(), className),
                    startTime
                );
                if (result.isSuccess() && (result.getOutput() == null || result.getOutput().isEmpty())) {
                    return success("Java code executed successfully (no output)", result.getExecutionTime());
                }
                if (!result.isSuccess()) {
                    return error("Java execution failed:\n" + result.getError(), result.getExecutionTime());
                }
                return result;

            } finally {
                // 清理临时文件
                deleteDirectory(tempDir);
            }

        } catch (Exception e) {
            return error("Java execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 从 Java 代码中提取类名
     */
    private String extractClassName(String code) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "public\\s+class\\s+(\\w+)"
        );
        java.util.regex.Matcher matcher = pattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    /**
     * 执行进程并分离收集 stdout/stderr，按退出码构造结构化结果。
     */
    private ToolResult runProcess(ProcessBuilder pb, long startTime) {
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            try {
                StringBuilder stdout = new StringBuilder();
                StringBuilder stderr = new StringBuilder();
                Thread outThread = readStreamAsync(process.getInputStream(), stdout);
                Thread errThread = readStreamAsync(process.getErrorStream(), stderr);

                int exitCode = process.waitFor();
                outThread.join();
                errThread.join();

                return ToolResult.command(stdout.toString().trim(), stderr.toString().trim(),
                        exitCode, System.currentTimeMillis() - startTime);
            } finally {
                if (process.isAlive()) {
                    process.destroy();
                }
            }
        } catch (Exception e) {
            return error("Process execution failed: " + e.getMessage(),
                    System.currentTimeMillis() - startTime);
        }
    }

    /** 异步读取流到 StringBuilder，返回读线程（用于并发读取 stdout/stderr 避免死锁） */
    private Thread readStreamAsync(InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (Exception e) {
                // 忽略流读取错误
            }
        });
        t.start();
        return t;
    }

    private ToolResult executeJavaScript(String code, long startTime) {
        try {
            // 通过 Node.js 执行 JavaScript 代码
            // 注意: Nashorn JavaScript 引擎在 JDK 15+ 已被移除
            File tempFile = File.createTempFile("thoughtcoding_js_", ".js");
            try {
                Files.writeString(tempFile.toPath(), code);

                // 尝试使用 node 命令
                ToolResult result = runProcess(new ProcessBuilder("node", tempFile.getAbsolutePath()), startTime);
                if (!result.isSuccess()) {
                    String err = result.getError();
                    if (err != null && (err.contains("Cannot run program") || err.contains("command not found") || err.contains("'node' is not recognized"))) {
                        return error("Node.js is not installed or not in PATH. Please install Node.js to execute JavaScript code.",
                                result.getExecutionTime());
                    }
                    return error("JavaScript execution failed:\n" + err, result.getExecutionTime());
                }
                if (result.getOutput() == null || result.getOutput().isEmpty()) {
                    return success("JavaScript code executed successfully (no output)", result.getExecutionTime());
                }
                return result;

            } finally {
                tempFile.delete();
            }

        } catch (IOException e) {
            if (e.getMessage().contains("Cannot run program \"node\"")) {
                return error("Node.js is not installed or not in PATH. Please install Node.js to execute JavaScript code.",
                        System.currentTimeMillis() - startTime);
            }
            return error("JavaScript execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("JavaScript execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private ToolResult executePython(String code, long startTime) {
        try {
            // 通过系统命令执行Python代码
            File tempFile = File.createTempFile("thoughtcoding_python_", ".py");
            try {
                Files.writeString(tempFile.toPath(), code);

                ToolResult result = runProcess(new ProcessBuilder("python", tempFile.getAbsolutePath()), startTime);
                if (result.isSuccess() && (result.getOutput() == null || result.getOutput().isEmpty())) {
                    return success("Python code executed successfully (no output)", result.getExecutionTime());
                }
                if (!result.isSuccess()) {
                    return error("Python execution failed:\n" + result.getError(), result.getExecutionTime());
                }
                return result;

            } finally {
                tempFile.delete();
            }

        } catch (Exception e) {
            return error("Python execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public String getCategory() {
        return "exec";
    }

    @Override
    public boolean isEnabled() {
        return appConfig != null && appConfig.getTools().getCodeExecutor().isEnabled();
    }
}