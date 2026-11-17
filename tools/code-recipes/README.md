<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
# Apache Maven Code Recipes

The fork's home for [OpenRewrite](https://docs.openrewrite.org) recipes. Recipes that change or search builds,
workflows and sources are kept here, one recipe per class, each with tests, so that the same change can be run on
this repository and on any other one.

The module (`org.apache.maven.plugins:maven-code-recipes`, a plain jar) is built on the Apache-2.0 OpenRewrite core:
`rewrite-core`, `rewrite-yaml`, `rewrite-xml`, `rewrite-maven` and `rewrite-java` 8.89.0, in `provided` scope because the recipe
runner (rewrite-maven-plugin, the Gradle plugin) brings them. 8.89.0 is the version rewrite-maven-plugin 6.46.1
runs recipes on. No module under the Moderne Source Available License is used; `FOSS_REUSE_DECISION.tsv` lists
what was checked and why.

## Recipes

| Recipe | Kind | Options |
|---|---|---|
| `org.apache.maven.recipes.SetupJavaMinimumVersion` | change | `minimumJavaMajorVersion` (8 to 99, default 21) |
| `org.apache.maven.recipes.FindSetupJavaVersionBelowMinimum` | search | `minimumJavaMajorVersion` (8 to 99, default 21) |
| `org.apache.maven.recipes.FindJavaLevelAboveMaximum` | search | `maximumJavaMajorVersion` (8 to 99, default 21) |
| `org.apache.maven.recipes.Jdk21Baseline` | composite (`META-INF/rewrite/jdk21.yml`) | the three above at 21 |
| `org.apache.maven.recipes.AddSetupJavaMatrixVersion` | change | `javaMajorVersion` (8 to 99, default 21) |
| `org.apache.maven.recipes.FindWorkflowWithoutSetupJavaVersion` | search | `javaMajorVersion` (8 to 99, default 21) |
| `org.apache.maven.recipes.Jdk21CiLeg` | composite (`META-INF/rewrite/jdk21.yml`) | the two above at 21 |
| `org.apache.maven.recipes.UseCoreResourceFilter` | search (Java) | none |

### SetupJavaMinimumVersion

In `.github/workflows/*.yml` and `*.yaml`, raises each `java-version` of an `actions/setup-java` step that is below
the minimum Java major version to the minimum, in the shapes listed here. `FindSetupJavaVersionBelowMinimum` marks
the other shapes listed in its own section. Two shapes get no marker at all: a YAML alias whose anchor is outside the
step, and a matrix axis given as an expression.

* Scalar values: `17`, `'17'`, `"17"`, `17.0.2`, `17-ea` and the old `1.8` form all read as a major version. The
  new value keeps the quoting style and any comment on the line. A literal or folded block (`java-version: |`,
  `>`, `|-` and the like) that holds a single version is raised in place: only the version text changes, so the
  block indicator and the indentation stay as they were.
* Matrix lists: when the value is `${{ matrix.<axis> }}`, the list `jobs.<job>.strategy.matrix.<axis>` of the same
  job is raised, in flow (`['17', '25']`) or block (`- 17`, `- |` blocks) form. A raised entry whose new value the
  list already holds is dropped, so `['17', '21', '25']` becomes `['21', '25']` and `[8, 11, 17, 25]` becomes
  `[21, 25]`. The comment on the line of a dropped block entry is dropped with it; the other comments stay on
  their lines.
* Matrix `include`: the axis value of each `include` object is raised as well (an include for a value the matrix
  no longer holds would otherwise add a job on the old JDK). An object that becomes equal to another one (the same
  keys and values, in any order) is dropped. An include that adds keys to the matching jobs
  (`{java: 17, experimental: true}`) then adds them to the jobs of the minimum.
* Matrix `exclude`: an exclude value is raised when the raise renames it, that is when the axis list gets the
  minimum from this value alone (`[17, 25]` with an exclude for `17`: the exclude becomes `21`). When values merge
  (`[17, 21]` becomes `[21]`), the exclude is kept as it is and matches no job. When only one of the merged values
  carried that exclude, the jobs of the minimum run as they did before. When every merged value carried an equal
  exclude, a job on the minimum appears that was excluded before. `FindSetupJavaVersionBelowMinimum` marks such
  stale excludes.
* Kept as they are: values at or above the minimum, expressions (`${{ inputs.java }}`), names such as `lts`,
  aliases (`*jdk`), multi-line values that install several JDKs (`java-version: |` with several lines),
  `java-version-file`, `java-version` of other actions, matrix lists no setup-java step reads, a matrix axis given
  as an expression (`${{ fromJSON(...) }}`), and YAML outside `.github/workflows`. A second run changes nothing.

