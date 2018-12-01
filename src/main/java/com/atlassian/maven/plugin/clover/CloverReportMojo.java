package com.atlassian.maven.plugin.clover;

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

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.apache.tools.ant.PropertyHelper;
import org.codehaus.plexus.resource.ResourceManager;

import com.atlassian.maven.plugin.clover.internal.AbstractCloverMojo;
import com.atlassian.maven.plugin.clover.internal.AntPropertyHelper;
import com.atlassian.maven.plugin.clover.internal.CloverConfiguration;
import com.atlassian.maven.plugin.clover.internal.ConfigUtil;
import com.atlassian.clover.cfg.Interval;

import static com.google.common.base.Strings.nullToEmpty;


/**
 * Generate a Clover report from existing Clover databases. The generated report
 * is an external report generated by Clover itself. If the project generating the report is a top level project and
 * if the <code>aggregate</code> configuration element is set to true then an aggregated report will also be created.
 *
 * <p>Note: This report mojo should be an @aggregator and the <code>clover:aggregate</code> mojo shouldn't exist. This
 * is a limitation of the site plugin which doesn't support @aggregator reports...</p>
 *
 * @goal clover
 */
public class CloverReportMojo extends AbstractMavenReport implements CloverConfiguration {
    // TODO: Need some way to share config elements and code between report mojos and main build mojos.
    // See http://jira.codehaus.org/browse/MNG-1886

    /**
     * Use a custom report descriptor for generating your Clover Reports.
     * The format for the configuration file is identical to an Ant build file which uses the &lt;clover-report/&gt;
     * task. For a complete reference, please consult the:
     *  <a href="http://openclover.org/doc/manual/4.2.0/maven--creating-custom-reports.html">Creating custom reports</a> and
     *  <a href="http://openclover.org/doc/manual/4.2.0/ant--clover-report.html">clover-report documentation</a>
     *
     * @parameter expression="${maven.clover.reportDescriptor}"
     */
    private File reportDescriptor;

    /**
     * If set to true, the clover-report configuration file will be resolved as a versioned artifact by looking for it
     * in your configured maven repositories - both remote and local.
     *
     * @parameter expression="${maven.clover.resolveReportDescriptor}" default-value="false"
     */
    private boolean resolveReportDescriptor;

    /**
     * The component that is used to resolve additional artifacts required.
     *
     * @component
     */
    protected ArtifactResolver artifactResolver;

    /**
     * Remote repositories used for the project.
     *
     * @parameter expression="${project.remoteArtifactRepositories}"
     */
    protected List<ArtifactRepository> repositories;

    /**
     * The component used for creating artifact instances.
     *
     * @component
     */
    protected ArtifactFactory artifactFactory;


    /**
     * The local repository.
     *
     * @parameter expression="${localRepository}"
     */
    protected ArtifactRepository localRepository;


    /**
     * The location of the <a href="http://openclover.org/doc/manual/4.2.0/ant--managing-the-coverage-database.html">Clover database</a>.
     *
     * @parameter expression="${maven.clover.cloverDatabase}"
     */
    private String cloverDatabase;

    /**
     * If true, then a single database will be saved for the entire project, in the target directory of the execution
     * root.
     * If a custom location for the cloverDatabase is specified, this flag is ignored.
     * @parameter expression="${maven.clover.singleCloverDatabase}" default-value="false"
     */
    protected boolean singleCloverDatabase;
    

    /**
     * The location of the merged clover database to create when running a report in a multimodule build.
     *
     * @parameter expression="${maven.clover.cloverMergeDatabase}" default-value="${project.build.directory}/clover/cloverMerge.db"
     * @required
     */
    private String cloverMergeDatabase;

    /**
     * The directory where the Clover report will be generated.
     *
     * @parameter expression="${maven.clover.outputDirectory}" default-value="${project.reporting.outputDirectory}/clover"
     * @required
     */
    private File outputDirectory;

    /**
     * <p>The location where historical Clover data will be saved.</p>
     * <p>Note: It's recommended to modify the location of this directory so that it points to a more permanent
     * location as the <code>${project.build.directory}</code> directory is erased when the project is cleaned.</p>
     *
     * @parameter expression="${maven.clover.historyDir}" default-value="${project.build.directory}/clover/history"
     * @required
     */
    private String historyDir;

