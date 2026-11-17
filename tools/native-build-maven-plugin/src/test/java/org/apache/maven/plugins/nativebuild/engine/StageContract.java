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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Stage classification, prefix stripping, staged outputs, and deterministic, safe zips. */
final class StageContract {
    private StageContract() {}

    static void run(Checks c) throws Exception {
        Path stage = stage(c);
        StageCollector.Staged s = StageCollector.classify(stage);
        classify(c, s);
        prefix(c);
        outputs(c);
        zips(c, stage, s);
        unpack(c, stage, s);
    }

    private static Path stage(Checks c) throws Exception {
        return c.dir("stage", Map.ofEntries(Map.entry("bin/fxtool.exe", "MZ"), Map.entry("bin/libfx.dll", "MZ"),
                Map.entry("lib/libfx.dll.a", "!<arch>"), Map.entry("lib/libcore.a", "!<arch>"),
                Map.entry("lib/fx.lib", "!<arch>"), Map.entry("bin/fx.dll", "MZ"), Map.entry("lib/static.lib", "x"),
                Map.entry("lib/libz.so.1.2.3", "\u007fELF"), Map.entry("include/fx/fx.h", "int f(void);"),
                Map.entry("bin/script", "#!/bin/sh"), Map.entry("libexec/helper", "\u007fELF"),
                Map.entry("share/doc/README", "doc")));
    }

    private static void classify(Checks c, StageCollector.Staged s) {
        c.equal("static libraries", List.of("lib/libcore.a", "lib/static.lib"), s.staticLibraries());
        c.equal("shared and import libraries", List.of("bin/fx.dll", "bin/libfx.dll", "lib/fx.lib", "lib/libfx.dll.a",
                "lib/libz.so.1.2.3"), s.sharedLibraries());
        c.equal("executables (exe, bin, ELF)", List.of("bin/fxtool.exe", "bin/script", "libexec/helper"), s.executables());
        c.equal("headers", List.of("include/fx/fx.h"), s.headers());
        c.equal("other", List.of("share/doc/README"), s.other());
    }

    private static void prefix(Checks c) throws Exception {
        Path destdir = c.dir("stage-destdir", Map.of("usr/local/lib/libfx.a", "x"));
        c.equal("DESTDIR prefix stripped", destdir.resolve("usr/local"), StageCollector.root(destdir, "/usr/local"));
        c.equal("no prefix folder: the stage itself", destdir, StageCollector.root(destdir, "/opt/fx"));
        c.equal("Windows prefix drive ignored", destdir.resolve("usr/local"), StageCollector.root(destdir, "C:/usr/local"));
    }

    private static void outputs(Checks c) throws Exception {
        Path base = c.dir("stage-outputs", Map.of("fxtool.exe", "MZ", "out/libfx.a", "x", "include/fx.h", "h"));
        List<String> staged = StageCollector.copyOutputs(base, List.of("fxtool.exe", "out/libfx.a", "include/fx.h",
                "missing.o"), base.resolve("stage"));
        c.equal("declared outputs staged by kind (missing skipped)", List.of("bin/fxtool.exe", "lib/libfx.a",
                "include/fx.h"), staged);
    }

    private static void zips(Checks c, Path stage, StageCollector.Staged s) throws Exception {
        Path one = stage.resolveSibling("zips/one.zip");
        Path two = stage.resolveSibling("zips/two.zip");
        long time = DeterministicZip.epoch("2026-01-01T00:00:00Z");
        DeterministicZip.write(one, stage, s.all(), time);
        List<String> reversed = new ArrayList<>(s.all());
        Collections.reverse(reversed);
        DeterministicZip.write(two, stage, reversed, time);
        c.equal("two archives of the same stage have the same sha256", sha256(one), sha256(two));
        c.equal("outputTimestamp forms", List.of(1_767_225_600_000L, 1_767_225_600_000L, DeterministicZip.DEFAULT_TIME),
                List.of(time, DeterministicZip.epoch("1767225600"), DeterministicZip.epoch("${project.build.x}")));
    }

    private static void unpack(Checks c, Path stage, StageCollector.Staged s) throws Exception {
        Path one = stage.resolveSibling("zips/one.zip");
        Path unpacked = stage.resolveSibling("unpacked");
        DeterministicZip.extract(one, unpacked);
        c.equal("extract round trip", s.all(), StageCollector.classify(unpacked).all());
        Path evil = stage.resolveSibling("zips/evil.zip");
        writeEntry(evil, "../escape.txt");
        c.fails("zip entry escaping the target fails", IOException.class,
                () -> DeterministicZip.extract(evil, stage.resolveSibling("evil-target")));
    }

    private static void writeEntry(Path zip, String name) throws IOException {
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(file)) {
            out.putNextEntry(new ZipEntry(name));
            out.write(1);
            out.closeEntry();
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
