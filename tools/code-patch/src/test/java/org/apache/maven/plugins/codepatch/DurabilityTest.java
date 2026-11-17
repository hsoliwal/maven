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

import static org.apache.maven.plugins.codepatch.Checks.check;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Every backup is forced to disk, and its directory synced where the platform allows it, before STATUS PENDING is
 * written: checked through the FINE records of the package logger ({@code force-file}, {@code force-directory}).
 */
final class DurabilityTest {
    private static final String[] BACKED_UP = {"src/crlf.txt", "src/gone.txt", "src/keep.txt", "src/old.txt", "src/nonl.txt"};

    private DurabilityTest() { }

    /** Collects the messages of the records it receives. */
    private static final class Recorder extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override public void flush() { }

        @Override public void close() { }
    }

    static void run(Path dir) throws Exception {
        Path base = Fixtures.tree(dir.resolve("tree"), Fixtures.TREE);
        Path out = base.resolve("target/patch");
        Path patch = Fixtures.write(dir.resolve("0001-change.patch"), Fixtures.SERIES);
        String id = CodePatch.plan(base, out, List.of(patch)).id();
        List<String> records = recorded(() -> CodePatch.apply(base, out, List.of(patch), id));
        Path receipt = CodePatch.receipt(out, id);
        int status = records.indexOf("force-file\t" + receipt.resolve("STATUS"));
        check("STATUS write is forced (" + records.size() + " records)", status >= 0);
        backups(records, receipt, status);
        directory(records, receipt, status);
        CodePatch.rollback(base, out, id);
    }

    private static void backups(List<String> records, Path receipt, int status) {
        for (String path : BACKED_UP) {
            int at = records.indexOf("force-file\t" + receipt.resolve("backup").resolve(path));
            check("backup forced before STATUS PENDING: " + path, at >= 0 && at < status);
        }
    }

    private static void directory(List<String> records, Path receipt, int status) {
        String prefix = "force-directory\t" + receipt.resolve("backup/src") + "\t";
        int synced = -1;
        for (int i = records.size() - 1; i >= 0; i--) if (records.get(i).startsWith(prefix) && i < status) synced = i;
        check("backup directory synced before STATUS PENDING: " + (synced < 0 ? "no record" : records.get(synced)),
                synced >= 0 && (Scratch.windows() || records.get(synced).endsWith("\tok")));
    }

    private static List<String> recorded(Checks.Action action) throws Exception {
        Logger logger = Logger.getLogger("org.apache.maven.plugins.codepatch");
        Recorder recorder = new Recorder();
        Level level = logger.getLevel();
        logger.setLevel(Level.FINE);
        logger.addHandler(recorder);
        try {
            action.run();
        } finally {
            logger.removeHandler(recorder);
            logger.setLevel(level);
        }
        return recorder.messages;
    }
}
