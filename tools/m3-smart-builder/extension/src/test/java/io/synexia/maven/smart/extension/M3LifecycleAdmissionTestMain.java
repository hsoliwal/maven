/*
 * SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.smart.extension;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.DefaultRepositorySystemSession;
import io.synexia.maven.polyglot.ModuleStateDb;

/**
 * Admission and project-state contracts using actual Maven API/model objects.
 * These boundary checks do not substitute for the separate real-Maven execution harness.
 */
public final class M3LifecycleAdmissionTestMain {
    private static int checks;

    private M3LifecycleAdmissionTestMain() {}

    public static void main(String[] ignored) throws Exception {
        Path temporary = Files.createTempDirectory("m3-lifecycle-admission-");
        try {
            acceptsOnlyQualifiedGoalMetadata();
            rejectsUntrackedResourceOverrides(temporary);
            rejectsUntrackedCompilerConfiguration(temporary);
            recognizesActualProcessorDiscoveryInputs(temporary);
            recognizesProcessorsInTheProjectsOwnOutputs(temporary);
            tracksConfiguredArchiveFiles(temporary);
            preservesJarAndCompilerProjectState(temporary);
            rejectsUnqualifiedProjectLayouts(temporary);
            preservesCompilerCreatedFileInventory(temporary);
            excludesOnlyInternalSystemCoordination(temporary);
            System.out.println("PASS: " + checks + " Maven lifecycle admission and state assertions");
        } finally {
            try (var entries = Files.walk(temporary)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            }
        }
    }

    private static void acceptsOnlyQualifiedGoalMetadata() throws Exception {
        for (String name : List.of("resources", "testResources")) {
            check(M3LifecycleExecutionStrategy.known(goal("maven-resources-plugin", "3.3.1", name)),
                    "qualified resources goal is admitted");
        }
        for (String version : List.of("3.13.0", "3.14.1")) {
            for (String name : List.of("compile", "testCompile")) {
                check(M3LifecycleExecutionStrategy.known(goal("maven-compiler-plugin", version, name)),
                        "qualified compiler version and goal are admitted");
            }
        }
        for (String version : List.of("3.4.1", "3.4.2")) {
            check(M3LifecycleExecutionStrategy.known(goal("maven-jar-plugin", version, "jar")),
                    "qualified JAR version is admitted");
        }
        MojoExecution normal = goal("maven-compiler-plugin", "3.14.1", "compile");
        MojoExecution direct = new MojoExecution(normal.getMojoDescriptor(), "direct", MojoExecution.Source.CLI);
        direct.setLifecyclePhase("compile");
        check(!M3LifecycleExecutionStrategy.known(direct), "direct CLI goal execution is not silently skipped");
        normal.setLifecyclePhase(null);
        check(!M3LifecycleExecutionStrategy.known(normal), "unbound execution has no lifecycle admission");
        MojoExecution aggregator = goal("maven-compiler-plugin", "3.14.1", "compile");
        aggregator.getMojoDescriptor().setAggregator(true);
        check(!M3LifecycleExecutionStrategy.known(aggregator), "aggregator semantics remain with Maven");
        MojoExecution fork = goal("maven-compiler-plugin", "3.14.1", "compile");
        fork.getMojoDescriptor().setExecutePhase("test");
        check(!M3LifecycleExecutionStrategy.known(fork), "descriptor-declared fork is not cached");
        MojoExecution forked = goal("maven-compiler-plugin", "3.14.1", "compile");
        forked.setForkedExecutions("fixture", List.of(goal("maven-jar-plugin", "3.4.1", "jar")));
        check(!M3LifecycleExecutionStrategy.known(forked), "materialized fork executions remain with Maven");
        MojoExecution foreign = goal("maven-compiler-plugin", "3.14.1", "compile");
        foreign.getPlugin().setGroupId("example.foreign");
        foreign.getMojoDescriptor().getPluginDescriptor().setGroupId("example.foreign");
        check(!M3LifecycleExecutionStrategy.known(foreign), "a matching artifact name does not admit another provider");
        check(!M3LifecycleExecutionStrategy.known(goal("maven-compiler-plugin", "99.0", "compile")),
                "unqualified future plugin versions execute normally");
        check(!M3LifecycleExecutionStrategy.known(goal("maven-jar-plugin", "3.4.1", "test-jar")),
                "attached test JAR goal remains uncached");
        check(!M3LifecycleExecutionStrategy.known(new MojoExecution(new Plugin(), "compile", "missing")),
                "missing descriptor is not evidence");
        for (String[] entry : List.of(new String[] {"maven-surefire-plugin", "test"},
                new String[] {"maven-failsafe-plugin", "integration-test"},
                new String[] {"maven-failsafe-plugin", "verify"},
                new String[] {"maven-install-plugin", "install"},
                new String[] {"maven-deploy-plugin", "deploy"})) {
            MojoExecution execution = goal(entry[0], "3.0.0", entry[1]);
            check(!M3LifecycleExecutionStrategy.known(execution)
                            && Boolean.TRUE.equals(invoke("alwaysRun", new Class<?>[] {MojoExecution.class}, execution)),
                    "tests and publication retain their original execution path");
        }
    }

