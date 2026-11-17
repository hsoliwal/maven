/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps javac-style task arguments to the arguments of ECJ's batch compiler, with the same choices
 * plexus-compiler-eclipse makes for maven-compiler-plugin's {@code compilerId=eclipse}: {@code -Werror} becomes
 * {@code -failOnWarning}, {@code -g} also keeps all locals, {@code -proceedOnError} becomes
 * {@code -proceedOnError:Fatal}, warning switches use ECJ's additive {@code -warn:+key} form, and a
 * {@code -properties} file (an Eclipse {@code org.eclipse.jdt.core.prefs}) must exist. Options both compilers
 * share pass unchanged. Javac options that change class files and have no ECJ form are refused, never dropped.
 *
 * <p>Three arguments belong to the provider and never reach ECJ: {@value #JAR}path selects the ECJ jar,
 * {@value #SHA256}hex pins its bytes, and {@value #ATOMIZER_SKIP} drops javac plugins such as the inline
 * {@code -Xplugin:M3AST} atomizer, which ECJ cannot load.
 */
final class EcjArguments {
    static final String ATOMIZER_SKIP = "--m3-atomizer=skip";
    static final String JAR = "--m3-ecj-jar=";
    static final String SHA256 = "--m3-ecj-sha256=";

    /** One javac token to one ECJ token; a following value, if any, passes unchanged. */
    private static final Map<String, String> RENAMED = Map.of(
            "-Werror", "-failOnWarning", "-nowarn", "-warn:none", "-Xlint:none", "-warn:none",
            "-proceedOnError", "-proceedOnError:Fatal", "-Xmaxerrs", "-maxProblems", "--class-path", "-classpath",
            "--source-path", "-sourcepath", "--source", "-source", "--target", "-target",
            "--processor-path", "-processorpath");
    /** Javac -Xlint keys with an ECJ -warn token of the same meaning; other keys keep ECJ's default. */
    private static final Map<String, String> LINT = Map.of("deprecation", "deprecation", "unchecked", "unchecked",
            "rawtypes", "raw", "serial", "serial", "fallthrough", "fallthrough", "finally", "finally",
            "static", "static-access");
    /** Javac options that only shape diagnostics and have no ECJ form; a value follows -Xmaxwarns. */
    private static final List<String> DIAGNOSTIC_ONLY = List.of("-Xdoclint", "-Xdiags:", "-Xmaxwarns");
    /** Javac options that change class files or the launcher and have no ECJ form. */
    private static final List<String> REFUSED = List.of("-XD", "-implicit:", "-Xpkginfo:", "-Xprefer:", "-J");

    /** The ECJ arguments and the provider's own settings. */
    record Mapped(List<String> args, String jar, String sha256, boolean atomizerSkipped) {
        Mapped {
            args = List.copyOf(args);
        }
    }

    private EcjArguments() { }

    /** Maps the task arguments; {@code directory} is the task's working folder, for relative {@code -properties}. */
    static Mapped map(List<String> javac, Path directory) throws IOException {
        boolean skip = javac.contains(ATOMIZER_SKIP);
        List<String> out = new ArrayList<>();
        String jar = null;
        String sha256 = null;
        for (int i = 0; i < javac.size(); i++) {
            String arg = javac.get(i);
            if (arg.startsWith(JAR)) {
                jar = arg.substring(JAR.length());
            } else if (arg.startsWith(SHA256)) {
                sha256 = arg.substring(SHA256.length());
            } else if (!ATOMIZER_SKIP.equals(arg)) {
                i = translate(javac, i, skip, directory, out);
            }
        }
        return new Mapped(out, jar, sha256, skip);
    }

    /** Appends the ECJ form of the argument at {@code i}; returns the index of the last argument it used. */
    private static int translate(List<String> javac, int i, boolean skip, Path directory, List<String> out)
            throws IOException {
        String arg = javac.get(i);
        if (RENAMED.containsKey(arg)) {
            out.add(RENAMED.get(arg));
        } else if ("-g".equals(arg)) {
            out.addAll(List.of("-g", "-preserveAllLocals"));
        } else if ("-properties".equals(arg)) {
            out.addAll(List.of(arg, properties(value(javac, i), directory)));
            return i + 1;
        } else if (DIAGNOSTIC_ONLY.stream().anyMatch(arg::startsWith)) {
            return "-Xmaxwarns".equals(arg) ? i + 1 : i;
        } else {
            other(arg, skip, out);
        }
        return i;
    }

    private static void other(String arg, boolean skip, List<String> out) throws IOException {
        if (arg.startsWith("-Xplugin:")) {
            if (!skip) {
                throw new IOException("ECJ cannot load javac plugins (" + arg + "): compile with "
                        + "@m3:jdk:compiler:java to run them, or add " + ATOMIZER_SKIP + " to compile without them");
            }
        } else if ("-Xlint".equals(arg) || arg.startsWith("-Xlint:")) {
            lint(arg, out);
        } else if (REFUSED.stream().anyMatch(arg::startsWith)) {
            throw new IOException("javac option has no ECJ equivalent: " + arg);
        } else {
            out.add(arg);
        }
    }

    /** {@code -Xlint:a,-b} becomes {@code -warn:+a,-b} for the keys ECJ names the same; ECJ defaults stay on. */
    private static void lint(String arg, List<String> out) {
        String keys = "-Xlint".equals(arg) ? "" : arg.substring("-Xlint:".length());
        List<String> tokens = new ArrayList<>();
        for (String key : keys.split(",")) {
            boolean off = key.startsWith("-");
            String token = LINT.get(off ? key.substring(1) : key);
            if (token != null) tokens.add((off ? "-" : "+") + token);
        }
        if (!tokens.isEmpty()) out.add("-warn:" + String.join(",", tokens));
    }

    private static String value(List<String> javac, int i) throws IOException {
        if (i + 1 >= javac.size()) throw new IOException(javac.get(i) + " needs a value");
        return javac.get(i + 1);
    }

    /** As plexus-compiler-eclipse: a named JDT preferences file must be an existing regular file. */
    private static String properties(String file, Path directory) throws IOException {
        if (!Files.isRegularFile(directory.resolve(file), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ECJ -properties file does not exist: " + file);
        }
        return file;
    }
}
