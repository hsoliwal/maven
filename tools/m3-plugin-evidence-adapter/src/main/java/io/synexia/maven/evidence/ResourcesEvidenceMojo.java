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
package io.synexia.maven.evidence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.plugin.descriptor.PluginDescriptor;
import org.apache.maven.api.services.M3PluginEvidenceService;
import org.apache.maven.api.services.M3PluginEvidenceService.Identity;
import org.apache.maven.api.services.M3PluginEvidenceService.Receipt;
import org.apache.maven.plugins.resources.ResourcesMojo;
import org.apache.maven.shared.filtering.DefaultMavenFileFilter;
import org.apache.maven.shared.filtering.DefaultMavenResourcesFiltering;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * Opt-in observations of the released Resources goal's original copy operations.
 * The inherited execute method retains source-root discovery and skip handling.
 */
@Mojo(name = "resources", defaultPhase = "process-resources", projectRequired = true)
public class ResourcesEvidenceMojo extends ResourcesMojo {
    private static final String UNSET_CONTEXT = "0".repeat(64);

    /** Enables the bounded observation profile; ordinary donor execution is the default. */
    @Parameter(property = "m3.evidence.enabled", defaultValue = "false")
    private boolean enabled;

    /** Selects one receipt format for an enabled invocation; operations are the default. */
    @Parameter(property = "m3.evidence.format", defaultValue = "operations")
    private String evidenceFormat;

    @Inject
    private MojoExecution mojoExecution;

    @Inject
    private MavenFileFilter fileFilter;

    @Inject
    private BuildContext buildContext;

