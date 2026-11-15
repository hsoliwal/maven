package org.apache.maven.plugins.annotations;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Mojo {String name(); boolean threadSafe() default false; boolean requiresProject() default true;}
