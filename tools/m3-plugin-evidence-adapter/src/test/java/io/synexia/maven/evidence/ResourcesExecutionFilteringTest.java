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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.maven.shared.filtering.ChangeDetection;
import org.apache.maven.shared.filtering.DefaultMavenFileFilter;
import org.apache.maven.shared.filtering.DefaultMavenResourcesFiltering;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.plexus.build.incremental.DefaultBuildContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Qualifies guarded selection against the actual pinned Resources filtering engine. */
class ResourcesExecutionFilteringTest {
    @TempDir
    Path directory;

    @Test
    void actualScannerIncludesExcludesAndEmptyDeletionSelectionArePreserved() throws Exception {
        Path project = project();
        Path source = project.resolve("src/main/resources");
        write(source.resolve("keep.txt"), "alpha");
        write(source.resolve("nested/keep.txt"), "beta");
        write(source.resolve("nested/skip.txt"), "excluded by configured pattern");
        write(source.resolve("ignore.bin"), "not selected by include pattern");
        write(source.resolve(".git/hidden.txt"), "excluded by original default excludes");
        Path baseline = project.resolve("target/baseline");
        Path output = project.resolve("target/classes");
        write(baseline.resolve("stale.txt"), "retained by non-incremental donor");
        write(output.resolve("stale.txt"), "retained by non-incremental donor");

        Resource baselineResource = selectedResource(source);
        original().filterResources(request(project, baselineResource, baseline));
        Adapter adapter = adapter(project);
        adapter.filtering().filterResources(request(project, selectedResource(source), output));

        List<String> expected = List.of("keep.txt", "nested/keep.txt", "stale.txt");
        assertEquals(expected, files(baseline));
        assertEquals(expected, files(output));
        for (String relative : expected) {
            assertArrayEquals(Files.readAllBytes(baseline.resolve(relative)), Files.readAllBytes(output.resolve(relative)));
        }
        List<ResourceEvidence.Operation> operations = adapter.recorder().finish();
        assertEquals(
                List.of("src/main/resources/keep.txt", "src/main/resources/nested/keep.txt"),
                operations.stream().map(ResourceEvidence.Operation::input).toList());
        assertEquals(
                List.of("target/classes/keep.txt", "target/classes/nested/keep.txt"),
                operations.stream().map(ResourceEvidence.Operation::output).toList());
        // Small CONTENT copies produce bytes while the pinned buffered donor returns false.
        assertFalse(operations.stream().anyMatch(ResourceEvidence.Operation::delegateResult));
        assertFalse(Files.exists(output.resolve("nested/skip.txt")));
        assertFalse(Files.exists(output.resolve("ignore.bin")));
        assertFalse(Files.exists(output.resolve(".git/hidden.txt")));
    }

