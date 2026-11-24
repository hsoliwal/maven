/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.smart.extension;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import io.synexia.maven.polyglot.ModuleStateDb;

/** Content evidence for a known Maven goal. Metadata alone never authorizes reuse. */
final class M3LifecycleFingerprint {
    private static final String DOMAIN = "m3.maven.lifecycle.v1";
    private static final long MAX_BYTES = 16L << 30;
    private static final int MAX_PATHS = 262_144;
    private final MessageDigest digest = sha256();
    private long bytes;
    private int paths;

    private M3LifecycleFingerprint() { text(DOMAIN); }

    static String key(MojoExecution execution) {
        M3LifecycleFingerprint value = new M3LifecycleFingerprint();
        value.text(execution.getGroupId());
        value.text(execution.getArtifactId());
        value.text(execution.getGoal());
        value.text(execution.getExecutionId());
        value.text(execution.getLifecyclePhase());
        return "lifecycle-" + value.finish();
    }

    static String inputs(MavenSession session, MojoExecution execution, List<Path> dependencies,
            List<Path> goalInputs) throws IOException {
        MavenProject project = session.getCurrentProject();
        Path root = project.getBasedir().toPath().toRealPath();
        Path build = path(root, project.getBuild().getDirectory());
        if (!build.startsWith(root) || build.equals(root)) throw new IOException("external build directory");
        M3LifecycleFingerprint value = new M3LifecycleFingerprint();
        value.text(key(execution));
        value.text(root.toString());
        value.text(session.getRequest().getBuilderId());
        value.text(session.getExecutionRootDirectory());
        value.text(Boolean.toString(session.isOffline()));
        value.text(Boolean.toString(session.getRequest().isUpdateSnapshots()));
        value.text(session.getRepositorySession().getLocalRepository().getBasedir().getAbsolutePath());
        value.properties(session.getSystemProperties(), true);
        value.properties(session.getUserProperties(), false);
        for (Map.Entry<String, String> entry : new TreeMap<>(System.getenv()).entrySet()) {
            value.text(entry.getKey());
            value.text(entry.getValue());
        }
        StringWriter model = new StringWriter();
        new MavenXpp3Writer().write(model, project.getModel());
        value.text(model.toString());
        value.configuration(execution.getConfiguration(), new PluginParameterExpressionEvaluator(session, execution));
        List<Path> reactorExclusions = reactorOutputExclusions(session, project, root, build);
        value.moduleTree(root, reactorExclusions);
        for (MavenProject parent = project; parent != null; parent = parent.getParent()) {
            if (parent.getFile() != null) value.tree(parent.getFile().toPath(), List.of());
        }
        for (Path directory = root; directory != null; directory = directory.getParent()) {
            for (String name : List.of("pom.xml", ".mvn/maven.config", ".mvn/jvm.config",
                    ".mvn/extensions.xml", ".mvn/toolchains.xml")) {
                value.tree(directory.resolve(name), List.of());
            }
        }
        for (File file : new File[] {session.getRequest().getUserSettingsFile(),
                session.getRequest().getGlobalSettingsFile(), session.getRequest().getUserToolchainsFile(),
                session.getRequest().getGlobalToolchainsFile()}) {
            if (file != null) value.tree(file.toPath(), List.of());
        }
        for (String source : project.getCompileSourceRoots()) {
            refuseNestedReactorOutputRoot(root, build, reactorExclusions, source);
            value.source(root, build, source);
        }
        for (String source : project.getTestCompileSourceRoots()) {
            refuseNestedReactorOutputRoot(root, build, reactorExclusions, source);
            value.source(root, build, source);
        }
        for (Resource resource : project.getResources()) {
            refuseNestedReactorOutputRoot(root, build, reactorExclusions, resource.getDirectory());
            value.source(root, build, resource.getDirectory());
        }
        for (Resource resource : project.getTestResources()) {
            refuseNestedReactorOutputRoot(root, build, reactorExclusions, resource.getDirectory());
            value.source(root, build, resource.getDirectory());
        }
        for (String filter : project.getFilters()) {
            Path input = path(root, filter);
            refuseInternalState(root, input);
            value.tree(input, List.of());
        }
        for (Path dependency : dependencies) {
            refuseInternalState(root, dependency);
            value.tree(dependency, List.of());
        }
        for (Path input : goalInputs) {
            refuseInternalState(root, input);
            value.tree(input, List.of());
        }
        String extra = M3LifecycleExecutionStrategy.property(session, "m3.lifecycle.inputs", "");
        if (!extra.isBlank()) {
            for (String declaration : extra.split(",", -1)) {
                if (declaration.isBlank()) throw new IOException("empty extra lifecycle input");
                Path input = path(root, declaration.trim());
                refuseInternalState(root, input);
                value.tree(input, List.of());
            }
        }
        for (Artifact artifact : execution.getMojoDescriptor().getPluginDescriptor().getArtifacts()) {
            if (artifact.getFile() == null) throw new IOException("unresolved plugin dependency");
            value.text(artifact.getId());
            value.tree(artifact.getFile().toPath(), List.of());
        }
        Artifact plugin = execution.getMojoDescriptor().getPluginDescriptor().getPluginArtifact();
        if (plugin == null || plugin.getFile() == null) throw new IOException("unresolved plugin artifact");
        value.tree(plugin.getFile().toPath(), List.of());
        value.code(M3LifecycleExecutionStrategy.class);
        value.code(ModuleStateDb.class);
        value.code(MavenSession.class);
        Path java = Path.of(System.getProperty("java.home")).toRealPath();
        for (String name : List.of("release", "lib/modules", "lib/ct.sym", "conf")) {
            value.tree(java.resolve(name), List.of());
        }
        return value.finish();
    }

