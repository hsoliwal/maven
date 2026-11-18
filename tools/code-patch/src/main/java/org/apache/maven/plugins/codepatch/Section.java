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
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.maven.plugins.codepatch.FilePatch.Hunk;
import org.apache.maven.plugins.codepatch.FilePatch.Operation;

/**
 * The header facts of one file section as the parser reads them, and the {@link FilePatch} they describe. In a git
 * section the {@code diff --git} line decides whether paths carry the {@code a/} and {@code b/} prefixes ({@code a/P
 * b/P}: yes, {@code P P}: no); the {@code ---}/{@code +++} paths and the rename lines must then name the same files.
 * When the git line does not tell (a rename, or a {@code diff -u} section), the prefixes are removed when both sides
 * have them.
 */
final class Section {
    private static final String DEV_NULL = "/dev/null";
    private static final Pattern GIT_SYNTAX =
            Pattern.compile("Binary files .+ and .+ differ|GIT binary patch|(new|deleted) file mode [0-7]{6}");
    private final boolean git;
    private final PatchLines lines;
    private final List<Hunk> hunks = new ArrayList<>();
    private String gitPath;
    private Boolean gitPrefixed;
    private String minus;
    private String plus;
    private int sidesLine;
    private String renameFrom;
    private String renameTo;
    private boolean added;
    private boolean deleted;

    Section(boolean git, PatchLines lines) {
        this.git = git;
        this.lines = lines;
    }

    /** True for the git header lines that only a section header may hold. */
    static boolean extendedHeader(String text) {
        return Stream.of("new file mode ", "deleted file mode ", "rename from ", "rename to ", "copy from ", "copy to ",
                "Binary files ").anyMatch(text::startsWith) || text.equals("GIT binary patch");
    }

    /**
     * True for a git header line in its exact syntax that tells without a {@code diff --git} line: a binary line or a
     * file mode line. Rename and copy lines are git lines only in the header of a {@code diff --git} section.
     */
    static boolean gitSyntax(String text) {
        return GIT_SYNTAX.matcher(text).matches();
    }

    /** A git section before its {@code ---}/{@code +++} lines and hunks: where git header lines belong. */
    boolean inHeader() {
        return git && minus == null && hunks.isEmpty();
    }

    /** A git section: its next file starts with a {@code diff --git} line, never with a bare {@code ---}/{@code +++}. */
    boolean git() {
        return git;
    }

    boolean hasSidesOrHunks() {
        return minus != null || !hunks.isEmpty();
    }

    boolean hasSides() {
        return minus != null;
    }

    void add(Hunk hunk) {
        hunks.add(hunk);
    }

    /** {@code a/P b/P} or {@code P P}; other forms (a rename, or names with spaces) leave the paths to later lines. */
    void gitPaths(String rest) throws PatchException {
        if (rest.startsWith("\"")) throw lines.refuse("quoted paths are not supported: " + rest);
        int half = (rest.length() - 1) / 2;
        if (rest.length() % 2 == 0 || rest.charAt(half) != ' ') return;
        String left = rest.substring(0, half);
        String right = rest.substring(half + 1);
        if (left.startsWith("a/") && right.startsWith("b/") && left.substring(2).equals(right.substring(2))) {
            gitPath = left.substring(2);
            gitPrefixed = true;
        } else if (left.equals(right)) {
            gitPath = left;
            gitPrefixed = false;
        }
    }

    /** The {@code ---} and {@code +++} paths: a tab and what follows it (a timestamp) are not part of a path. */
    void sides(String minusText, String plusText) throws PatchException {
        sidesLine = lines.number();
        minus = headerPath(minusText);
        plus = headerPath(plusText);
    }

    void header(String text) throws PatchException {
        if (text.startsWith("new file mode ")) {
            added = true;
        } else if (text.startsWith("deleted file mode ")) {
            deleted = true;
        } else if (text.startsWith("rename from ")) {
            renameFrom = unquoted(text.substring("rename from ".length()));
        } else if (text.startsWith("rename to ")) {
            renameTo = unquoted(text.substring("rename to ".length()));
        } else if (text.startsWith("copy ")) {
            throw lines.refuse("copies are not supported: " + text);
        } else {
            throw lines.refuse("binary patches are not supported: " + text);
        }
    }

