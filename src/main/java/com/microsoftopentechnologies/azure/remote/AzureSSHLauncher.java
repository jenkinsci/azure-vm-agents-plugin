/*
 Copyright 2014 Microsoft Open Technologies, Inc.
 
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
package com.microsoftopentechnologies.azure.remote;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.microsoftopentechnologies.azure.AzureSlave;
import com.microsoftopentechnologies.azure.AzureComputer;
import com.microsoftopentechnologies.azure.Messages;
import com.microsoftopentechnologies.azure.util.AzureUtil;
import com.microsoftopentechnologies.azure.util.Constants;

import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;

/**
 * SSH Launcher class
 * @author Suresh nallamilli (snallami@gmail.com)
 *
 */
public class AzureSSHLauncher extends ComputerLauncher {
    public static final Logger LOGGER = Logger.getLogger(AzureSSHLauncher.class.getName());
    private static final String remoteInitFileName = "init.sh";
    
    public void launch(SlaveComputer slaveComputer, TaskListener listener) {
		LOGGER.info("AzureSSHLauncher: launch: launch method called for slave ");
		AzureComputer computer = (AzureComputer)slaveComputer;
		AzureSlave slave = computer.getNode();
		
		//check if VM is already stopped or stopping or getting deleted , if yes then there is no point in trying to connect
		//Added this check - since after restarting jenkins master, jenkins is trying to connect to all the slaves although slaves are suspended.
		try {
			if (!slave.isVMAliveOrHealthy()) {
				return;
			}
		} catch (Exception e1) {
			// ignoring exception purposefully
			e1.printStackTrace();
		}
		
		PrintStream logger = listener.getLogger();
		boolean successful = false;
		Session session = null;
		
		try {
			session = connectToSsh(slave, logger);
		} catch (Exception e) {
			LOGGER.info("AzureSSHLauncher: launch: Got exception while connecting to slave " +e.getMessage());
			LOGGER.info("AzureSSHLauncher: launch: marking slave for delete ");
			slave.setDeleteSlave(true);
			
			// Checking if we need to mark template as disabled. Need to re-visit this logic based on tests.
			if (e instanceof ConnectException) {
    			LOGGER.severe("AzureSSHLauncher: launch: Got connect exception. Might be due to firewall rules");
    			markSlaveForDeletion(slave, Constants.SLAVE_POST_PROV_CONN_FAIL);
    		} else if (e instanceof UnknownHostException) {
    			LOGGER.severe("AzureSSHLauncher: launch: Got unknown host exception. Virtual machine might have been deleted already");
    		} else if (e.getMessage() != null && e.getMessage().equalsIgnoreCase("Auth fail")) {
    			LOGGER.severe("AzureSSHLauncher: launch: Authentication failure. Image may not be supporting password authentication");
    			markSlaveForDeletion(slave, Constants.SLAVE_POST_PROV_AUTH_FAIL);
    		} else {
    			LOGGER.severe("AzureSSHLauncher: launch: Got  exception. "+e.getMessage());
    			markSlaveForDeletion(slave, Constants.SLAVE_POST_PROV_CONN_FAIL+e.getMessage());
    		}
			return;
		}
	
		try {
			final Session cleanupSession = session;
			String initScript = slave.getInitScript();

			// Executing script only if script is not executed even once
			if (initScript != null && initScript.trim().length() > 0 && executeRemoteCommand(session, "test -e ~/.azure-slave-init", logger) != 0 ) {
		        LOGGER.info("AzureSSHLauncher: launch: Init script is not null, preparing to execute script remotely");
		        copyFileToRemote(session, new ByteArrayInputStream(initScript.getBytes("UTF-8")), remoteInitFileName);
		
		        // Execute initialization script
		        // Make sure to change file permission for execute if needed. TODO: need to test
		        int exitStatus = executeRemoteCommand(session, "sh "+remoteInitFileName, logger);
		        if (exitStatus != 0 ) {
		            LOGGER.severe("AzureSSHLauncher: launch: init script failed: exit code="+exitStatus);
		            //TODO: Do we need to expose flag and act accordingly?? For now ignoring init script failures
		        } else {
		        	LOGGER.info("AzureSSHLauncher: launch: init script got executed successfully");
		        }
		        // Create tracking file
		        executeRemoteCommand(session, "touch ~/.azure-slave-init", logger);
			}
	     
			LOGGER.info("AzureSSHLauncher: launch: checking for java runtime");
	        
			if(executeRemoteCommand(session, "java -fullversion", logger) !=0)  {
				LOGGER.info("AzureSSHLauncher: launch: Java not found. At a minimum init script should ensure that java runtime is installed");
				markSlaveForDeletion(slave, Constants.SLAVE_POST_PROV_JAVA_NOT_FOUND);
				return;
			}
			
			LOGGER.info("AzureSSHLauncher: launch: java runtime present, copying slaves.jar to remote");
			InputStream inputStream = new ByteArrayInputStream(Hudson.getInstance().getJnlpJars("slave.jar").readFully());
			copyFileToRemote(session, inputStream, "slave.jar");
	 
			String jvmopts = slave.getJvmOptions();
			String execCommand = "java " + (AzureUtil.isNotNull(jvmopts) ? jvmopts : "") + " -jar slave.jar";
	     	LOGGER.info("AzureSSHLauncher: launch: launching slave agent: " + execCommand);
	     	
	     	final ChannelExec jschChannel= (ChannelExec)session.openChannel("exec");
          	jschChannel.setCommand(execCommand);
          	jschChannel.connect();
          	LOGGER.info("AzureSSHLauncher: launch: Connected successfully" );
          	
          	computer.setChannel(jschChannel.getInputStream(), jschChannel.getOutputStream(),logger,new Listener() {
    			public void onClosed(Channel channel, IOException cause) {
    					if (jschChannel != null)
    						jschChannel.disconnect();
    					
    					if (cleanupSession != null)
    						cleanupSession.disconnect();
                     }
             });
          	
          	LOGGER.info("AzureSSHLauncher: launch: launched slave successfully" );
          	successful = true;
	} catch (Exception e) {
		LOGGER.info("AzureSSHLauncher: launch: got exception "+e );
		LOGGER.info("AzureSSHLauncher: launch: Exception message"+e.getMessage());
		e.printStackTrace();
	} finally {
	    if(!successful) {
	    	if (session != null)
	    		session.disconnect();
	    }
	}
}
		
