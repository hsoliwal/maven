<!--
SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->
# Maven invokes the LLVM compiler

Install the plugin from `tools/m3-polyglot-maven-plugin/pom.xml`, then run these
commands from this example directory with a qualified LLVM installation:

```sh
mkdir -p target
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:plan \
    -Dm3.llvm.home=/work/llvm-install
mvn io.synexia:m3-polyglot-maven-plugin:0.1.0-SNAPSHOT:run \
    -Dm3.llvm.home=/work/llvm-install
./target/hello
```

The native executable prints `Maven built this with LLVM`. The manifest keeps
compilation and linking as separate dependency-ordered tasks, with declared
object, dependency-file and executable outputs. Change `native/message.h` and
run again to rebuild with the new message. Provider tasks always execute,
including when M3 module incremental reuse is selected.

The `m3.llvm.home` property is shared with the existing Clang AST adapter.
Alternatively use `M3_LLVM_HOME` or add the legacy
`--m3-llvm-bin=<directory>` task selector in the manifest. If no home/bin is
selected, the original PATH lookup is preserved. To seal a specific installed
compiler, add `--m3-llvm-sha256=<64-hex-digest>` to each compiler task; the
digest must be of the executable bytes, not the LLVM source commit hash.

This example uses host C17 compilation and a POSIX executable name. Adapt the
output name and compiler target/SDK options for other platforms. The compiler
provider preserves the options supplied in the TSV. See
[LLVM_TOOLCHAIN.md](../../docs/LLVM_TOOLCHAIN.md) for C++, Objective-C, Fortran,
LLVM IR, executable digest pins and the existing Clang atomizer integration.
