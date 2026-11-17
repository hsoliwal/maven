/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// SPDX-License-Identifier: Apache-2.0
package io.synexia.maven.smart;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Emits a deterministic review plan. Only explicitly configured providers are invoked. */
@Mojo(name = "plan", threadSafe = true, requiresProject = true)
public final class PlanMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDir;
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;
    @Parameter(property = "m3.smart.manifest")
    private File manifest;
    @Parameter(property = "m3.smart.profile", defaultValue = "none")
    private String profile;
    @Parameter(property = "m3.smart.candidateFile")
    private File candidateFile;
    @Parameter(property = "m3.smart.llmBackend")
    private String llmBackend;
    @Parameter(property = "m3.smart.chromeLlmJar")
    private File chromeLlmJar;
    @Parameter(property = "m3.smart.llamaBin")
    private File llamaBin;
    @Parameter(property = "m3.smart.jniLib")
    private File jniLib;
    @Parameter(property = "m3.smart.modelPath")
    private File modelPath;
    @Parameter(property = "m3.smart.llmTimeoutSeconds", defaultValue = "120")
    private int llmTimeoutSeconds;

    @Override public void execute() throws MojoExecutionException {
        try {
            if (llmBackend != null && candidateFile != null) throw new IllegalArgumentException("choose exactly one advisory input");
            ArtifactManifest m = manifest == null ? ArtifactManifest.empty() : ArtifactManifest.load(manifest.toPath());
            Path pom = baseDir.toPath().resolve("pom.xml");
            String candidate = "";
            if (candidateFile != null) {
                if (!Files.isRegularFile(candidateFile.toPath()) || Files.size(candidateFile.toPath()) > 32768)
                    throw new IllegalArgumentException("candidate input missing or over 32 KiB");
                candidate = Files.readString(candidateFile.toPath(), StandardCharsets.UTF_8);
            } else if (llmBackend != null) {
                File bin = "jni".equals(llmBackend) ? jniLib : llamaBin;
                McpAdviser.Config config = new McpAdviser.Config(
                        chromeLlmJar == null ? null : chromeLlmJar.toPath(),
                        llmBackend, bin == null ? null : bin.toPath(),
                        modelPath == null ? null : modelPath.toPath(), llmTimeoutSeconds);
                candidate = McpAdviser.propose(config, PlanEngine.prompt(pom, m, profile));
            }
            String json = PlanEngine.plan(pom, m, profile, candidate);
            Path output = SafeBuildOutput.directory(baseDir.toPath(), buildDir.toPath());
            Path receipt = SafeBuildOutput.write(output, "plan.json", json);
            getLog().info("M3 review-only plan: " + receipt);
        } catch (Exception failure) {
            throw new MojoExecutionException("M3 planning failed: " + failure.getClass().getSimpleName(), failure);
        }
    }
}