	private Session getRemoteSession(String userName, String password, String dnsName, int sshPort) throws Exception {
    	LOGGER.info("AzureSSHLauncher: getRemoteSession: getting remote session for user "+userName +" to host "+dnsName+":"+sshPort);
		JSch remoteClient = new JSch();
		Session session = null;
		try {
			session = remoteClient.getSession(userName, dnsName, sshPort);
			session.setConfig("StrictHostKeyChecking", "no");
	    	session.setPassword(password);
	    	// pinging server for every 1 minutes to keep the connection alive
		    session.setServerAliveInterval(60 * 1000);
		    session.connect();
		    LOGGER.info("AzureSSHLauncher: getRemoteSession: Got remote session for user "+userName +" to host "+dnsName+":"+sshPort);
		    return session;
		} catch (JSchException e) {
			LOGGER.severe("AzureSSHLauncher: getRemoteSession: Got exception while connecting to remote host "+
			dnsName+":"+sshPort + " "+e.getMessage());
			throw e;
		}
	}
	 
	private void copyFileToRemote(Session jschSession, InputStream stream, String remotePath) throws Exception {
		LOGGER.info("AzureSSHLauncher: copyFileToRemote: Initiating file transfer to "+remotePath);
		ChannelSftp sftpChannel = null;
		   	    	
		try {
			sftpChannel = (ChannelSftp)jschSession.openChannel("sftp");
			sftpChannel.connect();
			sftpChannel.put(stream, remotePath);
			
			if (!sftpChannel.isClosed()) {
	    		try {
	    			LOGGER.warning("AzureSSHLauncher: copyFileToRemote: Channel is not yet closed , waiting for 10 seconds");
					Thread.sleep(10 * 1000);
				 } catch (InterruptedException e) {
					 //ignore error
				 }
	    	}
			LOGGER.info("AzureSSHLauncher: copyFileToRemote: copied file Successfully to "+remotePath);
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.severe("AzureSSHLauncher: copyFileToRemote: Error occurred while copying file to remote host "+e.getMessage());
			throw e;
		} finally {
			 try {
				 if (sftpChannel != null )
					 sftpChannel.disconnect();
			 } catch (Exception e) {
				 // ignore silently
			 }
		}
    }
	 
