// SPDX-License-Identifier: Apache-2.0
package org.apache.maven.plugin;
public abstract class AbstractMojo {
    public abstract void execute() throws MojoExecutionException;
    public org.apache.maven.plugin.logging.Log getLog() { return new org.apache.maven.plugin.logging.Log(); }
}
