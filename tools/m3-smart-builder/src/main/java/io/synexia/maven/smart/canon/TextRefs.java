// SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart.canon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Built-in check {@code text_refs} (a rename is complete only when nothing a build executes still uses the old
 * name). Atom: one working tree and a list of retired names. Pattern: guard.
 *
 * <p>A rename recipe changes Java sources. This check reads every other text file a build executes (workflows, make
 * files, build descriptors, scripts and test lists, tracked or untracked, at most 2 MiB each) and reports the first
 * line of each file that still contains one of the retired names as a whole word, one row per file in
 * {@value #TABLE}. Java sources are left to the compiler; ledgers and prose are not read. It reads the working tree
 * only and never fetches.
 */
final class TextRefs {
    static final String TABLE = "TEXT_REFS.tsv";
    static final String PREFIX = "TEXT_REF_";
    private static final int EVIDENCE = 160;
    private static final long TEXT_LIMIT = 2L << 20;
    private static final Pattern EXECUTED = Pattern.compile(
            "\\.(yml|yaml|sh|bash|py|gmk|mk|m4|xml|properties|ps1|bat|cmd|gradle|kts|toml|cfg|list|groups|awk|sed|jsh)$"
                    + "|(^|/)(Makefile|TEST\\.groups|TEST\\.ROOT|ProblemList[^/]*)$");

    private TextRefs() {
    }

    static String check(Git git, Path root, Path out, List<String> names, List<String> violations)
            throws IOException, InterruptedException {
        Pattern word = Pattern.compile("(?<![A-Za-z0-9_])("
                + names.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")(?![A-Za-z0-9_])");
        TreeSet<String> files = new TreeSet<>(git.files(List.of(), List.of(".")));
        files.addAll(git.untracked());
        List<String> rows = new ArrayList<>();
        int scanned = 0;
        for (String path : files) {
            if (path.endsWith(".java") || !EXECUTED.matcher(path).find() || !readable(root.resolve(path))) continue;
            scanned++;
            reference(root, path, word, rows, violations);
        }
        Ledger.write(out, TABLE, "name\tpath\tline\ttext\n" + String.join("", rows));
        return "text references: " + scanned + " file(s) read, " + rows.size() + " still use a retired name";
    }

    private static boolean readable(Path file) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) <= TEXT_LIMIT;
    }

    /** Adds the first line of {@code path} that names a retired word, when there is one. */
    private static void reference(Path root, String path, Pattern word, List<String> rows, List<String> violations)
            throws IOException {
        String text = new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.ISO_8859_1);
        Matcher found = word.matcher(text);
        if (!found.find()) return;
        int start = text.lastIndexOf('\n', found.start()) + 1;
        int end = text.indexOf('\n', found.end());
        long line = text.substring(0, start).chars().filter(ch -> ch == '\n').count() + 1;
        String shown = Ledger.clean(new String(text.substring(start, end < 0 ? text.length() : end)
                .getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)).strip();
        rows.add(found.group(1) + "\t" + Ledger.clean(path) + "\t" + line + "\t"
                + shown.substring(0, Math.min(EVIDENCE, shown.length())) + "\n");
        violations.add(PREFIX + "STALE " + path + ":" + line + " " + found.group(1));
    }
}
