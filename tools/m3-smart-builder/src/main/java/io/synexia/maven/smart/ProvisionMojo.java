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

package io.synexia.maven.smart;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Explicitly admitted artifact cache provisioning; never unpacks or executes a binary. */
@Mojo(name = "provision", threadSafe = true, requiresProject = true)
public final class ProvisionMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDir;
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;
    @Parameter(property = "m3.smart.manifest", required = true)
    private File manifest;
    @Parameter(property = "m3.smart.profile", required = true)
    private String profile;
    @Parameter(property = "m3.smart.cacheDir", required = true)
    private File cacheDir;
    @Parameter(property = "m3.smart.approveProvision", defaultValue = "false")
    private boolean approveProvision;
    @Parameter(defaultValue = "${settings.offline}", readonly = true)
    private boolean offline;

    @Override public void execute() throws MojoExecutionException {
        if (!approveProvision) throw new MojoExecutionException("explicit -Dm3.smart.approveProvision=true required");
        try {
            if (manifest == null || profile == null || cacheDir == null) throw new IllegalArgumentException("missing explicit manifest, profile or cache");
            ArtifactManifest entries = ArtifactManifest.load(manifest.toPath());
            List<ArtifactManifest.Entry> selected = entries.selected(profile);
            if (selected.isEmpty()) throw new IllegalArgumentException("no artifacts selected for named profile");
            ArtifactStore store = new ArtifactStore(cacheDir.toPath(), ArtifactStore.httpsTransfer());
            ArrayList<String> receipts = new ArrayList<>();
            for (ArtifactManifest.Entry entry : selected) {
                Path artifact = store.verifyOrAcquire(entry, offline);
                receipts.add("    {\"id\": " + TinyJson.quote(entry.id()) + ", \"sha256\": "
                        + TinyJson.quote(entry.sha256()) + ", \"cacheFile\": "
                        + TinyJson.quote(artifact.getFileName().toString()) + "}");
                getLog().info("Verified " + entry.id() + " SHA-256 " + entry.sha256() + " -> " + artifact);
            }
            Path directory = SafeBuildOutput.directory(baseDir.toPath(), buildDir.toPath());
            String evidence = "{\n  \"schema\": \"m3.smart.provision.v1\",\n  \"manifestSha256\": "
                    + TinyJson.quote(entries.digest()) + ",\n  \"profile\": "
                    + TinyJson.quote(profile) + ",\n  \"verifiedArtifacts\": [\n"
                    + String.join(",\n", receipts) + "\n  ]\n}\n";
            SafeBuildOutput.write(directory, "provision.json", evidence);
        } catch (Exception failure) {
            throw new MojoExecutionException("M3 provisioning failed: " + failure.getClass().getSimpleName(), failure);
        }
    }
}
