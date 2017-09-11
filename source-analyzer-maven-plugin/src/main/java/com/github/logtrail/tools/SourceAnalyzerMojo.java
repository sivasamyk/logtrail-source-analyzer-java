package com.github.logtrail.tools;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.github.javaparser.ParseException;
import com.github.logtrail.tools.sourceanalyzer.JavaSrcAnalyzer;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 *  does source analysis for logtrail
 */
@Mojo(name = "analyze", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class SourceAnalyzerMojo
    extends AbstractMojo
{
    private String outputFile = "patterns.json";

    private String context = "CLASS";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private ProjectBuilder projectBuilder;

    public void execute()
        throws MojoExecutionException
    {
        try {
            JavaSrcAnalyzer srcAnalyzer = new JavaSrcAnalyzer(project.getBasedir().getAbsolutePath(),
                    outputFile, context);
            srcAnalyzer.analyze();
        } catch (Exception e) {
            getLog().error("Exception while analyzing source",e);
            throw new MojoExecutionException("Exception while analyzing source : " + e.getMessage());
        }
    }
}
