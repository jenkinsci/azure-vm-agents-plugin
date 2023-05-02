/*
 Copyright 2016 Microsoft, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoft.azure.vmagent.remote;

import com.azure.resourcemanager.compute.models.OperatingSystemTypes;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.jcraft.jsch.*;
import com.microsoft.azure.vmagent.AzureVMAgent;
import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.AzureVMComputer;
import com.microsoft.azure.vmagent.Messages;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.FailureStage;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jvnet.localizer.Localizable;

import java.io.*;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SSH Launcher class.
 *
 * @author Suresh nallamilli (snallami@gmail.com)
 */
public class AzureVMAgentSSHLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(AzureVMAgentSSHLauncher.class.getName());

    private static final String REMOTE_INIT_FILE_NAME = "init.sh";

    private static final String REMOTE_INIT_FILE_NAME_WINDOWS = "/init.ps1";

    @Override
    public void launch(SlaveComputer agentComputer, TaskListener listener) {
        if (!(agentComputer instanceof AzureVMComputer)) {
            LOGGER.log(Level.INFO, "AgentComputer is invalid {0}", agentComputer);
            return;
        }
        AzureVMComputer computer = (AzureVMComputer) agentComputer;
        AzureVMAgent agent = computer.getNode();
        if (agent == null) {
            LOGGER.log(Level.INFO, "Agent node is null");
            return;
        }
        LOGGER.log(Level.FINE, "launching agent {0}", computer.getName());

        final boolean isUnix = agent.getOsType().equals(OperatingSystemTypes.LINUX);
        // Check if VM is already stopped or stopping or getting deleted ,
        // if yes then there is no point in trying to connect
        // Added this check - since after restarting jenkins controller,
        // jenkins is trying to connect to all the agents although agents are suspended.
        // This still means that a delete agent will eventually get cleaned up.
        try {
            if (!agent.isVMAliveOrHealthy()) {
                LOGGER.log(Level.INFO,
                        "Agent {0} is shut down, deleted, etc. Not attempting to connect", computer.getName());
                return;
            }
        } catch (Exception e1) {
            // ignoring exception purposefully
        }

        // Block cleanup while we attempt to start.
        agent.blockCleanUpAction();

        PrintStream logger = listener.getLogger();
        boolean successful = false;
        Session session = null;

        SlaveComputer slaveComputer = agent.getComputer();
        if (slaveComputer == null) {
            LOGGER.log(Level.SEVERE, "Got null computer.");
            handleLaunchFailure(agent, Constants.AGENT_POST_PROV_NULL_COMPUTER);
            return;
        }

        try {
            session = connectToSsh(agent);
        } catch (UnknownHostException e) {
            LOGGER.log(Level.SEVERE, "Got unknown host exception. Virtual machine might have been deleted already", e);
        } catch (ConnectException e) {
            LOGGER.log(Level.SEVERE, "Got connect exception while launching " + agent.getNodeName() + ". Might be due to firewall rules", e);
            handleLaunchFailure(agent, Constants.AGENT_POST_PROV_CONN_FAIL);
        } catch (Exception e) {
            // Checking if we need to mark template as disabled. Need to re-visit this logic based on tests.
            if (e.getMessage() != null && e.getMessage().equalsIgnoreCase("Auth fail")) {
                LOGGER.log(Level.SEVERE, "Authentication failure launching " + agent.getNodeName() + ". Image may not be supporting password authentication", e);
                handleLaunchFailure(agent, Constants.AGENT_POST_PROV_AUTH_FAIL);
            } else {
                LOGGER.log(Level.SEVERE,"Exception launching" + agent.getNodeName(), e);
                handleLaunchFailure(agent, Constants.AGENT_POST_PROV_CONN_FAIL + e.getMessage());
            }
        } finally {
            if (session == null) {
                slaveComputer.setAcceptingTasks(false);
                agent.setCleanUpAction(CleanUpAction.DELETE, Messages._Agent_Failed_To_Connect());
                return;
            }
        }

        Localizable cleanUpReason = null;

        try {
            final Session cleanupSession = session;
            String initScript = agent.getInitScript();

            // Executing script only if script is not executed even once
            String command;
            if (isUnix) {
                command = "test -e ~/.azure-agent-init";
            } else {
                command = "dir C:\\.azure-agent-init";
            }
            if (StringUtils.isNotBlank(initScript)
                    && executeRemoteCommand(session, command, logger, isUnix) != 0) {
                LOGGER.fine("Init script is not null, "
                        + "preparing to execute script remotely on " + agent.getNodeName());
                if (isUnix) {
                    copyFileToRemote(
                            session,
                            new ByteArrayInputStream(initScript.getBytes(StandardCharsets.UTF_8)),
                            REMOTE_INIT_FILE_NAME);
                } else {
                    copyFileToRemote(session,
                            new ByteArrayInputStream(initScript.getBytes(StandardCharsets.UTF_8)),
                            REMOTE_INIT_FILE_NAME_WINDOWS);
                }
                // Execute initialization script
                // Make sure to change file permission for execute if needed. TODO: need to test

                if (isUnix) {
                    command = "sh " + REMOTE_INIT_FILE_NAME;
                } else {
                    command = "powershell " + REMOTE_INIT_FILE_NAME_WINDOWS;
                }
                int exitStatus = executeRemoteCommand(
                        session,
                        command,
                        logger,
                        isUnix,
                        agent.getExecuteInitScriptAsRoot()
                );
                if (exitStatus != 0) {
                    if (agent.getDoNotUseMachineIfInitFails()) {
                        LOGGER.log(Level.SEVERE, "Init script failed on " + agent.getNodeName() + ": exit code={0} "
                                        + "(marking agent for deletion)", exitStatus);
                        cleanUpReason = Messages._Agent_Failed_Init_Script();
                        return;
                    } else {
                        LOGGER.log(Level.INFO, "Init script failed on " + agent.getNodeName() + ": exit code={0} (ignoring)",
                                exitStatus);
                    }
                } else {
                    LOGGER.fine("Init script on " + agent.getNodeName() + " got executed successfully");
                }

                //In Windows, restart sshd to get new system environment variables
                if (!isUnix) {
                    executeRemoteCommand(
                            session, "powershell -ExecutionPolicy Bypass Restart-Service sshd", logger, isUnix);
                }

                /* Create a new session after the init script has executed to
                 * make sure we pick up whatever new settings have been set up
                 * for our user
                 *
                 * https://issues.jenkins-ci.org/browse/JENKINS-40291
                 */
                session.disconnect();
                session = connectToSsh(agent);

                // Create tracking file
                if (isUnix) {
                    command = "touch ~/.azure-agent-init";
                } else {
                    command = "copy NUL C:\\.azure-agent-init";
                }
                executeRemoteCommand(session, command, logger, isUnix);
            }

            LOGGER.fine("Checking for java runtime on " + agent.getNodeName());

            if (executeRemoteCommand(session, agent.getJavaPath() + " -fullversion", logger, isUnix) != 0) {
                LOGGER.info("Java not found on " + agent.getNodeName() + ". "
                        + "At a minimum init script should ensure that java runtime is installed");
                handleLaunchFailure(agent, Constants.AGENT_POST_PROV_JAVA_NOT_FOUND);
                return;
            }

            LOGGER.fine("Java runtime present on " + agent.getNodeName() + ", copying remoting.jar to remote");
            InputStream inputStream = new ByteArrayInputStream(Jenkins.get().getJnlpJars("remoting.jar").
                    readFully());
            copyFileToRemote(session, inputStream, "remoting.jar");

            String remotingWorkingDirectory = getRemotingWorkingDirectory(isUnix);
            String remotingDefaultOptions = "-workDir " + remotingWorkingDirectory;

            String remotingOptions = Util.fixEmpty(agent.getRemotingOptions()) != null ? agent.getRemotingOptions() :
                    remotingDefaultOptions;

            String jvmopts = agent.getJvmOptions();
            String execCommand = agent.getJavaPath() + " " + (StringUtils.isNotBlank(jvmopts) ? jvmopts : "")
                    + " -jar remoting.jar "
                    + remotingOptions;
            LOGGER.log(Level.INFO,"Launching agent " + agent.getNodeName() + ": {0}", execCommand);

            final ChannelExec jschChannel = (ChannelExec) session.openChannel("exec");
            jschChannel.setCommand(execCommand);
            jschChannel.connect();
            LOGGER.info("Connected " + agent.getNodeName() + " successfully");

            computer.setChannel(jschChannel.getInputStream(), jschChannel.getOutputStream(), logger, new Listener() {
                @Override
                public void onClosed(Channel channel, IOException cause) {
                    jschChannel.disconnect();
                    cleanupSession.disconnect();
                }
            });

            LOGGER.info("Launched agent " + agent.getNodeName() + " successfully");
            // There's a chance that it was marked as delete for instance, if the node
            // was unreachable and then someone hit connect and it worked.  Reset the node cleanup
            // state to the default for the node.
            agent.clearCleanUpAction();
            successful = true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,"Got exception on agent " + agent.getNodeName(), e);
        } finally {
            if (!successful) {
                session.disconnect();
                if (cleanUpReason == null) {
                    cleanUpReason = Messages._Agent_Failed_To_Connect();
                }
                slaveComputer.setAcceptingTasks(false);
                // Set the machine to be deleted by the cleanup task
                agent.setCleanUpAction(CleanUpAction.DELETE, cleanUpReason);
            }
        }
    }

    private String getRemotingWorkingDirectory(boolean isUnix) {
        if (isUnix) {
            return "~/remoting";
        }
        return "C:\\remoting";
    }

    private Session getRemoteSession(String userName, String passwordOrKey, String passphrase, String dnsName, int sshPort, String sshConfig, boolean passwordAuth) throws JSchException {
        LOGGER.log(Level.INFO,
                "Getting remote session for user {0} to host {1}:{2}",
                new Object[]{userName, dnsName, sshPort});
        JSch remoteClient = new JSch();
        if (StringUtils.isNotBlank(sshConfig)) {
            try {
                ConfigRepository configRepository = OpenSSHConfig.parse(sshConfig);
                remoteClient.setConfigRepository(configRepository);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE,
                        "AzureVMAgentSSHLauncher: getRemoteSession: "
                                + "Got exception while using custom openssh config: {0} {1}",
                        new Object[]{sshConfig, e.getMessage()});
                throw new JSchException("Unable to parse openssh config", e);
            }
        }
        final Session session = remoteClient.getSession(userName, dnsName, sshPort);
        session.setConfig("StrictHostKeyChecking", "no");
        if (passwordAuth) {
            session.setPassword(passwordOrKey);
        } else {
            remoteClient.addIdentity("key", passwordOrKey.getBytes(StandardCharsets.UTF_8),
                    null,
                    passphrase != null ? passphrase.getBytes(StandardCharsets.UTF_8) : null);
        }
        // pinging server for every 1 minutes to keep the connection alive
        final int serverAliveIntervalInMillis = 60 * 1000;
        session.setServerAliveInterval(serverAliveIntervalInMillis);
        session.connect();
        LOGGER.log(Level.INFO,
                "Got remote session for user {0} to host {1}:{2}",
                new Object[]{userName, dnsName, sshPort});
        return session;
    }

    public void copyFileToRemote(AzureVMAgent agent, InputStream stream, String remotePath) throws Exception {
    	copyFileToRemote(connectToSsh(agent), stream, remotePath);
    }

    private void copyFileToRemote(Session jschSession, InputStream stream, String remotePath) throws Exception {
        LOGGER.log(Level.FINE, "Initiating file transfer to {0}", remotePath);
        ChannelSftp sftpChannel = null;

        try {
            sftpChannel = (ChannelSftp) jschSession.openChannel("sftp");
            sftpChannel.connect();
            sftpChannel.put(stream, remotePath);

            if (!sftpChannel.isClosed()) {
                try {
                    Thread.sleep(10 * 1000);
                } catch (InterruptedException e) {
                    //ignore error
                }
            }
            LOGGER.log(Level.FINE, "Copied file Successfully to {0}", remotePath);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error occurred while copying file to remote host", e);
            throw e;
        } finally {
            try {
                if (sftpChannel != null) {
                    sftpChannel.disconnect();
                }
            } catch (Exception e) {
                // ignore silently
            }
        }
    }

    public int executeRemoteCommand(AzureVMAgent agent, String command, PrintStream logger, boolean isUnix)  throws Exception {
    	return executeRemoteCommand(connectToSsh(agent), command, logger, isUnix, false);
    }

    public int executeRemoteCommand(AzureVMAgent agent, String command, PrintStream logger, boolean isUnix, boolean executeAsRoot)  throws Exception {
    	return executeRemoteCommand(connectToSsh(agent), command, logger, isUnix, executeAsRoot);
    }

    /* Helper method for most common call (without root). */
    private int executeRemoteCommand(Session jschSession, String command, PrintStream logger, boolean isUnix) {
        return executeRemoteCommand(jschSession, command, logger, isUnix, false);
    }

    /* Executes a remote command, as root if desired. */
    private int executeRemoteCommand(
            Session jschSession,
            String command,
            PrintStream logger,
            boolean isUnix,
            boolean executeAsRoot) {
        ChannelExec channel = null;
        try {
            // If root, modify the command to set up sudo -S
            String finalCommand;
            if (isUnix && executeAsRoot) {
                finalCommand = "sudo -S -p '' " + command;
            } else {
                finalCommand = command;
            }
            LOGGER.log(Level.INFO, "Starting {0}", command);

            channel = (ChannelExec) jschSession.openChannel("exec");
            channel.setCommand(finalCommand);
            channel.setInputStream(null);
            channel.setErrStream(System.err);
            final InputStream inputStream = channel.getInputStream();
            final InputStream errorStream = channel.getErrStream();
            final int connectTimeoutInMillis = 60 * 1000;
            channel.connect(connectTimeoutInMillis);


            // Read from input stream
            try {
                IOUtils.copy(inputStream, logger);
            } finally {
                IOUtils.closeQuietly(inputStream);
            }

            // Read from error stream
            try {
                IOUtils.copy(errorStream, logger);
            } finally {
                IOUtils.closeQuietly(errorStream);
            }

            if (!channel.isClosed()) {
                try {
                    Thread.sleep(10 * 1000);
                } catch (InterruptedException e) {
                    //ignore error
                }
            }

            LOGGER.fine("Executed command successfully");
            return channel.getExitStatus();
        } catch (JSchException jse) {
            LOGGER.log(Level.SEVERE, "Exception while executing remote command" + command, jse);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "IO failure running {0}", command);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, String.format("Unexpected exception running %s", command), e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
        // If control reached here then it indicates error
        return -1;
    }

    private Session connectToSsh(AzureVMAgent agent) throws Exception {
        LOGGER.fine("Start connecting to SSH");
        Session session;
        final int maxRetryCount = 36;
        int currRetryCount = 0;

        while (true) {
            currRetryCount++;
            try {
                // Grab the username/pass
                StandardUsernameCredentials creds = AzureUtil.getCredentials(agent.getVMCredentialsId());
                String passwordOrKey;
                String passphrase = null;
                boolean passwordAuth = false;
                if (creds instanceof StandardUsernamePasswordCredentials) {
                    passwordOrKey = ((StandardUsernamePasswordCredentials) creds).getPassword().getPlainText();
                    passwordAuth = true;
                } else {
                    SSHUserPrivateKey sshCreds = (SSHUserPrivateKey) creds;
                    passwordOrKey = sshCreds.getPrivateKeys().get(0);
                    Secret secretPassphrase = sshCreds.getPassphrase();
                    passphrase = secretPassphrase != null ? secretPassphrase.getPlainText() : null;
                }

                session = getRemoteSession(
                        creds.getUsername(),
                        passwordOrKey,
                        passphrase,
                        agent.getPublicDNSName(),
                        agent.getSshPort(),
                        agent.getSshConfig(),
                        passwordAuth);
                LOGGER.fine("Got remote connection");
            } catch (Exception e) {
                // Retry till max count and throw exception if not successful even after that
                if (currRetryCount >= maxRetryCount) {
                    throw e;
                }
                // keep retrying till time out
                int backoffTime = 10;
                LOGGER.log(Level.INFO, String.format("Failed connecting to host %s:%s. Will be trying again after %s seconds, error was: %s ", agent.getPublicDNSName(), agent.getSshPort(), backoffTime, e.getMessage()));
                LOGGER.log(Level.FINE, String.format("Failed connecting to host %s:%s.", agent.getPublicDNSName(), agent.getSshPort()), e);
                final long sleepInMills = TimeUnit.SECONDS.toMillis(backoffTime);
                Thread.sleep(sleepInMills);
                // continue again
                continue;
            }
            return session;
        }
    }

    /* Mark the agent for deletion and queue the corresponding template for verification. */
    private void handleLaunchFailure(AzureVMAgent agent, String message) {
        // Queue the template for verification in case something happened there.
        AzureVMCloud azureCloud = agent.getCloud();
        if (azureCloud != null) {
            AzureVMAgentTemplate agentTemplate = azureCloud.getAzureAgentTemplate(agent.getTemplateName());
            if (agentTemplate != null) {
                agentTemplate.handleTemplateProvisioningFailure(message, FailureStage.POSTPROVISIONING);
            }
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
