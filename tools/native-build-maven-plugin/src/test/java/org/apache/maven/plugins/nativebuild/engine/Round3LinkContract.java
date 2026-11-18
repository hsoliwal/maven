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
package org.apache.maven.plugins.nativebuild.engine;

import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.LINUX;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.context;
import static org.apache.maven.plugins.nativebuild.engine.PlanFixtures.options;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Round-3 MEDIUM findings about names and links: batch tools spelled with a trailing dot, a trailing space or a
 * stream suffix; junctions and symbolic links inside deleted trees, inside the stage and on recorded clean paths.
 */
final class Round3LinkContract {
    private static final List<String> SPELLINGS = List.of("fake.cmd.", "fake.cmd..", "fake.cmd. .", "fake.cmd::$DATA");

    private Round3LinkContract() {}

    static void run(Checks c) throws Exception {
        batchSpellings(c);
        resolvedName(c);
        junctionDelete(c);
        stageLinks(c);
        recordedPaths(c);
    }

    private static void batchSpellings(Checks c) throws Exception {
        if (!Round3ProcessContract.windows()) {
            c.check("MEDIUM batch spellings (Windows only, skipped)", true);
            return;
        }
        Path dir = c.dir("r3-batch", Map.of("fake.cmd", "@echo off\r\necho %*\r\n"));
        String tool = WindowsPaths.mixed(dir.resolve("fake.cmd"));
        c.check("MEDIUM batch: every spelling of a .cmd path is a batch tool", SPELLINGS.stream()
                .allMatch(s -> WindowsArgs.batch(WindowsPaths.mixed(dir) + "/" + s)));
        Round3ProcessContract.quietly(IntStream.range(0, 2).<Checks.ThrowingRunnable>mapToObj(i -> () ->
                CapturedProcess.run(List.of(tool + ".".repeat(i + 1), "x&echo.INJ>INJ" + i + ".txt"), dir,
                        Duration.ofMinutes(1))).toList());
        c.check("MEDIUM batch: fake.cmd. and fake.cmd.. cannot run an injected command",
                !Files.exists(dir.resolve("INJ0.txt")) && !Files.exists(dir.resolve("INJ1.txt")));
    }

    private static void resolvedName(Checks c) throws Exception {
        if (!Round3ProcessContract.windows()) {
            c.check("MEDIUM batch: resolved tool names (Windows only, skipped)", true);
            return;
        }
        Path dir = c.dir("r3-resolve", Map.of("fakemake.cmd", "@echo off\r\n"));
        ToolResolver resolver = new ToolResolver(Map.of("make", WindowsPaths.mixed(dir) + "/fakemake.cmd."), List.of(),
                Map.of(), Map.of(), Platform.current(), System.getenv());
        Optional<ToolSet.Tool> make = resolver.resolve("make");
        c.check("MEDIUM batch: a configured tool resolves to its real file name " + make,
                make.isPresent() && make.get().path().endsWith("/fakemake.cmd"));
    }

    private static void junctionDelete(Checks c) throws Exception {
        Path victim = c.dir("r3-victim", Map.of("VICTIM.txt", "keep"));
        Path tree = c.dir("r3-tree", Map.of("bin/a.txt", "x"));
        boolean inner = link(tree.resolve("bin/link"), victim);
        Path rootLink = c.dir("r3-links", Map.of()).resolve("root-link");
        boolean outer = link(rootLink, victim);
        Round3ProcessContract.quietly(List.of(() -> FileTrees.delete(tree), () -> FileTrees.delete(rootLink)));
        c.check("MEDIUM delete: a junction or link inside a deleted tree is not followed",
                inner && Files.isRegularFile(victim.resolve("VICTIM.txt")) && !Files.exists(tree));
        c.check("MEDIUM delete: a tree that is itself a link loses only the link",
                outer && Files.isRegularFile(victim.resolve("VICTIM.txt")) && !Files.exists(rootLink, LINK));
        unlink(tree.resolve("bin/link"));
        unlink(rootLink);
    }

    private static void stageLinks(Checks c) throws Exception {
        Path base = c.dir("r3-stage-link", Map.of("stage/lib/libfx.a", "a", "outside/id_rsa_fake", "KEY"));
        Path stage = base.resolve("stage");
        boolean inside = link(stage.resolve("lib-again"), stage.resolve("lib"));
        Optional<StageCollector.Staged> kept = staged(stage);
        c.check("stage: a link that stays inside the stage is accepted " + kept, inside && kept.isPresent()
                && kept.get().all().contains("lib/libfx.a"));
        boolean leak = link(stage.resolve("lib/leak"), base.resolve("outside"));
        c.fails("HIGH stage: a link that leaves the stage is refused", IOException.class,
                () -> StageCollector.classify(stage));
        c.check("stage: links were created", leak);
        unlink(stage.resolve("lib/leak"));
        unlink(stage.resolve("lib-again"));
    }

    private static void recordedPaths(Checks c) throws Exception {
        Path victim = c.dir("r3-rec-victim", Map.of("victim.txt", "keep"));
        Path base = c.dir("r3-rec", Map.of("configure.ac", "AC_INIT\n", "configure", "", "ok.txt", "",
                GeneratedFiles.BOOTSTRAP, "../r3-rec-victim/victim.txt\n" + WindowsPaths.mixed(victim) + "/victim.txt\n"
                        + "/etc/passwd\ntarget/x\n.git/config\nsub/../../r3-rec-victim/victim.txt\nlnk/victim.txt\n"
                        + "ok.txt\n"));
        link(base.resolve("lnk"), victim);
        Layout layout = context(base, BuildSystem.AUTOTOOLS, "configure", LINUX, options().build(), Map.of()).layout();
        c.equal("MEDIUM clean: only recorded paths inside the project, outside target, .git and links, are deleted",
                List.of("ok.txt"), GeneratedFiles.recorded(layout));
        unlink(base.resolve("lnk"));
    }

    private static Optional<StageCollector.Staged> staged(Path stage) {
        try {
            return Optional.of(StageCollector.classify(stage));
        } catch (IOException refused) {
            return Optional.empty();
        }
    }

    private static final java.nio.file.LinkOption LINK = java.nio.file.LinkOption.NOFOLLOW_LINKS;

    /** A directory junction on Windows (no privilege needed), else a symbolic link; true when it exists. */
    static boolean link(Path link, Path target) throws IOException, InterruptedException {
        if (Round3ProcessContract.windows()) {
            new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        } else {
            Files.createSymbolicLink(link, target);
        }
        return Files.exists(link, LINK);
    }

    /** Removes a link without touching its target. */
    static void unlink(Path link) throws IOException {
        if (Files.exists(link, LINK)) {
            Files.delete(link);
        }
    }
}