    /**
     * The file patch, or empty for a git section that changes no content (a mode change, a git index line only). A
     * section with {@code ---}/{@code +++} lines and no hunk is refused: its content would be dropped.
     */
    Optional<FilePatch> finish() throws PatchException {
        requireHunks();
        Operation operation = operation();
        String oldPath = path(minus, "a/", renameFrom);
        String newPath = path(plus, "b/", renameTo);
        if (operation != Operation.ADD && oldPath == null || operation != Operation.DELETE && newPath == null) {
            throw lines.refuse("cannot read the file names of a file section");
        }
        if (operation == Operation.MODIFY && hunks.isEmpty()) return Optional.empty();
        String from = operation == Operation.MODIFY ? modified(oldPath, newPath) : oldPath;
        return Optional.of(new FilePatch(operation == Operation.ADD ? null : from,
                operation == Operation.DELETE ? null : newPath, operation, hunks));
    }

    /** Git writes {@code ---}/{@code +++} only before hunks; without one, the lines that follow were not read. */
    private void requireHunks() throws PatchException {
        if (minus != null && hunks.isEmpty()) {
            throw lines.refuse(sidesLine, "file section without hunks: --- " + minus + ", +++ " + plus
                    + " (no hunk header follows the ---/+++ lines)");
        }
    }

    /** The old path of a MODIFY: in a git section both paths must agree; in a diff -u section the +++ path wins. */
    private String modified(String oldPath, String newPath) throws PatchException {
        if (git && !oldPath.equals(newPath)) {
            throw lines.refuse("old and new path differ without rename lines: " + oldPath + ", " + newPath);
        }
        return newPath;
    }

    private Operation operation() throws PatchException {
        boolean add = added || DEV_NULL.equals(minus);
        boolean delete = deleted || DEV_NULL.equals(plus);
        boolean rename = renameFrom != null || renameTo != null;
        if ((add ? 1 : 0) + (delete ? 1 : 0) + (rename ? 1 : 0) > 1 || rename && (renameFrom == null || renameTo == null)) {
            throw lines.refuse("contradictory file headers (new, deleted, renamed)");
        }
        return add ? Operation.ADD : delete ? Operation.DELETE : rename ? Operation.RENAME : Operation.MODIFY;
    }

    /**
     * One side's path from its {@code ---}/{@code +++} path, its rename line and the git line; every one of them that
     * names a file must name the same one.
     */
    private String path(String header, String prefix, String rename) throws PatchException {
        String side = header == null || DEV_NULL.equals(header) ? null : strip(header, prefix);
        List<String> named = Stream.of(rename, side, gitPath).filter(Objects::nonNull).distinct().toList();
        if (named.size() > 1) {
            throw lines.refuse("the diff --git, rename and ---/+++ paths disagree: " + String.join(", ", named));
        }
        return named.isEmpty() ? null : named.get(0);
    }

    private String strip(String header, String prefix) throws PatchException {
        boolean prefixed = gitPrefixed != null ? gitPrefixed : prefixed(minus, "a/") && prefixed(plus, "b/");
        if (!prefixed) return header;
        if (!header.startsWith(prefix)) {
            throw lines.refuse("the diff --git paths disagree with the ---/+++ paths: " + gitPath + ", " + header);
        }
        return header.substring(prefix.length());
    }

    private static boolean prefixed(String header, String prefix) {
        return header == null || DEV_NULL.equals(header) || header.startsWith(prefix);
    }

    private String headerPath(String text) throws PatchException {
        int tab = text.indexOf('\t');
        return unquoted(tab < 0 ? text : text.substring(0, tab));
    }

    private String unquoted(String path) throws PatchException {
        if (path.startsWith("\"")) throw lines.refuse("quoted paths are not supported: " + path);
        return path;
    }
}
