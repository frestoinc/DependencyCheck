/*
 * This file is part of dependency-check-ant.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2021 The OWASP Foundation. All Rights Reserved.
 */
package org.owasp.dependencycheck.analyzer;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.analyzer.exception.SearchException;
import org.owasp.dependencycheck.analyzer.exception.UnexpectedAnalysisException;
import org.owasp.dependencycheck.data.nodeaudit.Advisory;
import org.owasp.dependencycheck.data.nodeaudit.NpmPayloadBuilder;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.exception.InitializationException;
import org.owasp.dependencycheck.utils.FileFilterBuilder;
import org.owasp.dependencycheck.utils.Settings;
import org.owasp.dependencycheck.utils.URLConnectionFailureException;
import org.owasp.dependencycheck.utils.processing.ProcessReader;
import org.semver4j.Semver;
import org.semver4j.SemverException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.springett.parsers.cpe.exceptions.CpeValidationException;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import javax.annotation.concurrent.ThreadSafe;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@ThreadSafe
public class YarnAuditAnalyzer extends AbstractNpmAnalyzer {

    /**
     * The Logger for use throughout the class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(YarnAuditAnalyzer.class);

    /**
     * The major version of the Yarn Classic CLI.
     */
    private static final int YARN_CLASSIC_MAJOR_VERSION = 1;

    /**
     * The file name to scan.
     */
    public static final String YARN_PACKAGE_LOCK = "yarn.lock";

    /**
     * Filter that detects files named "yarn.lock"
     */
    private static final FileFilter LOCK_FILE_FILTER = FileFilterBuilder.newInstance()
            .addFilenames(YARN_PACKAGE_LOCK).build();

    /**
     * An expected error from `yarn audit --offline --verbose --json` that will
     * be ignored.
     */
    private static final String EXPECTED_ERROR = "{\"type\":\"error\",\"data\":\"Can't make a request in "
            + "offline mode (\\\"https://registry.yarnpkg.com/-/npm/v1/security/audits\\\")\"}\n";

