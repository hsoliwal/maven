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
package org.apache.maven.plugins.codepatch;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.Mojo;

/** Plans, then writes the after-images to {@code stage/<plan-id>/} in the output directory. Changes no source file. */
@Mojo(name = "preview", aggregator = true, requiresProject = false, threadSafe = true)
public final class PreviewMojo extends AbstractPatchMojo {
    @Override protected void run(Path base, Path out) throws IOException, PatchException {
        Path staged = CodePatch.preview(base, out, patches());
        getLog().info("staged after-images in " + staged);
    }
}
