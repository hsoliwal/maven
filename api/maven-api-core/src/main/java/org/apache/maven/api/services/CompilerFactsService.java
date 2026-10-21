/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this work.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.maven.api.services;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.apache.maven.api.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.annotations.Experimental;

/**
 * Session-local discovery of ORIGINAL compiler-produced immutable facts.
 * Maven never reparses the Java source, creates an AST, invents attribution or
 * decides build-cache validity from this service. JDK/M3File/M3AST own source
 * identity, syntax/semantic facts and durable image formats. Plugins may use
 * the image only when they implement its exact schema and capability contract.
 */
@Experimental
public interface CompilerFactsService extends Service {
    int SYNTAX = 1, POSITIONS = 2, PARENTS = 4;
    int DECLARATIONS = 8, ATTRIBUTION = 16, BYTECODE = 32;
    int KNOWN_CAPABILITIES = SYNTAX | POSITIONS | PARENTS | DECLARATIONS | ATTRIBUTION | BYTECODE;

    /** An exact source generation, toolchain, and full compile-context identity. */
    record Identity(String projectId, URI sourceUri, String sourceSha256,
            String contextSha256, String compilerId) {
        public Identity {
            projectId = requireName(projectId, "projectId");
            sourceUri = Objects.requireNonNull(sourceUri, "sourceUri");
            if (!sourceUri.isAbsolute() || sourceUri.getFragment() != null) {
                throw new IllegalArgumentException("source identity requires absolute fragment-free URI");
            }
            sourceSha256 = requireSha256(sourceSha256);
            contextSha256 = requireSha256(contextSha256);
            compilerId = requireName(compilerId, "compilerId");
        }
    }

    /** 
     * A source-owned immutable fact image. Schema and claimed capabilities do
     * not themselves prove correctness, semantic completeness, or cacheability.
     */
    record Image(Identity identity, Path image, String schema, String imageSha256,
            int capabilities) {
        public Image {
            identity = Objects.requireNonNull(identity, "identity");
            image = Objects.requireNonNull(image, "image");
            schema = requireName(schema, "schema");
            imageSha256 = requireSha256(imageSha256);
            if (capabilities <= 0 || (capabilities & ~KNOWN_CAPABILITIES) != 0) {
                throw new IllegalArgumentException("unknown or empty compiler facts capabilities");
            }
        }
    }

    /**
     * Admit a compiler-completed image only after its contents match the
     * specified SHA-256. The plugin/tool which ran the compiler owns publication.
     * This validates byte custody, not who produced the underlying semantics.
     */
    void publish(Session session, Image image) throws IOException;

    /**
     * Lookup requires EXACT source/context/compiler/schema identity and all requested
     * capabilities; it checks image bytes again to reject externally changed
     * files. A miss never triggers a parser or recompilation.
     */
    Optional<Image> find(Session session, Identity identity, String requiredSchema, int requiredCapabilities)
            throws IOException;

    /** Detach a published generation without deleting its producer-owned image. */
    void revoke(Session session, Identity identity);

    private static String requireSha256(String value) {
        Objects.requireNonNull(value, "sha256");
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("noncanonical SHA-256");
        return value;
    }
    private static String requireName(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }
}