    @Override
    protected void doExecute() throws MojoException {
        if (!enabled) {
            super.doExecute();
            return;
        }
        if (!ResourceInventoryProjection.DEFAULT_FORMAT.equals(evidenceFormat)
                && !ResourceInventoryProjection.FORMAT.equals(evidenceFormat)) {
            throw new MojoException("resource evidence format must be operations or resource-io");
        }

        if (mavenResourcesFiltering == null
                || mavenResourcesFiltering.getClass() != DefaultMavenResourcesFiltering.class
                || fileFilter == null
                || fileFilter.getClass() != DefaultMavenFileFilter.class) {
            throw new MojoException("resource evidence v1 requires the exact default filtering providers");
        }
        if (buildContext == null || buildContext.isIncremental()) {
            throw new MojoException("resource evidence v1 requires a non-incremental BuildContext");
        }
        if (getResources() == null || getResources().size() != 1) {
            throw new MojoException("resource evidence v1 requires exactly one resource root");
        }
        if (project == null || session == null || mojoExecution == null) {
            throw new MojoException("resource evidence requires the active project, session and MojoExecution");
        }
        // This is the same merged configuration used to populate the donor's
        // private mavenFilteringHints parameter; no reflection or field shadowing.
        if (mojoExecution.configuration()
                .map(configuration -> configuration.children().stream()
                        .anyMatch(child -> "mavenFilteringHints".equals(child.name())))
                .orElse(false)) {
            throw new MojoException("configured user filtering components are outside resource evidence v1");
        }

        try {
            Path projectRoot = EvidenceFiles.checked(project.basedir(), project.basedir().toAbsolutePath(), false);
            String buildDirectory = project.build().getDirectory();
            if (buildDirectory == null || buildDirectory.isBlank()) {
                throw new IOException("resource evidence requires the project's declared build directory");
            }
            Path evidenceDirectory =
                    EvidenceFiles.checked(projectRoot, projectRoot.resolve(buildDirectory).resolve("m3-evidence"), true);
            Identity invocation = invocation();
            M3PluginEvidenceService service = session.getService(M3PluginEvidenceService.class);
            RecordingFileFilter recorder = new RecordingFileFilter(projectRoot, fileFilter);
            ResourcesExecutionFiltering local = new ResourcesExecutionFiltering(
                    projectRoot,
                    evidenceDirectory,
                    (DefaultMavenResourcesFiltering) mavenResourcesFiltering,
                    recorder,
                    buildContext);
            long generation = nextGeneration(invocation);

            MavenResourcesFiltering original = mavenResourcesFiltering;
            List<ResourceEvidence.Operation> operations;
            mavenResourcesFiltering = local;
            try {
                super.doExecute();
                operations = recorder.finish();
            } finally {
                mavenResourcesFiltering = original;
            }

            String context = context(invocation, local.declaredContext(), operations);
            Identity identity = new Identity(
                    invocation.project(),
                    invocation.pluginGa(),
                    invocation.version(),
                    invocation.goal(),
                    invocation.execution(),
                    context,
                    generation);
            ResourceEvidence observed = new ResourceEvidence(identity, operations);
            if (ResourceInventoryProjection.FORMAT.equals(evidenceFormat)) {
                byte[] inventory = ResourceInventoryProjection.encode(observed);
                String inventorySha256 = EvidenceFiles.sha256(inventory);
                Path inventoryFile = EvidenceFiles.publish(
                        projectRoot, evidenceDirectory, "resources-" + inventorySha256 + ".tsv", inventory);
                Receipt receipt = new Receipt(
                        identity,
                        inventoryFile,
                        ResourceInventoryProjection.SCHEMA,
                        inventorySha256,
                        ResourceEvidenceCodec.CAPABILITIES);
                service.publish(session, receipt);
                M3PluginEvidenceService.Snapshot authenticated = service.read(
                                session, identity, ResourceInventoryProjection.SCHEMA, ResourceEvidenceCodec.CAPABILITIES)
                        .orElseThrow(() -> new IOException("resource inventory receipt missing after publication"));
                if (!receipt.equals(authenticated.receipt())
                        || !Arrays.equals(inventory, authenticated.bytes())) {
                    throw new IOException("resource inventory publication readback mismatch");
                }
                getLog().info("Partial resource input/output evidence (not cache eligible): " + inventoryFile);
            } else {
                byte[] envelope = ResourceEvidenceCodec.encode(observed);
                String envelopeSha256 = EvidenceFiles.sha256(envelope);
                Path receiptFile = EvidenceFiles.publish(
                        projectRoot, evidenceDirectory, "resources-" + envelopeSha256 + ".bin", envelope);
                service.publish(
                        session,
                        new Receipt(
                                identity,
                                receiptFile,
                                ResourceEvidenceCodec.SCHEMA,
                                envelopeSha256,
                                ResourceEvidenceCodec.CAPABILITIES));
                byte[] report = CapabilityReport.read(session, service, identity);
                Path reportFile = EvidenceFiles.publish(
                        projectRoot,
                        evidenceDirectory,
                        "resources-" + EvidenceFiles.sha256(report) + ".json",
                        report);
                getLog().info("Resource operation evidence: " + reportFile);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new MojoException("resource evidence failed: " + exception.getMessage(), exception);
        }
    }

    /** All producer coordinates come from the active adapter execution descriptor. */
    private Identity invocation() {
        PluginDescriptor plugin = mojoExecution.plugin().getDescriptor();
        return new Identity(
                project.getId(),
                plugin.getGroupId() + ":" + plugin.getArtifactId(),
                plugin.getVersion(),
                mojoExecution.goal(),
                mojoExecution.executionId().orElse("<direct-cli>"),
                UNSET_CONTEXT,
                1);
    }

    /** A session counter advances once per attempted enabled invocation, never on a publication retry. */
    private long nextGeneration(Identity invocation) throws IOException {
        String key = ResourcesEvidenceMojo.class.getName() + ".invocation." + EvidenceFiles.sha256(identityBytes(invocation));
        AtomicLong counter = session.getData()
                .computeIfAbsent(SessionData.key(AtomicLong.class, key), AtomicLong::new);
        long generation = counter.incrementAndGet();
        if (generation < 1) {
            throw new IOException("resource evidence invocation generation exhausted");
        }
        return generation;
    }

    /**
     * Binds declared configuration and actual observations with explicit framing.
     * It intentionally excludes timestamps and is never a complete build/cache key.
     */
    private String context(
            Identity invocation, byte[] declaration, List<ResourceEvidence.Operation> operations)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            text(output, "m3.resources.observed-context.v1");
            byte[] producer = identityBytes(invocation);
            output.writeInt(producer.length);
            output.write(producer);
            output.writeBoolean(mojoExecution.executionId().isPresent());
            output.writeInt(declaration.length);
            output.write(declaration);
            output.writeInt(operations.size());
            for (ResourceEvidence.Operation operation : operations) {
                text(output, operation.input());
                text(output, operation.output());
                text(output, operation.inputSha256());
                text(output, operation.outputSha256());
                output.writeLong(operation.inputBytes());
                output.writeLong(operation.outputBytes());
                output.writeBoolean(operation.delegateResult());
            }
        }
        return EvidenceFiles.sha256(bytes.toByteArray());
    }

    private static byte[] identityBytes(Identity identity) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            text(output, identity.project());
            text(output, identity.pluginGa());
            text(output, identity.version());
            text(output, identity.goal());
            text(output, identity.execution());
        }
        return bytes.toByteArray();
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException exception) {
            throw new IOException("malformed Unicode in resource invocation context", exception);
        }
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}

