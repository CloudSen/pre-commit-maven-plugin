package no.oms.maven.precommit.lib;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

final class PythonException extends Exception {

    PythonException(String message) {
        super(message);
    }

    PythonException(String message, Throwable cause) {
        super(message, cause);
    }
}

interface PythonHandle {

    VirtualEnvDescriptor setupVirtualEnv(File directory, String envName) throws PythonException;

    void sourceEnv(VirtualEnvDescriptor env) throws PythonException;

    void installAllDependencies(VirtualEnvDescriptor env, File setupFile) throws PythonException;

    void installPyYaml(VirtualEnvDescriptor env) throws PythonException;

    void installSetupTools(VirtualEnvDescriptor env) throws PythonException;

    void installIntoVirtualEnv(VirtualEnvDescriptor env, File setupFile) throws PythonException;

    void installGitHooks(VirtualEnvDescriptor env, HookType[] hookTypes) throws PythonException;
}

final class VirtualEnvDescriptor {

    File directory;
    String name;

    VirtualEnvDescriptor(File directory, String name) {
        this.directory = new File(directory, "." + name + "-virtualenv");
        this.name = name;
    }
}

final class DefaultPythonHandle implements PythonHandle {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonHandle.class);

    @Override
    public VirtualEnvDescriptor setupVirtualEnv(File directory, String envName) throws PythonException {

        VirtualEnvDescriptor env = new VirtualEnvDescriptor(directory, envName);

        LOGGER.info("About to setup virtual env {}", env.directory.getAbsolutePath());

        if (env.directory.exists()) {
            LOGGER.info("Virtual env already exists, skipping");
            return env;
        }

        String[] command = { "python3", "-m", "venv", env.directory.getAbsolutePath() };
        LOGGER.debug("Running {}", (Object) command);

        try {
            Process child = Runtime.getRuntime().exec(command);
            int result = child.waitFor();
            String stdout = IOUtils.toString(child.getInputStream());

            if (result != 0) {
                throw new PythonException(
                        "Could not create virtual env " + env.directory.getAbsolutePath() + ". return code " + result +
                                "\nPython said: " + stdout);
            }
        } catch (IOException e) {
            throw new PythonException("Failed to execute python", e);
        } catch (InterruptedException e) {
            throw new PythonException("Unexpected interruption of while waiting for python virtualenv process", e);
        }

        return env;
    }

    @Override
    public void installAllDependencies(VirtualEnvDescriptor env, File setupFile) throws PythonException {
        LOGGER.info("About to install all dependencies into env {}", env.name);

        if (!env.directory.exists()) {
            throw new PythonException("Virtual env " + env.name + " does not exist");
        }

        String activateCommand = isWindows() ? env.directory.getAbsolutePath() + "Scripts\\activate" : "bin/activate";
        String[] command;
        if (isWindows()) {
            command = new String[] {
                env.directory.getAbsolutePath() + File.separator + activateCommand,
                "&&",
                "pip",
                "install",
                "pyyaml setuptools",
                "--disable-pip-version-check",
                "&&",
                "python",
                setupFile.getAbsolutePath(),
                "install"
            };
        } else {
            command = new String[] {
                "/bin/bash",
                "-c",
                "source "
                + env.directory.getAbsolutePath() + File.separator + activateCommand
                +" && "
                + "pip install pyyaml setuptools --disable-pip-version-check"
                + " && cd "
                + env.directory.getAbsolutePath()
                + " && cd .."
                + " && python "
                + setupFile.getAbsolutePath()
                +" install"
            };
        }
        LOGGER.debug("Running command: {}", (Object) command);

        executePythonCommand(command, null);
        LOGGER.info("Successfully installed all dependencies into {}", env.name);
    }

    @Override
    public void installPyYaml(VirtualEnvDescriptor env) throws PythonException {
        LOGGER.info("About to install pyyaml into env {}", env.name);

        if (!env.directory.exists()) {
            throw new PythonException("Virtual env " + env.name + " does not exist");
        }

        String activateCommand = isWindows() ? "Scripts\\activate" : "bin/activate";
        String[] command = {
            env.directory.getAbsolutePath() + File.separator + activateCommand,
            "&&",
            "pip",
            "install",
            "pyyaml",
            "--disable-pip-version-check"
        };
        LOGGER.debug("Running command: {}", (Object) command);

        executePythonCommand(command, null);
        LOGGER.info("Successfully installed pyyaml into {}", env.name);
    }

    @Override
    public void installSetupTools(VirtualEnvDescriptor env) throws PythonException {
        LOGGER.info("About to install setuptools into env {}", env.name);

        if (!env.directory.exists()) {
            throw new PythonException("Virtual env " + env.name + " does not exist");
        }

        String pipCommand = getPipExecutable(env);
        String[] winCommand = { pipCommand, "-m", "pip", "install", "setuptools", "--disable-pip-version-check" };
        String[] unixCommand = { pipCommand, "install", "setuptools", "--disable-pip-version-check" };
        String[] command = isWindows() ? winCommand : unixCommand;
        String[] environment = { "VIRTUAL_ENV=" + env.directory.getAbsolutePath() };
        LOGGER.debug("Running {} with command: {}", environment, command);

        executePythonCommand(command, environment);
        LOGGER.info("Successfully installed setuptools into {}", env.name);
    }

    @Override
    public void installIntoVirtualEnv(VirtualEnvDescriptor env, File setupFile) throws PythonException {
        LOGGER.info("About to install binary into virtual env {}", env.name);

        if (!env.directory.exists()) {
            throw new PythonException("Virtual env " + env.name + " does not exist");
        }

        String pythonCommand = getPython3Executable();
        String[] command = { pythonCommand, setupFile.getAbsolutePath(), "install" };
        String[] environment = { "VIRTUAL_ENV=" + env.directory.getAbsolutePath() };
        LOGGER.debug("Running {} with command: {} in {}", environment, command, setupFile.getParentFile());

        executePythonCommand(command, environment, setupFile.getParentFile());
        LOGGER.info("Successfully installed into {}", env.name);
    }

    @Override
    public void installGitHooks(VirtualEnvDescriptor env, HookType[] hookTypes) throws PythonException {
        LOGGER.info("About to install commit hooks into virtual env {}", env.name);

        if (!env.directory.exists()) {
            throw new PythonException("Virtual env " + env.name + " does not exist");
        }

        if (hookTypes == null || hookTypes.length == 0) {
            throw new PythonException("Providing the hook types to install are required");
        }

        for (HookType type : hookTypes) {
            String preCommitCommand = getPreCommitExecutable(env);
            String[] command = { preCommitCommand, "install", "--install-hooks", "--overwrite", "--hook-type", type.getValue() };
            String[] environment = {
                "VIRTUAL_ENV=" + env.directory.getAbsolutePath(),
                "PATH=" + System.getenv("PATH")
            };
            LOGGER.debug("Running {} with command: {}", environment, command);

            executePythonCommand(command, environment);
        }

        LOGGER.info("Successfully installed Git commit hooks");
    }

    private String getPython3Executable() throws PythonException {
        return "python";
    }

    private boolean binaryExists(String binaryName) {
        Runtime runtime = Runtime.getRuntime();

        try {
            Process proc = runtime.exec(new String[] { binaryName, "--version" });
            String output = IOUtils.toString(proc.getInputStream());

            if (proc.waitFor() == 0 && checkVersion(output)) {
                LOGGER.debug("Located python binary `{}`", binaryName);
                return true;
            }
        } catch (Exception ignored) {
        }

        LOGGER.debug("Did not locate a python binary called `{}`", binaryName);
        return false;
    }

    private boolean checkVersion(String pythonOutput) throws PythonException {
        try {
            String versionString = pythonOutput.split(" ")[1];
            int majorVersion = Integer.parseInt(versionString.split("\\.")[0]);
            int minorVersion = Integer.parseInt(versionString.split("\\.")[1]);

            return majorVersion >= 3 && minorVersion >= 3;
        } catch (Exception exception) {
            throw new PythonException("Unexpected python version output: " + pythonOutput, exception);
        }
    }

    private String getPipExecutable(VirtualEnvDescriptor env) throws PythonException {
        return isWindows() ? getPython3Executable() : new File(env.directory, "bin/pip").getAbsolutePath();
    }

    private String getPreCommitExecutable(VirtualEnvDescriptor env) {
        String binDir = isWindows() ? "Scripts" : "bin";
        return new File(env.directory, binDir + File.separator + "pre-commit").getAbsolutePath();
    }

    private void executePythonCommand(String[] command, String[] environment) throws PythonException {
        executePythonCommand(command, environment, null);
    }

    private void executePythonCommand(String[] command, String[] environment, File workingDirectory) throws PythonException {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (environment != null) {
                processBuilder.environment().putAll(System.getenv());
                for (String envVar : environment) {
                    String[] keyValue = envVar.split("=");
                    processBuilder.environment().put(keyValue[0], keyValue[1]);
                }
            }
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory);
            }
            processBuilder.redirectErrorStream(true);

            Process child = processBuilder.start();

            // Write messages to output
            BackgroundStreamLogger errorGobbler = new BackgroundStreamLogger(child.getInputStream(), "DEBUG");
            errorGobbler.start();

            int result = child.waitFor();

            if (result != 0) {
                String output = IOUtils.toString(child.getInputStream());
                throw new PythonException("Command failed with return code " + result + "\nOutput:\n" + output);
            }
        } catch (IOException e) {
            throw new PythonException("Failed to execute python command", e);
        } catch (InterruptedException e) {
            throw new PythonException("Unexpected interruption of while waiting for the python command", e);
        }
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("win");
    }

    @Override
    public void sourceEnv(VirtualEnvDescriptor env) throws PythonException {
        String[] command;
        if (isWindows()) {
            command = new String[] { "cmd.exe", "/c", env.directory.getAbsolutePath() + "\\Scripts\\activate.bat" };
        } else {
            command = new String[] { "bash", "-c", "source " + env.directory.getAbsolutePath() + "/bin/activate" };
        }

        LOGGER.info("About to activate virtual env using {}", String.join(" ", command));

        if (!env.directory.exists()) {
            throw new PythonException("Virtual env directory does not exist at " + env.directory.getAbsolutePath());
        }

        try {
            executePythonCommand(command, new String[0]);
        } catch (PythonException e) {
            LOGGER.error("Failed to activate virtual environment", e);
        }
    }
}