    /**
     * When the Clover Flush Policy is set to "interval" or threaded this value is the minimum
     * period between flush operations (in milliseconds).
     *
     * @parameter expression="${maven.clover.flushInterval}" default-value="500"
     */
    private int flushInterval;

    /**
     * If true we'll wait 2*flushInterval to ensure coverage data is flushed to the Clover database before running
     * any query on it.
     * <p/>
     * <p>Note: The only use case where you would want to turn this off is if you're running your tests in a separate
     * JVM. In that case the coverage data will be flushed by default upon the JVM shutdown and there would be no need
     * to wait for the data to be flushed. As we can't control whether users want to fork their tests or not, we're
     * offering this parameter to them.</p>
     *
     * @parameter expression="${maven.clover.waitForFlush}" default-value="true"
     */
    private boolean waitForFlush;

    /**
     * Decide whether to generate an HTML report or not.
     *
     * @parameter default-value="true" expression="${maven.clover.generateHtml}"
     */
    private boolean generateHtml;

    /**
     * Decide whether to generate a PDF report or not.
     *
     * @parameter default-value="false" expression="${maven.clover.generatePdf}"
     */
    private boolean generatePdf;

    /**
     * Decide whether to generate a XML report or not.
     *
     * @parameter default-value="true" expression="${maven.clover.generateXml}"
     */
    private boolean generateXml;

    /**
     * Decide whether to generate a JSON report or not.
     *
     * @parameter default-value="false" expression="${maven.clover.generateJson}"
     */
    private boolean generateJson;
    /**
     * Decide whether to generate a Clover historical report or not.
     *
     * @parameter default-value="false" expression="${maven.clover.generateHistorical}"
     */
    private boolean generateHistorical;

    /**
     * How to order coverage tables.
     *
     * @parameter default-value="PcCoveredAsc" expression="${maven.clover.orderBy}"
     */
    private String orderBy;

    /**
     * Comma or space separated list of Clover somesrcexcluded (block, statement or method filers) to exclude when
     * generating coverage reports.
     *
     * @parameter expression="${maven.clover.contextFilters}" default-value=""
     */
    private String contextFilters;

    /**
     * Style of the HTML report: ADG (default) or CLASSIC (deprecated).
     *
     * @deprecated this parameter will be removed in next major release
     * @parameter expression="${maven.clover.reportStyle}" default-value="ADG"
     */
    private String reportStyle;

    /**
     * Specifies whether to include failed test coverage when calculating the total coverage percentage.
     *
     * @parameter expression="${maven.clover.includeFailedTestCoverage}" default-value="false"
     * @since 4.4.0
     */
    private boolean includeFailedTestCoverage;

    /**
     * Whether to show inner functions, i.e. functions declared inside methods in the report. This applies to Java8
     * lambda functions for instance. If set to <code>false</code> then they are hidden on the list of methods, but
     * code metrics still include them.
     *
     * Note: if you will use showLambdaFunctions=true and showInnerFunctions=false then only lambda functions declared
     * as a class field will be listed.
     *
     * @parameter expression="${maven.clover.showInnerFunctions}" default-value="false"
     * @since 3.2.1
     */
    private boolean showInnerFunctions;

    /**
     * Whether to show lambda functions in the report. Lambda functions can be either declared inside method body
     * or as a class field. If set to <code>false</code> then they are hidden on the list of methods, but code
     * metrics still include them.
     *
     * Note: if you will use showLambdaFunctions=true and showInnerFunctions=false then only lambda functions declared
     * as a class field will be listed.
     *
     * @parameter expression="${maven.clover.showLambdaFunctions}" default-value="false"
     * @since 3.2.1
     */
    private boolean showLambdaFunctions;

    /**
     * Calculate and show unique per-test coverage (for large projects, this can take a significant amount of time).
     *
     * @parameter expression="${maven.clover.showUniqueCoverage}" default-value="false"
     * @since 4.4.0
     */
    private boolean showUniqueCoverage;

    /**
     * Title of the report
     * 
     * @parameter expression="${maven.clover.title}" default-value="${project.name} ${project.version}"
     */
    private String title;
    
    /**
     * Title anchor of the report
     * 
     * @parameter expression="${maven.clover.titleAnchor}" default-value="${project.url}"
     */
    private String titleAnchor;

