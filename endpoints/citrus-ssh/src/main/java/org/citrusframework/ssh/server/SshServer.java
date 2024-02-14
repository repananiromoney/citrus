/*
 * Copyright 2006-2012 the original author or authors.
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
 */

package org.citrusframework.ssh.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.ClassLoadableResourceKeyPairProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.scp.common.AbstractScpTransferEventListenerAdapter;
import org.apache.sshd.scp.common.ScpTransferEventListener;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.apache.sshd.sftp.server.AbstractSftpEventListenerAdapter;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.citrusframework.endpoint.AbstractPollableEndpointConfiguration;
import org.citrusframework.exceptions.CitrusRuntimeException;
import org.citrusframework.server.AbstractServer;
import org.citrusframework.spi.Resource;
import org.citrusframework.spi.Resources;
import org.citrusframework.ssh.SshCommand;
import org.citrusframework.ssh.client.SshEndpointConfiguration;
import org.citrusframework.ssh.message.SshMessageConverter;
import org.citrusframework.ssh.model.SshMarshaller;
import org.citrusframework.util.FileUtils;
import org.citrusframework.util.StringUtils;

/**
 * SSH Server implemented with Apache SSHD (http://mina.apache.org/sshd/).
 *
 * It uses the same semantic as the Jetty Servers for HTTP and WS mocks and translates
 * an incoming request into a message, for which a reply message gets translates to
 * the SSH return value.
 *
 * The incoming message generated has the following format:
 *
 * <ssh-request>
 *   <command>cat -</command>
 *   <stdin>This is the standard input sent</stdin>
 * </ssh-request>
 *
 * The reply message to be generated by a handler should have the following format
 *
 * <ssh-response>
 *   <exit>0</exit>
 *   <stdout>This is the standard input sent</stdout>
 *   <stderr>warning: no tty</stderr>
 * </ssh-response>
 *
 * @author Roland Huss
 * @since 04.09.12
 */
public class SshServer extends AbstractServer {

    /** Port to listen to **/
    private int port = 22;

    /** User allowed to connect **/
    private String user;

    /** User's password or ... **/
    private String password;

    /** ... path to its public key
      Use this to convert to PEM: ssh-keygen -f key.pub -e -m pem **/
    private String allowedKeyPath;

    /* Path to our own host keys. If not provided, a default is used. The format of this
       file should be PEM, a serialized {@link java.security.KeyPair}. **/
    private String hostKeyPath;

    /** User home directory path  **/
    private String userHomePath;

    /** Ssh message converter **/
    private SshMessageConverter messageConverter = new SshMessageConverter();

    /** SSH server used **/
    private org.apache.sshd.server.SshServer sshd;

    /**  This servers endpoint configuration */
    private final SshEndpointConfiguration endpointConfiguration;

    /**
     * Default constructor using default endpoint configuration.
     */
    public SshServer() {
        this(new SshEndpointConfiguration());
    }

    /**
     * Constructor using endpoint configuration.
     * @param endpointConfiguration
     */
    public SshServer(SshEndpointConfiguration endpointConfiguration) {
        this.endpointConfiguration = endpointConfiguration;
    }

    @Override
    protected void startup() {
        if (!StringUtils.hasText(user)) {
            throw new CitrusRuntimeException("No 'user' provided (mandatory for authentication)");
        }
        sshd = org.apache.sshd.server.SshServer.setUpDefaultServer();
        sshd.setPort(port);

        VirtualFileSystemFactory fileSystemFactory = new VirtualFileSystemFactory();
        Path userHomeDir = Optional.ofNullable(userHomePath).map(Paths::get).map(Path::toAbsolutePath).orElseGet(() -> Paths.get(String.format("target/%s/home/%s", getName(), user)).toAbsolutePath());

        if (!Files.exists(userHomeDir)) {
            try {
                Files.createDirectories(userHomeDir);
            } catch (IOException e) {
                throw new CitrusRuntimeException("Failed to setup user home dir", e);
            }
        }

        fileSystemFactory.setUserHomeDir(user, userHomeDir);
        sshd.setFileSystemFactory(fileSystemFactory);

        if (hostKeyPath != null) {
            Resource hostKey = FileUtils.getFileResource(hostKeyPath);

            if (hostKey instanceof Resources.ClasspathResource) {
                ClassLoadableResourceKeyPairProvider resourceKeyPairProvider = new ClassLoadableResourceKeyPairProvider(Collections.singletonList(hostKey.getLocation()));
                sshd.setKeyPairProvider(resourceKeyPairProvider);
            } else {
                FileKeyPairProvider fileKeyPairProvider = new FileKeyPairProvider(Collections.singletonList(hostKey.getFile().toPath()));
                sshd.setKeyPairProvider(fileKeyPairProvider);
            }
        } else {
            ClassLoadableResourceKeyPairProvider resourceKeyPairProvider = new ClassLoadableResourceKeyPairProvider(Collections.singletonList("org/citrusframework/ssh/citrus.pem"));
            sshd.setKeyPairProvider(resourceKeyPairProvider);
        }

        List<String> availableSignatureFactories = sshd.getSignatureFactoriesNames();
        availableSignatureFactories.add(KeyPairProvider.SSH_DSS);
        availableSignatureFactories.add(KeyPairProvider.SSH_RSA);
        availableSignatureFactories.add(KeyUtils.RSA_SHA256_KEY_TYPE_ALIAS);
        sshd.setSignatureFactoriesNames(availableSignatureFactories);

        // Authentication
        boolean authFound = false;
        if (password != null) {
            sshd.setPasswordAuthenticator(new SimplePasswordAuthenticator(user, password));
            authFound = true;
        }

        if (allowedKeyPath != null) {
            sshd.setPublickeyAuthenticator(new SinglePublicKeyAuthenticator(user, allowedKeyPath));
            authFound = true;
        }

        if (!authFound) {
            throw new CitrusRuntimeException("Neither 'password' nor 'allowed-key-path' is set. Please provide at least one");
        }

        // Setup endpoint adapter
        ScpCommandFactory commandFactory = new ScpCommandFactory.Builder()
                .withDelegate((session, command) -> new SshCommand(command, getEndpointAdapter(), endpointConfiguration))
                .build();

        commandFactory.addEventListener(getScpTransferEventListener());
        sshd.setCommandFactory(commandFactory);

        List<SubsystemFactory> subsystemFactories = new ArrayList<>();
        SftpSubsystemFactory sftpSubsystemFactory = new SftpSubsystemFactory.Builder().build();
        sftpSubsystemFactory.addSftpEventListener(getSftpEventListener());

        subsystemFactories.add(sftpSubsystemFactory);
        sshd.setSubsystemFactories(subsystemFactories);

        try {
            sshd.start();
        } catch (IOException e) {
            throw new CitrusRuntimeException("Failed to start SSH server - " + e.getMessage(), e);
        }
    }

