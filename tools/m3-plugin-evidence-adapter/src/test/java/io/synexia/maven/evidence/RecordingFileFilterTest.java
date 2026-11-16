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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.apache.maven.shared.filtering.ChangeDetection;
import org.apache.maven.shared.filtering.DefaultMavenFileFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonatype.plexus.build.incremental.DefaultBuildContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the real pinned file-filter provider and non-incremental build context. */
class RecordingFileFilterTest {
    private static final FileTime FIXED_TIME = FileTime.fromMillis(1_700_000_000_000L);

    @TempDir
    Path directory;

    @Test
    void actualUnfilteredCopyPreservesRawBinaryEvenWithDifferentEncodings() throws Exception {
        byte[] content = {0, (byte) 0xff, (byte) 0xc3, 0x28, 0x7f, '\n'};
        Path source = write("src/main/resources/binary.dat", content);
        Path destination = output("binary.dat");
        RecordingFileFilter recorder = recorder();

        assertTrue(recorder.copyFileWithResult(
                source, destination, false, List.of(), "UTF-16", "US-ASCII", ChangeDetection.ALWAYS));
        assertArrayEquals(content, Files.readAllBytes(destination));
        List<ResourceEvidence.Operation> operations = recorder.finish();
        assertEquals(1, operations.size());
        ResourceEvidence.Operation operation = operations.get(0);
        assertEquals("src/main/resources/binary.dat", operation.input());
        assertEquals("target/classes/binary.dat", operation.output());
        assertTrue(operation.delegateResult());
        assertEquals(sha256(content), operation.inputSha256());
        assertEquals(sha256(content), operation.outputSha256());
        assertEquals(content.length, operation.inputBytes());
        assertEquals(content.length, operation.outputBytes());
        assertSame(operations, recorder.finish());
        assertThrows(UnsupportedOperationException.class, operations::clear);
    }

    @Test
    void neverKeepsMismatchingExistingOutputAndItsFalseResult() throws Exception {
        byte[] sourceBytes = bytes("source");
        byte[] oldOutput = bytes("stale!");
        Path source = write("src/main/resources/config.txt", sourceBytes);
        Path destination = write("target/classes/config.txt", oldOutput);
        Files.setLastModifiedTime(destination, FIXED_TIME);
        FileTime previousTime = Files.getLastModifiedTime(destination);
        RecordingFileFilter recorder = recorder();

        assertFalse(copy(recorder, source, destination, ChangeDetection.NEVER));
        assertArrayEquals(oldOutput, Files.readAllBytes(destination));
        assertEquals(previousTime, Files.getLastModifiedTime(destination));
        ResourceEvidence.Operation operation = recorder.finish().get(0);
        assertFalse(operation.delegateResult());
        assertEquals(sha256(sourceBytes), operation.inputSha256());
        assertEquals(sha256(oldOutput), operation.outputSha256());
        assertNotEquals(operation.inputSha256(), operation.outputSha256());
        assertEquals(sourceBytes.length, operation.inputBytes());
        assertEquals(oldOutput.length, operation.outputBytes());
    }

    @Test
    void neverStillCopiesWhenTheDestinationDoesNotExist() throws Exception {
        byte[] content = bytes("new output");
        Path source = write("src/main/resources/new.txt", content);
        Path destination = output("new.txt");
        RecordingFileFilter recorder = recorder();

        assertFalse(Files.exists(destination));
        assertTrue(copy(recorder, source, destination, ChangeDetection.NEVER));
        assertArrayEquals(content, Files.readAllBytes(destination));
        assertTrue(recorder.finish().get(0).delegateResult());
    }