	private int executeRemoteCommand(Session jschSession, String command, PrintStream logger) {
		ChannelExec channel = null;
		LOGGER.info("AzureSSHLauncher: executeRemoteCommand: start");
		try {
			channel = createExecChannel(jschSession, command);
	    	final InputStream inputStream = channel.getInputStream();
	    	final InputStream errorStream = ((ChannelExec)channel).getErrStream();
	    	
	    	// Read from input stream
	    	try {
	    		IOUtils.copy(inputStream, logger);
	    	} finally {
	    		inputStream.close();
	    	}
	    		
	    	// Read from error stream	
	    	try {
	    		IOUtils.copy(errorStream, logger);
	    	} finally {
	    		errorStream.close();
	    	}
	    	
	    		
	    	if (!channel.isClosed()) {
	    		try {
	    			LOGGER.warning("AzureSSHLauncher: executeRemoteCommand: Channel is not yet closed , waiting for 10 seconds");
					Thread.sleep(10 * 1000);
				 } catch (InterruptedException e) {
					 //ignore error
				 }
	    	}

	        return channel.getExitStatus();
        } catch (JSchException jse) {
        	jse.printStackTrace();
        	LOGGER.severe("AzureSSHLauncher: executeRemoteCommand: got exception while executing remote command " + jse);
        } catch (IOException ex) {
        	ex.printStackTrace();
        	LOGGER.warning("IO failure during running " + command);
        } finally {
        	if (channel != null)
        		channel.disconnect();
        }
		// If control reached here then it indicates error
	    return -1;
    }
	
	private ChannelExec createExecChannel(Session jschSession, String command) throws JSchException {
        ChannelExec echannel = (ChannelExec) jschSession.openChannel("exec");
        echannel.setCommand(command);
        echannel.setInputStream(null);
        echannel.setErrStream(System.err);
        echannel.connect();
        return echannel;
    }

    private Session connectToSsh(AzureSlave slave, PrintStream logger) throws Exception {
    	LOGGER.info("AzureSSHLauncher: connectToSsh: start");
    	Session session = null;
    	int maxRetryCount=6;
    	int currRetryCount=0;
    	
        while(true) {
        	currRetryCount++;
            try {
                session = getRemoteSession(slave.getAdminUserName(), slave.getAdminPassword(), slave.getPublicDNSName(), slave.getSshPort());
                LOGGER.info("AzureSSHLauncher: connectToSsh: Got remote connection");
            } catch (Exception e) {
            	// Retry till max count and throw exception if not successful even after that
            	if (currRetryCount >= maxRetryCount) {
            		throw e;
            	}
                // keep retrying till time out
                LOGGER.severe("AzureSSHLauncher: connectToSsh: Got exception while connecting to remote host. Will be trying again after 1 minute "+e.getMessage());
                Thread.sleep(1 * 60* 1000);
                // continue again
                continue;
            }
            return session; 
        }
    }
    
    private static void markSlaveForDeletion(AzureSlave slave, String message) {
		slave.setTemplateStatus(Constants.TEMPLATE_STATUS_DISBALED, message);
		if (slave.toComputer() != null) {
			slave.toComputer().setTemporarilyOffline(true, OfflineCause.create(Messages._Slave_Failed_To_Connect()));
		}
		slave.setDeleteSlave(true);
	}

    @Override
	public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