    /**
     * Gets Scp trsanfer event listener. By default uses abstract implementation that use trace level logging of all operations.
     * @return
     */
    protected ScpTransferEventListener getScpTransferEventListener() {
        return new AbstractScpTransferEventListenerAdapter() {};
    }

    /**
     * Gets Sftp event listener. By default uses abstract implementation that use trace level logging of all operations.
     * @return
     */
    protected SftpEventListener getSftpEventListener() {
        return new AbstractSftpEventListenerAdapter(){};
    }

    @Override
    protected void shutdown() {
        try {
            sshd.stop();
        } catch (IOException e) {
            throw new CitrusRuntimeException("Failed to stop SSH server - " + e.getMessage(), e);
        }
    }

    @Override
    public AbstractPollableEndpointConfiguration getEndpointConfiguration() {
        return endpointConfiguration;
    }

    /**
     * Gets the server port.
     * @return
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the port.
     * @param port the port to set
     */
    public void setPort(int port) {
        this.port = port;
        this.endpointConfiguration.setPort(port);
    }

    /**
     * Gets the username.
     * @return
     */
    public String getUser() {
        return user;
    }

    /**
     * Sets the user.
     * @param user the user to set
     */
    public void setUser(String user) {
        this.user = user;
        this.endpointConfiguration.setUser(user);
    }

    /**
     * Gets the user password.
     * @return
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the password.
     * @param password the password to set
     */
    public void setPassword(String password) {
        this.password = password;
        this.endpointConfiguration.setPassword(password);
    }

    /**
     * Gets the allowed key path.
     * @return
     */
    public String getAllowedKeyPath() {
        return allowedKeyPath;
    }

    /**
     * Sets the allowedKeyPath.
     * @param allowedKeyPath the allowedKeyPath to set
     */
    public void setAllowedKeyPath(String allowedKeyPath) {
        this.allowedKeyPath = allowedKeyPath;
    }

    /**
     * Gets the host key path.
     * @return
     */
    public String getHostKeyPath() {
        return hostKeyPath;
    }

    /**
     * Sets the hostKeyPath.
     * @param hostKeyPath the hostKeyPath to set
     */
    public void setHostKeyPath(String hostKeyPath) {
        this.hostKeyPath = hostKeyPath;
    }

    /**
     * Gets the userHomePath.
     *
     * @return
     */
    public String getUserHomePath() {
        return userHomePath;
    }

    /**
     * Sets the userHomePath.
     *
     * @param userHomePath
     */
    public void setUserHomePath(String userHomePath) {
        this.userHomePath = userHomePath;
    }

    /**
     * Gets the message converter.
     * @return
     */
    public SshMessageConverter getMessageConverter() {
        return messageConverter;
    }

    /**
     * Sets the marshaller.
     * @param marshaller
     */
    public void setMarshaller(SshMarshaller marshaller) {
        this.endpointConfiguration.setSshMarshaller(marshaller);
    }

    /**
     * Sets the message converter.
     * @param messageConverter
     */
    public void setMessageConverter(SshMessageConverter messageConverter) {
        this.messageConverter = messageConverter;
        this.endpointConfiguration.setMessageConverter(messageConverter);
    }

}