    /**
     * The charset to use in the html reports.
     *
     * @parameter expression="${maven.clover.charset}" default-value="UTF-8"
     */
    private String charset;

    /**
     * <p>Note: This is passed by Maven and must not be configured by the user.</p>
     *
     * @component
     */
    private Renderer siteRenderer;

    /**
     * <p>The Maven project instance for the executing project.</p>
     * <p>Note: This is passed by Maven and must not be configured by the user.</p>
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * <p>The projects in the reactor for aggregation report.</p>
     * <p>Note: This is passed by Maven and must not be configured by the user.</p>
     *
     * @parameter expression="${reactorProjects}"
     * @readonly
     */
    private List<MavenProject> reactorProjects;

    /**
     * @parameter expression="${maven.clover.licenseLocation}"
     * @see com.atlassian.maven.plugin.clover.internal.AbstractCloverMojo#licenseLocation
     */
    private String licenseLocation;

    /**
     * @parameter expression="${maven.clover.license}"
     * @see com.atlassian.maven.plugin.clover.internal.AbstractCloverMojo#license
     */
    private String license;

    /**
     * Resource manager used to locate any Clover license file provided by the user.
     *
     * @component
     */
    private ResourceManager resourceManager;

    /**
     * A span specifies the age of the coverage data that should be used when creating a report.
     *
     * @parameter expression="${maven.clover.span}"
     */
    private String span = Interval.DEFAULT_SPAN.toString();

    /**
     * If set to true, a report will be generated even in the absence of coverage data.
     *
     * @parameter expression="${maven.clover.alwaysReport}" default-value="true"
     */
    private boolean alwaysReport = true;

    /**
     * @see org.apache.maven.reporting.AbstractMavenReport#executeReport(java.util.Locale)
     */
    public void executeReport(final Locale locale) throws MavenReportException {
        if (!canGenerateReport()) {
            getLog().info("No report being generated for this module.");
        }

        // only run the report once, on the very last project.
        final MavenProject lastProject = getReactorProjects().get(getReactorProjects().size() - 1);
        final MavenProject thisProject = getProject();
        if (isSingleCloverDatabase() && !thisProject.equals(lastProject)) {
            getLog().info("Skipping report generation until the final project in the reactor.");
            return;
        }

        // Register the Clover license
        try {
            AbstractCloverMojo.registerLicenseFile(this.project, this.resourceManager, this.licenseLocation, getLog(),
                    this.getClass().getClassLoader(), this.license);
        }
        catch (MojoExecutionException e) {
            throw new MavenReportException("Failed to locate Clover license", e);
        }

        // Ensure the output directory exists
        this.outputDirectory.mkdirs();

        if (reportDescriptor == null) {
            reportDescriptor = resolveCloverDescriptor();
        } else if (!reportDescriptor.exists()){ // try finding this as a resource
            try {
                reportDescriptor = AbstractCloverMojo.getResourceAsFile(
                        project, resourceManager, reportDescriptor.getPath(), getLog(), this.getClass().getClassLoader());
            } catch (MojoExecutionException e) {
                throw new MavenReportException("Could not resolve report descriptor: " + reportDescriptor.getPath(), e);
            }
        }

        getLog().info("Using Clover report descriptor: " + reportDescriptor.getAbsolutePath());

        if(title != null && title.startsWith("Unnamed")) { // no project.name on the project
            title = project.getArtifactId() + " " + project.getVersion();
        }

        File singleModuleCloverDatabase = new File(resolveCloverDatabase());
        if (singleModuleCloverDatabase.exists()) {
            createAllReportTypes(resolveCloverDatabase(), title);
        }

        File mergedCloverDatabase = new File(this.cloverMergeDatabase);
        if (mergedCloverDatabase.exists()) {
            createAllReportTypes(this.cloverMergeDatabase, title + " (Aggregated)");
        }
    }