    @Test
    void unchangedContentPreservesTheFalseDelegateResult() throws Exception {
        byte[] content = bytes("unchanged");
        Path source = write("src/main/resources/same.txt", content);
        Path destination = write("target/classes/same.txt", content);
        Files.setLastModifiedTime(destination, FIXED_TIME);
        FileTime previousTime = Files.getLastModifiedTime(destination);
        RecordingFileFilter recorder = recorder();

        assertFalse(copy(recorder, source, destination, ChangeDetection.CONTENT));
        assertEquals(previousTime, Files.getLastModifiedTime(destination));
        ResourceEvidence.Operation operation = recorder.finish().get(0);
        assertFalse(operation.delegateResult());
        assertEquals(operation.inputSha256(), operation.outputSha256());
        assertEquals(content.length, operation.outputBytes());
    }

    @Test
    void smallContentCopyPreservesFalseEvenWhenBothOriginalAndWrappedCallsCreateOutputs() throws Exception {
        byte[] content = bytes("small payload");
        Path source = write("src/main/resources/small.txt", content);
        Path baseline = output("baseline.txt");
        Path destination = output("observed.txt");
        assertFalse(Files.exists(baseline));
        assertFalse(Files.exists(destination));
        DefaultMavenFileFilter original = new DefaultMavenFileFilter(new DefaultBuildContext());
        boolean originalResult = original.copyFileWithResult(
                source, baseline, false, List.of(), "UTF-8", ChangeDetection.CONTENT);
        RecordingFileFilter recorder = recorder();
        boolean recordedResult = copy(recorder, source, destination, ChangeDetection.CONTENT);

        // The pinned donor samples its buffered stream before close flushes the bytes.
        assertFalse(originalResult);
        assertEquals(originalResult, recordedResult);
        assertArrayEquals(content, Files.readAllBytes(baseline));
        assertArrayEquals(content, Files.readAllBytes(destination));
        ResourceEvidence.Operation operation = recorder.finish().get(0);
        assertFalse(operation.delegateResult());
        assertEquals(sha256(content), operation.inputSha256());
        assertEquals(sha256(content), operation.outputSha256());
        assertEquals(content.length, operation.outputBytes());
    }

    @Test
    void contentPolicyChangesBytesWithSameSizeAndTimestampWhilePreservingFalseResult() throws Exception {
        byte[] before = bytes("old");
        byte[] after = bytes("new");
        Path source = write("src/main/resources/change.txt", before);
        Path destination = output("change.txt");
        RecordingFileFilter first = recorder();
        assertTrue(copy(first, source, destination, ChangeDetection.ALWAYS));
        ResourceEvidence.Operation original = first.finish().get(0);

        Files.setLastModifiedTime(source, FIXED_TIME);
        FileTime preservedTime = Files.getLastModifiedTime(source);
        Files.setLastModifiedTime(destination, preservedTime);
        Files.write(source, after);
        Files.setLastModifiedTime(source, preservedTime);
        assertEquals(Files.size(source), Files.size(destination));
        assertEquals(Files.getLastModifiedTime(source), Files.getLastModifiedTime(destination));
        RecordingFileFilter second = recorder();

        assertFalse(copy(second, source, destination, ChangeDetection.CONTENT));
        assertArrayEquals(after, Files.readAllBytes(destination));
        ResourceEvidence.Operation changed = second.finish().get(0);
        assertFalse(changed.delegateResult());
        assertEquals(sha256(after), changed.inputSha256());
        assertEquals(sha256(after), changed.outputSha256());
        assertNotEquals(original.inputSha256(), changed.inputSha256());
    }

    @Test
    void finalValidationRejectsSourceMutationWithoutRepairingEitherFile() throws Exception {
        byte[] original = bytes("first");
        byte[] changed = bytes("other");
        Path source = write("src/main/resources/source.txt", original);
        Path destination = output("source.txt");
        RecordingFileFilter recorder = recorder();
        assertTrue(copy(recorder, source, destination, ChangeDetection.ALWAYS));
        FileTime preservedTime = Files.getLastModifiedTime(source);
        Files.write(source, changed);
        Files.setLastModifiedTime(source, preservedTime);

        assertThrows(IOException.class, recorder::finish);
        assertThrows(IOException.class, recorder::finish);
        assertArrayEquals(changed, Files.readAllBytes(source));
        assertArrayEquals(original, Files.readAllBytes(destination));
    }

