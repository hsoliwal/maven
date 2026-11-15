package org.apache.maven.plugin;
public class MojoExecutionException extends Exception {
    private static final long serialVersionUID = 1L;
    public MojoExecutionException(String message) {super(message);}
    public MojoExecutionException(String message, Throwable reason) {super(message,reason);}
}