```yaml
# before                                   # after
matrix:                                    matrix:
  java: ['17', '25']                         java: ['21', '25']
  include:                                   include:
    - java: '17'                               - java: '21'
      os: macos-latest                           os: macos-latest
steps:                                     steps:
  - uses: actions/setup-java@v4              - uses: actions/setup-java@v4
    with:                                      with:
      java-version: ${{ matrix.java }}           java-version: ${{ matrix.java }}
  - uses: actions/setup-java@v4              - uses: actions/setup-java@v4
    with:                                      with:
      java-version: |                            java-version: |
        17                                         21
```

### FindSetupJavaVersionBelowMinimum

Marks the `actions/setup-java` Java versions below the minimum that `SetupJavaMinimumVersion` does not raise, in
`.github/workflows/*.yml` and `*.yaml` and in composite actions (`action.yml` and `action.yaml` in any directory,
such as `.github/actions/<name>/action.yml`). It only adds search markers:

| Shape | Marker |
|---|---|
| a `with` written as a nested flow mapping, `with: {java-version: 17}`, whose `java-version` is below the minimum or reads the matrix (rewrite-yaml keeps a nested flow mapping as plain text) | `java-version in a flow mapping is not raised` |
| a matrix, or a matrix `include` or `exclude` object, written as a nested flow mapping (`matrix: {java: [17, 21]}`, `include: [{java: 17, os: macos-latest}]`), with an axis a setup-java step reads holding a value below the minimum | `matrix in a flow mapping is not raised` |
| a multi-line `java-version` with a line below the minimum or a `${{ matrix.<axis> }}` line | `multi-line java-version is not raised` |
| a `java-version` below the minimum in the `runs.steps` of a composite action | `java-version of a composite action is not raised` |
| a matrix `exclude` value below the minimum that the axis list does not hold | `Java 17 matches no value of matrix axis java` |

Run after `SetupJavaMinimumVersion` (as `Jdk21Baseline` does), its markers are the values left to change by hand.

### FindJavaLevelAboveMaximum

Marks the POM Java levels above the maximum, so that the builds a JDK of that version cannot compile are listed. It
looks at `maven.compiler.release`, `maven.compiler.source` and `maven.compiler.target` (and their `testRelease`,
`testSource`, `testTarget` forms) in the project's or a profile's `<properties>`, at the same parameters in the
`<configuration>` of `maven-compiler-plugin` or of one of its `<execution>`s (under `<plugins>` or
`<pluginManagement>`), and at the levels its `<compilerArgs>` pass to javac (`--release 25`, `--release=25`,
`--source`, `-source`, `--target`, `-target`). It only adds search markers:

```xml
<!--~~(Java 25 is above 21)~~>--><maven.compiler.release>25</maven.compiler.release>
```

`${...}` references are resolved first, against the POM model: the properties of the project, of its parents and
of its active profiles; inside a `<profile>`, that profile's own `<properties>` come first, whether or not it is
active. Only a key that none of these defines is read from the JVM that runs the recipe (system properties such as
`java.version`, then `project.*` values), as Maven does; a POM property of the same name always wins. Limits:

* Parent POMs count only when rewrite-maven resolves them (a `relativePath` in the same run, or a repository).
* A profile that redefines a property is not applied to the tags outside it, unless the profile is active in the
  run; user properties (`-Dkey=value` of a Maven build) are not known.
* The single-string `<compilerArgument>` parameter, `<compilerArgs>` of other plugins and levels set in
  `.mvn/maven.config` or `.mvn/jvm.config` are not read.

### Jdk21Baseline

Runs `SetupJavaMinimumVersion` with minimum 21, then `FindSetupJavaVersionBelowMinimum` with minimum 21 and
`FindJavaLevelAboveMaximum` with maximum 21.

### AddSetupJavaMatrixVersion

In `.github/workflows/*.yml` and `*.yaml`, adds the Java major version to the matrix axis list
`jobs.<job>.strategy.matrix.<axis>` when an `actions/setup-java` step of the same job reads that axis
(`java-version: ${{ matrix.<axis> }}`) and the list lacks the version, so that the workflow gets jobs on it.

* A list holds the version when one of its entries has that major version: `21`, `'21'`, `21.0.2`, `21-ea` (and
  `1.8` for 8).
* The version goes in ascending order: before the first entry with a higher major version, or last when there is
  none, in flow (`['17', '25']`) or block (`- 17`) form. It is a copy of the entry before it (of the first entry when
  it goes first), so it takes that entry's quoting style; an anchor on that entry is not copied.