    /**
     * Example of title prefixes: "Maven Clover", "Maven Aggregated Clover"
     */
    private void createAllReportTypes(final String database, final String titlePrefix) throws MavenReportException {

        final String outpath = outputDirectory.getAbsolutePath();
        if (this.generateHtml) {
            createReport(database, "html", titlePrefix, outpath, outpath, false);
        }
        if (this.generatePdf) {
            createReport(database, "pdf", titlePrefix, outpath + "/clover.pdf", outpath + "/historical.pdf", true);
        }
        if (this.generateXml) {
            createReport(database, "xml", titlePrefix, outpath + "/clover.xml", null, false);
        }
        if (this.generateJson) {
            createReport(database, "json", titlePrefix, outpath, null, false);
        }
    } 

    /**
     * Note: We use Clover's <code>clover-report</code> Ant task instead of the Clover CLI APIs because the CLI
     * APIs are limited and do not support historical reports.
     */
    private void createReport(final String database, final String format, final String title,
                              final String output, final String historyOut, final boolean summary) {
        final Project antProject = new Project();
        antProject.init();

        PropertyHelper propertyHelper = PropertyHelper.getPropertyHelper( antProject );

        propertyHelper.setNext( new AntPropertyHelper( project, getLog() ) );

        antProject.setUserProperty("ant.file", reportDescriptor.getAbsolutePath());
        antProject.setCoreLoader(getClass().getClassLoader());

        addMavenProperties(antProject);
        
        antProject.setProperty("cloverdb", database);
        antProject.setProperty("output", output);
        antProject.setProperty("history", historyDir);
        antProject.setProperty("title", nullToEmpty(title)); // empty string will have it be ignore by clover
        antProject.setProperty("titleAnchor", nullToEmpty(titleAnchor));
        final String projectDir = project.getBasedir().getPath();
        antProject.setProperty("projectDir", projectDir);
        antProject.setProperty("testPattern", "**/src/test/**");
        antProject.setProperty("filter", nullToEmpty(contextFilters));
        antProject.setProperty("orderBy", orderBy);
        antProject.setProperty("charset", charset);
        antProject.setProperty("reportStyle", reportStyle);
        antProject.setProperty("type", format);
        antProject.setProperty("span", span);
        antProject.setProperty("alwaysReport", Boolean.toString(alwaysReport));
        antProject.setProperty("summary", Boolean.toString(summary));
        antProject.setProperty("showInnerFunctions", Boolean.toString(showInnerFunctions));
        antProject.setProperty("showLambdaFunctions", Boolean.toString(showLambdaFunctions));
        antProject.setProperty("showUniqueCoverage", Boolean.toString(showUniqueCoverage));
        antProject.setProperty("includeFailedTestCoverage", Boolean.toString(includeFailedTestCoverage));
        if (historyOut != null) {
            antProject.setProperty("historyout", historyOut);
        }

        AbstractCloverMojo.registerCloverAntTasks(antProject, getLog());
        ProjectHelper.configureProject(antProject, reportDescriptor);
        antProject.setBaseDir(project.getBasedir());
        String target = (generateHistorical && isHistoricalDirectoryValid(output) && historyOut != null)
                ? "historical"
                : "current";
        antProject.executeTarget(target);
    }

