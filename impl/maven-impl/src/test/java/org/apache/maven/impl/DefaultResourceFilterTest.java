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
package org.apache.maven.impl;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.Language;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.ProtoSession;
import org.apache.maven.api.SourceRoot;
import org.apache.maven.api.model.Resource;
import org.apache.maven.api.services.Interpolator;
import org.apache.maven.api.services.PathMatcherFactory;
import org.apache.maven.api.services.ResourceFilter;
import org.apache.maven.api.services.ResourceFilterException;
import org.apache.maven.api.services.ResourceFilterRequest;
import org.apache.maven.api.services.ResourceFilterRequest.ChangeDetection;
import org.apache.maven.api.services.ResourceFilterResult;
import org.apache.maven.api.services.ResourceFilterResult.CopiedFile;
import org.apache.maven.di.Injector;
import org.apache.maven.impl.model.DefaultInterpolator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultResourceFilterTest {

    @TempDir
    Path temp;

    private final DefaultResourceFilter filter =
            new DefaultResourceFilter(new DefaultInterpolator(), new DefaultPathMatcherFactory());

    private ProtoSession session(Map<String, String> user, Map<String, String> system) {
        return ProtoSession.newBuilder()
                .withTopDirectory(temp)
                .withUserProperties(user)
                .withSystemProperties(system)
                .build();
    }

    private ResourceFilterRequest.ResourceFilterRequestBuilder builder() {
        return ResourceFilterRequest.builder().session(session(Map.of(), Map.of()));
    }

    private static SourceRoot root(Path base, String directory, boolean filtering, String targetPath) {
        Resource.Builder resource = Resource.newBuilder().directory(directory).filtering(Boolean.toString(filtering));
        if (targetPath != null) {
            resource.targetPath(targetPath);
        }
        return new DefaultSourceRoot(base, ProjectScope.MAIN, resource.build());
    }

    private Path write(String path, String content) {
        try {
            Path file = temp.resolve(path);
            Files.createDirectories(file.getParent());
            return Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(String path) throws IOException {
        return Files.readString(temp.resolve(path));
    }

    @Test
    void mavenInjectorResolvesTheService() {
        Injector injector = Injector.create();
        try {
            injector.bindInstance(Interpolator.class, new DefaultInterpolator());
            injector.bindInstance(PathMatcherFactory.class, new DefaultPathMatcherFactory());
            injector.bindImplicit(DefaultResourceFilter.class);
            ResourceFilter service = injector.getInstance(ResourceFilter.class);
            assertInstanceOf(DefaultResourceFilter.class, service);
            assertSame(service, injector.getInstance(ResourceFilter.class));
        } finally {
            injector.dispose();
        }
    }

    @Test
    void lookupOrderIsPropertiesThenUserThenSystemThenCallback() {
        ResourceFilterRequest request = ResourceFilterRequest.builder()
                .session(session(Map.of("u", "user", "s", "user-wins"), Map.of("s", "system", "y", "sys")))
                .properties(Map.of("u", "property-wins"))
                .callback(name -> name.equals("c") || name.equals("y") ? "callback-" + name : null)
                .build();
        assertEquals(
                "property-wins user-wins sys callback-c ${none}",
                filter.filter("${u} ${s} ${y} ${c} ${none}", request));
    }

    @Test
    void interpolatorOperatorsAndNestedValuesApply() {
        ResourceFilterRequest request = builder()
                .properties(Map.of("a", "A", "nested", "[${a}]", "blank", ""))
                .build();
        assertEquals("A x [A] y", filter.filter("${a:-z} ${blank:-x} ${nested} ${a:+y}", request));
        assertEquals("q and q", filter.filter("@b:-q@ and ${b:-q}", request));
    }

    @Test
    void longExpressionsAreCopiedAsWritten() {
        String name = "k".repeat(300);
        ResourceFilterRequest request =
                builder().properties(Map.of(name, "V", "a", "A")).build();
        assertEquals("${" + name + "} A", filter.filter("${" + name + "} ${a}", request));
    }

    @Test
    void customDelimitersAndEscapeString() {
        ResourceFilterRequest request = builder()
                .properties(Map.of("a", "A"))
                .delimiters(List.of("#{*}", "@"))
                .escapeString("\\")
                .build();
        assertEquals("A A ${a} #{a} @a@", filter.filter("#{a} @a@ ${a} \\#{a} \\@a@", request));
    }

    @Test
    void multiLineExpressionsNeedTheOption() {
        ResourceFilterRequest single =
                builder().properties(Map.of("a\nb", "AB")).build();
        ResourceFilterRequest multi = builder()
                .properties(Map.of("a\nb", "AB"))
                .supportMultiLineFiltering(true)
                .build();
        assertEquals("@a\nb@", filter.filter("@a\nb@", single));
        assertEquals("AB", filter.filter("@a\nb@", multi));
    }

    @Test
    void cycleFailsWithTheFileName() throws IOException {
        write("src/a.txt", "${loop}");
        ResourceFilterRequest request = builder()
                .roots(List.of(root(temp, "src", true, null)))
                .outputDirectory(temp.resolve("out"))
                .properties(Map.of("loop", "${loop}"))
                .build();
        ResourceFilterException e = assertThrows(ResourceFilterException.class, () -> filter.copy(request));
        assertTrue(e.getMessage().startsWith("filtering " + temp.resolve("src/a.txt")), e.getMessage());
    }

    @Test
    void readerHandlesManyEmptyValuesAndSmallBuffers() throws IOException {
        ResourceFilterRequest request = builder().properties(Map.of("e", "")).build();
        String text = "${e}".repeat(100_000) + "end";
        assertEquals("end", filter.filter(text, request));
        try (Reader reader = filter.filter(new StringReader("x${e}y"), request)) {
            char[] buffer = new char[1];
            assertEquals(1, reader.read(buffer, 0, 1));
            assertEquals('x', buffer[0]);
            assertEquals('y', reader.read());
            assertEquals(-1, reader.read());
        }
    }

    @Test
    void copyUsesTargetPathsFromSourceRoots() throws IOException {
        List.of("res/a.txt", "meta/b.txt", "abs/c.txt").forEach(path -> write(path, "${v}"));
        List<SourceRoot> roots = List.of(
                root(temp, "res", true, null),
                root(temp, "meta", true, "META-INF/x"),
                root(temp, "abs", false, temp.resolve("elsewhere").toString()));
        ResourceFilterRequest request = builder()
                .roots(roots)
                .outputDirectory(temp.resolve("out"))
                .properties(Map.of("v", "V"))
                .build();
        ResourceFilterResult result = filter.copy(request);
        assertEquals(
                "V V ${v}", read("out/a.txt") + " " + read("out/META-INF/x/b.txt") + " " + read("elsewhere/c.txt"));
        assertEquals(3, result.getWrittenFiles().size());
        assertSame(request, result.getRequest());
    }

    @Test
    void includesExcludesAndDefaultExcludes() throws IOException {
        write("res/keep.txt", "k");
        write("res/skip.log", "s");
        write("res/sub/deep.txt", "d");
        write("res/.git/config", "g");
        Resource resource = Resource.newBuilder()
                .directory("res")
                .includes(List.of("**/*.txt", "**/config"))
                .excludes(List.of("sub/**"))
                .build();
        ResourceFilterRequest request = builder()
                .roots(List.of(new DefaultSourceRoot(temp, ProjectScope.MAIN, resource)))
                .outputDirectory(temp.resolve("out"))
                .build();
        List<Path> written = filter.copy(request).getWrittenFiles();
        assertEquals(List.of(temp.resolve("out/keep.txt")), written);
    }

    @Test
    void nonFilteredExtensionsAreCaseInsensitiveAndExtensible() throws IOException {
        write("res/img.PNG", "${v}");
        write("res/doc.Bin", "${v}");
        write("res/doc.txt", "${v}");
        ResourceFilterRequest request = builder()
                .roots(List.of(root(temp, "res", true, null)))
                .outputDirectory(temp.resolve("out"))
                .properties(Map.of("v", "V"))
                .nonFilteredFileExtensions(List.of("BIN"))
                .build();
        List<CopiedFile> files = filter.copy(request).getFiles();
        assertEquals("${v}${v}V", read("out/img.PNG") + read("out/doc.Bin") + read("out/doc.txt"));
        // copy order is by path: doc.Bin, doc.txt, img.PNG
        assertEquals(
                List.of(false, true, false),
                files.stream().map(CopiedFile::filtered).toList());
    }

    @Test
    void propertiesFilesUseThePropertiesEncoding() throws IOException {
        Files.createDirectories(temp.resolve("res"));
        Files.write(temp.resolve("res/a.properties"), "k=\u00e9${v}".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(temp.resolve("res/a.txt"), "k=\u00e9${v}".getBytes(StandardCharsets.UTF_8));
        ResourceFilterRequest request = builder()
                .roots(List.of(root(temp, "res", true, null)))
                .outputDirectory(temp.resolve("out"))
                .properties(Map.of("v", "\u20ac"))
                .propertiesEncoding(StandardCharsets.ISO_8859_1)
                .build();
        filter.copy(request);
        assertEquals(
                "k=\u00e9?",
                new String(Files.readAllBytes(temp.resolve("out/a.properties")), StandardCharsets.ISO_8859_1));
        assertEquals("k=\u00e9\u20ac", read("out/a.txt"));
    }

    @Test
    void firstRootWinsUnlessAlways() throws IOException {
        write("one/a.txt", "one");
        write("two/a.txt", "two");
        List<SourceRoot> roots = List.of(root(temp, "one", false, null), root(temp, "two", false, null));
        ResourceFilterRequest.ResourceFilterRequestBuilder builder =
                builder().roots(roots).outputDirectory(temp.resolve("out"));
        ResourceFilterResult result = filter.copy(builder.build());
        assertEquals("one", read("out/a.txt"));
        assertEquals(
                List.of(true, false),
                result.getFiles().stream().map(CopiedFile::written).toList());
        filter.copy(builder.changeDetection(ChangeDetection.ALWAYS).build());
        assertEquals("two", read("out/a.txt"));
    }

    @Test
    void flattenCollisionFailsUnlessAlways() throws IOException {
        write("res/x/a.txt", "x");
        write("res/y/a.txt", "y");
        ResourceFilterRequest.ResourceFilterRequestBuilder builder = builder()
                .roots(List.of(root(temp, "res", false, null)))
                .outputDirectory(temp.resolve("out"))
                .flatten(true);
        ResourceFilterException e = assertThrows(ResourceFilterException.class, () -> filter.copy(builder.build()));
        assertTrue(e.getMessage().contains("overwrite was not set to true"), e.getMessage());
        filter.copy(builder.changeDetection(ChangeDetection.ALWAYS).build());
        assertEquals("y", read("out/a.txt"));
    }

    /** A root that {@code DefaultSourceRoot(Path, ProjectScope, Resource)} cannot express: disabled or relative. */
    private static SourceRoot plainRoot(Path directory, boolean enabled) {
        return new DefaultSourceRoot(
                ProjectScope.MAIN, Language.RESOURCES, null, null, directory, null, null, false, null, false, enabled);
    }

    @Test
    void emptyDirectoriesAreCreatedOnRequest() throws IOException {
        Files.createDirectories(temp.resolve("res/empty/inner"));
        write("res/a.txt", "a");
        ResourceFilterRequest request = builder()
                .roots(List.of(root(temp, "res", false, null)))
                .outputDirectory(temp.resolve("out"))
                .includeEmptyDirs(true)
                .build();
        assertEquals(1, filter.copy(request).getFiles().size());
        assertTrue(Files.isDirectory(temp.resolve("out/empty/inner")));
    }

    @Test
    void missingAndDisabledRootsAreSkipped() {
        write("res/a.txt", "a");
        List<SourceRoot> roots = List.of(root(temp, "missing", false, null), plainRoot(temp.resolve("res"), false));
        ResourceFilterRequest request =
                builder().roots(roots).outputDirectory(temp.resolve("out")).build();
        assertEquals(List.of(), filter.copy(request).getFiles());
        assertFalse(Files.isDirectory(temp.resolve("out")));
    }

    @Test
    void relativeRootDirectoriesUseTheBaseDirectory() throws IOException {
        write("base/res/a.txt", "a");
        ResourceFilterRequest request = builder()
                .roots(List.of(plainRoot(Path.of("res"), true)))
                .baseDirectory(temp.resolve("base"))
                .outputDirectory(temp.resolve("out"))
                .build();
        filter.copy(request);
        assertEquals("a", read("out/a.txt"));
    }

    @Test
    void contentModeKeepsUnchangedTargets() throws IOException {
        write("res/a.txt", "${v}");
        ResourceFilterRequest request = builder()
                .roots(List.of(root(temp, "res", true, null)))
                .outputDirectory(temp.resolve("out"))
                .properties(Map.of("v", "V"))
                .build();
        assertEquals(1, filter.copy(request).getWrittenFiles().size());
        assertEquals(0, filter.copy(request).getWrittenFiles().size());
        temp.resolve("out/a.txt").toFile().setReadOnly();
        write("res/a.txt", "${v}${v}");
        assertEquals(1, filter.copy(request).getWrittenFiles().size());
        assertEquals("VV", read("out/a.txt"));
    }

    @Test
    void requestDefaults() {
        ResourceFilterRequest request = builder().build();
        assertEquals(ResourceFilterRequest.DEFAULT_DELIMITERS, request.getDelimiters());
        assertEquals(
                ResourceFilterRequest.DEFAULT_NON_FILTERED_FILE_EXTENSIONS, request.getNonFilteredFileExtensions());
        assertEquals(StandardCharsets.UTF_8, request.getEncoding());
        assertEquals(ChangeDetection.CONTENT, request.getChangeDetection());
        assertTrue(request.isEscapeWindowsPaths() && request.isUseDefaultExcludes());
        assertFalse(request.isSupportMultiLineFiltering() || request.isFlatten() || request.isIncludeEmptyDirs());
        assertTrue(request.getEscapeString().isEmpty()
                && request.getOutputDirectory().isEmpty());
    }

    @Test
    void requestValidation() {
        assertEquals(List.of("${*}"), builder().delimiters(List.of()).build().getDelimiters());
        assertEquals(
                List.of("@", "${*}"),
                builder().delimiters(List.of("@", "${*}", "@")).build().getDelimiters());
        assertTrue(builder().escapeString("").build().getEscapeString().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> builder().delimiters(List.of("*}")));
        assertThrows(
                NullPointerException.class,
                () -> ResourceFilterRequest.builder().build());
        assertThrows(IllegalArgumentException.class, () -> filter.copy(builder().build()));
    }

    @Test
    void requestEqualityIgnoresTheSession() {
        ResourceFilterRequest a = builder().properties(Map.of("a", "1")).build();
        ResourceFilterRequest b = ResourceFilterRequest.builder()
                .session(session(Map.of("x", "y"), Map.of()))
                .properties(Map.of("a", "1"))
                .build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, builder().properties(Map.of("a", "2")).build());
        assertTrue(a.toString().contains("delimiters=[${*}, @]"), a.toString());
    }
}
