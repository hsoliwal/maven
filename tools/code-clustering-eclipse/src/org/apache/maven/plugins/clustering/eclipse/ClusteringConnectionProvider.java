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
package org.apache.maven.plugins.clustering.eclipse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.lsp4e.server.ProcessStreamConnectionProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Starts the code-clustering language server for LSP4E: the running JVM's {@code java} with this bundle's
 * {@code lib/*.jar} on the class path, in the workspace root. The server speaks LSP on standard input and output.
 */
public final class ClusteringConnectionProvider extends ProcessStreamConnectionProvider {
    static final String LIB = "lib";

    /** Builds the command when LSP4E starts the server, so a missing jar is reported then, not at load time. */
    @Override
    public void start() throws IOException {
        Bundle bundle = FrameworkUtil.getBundle(ClusteringConnectionProvider.class);
        List<Path> jars = jars(bundle);
        if (jars.isEmpty()) {
            throw new IOException("no server jars in " + LIB + "/ of bundle " + bundle.getSymbolicName()
                    + " (see its README)");
        }
        setCommands(ServerCommand.of(System.getProperties(), Path.of(System.getProperty("java.home")), jars));
        workspaceRoot().ifPresent(this::setWorkingDirectory);
        super.start();
    }

    /** The bundle's {@code lib/*.jar} entries as local files; a bundle installed as a jar extracts them first. */
    static List<Path> jars(Bundle bundle) throws IOException {
        Enumeration<URL> entries = bundle.findEntries(LIB, "*.jar", false);
        List<Path> jars = new ArrayList<>();
        if (entries == null) return jars;
        for (URL entry : Collections.list(entries)) jars.add(localFile(entry));
        Collections.sort(jars);
        return jars;
    }

    private static Path localFile(URL entry) throws IOException {
        try {
            return Path.of(URIUtil.toURI(FileLocator.toFileURL(entry)));
        } catch (URISyntaxException e) {
            throw new IOException("not a file: " + entry, e);
        }
    }

    private static Optional<String> workspaceRoot() {
        IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        return Optional.ofNullable(location).map(IPath::toOSString);
    }
}
