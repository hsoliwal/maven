/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.gradle.recipe;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.config.YamlResourceLoader;
import org.openrewrite.gradle.GradleParser;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.plugins.AddBuildPlugin;
import org.openrewrite.internal.InMemoryLargeSourceSet;

import static org.junit.jupiter.api.Assertions.*;

/** Source-pinned real rewrite-gradle 8.90.4 receiver; canonical recipes reside in com.synexia. */
final class M3GradleRecipeSdkAcceptanceTest {
    private record Row(String file, String sha256) {}

    private static final List<Row> ROWS = List.of(
        new Row("enable-m3-eclipse-descriptor-bridge.yml", "55b0fcba01b2749b799b1f06ceaa01d4fe9350c84bc5165c5cac5557b7c0f245"),
        new Row("enable-m3-llvm-compdb.yml", "aed5c10b547804caa0b56cd6364443129abeb00bcc4dcdb08497489e2c4cb2cd"),
        new Row("enable-m3-llvm-evidence-bridge.yml", "cf5e979fbc4ff843f95a06a71611b72d4783086e9917f70e70991eec30e6eab6"),
        new Row("enable-m3-llvm-provider-bridge.yml", "9d7483b1b4a6c92eaa9358ac895b1592c312d20172849e92379032a9876fc9a2"),
        new Row("enable-m3-native-manifest-bridge.yml", "afbebc519f275881c484f7e8be70ebd7196e2d2590a584617037f650780b4dbc"),
        new Row("enable-m3-polyglot-state-bridge.yml", "509be5ab617235b1dda114ac36c50cd5e5e36682e362b6e42b743b4387f93db0"),
        new Row("enable-m3-quality-bridge.yml", "679534de69f74010ef94a10c2fd9251bc132002e2690a51ade3552dd4efeb47a"),
        new Row("enable-m3jdk-db-module-access.yml", "c9b0e282d0dc4e3ff247ce09cee476175a8c64b88a34dd931dfa403b31db70bc")
    );

    @Test
    void originalAddBuildPluginResolvesForEveryPinnedGradleRecipeAndReachesFixedPoint() throws Exception {
        String selected = System.getProperty("m3.gradle.root");
        assertNotNull(selected, "exact Gradle #74 checkout root must be explicitly supplied");
        Path root = Path.of(selected).toAbsolutePath().normalize();
        Path folder = root.resolve("m3/quality-bridge/recipes");
        assertTrue(Files.isDirectory(folder));
        assertEquals(8L, Files.list(folder).filter(p -> p.getFileName().toString().endsWith(".yml")).count());

        var recipes = new ArrayList<Recipe>();
        for (Row row : ROWS) {
            Path file = folder.resolve(row.file());
            assertTrue(Files.isRegularFile(file));
            byte[] bytes = Files.readAllBytes(file);
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(row.sha256(), digest, row.file());
            String yaml = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(yaml.contains("org.openrewrite.gradle.plugins.AddBuildPlugin:"), row.file());
            assertFalse(yaml.contains("org.openrewrite.gradle.plugins.AddPlugin:"), row.file());
            assertTrue(yaml.contains("pluginId: io.synexia.m3.quality"), row.file());
            assertTrue(yaml.contains("version: '0.1.0-SNAPSHOT'"), row.file());
            String recipeName = yaml.lines()
                    .filter(line -> line.startsWith("name: "))
                    .findFirst().orElseThrow().substring(6);
            var source = new ByteArrayInputStream(bytes);
            var loader = new YamlResourceLoader(source, URI.create("file:///gradle/" + row.file()),
                    new Properties());
            Recipe activated = Environment.builder().load(loader).build().activateRecipes(recipeName);
            assertTrue(activated.validate().isValid(), recipeName);
            assertEquals(1, activated.getRecipeList().size());
            assertInstanceOf(AddBuildPlugin.class, activated.getRecipeList().getFirst());
            recipes.add(activated);
        }

        var errors = new ArrayList<Throwable>();
        List<SourceFile> input = GradleParser.builder().build().parseInputs(
                List.of(Parser.Input.fromString(Path.of("build.gradle"),
                        "plugins {\n    id 'java'\n}\n")),
                null, new InMemoryExecutionContext(errors::add)).toList();
        assertTrue(errors.isEmpty(), () -> "Gradle syntax parsing errors: " + errors);
        assertEquals(1, input.size());
        SourceFile original = input.getFirst().withMarkers(input.getFirst().getMarkers()
                .add(GradleProject.builder().name("original-gradle-consumer").build()));
        List<Result> changes = run(recipes.getFirst(), List.of(original));
        assertEquals(1, changes.size(), "original plugin insertion must execute");
        SourceFile installed = changes.getFirst().getAfter();
        assertNotNull(installed);
        String source = installed.printAll();
        assertTrue(source.contains("io.synexia.m3.quality"), source);
        assertTrue(source.contains("0.1.0-SNAPSHOT"), source);
        for (Recipe recipe : recipes) {
            assertTrue(run(recipe, List.of(installed)).isEmpty(),
                    "all eight migrated recipes must be idempotent: " + recipe.getName());
        }
        assertTrue(run(recipes.getFirst(), List.of(installed)).isEmpty(),
                "repeat OpenRewrite SDK replay must be fixed-point");
    }

    private static List<Result> run(Recipe recipe, List<SourceFile> files) {
        var failures = new ArrayList<Throwable>();
        var set = new InMemoryLargeSourceSet(files);
        var result = recipe.run(set, new InMemoryExecutionContext(failures::add), 1);
        assertTrue(failures.isEmpty(), () -> "OpenRewrite errors: " + failures);
        return result.getChangeset().getAllResults();
    }
}
