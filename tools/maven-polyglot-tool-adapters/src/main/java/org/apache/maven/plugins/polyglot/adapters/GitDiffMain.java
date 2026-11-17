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
package org.apache.maven.plugins.polyglot.adapters;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The text differ process, around the original git program ({@link PinnedGit}). Exit 0 with the patch written (empty
 * when nothing differs), {@link #FAILED} with no patch left behind when git failed. Usage:
 * <ul>
 *   <li>{@code GitDiffMain trees <git> <before> <after> <out.patch>}: {@code git diff --no-index}. git exits 1 both for
 *   "differences found" and for errors (an unreadable or misspelled tree, after creating an empty output), so both
 *   trees are checked first (they must exist and both be folders or both be files), and exit 1 counts as
 *   differences only with no {@code error:}/{@code fatal:} line and a patch that starts with {@code diff --git}.</li>
 *   <li>{@code GitDiffMain repo <git> <repository> <revision> <out.patch> [<pathspec>...]}: the work tree against a
 *   revision with repository-relative paths, untracked files included (not ignored ones): they are added
 *   intent-to-add to a copy of the index, so the repository's own index is never written. A path with a
 *   {@code filter} attribute is refused before {@code git diff} could run its clean filter.</li>
 * </ul>
 * Relative paths resolve against the working directory, so trees-mode patch headers keep the paths as given.
 */
public final class GitDiffMain {
    static final int FAILED = 2;
    /** check-attr values of an attribute that is not set. */
    static final Set<String> UNSET = Set.of("unspecified", "unset");
    static final String USAGE = "GitDiffMain trees <git> <before> <after> <out.patch>"
            + " | repo <git> <repository> <revision> <out.patch> [<pathspec>...]";
    private static final System.Logger LOG = System.getLogger(GitDiffMain.class.getName());

    private GitDiffMain() { }

    public static void main(String[] args) {
        System.exit(run(List.of(args)));
    }

    static int run(List<String> args) {
        Path out = output(args);
        try {
            diff(args, out);
            LOG.log(System.Logger.Level.INFO, "git differ: {0} bytes in {1}", Files.size(out), out);
            return 0;
        } catch (IOException | InterruptedException failure) {
            LOG.log(System.Logger.Level.ERROR, "git differ failed: {0}", failure.getMessage());
            deleteQuietly(out);
            return FAILED;
        }
    }

    /** The patch path of a well-formed command line. */
    private static Path output(List<String> args) {
        boolean trees = args.size() == 5 && "trees".equals(args.get(0));
        if (!trees && !(args.size() >= 5 && "repo".equals(args.get(0)))) {
            throw new IllegalArgumentException("usage: " + USAGE);
        }
        return Path.of(args.get(4)).toAbsolutePath().normalize();
    }

    private static void diff(List<String> args, Path out) throws IOException, InterruptedException {
        Files.deleteIfExists(out);
        Files.createDirectories(out.getParent());
        Path scratch = Files.createTempDirectory("git-differ-");
        try {
            PinnedGit git = new PinnedGit(args.get(1), scratch);
            if ("trees".equals(args.get(0))) {
                trees(git, args.get(2), args.get(3), out);
            } else {
                repo(git, scratch, args, out);
            }
        } finally {
            delete(scratch);
        }
    }

    private static void trees(PinnedGit git, String before, String after, Path out)
            throws IOException, InterruptedException {
        checkTrees(Path.of(before), Path.of(after));
        PinnedGit.Result result = git.run(Path.of("").toAbsolutePath(), Map.of(), Stream.of(List.of("diff", "--no-index"),
                PinnedGit.DIFF_OPTIONS, List.of("--output=" + out, "--", before, after)).flatMap(List::stream).toList());
        if (!treesSucceeded(result, out)) {
            throw failure("git diff --no-index", result, out);
        }
    }

    /** Exit 0 with an empty patch (equal trees) or exit 1 with a patch (differences), and no error from git. */
    static boolean treesSucceeded(PinnedGit.Result result, Path out) throws IOException {
        boolean same = result.exit() == 0 && Files.isRegularFile(out) && Files.size(out) == 0;
        boolean differs = result.exit() == 1 && patch(out);
        return !result.failed() && (same || differs);
    }

    /** Both trees exist and are of one kind: git would otherwise compare a folder's member, or fail like a difference. */
    static void checkTrees(Path before, Path after) throws IOException {
        boolean folders = Files.isDirectory(before, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(after, LinkOption.NOFOLLOW_LINKS);
        boolean files = Files.isRegularFile(before, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(after, LinkOption.NOFOLLOW_LINKS);
        if (!folders && !files) {
            throw new IOException("both trees must exist and be two folders or two files: " + before + ", " + after);
        }
    }

    /** The work tree against a revision, untracked files intent-to-add in a copy of the index. */
    private static void repo(PinnedGit git, Path scratch, List<String> args, Path out)
            throws IOException, InterruptedException {
        Path repository = folder(Path.of(args.get(2)).toAbsolutePath().normalize());
        Map<String, String> index = Map.of("GIT_INDEX_FILE", indexCopy(git, repository, scratch).toString());
        List<String> pathspecs = args.subList(5, args.size());
        addUntracked(git, repository, index, pathspecs, scratch);
        refuseFilters(git, repository, index, files(git, repository, index, pathspecs, scratch));
        PinnedGit.Result result = git.run(repository, index, Stream.of(List.of("diff"), PinnedGit.DIFF_OPTIONS,
                List.of("--output=" + out, args.get(3), "--"), pathspecs).flatMap(List::stream).toList());
        boolean written = Files.isRegularFile(out) && (Files.size(out) == 0 || patch(out));
        if (result.exit() != 0 || result.failed() || !written) {
            throw failure("git diff " + args.get(3), result, out);
        }
    }

    private static Path folder(Path repository) throws IOException {
        if (!Files.isDirectory(repository)) {
            throw new IOException("the repository is not a folder: " + repository);
        }
        return repository;
    }

    /** A private copy of the repository's index (none in a fresh repository): git writes only the copy. */
    private static Path indexCopy(PinnedGit git, Path repository, Path scratch) throws IOException, InterruptedException {
        PinnedGit.Result where = git.run(repository, Map.of(), List.of("rev-parse", "--git-path", "index"));
        if (where.exit() != 0 || where.failed()) {
            throw failure("git rev-parse --git-path index", where, scratch.resolve("index"));
        }
        Path index = repository.resolve(new String(where.stdout(), StandardCharsets.UTF_8).strip());
        Path copy = scratch.resolve("index");
        if (Files.isRegularFile(index)) {
            Files.copy(index, copy);
        }
        return copy;
    }

    /** Untracked, not ignored files in the pathspecs become intent-to-add entries of the index copy. */
    private static void addUntracked(PinnedGit git, Path repository, Map<String, String> index, List<String> pathspecs,
            Path scratch) throws IOException, InterruptedException {
        List<String> list = new ArrayList<>(List.of("ls-files", "-z", "--others", "--exclude-standard", "--"));
        list.addAll(pathspecs);
        PinnedGit.Result untracked = git.run(repository, index, list);
        if (untracked.exit() != 0 || untracked.failed()) {
            throw failure("git ls-files --others", untracked, scratch.resolve("untracked"));
        }
        if (untracked.stdout().length > 0) {
            intentToAdd(git, repository, index, Files.write(scratch.resolve("untracked"), untracked.stdout()));
        }
    }

    private static void intentToAdd(PinnedGit git, Path repository, Map<String, String> index, Path list)
            throws IOException, InterruptedException {
        PinnedGit.Result added = git.run(repository, index, List.of("--literal-pathspecs", "add", "--intent-to-add",
                "--pathspec-from-file=" + list, "--pathspec-file-nul"));
        if (added.exit() != 0 || added.failed()) {
            throw failure("git add --intent-to-add (index copy)", added, list);
        }
    }

    /** Every path the diff can read (the index copy holds the untracked ones), NUL-separated, in a scratch file. */
    private static Path files(PinnedGit git, Path repository, Map<String, String> index, List<String> pathspecs,
            Path scratch) throws IOException, InterruptedException {
        List<String> list = new ArrayList<>(List.of("ls-files", "-z", "--cached", "--others", "--exclude-standard", "--"));
        list.addAll(pathspecs);
        PinnedGit.Result files = git.run(repository, index, list);
        if (files.exit() != 0 || files.failed()) {
            throw failure("git ls-files --cached --others", files, scratch.resolve("files"));
        }
        return Files.write(scratch.resolve("files"), files.stdout());
    }

    /**
     * A path with a {@code filter} attribute would make git run the clean filter program configured for it (the
     * repository's own configuration, git-lfs and the like) and its bytes would depend on that program: refused.
     */
    private static void refuseFilters(PinnedGit git, Path repository, Map<String, String> index, Path files)
            throws IOException, InterruptedException {
        PinnedGit.Result attributes = git.run(repository, index, List.of("check-attr", "-z", "--stdin", "filter"), files);
        if (attributes.exit() != 0 || attributes.failed()) {
            throw failure("git check-attr filter", attributes, files);
        }
        List<String> filtered = filtered(attributes.stdout());
        if (!filtered.isEmpty()) {
            throw new IOException("repository mode refuses paths with a filter attribute (git would run a configured"
                    + " clean filter program; diff two trees instead): " + filtered);
        }
    }

    /** {@code git check-attr -z} output, {@code <path> NUL <attribute> NUL <value> NUL}: the paths whose value is set. */
    static List<String> filtered(byte[] output) {
        String[] fields = new String(output, StandardCharsets.UTF_8).split(String.valueOf((char) 0), -1);
        return IntStream.range(0, fields.length / 3).filter(i -> !UNSET.contains(fields[3 * i + 2]))
                .mapToObj(i -> fields[3 * i]).toList();
    }

    /** A non-empty unified diff as git writes it. */
    static boolean patch(Path out) throws IOException {
        if (!Files.isRegularFile(out)) {
            return false;
        }
        byte[] head = "diff --git ".getBytes(StandardCharsets.US_ASCII);
        try (var in = Files.newInputStream(out)) {
            return Arrays.equals(in.readNBytes(head.length), head);
        }
    }

    private static IOException failure(String what, PinnedGit.Result result, Path out) {
        String size = Files.isRegularFile(out) ? out.toFile().length() + " bytes" : "no file";
        return new IOException(what + " failed (exit " + result.exit() + ", output " + size + "): "
                + result.stderr().strip());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException kept) {
            LOG.log(System.Logger.Level.ERROR, "git differ: cannot delete the incomplete patch {0}: {1}", file,
                    kept.getMessage());
        }
    }

    private static void delete(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