    private void addMavenProperties(final Project antProject) {
        final Map<Object, Object> properties = getProject().getProperties();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            getLog().debug("Setting Property: " + entry.getKey().toString() + " = " + entry.getValue().toString());
            antProject.setProperty(entry.getKey().toString(), entry.getValue().toString());
        }
    }

    private boolean isHistoricalDirectoryValid(final String outFile) {
        boolean isValid = false;

        File dir = new File(this.historyDir);
        if (dir.exists()) {
            if (dir.listFiles().length > 0) {
                isValid = true;
            } else if (generateHistorical){
                getLog().warn("No Clover historical data found in [" + this.historyDir + "], skipping Clover "
                        + "historical report generation ([" + outFile + "])");
            }
        } else if (generateHistorical){
            getLog().warn("Clover historical directory [" + this.historyDir + "] does not exist, skipping Clover "
                    + "historical report generation ([" + outFile + "])");
        }

        return isValid;
    }
    
    /**
     * @see org.apache.maven.reporting.MavenReport#getOutputName()
     */
    public String getOutputName() {
        return "clover/index";
    }



    /**
     * @see org.apache.maven.reporting.MavenReport#getDescription(java.util.Locale)
     */
    public String getDescription(final Locale locale) {
        return getBundle(locale).getString("report.clover.description");
    }

    private static ResourceBundle getBundle(final Locale locale) {
        return ResourceBundle.getBundle("clover-report", locale, CloverReportMojo.class.getClassLoader());
    }

    /**
     * @see org.apache.maven.reporting.AbstractMavenReport#getOutputDirectory()
     */
    protected String getOutputDirectory() {
        return this.outputDirectory.getAbsoluteFile().toString();
    }

    /**
     * @see org.apache.maven.reporting.AbstractMavenReport#getSiteRenderer()
     */
    protected Renderer getSiteRenderer() {
        return this.siteRenderer;
    }

    /**
     * @see org.apache.maven.reporting.AbstractMavenReport#getProject()
     */
    public MavenProject getProject() {
        return this.project;
    }

    /**
     * @see org.apache.maven.reporting.MavenReport#getName(java.util.Locale)
     */
    public String getName(final Locale locale) {
        return getBundle(locale).getString("report.clover.name");
    }

    /**
     * Always return true as we're using the report generated by Clover rather than creating our own report.
     *
     * @return true
     */
    public boolean isExternalReport() {
        return true;
    }

    /**
     * Generate reports if a Clover module database or a Clover merged database exist.
     *
     * @return true if a project should be generated
     * @see org.apache.maven.reporting.AbstractMavenReport#canGenerateReport()
     */
    public boolean canGenerateReport() {
        boolean canGenerate = false;


        AbstractCloverMojo.waitForFlush(this.waitForFlush, this.flushInterval);

        File singleModuleCloverDatabase = new File(resolveCloverDatabase());
        File mergedCloverDatabase = new File(this.cloverMergeDatabase);

        if (singleModuleCloverDatabase.exists() || mergedCloverDatabase.exists()) {
            if (this.generateHtml || this.generatePdf || this.generateXml) {
                canGenerate = true;
            }
        } else {
            getLog().warn("No Clover database found, skipping report generation");
        }

        return canGenerate;
    }

    /**
     * @see org.apache.maven.reporting.AbstractMavenReport#setReportOutputDirectory(java.io.File)
     */
    public void setReportOutputDirectory(final File reportOutputDirectory) {
        if ((reportOutputDirectory != null) && (!reportOutputDirectory.getAbsolutePath().endsWith("clover"))) {
            this.outputDirectory = new File(reportOutputDirectory, "clover");
        } else {
            this.outputDirectory = reportOutputDirectory;
        }
    }

    /**
     * The logic here is taken from AbstractSiteRenderingMojo#resolveSiteDescriptor in the maven-site-plugin.
     * See also: http://docs.codehaus.org/display/MAVENUSER/Mojo+Developer+Cookbook
     *
     * @return the clover report configuration file to use
     * @throws MavenReportException if at least the default file can't be resolved
     */
    protected File resolveCloverDescriptor()
            throws MavenReportException {

        if (resolveReportDescriptor) {
            getLog().info("Attempting to resolve the clover-report configuration as an xml artifact.");
            Artifact artifact = artifactFactory.createArtifactWithClassifier(
                    project.getGroupId(),
                    project.getArtifactId(),
                    project.getVersion(),
                    "xml", "clover-report");

            try {
                artifactResolver.resolve(artifact, repositories, localRepository);
                return artifact.getFile();
            } catch (ArtifactResolutionException e) {
                getLog().warn(e.getMessage(), e);
            } catch (ArtifactNotFoundException e) {
                getLog().warn(e.getMessage(), e);
            }
        }
        try {
            getLog().info("Using /default-clover-report descriptor.");
            final File file = AbstractCloverMojo.getResourceAsFile(project,
                    resourceManager,
                    "/default-clover-report.xml",
                    getLog(),
                    this.getClass().getClassLoader());
            file.deleteOnExit();
            return file;

        } catch (Exception e) {
            throw new MavenReportException("Could not resolve default-clover-report.xml. " +
                    "Please try specifying this via the maven.clover.reportDescriptor property.", e);
        }
    }

    public String getCloverDatabase()
    {
        return cloverDatabase;
    }

    public String resolveCloverDatabase()
    {
        return new ConfigUtil(this).resolveCloverDatabase();
    }

    public List<MavenProject> getReactorProjects() {
        return reactorProjects;
    }

    public boolean isSingleCloverDatabase() {
        return this.singleCloverDatabase;
    }
}
 