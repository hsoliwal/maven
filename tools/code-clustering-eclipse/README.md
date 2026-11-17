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
# Code Clustering: Eclipse Connector

An Eclipse plug-in (OSGi bundle `org.apache.maven.plugins.clustering.eclipse`) that connects the
[code-clustering language server](../code-clustering-lsp/README.md#language-server) to Eclipse through
[LSP4E](https://github.com/eclipse-lsp4e/lsp4e). Duplicate atoms then show as Information markers in the Problems
view and in the editors of JDT, CDT, PyDev, WTP / Wild Web Developer and Rust (Corrosion, RustDT).

## What it does

- `plugin.xml` contributes one server to LSP4E's `org.eclipse.lsp4e.languageServer` extension point and maps it to
  the [content types](#content-types) below. The server is a singleton: LSP4E sends every open project as a
  workspace folder, and one server process scans them all, so duplicates across projects are found too.
- When a file of a mapped type is opened, LSP4E starts `ClusteringConnectionProvider` (a
  `ProcessStreamConnectionProvider`). It runs, in the workspace root,

  ```
  <java.home>/bin/java -cp <the bundle's lib/*.jar> org.apache.maven.plugins.clustering.lsp.ClusteringServerMain
  ```

  and LSP4E talks to it over standard input and output. `java.home` is the JVM that runs Eclipse. If the bundle is
  installed as a jar, Equinox first extracts the `lib/` jars into Eclipse's configuration area (`FileLocator.toFileURL`).
  If `lib/` has no jars, the start fails with an error that points to this README.
- The server scans after `initialized`, after each save and after watched files change. It publishes one diagnostic
  per duplicate atom: source `code-clustering`, code `EXACT` or `LOGIC`, with a message that names the other copies.
  LSP4E turns them into markers.
- Which files get atoms: Java through the javac front end (the server JVM needs the `jdk.compiler` module), C, C++,
  C#, JavaScript, TypeScript, Go, Rust, Kotlin and other brace languages, and Python, through the lexical front end.
  XML, HTML, CSS and JSON files get atoms only through the LSP front end, when `clustering.lsp.servers` maps their
  extensions to a language server. Opening one of those files still starts the server, so the other duplicates in
  the workspace show up.
- LSP4E stops the server 5 seconds after the last editor of a mapped type closes (the default of
  `lastDocumentDisconnectedTimeout`). It starts the server again, with a new scan, when the next one opens.

## Build

### 1. Put the server jars into `lib/`

The jars are not committed. Build the two modules, then copy their jars and the dependency jars:

```
cd tools/code-clustering && mvn install
cd ../code-clustering-lsp && mvn package
mvn dependency:copy-dependencies -DincludeScope=compile -DoutputDirectory=../code-clustering-eclipse/lib
cp target/maven-code-clustering-lsp-0.1.0-SNAPSHOT.jar ../code-clustering-eclipse/lib/
```

`lib/` then holds:

| Jar | From |
|---|---|
| `maven-code-clustering-plugin-0.1.0-SNAPSHOT.jar` | `tools/code-clustering` (through the local repository) |
| `maven-code-clustering-lsp-0.1.0-SNAPSHOT.jar` | `tools/code-clustering-lsp/target` |
| `org.eclipse.lsp4j-0.23.1.jar`, `org.eclipse.lsp4j.jsonrpc-0.23.1.jar` | Maven Central, `org.eclipse.lsp4j` |
| `gson-<version>.jar` (2.9.1 or later) | Maven Central, `com.google.code.gson` |
| `error_prone_annotations-<version>.jar` | comes with Gson; not needed at run time, harmless |

These jars are only on the class path of the server process. They are not on the bundle class path
(`Bundle-ClassPath: .`), so they never meet the LSP4J bundles that Eclipse runs LSP4E with.

### 2. Compile and package

The bundle needs these Eclipse bundles to compile: `org.eclipse.lsp4e`, `org.eclipse.lsp4j`,
`org.eclipse.lsp4j.jsonrpc`, `org.eclipse.core.runtime`, `org.eclipse.equinox.common`, `org.eclipse.osgi`,
`org.eclipse.core.resources` and `org.eclipse.core.jobs`. The `plugins` folder of an Eclipse installation has them
(or `~/.p2/pool/plugins` for an installation with a shared bundle pool). LSP4E 0.19 is compiled for Java 21, so use
the `javac` of a JDK 21 or later; `--release 17` keeps this bundle's own classes at Java 17:

```
cd tools/code-clustering-eclipse
javac --release 17 -d bin -cp "<eclipse>/plugins/*" src/org/apache/maven/plugins/clustering/eclipse/*.java
jar --create --file org.apache.maven.plugins.clustering.eclipse_0.1.0.qualifier.jar \
    --manifest META-INF/MANIFEST.MF plugin.xml lib -C bin .
```

On Windows, quote the class path the same way: `-cp "C:\eclipse\plugins\*"`.

With PDE, the folder is a plug-in project layout (`build.properties`: sources in `src/`, output in `bin/`, `lib/`
included). Open it with **File > Open Projects from File System...** and export it with **File > Export > Plug-in
Development > Deployable plug-ins and fragments**, which can also write a p2 repository.

## Install

### dropins

Copy the bundle jar into `<eclipse>/dropins/` and restart Eclipse (once with `-clean` if it does not show up).
**Help > About Eclipse IDE > Installation Details > Plug-ins** then lists it, and **Window > Preferences > Language
Servers** lists "Code Clustering" with each installed content type it maps. A mapping can be switched off there.

### Local p2 repository

Put the jar into `<site>/plugins/` and publish the folder as a p2 repository with Eclipse's publisher application
(`eclipsec` on Windows):

```
eclipse -nosplash -consoleLog -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository file:/<repo> -artifactRepository file:/<repo> -source <site> -publishArtifacts
```

The **Install New Software** dialog lists features only, so install the bundle with the p2 director, while the
target Eclipse is closed:

```
eclipse -nosplash -consoleLog -application org.eclipse.equinox.p2.director \
    -repository file:/<repo> -installIU org.apache.maven.plugins.clustering.eclipse
```

## Configuration

System properties of the Eclipse JVM, set in `eclipse.ini` after `-vmargs`:

| Property | Effect |
|---|---|
| `clustering.eclipse.java` | The `java` executable for the server. Default: `bin/java` (`java.exe` on Windows) under the Eclipse JVM's `java.home`. Point it at a JDK when Eclipse runs on a JRE without `jdk.compiler`, because the javac front end needs it. |
| `clustering.eclipse.vmargs` | Extra JVM options for the server, separated by whitespace, for example `-Xmx2g`. |
| `clustering.minTokens` | Passed on to the server as `-D`: atoms with fewer logic tokens are ignored (server default 12). |
| `clustering.lsp.servers` | Passed on as `-D`: extensions to language server commands for the LSP front end, for example `c,h,cpp,hpp=clangd;rs=rust-analyzer`. |
| `clustering.lsp.timeoutSeconds` | Passed on as `-D`: the LSP front end's request timeout (default 60). |

The server process inherits Eclipse's environment, so `CLUSTERING_LSP_SERVERS` works too. The server's standard
error goes to Eclipse's standard error (visible with `-consoleLog` or `eclipsec`).

## Content types

Every id below was taken from the upstream file that declares it, at the commit in the link. LSP4E skips a mapping
whose content type is not installed, so the plug-in works with any subset of these editors. LSP4E also follows base
types, so subtypes are covered too, for example Wild Web Developer's `org.eclipse.wildwebdeveloper.json` (base type
`org.eclipse.tm4e.language_pack.json`).

| Editor | Content type id | `languageId` | Declared in | Mapped by the editor's own server |
|---|---|---|---|---|
| JDT | `org.eclipse.jdt.core.javaSource` | java | [eclipse.jdt.core: org.eclipse.jdt.core/plugin.xml](https://github.com/eclipse-jdt/eclipse.jdt.core/blob/dd2c87485ce2f01b4fc88167f1db701dd6540b35/org.eclipse.jdt.core/plugin.xml#L273) (`javaSource` in bundle `org.eclipse.jdt.core`) | |
| CDT | `org.eclipse.cdt.core.cSource` | c | [cdt: core/org.eclipse.cdt.core/plugin.xml](https://github.com/eclipse-cdt/cdt/blob/52a6b1f174c3169b9c1f1db770eb76249f723392/core/org.eclipse.cdt.core/plugin.xml#L607) (`cSource`) | |
| CDT | `org.eclipse.cdt.core.cHeader` | c | [cdt: core/org.eclipse.cdt.core/plugin.xml](https://github.com/eclipse-cdt/cdt/blob/52a6b1f174c3169b9c1f1db770eb76249f723392/core/org.eclipse.cdt.core/plugin.xml#L612) (`cHeader`) | |
| CDT | `org.eclipse.cdt.core.cxxSource` | cpp | [cdt: core/org.eclipse.cdt.core/plugin.xml](https://github.com/eclipse-cdt/cdt/blob/52a6b1f174c3169b9c1f1db770eb76249f723392/core/org.eclipse.cdt.core/plugin.xml#L617) (`cxxSource`) | |
| CDT | `org.eclipse.cdt.core.cxxHeader` | cpp | [cdt: core/org.eclipse.cdt.core/plugin.xml](https://github.com/eclipse-cdt/cdt/blob/52a6b1f174c3169b9c1f1db770eb76249f723392/core/org.eclipse.cdt.core/plugin.xml#L622) (`cxxHeader`) | |
| PyDev | `org.python.pydev.pythonfile` | python | [Pydev: plugins/org.python.pydev/plugin.xml](https://github.com/fabioz/Pydev/blob/3a91ff856423090cfc5ffa9420becf4c9107398f/plugins/org.python.pydev/plugin.xml#L58) | |
| Wild Web Developer (XML) | `org.eclipse.core.runtime.xml` | xml | [eclipse.platform: runtime/bundles/org.eclipse.core.contenttype/plugin.xml](https://github.com/eclipse-platform/eclipse.platform/blob/30dafa8d50918869baccfa02576ed41997dbfb3b/runtime/bundles/org.eclipse.core.contenttype/plugin.xml#L14) | [LemMinX](https://github.com/eclipse-wildwebdeveloper/wildwebdeveloper/blob/9350f71cb63b39c34ed49b84d05fb733122fe09b/org.eclipse.wildwebdeveloper.xml/plugin.xml#L84) |
| WTP (XML) | `org.eclipse.wst.xml.core.xmlsource` | xml | [sourceediting: xml/bundles/org.eclipse.wst.xml.core/plugin.xml](https://github.com/eclipse-sourceediting/sourceediting/blob/22b6a36e7305572f9813de99fec4a09964b35dc9/xml/bundles/org.eclipse.wst.xml.core/plugin.xml#L129) | |
| WTP (HTML) | `org.eclipse.wst.html.core.htmlsource` | html | [sourceediting: web/bundles/org.eclipse.wst.html.core/plugin.xml](https://github.com/eclipse-sourceediting/sourceediting/blob/22b6a36e7305572f9813de99fec4a09964b35dc9/web/bundles/org.eclipse.wst.html.core/plugin.xml#L131) | |
| Wild Web Developer (HTML) | `org.eclipse.tm4e.language_pack.html` | html | [tm4e: org.eclipse.tm4e.language_pack/plugin.xml](https://github.com/eclipse-tm4e/tm4e/blob/c46afcd027fdc390278a9915abe10ff631dd9a47/org.eclipse.tm4e.language_pack/plugin.xml#L515) | [HTML server](https://github.com/eclipse-wildwebdeveloper/wildwebdeveloper/blob/9350f71cb63b39c34ed49b84d05fb733122fe09b/org.eclipse.wildwebdeveloper/plugin.xml#L354) |
| WTP (CSS) | `org.eclipse.wst.css.core.csssource` | css | [sourceediting: web/bundles/org.eclipse.wst.css.core/plugin.xml](https://github.com/eclipse-sourceediting/sourceediting/blob/22b6a36e7305572f9813de99fec4a09964b35dc9/web/bundles/org.eclipse.wst.css.core/plugin.xml#L84) | |
| Wild Web Developer (CSS) | `org.eclipse.tm4e.language_pack.css` | css | [tm4e: org.eclipse.tm4e.language_pack/plugin.xml](https://github.com/eclipse-tm4e/tm4e/blob/c46afcd027fdc390278a9915abe10ff631dd9a47/org.eclipse.tm4e.language_pack/plugin.xml#L247) | [CSS server](https://github.com/eclipse-wildwebdeveloper/wildwebdeveloper/blob/9350f71cb63b39c34ed49b84d05fb733122fe09b/org.eclipse.wildwebdeveloper/plugin.xml#L190) |
| WTP (JSON) | `org.eclipse.wst.json.core.jsonsource` | json | [sourceediting: json/bundles/org.eclipse.wst.json.core/plugin.xml](https://github.com/eclipse-sourceediting/sourceediting/blob/22b6a36e7305572f9813de99fec4a09964b35dc9/json/bundles/org.eclipse.wst.json.core/plugin.xml#L41) | |
| Wild Web Developer (JSON) | `org.eclipse.tm4e.language_pack.json` | json | [tm4e: org.eclipse.tm4e.language_pack/plugin.xml](https://github.com/eclipse-tm4e/tm4e/blob/c46afcd027fdc390278a9915abe10ff631dd9a47/org.eclipse.tm4e.language_pack/plugin.xml#L633) | [JSON server](https://github.com/eclipse-wildwebdeveloper/wildwebdeveloper/blob/9350f71cb63b39c34ed49b84d05fb733122fe09b/org.eclipse.wildwebdeveloper/plugin.xml#L129) |
| WTP JSDT (JavaScript) | `org.eclipse.wst.jsdt.core.jsSource` | javascript | [webtools.jsdt: bundles/org.eclipse.wst.jsdt.core/plugin.xml](https://github.com/eclipse-jsdt/webtools.jsdt/blob/bb1803043b45fa33ad8eecc4d4e398275037aa64/bundles/org.eclipse.wst.jsdt.core/plugin.xml#L214) (`jsSource`) | |
| Wild Web Developer (JavaScript) | `org.eclipse.tm4e.language_pack.javascript` | javascript | [tm4e: org.eclipse.tm4e.language_pack/plugin.xml](https://github.com/eclipse-tm4e/tm4e/blob/c46afcd027fdc390278a9915abe10ff631dd9a47/org.eclipse.tm4e.language_pack/plugin.xml#L586) | [TypeScript server](https://github.com/eclipse-wildwebdeveloper/wildwebdeveloper/blob/9350f71cb63b39c34ed49b84d05fb733122fe09b/org.eclipse.wildwebdeveloper/plugin.xml#L430) |
| Wild Web Developer (JSX) | `org.eclipse.tm4e.language_pack.javascriptreact` | javascriptreact | [tm4e: org.eclipse.tm4e.language_pack/plugin.xml](https://github.com/eclipse-tm4e/tm4e/blob/c46afcd027fdc390278a9915abe10ff631dd9a47/org.eclipse.tm4e.language_pack/plugin.xml#L606) | |
| Wild Web Developer (TypeScript) | `org.eclipse.tm4e.language_pack.typescript` | typescript | [tm4e: org.eclipse.tm4e.language_pack/plugin.xml](https://github.com/eclipse-tm4e/tm4e/blob/c46afcd027fdc390278a9915abe10ff631dd9a47/org.eclipse.tm4e.language_pack/plugin.xml#L1331) | [TypeScript server](https://github.com/eclipse-wildwebdeveloper/wildwebdeveloper/blob/9350f71cb63b39c34ed49b84d05fb733122fe09b/org.eclipse.wildwebdeveloper/plugin.xml#L435) |
| Wild Web Developer (TSX) | `org.eclipse.tm4e.language_pack.typescriptreact` | typescriptreact | [tm4e: org.eclipse.tm4e.language_pack/plugin.xml](https://github.com/eclipse-tm4e/tm4e/blob/c46afcd027fdc390278a9915abe10ff631dd9a47/org.eclipse.tm4e.language_pack/plugin.xml#L1351) | |
| Corrosion (Rust) | `org.eclipse.tm4e.language_pack.rust` | rust | [tm4e: org.eclipse.tm4e.language_pack/plugin.xml](https://github.com/eclipse-tm4e/tm4e/blob/c46afcd027fdc390278a9915abe10ff631dd9a47/org.eclipse.tm4e.language_pack/plugin.xml#L1180) | [rust-analyzer](https://github.com/eclipse-corrosion/corrosion/blob/a2c92491c034484caff93cc6e159ebbb2512cf9b/org.eclipse.corrosion/plugin.xml#L38) |
| RustDT (Rust) | `com.github.rustdt.ide.core.content.source` | rust | [RustDT: plugin_ide.core/plugin.xml](https://github.com/RustDT/RustDT/blob/62179ea8443fc0692b70fb678ea5e5d575be1dc1/plugin_ide.core/plugin.xml#L40) | |

Notes:

- A short id such as `javaSource` gets the declaring bundle's symbolic name as a prefix; an id with a dot is used as
  written (`getUniqueId` and `createContentType` in
  [ContentTypeBuilder](https://github.com/eclipse-platform/eclipse.platform/blob/30dafa8d50918869baccfa02576ed41997dbfb3b/runtime/bundles/org.eclipse.core.contenttype/src/org/eclipse/core/internal/content/ContentTypeBuilder.java#L37)).
- Corrosion declares no Rust content type of its own: it maps rust-analyzer to TM4E's `org.eclipse.tm4e.language_pack.rust`.
- Wild Web Developer uses the platform's XML type and TM4E's types for HTML, CSS, JSON, JavaScript and TypeScript.
- The RustDT repository is archived; its content type is still mapped for installations that have it.
- JSDT lives in its own repository and may be missing from current WTP installations.

## Without this plugin

LSP4E can run the same server without this bundle: create a **Program** launch configuration for `java -cp <class
path> org.apache.maven.plugins.clustering.lsp.ClusteringServerMain`, then map content types to it under **Window >
Preferences > Language Servers**. The steps are in the
[language server README](../code-clustering-lsp/README.md#eclipse-lsp4e). That route needs no build, but each user
sets up the launch configuration with an absolute class path and adds one mapping per content type.

## Compatibility

The bundle compiles against LSP4E 0.19.16 and 0.19.15 (both need Java 21, so Eclipse runs on Java 21 or later). It
uses `ProcessStreamConnectionProvider` (LSP4E 0.1.0 and later) and the `singleton` server attribute (0.10 and later).
The `plugin.xml` uses only elements and attributes of LSP4E's
[schema/languageServer.exsd](https://github.com/eclipse-lsp4e/lsp4e/blob/fcb11230419c9f8b254287e33d6a5a4a5db69444/org.eclipse.lsp4e/schema/languageServer.exsd):
`server` (`id`, `label`, `class`, `singleton`) and `contentTypeMapping` (`id`, `contentType`, `languageId`).

## Notices

The bundle requires Eclipse LSP4E (EPL-2.0) at run time and does not include it. A bundle jar built with `lib/`
redistributes Eclipse LSP4J (EPL-2.0), Gson (Apache-2.0) and Error Prone annotations (Apache-2.0) as binaries; ship
their license notices with it. The editors and language servers named above are separate products under their own
licenses.