    private static void rejectsUntrackedResourceOverrides(Path temporary) throws Exception {
        Fixture fixture = fixture(temporary, "resources");
        for (String name : List.of("resources", "testResources")) {
            MojoExecution normal = goal("maven-resources-plugin", "3.3.1", name);
            Object paths = paths(fixture, normal);
            Path expected = fixture.root().resolve(name.equals("resources") ? "target/classes" : "target/test-classes");
            check(list(paths, "outputs").equals(List.of(expected)), "standard resources retain their exact output root");
            for (String property : List.of("resources", "filters")) {
                MojoExecution custom = goal("maven-resources-plugin", "3.3.1", name);
                Xpp3Dom values = new Xpp3Dom(property);
                Xpp3Dom value = new Xpp3Dom(property.equals("resources") ? "resource" : "filter");
                if (property.equals("resources")) {
                    Xpp3Dom directory = new Xpp3Dom("directory");
                    directory.setValue(temporary.resolve("external-resources").toString());
                    value.addChild(directory);
                } else value.setValue(temporary.resolve("external-filter.properties").toString());
                values.addChild(value);
                custom.getConfiguration().addChild(values);
                refused(() -> paths(fixture, custom), "complex resources/filters cannot hide external file contents");
            }
            MojoExecution customOutput = goal("maven-resources-plugin", "3.3.1", name);
            scalar(customOutput, "outputDirectory", temporary.resolve("external-output").toString());
            refused(() -> paths(fixture, customOutput), "custom resources output is outside the admitted contract");
        }
    }

    private static void rejectsUntrackedCompilerConfiguration(Path temporary) throws Exception {
        Fixture fixture = fixture(temporary, "compiler");
        for (String name : List.of("compile", "testCompile")) {
            check(paths(fixture, goal("maven-compiler-plugin", "3.14.1", name)) != null,
                    "default in-process javac layout is admitted");
            for (String argument : List.of("compilerArgument", "compilerArgs", "compilerArguments",
                    "testCompilerArgument", "testCompilerArgs", "testCompilerArguments")) {
                MojoExecution custom = goal("maven-compiler-plugin", "3.14.1", name);
                scalar(custom, argument, "@" + temporary.resolve("external-arguments.txt"));
                refused(() -> paths(fixture, custom), "every supported spelling of opaque compiler arguments is refused");
            }
            for (String property : List.of("jdkToolchain", "annotationProcessorPaths", "annotationProcessors", "compileSourceRoots")) {
                MojoExecution custom = goal("maven-compiler-plugin", "3.14.1", name);
                Xpp3Dom list = new Xpp3Dom(property);
                Xpp3Dom child = new Xpp3Dom("entry");
                child.setValue("external-input");
                list.addChild(child);
                custom.getConfiguration().addChild(list);
                refused(() -> paths(fixture, custom), "opaque compiler providers and roots require their own evidence");
            }
            for (String[] setting : List.of(new String[] {"fork", "true"},
                    new String[] {"executable", temporary.resolve("custom-javac").toString()},
                    new String[] {"compilerId", "eclipse"},
                    new String[] {"useIncrementalCompilation", "false"},
                    new String[] {name.equals("compile") ? "skipMain" : "skip", "true"},
                    new String[] {"outputDirectory", fixture.root().resolve("target/elsewhere").toString()},
                    new String[] {name.equals("compile") ? "generatedSourcesDirectory" : "generatedTestSourcesDirectory",
                            temporary.resolve("external-generated").toString()})) {
                MojoExecution custom = goal("maven-compiler-plugin", "3.14.1", name);
                scalar(custom, setting[0], setting[1]);
                refused(() -> paths(fixture, custom), "custom compiler behavior cannot inherit default output evidence");
            }
        }
    }

