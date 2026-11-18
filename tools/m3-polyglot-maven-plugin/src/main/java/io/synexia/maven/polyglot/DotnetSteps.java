/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.maven.polyglot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Mavenizer steps of a .NET folder (a {@code .sln}, {@code .slnx}, {@code .csproj}, {@code .fsproj} or
 * {@code .vbproj}): {@code dotnet restore} in validate ({@code --locked-mode} next to a {@code packages.lock.json}),
 * {@code dotnet build} in compile, {@code dotnet test} with the TRX logger in test, run through
 * {@code @m3:surefire:interpreter:trx} so its results become surefire reports even when tests fail, and
 * {@code dotnet pack} into {@value #PACKAGES} in package, where the attach goal finds the packages.
 */
final class DotnetSteps {
    static final List<String> DESCRIPTORS = List.of(".sln", ".slnx", ".csproj", ".fsproj", ".vbproj");
    static final String RESULTS = "target/dotnet-test-results";
    static final String REPORTS = "target/surefire-reports";
    static final String PACKAGES = "target/nuget";
    static final String TEST_TOOL = "@m3:surefire:interpreter:trx";
    static final String PACKAGE_STEP = "dotnet-package";

    private DotnetSteps() { }

    static boolean detects(Path dir) throws IOException {
        for (String suffix : DESCRIPTORS) {
            if (Mavenizer.any(dir, suffix)) return true;
        }
        return false;
    }

    /** Adds the four steps when the folder holds a .NET solution or project file. */
    static void add(Path dir, List<Mavenizer.Step> steps) throws IOException {
        if (!detects(dir)) return;
        List<String> restore = new ArrayList<>(List.of("restore"));
        if (Mavenizer.has(dir, "packages.lock.json")) restore.add("--locked-mode");
        steps.add(new Mavenizer.Step("validate", "dotnet-validate", "dotnet", restore));
        steps.add(new Mavenizer.Step("compile", "dotnet-compile", "dotnet", List.of("build")));
        steps.add(new Mavenizer.Step("test", "dotnet-test", TEST_TOOL, List.of(RESULTS, REPORTS,
                "dotnet", "test", "--logger", "trx", "--results-directory", RESULTS)));
        steps.add(new Mavenizer.Step("package", PACKAGE_STEP, "dotnet", List.of("pack", "--output", PACKAGES)));
    }

    /** Whether the steps pack NuGet packages, which the generated profile then attaches. */
    static boolean packs(List<Mavenizer.Step> steps) {
        return steps.stream().anyMatch(step -> PACKAGE_STEP.equals(step.name()));
    }
}
