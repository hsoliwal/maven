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
package org.apache.maven.recipes;

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

class CommandlineToProcessLauncherTest implements RewriteTest {

    /** Signatures of the classes the recipe looks at; the tests need their types, not their code. */
    private static final String[] STUBS = {
        """
        package org.apache.maven.api;
        public interface Session {}
        """,
        """
        package org.apache.maven.api.services;
        public interface ProcessLauncher {}
        """,
        """
        package org.apache.maven.shared.utils.cli;
        public class CommandLineException extends Exception {}
        """,
        """
        package org.apache.maven.shared.utils.cli;
        public interface StreamConsumer { void consumeLine(String line); }
        """,
        """
        package org.apache.maven.shared.utils.cli;
        public class Commandline {
            public void setExecutable(String executable) {}
            public void addArguments(String... line) {}
            public void addEnvironment(String name, String value) {}
            public void setWorkingDirectory(java.io.File directory) {}
        }
        """,
        """
        package org.apache.maven.shared.utils.cli;
        public abstract class CommandLineUtils {
            public static int executeCommandLine(Commandline cl, StreamConsumer out, StreamConsumer err, int timeout)
                    throws CommandLineException { return 0; }
        }
        """,
        """
        package org.codehaus.plexus.util.cli;
        public abstract class CommandLineUtils {
            public static String[] translateCommandline(String line) throws Exception { return new String[0]; }
        }
        """
    };

    private static final String SHARED_UTILS_BEFORE =
            """
            import org.apache.maven.api.Session;
            import org.apache.maven.shared.utils.cli.CommandLineUtils;
            import org.apache.maven.shared.utils.cli.Commandline;

            class GpgSigner {
                Session session;

                int sign(String file) throws Exception {
                    Commandline cl = new Commandline();
                    cl.setExecutable("gpg");
                    cl.addArguments("--detach-sign", file);
                    cl.addEnvironment("GNUPGHOME", "target/gpg");
                    return CommandLineUtils.executeCommandLine(cl, System.out::println, System.err::println, 60);
                }
            }
            """;

    private static final String SHARED_UTILS_AFTER =
            """
            import org.apache.maven.api.Session;
            import org.apache.maven.shared.utils.cli.CommandLineUtils;
            import org.apache.maven.shared.utils.cli.Commandline;

            class GpgSigner {
                Session session;

                int sign(String file) throws Exception {
                    Commandline cl = /*~~(ProcessLaunchRequest.builder().shell(Shell.DEFAULT))~~>*/new Commandline();
                    /*~~(ProcessLaunchRequestBuilder.executable(String))~~>*/cl.setExecutable("gpg");
                    /*~~(ProcessLaunchRequestBuilder.arguments(String...))~~>*/cl.addArguments("--detach-sign", file);
                    /*~~(ProcessLaunchRequestBuilder.environment(String, String))~~>*/cl.addEnvironment("GNUPGHOME", "target/gpg");
                    return /*~~(ProcessLauncher.execute(ProcessLaunchRequest))~~>*/CommandLineUtils.executeCommandLine(cl, System.out::println, System.err::println, 60);
                }
            }
            """;

    private static final String PLEXUS_BEFORE =
            """
            import org.apache.maven.api.services.ProcessLauncher;
            import org.codehaus.plexus.util.cli.CommandLineUtils;

            class ArgLine {
                ProcessLauncher launcher;

                String[] split(String argLine) throws Exception {
                    return CommandLineUtils.translateCommandline(argLine);
                }
            }
            """;

    private static final String PLEXUS_AFTER =
            """
            import org.apache.maven.api.services.ProcessLauncher;
            import org.codehaus.plexus.util.cli.CommandLineUtils;

            class ArgLine {
                ProcessLauncher launcher;

                String[] split(String argLine) throws Exception {
                    return /*~~(ProcessLauncher.tokenize(String))~~>*/CommandLineUtils.translateCommandline(argLine);
                }
            }
            """;

    private static final String MAVEN_3_PLUGIN =
            """
            import org.apache.maven.shared.utils.cli.CommandLineUtils;
            import org.apache.maven.shared.utils.cli.Commandline;

            class LegacyMojo {
                int run() throws Exception {
                    Commandline cl = new Commandline();
                    cl.setExecutable("git");
                    return CommandLineUtils.executeCommandLine(cl, System.out::println, System.err::println, 0);
                }
            }
            """;

    private static final String PROCESS_LAUNCHER_PLUGIN =
            """
            import org.apache.maven.api.Session;
            import org.apache.maven.api.services.ProcessLauncher;

            class ModernMojo {
                Session session;
                ProcessLauncher launcher;

                String describe() {
                    return launcher.toString();
                }
            }
            """;

    private static final String OWN_COMMANDLINE_CLASS =
            """
            package org.example.cli;

            public class Commandline {
                public void setExecutable(String executable) {}
            }
            """;

    private static final String OWN_COMMANDLINE_USE =
            """
            import org.apache.maven.api.Session;
            import org.example.cli.Commandline;

            class OwnCommandline {
                Session session;

                void run() {
                    Commandline cl = new Commandline();
                    cl.setExecutable("git");
                }
            }
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new CommandlineToProcessLauncher()).parser(JavaParser.fromJavaVersion().dependsOn(STUBS));
    }

    @Test
    void marksSharedUtilsCallsInMaven4Plugins() {
        rewriteRun(java(SHARED_UTILS_BEFORE, SHARED_UTILS_AFTER));
    }

    @Test
    void marksPlexusUtilsCallsInMaven4Plugins() {
        rewriteRun(java(PLEXUS_BEFORE, PLEXUS_AFTER));
    }

    @Test
    void leavesMaven3PluginsAlone() {
        rewriteRun(java(MAVEN_3_PLUGIN));
    }

    @Test
    void leavesPluginsAlreadyOnProcessLauncherAlone() {
        rewriteRun(java(PROCESS_LAUNCHER_PLUGIN));
    }

    @Test
    void leavesOtherCommandlineClassesAlone() {
        rewriteRun(java(OWN_COMMANDLINE_CLASS), java(OWN_COMMANDLINE_USE));
    }
}
