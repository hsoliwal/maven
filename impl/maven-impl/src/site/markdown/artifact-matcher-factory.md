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
# Artifact Matcher Factory

`org.apache.maven.api.services.ArtifactMatcherFactory` (Maven 4.2.0, `@Experimental`) selects artifacts by
coordinate patterns, scope and coordinate values. It is the coordinate twin of `PathMatcherFactory`, and it gives
Maven 4 plugins the selection that Maven 3 plugins get from Apache Maven Common Artifact Filters
(`PatternIncludesArtifactFilter`, `PatternExcludesArtifactFilter`, and `ScopeFilter`, `GroupIdFilter`,
`ArtifactIdFilter`, `TypeFilter`, `ClassifierFilter` of its `collection` package), with the same answers.

Maven 3 plugins and the shared components keep their APIs; only Maven 4 plugins can call the service.

## Use

```java
@Inject
private Session session;

ArtifactMatcherFactory factory = session.getService(ArtifactMatcherFactory.class);
ArtifactMatcher matcher = factory.createArtifactMatcher(ArtifactMatcherRequest.builder()
        .includes(List.of("org.example:*", "*:*:jar:*:[1.0,2.0)"))
        .excludes(List.of("*:*:test-jar"))
        .includeScope("runtime")
        .build());

boolean selected = matcher.matches(dependency);            // org.apache.maven.api.Artifact or Dependency
ArtifactMatcherResult<Dependency> result = factory.select(matcher, dependencies);
result.getIncluded();                                       // selected, in input order
result.getUntriggeredPatterns();                            // patterns that decided for no artifact
```

`ArtifactMatcher` is a `Predicate<Artifact>`, so matchers compose with `and`, `or` and `negate`. A request without
criteria gives `factory.includesAll()`.

## Criteria

An artifact is selected when it passes every criterion that is set, checked in this order (the next one only when
the previous ones accept the artifact):

| Criterion | Request | Maven 3 equivalent |
|---|---|---|
| scope | `includeScope`, `excludeScope` | `collection.ScopeFilter` |
| coordinate values | `includeValues`, `excludeValues` per `Coordinate` (`GROUP_ID`, `ARTIFACT_ID`, `TYPE`, `CLASSIFIER`), `groupIdPrefixMatch` | `collection.GroupIdFilter`, `ArtifactIdFilter`, `TypeFilter`, `ClassifierFilter` |
| include patterns | `includes`, `transitive` | `PatternIncludesArtifactFilter` |
| exclude patterns | `excludes`, `transitive` | `PatternExcludesArtifactFilter` |

The coordinates compared are the group id, the artifact id, the type, the classifier, the base version and the
scope. For a `Dependency`, the type is `type().id()` and the scope `scope().id()`; for another `Artifact`, the type is
its extension and there is no scope. An empty classifier means none.

### Patterns

Tokens are separated by `:`; an empty token stands for `*`; `*` and `?` are wildcards inside a token; a leading `!`
negates a pattern, and the first pattern of a list that matches decides.

| Pattern | Compares |
|---|---|
| `G:A:T:C:V` | group id, artifact id, type, classifier, version; `V` may be a range such as `[1.0,2.0)` |
| `G:A:T:X` | group id, artifact id, type; `X` against the version or the classifier |
| `G:A:T`, `G:A:*`, `G:A`, `G` | the given leading coordinates |
| `*:X:*` | `X` against the artifact id or the type |
| `*:*:X` | `X` against the type or the classifier |
| `*:X:Y` | `X` against the group id or the artifact id, `Y` against the type or the classifier |
| `X:*:*` | `X` against the group id or the artifact id |
| `X:*:Y` | `X` against the group id, `Y` against the type or the classifier |
| `*:X` | `X` against the group id, the artifact id, the type or the version |
| `X:*` | `X` against the group id |
| `*`, `*:*`, `*:*:*`, empty | every artifact |

A pattern with more than five tokens, or with no token (`:`), and an invalid version range are rejected with an
`IllegalArgumentException` when the matcher is created. Version ranges use the Maven 4 `VersionParser`.

With `transitive(true)`, an artifact that no pattern matches is decided by the first element of its dependency
trail that a pattern matches (`matches(artifact, trail)`, the trail running from the root project to the artifact,
looked at only when it has more than one element).

### Scopes

`includeScope`: `compile` (compile, provided, system), `runtime` (compile, runtime), `test` (the five Maven 3 scopes);
with these three, artifacts of other scopes and without scope are included too. `provided` and `system` include that
scope only. `excludeScope` excludes what the same value includes; excluding `test` is an error. An include scope
wins over an exclude scope; other values are errors.

### Coordinate values

Values match exactly. With `groupIdPrefixMatch(true)`, a group id value matches every group id that starts with it,
which is what the Maven 3 `GroupIdFilter` does by default.

## Differences with the Maven 3 filters

* An empty include list means no include criterion. `new PatternIncludesArtifactFilter(emptyList)` includes nothing;
  its callers only build it for a non-empty list.
* Invalid scopes, patterns and version ranges fail when the matcher is created, not when it is used.
* A pattern given twice in a list counts once in `getUntriggeredPatterns()`, as on the library's master; the 3.4.0
  release reports the second copy as never triggered.
* The `GroupIdFilter` of the dependency plugin matches nested group ids by segment (`org.example` matches
  `org.example.foo`, not `org.examples`); neither mode of the service does that.

## Parity

`impl/maven-core` `ArtifactMatcherFactoryParityTest` runs each case through the Maven 3 filter of
maven-common-artifact-filters 3.4.0 (test scope) and through the service, and writes
`target/artifact-matcher-parity.tsv`:

| Suite | Cases | Different |
|---|---:|---:|
| upstream pattern test vectors (`AbstractPatternArtifactFilterTest`) | 65 | 0 |
| generated pattern corpus (1 to 5 tokens, wildcards, ranges, negations) | 41,568 | 0 |
| wildcards: every pattern over `a b * ?` up to 5 characters against every group id over `a b` up to 5 | 173,355 | 0 |
| negation and order (pattern pairs) | 1,088 | 0 |
| dependency trails | 170 | 0 |
| version ranges | 370 | 0 |
| missed criteria, and the upstream duplicate-pattern vector | 6 + 2 | 0 |
| 3.4.0 duplicate-pattern report, asserted as the known difference | 1 | 0 |
| `ScopeFilter` | 81 | 0 |
| `GroupIdFilter`, `ArtifactIdFilter`, `TypeFilter`, `ClassifierFilter` | 1,609 | 0 |
| exact group ids (master test expectations; no released jar) | 3 | 0 |

## Migration

The recipe `org.apache.maven.recipes.UseArtifactMatcherFactory` in `tools/code-recipes` marks the filter
constructions of Maven 4 plugins with the request that selects the same artifacts.

## Provenance

The pattern rules, the wildcard match, the scope rules and the value lists are adapted from Apache Maven Common
Artifact Filters (Apache-2.0, commit `a11a231759800e38a5c46822f1cc04b867bc5670`); see
`impl/maven-impl/FOSS_REUSE_DECISION.tsv`.
