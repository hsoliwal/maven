package io.synexia.maven.smart;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded Maven diagnostics classification; never serializes raw log lines. */
public record BuildLogFacts(String sha256, int lines, Map<String,Integer> categories,
                            Map<String,Integer> patterns) {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final String[] CATEGORIES = {"compiler", "dependency", "test", "plugin", "native", "other"};
    private static final String[] PATTERNS = {
        "cannot-find-symbol", "missing-package", "incompatible-types", "compilation-failure",
        "artifact-resolution", "test-failure", "plugin-execution", "native-link", "other-error"
    };

    public static BuildLogFacts inspect(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > MAX_BYTES) {
            throw new IOException("log missing, linked or exceeds 32 MiB");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) throw new IOException("log size changed during reading");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("log must be valid UTF-8", invalid);
        }
        Map<String,Integer> byCategory = new LinkedHashMap<>();
        Map<String,Integer> byPattern = new LinkedHashMap<>();
        for (String category : CATEGORIES) byCategory.put(category, 0);
        for (String pattern : PATTERNS) byPattern.put(pattern, 0);
        String[] lines = text.split("\\r?\\n", -1);
        int lineCount = lines.length - (lines.length > 0 && lines[lines.length - 1].isEmpty() ? 1 : 0);
        for (int i = 0; i < lineCount; i++) {
            String line = lines[i];
            if (line.length() > 65536) throw new IOException("overlong log line");
            String lower = line.toLowerCase(Locale.ROOT);
            String category = null, pattern = null;
            if (lower.contains("cannot find symbol")) { category = "compiler"; pattern = "cannot-find-symbol"; }
            else if (lower.contains("does not exist") && lower.contains("package")) { category = "compiler"; pattern = "missing-package"; }
            else if (lower.contains("incompatible types")) { category = "compiler"; pattern = "incompatible-types"; }
            else if (lower.contains("compilation failure") || lower.contains("compilation error")) { category = "compiler"; pattern = "compilation-failure"; }
            else if (lower.contains("could not resolve dependencies") || lower.contains("could not find artifact")
                    || lower.contains("failed to collect dependencies")) { category = "dependency"; pattern = "artifact-resolution"; }
            else if (lower.contains("there are test failures") || lower.contains("failed tests:")
                    || lower.contains("failures: 1") || lower.contains("tests run:") && lower.contains("failures:")
                      && !lower.contains("failures: 0")) { category = "test"; pattern = "test-failure"; }
            else if (lower.contains("undefined reference") || lower.contains("unsatisfiedlinkerror")
                    || lower.contains("cmake error") || lower.contains("cannot open shared object file")) {
                category = "native"; pattern = "native-link";
            }
            else if (lower.contains("failed to execute goal") || lower.contains("pluginresolutionexception")) {
                category = "plugin"; pattern = "plugin-execution";
            }
            else if (lower.contains("[error]") || lower.contains("[fatal]")) { category = "other"; pattern = "other-error"; }
            if (category != null) {
                byCategory.put(category, byCategory.get(category) + 1);
                byPattern.put(pattern, byPattern.get(pattern) + 1);
            }
        }
        return new BuildLogFacts(Hashes.sha256(bytes), lineCount, Map.copyOf(byCategory), Map.copyOf(byPattern));
    }

    public String json() {
        StringBuilder out = new StringBuilder("{\"sha256\":").append(TinyJson.quote(sha256))
                .append(",\"lines\":").append(lines).append(",\"categories\":{");
        for (int i=0; i<CATEGORIES.length; i++) {
            if (i != 0) out.append(',');
            out.append(TinyJson.quote(CATEGORIES[i])).append(':').append(categories.get(CATEGORIES[i]));
        }
        out.append("},\"patterns\":{");
        for (int i=0; i<PATTERNS.length; i++) {
            if (i != 0) out.append(',');
            out.append(TinyJson.quote(PATTERNS[i])).append(':').append(patterns.get(PATTERNS[i]));
        }
        return out.append("}}").toString();
    }
}
