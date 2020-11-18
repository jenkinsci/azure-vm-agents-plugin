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

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.jcraft.jsch.*;
import com.microsoft.azure.management.compute.OperatingSystemTypes;
import com.microsoft.azure.vmagent.AzureVMAgent;
import com.microsoft.azure.vmagent.AzureVMAgentPlugin;
import com.microsoft.azure.vmagent.AzureVMAgentTemplate;
import com.microsoft.azure.vmagent.AzureVMCloud;
import com.microsoft.azure.vmagent.AzureVMComputer;
import com.microsoft.azure.vmagent.Messages;
import com.microsoft.azure.vmagent.util.AzureUtil;
import com.microsoft.azure.vmagent.util.CleanUpAction;
import com.microsoft.azure.vmagent.util.Constants;
import com.microsoft.azure.vmagent.util.FailureStage;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jvnet.localizer.Localizable;

import java.io.*;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
        if (agentComputer == null || !(agentComputer instanceof AzureVMComputer)) {
            LOGGER.log(Level.INFO,
                    "AzureVMAgentSSHLauncher: launch: AgentComputer is invalid {0}",
                    agentComputer);
            return;
        }
        AzureVMComputer computer = (AzureVMComputer) agentComputer;
        AzureVMAgent agent = computer.getNode();
        if (agent == null) {
            LOGGER.log(Level.INFO, "AzureVMAgentSSHLauncher: launch: Agent Node is null");
            return;
        }
        LOGGER.log(Level.INFO,
                "AzureVMAgentSSHLauncher: launch: launch method called for agent {0}",
                computer.getName());

        final boolean isUnix = agent.getOsType().equals(OperatingSystemTypes.LINUX);
        // Check if VM is already stopped or stopping or getting deleted ,
        // if yes then there is no point in trying to connect
        // Added this check - since after restarting jenkins controller,
        // jenkins is trying to connect to all the agents although agents are suspended.
        // This still means that a delete agent will eventually get cleaned up.
        try {
            if (!agent.isVMAliveOrHealthy()) {
                LOGGER.log(Level.INFO,
                        "AzureVMAgentSSHLauncher: launch: Agent {0} is shut down, deleted, etc. "
                                + "Not attempting to connect",
                        computer.getName());
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
            LOGGER.log(Level.SEVERE, "AzureVMAgentSSHLauncher: launch: Got null computer.");
            handleLaunchFailure(agent, Constants.AGENT_POST_PROV_NULL_COMPUTER);
            return;
        }

        try {
            session = connectToSsh(agent);
        } catch (UnknownHostException e) {
            LOGGER.log(Level.SEVERE, "AzureVMAgentSSHLauncher: launch: "
                    + "Got unknown host exception. Virtual machine might have been deleted already", e);
        } catch (ConnectException e) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMAgentSSHLauncher: launch: Got connect exception. Might be due to firewall rules", e);
            handleLaunchFailure(agent, Constants.AGENT_POST_PROV_CONN_FAIL);
        } catch (Exception e) {
            // Checking if we need to mark template as disabled. Need to re-visit this logic based on tests.
            if (e.getMessage() != null && e.getMessage().equalsIgnoreCase("Auth fail")) {
                LOGGER.log(Level.SEVERE,
                        "AzureVMAgentSSHLauncher: launch: "
                                + "Authentication failure. Image may not be supporting password authentication", e);
                handleLaunchFailure(agent, Constants.AGENT_POST_PROV_AUTH_FAIL);
            } else {
                LOGGER.log(Level.SEVERE, "AzureVMAgentSSHLauncher: launch: Got  exception", e);
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
                LOGGER.info("AzureVMAgentSSHLauncher: launch: Init script is not null, "
                        + "preparing to execute script remotely");
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

                // Grab the username/pass
                StandardUsernamePasswordCredentials creds = AzureUtil.getCredentials(agent.getVMCredentialsId());

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
                        agent.getExecuteInitScriptAsRoot(),
                        creds.getPassword().getPlainText());
                if (exitStatus != 0) {
                    if (agent.getDoNotUseMachineIfInitFails()) {
                        LOGGER.log(Level.SEVERE,
                                "AzureVMAgentSSHLauncher: launch: init script failed: exit code={0} "
                                        + "(marking agent for deletion)", exitStatus);
                        cleanUpReason = Messages._Agent_Failed_Init_Script();
                        return;
                    } else {
                        LOGGER.log(Level.INFO,
                                "AzureVMAgentSSHLauncher: launch: init script failed: exit code={0} (ignoring)",
                                exitStatus);
                    }
                } else {
                    LOGGER.info("AzureVMAgentSSHLauncher: launch: init script got executed successfully");
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

            LOGGER.info("AzureVMAgentSSHLauncher: launch: checking for java runtime");

            if (executeRemoteCommand(session, agent.getJavaPath() + " -fullversion", logger, isUnix) != 0) {
                LOGGER.info("AzureVMAgentSSHLauncher: launch: Java not found. "
                        + "At a minimum init script should ensure that java runtime is installed");
                handleLaunchFailure(agent, Constants.AGENT_POST_PROV_JAVA_NOT_FOUND);
                return;
            }

            LOGGER.info("AzureVMAgentSSHLauncher: launch: java runtime present, copying remoting.jar to remote");
            InputStream inputStream = new ByteArrayInputStream(Jenkins.getInstance().getJnlpJars("remoting.jar").
                    readFully());
            copyFileToRemote(session, inputStream, "remoting.jar");

            String jvmopts = agent.getJvmOptions();
            String execCommand = agent.getJavaPath() + " " + (StringUtils.isNotBlank(jvmopts) ? jvmopts : "") + " -jar remoting.jar";
            LOGGER.log(Level.INFO, "AzureVMAgentSSHLauncher: launch: launching agent: {0}", execCommand);

            final ChannelExec jschChannel = (ChannelExec) session.openChannel("exec");
            jschChannel.setCommand(execCommand);
            jschChannel.connect();
            LOGGER.info("AzureVMAgentSSHLauncher: launch: Connected successfully");

            computer.setChannel(jschChannel.getInputStream(), jschChannel.getOutputStream(), logger, new Listener() {

                @Override
                public void onClosed(Channel channel, IOException cause) {
                    if (jschChannel != null) {
                        jschChannel.disconnect();
                    }

                    if (cleanupSession != null) {
                        cleanupSession.disconnect();
                    }
                }
            });

            LOGGER.info("AzureVMAgentSSHLauncher: launch: launched agent successfully");
            // There's a chance that it was marked as delete (for instance, if the node
            // was unreachable and then someone hit connect and it worked.  Reset the node cleanup
            // state to the default for the node.
            agent.clearCleanUpAction();
            successful = true;

            // send AI event
            final Map<String, String> properties = new HashMap<>();
            properties.put("OSType", agent.getOsType().toString());
            AzureVMAgentPlugin.sendEvent(Constants.AI_VM_AGENT, "SSHLaunch", properties);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "AzureVMAgentSSHLauncher: launch: got exception ", e);

            final Map<String, String> properties = new HashMap<>();
            properties.put("OSType", agent.getOsType().toString());
            properties.put("Message", e.getMessage());
            AzureVMAgentPlugin.sendEvent(Constants.AI_VM_AGENT, "SSHLaunchFailed", properties);
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

    private Session getRemoteSession(String userName, String password, String dnsName, int sshPort) throws JSchException {
        LOGGER.log(Level.INFO,
                "AzureVMAgentSSHLauncher: getRemoteSession: getting remote session for user {0} to host {1}:{2}",
                new Object[]{userName, dnsName, sshPort});
        JSch remoteClient = new JSch();
        try {
            final Session session = remoteClient.getSession(userName, dnsName, sshPort);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setPassword(password);
            // pinging server for every 1 minutes to keep the connection alive
            final int serverAliveIntervalInMillis = 60 * 1000;
            session.setServerAliveInterval(serverAliveIntervalInMillis);
            session.connect();
            LOGGER.log(Level.INFO,
                    "AzureVMAgentSSHLauncher: getRemoteSession: Got remote session for user {0} to host {1}:{2}",
                    new Object[]{userName, dnsName, sshPort});
            return session;
        } catch (JSchException e) {
            LOGGER.log(Level.SEVERE,
                    String.format("AzureVMAgentSSHLauncher: getRemoteSession: "
                                    + "Got exception while connecting to remote host %s:%s",
                            dnsName, sshPort), e);
            throw e;
        }
    }
    
    public void copyFileToRemote(AzureVMAgent agent, InputStream stream, String remotePath) throws Exception {
    	copyFileToRemote(connectToSsh(agent), stream, remotePath);
    }

    private void copyFileToRemote(Session jschSession, InputStream stream, String remotePath) throws Exception {
        LOGGER.log(Level.INFO,
                "AzureVMAgentSSHLauncher: copyFileToRemote: Initiating file transfer to {0}",
                remotePath);
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
            LOGGER.log(Level.INFO,
                    "AzureVMAgentSSHLauncher: copyFileToRemote: copied file Successfully to {0}",
                    remotePath);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMAgentSSHLauncher: copyFileToRemote: "
                            + "Error occurred while copying file to remote host",
                    e);
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
    	return executeRemoteCommand(connectToSsh(agent), command, logger, isUnix, false, null);
    }
    
    public int executeRemoteCommand(AzureVMAgent agent, String command, PrintStream logger, boolean isUnix, boolean executeAsRoot, String passwordIfRoot)  throws Exception {
    	return executeRemoteCommand(connectToSsh(agent), command, logger, isUnix, executeAsRoot, passwordIfRoot);
    }
    
    /* Helper method for most common call (without root). */
    private int executeRemoteCommand(Session jschSession, String command, PrintStream logger, boolean isUnix) {
        return executeRemoteCommand(jschSession, command, logger, isUnix, false, null);
    }

    /* Executes a remote command, as root if desired. */
    private int executeRemoteCommand(
            Session jschSession,
            String command,
            PrintStream logger,
            boolean isUnix,
            boolean executeAsRoot,
            String passwordIfRoot) {
        ChannelExec channel = null;
        try {
            // If root, modify the command to set up sudo -S
            String finalCommand = null;
            if (isUnix && executeAsRoot) {
                finalCommand = "sudo -S -p '' " + command;
            } else {
                finalCommand = command;
            }
            LOGGER.log(Level.INFO, "AzureVMAgentSSHLauncher: executeRemoteCommand: starting {0}", command);

            channel = (ChannelExec) jschSession.openChannel("exec");
            channel.setCommand(finalCommand);
            channel.setInputStream(null);
            channel.setErrStream(System.err);
            final InputStream inputStream = channel.getInputStream();
            final InputStream errorStream = channel.getErrStream();
            final OutputStream outputStream = channel.getOutputStream();
            final int connectTimeoutInMillis = 60 * 1000;
            channel.connect(connectTimeoutInMillis);

            // If as root, push the password
            if (isUnix && executeAsRoot) {
                outputStream.write((passwordIfRoot + "\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }

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

            LOGGER.info("AzureVMAgentSSHLauncher: executeRemoteCommand: executed successfully");
            return channel.getExitStatus();
        } catch (JSchException jse) {
            LOGGER.log(Level.SEVERE,
                    "AzureVMAgentSSHLauncher: executeRemoteCommand: "
                            + "got exception while executing remote command\n" + command,
                    jse);
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
        LOGGER.info("AzureVMAgentSSHLauncher: connectToSsh: start");
        Session session = null;
        final int maxRetryCount = 6;
        int currRetryCount = 0;

        while (true) {
            currRetryCount++;
            try {
                // Grab the username/pass
                StandardUsernamePasswordCredentials creds = AzureUtil.getCredentials(agent.getVMCredentialsId());

                session = getRemoteSession(
                        creds.getUsername(),
                        creds.getPassword().getPlainText(),
                        agent.getPublicDNSName(),
                        agent.getSshPort());
                LOGGER.info("AzureVMAgentSSHLauncher: connectToSsh: Got remote connection");
            } catch (Exception e) {
                // Retry till max count and throw exception if not successful even after that
                if (currRetryCount >= maxRetryCount) {
                    throw e;
                }
                // keep retrying till time out
                LOGGER.log(Level.SEVERE,
                        "AzureVMAgentSSHLauncher: connectToSsh: Got exception while connecting to remote host. "
                                + "Will be trying again after 1 minute ", e);
                final int sleepInMills = 60 * 1000;
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
