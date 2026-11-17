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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.ProtoSession;
import org.apache.maven.api.model.Resource;
import org.apache.maven.api.services.ResourceFilterException;
import org.apache.maven.api.services.ResourceFilterRequest;
import org.apache.maven.api.services.ResourceFilterRequest.ChangeDetection;
import org.apache.maven.impl.ResourceFilterParityTest.Config;
import org.apache.maven.impl.model.DefaultInterpolator;
import org.apache.maven.shared.filtering.FilterWrapper;
import org.apache.maven.shared.filtering.FilteringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Compares files written by {@link DefaultResourceFilter#copy} with files written by maven-filtering 3.5.0
 * {@code FilteringUtils.copyFile}, the method {@code DefaultMavenFileFilter.copyFile} delegates to, for encodings,
 * line endings, byte order marks, non-filtered extensions and every {@code ChangeDetection} mode. The reference
 * side picks the encoding and the filtering flag per file with the rules of
 * {@code DefaultMavenResourcesFiltering.filterResources} (non-filtered extensions, {@code getEncoding}). The
 * change detection table is written to {@code target/resource-filter-parity/CHANGE_DETECTION_PARITY.tsv}.
 */
class ResourceFilterCopyParityTest {

    private static final Charset LATIN_1 = StandardCharsets.ISO_8859_1;
    private static final Config CONFIG = ResourceFilterParityTest.CONFIGS.get(0);
    private static final List<String> TABLE = new ArrayList<>();
    private static final long SOURCE_TIME = 1_500_000_000_000L;

    @TempDir
    Path temp;

    private final DefaultResourceFilter filter =
            new DefaultResourceFilter(new DefaultInterpolator(), new DefaultPathMatcherFactory());

    @Test
    void filteredFilesAreByteEqual() throws IOException {
        Path source = Files.createDirectories(temp.resolve("src"));
        Map<String, byte[]> files = corpus();
        Path reference = Files.createDirectories(temp.resolve("reference"));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path from = Files.write(source.resolve(file.getKey()), file.getValue());
            referenceCopy(from, reference.resolve(file.getKey()));
        }
        filter.copy(request(source, temp.resolve("core"), ChangeDetection.CONTENT));
        for (String name : files.keySet()) {
            byte[] expected = Files.readAllBytes(reference.resolve(name));
            assertArrayEquals(expected, Files.readAllBytes(temp.resolve("core/" + name)), name);
        }
    }

    /** CRLF, LF, BOM, ISO-8859-1 properties, a binary image and a file with an added non-filtered extension. */
    private static Map<String, byte[]> corpus() {
        return Map.of(
                "app.txt", "name=${a}\r\nat=@b@\r\nesc=\\${a}\r\nmail=x@y.z\r\n".getBytes(StandardCharsets.UTF_8),
                "lf.txt", "${a}\n${unknown}\n\n@b@".getBytes(StandardCharsets.UTF_8),
                "bom.txt", "\ufeff${a} \u20ac\n".getBytes(StandardCharsets.UTF_8),
                "latin.properties", "k=caf\u00e9 ${a}\r\n".getBytes(LATIN_1),
                "logo.PNG", bytes(),
                "data.bin", "${a}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void malformedInputFailsOnBothSides() throws IOException {
        Path source = Files.createDirectories(temp.resolve("src"));
        Files.write(source.resolve("bad.txt"), new byte[] {'$', '{', 'a', '}', (byte) 0xC3, (byte) 0x28});
        assertThrows(IOException.class, () -> referenceCopy(source.resolve("bad.txt"), temp.resolve("bad.txt")));
        ResourceFilterException e = assertThrows(
                ResourceFilterException.class,
                () -> filter.copy(request(source, temp.resolve("core"), ChangeDetection.CONTENT)));
        assertEquals(true, e.getMessage().contains("while reading with UTF-8 encoding"), e.getMessage());
    }

    /**
     * A value the target encoding cannot represent: maven-filtering replaces it in {@code CONTENT} mode (its
     * {@code CachingWriter}) and fails in {@code ALWAYS} mode ({@code Files.newBufferedWriter}); the service
     * replaces it in every mode. Known difference, kept visible here.
     */
    @Test
    void unmappableCharacters() throws IOException {
        Path source = Files.createDirectories(temp.resolve("src"));
        Path from = Files.writeString(source.resolve("a.properties"), "k=${unicode}\n", LATIN_1);
        referenceLatin1(from, temp.resolve("content.properties"), ChangeDetection.CONTENT);
        assertEquals("k=\u00e9?\n", Files.readString(temp.resolve("content.properties"), LATIN_1));
        Path always = temp.resolve("always.properties");
        assertThrows(IOException.class, () -> referenceLatin1(from, always, ChangeDetection.ALWAYS));
        filter.copy(request(source, temp.resolve("core"), ChangeDetection.ALWAYS));
        assertEquals("k=\u00e9?\n", Files.readString(temp.resolve("core/a.properties"), LATIN_1));
    }

    private static void referenceLatin1(Path from, Path to, ChangeDetection mode) throws IOException {
        FilteringUtils.copyFile(from.toFile(), to.toFile(), "ISO-8859-1", wrappers(), referenceMode(mode));
    }

    @Test
    void changeDetectionParity() throws IOException {
        List<String> failures = new ArrayList<>();
        for (ChangeDetection mode : ChangeDetection.values()) {
            for (TargetState state : TargetState.values()) {
                compareChangeDetection(mode, state, failures);
            }
        }
        assertEquals(List.of(), failures);
    }

    /** State of the target file before the copy. */
    enum TargetState {
        MISSING,
        SAME_OLDER,
        SAME_NEWER,
        DIFFERENT_OLDER,
        DIFFERENT_NEWER
    }

    /**
     * Compares what each side did to the target. maven-filtering 3.5.0 returns {@code false} from
     * {@code copyFile} in the content modes even when it wrote (it asks its {@code CachingWriter} before closing
     * it), so a write is observed on the file: created, or its modification time changed.
     */
    private void compareChangeDetection(ChangeDetection mode, TargetState state, List<String> failures)
            throws IOException {
        Path dir = Files.createDirectories(temp.resolve(mode + "-" + state));
        Path source = Files.createDirectories(dir.resolve("src"));
        Path from = Files.writeString(source.resolve("a.txt"), "v=${a}\n");
        Files.setLastModifiedTime(from, FileTime.fromMillis(SOURCE_TIME));
        Path referenceTarget = prepare(dir.resolve("ref/a.txt"), state);
        Path coreTarget = prepare(dir.resolve("core/a.txt"), state);
        boolean returned = FilteringUtils.copyFile(
                from.toFile(), referenceTarget.toFile(), "UTF-8", wrappers(), referenceMode(mode));
        boolean coreReported = filter.copy(request(source, dir.resolve("core"), mode))
                .getFiles()
                .get(0)
                .written();
        record(List.<Object>of(mode, state, returned, coreReported), referenceTarget, coreTarget, failures);
    }

    /** Adds the table row of one case; {@code head} is mode, target state, reference return, core report. */
    private static void record(List<Object> head, Path referenceTarget, Path coreTarget, List<String> failures)
            throws IOException {
        TargetState state = (TargetState) head.get(1);
        boolean referenceWritten = observedWrite(referenceTarget, state);
        boolean coreWritten = observedWrite(coreTarget, state);
        boolean sameBytes = Files.readString(referenceTarget).equals(Files.readString(coreTarget));
        List<Object> row =
                List.<Object>of(head.get(0), state, head.get(2), referenceWritten, head.get(3), coreWritten, sameBytes);
        TABLE.add(String.join("\t", row.stream().map(String::valueOf).toList()));
        if (referenceWritten != coreWritten || coreWritten != (Boolean) head.get(3) || !sameBytes) {
            failures.add(head.get(0) + "/" + state + ": written " + referenceWritten + " vs " + coreWritten);
        }
    }

    private static boolean observedWrite(Path target, TargetState state) throws IOException {
        return state == TargetState.MISSING
                ? Files.exists(target)
                : Files.getLastModifiedTime(target).toMillis() != targetTime(state);
    }

    private static long targetTime(TargetState state) {
        return state.name().endsWith("OLDER") ? SOURCE_TIME - 1_000_000L : SOURCE_TIME + 1_000_000L;
    }

    private static Path prepare(Path target, TargetState state) throws IOException {
        Files.createDirectories(target.getParent());
        if (state == TargetState.MISSING) {
            return target;
        }
        Files.writeString(target, state.name().startsWith("SAME") ? "v=A\n" : "stale\n");
        Files.setLastModifiedTime(target, FileTime.fromMillis(targetTime(state)));
        return target;
    }

    @AfterAll
    static void writeTable() throws IOException {
        Path dir = Files.createDirectories(Path.of("target", "resource-filter-parity"));
        List<String> rows = new ArrayList<>(List.of(
                "mode\ttarget\treference_returned\treference_written\tcore_reported\tcore_written\tsame_bytes"));
        rows.addAll(TABLE);
        Files.write(dir.resolve("CHANGE_DETECTION_PARITY.tsv"), rows, StandardCharsets.UTF_8);
    }

    /** The per-file decisions of {@code DefaultMavenResourcesFiltering.filterResources}, then the real copy. */
    private static void referenceCopy(Path from, Path to) throws IOException {
        String name = from.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = name.substring(name.lastIndexOf('.') + 1);
        boolean filtering = !ResourceFilterRequest.DEFAULT_NON_FILTERED_FILE_EXTENSIONS.contains(extension)
                && !"bin".equals(extension);
        String encoding = "properties".equals(extension) ? "ISO-8859-1" : "UTF-8";
        FilterWrapper[] wrappers = filtering ? wrappers() : new FilterWrapper[0];
        FilteringUtils.copyFile(
                from.toFile(),
                to.toFile(),
                encoding,
                wrappers,
                org.apache.maven.shared.filtering.ChangeDetection.CONTENT);
    }

    private static FilterWrapper[] wrappers() {
        return new FilterWrapper[] {
            new FilterWrapper() {
                @Override
                public Reader getReader(Reader reader) {
                    return ResourceFilterParityTest.multiDelimiterReader(reader, CONFIG);
                }
            }
        };
    }

    private static org.apache.maven.shared.filtering.ChangeDetection referenceMode(ChangeDetection mode) {
        return org.apache.maven.shared.filtering.ChangeDetection.valueOf(mode.name());
    }

    private ResourceFilterRequest request(Path source, Path output, ChangeDetection mode) {
        Resource resource = Resource.newBuilder()
                .directory(source.toString())
                .filtering("true")
                .build();
        return ResourceFilterRequest.builder()
                .session(ProtoSession.newBuilder().withTopDirectory(temp).build())
                .roots(List.of(new DefaultSourceRoot(temp, ProjectScope.MAIN, resource)))
                .outputDirectory(output)
                .properties(ResourceFilterParityTest.VALUES)
                .escapeString(CONFIG.escape())
                .propertiesEncoding(LATIN_1)
                .nonFilteredFileExtensions(List.of("bin"))
                .changeDetection(mode)
                .build();
    }

    private static byte[] bytes() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }
}
