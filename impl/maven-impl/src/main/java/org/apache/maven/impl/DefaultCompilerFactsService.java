/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this work.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.maven.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.api.Session;
import org.apache.maven.api.SessionData;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.services.CompilerFactsService;

/**
 * Bounded session-local pointers to already-published compiler fact images.
 * The original SDK owns generation, durable bytes, AST and ABI facts. This
 * singleton holds NO build/project/compiler globals; metadata is retained only
 * in Maven's owning SessionData lifetime and never creates another parser.
 */
@Named
@Singleton
public final class DefaultCompilerFactsService implements CompilerFactsService {
    private static final int MAX_IMAGES = 2048;
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;
    private static final SessionData.Key<Store> KEY =
            SessionData.key(Store.class, DefaultCompilerFactsService.class.getName());

    /** Sorted key slots and image references; O(log n) lookups and no parser/tree. */
    private static final class Store {
        private Identity[] identities = new Identity[16];
        private Image[] images = new Image[16];
        private int count;

        private static int compare(Identity left, Identity right) {
            int difference = left.projectId().compareTo(right.projectId());
            if (difference == 0) difference = left.sourceUri().compareTo(right.sourceUri());
            if (difference == 0) difference = left.sourceSha256().compareTo(right.sourceSha256());
            if (difference == 0) difference = left.contextSha256().compareTo(right.contextSha256());
            if (difference == 0) difference = left.compilerId().compareTo(right.compilerId());
            return difference;
        }
        /** Binary search over the stable sorted identity lane. */
        private int find(Identity identity) {
            int low = 0, high = count - 1;
            while (low <= high) {
                int mid = low + ((high - low) >>> 1);
                int difference = compare(identities[mid], identity);
                if (difference == 0) return mid;
                if (difference < 0) low = mid + 1;
                else high = mid - 1;
            }
            return -low - 1;
        }
        synchronized void publish(Image image) throws IOException {
            int old = find(image.identity());
            if (old >= 0) {
                if (!images[old].equals(image)) {
                    throw new IOException("compiler facts already published for this exact generation");
                }
                return;
            }
            if (count == MAX_IMAGES) throw new IOException("compiler facts session budget exceeded");
            if (count == images.length) {
                identities = Arrays.copyOf(identities, Math.min(MAX_IMAGES, count * 2));
                images = Arrays.copyOf(images, identities.length);
            }
            int insertion = -old - 1;
            System.arraycopy(identities, insertion, identities, insertion + 1, count - insertion);
            System.arraycopy(images, insertion, images, insertion + 1, count - insertion);
            identities[insertion] = image.identity();
            images[insertion] = image;
            count++;
        }
        synchronized Optional<Image> findCopy(Identity identity) {
            int index = find(identity);
            return index < 0 ? Optional.empty() : Optional.of(images[index]);
        }
        synchronized void revoke(Identity identity) {
            int index = find(identity);
            if (index < 0) return;
            System.arraycopy(identities, index + 1, identities, index, count - index - 1);
            System.arraycopy(images, index + 1, images, index, count - index - 1);
            identities[--count] = null; images[count] = null;
        }
    }

    @Override public void publish(Session session, Image image) throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(image, "image");
        verify(image);
        store(session).publish(image);
    }

    @Override public Optional<Image> find(Session session, Identity identity,
            String requiredSchema, int requiredCapabilities) throws IOException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(requiredSchema, "requiredSchema");
        if (requiredSchema.isBlank() || requiredCapabilities <= 0 ||
                (requiredCapabilities & ~KNOWN_CAPABILITIES) != 0) {
            throw new IllegalArgumentException("invalid required capabilities");
        }
        Store existing = session.getData().get(KEY);
        if (existing == null) return Optional.empty();
        Optional<Image> found = existing.findCopy(identity);
        if (found.isEmpty() || !found.get().schema().equals(requiredSchema) ||
                (found.get().capabilities() & requiredCapabilities) != requiredCapabilities) {
            return Optional.empty();
        }
        verify(found.orElseThrow());
        return found;
    }

    @Override public void revoke(Session session, Identity identity) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(identity, "identity");
        Store existing = session.getData().get(KEY);
        if (existing != null) existing.revoke(identity);
    }

    private static Store store(Session session) {
        return session.getData().computeIfAbsent(KEY, Store::new);
    }

    /** Verify actual original provider bytes, not timestamps or filename alone. */
    private static void verify(Image image) throws IOException {
        Path path = image.image();
        BasicFileAttributes stat = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!stat.isRegularFile() || stat.size() > MAX_IMAGE_BYTES) {
            throw new IOException("compiler facts file is not an admitted regular bounded image");
        }
        MessageDigest checksum;
        try { checksum = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (InputStream in = Channels.newInputStream(Files.newByteChannel(
                path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            byte[] buffer = new byte[16_384];
            long readTotal = 0L;
            for (int read; (read = in.read(buffer)) != -1;) {
                if (read == 0) throw new IOException("facts image read stalled");
                readTotal += read;
                if (readTotal > MAX_IMAGE_BYTES) throw new IOException("facts image exceeded byte budget");
                checksum.update(buffer, 0, read);
            }
            if (readTotal != stat.size()) throw new IOException("facts image changed during read");
        }
        String actual = java.util.HexFormat.of().formatHex(checksum.digest());
        if (!actual.equals(image.imageSha256())) {
            throw new IOException("compiler facts image digest mismatch");
        }
    }
}
