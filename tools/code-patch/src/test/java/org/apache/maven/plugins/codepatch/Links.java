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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates directory links for the path tests: symbolic links where the platform and file system allow them, and
 * directory junctions on Windows (which need no privilege). Each returns false when it cannot, and the caller skips.
 */
final class Links {
    private Links() { }

    static boolean symbolic(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    static boolean junction(Path link, Path target) throws InterruptedException {
        if (!Scratch.windows()) return false;
        try {
            Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return mklink.waitFor() == 0 && Files.isDirectory(link);
        } catch (IOException e) {
            return false;
        }
    }

    static void skip(String check, String why) {
        System.err.println("SKIP " + check + " (" + why + ")");
    }
}
