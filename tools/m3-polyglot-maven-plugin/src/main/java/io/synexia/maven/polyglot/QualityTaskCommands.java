/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provider-specific plans for the separately installed CodeQL CLI and the
 * original OpenRewrite Maven plugin. No QL execution engine or recipe engine is
 * reimplemented here. Every command remains an argv vector, never a shell script.
 */
public final class QualityTaskCommands {
    private QualityTaskCommands() {}

    /** Returns whether this task is a known explicit analysis/transformation provider. */
    public static boolean supported(String tool) {
        return "@codeql".equals(tool) || "@rewrite".equals(tool);
    }

    /**
     * Validates and plans exactly one original provider invocation. Filesystem
     * existence and version/provenance qualification remain with each provider.
     * An apply recipe is refused unless separately approved by the caller.
     */
    public static List<String> command(
            TaskManifest.Task task, Path workspace, boolean allowRewriteApply) throws IOException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(workspace, "workspace");
        Path root = workspace.toAbsolutePath().normalize();
        return switch (task.tool()) {
            case "@codeql" -> codeql(task.arguments(), root);
            case "@rewrite" -> rewrite(task.arguments(), root, allowRewriteApply);
            default -> throw new IOException("unsupported quality provider: " + task.tool());
        };
    }

    private static List<String> codeql(List<String> args, Path root) throws IOException {
        if (args.size() != 5) {
            throw new IOException("CodeQL requires operation, executable and three explicit operands");
        }
        String operation = args.get(0);
        String cli = executable(args.get(1));
        String database;
        if ("create".equals(operation)) {
            String language = name(args.get(2), "CodeQL language");
            if (!language.matches("[a-z][a-z0-9-]{0,79}")) {
                throw new IOException("invalid CodeQL language identifier");
            }
            database = relative(root, args.get(3), "CodeQL database");
            String buildMode = args.get(4);
            if (!"none".equals(buildMode) && !"autobuild".equals(buildMode)) {
                throw new IOException("CodeQL build mode must be none or autobuild; manual tracing is not implemented");
            }
            return List.of(cli, "database", "create", database,
                    "--source-root=" + root,
                    "--language=" + language,
                    "--build-mode=" + buildMode);
        }
        if ("analyze".equals(operation)) {
            database = relative(root, args.get(2), "CodeQL database");
            String suite = name(args.get(3), "CodeQL query suite");
            if (suite.startsWith("-") || suite.contains("..")) {
                throw new IOException("unsafe CodeQL suite selector");
            }
            String report = relative(root, args.get(4), "CodeQL SARIF report");
            if (!report.endsWith(".sarif")) {
                throw new IOException("CodeQL report must use .sarif extension");
            }
            return List.of(cli, "database", "analyze", database,
                    suite, "--format=sarifv2.1.0", "--output=" + report);
        }
        throw new IOException("unsupported CodeQL operation: " + operation);
    }

    private static List<String> rewrite(List<String> args, Path root, boolean allowApply) throws IOException {
        if (args.size() != 5 && args.size() != 6) {
            throw new IOException("OpenRewrite requires mode, Maven executable, POM, plugin version, recipe[, artifact]");
        }
        String mode = args.get(0);
        if (!"preview".equals(mode) && !"check".equals(mode) && !"apply".equals(mode)) {
            throw new IOException("OpenRewrite mode must be preview, check or apply");
        }
        if ("apply".equals(mode) && !allowApply) {
            throw new IOException("OpenRewrite apply requires explicit approval");
        }
        String maven = executable(args.get(1));
        String pom = relative(root, args.get(2), "OpenRewrite POM");
        if (!pom.endsWith(".xml")) {
            throw new IOException("OpenRewrite POM must be an XML file");
        }
        String version = name(args.get(3), "OpenRewrite plugin version");
        if (version.equalsIgnoreCase("LATEST") || version.equalsIgnoreCase("RELEASE")
                || !version.matches("[0-9][A-Za-z0-9_.-]{0,79}")) {
            throw new IOException("pin an exact OpenRewrite plugin version");
        }
        String recipe = name(args.get(4), "OpenRewrite recipe");
        if (!recipe.matches("[A-Za-z_$][A-Za-z0-9_.$-]{0,255}")) {
            throw new IOException("invalid OpenRewrite recipe identifier");
        }
        String goal = "apply".equals(mode) ? "runNoFork" : "dryRunNoFork";
        List<String> cmd = new ArrayList<>();
        cmd.add(maven);
        cmd.add("--batch-mode");
        cmd.add("--no-transfer-progress");
        cmd.add("-f");
        cmd.add(pom);
        cmd.add("org.openrewrite.maven:rewrite-maven-plugin:" + version + ':' + goal);
        cmd.add("-Drewrite.activeRecipes=" + recipe);
        cmd.add("-Drewrite.failOnInvalidActiveRecipes=true");
        if ("check".equals(mode)) {
            cmd.add("-Drewrite.failOnDryRunResults=true");
        }
        if (args.size() == 6) {
            String artifact = name(args.get(5), "OpenRewrite recipe artifact");
            if (!artifact.matches("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+")
                    || artifact.endsWith(":LATEST") || artifact.endsWith(":RELEASE")) {
                throw new IOException("recipe artifact must have explicit group:artifact:version");
            }
            cmd.add("-Drewrite.recipeArtifactCoordinates=" + artifact);
        }
        return List.copyOf(cmd);
    }

    private static String executable(String value) throws IOException {
        return name(value, "executable");
    }

    private static String name(String value, String label) throws IOException {
        if (value == null || value.isBlank() || value.length() > 1024
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("invalid " + label);
        }
        return value;
    }

    private static String relative(Path root, String value, String label) throws IOException {
        name(value, label);
        if (value.startsWith("/") || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            throw new IOException("expected project-relative " + label);
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("unsafe path segment in " + label);
            }
        }
        Path path = root.resolve(value).normalize();
        if (!path.startsWith(root)) {
            throw new IOException("path outside project: " + label);
        }
        Path current = root;
        for (Path segment : root.relativize(path)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("symlink in " + label);
            }
        }
        return path.toString();
    }
}