    /**
     * Parent module scans do not acquire another reactor module's generated
     * output directory as an input. Original Maven reactor project metadata
     * identifies the exact output owners; source files and POMs of nested
     * modules remain in the source scan. Explicit dependencies and goal inputs
     * are still content-verified independently.
     */
    static List<Path> reactorOutputExclusions(MavenSession session, MavenProject current,
            Path root, Path build) throws IOException {
        List<Path> result = new ArrayList<>();
        result.add(build);
        List<MavenProject> projects = session.getProjects();
        if (projects == null) {
            return List.copyOf(result);
        }
        for (MavenProject other : projects) {
            if (other == null || other == current || other.getBasedir() == null || other.getBuild() == null) {
                continue;
            }
            Path module = other.getBasedir().toPath().toAbsolutePath().normalize();
            if (module.equals(root) || !module.startsWith(root)) {
                continue;
            }
            String outputDirectory = other.getBuild().getDirectory();
            if (outputDirectory == null || outputDirectory.isBlank()) {
                continue;
            }
            Path output = path(module, outputDirectory);
            if (!output.startsWith(module) || output.equals(module)) {
                throw new IOException("nested reactor module has unqualified build directory: " + output);
            }
            if (!result.contains(output)) {
                result.add(output);
            }
        }
        return List.copyOf(result);
    }

    /**
     * A parent Mojo may explicitly consume generated child-reactor files via
     * a customized source/resource root. Such output must never be omitted
     * from its source closure merely because a different reactor project owns
     * it. Fail closed to original Maven execution on any ancestor/descendant
     * overlap; direct dependency artifacts remain separately content-hashed.
     */
    static void refuseNestedReactorOutputRoot(Path root, Path build, List<Path> exclusions,
            String declaration) throws IOException {
        Path source = path(root, declaration);
        for (Path excluded : exclusions) {
            if (!excluded.equals(build) && (source.startsWith(excluded) || excluded.startsWith(source))) {
                throw new IOException("source or resource root overlaps another reactor module's output: " + source);
            }
        }
    }

    static String outputs(List<Path> outputs) throws IOException {
        M3LifecycleFingerprint value = new M3LifecycleFingerprint();
        value.text("outputs");
        for (Path output : outputs) value.tree(output, List.of());
        return value.finish();
    }

    static Path path(Path root, String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("missing Maven path");
        Path result = Path.of(value);
        return (result.isAbsolute() ? result : root.resolve(result)).toAbsolutePath().normalize();
    }

    private void source(Path root, Path build, String declaration) throws IOException {
        Path source = path(root, declaration);
        if (source.startsWith(build)) {
            // Javac itself adds these roots. Other generated inputs need a qualified producer.
            if (source.equals(build.resolve("generated-sources/annotations"))
                    || source.equals(build.resolve("generated-test-sources/test-annotations"))) return;
            throw new IOException("unqualified generated source root");
        }
        refuseInternalState(root, source);
        if (!source.startsWith(root)) tree(source, List.of());
    }

