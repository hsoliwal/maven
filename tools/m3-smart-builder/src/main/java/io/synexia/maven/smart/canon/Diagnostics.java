// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build and lint log extraction (BLLSE). Atom: one diagnostic line. Pattern: extractor; javac, gcc, clang and
 * Maven diagnostics become {@code stage, file, line, column, severity, message} rows in {@code BUILD_LOG.tsv}.
 */
final class Diagnostics {
    static final Pattern COLON = Pattern.compile("^(.+?):(\\d+)(?::(\\d+))?:\\s*(error|warning):\\s*(.*)$");
    static final Pattern MAVEN = Pattern.compile("^\\[(ERROR|WARNING)\\]\\s+(.+?):\\[(\\d+),(\\d+)\\]\\s*(.*)$");
    private static final int MESSAGE_LIMIT = 300;

    private Diagnostics() {
    }

    /** Writes {@code BUILD_LOG.tsv} and returns error and warning counts per stage. */
    static Map<String, int[]> extract(Path runDir, List<RunState.StageRun> stages) throws IOException {
        StringBuilder out = new StringBuilder("stage\tfile\tline\tcolumn\tseverity\tmessage\n");
        Map<String, int[]> counts = new TreeMap<>();
        for (RunState.StageRun stage : stages) {
            int[] count = counts.computeIfAbsent(stage.stage(), k -> new int[2]);
            Path file = runDir.resolve(stage.log());
            if (!Files.isRegularFile(file)) continue;
            try (BufferedReader reader = Ledger.reader(file)) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    Matcher colon = COLON.matcher(line);
                    Matcher maven = MAVEN.matcher(line);
                    if (colon.matches()) row(out, count, stage.stage(), colon.group(1), colon.group(2), colon.group(3), colon.group(4), colon.group(5));
                    else if (maven.matches()) row(out, count, stage.stage(), maven.group(2), maven.group(3), maven.group(4), maven.group(1), maven.group(5));
                }
            }
        }
        Ledger.write(runDir, "BUILD_LOG.tsv", out.toString());
        return counts;
    }

    private static void row(StringBuilder out, int[] count, String stage, String file, String line, String column,
            String severity, String message) {
        String level = severity.toLowerCase(Locale.ROOT);
        count[level.equals("error") ? 0 : 1]++;
        String text = message.length() > MESSAGE_LIMIT ? message.substring(0, MESSAGE_LIMIT) : message;
        out.append(stage).append('\t').append(Ledger.clean(file)).append('\t').append(line).append('\t')
                .append(column == null ? "-" : column).append('\t').append(level).append('\t').append(Ledger.clean(text))
                .append('\n');
    }
}