* Comments stay on their lines. In a block list the comment of the entry before the new one stays on that entry's
  line, also when the new entry goes last (rewrite-yaml keeps that comment in the prefix of the node after the list,
  and it is moved back in front of the new entry). In a multi-line flow list the new comma goes before the comment. A
  trailing comma (`[11, 17,]`) stays last.
* Kept as they are: `include` and `exclude` (GitHub Actions then adds an `include` object for the version to the new
  jobs whose values it does not change, instead of making a job of its own, and an `exclude` for the version removes
  the matching new jobs), axes given as an expression (`${{ fromJSON(...) }}`) or an alias (`*versions`), lists that
  carry an anchor (their aliases would change too), empty lists, lists with an entry that is not a plain or quoted
  version (an expression, `lts`, an alias, a `|` block, a nested list), matrices and `with` inputs written as nested
  flow mappings, axes read by other actions or by reusable workflow calls, values such as `${{ matrix.java }}-ea`, and
  YAML outside `.github/workflows`. A second run changes nothing.

```yaml
# before                                   # after
matrix:                                    matrix:
  java: ['11', '17']                         java: ['11', '17', '21']
  jdk:                                       jdk:
    - 17 # LTS                                 - 17 # LTS
    - 25 # current                             - 21
                                               - 25 # current
steps:                                     steps:
  - uses: actions/setup-java@v4              - uses: actions/setup-java@v4
    with:                                      with:
      java-version: ${{ matrix.java }}           java-version: ${{ matrix.java }}
  - uses: actions/setup-java@v4              - uses: actions/setup-java@v4
    with:                                      with:
      java-version: ${{ matrix.jdk }}            java-version: ${{ matrix.jdk }}
```

### FindWorkflowWithoutSetupJavaVersion

Marks the `.github/workflows/*.yml` and `*.yaml` files that set up Java and in which nothing can run the Java major
version, so that the shapes `AddSetupJavaMatrixVersion` does not change are listed for review by hand: workflows
whose `java-version` values are all scalars that differ, and reusable workflows (callers that pass a Java version as
an input, workflows that take it as an input). The marker is on the YAML document, so it is printed in front of its
first key, after any leading comments:

```yaml
~~(no actions/setup-java step or reusable workflow input runs Java 21)~~>jobs:
```

* Java sources are the `actions/setup-java` steps of the jobs and the inputs of reusable workflow calls
  (`jobs.<job>.uses`) whose name holds `java` or `jdk` and whose value names a Java version or a matrix axis
  (`jdk-matrix: '[ "11", "17" ]'`, `java-version: ${{ matrix.java }}`). A file without Java sources is not marked.
* A source runs the version when one of its values has that major version: a scalar `java-version`, a line of a
  multi-line one, a value in a `with` written as a nested flow mapping, a version in the text of an input, or, for
  `${{ matrix.<axis> }}`, a value that the job's matrix gives the axis (its list or one of its `include` objects,
  also when written as nested flow mappings).
