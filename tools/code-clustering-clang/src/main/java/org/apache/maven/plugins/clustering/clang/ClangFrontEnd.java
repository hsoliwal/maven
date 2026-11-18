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
package org.apache.maven.plugins.clustering.clang;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.apache.maven.plugins.clustering.AtomFrontEnd;
import org.apache.maven.plugins.clustering.AtomPlanes.Atom;
import org.apache.maven.plugins.clustering.clang.ClangRun.Result;
import org.apache.maven.plugins.clustering.clang.ClangRun.Status;

/**
 * Atomizes C, C++, Objective-C and Objective-C++ on clang's own syntax tree: {@code clang -Xclang -ast-dump=json
 * -fsyntax-only}, run with each file's flags from compile_commands.json (see {@link CompileDatabases}, configuration
 * in {@link ClangSettings}). Atoms and their planes follow {@link AstAtoms} and {@link AstPlanes}. Without clang the
 * front end takes no file, so the plugin's lexical front end atomizes them as before. A file clang cannot read (no
 * compile command, a fatal error, a refused argument, a timeout) goes to the lexical front end, with a warning
 * naming the reason. Files run in parallel, each clang process on a thread with a deep stack for deep trees; atoms
 * come back in file order.
 */
public final class ClangFrontEnd implements AtomFrontEnd {
    static final Set<String> EXTENSIONS = Set.of("c", "h", "cc", "cpp", "cxx", "c++", "hpp", "hh", "hxx", "h++", "m",
            "mm", "inl", "ipp");
    private static final System.Logger LOG = System.getLogger(ClangFrontEnd.class.getName());
    private static final long STACK = 256L << 20;
    private static final int STUB_RETRIES = 64;

    /** What became of one file. */
    record Outcome(Path file, Status status, int atoms, String detail) { }

    private final ClangSettings settings;
    private final CompileDatabases databases;
    private final Consumer<Outcome> outcomes;

    /** The service constructor: configuration from system properties and the environment. */
    public ClangFrontEnd() {
        this(ClangSettings.configured(), ClangFrontEnd::log);
    }

    ClangFrontEnd(ClangSettings settings, Consumer<Outcome> outcomes) {
        this.settings = settings;
        this.databases = new CompileDatabases(settings.databases(), settings.windows(),
                m -> LOG.log(System.Logger.Level.WARNING, m));
        this.outcomes = outcomes;
    }

    @Override public String name() {
        return "clang";
    }

    @Override public boolean accepts(Path file) {
        return settings.clang().isPresent() && EXTENSIONS.contains(AtomFrontEnd.extension(file));
    }

    @Override public List<Atom> atoms(Path root, List<Path> files) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(settings.threads(),
                task -> new Thread(null, task, "clang-ast", STACK));
        try {
            List<Future<List<Atom>>> results = new ArrayList<>();
            files.forEach(file -> results.add(pool.submit(() -> atomize(root, file))));
            List<Atom> atoms = new ArrayList<>();
            for (Future<List<Atom>> result : results) atoms.addAll(get(result));
            return atoms;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Atom> get(Future<List<Atom>> result) throws IOException {
        try {
            return result.get();
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", stop);
        } catch (ExecutionException failed) {
            throw failed.getCause() instanceof UncheckedIOException io ? io.getCause() : new IOException(failed.getCause());
        }
    }

    /** One file: clang's atoms, or the lexical front end's when clang cannot read it. */
    List<Atom> atomize(Path root, Path file) {
        Optional<CompileCommand> command = databases.forFile(file).or(() -> settings.defaults(file));
        Result result = command.map(c -> attempts(c, root, file))
                .orElse(new Result(Status.NO_COMMAND, List.of(), "no compile_commands.json entry", ""));
        if (result.status() == Status.AST) {
            outcomes.accept(new Outcome(file, Status.AST, result.atoms().size(), result.detail()));
            return result.atoms();
        }
        List<Atom> lexical = lexical(root, file);
        outcomes.accept(new Outcome(file, result.status(), lexical.size(), result.detail()));
        return lexical;
    }

    /** Runs clang; with stub headers on, each missing header is stubbed and the file parsed again. */
    private Result attempts(CompileCommand command, Path root, Path file) {
        List<Path> include = settings.stubs().map(List::of).orElse(List.of());
        List<String> words = AstCommand.of(settings.clang().orElseThrow(), command, file.toAbsolutePath(), include);
        Set<String> stubbed = new HashSet<>();
        Result result = ClangRun.run(words, command.directory(), file, relative(root, file), settings.timeout());
        while (result.status() == Status.FATAL && stubbed.size() < STUB_RETRIES && stubbed(result, stubbed)) {
            result = ClangRun.run(words, command.directory(), file, relative(root, file), settings.timeout());
        }
        if (stubbed.isEmpty()) return result;
        String note = (result.detail().isEmpty() ? "" : result.detail() + "; ") + "stub headers: " + String.join(" ", stubbed);
        return new Result(result.status(), result.atoms(), note, result.errors());
    }

    private boolean stubbed(Result result, Set<String> stubbed) {
        Optional<String> header = MissingIncludes.missing(result.errors());
        if (settings.stubs().isEmpty() || header.isEmpty() || !stubbed.add(header.get())) return false;
        try {
            return MissingIncludes.stub(settings.stubs().get(), header.get());
        } catch (IOException unwritable) {
            LOG.log(System.Logger.Level.WARNING, "stub header not written: " + header.get() + ": " + unwritable);
            return false;
        }
    }

    private static List<Atom> lexical(Path root, Path file) {
        AtomFrontEnd lexical = new AtomFrontEnd.LexicalFrontEnd();
        try {
            return lexical.accepts(file) ? lexical.atoms(root, List.of(file)) : List.of();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void log(Outcome outcome) {
        if (outcome.status() == Status.AST) return;
        LOG.log(System.Logger.Level.WARNING, "clang: " + outcome.file() + ": " + outcome.status() + " ("
                + outcome.detail() + "); lexical front end used");
    }

    /** Counts outcomes by status, for summaries. */
    static Map<Status, Integer> count(List<Outcome> outcomes) {
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        outcomes.forEach(o -> counts.merge(o.status(), 1, Integer::sum));
        return counts;
    }
}
