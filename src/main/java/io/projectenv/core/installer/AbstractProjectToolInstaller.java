package io.projectenv.core.installer;

import io.projectenv.core.archive.ArchiveExtractor;
import io.projectenv.core.common.LockFileHelper;
import io.projectenv.core.common.LockFileHelper.LockFile;
import io.projectenv.core.common.OperatingSystem;
import io.projectenv.core.common.ProcessEnvironmentHelper;
import io.projectenv.core.common.YamlHelper;
import io.projectenv.core.configuration.DownloadUri;
import io.projectenv.core.configuration.PostExtractionCommand;
import io.projectenv.core.configuration.ToolConfiguration;
import io.projectenv.core.toolinfo.ToolInfo;
import io.projectenv.core.toolinfo.collector.ImmutableToolInfoCollectorContext;
import io.projectenv.core.toolinfo.collector.ToolInfoCollectorContext;
import io.projectenv.core.toolinfo.collector.ToolInfoCollectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public abstract class AbstractProjectToolInstaller<ToolConfigurationType extends ToolConfiguration, ToolInfoType extends ToolInfo>
        implements ProjectToolInstaller<ToolConfigurationType, ToolInfoType> {

    private static final String TOOL_INSTALLATION_VERSION_FILE = "version.yml";
    private static final String TOOL_INSTALLATION_LOCK_FILE = ".lock";
    private static final String TOOL_BINARIES_DIRECTORY_NAME = "binary";

    private final ArchiveExtractor archiveExtractor = new ArchiveExtractor();

    @Override
    public ToolInfoType installTool(ToolConfigurationType toolConfiguration, ProjectToolInstallerContext context) throws Exception {
        File systemSpecificToolDirectory = getSystemSpecificToolDirectory(context);
        FileUtils.forceMkdir(systemSpecificToolDirectory);

        try (LockFile lockFile = LockFileHelper.tryAcquireLockFile(getLockFile(systemSpecificToolDirectory), Duration.ofMinutes(5))) {
            File toolBinariesDirectory = getToolBinariesDirectory(systemSpecificToolDirectory);

            if (isCurrentToolUpToDate(toolConfiguration, systemSpecificToolDirectory)) {
                return collectToolInfo(toolConfiguration, toolBinariesDirectory, context);
            }

            cleanToolInstallationDirectory(toolBinariesDirectory);
            extractToolInstallation(toolConfiguration, toolBinariesDirectory);

            ToolInfoType toolInfo = collectToolInfo(toolConfiguration, toolBinariesDirectory, context);
            executePostInstallationCommands(toolConfiguration, toolInfo, context);
            executePostInstallationSteps(toolConfiguration, toolInfo, context);
            storeToolVersion(toolConfiguration, systemSpecificToolDirectory);

            return toolInfo;
        }
    }

    @Override
    public boolean supportsTool(ToolConfiguration toolConfiguration) {
        return getToolConfigurationClass().isAssignableFrom(toolConfiguration.getClass());
    }

    protected abstract Class<ToolConfigurationType> getToolConfigurationClass();

    private File getSystemSpecificToolDirectory(ProjectToolInstallerContext context) {
        return new File(context.getToolRoot(), OperatingSystem.getCurrentOS().name().toLowerCase(Locale.ROOT));
    }

    private File getLockFile(File systemSpecificToolDirectory) {
        return new File(systemSpecificToolDirectory, TOOL_INSTALLATION_LOCK_FILE);
    }

    private File getToolBinariesDirectory(File toolInstallationDirectory) {
        return new File(toolInstallationDirectory, TOOL_BINARIES_DIRECTORY_NAME);
    }

    private boolean isCurrentToolUpToDate(ToolConfigurationType configuration, File toolInstallationDirectory) throws Exception {
        String currentToolVersion = getCurrentToolVersion(toolInstallationDirectory);
        String requestedToolVersion = getRequestedToolVersion(configuration);
        return StringUtils.equals(currentToolVersion, requestedToolVersion);
    }

    private String getCurrentToolVersion(File toolInstallationDirectory) throws Exception {
        File toolVersionFile = getToolVersionFile(toolInstallationDirectory);
        if (toolVersionFile.exists()) {
            return FileUtils.readFileToString(toolVersionFile, StandardCharsets.UTF_8);
        } else {
            return null;
        }
    }

    private File getToolVersionFile(File toolInstallationDirectory) {
        return new File(toolInstallationDirectory, TOOL_INSTALLATION_VERSION_FILE);
    }

    private String getRequestedToolVersion(ToolConfigurationType configuration) throws IOException {
        return YamlHelper.writeValueAsString(configuration);
    }

    private void storeToolVersion(ToolConfigurationType configuration, File toolDirectory) throws Exception {
        YamlHelper.writeValue(configuration, getToolVersionFile(toolDirectory));
    }

    private DownloadUri getSystemSpecificDownloadUri(ToolConfigurationType configuration) {
        OperatingSystem currentOS = OperatingSystem.getCurrentOS();

        return configuration
                .getDownloadUris()
                .stream()
                .filter(downloadUriConfiguration -> {
                    OperatingSystem targetOperatingSystem = downloadUriConfiguration.getTargetOs();

                    return targetOperatingSystem == OperatingSystem.ALL || targetOperatingSystem == currentOS;
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no download URI for OS " + currentOS + " available"));
    }

    private void cleanToolInstallationDirectory(File toolInstallationDirectory) throws Exception {
        if (toolInstallationDirectory.exists()) {
            FileUtils.forceDelete(toolInstallationDirectory);
        }
    }

    private void extractToolInstallation(ToolConfigurationType toolInstallationConfiguration, File toolInstallationDirectory) throws Exception {
        URI systemSpecificDownloadUri = URI.create(getSystemSpecificDownloadUri(toolInstallationConfiguration).getDownloadUri());

        String archiveName = FilenameUtils.getName(systemSpecificDownloadUri.getPath());
        File tempArchive = Files.createTempFile(toolInstallationConfiguration.getToolName(), archiveName).toFile();

        try (InputStream inputStream = new BufferedInputStream(systemSpecificDownloadUri.toURL().openStream());
             OutputStream outputStream = new FileOutputStream(tempArchive)) {
            IOUtils.copy(inputStream, outputStream);
        }

        archiveExtractor.extractArchive(tempArchive, toolInstallationDirectory);

        FileUtils.forceDelete(tempArchive);
    }

    private ToolInfoType collectToolInfo(ToolConfigurationType toolConfiguration, File toolBinariesDirectory, ProjectToolInstallerContext context) throws Exception {
        ToolInfoCollectorContext collectorContext = ImmutableToolInfoCollectorContext
                .builder()
                .projectRoot(context.getProjectRoot())
                .toolBinariesRoot(toolBinariesDirectory)
                .build();

        return ToolInfoCollectors.collectToolInfo(toolConfiguration, collectorContext);
    }

    private void executePostInstallationCommands(ToolConfigurationType toolInstallationConfiguration, ToolInfoType toolInfo, ProjectToolInstallerContext context) throws Exception {
        Map<String, String> processEnvironment = ProcessEnvironmentHelper.createProcessEnvironmentFromToolInfo(toolInfo);

        for (PostExtractionCommand postInstallationCommand : toolInstallationConfiguration.getPostExtractionCommands()) {
            File executable = ProcessEnvironmentHelper.resolveExecutableFromToolInfo(postInstallationCommand.getExecutableName(), toolInfo);
            if (executable == null) {
                throw new IllegalStateException("failed to resolve executable with name " + postInstallationCommand.getExecutableName());
            }

            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.environment().putAll(processEnvironment);
            processBuilder.inheritIO();
            processBuilder.command().add(executable.getAbsolutePath());
            processBuilder.command().addAll(postInstallationCommand.getArguments());
            processBuilder.directory(context.getProjectRoot());
            Process process = processBuilder.start();
            process.waitFor();
        }
    }

    protected void executePostInstallationSteps(ToolConfigurationType toolInstallationConfiguration, ToolInfoType toolInfo, ProjectToolInstallerContext context) throws Exception {
        // noop
    }

}