    private static void recognizesActualProcessorDiscoveryInputs(Path temporary) throws Exception {
        Fixture fixture = fixture(temporary, "processor");
        Path directory = Files.createDirectories(temporary.resolve("processor-directory/META-INF/services"));
        Files.writeString(directory.resolve("javax.annotation.processing.Processor"), "fixture.FixtureProcessor\n");
        Path jar = processorJar(temporary.resolve("processor-jar"), true);
        Path zip = Files.copy(jar, temporary.resolve("processor.zip"));
        Path plain = processorJar(temporary.resolve("plain-jar"), false);
        MojoExecution compiler = goal("maven-compiler-plugin", "3.14.1", "compile");
        for (Path dependency : List.of(directory.getParent().getParent(), jar)) {
            refused(() -> processors(fixture, compiler, List.of(dependency)),
                    "real directory and automatic-module service declarations require processor evidence");
        }
        processors(fixture, compiler, List.of(plain));
        check(true, "a valid JAR without processor discovery metadata remains eligible");
        refused(() -> processors(fixture, compiler, List.of(zip)),
                "a real processor archive renamed ZIP cannot bypass compiler classpath discovery admission");
        Path corrupt = Files.writeString(temporary.resolve("corrupt.jar"), "not a jar");
        refused(() -> processors(fixture, compiler, List.of(corrupt)), "uninspectable compiler dependencies fail closed");
        MojoExecution disabled = goal("maven-compiler-plugin", "3.14.1", "compile");
        scalar(disabled, "proc", "none");
        processors(fixture, disabled, List.of(jar));
        check(true, "explicitly disabled annotation processing does not execute a discovered processor");
        processors(fixture, disabled, List.of(zip));
        check(true, "proc none also disables discovery from an otherwise uninspectable ZIP classpath entry");
    }

    private static void recognizesProcessorsInTheProjectsOwnOutputs(Path temporary) throws Exception {
        for (String[] location : List.of(new String[] {"compile", "classes"},
                new String[] {"testCompile", "classes"}, new String[] {"testCompile", "test-classes"})) {
            Fixture fixture = fixture(temporary, "output-processor-" + location[0] + "-" + location[1]);
            MojoExecution compiler = goal("maven-compiler-plugin", "3.14.1", location[0]);
            processors(fixture, compiler, List.of());
            check(true, "ordinary project output roots without processor metadata remain eligible");
            Path services = Files.createDirectories(fixture.root().resolve("target/" + location[1] + "/META-INF/services"));
            Files.writeString(services.resolve("javax.annotation.processing.Processor"), "fixture.FixtureProcessor\n");
            refused(() -> processors(fixture, compiler, List.of()),
                    "Maven's own compiler classpath can discover processors in project output, even without dependency artifacts");
            MojoExecution disabled = goal("maven-compiler-plugin", "3.14.1", location[0]);
            scalar(disabled, "proc", "none");
            processors(fixture, disabled, List.of());
            check(true, "proc none keeps project-provided processor discovery disabled for the exact compile goal");
        }
    }

    private static void tracksConfiguredArchiveFiles(Path temporary) throws Exception {
        Fixture standard = fixture(temporary, "archive-standard");
        check(list(paths(standard, goal("maven-jar-plugin", "3.4.1", "jar")), "inputs")
                        .equals(List.of(standard.root().resolve("target/classes"))),
                "the standard archive retains its classes input without speculative external files");
        for (boolean external : List.of(false, true)) {
            Fixture fixture = fixture(temporary, external ? "archive-external" : "archive-build");
            Path build = Files.createDirectories(fixture.root().resolve("target"));
            Path manifest = Files.writeString(build.resolve("custom-manifest.mf"), "Manifest-Version: 1.0\n");
            Path properties = external ? temporary.resolve("external-pom.properties") : build.resolve("custom-pom.properties");
            Files.writeString(properties, "version=A\n");
            MojoExecution jar = goal("maven-jar-plugin", "3.4.1", "jar");
            Xpp3Dom archive = new Xpp3Dom("archive");
            Xpp3Dom manifestFile = new Xpp3Dom("manifestFile");
            manifestFile.setValue("${project.build.directory}/custom-manifest.mf");
            archive.addChild(manifestFile);
            Xpp3Dom propertiesFile = new Xpp3Dom("pomPropertiesFile");
            propertiesFile.setValue(external ? properties.toString() : "${project.build.directory}/custom-pom.properties");
            archive.addChild(propertiesFile);
            jar.getConfiguration().addChild(archive);
            Object archivePaths = paths(fixture, jar);
            List<Path> inputs = list(archivePaths, "inputs").stream().map(Path.class::cast).toList();
            check(inputs.equals(List.of(build.resolve("classes"), manifest, properties)),
                    "both archive files are resolved as exact inputs, including files outside the module or beneath target");
            check(list(archivePaths, "outputs").equals(List.of(build.resolve("fixture-1.jar"))),
                    "configured archive source files are inputs, not owned archive outputs");
            String original = M3LifecycleFingerprint.outputs(inputs);
            var propertiesTime = Files.getLastModifiedTime(properties);
            Files.writeString(properties, "version=B\n");
            Files.setLastModifiedTime(properties, propertiesTime);
            String changedProperties = M3LifecycleFingerprint.outputs(inputs);
            check(!original.equals(changedProperties),
                    "the common content fingerprint detects a same-size, same-time POM properties change");
            var manifestTime = Files.getLastModifiedTime(manifest);
            Files.writeString(manifest, "Manifest-Version: 2.0\n");
            Files.setLastModifiedTime(manifest, manifestTime);
            check(!changedProperties.equals(M3LifecycleFingerprint.outputs(inputs)),
                    "the common content fingerprint detects a same-size, same-time manifest change");
            check(list(paths(fixture, jar), "inputs").equals(inputs),
                    "file-content changes preserve resolved input identity while changing content evidence");
        }
    }

