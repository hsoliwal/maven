---
title: Resource Filter Service
date: 2026-11-17
---

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

# Resource Filter Service

`org.apache.maven.api.services.ResourceFilter` (experimental, since 4.2.0) copies resource roots and replaces
expressions in their text on the way. It is the opt-in Maven 4 API replacement for
`org.apache.maven.shared:maven-filtering`; Maven 3 plugins keep using maven-filtering unchanged.
The implementation is `DefaultResourceFilter`, a `@Named @Singleton` component of this module. Values are resolved
by the `Interpolator` service and files are selected by the `PathMatcherFactory` service.

## Use from a Maven 4 plugin

```java
@Inject
private Session session;

@Inject
private Project project;

ResourceFilter filter = session.getService(ResourceFilter.class);
ProjectManager projects = session.getService(ProjectManager.class);
ResourceFilterResult result = filter.copy(ResourceFilterRequest.builder()
        .session(session)
        .roots(projects.getEnabledSourceRoots(project, ProjectScope.MAIN, Language.RESOURCES).toList())
        .outputDirectory(project.outputDirectory(ProjectScope.MAIN))
        .baseDirectory(project.getBasedir())
        .properties(additionalProperties)
        .callback(name -> projectValue(name))      // project.*, project properties, filter files
        .escapeString("\\")
        .build());
```

`filter.filter(Reader, request)` and `filter.filter(String, request)` filter text with the same expression
settings, without touching files.

## Request

| builder method | default | maven-filtering equivalent |
|---|---|---|
| `session` | required | `setMavenSession` |
| `roots` (`SourceRoot`s, first root wins on overlapping targets) | none | `setResources` |
| `outputDirectory` (required by `copy`) | none | `setOutputDirectory` |
| `baseDirectory` (for relative root directories) | session top directory | `setResourcesBaseDirectory` |
| `properties` (looked up first) | empty | `setAdditionalProperties` |
| `callback` (looked up last) | none | project value sources, `setFilters` files |
| `delimiters` (`BEGIN*END` or `TOKEN`; empty means `${*}` only) | `${*}`, `@` | `setDelimiters` |
| `escapeString` | none | `setEscapeString` |
| `supportMultiLineFiltering` | false | `setSupportMultiLineFiltering` |
| `escapeWindowsPaths` | true | `setEscapeWindowsPaths` |
| `encoding` | UTF-8 | `setEncoding` (maven-filtering: platform encoding) |
| `propertiesEncoding` (for `*.properties`) | the encoding | `setPropertiesEncoding` |
| `nonFilteredFileExtensions` (added to jpg jpeg gif bmp png ico) | none | `setNonFilteredFileExtensions` |
| `useDefaultExcludes` | true | `setAddDefaultExcludes` |
| `includeEmptyDirs` | false | `setIncludeEmptyDirs` |
| `flatten` | false | `setFlatten` |
| `changeDetection` (`TIMESTAMP`, `CONTENT`, `TIMESTAMP_AND_CONTENT`, `ALWAYS`, `NEVER`) | `CONTENT` | `setChangeDetection` |

Values are looked up in `properties`, then in the session's effective properties (user properties over system
properties), then through `callback`. maven-filtering's `filterFilenames`, `injectProjectBuildFilters`,
`projectStartExpressions`, `interpolatorCustomizer`, filter wrappers and `BuildContext` incremental deltas have no
equivalent: callers resolve filter files and project values in the callback.

## Expression rules

* An expression starts with a begin token and ends at the first following end token. Without multi-line filtering
  it also ends, unresolved, at `\n`. It is searched for in a window of 255 characters plus the lengths of the escape
  string and of all delimiters; a longer expression is copied as written.
* The body is resolved as `${body}` by the `Interpolator`, so `${a:-default}`, `${a:+alternate}` and nested
  `${...}` in property values work. A leading `.` is dropped. A body with `$`, braces or a backslash is looked up as
  is. An unresolved expression is copied unchanged; a cycle fails the copy.
* The escape string before a begin token is removed and the expression is copied literally (`\${a}` gives `${a}`);
  before any other character the escape string is kept.
* Characters between expressions are copied as read, so `\n`, `\r\n`, `\r` and a byte order mark are preserved.
  Files are decoded strictly (malformed input fails the copy with the encoding in the message) and encoded with
  replacement of unmappable characters.

## Parity with maven-filtering

`ResourceFilterParityTest` runs 41 texts (delimiters, escapes, line endings, windows, values) through
maven-filtering 3.5.0 `MultiDelimiterInterpolatorFilterReaderLineEnding` (wired as `BaseFilter.Wrapper` wires it, in
4 configurations), `InterpolatorFilterReaderLineEnding` (wired as `MavenResourcesExecution.addFilerWrapperWithEscaping`)
and plexus-utils `InterpolationFilterReader`, and writes `target/resource-filter-parity/READER_PARITY.tsv`.
`ResourceFilterCopyParityTest` compares files with `FilteringUtils.copyFile` for every change detection mode and
target state (`CHANGE_DETECTION_PARITY.tsv`) and for CRLF, BOM, ISO-8859-1 and binary files. Known differences,
asserted to stay different:

| case | maven-filtering 3.5.0 | ResourceFilter |
|---|---|---|
| escape string at the end of the input | appends `U+FFFF` | copies the escape string |
| begin token starting with the first character of a multi-character escape string (`#{a}`, escape `##`) | not recognised when it is the first declared delimiter | recognised |
| `${a:-x}`, `${a:+x}` | looks up the name `a:-x` | Interpolator operators |
| a property value containing a custom delimiter (`@b@`) | expanded | kept (only `${...}` is expanded in values) |
| a value containing `\{`, `\}` or `$__` | kept | unescaped by the Interpolator |
| unmappable character with `ALWAYS`, `NEVER` or `TIMESTAMP` | write fails | replaced, as in `CONTENT` |
| `copyFile` return value in the content modes | `false` even when written | `written` is true when written |
| Windows path pattern | 3.5.0: drive letter and backslash (master also matches relative backslash paths) | as 3.5.0 |

The single-delimiter reader and plexus-utils differ further (no search window; plexus-utils keeps a leading dot,
misses `${` right after `$` and does not resolve values); those cases are listed in the test.

## Migration recipe

`org.apache.maven.recipes.UseCoreResourceFilter` in `tools/code-recipes` marks `MavenResourcesFiltering`
`filterResources` calls in Maven 4 API plugins with the request builder method each `MavenResourcesExecution`
setter of the same method maps to, and the setters without an equivalent.