    @Test
    void finalValidationRejectsOutputMutationWithoutRepairingIt() throws Exception {
        byte[] original = bytes("first");
        byte[] changed = bytes("other");
        Path source = write("src/main/resources/output.txt", original);
        Path destination = output("output.txt");
        RecordingFileFilter recorder = recorder();
        assertTrue(copy(recorder, source, destination, ChangeDetection.ALWAYS));
        FileTime preservedTime = Files.getLastModifiedTime(destination);
        Files.write(destination, changed);
        Files.setLastModifiedTime(destination, preservedTime);

        assertThrows(IOException.class, recorder::finish);
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(changed, Files.readAllBytes(destination));
    }

    @Test
    void filteredCopyIsRefusedBeforeTheRealDelegateCanOverwriteOutput() throws Exception {
        Path source = write("src/main/resources/filter.txt", bytes("replacement"));
        byte[] original = bytes("preserve");
        Path destination = write("target/classes/filter.txt", original);
        RecordingFileFilter recorder = recorder();

        assertThrows(MavenFilteringException.class, () -> recorder.copyFileWithResult(
                source, destination, true, List.of(), "UTF-8", ChangeDetection.ALWAYS));
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertThrows(IOException.class, recorder::finish);
    }

    @Test
    void duplicateDestinationIsRefusedBeforeASecondDelegateWrite() throws Exception {
        byte[] original = bytes("first");
        Path firstSource = write("src/main/resources/first.txt", original);
        Path secondSource = write("src/main/resources/second.txt", bytes("second"));
        Path destination = output("shared.txt");
        RecordingFileFilter recorder = recorder();
        assertTrue(copy(recorder, firstSource, destination, ChangeDetection.ALWAYS));

        assertThrows(MavenFilteringException.class, () -> copy(
                recorder, secondSource, destination, ChangeDetection.ALWAYS));
        assertArrayEquals(original, Files.readAllBytes(destination));
        assertThrows(IOException.class, recorder::finish);
    }

    @Test
    void cumulativeDigestBudgetIncludesFinalRevalidation() throws Exception {
        long fileBytes = 32L * 1024 * 1024;
        Path source = directory.resolve("src/main/resources/budget.bin");
        Files.createDirectories(source.getParent());
        try (SeekableByteChannel file = Files.newByteChannel(
                source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            file.position(fileBytes - 1);
            file.write(ByteBuffer.wrap(new byte[] {0}));
        }
        Path firstOutput = output("budget-first.bin");
        Path secondOutput = output("budget-second.bin");
        RecordingFileFilter recorder = recorder();
        assertTrue(copy(recorder, source, firstOutput, ChangeDetection.ALWAYS));
        assertTrue(copy(recorder, source, secondOutput, ChangeDetection.ALWAYS));
        assertEquals(fileBytes, Files.size(firstOutput));
        assertEquals(fileBytes, Files.size(secondOutput));

        // The two captures read 192 MiB. The first final pair reaches 256 MiB;
        // admitting the second pair would violate the documented aggregate bound.
        IOException failure = assertThrows(IOException.class, recorder::finish);
        assertTrue(failure.getMessage().contains("bounded") || failure.getMessage().contains("budget"));
        assertThrows(IOException.class, recorder::finish);
    }

    private RecordingFileFilter recorder() throws IOException {
        return new RecordingFileFilter(directory, new DefaultMavenFileFilter(new DefaultBuildContext()));
    }

    private Path write(String relative, byte[] content) throws IOException {
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.write(file, content);
    }

    private Path output(String name) throws IOException {
        Path file = directory.resolve("target/classes").resolve(name);
        Files.createDirectories(file.getParent());
        return file;
    }

    private static boolean copy(RecordingFileFilter recorder, Path source, Path output, ChangeDetection policy)
            throws MavenFilteringException {
        return recorder.copyFileWithResult(source, output, false, List.of(), "UTF-8", policy);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