    private static void preservesJarAndCompilerProjectState(Path temporary) throws Exception {
        Fixture fixture = fixture(temporary, "restore");
        MojoExecution jar = goal("maven-jar-plugin", "3.4.1", "jar");
        Object jarPaths = paths(fixture, jar);
        Path artifact = fixture.root().resolve("target/fixture-1.jar");
        check(artifact.equals(accessor(jarPaths, "artifact")), "main JAR identity is the exact configured artifact path");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "recorded fixture bytes");
        restore(fixture, jar, jarPaths);
        check(fixture.project().getArtifact().getFile().equals(artifact.toFile()), "a JAR hit restores Maven's main artifact file");
        check(fixture.project().hasLifecyclePhase("package"), "a JAR hit restores completed lifecycle bookkeeping");
        for (String[] setting : List.of(new String[] {"classifier", "sources"},
                new String[] {"outputDirectory", temporary.resolve("other-jars").toString()},
                new String[] {"finalName", "../escaped"})) {
            MojoExecution custom = goal("maven-jar-plugin", "3.4.1", "jar");
            scalar(custom, setting[0], setting[1]);
            refused(() -> paths(fixture, custom), "attached or externally located archive cannot use main-JAR restoration");
        }
        for (String name : List.of("compile", "testCompile")) {
            MojoExecution compiler = goal("maven-compiler-plugin", "3.14.1", name);
            Object compilerPaths = paths(fixture, compiler);
            Path generated = fixture.root().resolve(name.equals("compile")
                    ? "target/generated-sources/annotations" : "target/generated-test-sources/test-annotations");
            Files.createDirectories(generated);
            restore(fixture, compiler, compilerPaths);
            List<String> roots = name.equals("compile") ? fixture.project().getCompileSourceRoots()
                    : fixture.project().getTestCompileSourceRoots();
            check(roots.contains(generated.toString()), "compiler hit restores the matching generated-source root");
            check(fixture.project().hasLifecyclePhase(name.equals("compile") ? "compile" : "test-compile"),
                    "compiler hit retains Maven lifecycle state");
        }
    }

    private static void rejectsUnqualifiedProjectLayouts(Path temporary) throws Exception {
        Fixture fixture = fixture(temporary, "layouts");
        MojoExecution compile = goal("maven-compiler-plugin", "3.14.1", "compile");
        fixture.project().setPackaging("war");
        refused(() -> paths(fixture, compile), "non-JAR packaging does not inherit Java JAR assumptions");
        fixture.project().setPackaging("jar");
        fixture.project().getBuild().addExtension(new Extension());
        refused(() -> paths(fixture, compile), "unqualified build extensions retain original behavior");
        fixture.project().getBuild().getExtensions().clear();
        fixture.project().getAttachedArtifacts().add(new DefaultArtifact("fixture", "extra", "1", "compile", "jar", "extra",
                new DefaultArtifactHandler("jar")));
        refused(() -> paths(fixture, compile), "existing artifact attachments are not reconstructed speculatively");
        fixture.project().getAttachedArtifacts().clear();
        fixture.project().getAttachedArtifacts().add(new DefaultArtifact("fixture", "fixture", "1", "compile", "pom", "consumer",
                new DefaultArtifactHandler("pom")));
        refused(() -> paths(fixture, compile), "a custom consumer POM cannot impersonate Maven's original lazy transformed artifact");
        fixture.project().getAttachedArtifacts().clear();
        check(Boolean.TRUE.equals(invoke("unchangedAttachments", new Class<?>[] {MavenProject.class, List.class},
                fixture.project(), List.of())), "empty Maven3 attachments preserve identity without materialization");
        fixture.project().getBuild().setDirectory(fixture.root().toString());
        refused(() -> paths(fixture, compile), "module-root output ownership is refused");
    }

    private static void preservesCompilerCreatedFileInventory(Path temporary) throws Exception {
        Fixture fixture = fixture(temporary, "compiler-inventory");
        MojoExecution compiler = goal("maven-compiler-plugin", "3.14.1", "compile");
        Object paths = paths(fixture, compiler);
        Path status = Files.createDirectories(fixture.root().resolve("target/maven-status/maven-compiler-plugin/compile/default-compile"));
        Path inputs = Files.writeString(status.resolve("inputFiles.lst"), "old input\n");
        Path created = Files.writeString(status.resolve("createdFiles.lst"), "Old.class\n");
        invoke("forceCompilerContentCheck", new Class<?>[] {MojoExecution.class, paths.getClass()}, compiler, paths);
        check(!Files.exists(inputs), "a verified content miss invalidates the compiler's timestamp-based input inventory");
        check(Files.readString(created).equals("Old.class\n"), "created-file inventory survives so javac can remove deleted-source outputs");
    }

    private static void excludesOnlyInternalSystemCoordination(Path temporary) throws Exception {
        Properties normal = new Properties();
        normal.setProperty("fixture.property", "original");
        String baseline = propertyFingerprint(normal, true);
        String leaseName;
        Path module = Files.createDirectories(temporary.resolve("property-coordination"));
        try (ModuleStateDb state = ModuleStateDb.open(module, "embedded")) {
            state.record("property-probe", "a".repeat(64), "b".repeat(64));
            List<String> leases = System.getProperties().stringPropertyNames().stream()
                    .filter(ModuleStateDb::isJvmLeaseProperty).toList();
            check(leases.size() == 1, "the active lease uses one recognizable string property");
            leaseName = leases.get(0);
            Properties withLease = new Properties();
            withLease.putAll(normal);
            withLease.setProperty(leaseName, System.getProperty(leaseName));
            check(baseline.equals(propertyFingerprint(withLease, true)),
                    "an embedded Maven system-property snapshot does not inherit temporary lease ownership");
            check(!propertyFingerprint(normal, false).equals(propertyFingerprint(withLease, false)),
                    "an explicitly supplied user property is still a build input even in the reserved namespace");
            withLease.setProperty(leaseName, "another-owner");
            check(baseline.equals(propertyFingerprint(withLease, true)),
                    "a concurrent owner's token does not perturb lifecycle fingerprints");
            withLease.setProperty("fixture.property", "changed");
            check(!baseline.equals(propertyFingerprint(withLease, true)),
                    "ordinary system properties still invalidate the fingerprint");
            withLease.setProperty("fixture.property", "original");
            withLease.setProperty(leaseName + "-user", "tracked");
            check(!ModuleStateDb.isJvmLeaseProperty(leaseName + "-user")
                            && !baseline.equals(propertyFingerprint(withLease, true)),
                    "lookalike property names cannot escape input tracking");
        }
        check(!System.getProperties().containsKey(leaseName), "closing the lease removes its coordination property");
    }

    private static String propertyFingerprint(Properties properties, boolean system) throws Exception {
        var constructor = M3LifecycleFingerprint.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object fingerprint = constructor.newInstance();
        Method collect = M3LifecycleFingerprint.class.getDeclaredMethod("properties", Properties.class, boolean.class);
        collect.setAccessible(true);
        collect.invoke(fingerprint, properties, system);
        Method finish = M3LifecycleFingerprint.class.getDeclaredMethod("finish");
        finish.setAccessible(true);
        return (String) finish.invoke(fingerprint);
    }

    private static Path processorJar(Path directory, boolean service) throws Exception {
        Files.createDirectories(directory);
        Path source = directory.resolve("FixtureProcessor.java");
        Files.writeString(source, """
                package fixture;
                public final class FixtureProcessor extends javax.annotation.processing.AbstractProcessor {
                    public java.util.Set<String> getSupportedAnnotationTypes() { return java.util.Set.of("*"); }
                    public javax.lang.model.SourceVersion getSupportedSourceVersion() {
                        return javax.lang.model.SourceVersion.RELEASE_17;
                    }
                    public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> annotations,
                            javax.annotation.processing.RoundEnvironment round) { return false; }
                }
                """, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new AssertionError("processor fixture requires a full JDK");
        int compiled = javac.run(null, null, null, "--release", "17", "-d", classes.toString(), source.toString());
        if (compiled != 0) throw new AssertionError("real processor fixture did not compile");
        Path jar = directory.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/FixtureProcessor.class"));
            output.write(Files.readAllBytes(classes.resolve("fixture/FixtureProcessor.class")));
            output.closeEntry();
            if (service) {
                output.putNextEntry(new JarEntry("META-INF/services/javax.annotation.processing.Processor"));
                output.write("fixture.FixtureProcessor\n".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }

    // Maven 3.9.9 deprecates its public session constructors; this fixture deliberately
    // constructs that real model without booting a second container or replacing its API.
    @SuppressWarnings("deprecation")
    private static Fixture fixture(Path temporary, String name) throws IOException {
        Path root = Files.createDirectories(temporary.resolve(name));
        Path pom = Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("fixture"); model.setArtifactId("fixture"); model.setVersion("1"); model.setPackaging("jar");
        Build build = new Build();
        build.setDirectory(root.resolve("target").toString());
        build.setOutputDirectory(root.resolve("target/classes").toString());
        build.setTestOutputDirectory(root.resolve("target/test-classes").toString());
        build.setFinalName("fixture-1");
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setFile(pom.toFile());
        project.setArtifact(new DefaultArtifact("fixture", "fixture", "1", "compile", "jar", null,
                new DefaultArtifactHandler("jar")));
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setBaseDirectory(root.toFile());
        request.setSystemProperties(new Properties());
        request.setUserProperties(new Properties());
        MavenSession session = new MavenSession(null, new DefaultRepositorySystemSession(), request,
                new DefaultMavenExecutionResult());
        session.setProjects(List.of(project));
        session.setCurrentProject(project);
        return new Fixture(root, session);
    }

    private static MojoExecution goal(String artifact, String version, String name) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins"); plugin.setArtifactId(artifact); plugin.setVersion(version);
        PluginDescriptor descriptor = new PluginDescriptor();
        descriptor.setGroupId(plugin.getGroupId()); descriptor.setArtifactId(artifact); descriptor.setVersion(version);
        descriptor.setPlugin(plugin);
        MojoDescriptor mojo = new MojoDescriptor();
        mojo.setPluginDescriptor(descriptor); mojo.setGoal(name);
        MojoExecution execution = new MojoExecution(mojo, "default-" + name, MojoExecution.Source.LIFECYCLE);
        execution.setLifecyclePhase(switch (name) {
            case "resources" -> "process-resources";
            case "testResources" -> "process-test-resources";
            case "testCompile" -> "test-compile";
            case "jar" -> "package";
            default -> name;
        });
        execution.setConfiguration(new Xpp3Dom("configuration"));
        return execution;
    }

    private static void scalar(MojoExecution execution, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        execution.getConfiguration().addChild(child);
    }

    private static Object paths(Fixture fixture, MojoExecution execution) throws Exception {
        return invoke("paths", new Class<?>[] {MavenSession.class, MojoExecution.class, Path.class},
                fixture.session(), execution, fixture.root());
    }

    private static void processors(Fixture fixture, MojoExecution execution, List<Path> dependencies) throws Exception {
        invoke("rejectProcessors", new Class<?>[] {MavenSession.class, MojoExecution.class, List.class},
                fixture.session(), execution, dependencies);
    }

    private static void restore(Fixture fixture, MojoExecution execution, Object paths) throws Exception {
        invoke("restore", new Class<?>[] {MavenProject.class, MojoExecution.class, paths.getClass()},
                fixture.project(), execution, paths);
    }

    private static List<?> list(Object value, String name) throws Exception {
        return (List<?>) accessor(value, name);
    }

    private static Object accessor(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }

    private static Object invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = M3LifecycleExecutionStrategy.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception exception) throw exception;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw wrapped;
        }
    }

    private static void refused(Checked action, String message) throws Exception {
        boolean refused = false;
        try { action.run(); }
        catch (IOException expected) { refused = true; }
        check(refused, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }

    private record Fixture(Path root, MavenSession session) {
        MavenProject project() { return session.getCurrentProject(); }
    }
}
