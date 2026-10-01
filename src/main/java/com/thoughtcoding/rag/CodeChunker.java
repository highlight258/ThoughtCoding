package com.thoughtcoding.rag;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码分块器 — 按语义边界（类/方法/函数）切分代码。
 *
 * 支持 Java、Python、JavaScript/TypeScript，其余语言按固定行数切分。
 */
public class CodeChunker {

    public record Chunk(String filePath, int startLine, int endLine,
                        String content, String type, String name) {}

    // Java: class / interface / enum declaration
    private static final Pattern JAVA_CLASS = Pattern.compile(
            "(public\\s+)?(abstract\\s+)?(final\\s+)?(class|interface|enum)\\s+(\\w+)");
    // Java: method / constructor declaration
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(public|private|protected)\\s+(static\\s+)?(final\\s+)?(synchronized\\s+)?" +
            "[\\w<>\\[\\],\\.\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(\\{|throws)");
    private static final Pattern JAVA_CONSTRUCTOR = Pattern.compile(
            "(public|private|protected)\\s+(\\w+)\\s*\\([^)]*\\)\\s*(\\{|throws)");

    // Python: def / class
    private static final Pattern PYTHON_FUNC = Pattern.compile("^\\s*def\\s+(\\w+)\\s*\\(");
    private static final Pattern PYTHON_CLASS = Pattern.compile("^\\s*class\\s+(\\w+)");

    // JS/TS: function / class / arrow function / method
    private static final Pattern JS_FUNC = Pattern.compile(
            "(function\\s+(\\w+)|(const|let|var)\\s+(\\w+)\\s*=\\s*(async\\s+)?\\()");
    private static final Pattern JS_CLASS = Pattern.compile("class\\s+(\\w+)");

    private static final int DEFAULT_CHUNK_LINES = 30;

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".kt", ".scala", ".groovy",
            ".py", ".pyi", ".pyx",
            ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs",
            ".go", ".rs", ".cpp", ".c", ".h", ".hpp", ".cc", ".cxx",
            ".rb", ".php", ".swift", ".cs", ".vb",
            ".xml", ".yaml", ".yml", ".json", ".properties",
            ".sql", ".sh", ".bat", ".ps1"
    );

    private static final Set<String> EXCLUDE_DIRS = Set.of(
            "node_modules", ".git", "target", "build", ".idea", ".vscode",
            "__pycache__", ".gradle", "dist", "out", "bin", ".kilocode",
            "sessions", ".thoughtcoding",
            // Windows system directories
            "$RECYCLE.BIN", "System Volume Information", "Config.Msi",
            "Program Files", "Program Files (x86)", "ProgramData",
            "Windows", "Recovery", "Documents and Settings",
            "MSOCache", "PerfLogs", "Intel", "AMD", "NVIDIA"
    );

    public boolean isCodeFile(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        return CODE_EXTENSIONS.contains(filename.substring(dot).toLowerCase(Locale.ROOT));
    }

    public boolean shouldExcludeDir(String dirName) {
        return EXCLUDE_DIRS.contains(dirName);
    }

    /**
     * 根据文件扩展名选择分块策略
     */
    public List<Chunk> chunkFile(String filePath, String content) {
        String ext = filePath.substring(filePath.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case ".java", ".kt", ".scala", ".groovy", ".cs" -> chunkJavaLike(filePath, content);
            case ".py", ".pyi" -> chunkPythonLike(filePath, content);
            case ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs" -> chunkJsLike(filePath, content);
            default -> chunkGeneric(filePath, content);
        };
    }

    /**
     * Java 风格语言：按 class / method 边界分块
     */
    private List<Chunk> chunkJavaLike(String filePath, String content) {
        List<Chunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n", -1);

        String currentType = "top-level";
        String currentName = filePath;
        int chunkStart = 1;
        StringBuilder chunkBuf = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            // 检测类声明
            Matcher classM = JAVA_CLASS.matcher(line.trim());
            if (classM.find()) {
                // 保存当前 chunk
                if (chunkBuf.length() > 0) {
                    chunks.add(new Chunk(filePath, chunkStart, lineNum - 1,
                            chunkBuf.toString().trim(), currentType, currentName));
                }
                currentType = classM.group(4);  // class/interface/enum
                currentName = classM.group(5);  // class name
                chunkStart = lineNum;
                chunkBuf = new StringBuilder();
                chunkBuf.append(line).append("\n");
                continue;
            }

            // 检测方法/构造函数声明（粗略：在行首或有缩进）
            String trimmed = line.trim();
            Matcher methodM = JAVA_METHOD.matcher(trimmed);
            Matcher ctorM = JAVA_CONSTRUCTOR.matcher(trimmed);

            boolean methodFound = methodM.find();
            boolean ctorFound = ctorM.find();

            if (methodFound || ctorFound) {
                String methodName;
                if (methodFound) {
                    methodName = methodM.group(5);
                } else {
                    methodName = ctorM.group(2);
                }
                // 保存前一个 chunk（类级声明、导入等）
                if (chunkBuf.length() > 0 && !currentType.equals("method")) {
                    chunks.add(new Chunk(filePath, chunkStart, lineNum - 1,
                            chunkBuf.toString().trim(), currentType, currentName));
                }
                // 小方法合并到类级 chunk 中，除非是 10 行以上的大方法
                currentType = "method";
                currentName = methodName;
                chunkStart = lineNum;
                chunkBuf = new StringBuilder();
            }

            chunkBuf.append(line).append("\n");
        }

        // 最后一个 chunk
        if (chunkBuf.length() > 0) {
            chunks.add(new Chunk(filePath, chunkStart, lines.length,
                    chunkBuf.toString().trim(), currentType, currentName));
        }

        return chunks.isEmpty() ?
                List.of(new Chunk(filePath, 1, lines.length, content, "file", filePath)) :
                chunks;
    }

    /**
     * Python：按 def / class 边界分块
     */
    private List<Chunk> chunkPythonLike(String filePath, String content) {
        return chunkByLinePattern(filePath, content, PYTHON_FUNC, PYTHON_CLASS);
    }

    /**
     * JavaScript/TypeScript：按 function / class / 箭头函数边界
     */
    private List<Chunk> chunkJsLike(String filePath, String content) {
        return chunkByLinePattern(filePath, content, JS_FUNC, JS_CLASS);
    }

    /**
     * 通用：按行正则匹配分块
     */
    private List<Chunk> chunkByLinePattern(String filePath, String content,
                                            Pattern funcPattern, Pattern classPattern) {
        List<Chunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n", -1);

        String currentType = "file";
        String currentName = filePath;
        int chunkStart = 1;
        StringBuilder chunkBuf = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            Matcher classM = classPattern.matcher(line);
            Matcher funcM = funcPattern.matcher(line);

            if (classM.find()) {
                if (chunkBuf.length() > 0) {
                    chunks.add(new Chunk(filePath, chunkStart, lineNum - 1,
                            chunkBuf.toString().trim(), currentType, currentName));
                }
                currentType = "class";
                currentName = classM.group(1);
                chunkStart = lineNum;
                chunkBuf = new StringBuilder();
            } else if (funcM.find()) {
                if (chunkBuf.length() > 0 && !"function".equals(currentType)) {
                    chunks.add(new Chunk(filePath, chunkStart, lineNum - 1,
                            chunkBuf.toString().trim(), currentType, currentName));
                }
                currentType = "function";
                currentName = funcM.group(1);
                chunkStart = lineNum;
                chunkBuf = new StringBuilder();
            }

            chunkBuf.append(line).append("\n");
        }

        if (chunkBuf.length() > 0) {
            chunks.add(new Chunk(filePath, chunkStart, lines.length,
                    chunkBuf.toString().trim(), currentType, currentName));
        }

        return chunks.isEmpty() ?
                List.of(new Chunk(filePath, 1, lines.length, content, "file", filePath)) :
                chunks;
    }

    /**
     * 无法识别语言的文件，按固定行数分块
     */
    private List<Chunk> chunkGeneric(String filePath, String content) {
        List<Chunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n", -1);

        for (int start = 0; start < lines.length; start += DEFAULT_CHUNK_LINES) {
            int end = Math.min(start + DEFAULT_CHUNK_LINES, lines.length);
            StringBuilder buf = new StringBuilder();
            for (int i = start; i < end; i++) {
                buf.append(lines[i]).append("\n");
            }
            chunks.add(new Chunk(filePath, start + 1, end,
                    buf.toString().trim(), "block",
                    filePath + "#L" + (start + 1)));
        }

        return chunks;
    }
}
