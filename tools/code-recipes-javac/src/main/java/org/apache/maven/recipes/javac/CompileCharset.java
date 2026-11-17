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
package org.apache.maven.recipes.javac;

import javax.tools.JavaFileManager;

import java.nio.charset.Charset;

import com.sun.tools.javac.file.BaseFileManager;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Options;

/**
 * The charset javac decodes the compile's sources with. javac's file manager decodes them
 * ({@code BaseFileManager.decode}): with the charset given to {@code getStandardFileManager} when there is one, as
 * maven-compiler-plugin gives it in process, else with its {@code -encoding} option. In a javax.tools compile javac
 * hands {@code -encoding} to the file manager and does not record it in the compiler's {@link Options}, so the file
 * manager is asked first; a file manager javac does not own (wrapped by its client code wrapper) falls back to the
 * options and then to the platform default.
 */
final class CompileCharset {
    private static final String ENCODING = "-encoding";

    private CompileCharset() {}

    static Charset of(Context context) {
        if (context.get(JavaFileManager.class) instanceof BaseFileManager files) {
            return files.getDecoder(files.getEncodingName(), false).charset();
        }
        String encoding = Options.instance(context).get(ENCODING);
        return encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
    }
}
