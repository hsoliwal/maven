package org.apache.maven.plugins.annotations;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface Parameter {String defaultValue() default ""; boolean readonly() default false; boolean required() default false; String property() default "";}
