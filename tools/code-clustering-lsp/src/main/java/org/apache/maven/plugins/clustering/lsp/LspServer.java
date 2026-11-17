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
package org.apache.maven.plugins.clustering.lsp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A language server ready to start: a name for reports (a profile server id such as {@code wtp.html}, or the command
 * of a {@code clustering.lsp.servers} entry), its command, the time every request may take, and the LSP languageId
 * of each file extension it serves.
 */
record LspServer(String name, List<String> command, Duration timeout, Map<String, String> languageIds) {
    LspServer {
        command = List.copyOf(command);
        languageIds = Map.copyOf(languageIds);
    }

    String languageId(String extension) {
        return languageIds.getOrDefault(extension, extension);
    }
}
