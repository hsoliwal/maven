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
package org.apache.maven.plugins.nativebuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.nativebuild.engine.DeterministicZip;
import org.apache.maven.plugins.nativebuild.engine.Layout;
import org.apache.maven.plugins.nativebuild.engine.StageCollector;
import org.apache.maven.project.MavenProjectHelper;

/**
 * Attaches the stage in package as a zip with the platform classifier (or {@code <classifier>-lib},
 * {@code -bin} and {@code -headers} with split), so Maven's install and deploy carry it. Entries are sorted and
 * carry {@code project.build.outputTimestamp}, so an unchanged stage gives a byte-identical archive.
 */
@Mojo(name = "attach", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public final class AttachMojo extends AbstractNativeMojo {
    @Inject
    MavenProjectHelper projectHelper;

    /** Attaches the stage. */
    @Parameter(property = "native.attach", defaultValue = "true")
    boolean attach;

    /** Attaches libraries, executables and headers as three archives. */
    @Parameter(property = "native.attach.split", defaultValue = "false")
    boolean split;

    /** Entry time of the archives. */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    String outputTimestamp;

    @Override
    protected void runNative() throws IOException {
        NativeSession session = attach ? open(false) : null;
        if (session == null || !session.found()) {
            return;
        }
        Layout layout = session.context().layout();
        StageCollector.Staged staged = StageCollector.classify(StageCollector.root(layout.path(layout.stage()), prefix));
        if (staged.all().isEmpty()) {
            getLog().warn("nothing staged in " + staged.root() + "; the stage goal (prepare-package) fills it");
        } else {
            attachAll(staged, session.classifier());
        }
    }

    private void attachAll(StageCollector.Staged staged, String classifier) throws IOException {
        if (!split) {
            attach(staged, classifier, staged.all());
            return;
        }
        attach(staged, classifier + "-lib", Stream.of(staged.staticLibraries(), staged.sharedLibraries(),
                staged.other()).flatMap(List::stream).toList());
        attach(staged, classifier + "-bin", staged.executables());
        attach(staged, classifier + "-headers", staged.headers());
    }

    private void attach(StageCollector.Staged staged, String classifier, List<String> files) throws IOException {
        if (files.isEmpty()) {
            return;
        }
        Path zip = Path.of(project.getBuild().getDirectory(), project.getBuild().getFinalName() + "-" + classifier
                + ".zip");
        DeterministicZip.write(zip, staged.root(), files, DeterministicZip.epoch(outputTimestamp));
        projectHelper.attachArtifact(project, "zip", classifier, zip.toFile());
        getLog().info("ATTACHED zip:" + classifier + " " + zip + " (" + files.size() + " files, sha256 " + sha256(zip)
                + ")");
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
