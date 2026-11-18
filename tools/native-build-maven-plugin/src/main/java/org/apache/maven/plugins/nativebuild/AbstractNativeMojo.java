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

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import javax.inject.Inject;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;

/**
 * Parameters shared by every goal: what to build, where, with which tools. Property names use the
 * {@code native.} prefix.
 */
public abstract class AbstractNativeMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @Inject
    RepositorySystem repositorySystem;

    /** Skips every native goal. */
    @Parameter(property = "native.skip", defaultValue = "false")
    boolean skip;

    /** The folder with the native build files; only this folder is searched, not its sub-folders. */
    @Parameter(property = "native.sourceDirectory", defaultValue = "${project.basedir}")
    File sourceDirectory;

    /** cmake, cmake-presets, meson, autotools, ninja or make; empty to detect. */
    @Parameter(property = "native.buildSystem")
    String buildSystem;

    /**
     * The attached artifacts' classifier; empty for os-arch in the os-maven-plugin vocabulary (plus the runtime the
     * configured build uses on Windows, -msvc or -mingw). Letters, digits, '_', '.' and '-' only.
     */
    @Parameter(property = "native.classifier")
    String classifier;

    /** CMake build type (Meson maps it). */
    @Parameter(property = "native.buildType", defaultValue = "Release")
    String buildType;

    /** Parallel jobs; 0 for the processors divided by the reactor's threads ({@code -T}). */
    @Parameter(property = "native.jobs", defaultValue = "0")
    int jobs;

    /** Timeout of each native step, 1 to 43200 seconds (default 4 hours). */
    @Parameter(property = "native.timeoutSeconds", defaultValue = "14400")
    int timeoutSeconds;

    /**
     * Skips steps whose declared inputs are unchanged (polyglot content stamps in .m3/polyglot-stamps). A run without
     * it deletes the stamps of the steps it runs, so a later incremental run never trusts a stale one.
     */
    @Parameter(property = "native.incremental", defaultValue = "false")
    boolean incremental;

    /** Root of the build directories; each build uses {@code <classifier>} below it, the stage {@code stage/}. */
    @Parameter(property = "native.buildDirectory", defaultValue = "${project.build.directory}/native")
    File buildDirectory;

    /** Normalized surefire-format test reports. */
    @Parameter(property = "native.reportsDirectory", defaultValue = "${project.build.directory}/native-reports")
    File reportsDirectory;

    /** Extra configure arguments. */
    @Parameter
    List<String> configureArgs;

    /** Extra build arguments. */
    @Parameter
    List<String> buildArgs;

    /** Extra test arguments. */
    @Parameter
    List<String> testArgs;

    /** Extra install arguments. */
    @Parameter
    List<String> installArgs;

    /** Project-relative build outputs; enables content stamps for the build step and stages them without install. */
    @Parameter
    List<String> outputs;

    /** CMake preset used for configure, build and test. */
    @Parameter(property = "native.cmake.preset")
    String cmakePreset;

    /** CMake generator, such as Ninja or "Visual Studio 17 2022". */
    @Parameter(property = "native.cmake.generator")
    String cmakeGenerator;

    /** Resolves a CMake release through the local repository instead of PATH. */
    @Parameter(property = "native.cmake.provision", defaultValue = "false")
    boolean cmakeProvision;

    /** Version of the provisioned CMake artifact. */
    @Parameter(property = "native.cmake.version", defaultValue = "3.31.5-b1")
    String cmakeVersion;

    /**
     * SHA-256 the provisioned CMake archive must have; empty for the plugin's pin of the default version. Any other
     * version needs it: an archive whose hash differs is never unpacked.
     */
    @Parameter(property = "native.cmake.sha256")
    String cmakeSha256;

    /** Group of the provisioned CMake artifact (artifactId cmake-binaries, classifier per platform). */
    @Parameter(property = "native.cmake.provisionGroupId", defaultValue = "com.googlecode.cmake-maven-project")
    String cmakeProvisionGroupId;

    /** make, gmake, mingw32-make or nmake. */
    @Parameter(property = "native.make.tool")
    String makeTool;

    /** The makefile; empty for the detected one. */
    @Parameter(property = "native.make.file")
    String makeFile;

    /** Build targets (Make, Ninja, CMake --target). */
    @Parameter(property = "native.make.targets")
    List<String> makeTargets;

    /** Make or Ninja test target; empty for check or test when the makefile declares it. */
    @Parameter(property = "native.make.testTarget")
    String makeTestTarget;

    /** auto (only without configure), always or never. */
    @Parameter(property = "native.autotools.autoreconf", defaultValue = "auto")
    String autoreconf;

    /** Autotools --prefix; stripped again from the stage. */
    @Parameter(property = "native.autotools.prefix", defaultValue = "/usr/local")
    String prefix;

    /** Tool paths or names by logical name: cmake, ctest, make, ninja, meson, sh, cc, cxx, bear. */
    @Parameter
    Map<String, String> tools;

    /** Reads cmake, make, ninja, meson, cc, cxx ... entries from toolchains.xml. */
    @Parameter(property = "native.toolchains", defaultValue = "true")
    boolean toolchains;

    /** provides values a toolchains.xml entry must have, such as version. */
    @Parameter
    Map<String, String> toolchainRequirements;

    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("native build skipped (native.skip)");
            return;
        }
        try {
            runNative();
        } catch (InterruptedException interrupt) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("native build interrupted", interrupt);
        } catch (IOException | RuntimeException failure) {
            throw new MojoExecutionException("native build failed: " + failure.getMessage(), failure);
        }
    }

    /** The goal's work. */
    protected abstract void runNative()
            throws IOException, InterruptedException, MojoExecutionException, MojoFailureException;

    /** Detects, resolves tools and plans for {@code use}. */
    NativeSession open(NativeSession.Use use) throws IOException {
        return NativeSession.open(this, use);
    }

    /** Logs at info level; under {@code -T} the line starts with {@code [artifactId]}, like the tool output lines. */
    void info(String line) {
        getLog().info(modulePrefix() + line);
    }

    /** {@code [artifactId] } when the reactor builds modules in parallel, else empty. */
    String modulePrefix() {
        boolean parallel = session != null && session.getRequest() != null
                && session.getRequest().getDegreeOfConcurrency() > 1;
        return parallel ? "[" + project.getArtifactId() + "] " : "";
    }

    /** Refuses a timeout outside 1 s to 12 h, the polyglot runner's range. */
    void checkTimeout() {
        if (timeoutSeconds < 1 || timeoutSeconds > 43_200) {
            throw new IllegalArgumentException("native.timeoutSeconds must be between 1 and 43200");
        }
    }
}
