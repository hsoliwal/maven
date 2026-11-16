<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
# Visual Studio (not VS Code): explicit Maven integration

Visual Studio owns MSBuild and CMake builds. These examples invoke Maven's **original** goals
explicitly. Neither monitors workspace files nor claims Maven/JDT build reuse.

## Visual Studio Open Folder

For a non-CMake, Maven-based folder, copy `tasks.vs.json` to the Visual Studio
Open Folder task configuration (`.vs/tasks.vs.json`) or use **Configure Tasks**
from the `pom.xml` context menu. `taskLabel`, `appliesTo`, `type: launch`, `command`
and `args` are from the Microsoft `tasks.vs.json` schema. Open the Maven POM in
Solution Explorer and choose **M3 Maven: test** or **M3 Maven: verify**.

On Windows, `mvn.cmd` must be installed and accessible on `PATH`. The tasks do not
become the default build for a CMake project and do not supersede the Visual Studio
CMake/IntelliSense integration. No Maven command runs on ordinary file saves.

## Visual Studio MSBuild projects

Opt in by importing `M3Maven.targets` in a specific `.csproj` or `.vcxproj`:

```xml
<Import Project="path\to\M3Maven.targets" />
```

Run the explicitly named target (not an automatic `AfterTargets=Build` hook):

```powershell
msbuild MyProject.csproj /t:M3MavenVerify /p:M3MavenEnable=true
```

The default goal is `verify`; set `M3MavenGoals` to a trusted static list of
Maven goals, and `M3MavenPom` to the intended POM. Do not populate these command
parameters from source paths or untrusted IDE change notifications. MSBuild exit
status reflects Maven's original process exit code. `M3MavenEnable` defaults to
false, and simply importing the targets file does nothing.

## Incremental build boundary

MSBuild/CMake/JDT each own incremental compilation and generated outputs. Maven
owns POMs, plugins, reactor and artifact resolution. `M3ExternalBuildService` in
Maven PR #78 evaluates separately supplied IDE change events, but never skips
Maven goals. This is a command integration template, **not** a full VSIX or
producer-side workspace change protocol.

Microsoft references:
- https://learn.microsoft.com/en-us/cpp/build/tasks-vs-json-schema-reference-cpp
- https://learn.microsoft.com/en-us/visualstudio/msbuild/how-to-extend-the-visual-studio-build-process
