/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.codepatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a unified diff as bytes: the output of {@code git diff} or {@code git format-patch} (extended headers
 * {@code new file mode}, {@code deleted file mode}, {@code rename from}, {@code rename to}) and of {@code diff -u}.
 * Hunk lines keep their bytes and line terminators ({@link HunkReader}); {@link Section} turns each file section's
 * headers into a {@link FilePatch}. Text outside file sections (mail headers, commit message, diffstat, signature) and
 * the {@code index}, {@code similarity} and mode lines are ignored, so file modes are not applied. Each
 * {@code From <sha> Mon Sep 17 00:00:00 2001} line of a {@code git format-patch --stdout} series ends the file of the
 * mail before it. Refused: git header lines outside a section header, copies, binary patches, combined diffs, quoted
 * paths, an unreadable hunk header (every line that starts with {@code @@}, and after a file section's
 * {@code ---}/{@code +++} lines every line that starts with {@code @}), a file section with {@code ---}/{@code +++}
 * lines but no hunk, and hunk lines after a finished hunk body (a header that counts fewer lines than follow it), a
 * {@code ---}/{@code +++} pair there included unless it starts the next {@code diff -u} file.
 */
public final class PatchParser {
    private static final String STALE = ", whose header counts fewer lines";
    private static final Pattern MAIL_START = Pattern.compile("From [0-9a-f]{40} Mon Sep 17 00:00:00 2001");
    private final PatchLines lines;
    private final List<FilePatch> files = new ArrayList<>();
    private Section section;
    private String finishedHunk;

    private PatchParser(byte[] patch, String source) {
        this.lines = new PatchLines(patch, source);
    }

    /** The file sections of a patch in patch order; {@code source} names the patch in messages. */
    public static List<FilePatch> parse(byte[] patch, String source) throws PatchException {
        PatchParser parser = new PatchParser(patch, source);
        while (parser.lines.more()) parser.dispatch(parser.lines.text(0));
        parser.finish();
        return List.copyOf(parser.files);
    }

    /**
     * One line: the start of a git section, the start of a combined diff (refused), a {@code ---}/{@code +++} pair, a
     * hunk, the start of the next mail of a series, or any other line.
     */
    private void dispatch(String text) throws PatchException {
        if (text.startsWith("diff --git ")) {
            gitSection(text.substring("diff --git ".length()));
        } else if (text.startsWith("diff --cc ") || text.startsWith("diff --combined ")) {
            throw lines.refuse("combined diffs (diff --cc, diff --combined) are not supported: " + text);
        } else if (text.startsWith("--- ") && String.valueOf(lines.text(1)).startsWith("+++ ")) {
            sides(text);
        } else if (hunkHeader(text)) {
            hunk();
        } else if (MAIL_START.matcher(text).matches()) {
            endOfMail();
        } else {
            other(text);
        }
    }

    /**
     * Every line that starts with {@code @@} is a hunk header, readable or not ({@link HunkReader} refuses an unreadable
     * one, such as {@code @@-3 +3 @@} or an {@code @@@} combined hunk); after a section's {@code ---}/{@code +++} lines
     * so is every line that starts with {@code @}. Such a line is never skipped as text, so a header typo cannot end the
     * hunks of a section and drop the lines that follow it.
     */
    private boolean hunkHeader(String text) {
        return text.startsWith("@@") || text.startsWith("@") && section != null && section.hasSides();
    }

    /** The {@code From <sha>} line that starts each mail of {@code git format-patch --stdout}: the file before it ends. */
    private void endOfMail() throws PatchException {
        finish();
        finishedHunk = null;
        lines.take();
    }

    private void gitSection(String rest) throws PatchException {
        finish();
        section = new Section(true, lines);
        section.gitPaths(rest);
        lines.take();
        finishedHunk = null;
    }

    private void sides(String minusLine) throws PatchException {
        pairAfterHunk();
        if (section == null || section.hasSidesOrHunks()) {
            finish();
            section = new Section(false, lines);
        }
        section.sides(minusLine.substring(4), lines.text(1).substring(4));
        lines.skip(2);
        finishedHunk = null;
    }

    /**
     * A {@code ---}/{@code +++} pair right after a finished hunk starts a new file only in a {@code diff -u} section and
     * only before a hunk header (git starts every file with its {@code diff --git} line). Otherwise it is a removed
     * {@code "-- "} line and an added {@code "++ "} line beyond the hunk's count, and it is refused.
     */
    private void pairAfterHunk() throws PatchException {
        if (finishedHunk == null) return;
        if (section.git()) throw stale(" of a diff --git section (a new git file starts with its diff --git line)");
        if (!String.valueOf(lines.text(2)).startsWith("@@ ")) throw stale(STALE);
    }

    private void hunk() throws PatchException {
        if (section == null || !section.hasSides()) throw lines.refuse("hunk without --- and +++ lines");
        String header = lines.text(0);
        section.add(HunkReader.read(lines));
        finishedHunk = header;
    }

    /**
     * Any other line. After a hunk body a {@code ' '}, {@code '+'}, {@code '-'} or {@code '\'} line is refused until a
     * header line (any other non-blank line) follows; the format-patch {@link #signature(String) signature} is allowed
     * there. Git header lines are refused outside a section header (before the first section only in their exact syntax,
     * so a commit message may use the same words); other text is ignored.
     */
    private void other(String text) throws PatchException {
        byte first = lines.peek()[0];
        boolean hunkLine = first == ' ' || first == '+' || first == '-' || first == '\\';
        if (finishedHunk != null && hunkLine && !signature(text)) throw stale(STALE);
        if (!hunkLine && !text.isEmpty()) finishedHunk = null;
        if (Section.extendedHeader(text)) header(text);
        lines.take();
    }

    /**
     * The format-patch signature: {@code "-- "} followed by plain text (git writes {@code "-- \n<version>\n"}). Followed
     * by the end of the patch, a blank line, a line that starts with {@code ' '}, {@code '+'}, {@code '-'},
     * {@code '\'} or {@code '@'} (a hunk line, a {@code ---}/{@code +++} pair, a hunk header), a {@code diff} line or
     * the {@code From <sha>} line of the next mail, it is a removed line {@code "- "} beyond a stale hunk count.
     */
    private boolean signature(String text) {
        String next = lines.text(1);
        return text.equals("-- ") && next != null && !next.isEmpty() && " +-\\@".indexOf(next.charAt(0)) < 0
                && !next.startsWith("diff ") && !MAIL_START.matcher(next).matches();
    }

    private PatchException stale(String why) {
        return lines.refuse("line after the end of hunk " + finishedHunk + why + ": " + ByteLines.show(lines.peek()));
    }

    private void header(String text) throws PatchException {
        if (section == null && !Section.gitSyntax(text)) return;
        if (section == null || !section.inHeader()) {
            throw lines.refuse("git header line outside the header of a diff --git section: " + text);
        }
        section.header(text);
    }

    private void finish() throws PatchException {
        if (section == null) return;
        Optional<FilePatch> file = section.finish();
        section = null;
        file.ifPresent(files::add);
    }
}