    private void code(Class<?> type) throws IOException {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null) throw new IOException("unavailable implementation identity");
        try {
            tree(Path.of(source.getLocation().toURI()), List.of());
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("unsupported implementation identity", failure);
        }
    }

    private void properties(Properties properties, boolean systemProperties) {
        // Embedded or concurrent Maven callers can snapshot another active
        // module's temporary lease entries. These coordinate writers; they are
        // not build inputs. Explicit user properties remain fully represented.
        List<String> names = properties.stringPropertyNames().stream()
                .filter(name -> !systemProperties || !ModuleStateDb.isJvmLeaseProperty(name))
                .sorted().toList();
        text("properties");
        text(Integer.toString(names.size()));
        for (String name : names) {
            text(name);
            text(properties.getProperty(name));
        }
    }

    private void configuration(Xpp3Dom element, PluginParameterExpressionEvaluator evaluator) throws IOException {
        if (element == null) { text("null-configuration"); return; }
        text("element");
        text(element.getName());
        text(Integer.toString(element.getAttributeNames().length));
        for (String name : java.util.Arrays.stream(element.getAttributeNames()).sorted().toList()) {
            text(name); text(element.getAttribute(name));
        }
        text(element.getValue());
        if (element.getValue() != null) {
            try {
                Object resolved = evaluator.evaluate(element.getValue());
                if (resolved instanceof CharSequence || resolved instanceof Number || resolved instanceof Boolean
                        || resolved instanceof File || resolved instanceof Path) text(resolved.toString());
                else if (resolved != null) text(resolved.getClass().getName());
            } catch (org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException failure) {
                throw new IOException("unresolved Maven configuration", failure);
            }
        }
        text(Integer.toString(element.getChildCount()));
        for (Xpp3Dom child : element.getChildren()) configuration(child, evaluator);
    }

    /** Internal coordination is not source input even when a module contains nested reactors. */
    private void moduleTree(Path root, List<Path> excluded) throws IOException {
        tree(root, excluded, true);
    }

    private static void refuseInternalState(Path root, Path input) throws IOException {
        if (input.startsWith(root) && isInternalState(root.relativize(input))) {
            throw new IOException("M3 internal state cannot be a build input: " + input);
        }
    }

    private static boolean isInternalState(Path relative) {
        for (Path part : relative) {
            if (part.toString().equals(".git")) {
                return true;
            }
        }
        return ModuleStateDb.reservedStatePath(relative.toString().replace('\\', '/'));
    }

    private void tree(Path requested, List<Path> excluded) throws IOException {
        tree(requested, excluded, false);
    }

    private void tree(Path requested, List<Path> excluded, boolean moduleRoot) throws IOException {
        Path root = requested.toAbsolutePath().normalize();
        text(root.toString());
        rejectLinks(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) { text("missing"); return; }
        List<Path> entries = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void admit(Path path, BasicFileAttributes attributes) throws IOException {
                if (++paths > MAX_PATHS) throw new IOException("lifecycle path budget exceeded");
                if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                    throw new IOException("non-ordinary lifecycle file: " + path);
                }
                entries.add(path);
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (excluded.stream().anyMatch(directory::startsWith)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (moduleRoot && !directory.equals(root)) {
                    Path relative = root.relativize(directory);
                    // Do not hash the M3 state parent directory markers (.m3 and
                    // the .m3/maven MIndexDB store parent), but descend to account
                    // for unrelated user configuration inside them.
                    if (ModuleStateDb.reservedStateParent(relative.toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (isInternalState(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                admit(directory, attributes);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!excluded.contains(file)
                        && !(moduleRoot && isInternalState(root.relativize(file)))) {
                    admit(file, attributes);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(path -> root.relativize(path).toString()));
        for (Path path : entries) {
            text(root.relativize(path).toString());
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.isDirectory()) { text("directory"); continue; }
            if (!before.isRegularFile()) throw new IOException("lifecycle file changed type");
            text("file");
            text(Long.toString(before.size()));
            MessageDigest file = sha256();
            long read = 0;
            try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = stream.read(buffer)) != -1;) {
                    if ((bytes += count) > MAX_BYTES) throw new IOException("lifecycle byte budget exceeded");
                    read += count;
                    file.update(buffer, 0, count);
                }
            }
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (read != before.size() || after.size() != before.size()
                    || !after.lastModifiedTime().equals(before.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                throw new IOException("lifecycle input changed while hashing");
            }
            text(HexFormat.of().formatHex(file.digest()));
        }
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path at = path; at != null; at = at.getParent()) {
            if (Files.isSymbolicLink(at)) throw new IOException("symlink in lifecycle file tree: " + at);
        }
    }

    private void text(String value) {
        if (value == null) { digest.update((byte) 0); return; }
        digest.update((byte) 1);
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (encoded.length >>> 24)); digest.update((byte) (encoded.length >>> 16));
        digest.update((byte) (encoded.length >>> 8)); digest.update((byte) encoded.length);
        digest.update(encoded);
    }

    private String finish() { return HexFormat.of().formatHex(digest.digest()); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