    @Test
    void sourceAndOutputTreesCannotOverlapInEitherDirection() throws Exception {
        Path project = project();
        Path source = project.resolve("content/resources");
        byte[] original = bytes("preserved source");
        Path file = write(source.resolve("input.txt"), original);
        List<String> before = files(project);

        for (Path output : List.of(source, source.resolve("generated"), source.getParent())) {
            Adapter adapter = adapter(project);
            MavenResourcesExecution request = request(project, resource(source), output);
            assertThrows(MavenFilteringException.class, () -> adapter.filtering().filterResources(request));
            assertEquals(before, files(project));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
        assertFalse(Files.exists(source.resolve("generated")));
    }

    @Test
    void unsupportedProfileOptionsFailBeforeCreatingTheOutputTree() throws Exception {
        Path project = project();
        Path source = project.resolve("src/main/resources");
        Path input = write(source.resolve("input.txt"), "preserved");
        List<Consumer<MavenResourcesExecution>> unsupported = List.of(
                request -> request.getResources().get(0).setTargetPath("placed"),
                request -> request.getResources().get(0).setTargetPath(""),
                request -> request.getResources().get(0).setFiltering(true),
                request -> request.setIncludeEmptyDirs(true),
                request -> request.setFlatten(true),
                request -> request.setFilterFilenames(true));

        for (int index = 0; index < unsupported.size(); index++) {
            Path output = project.resolve("target/unsupported-" + index);
            MavenResourcesExecution request = request(project, resource(source), output);
            unsupported.get(index).accept(request);
            Adapter adapter = adapter(project);
            MavenFilteringException failure = assertThrows(
                    MavenFilteringException.class, () -> adapter.filtering().filterResources(request));
            assertTrue(failure.getMessage().contains("requires non-filtering, no targetPath"));
            assertFalse(Files.exists(output));
            assertArrayEquals(bytes("preserved"), Files.readAllBytes(input));
        }
    }

    @Test
    void selectedDestinationSymlinkIsRejectedBeforeAnyChildDirectoryOrCopy() throws Exception {
        Path project = project();
        Path source = project.resolve("src/main/resources");
        write(source.resolve("first.txt"), "must not be copied before complete scan validation");
        write(source.resolve("assets/deep/value.txt"), "must not escape");
        Path output = project.resolve("target/classes");
        Files.createDirectories(output);
        Path outside = directory.resolve("outside-destination");
        Path sentinel = write(outside.resolve("sentinel.txt"), "unchanged outside project");
        Files.createSymbolicLink(output.resolve("assets"), outside);
        Adapter adapter = adapter(project);
        MavenResourcesExecution request = request(project, resource(source), output);

        assertThrows(MavenFilteringException.class, () -> adapter.filtering().filterResources(request));
        assertFalse(Files.exists(outside.resolve("deep")));
        assertFalse(Files.exists(output.resolve("first.txt")));
        assertArrayEquals(bytes("unchanged outside project"), Files.readAllBytes(sentinel));
        assertTrue(Files.isSymbolicLink(output.resolve("assets")));
    }

    @Test
    void selectedSourceSymlinkIsRejectedBeforeAnySelectedFileIsCopied() throws Exception {
        Path project = project();
        Path source = project.resolve("src/main/resources");
        write(source.resolve("regular.txt"), "must remain unprocessed");
        Path outside = write(directory.resolve("outside-source.txt"), "external source content");
        Files.createSymbolicLink(source.resolve("linked.txt"), outside);
        Resource resource = resource(source);
        resource.setIncludes(List.of("*.txt"));
        Path output = project.resolve("target/classes");
        Adapter adapter = adapter(project);
        MavenResourcesExecution request = request(project, resource, output);

        assertThrows(MavenFilteringException.class, () -> adapter.filtering().filterResources(request));
        assertFalse(Files.exists(output.resolve("regular.txt")));
        assertFalse(Files.exists(output.resolve("linked.txt")));
        assertTrue(Files.isSymbolicLink(source.resolve("linked.txt")));
        assertArrayEquals(bytes("external source content"), Files.readAllBytes(outside));
    }

    @Test
    void missingDeclaredSourceRetainsTheOriginalZeroOperationBehavior() throws Exception {
        Path project = project();
        Path absentSource = project.resolve("src/missing-resources");
        Path baselineOutput = project.resolve("target/missing-baseline");
        Path adapterOutput = project.resolve("target/missing-adapter");
        original().filterResources(request(project, resource(absentSource), baselineOutput));
        Adapter adapter = adapter(project);
        adapter.filtering().filterResources(request(project, resource(absentSource), adapterOutput));

        assertEquals(List.of(), adapter.recorder().finish());
        assertFalse(Files.exists(absentSource));
        assertFalse(Files.exists(baselineOutput));
        assertFalse(Files.exists(adapterOutput));
        byte[] declaration = adapter.filtering().declaredContext();
        assertTrue(declaration.length > 0);
        byte first = declaration[0];
        declaration[0] ^= 1;
        assertEquals(first, adapter.filtering().declaredContext()[0]);
    }

    @Test
    void malformedUnicodeConfigurationIsRejectedBeforeTheDonorCreatesOutput() throws Exception {
        Path project = project();
        Path source = project.resolve("src/main/resources");
        write(source.resolve("input.txt"), "unchanged");
        Resource resource = resource(source);
        resource.setIncludes(List.of("**/" + (char) 0xd800 + "*.txt"));
        Path output = project.resolve("target/unicode-rejected");
        Adapter adapter = adapter(project);
        MavenResourcesExecution request = request(project, resource, output);

        assertThrows(MavenFilteringException.class, () -> adapter.filtering().filterResources(request));
        assertFalse(Files.exists(output));
        assertArrayEquals(bytes("unchanged"), Files.readAllBytes(source.resolve("input.txt")));
    }

    private Path project() throws IOException {
        return Files.createDirectories(directory.resolve("project"));
    }

    private static Adapter adapter(Path project) throws IOException {
        DefaultBuildContext context = new DefaultBuildContext();
        DefaultMavenFileFilter files = new DefaultMavenFileFilter(context);
        DefaultMavenResourcesFiltering original = new DefaultMavenResourcesFiltering(files, context);
        RecordingFileFilter recorder = new RecordingFileFilter(project, files);
        ResourcesExecutionFiltering filtering = new ResourcesExecutionFiltering(
                project, project.resolve("target/m3-evidence"), original, recorder, context);
        return new Adapter(filtering, recorder);
    }

    private static DefaultMavenResourcesFiltering original() {
        DefaultBuildContext context = new DefaultBuildContext();
        return new DefaultMavenResourcesFiltering(new DefaultMavenFileFilter(context), context);
    }

    private static MavenResourcesExecution request(Path project, Resource resource, Path output) {
        MavenResourcesExecution request = new MavenResourcesExecution(
                List.of(resource), output, "UTF-8", List.of(), project, List.of());
        request.setChangeDetection(ChangeDetection.CONTENT);
        return request;
    }

    private static Resource resource(Path source) {
        Resource resource = new Resource();
        resource.setDirectory(source.toString());
        resource.setFiltering(false);
        return resource;
    }

    private static Resource selectedResource(Path source) {
        Resource resource = resource(source);
        resource.setIncludes(List.of("**/*.txt"));
        resource.setExcludes(List.of("**/skip.txt"));
        return resource;
    }

    private static Path write(Path file, String content) throws IOException {
        return write(file, bytes(content));
    }

    private static Path write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, content);
    }

    private static List<String> files(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .toList();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private record Adapter(ResourcesExecutionFiltering filtering, RecordingFileFilter recorder) {}
}
