/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.nativebuild.engine;

/** Classifier naming: os-maven-plugin vocabulary, and the Windows runtime suffix. */
final class PlatformContract {
    private PlatformContract() {}

    static void run(Checks c) {
        classifiers(c);
        runtimes(c, Platform.of("Windows 11", "amd64"));
    }

    private static void classifiers(Checks c) {
        Platform windows = Platform.of("Windows 11", "amd64");
        c.equal("windows amd64 normalizes", new Platform("windows", "x86_64"), windows);
        c.equal("windows classifier with mingw", "windows-x86_64-mingw", windows.classifier(Platform.MINGW));
        c.equal("windows classifier without runtime", "windows-x86_64", windows.classifier(""));
        c.equal("linux aarch64 ignores a runtime", "linux-aarch_64", Platform.of("Linux", "aarch64").classifier("msvc"));
        c.equal("mac x86_64", "osx-x86_64", Platform.of("Mac OS X", "x86_64").classifier(""));
        c.equal("mac arm64", "osx-aarch_64", Platform.of("Mac OS X", "arm64").classifier(""));
        c.equal("i686 is x86_32", "x86_32", Platform.of("Linux", "i686").arch());
        c.equal("ppc64le", "ppcle_64", Platform.of("Linux", "ppc64le").arch());
    }

    private static void runtimes(Checks c, Platform windows) {
        c.equal("Visual Studio generator is msvc", "msvc", windows.runtime("Visual Studio 17 2022", "", false));
        c.equal("clang-cl is msvc", "msvc", windows.runtime("Ninja", "C:/LLVM/bin/clang-cl.exe", false));
        c.equal("cl is msvc", "msvc", windows.runtime("", "cl", false));
        c.equal("gcc is mingw", "mingw", windows.runtime("Ninja", "C:/mingw64/bin/gcc.exe", true));
        c.equal("unconfigured with Visual Studio installed is msvc", "msvc", windows.runtime("", "", true));
        c.equal("unconfigured without Visual Studio is mingw", "mingw", windows.runtime("", "", false));
        c.equal("no runtime on linux", "", Platform.of("Linux", "amd64").runtime("", "cl", true));
        c.equal("stem drops .exe only", "x86_64-w64-mingw32-gcc", WindowsPaths.stem("C:/m/x86_64-w64-mingw32-gcc.exe"));
    }
}