* Expressions (`${{ inputs.java-version }}`, whatever the input's default), names such as `lts`, aliases,
  `java-version-file` and setup-java steps without `java-version` do not run it. `exclude` objects are not read.
* Not read: composite actions and steps that use them, reusable workflow calls without a Java input (their JDKs are
  set in the called workflow), and workflows that use the runner's JDK or a container image instead of
  `actions/setup-java`.

Run after `AddSetupJavaMatrixVersion` (as `Jdk21CiLeg` does), its markers are the workflows left without the version.

### Jdk21CiLeg

Runs `AddSetupJavaMatrixVersion` with version 21, then `FindWorkflowWithoutSetupJavaVersion` with version 21. A
build whose POM Java level is above 21 cannot compile on an added Java 21 job; `FindJavaLevelAboveMaximum` (part of
`Jdk21Baseline`) lists those POMs.

### UseCoreResourceFilter

For plugins written against the Maven 4 API (a source that imports or uses an `org.apache.maven.api` type), marks
each `org.apache.maven.shared.filtering.MavenResourcesFiltering.filterResources(MavenResourcesExecution)` call
(overrides included) with its migration to the core `ResourceFilter` service: the `ResourceFilterRequest` builder
method each `MavenResourcesExecution` setter called on the same variable in the same method maps to, the setters
whose values the request callback supplies, and the setters without an equivalent:

```java
/*~~(ResourceFilter.copy(ResourceFilterRequest); builder: setEncoding -> encoding, setEscapeString -> escapeString,
setOutputDirectory -> outputDirectory; callback: setMavenProject; no equivalent: setFilterFilenames)~~>*/
filtering.filterResources(execution);
```

| Setters | Request |
|---|---|
| `setResources`, `setOutputDirectory`, `setResourcesBaseDirectory`, `setMavenSession`, `setEncoding`, `setPropertiesEncoding`, `setAdditionalProperties`, `setDelimiters`, `setEscapeString`, `setSupportMultiLineFiltering`, `setEscapeWindowsPaths`, `setNonFilteredFileExtensions`, `setAddDefaultExcludes`, `setIncludeEmptyDirs`, `setFlatten`, `setChangeDetection`, `setOverwrite` | builder methods `roots`, `outputDirectory`, `baseDirectory`, `session`, `encoding`, `propertiesEncoding`, `properties`, `delimiters`, `escapeString`, `supportMultiLineFiltering`, `escapeWindowsPaths`, `nonFilteredFileExtensions`, `useDefaultExcludes`, `includeEmptyDirs`, `flatten`, `changeDetection` (twice) |
| `setMavenProject`, `setFilters`, `setFileFilters`, `setInjectProjectBuildFilters`, `setUseDefaultFilterWrappers` | values supplied by `callback` |
| anything else (`setFilterFilenames`, `setProjectStartExpressions`, `setInterpolatorCustomizer`, filter wrappers) | no equivalent |

Search only: a `MavenResourcesExecution` holds model `Resource`s where the request holds `SourceRoot`s, and the call
site has no `ResourceFilter` handle, so no call is rewritten one to one. Maven 3 plugins are not marked, since they
cannot use Maven 4 services. The service and its parity with maven-filtering are described in the site page
`impl/maven-impl/src/site/markdown/resource-filter.md`.

## Running the recipes on any repository

Install the module once (it is not published to a remote repository):

```sh
cd tools/code-recipes
mvn install
```

Then, at the root of the repository to change (it needs a `pom.xml`; for a repository without one, a minimal POM
with only `groupId`, `artifactId`, `version` and `<packaging>pom</packaging>` is enough):

```sh
# list what would change; the patch is written to target/rewrite/rewrite.patch
mvn org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=org.apache.maven.plugins:maven-code-recipes:0.1.0-SNAPSHOT \
  -Drewrite.activeRecipes=org.apache.maven.recipes.Jdk21Baseline

# apply the workflow changes
mvn org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates=org.apache.maven.plugins:maven-code-recipes:0.1.0-SNAPSHOT \
  -Drewrite.activeRecipes=org.apache.maven.recipes.SetupJavaMinimumVersion

# list the Java 21 matrix legs to add and the workflows left without Java 21
mvn org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=org.apache.maven.plugins:maven-code-recipes:0.1.0-SNAPSHOT \
  -Drewrite.activeRecipes=org.apache.maven.recipes.Jdk21CiLeg

# add the Java 21 matrix legs
mvn org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates=org.apache.maven.plugins:maven-code-recipes:0.1.0-SNAPSHOT \
  -Drewrite.activeRecipes=org.apache.maven.recipes.AddSetupJavaMatrixVersion
```

`rewrite:run` writes search markers into the files: as XML comments in a POM, and as text inside the value in YAML
(`java-version: ~~(...)~~>17`), which breaks the workflow. So run the composites and the `Find` recipes with
`dryRun` only (the markers are then in the patch), and use `run` with `SetupJavaMinimumVersion` or
`AddSetupJavaMatrixVersion` alone, which add no markers, to apply changes. Options other than
the defaults go through a declarative recipe of your own (a `rewrite.yml`, see `-Drewrite.configLocation`). The
commands were tested with rewrite-maven-plugin 6.46.1.

## Adding a recipe

1. Check for an existing recipe first (OpenRewrite core modules, Apache-2.0 recipe modules) and add a row to
   `FOSS_REUSE_DECISION.tsv` with its license and why it is used, wrapped or not used. Modules under the Moderne
   Source Available License or the Moderne Proprietary License are not used.
2. One recipe per class in `org.apache.maven.recipes`, with a display name (no final period), a description (ending
   with a period), `@Option` fields with a `validate()` check, a `@JsonCreator` constructor, `equals` and `hashCode`.
   Visitors and helpers go in their own package-private classes.
3. Tests with `rewrite-test` (`RewriteTest`) for each behaviour, including the cases that must not change; they run
   with `mvn verify`. `RewriteTest` also checks that a second cycle changes nothing.
4. Composites go in `src/main/resources/META-INF/rewrite/*.yml`, with a test that loads them through
   `RecipeSpec.recipeFromResources`.

## Building

```sh
mvn verify
```

Java 17 or newer (built with `--release 17`, `-Xlint:all -Werror`).