    /**
     * The path to the `yarn` executable.
     */
    private String yarnPath;

    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_YARN_AUDIT_ENABLED;
    }

    @Override
    protected FileFilter getFileFilter() {
        return LOCK_FILE_FILTER;
    }

    @Override
    public String getName() {
        return "Yarn Audit Analyzer";
    }

    @Override
    public AnalysisPhase getAnalysisPhase() {
        return AnalysisPhase.FINDING_ANALYSIS;
    }

    /**
     * Extracts the major version from a version string.
     *
     * @param dependency the dependency to extract the yarn version from
     * @return the major version (e.g., `4` from "4.2.1")
     */
    private int getYarnMajorVersion(Dependency dependency) {
        final var yarnVersion = getYarnVersion(dependency);
        try {
            final var semver = Semver.coerce(yarnVersion);
            return semver.getMajor();
        } catch (SemverException e) {
            throw new IllegalStateException("Invalid version string format", e);
        }
    }

    private String getYarnVersion(Dependency dependency) {
        final List<String> args = new ArrayList<>();
        args.add(getYarn());
        args.add("--version");
        final ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(getDependencyDirectory(dependency));
        LOGGER.debug("Launching: {}", args);
        try {
            final Process process = builder.start();
            try (ProcessReader processReader = new ProcessReader(process)) {
                processReader.readAll();
                final int exitValue = process.waitFor();
                if (exitValue != 0) {
                    throw new IllegalStateException("Unable to determine yarn version, unexpected response.");
                }
                final var yarnVersion = processReader.getOutput();
                if (StringUtils.isBlank(yarnVersion)) {
                    throw new IllegalStateException("Unable to determine yarn version, blank output.");
                }
                return yarnVersion;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to determine yarn version.", ex);
        }
    }


    /**
     * Initializes the analyzer once before any analysis is performed.
     *
     * @param engine a reference to the dependency-check engine
     * @throws InitializationException if there's an error during initialization
     */
    @Override
    protected void prepareFileTypeAnalyzer(Engine engine) throws InitializationException {
        super.prepareFileTypeAnalyzer(engine);
        if (!isEnabled()) {
            LOGGER.debug("{} Analyzer is disabled skipping yarn executable check", getName());
            return;
        }
        final List<String> args = new ArrayList<>();
        args.add(getYarn());
        args.add("--help");
        final ProcessBuilder builder = new ProcessBuilder(args);
        LOGGER.debug("Launching: {}", args);
        try {
            final Process process = builder.start();
            try (ProcessReader processReader = new ProcessReader(process)) {
                processReader.readAll();
                final int exitValue = process.waitFor();
                final int expectedExitValue = 0;
                final int yarnExecutableNotFoundExitValue = 127;
                switch (exitValue) {
                    case expectedExitValue:
                        LOGGER.debug("{} is enabled.", getName());
                        break;
                    case yarnExecutableNotFoundExitValue:
                    default:
                        this.setEnabled(false);
                        LOGGER.warn("The {} has been disabled after receiving exit value {}. Yarn executable was not " +
                                        "found or received a non-zero exit value.", getName(), exitValue);
                }
            }
        } catch (Exception ex) {
            this.setEnabled(false);
            LOGGER.warn("The {} has been disabled after receiving an exception. This can occur when Yarn executable " +
                    "is not found.", getName());
            throw new InitializationException("Unable to read yarn audit output.", ex);
        }
    }

    /**
     * Attempts to determine the path to `yarn`.
     *
     * @return the path to `yarn`
     */
    private String getYarn() {
        final String value;
        synchronized (this) {
            if (yarnPath == null) {
                final String path = getSettings().getString(Settings.KEYS.ANALYZER_YARN_PATH);
                if (path == null) {
                    yarnPath = "yarn";
                } else {
                    final File yarnFile = new File(path);
                    if (yarnFile.isFile()) {
                        yarnPath = yarnFile.getAbsolutePath();
                    } else {
                        LOGGER.warn("Provided path to `yarn` executable is invalid.");
                        yarnPath = "yarn";
                    }
                }
            }
            value = yarnPath;
        }
        return value;
    }

    /**
     * Workaround 64k limitation of InputStream, redirect stdout to a file that we will read later
     * instead of reading directly stdout from Process's InputStream which is topped at 64k
     *
     * @param builder a reference to the process builder
     * @return returns the standard out from the process
     */
    private String startAndReadStdoutToString(ProcessBuilder builder) throws AnalysisException {
        try {
            final File tmpFile = getSettings().getTempFile("yarn_audit", "json");
            builder.redirectOutput(tmpFile);
            final Process process = builder.start();
            try (ProcessReader processReader = new ProcessReader(process)) {
                processReader.readAll();
                final String errOutput = processReader.getError();

                if (!StringUtils.isBlank(errOutput) && !EXPECTED_ERROR.equals(errOutput)) {
                    LOGGER.debug("Process Error Out: {}", errOutput);
                    LOGGER.debug("Process Out: {}", processReader.getOutput());
                }
                return new String(Files.readAllBytes(tmpFile.toPath()), StandardCharsets.UTF_8);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AnalysisException("Yarn audit process was interrupted.", ex);
            }
        } catch (IOException ioe) {
            throw new AnalysisException("yarn audit failure; this error can be ignored if you are not analyzing projects with a yarn lockfile.", ioe);
        }
    }

    /**
     * Analyzes the yarn lock file to determine vulnerable dependencies. Uses
     * yarn audit --offline to generate the payload to be sent to the NPM API.
     *
     * @param dependency the yarn lock file
     * @param engine the analysis engine
     * @throws AnalysisException thrown if there is an error analyzing the file
     */
    @Override
    protected void analyzeDependency(Dependency dependency, Engine engine) throws AnalysisException {
        if (dependency.getDisplayFileName().equals(dependency.getFileName())) {
            engine.removeDependency(dependency);
        }
        final File packageLock = dependency.getActualFile();
        if (!packageLock.isFile() || packageLock.length() == 0 || !shouldProcess(packageLock)) {
            return;
        }
        final File packageJson = new File(packageLock.getParentFile(), "package.json");
        final List<Advisory> advisories;
        final MultiValuedMap<String, String> dependencyMap = new HashSetValuedHashMap<>();
        final var yarnMajorVersion = getYarnMajorVersion(dependency);
        if (YARN_CLASSIC_MAJOR_VERSION < yarnMajorVersion) {
            LOGGER.info("Analyzing using Yarn Berry audit");
            advisories = analyzePackageWithYarnBerry(dependency);
        } else {
            LOGGER.info("Analyzing using Yarn Classic audit");
            advisories = analyzePackageWithYarnClassic(packageLock, packageJson, dependency, dependencyMap);
        }
        try {
            processResults(advisories, engine, dependency, dependencyMap);
        } catch (CpeValidationException ex) {
            throw new UnexpectedAnalysisException(ex);
        }
    }

    private JsonObject fetchYarnAuditJson(Dependency dependency, boolean skipDevDependencies) throws AnalysisException {
        final List<String> args = new ArrayList<>();
        args.add(getYarn());
        args.add("audit");
        //offline audit is not supported - but the audit request is generated in the verbose output
        args.add("--offline");
        if (skipDevDependencies) {
            args.add("--groups");
            args.add("dependencies");
        }
        args.add("--json");
        args.add("--verbose");
        final ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(getDependencyDirectory(dependency));
        LOGGER.debug("Launching: {}", args);

        final String verboseJson = startAndReadStdoutToString(builder);
        final String auditRequestJson = Arrays.stream(verboseJson.split("\n"))
                .filter(line -> line.contains("Audit Request"))
                .findFirst().get();
        String auditRequest;
        try (JsonReader reader = Json.createReader(IOUtils.toInputStream(auditRequestJson, StandardCharsets.UTF_8))) {
            final JsonObject jsonObject = reader.readObject();
            auditRequest = jsonObject.getString("data");
            auditRequest = auditRequest.substring(15);
        }
        LOGGER.debug("Audit Request: {}", auditRequest);

        return Json.createReader(IOUtils.toInputStream(auditRequest, StandardCharsets.UTF_8)).readObject();
    }

    private static File getDependencyDirectory(Dependency dependency) {
        final File folder = dependency.getActualFile().getParentFile();
        if (!folder.isDirectory()) {
            throw new IllegalArgumentException(String.format("%s should have been a directory.", folder.getAbsolutePath()));
        }
        return folder;
    }

    /**
     * Analyzes the package and yarn lock files by extracting dependency
     * information, creating a payload to submit to the npm audit API,
     * submitting the payload, and returning the identified advisories.
     *
     * @param lockFile a reference to the package-lock.json
     * @param packageFile a reference to the package.json
     * @param dependency a reference to the dependency-object for the yarn.lock
     * @param dependencyMap a collection of module/version pairs; during
     * creation of the payload the dependency map is populated with the
     * module/version information.
     * @return a list of advisories
     * @throws AnalysisException thrown when there is an error creating or
     * submitting the npm audit API payload
     */
    private List<Advisory> analyzePackageWithYarnClassic(final File lockFile, final File packageFile,
                                                         Dependency dependency, MultiValuedMap<String, String> dependencyMap)
            throws AnalysisException {
        try {
            final boolean skipDevDependencies = getSettings().getBoolean(Settings.KEYS.ANALYZER_NODE_AUDIT_SKIPDEV, false);
            // Retrieves the contents of package-lock.json from the Dependency
            final JsonObject lockJson = fetchYarnAuditJson(dependency, skipDevDependencies);
            // Retrieves the contents of package-lock.json from the Dependency
            final JsonObject packageJson;
            try (JsonReader packageReader = Json.createReader(Files.newInputStream(packageFile.toPath()))) {
                packageJson = packageReader.readObject();
            }
            // Modify the payload to meet the NPM Audit API requirements
            final JsonObject payload = NpmPayloadBuilder.build(lockJson, packageJson, dependencyMap, skipDevDependencies);

            // Submits the package payload to the nsp check service
            return getSearcher().submitPackage(payload);

        } catch (URLConnectionFailureException e) {
            this.setEnabled(false);
            throw new AnalysisException("Failed to connect to the NPM Audit API (YarnAuditAnalyzer); the analyzer "
                    + "is being disabled and may result in false negatives.", e);
        } catch (IOException e) {
            LOGGER.debug("Error reading dependency or connecting to NPM Audit API", e);
            this.setEnabled(false);
            throw new AnalysisException("Failed to read results from the NPM Audit API (YarnAuditAnalyzer); "
                    + "the analyzer is being disabled and may result in false negatives.", e);
        } catch (JsonException e) {
            throw new AnalysisException(String.format("Failed to parse %s file from the NPM Audit API "
                    + "(YarnAuditAnalyzer).", lockFile.getPath()), e);
        } catch (SearchException ex) {
            LOGGER.error("YarnAuditAnalyzer failed on {}", dependency.getActualFilePath());
            throw ex;
        }
    }

    private List<JSONObject> fetchYarnAdvisories(Dependency dependency, boolean skipDevDependencies) throws AnalysisException {
        final List<String> args = new ArrayList<>();

        args.add(getYarn());
        args.add("npm");
        args.add("audit");
        if (skipDevDependencies) {
            args.add("--environment");
            args.add("production");
        }
        args.add("--all");
        args.add("--recursive");
        args.add("--json");
        final ProcessBuilder builder = new ProcessBuilder(args);
        builder.directory(getDependencyDirectory(dependency));

        final String advisoriesJsons = startAndReadStdoutToString(builder);

        LOGGER.debug("Advisories JSON: {}", advisoriesJsons);
        final String[] advisoriesJsonArray = Stream.of(advisoriesJsons.split("\n"))
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);
        try {
            final List<JSONObject> advisories = new ArrayList<>();
            for (String advisoriesJson : advisoriesJsonArray) {
                advisories.add(new JSONObject(advisoriesJson));
            }

            return advisories;
        } catch (JSONException e) {
            throw new AnalysisException("Failed to parse the response from NPM Audit API "
                    + "(YarnBerryAuditAnalyzer).", e);
        }
    }

    /**
     * Analyzes the package and yarn lock files by calling yarn npm audit and returning the identified advisories.
     *
     * @param dependency a reference to the dependency-object for the yarn.lock
     * @return a list of advisories
     */
    private List<Advisory> analyzePackageWithYarnBerry(Dependency dependency) throws AnalysisException {
        try {
            final var skipDevDependencies = getSettings().getBoolean(Settings.KEYS.ANALYZER_NODE_AUDIT_SKIPDEV, false);
            final var advisoryJsons = fetchYarnAdvisories(dependency, skipDevDependencies);
            return parseAdvisoryJsons(advisoryJsons);
        } catch (JSONException e) {
            throw new AnalysisException("Failed to parse the response from NPM Audit API "
                    + "(YarnBerryAuditAnalyzer).", e);
        } catch (SearchException ex) {
            LOGGER.error("YarnBerryAuditAnalyzer failed on {}", dependency.getActualFilePath());
            throw ex;
        }
    }

    private static List<Advisory> parseAdvisoryJsons(List<JSONObject> advisoryJsons) throws JSONException {
        final List<Advisory> advisories = new ArrayList<>();
        for (JSONObject advisoryJson : advisoryJsons) {
            final var advisory = new Advisory();
            final var object = advisoryJson.getJSONObject("children");
            final var moduleName = advisoryJson.optString("value", null);
            final var id = object.getString("ID");
            final var url = object.optString("URL", null);
            final var ghsaId = extractGhsaId(url);
            final var issue = object.optString("Issue", null);
            final var severity = object.optString("Severity", null);
            final var vulnerableVersions = object.optString("Vulnerable Versions", null);
            final var treeVersions = object.optJSONArray("Tree Versions");
            final var treeVersionsLength = treeVersions == null ? 0 : treeVersions.length();
            final var versions = new ArrayList<String>();
            for (int i = 0; i < treeVersionsLength; i++) {
                versions.add(treeVersions.getString(i));
            }
            if (versions.isEmpty()) {
                versions.add(null);
            }
            for (String version : versions) {
                advisory.setGhsaId(ghsaId);
                advisory.setTitle(issue);
                advisory.setOverview("URL:" + url + "ID: " + id);
                advisory.setSeverity(severity);
                advisory.setVulnerableVersions(vulnerableVersions);
                advisory.setModuleName(moduleName);
                advisory.setVersion(version);
                advisory.setCwes(new ArrayList<>());
                advisories.add(advisory);
            }
        }
        return advisories;
    }

    private static String extractGhsaId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        final int lastSlashIndex = url.lastIndexOf('/');
        if (lastSlashIndex == -1 || lastSlashIndex == url.length() - 1) {
            return null;
        }
        return url.substring(lastSlashIndex + 1);
    }
}
