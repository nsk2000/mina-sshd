/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.client.session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.ServerException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.apache.sshd.client.ClientAuthenticationManager;
import org.apache.sshd.client.ClientFactoryManager;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.scp.ScpClientCreator;
import org.apache.sshd.client.subsystem.sftp.SftpClientCreator;
import org.apache.sshd.common.future.KeyExchangeFuture;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.io.NoCloseOutputStream;
import org.apache.sshd.common.util.io.NullOutputStream;
import org.apache.sshd.common.util.net.SshdSocketAddress;

/**
 * <P>An authenticated session to a given SSH server</P>
 *
 * <P>
 * A client session is established using the {@link org.apache.sshd.client.SshClient}.
 * Once the session has been created, the user has to authenticate
 * using either {@link #addPasswordIdentity(String)} or
 * {@link #addPublicKeyIdentity(java.security.KeyPair)} followed by
 * a call to {$link #auth()}.
 * </P>
 *
 * <P>
 * From this session, channels can be created using the
 * {@link #createChannel(String)} method.  Multiple channels can
 * be created on a given session concurrently.
 * </P>
 *
 * <P>
 * When using the client in an interactive mode, the
 * {@link #waitFor(Collection, long)} method can be used to listen to specific
 * events such as the session being established, authenticated or closed.
 * </P>
 *
 * When a given session is no longer used, it must be closed using the
 * {@link #close(boolean)} method.
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public interface ClientSession
            extends Session, ScpClientCreator, SftpClientCreator,
            ClientProxyConnectorHolder, ClientAuthenticationManager {
    enum ClientSessionEvent {
        TIMEOUT,
        CLOSED,
        WAIT_AUTH,
        AUTHED;
    }

    Set<ClientChannelEvent> REMOTE_COMMAND_WAIT_EVENTS =
            Collections.unmodifiableSet(EnumSet.of(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS));

    /**
     * Returns the original address (after having been translated through host
     * configuration entries if any) that was request to connect. It contains the
     * original host or address string that was used. <B>Note:</B> this may be
     * different than the result of the {@link #getIoSession()} report of the
     * remote peer
     *
     * @return The original requested address
     */
    SocketAddress getConnectAddress();

    /**
     * Starts the authentication process.
     * User identities will be tried until the server successfully authenticate the user.
     * User identities must be provided before calling this method using
     * {@link #addPasswordIdentity(String)} or {@link #addPublicKeyIdentity(java.security.KeyPair)}.
     *
     * @return the authentication future
     * @throws IOException if failed to generate the future
     * @see #addPasswordIdentity(String)
     * @see #addPublicKeyIdentity(java.security.KeyPair)
     */
    AuthFuture auth() throws IOException;

    /**
     * Create a channel of the given type.
     * Same as calling <code>createChannel(type, null)</code>.
     *
     * @param type The channel type
     * @return The created {@link ClientChannel}
     * @throws IOException If failed to create the requested channel
     */
    ClientChannel createChannel(String type) throws IOException;

    /**
     * Create a channel of the given type and sub-type.
     *
     * @param type      The channel type
     * @param subType   The channel sub-type
     * @return The created {@link ClientChannel}
     * @throws IOException If failed to create the requested channel
     */
    ClientChannel createChannel(String type, String subType) throws IOException;

    /**
     * Create a channel to start a shell.
     *
     * @return The created {@link ChannelShell}
     * @throws IOException If failed to create the requested channel
     */
    ChannelShell createShellChannel() throws IOException;

    /**
     * Create a channel to execute a command.
     *
     * @param command The command to execute
     * @return The created {@link ChannelExec}
     * @throws IOException If failed to create the requested channel
     */
    ChannelExec createExecChannel(String command) throws IOException;

    /**
     * Execute a command that requires no input and returns its output
     *
     * @param command The command to execute
     * @return The command's standard output result (assumed to be in US-ASCII)
     * @throws IOException If failed to execute the command - including
     * if <U>anything</U> was written to the standard error or a non-zero exit
     * status was received. If this happens, then a {@link RemoteException} is
     * thrown with a cause of {@link ServerException} containing the remote
     * captured standard error - including CR/LF(s)
     * @see #executeRemoteCommand(String, OutputStream, Charset)
     */
    default String executeRemoteCommand(String command) throws IOException {
        try (ByteArrayOutputStream stderr = new ByteArrayOutputStream()) {
            String response = executeRemoteCommand(command, stderr, StandardCharsets.US_ASCII);
            if (stderr.size() > 0) {
                byte[] error = stderr.toByteArray();
                String errorMessage = new String(error, StandardCharsets.US_ASCII);
                throw new RemoteException("Error reported from remote command='" + command, new ServerException(errorMessage));
            }

            return response;
        }
    }

    /**
     * Execute a command that requires no input and returns its output
     *
     * @param command The command to execute - without a terminating LF
     * @param stderr Standard error output stream - if {@code null} then
     * error stream data is ignored. <B>Note:</B> if the stream is not {@code null}
     * then it will be left <U>open</U> when this method returns or exception
     * is thrown
     * @param charset The command {@link Charset} for input/output/error - if
     * {@code null} then US_ASCII is assumed
     * @return The command's standard output result
     * @throws IOException If failed to manage the command channel - <B>Note:</B>
     * the code does not check if anything was output to the standard error stream,
     * but does check the reported exit status (if any) for non-zero value. If
     * non-zero exit status received then a {@link RemoteException} is thrown with'
     * a {@link ServerException} cause containing the exits value
     */
    default String executeRemoteCommand(String command, OutputStream stderr, Charset charset) throws IOException {
        if (charset == null) {
            charset = StandardCharsets.US_ASCII;
        }

        try (ByteArrayOutputStream channelOut = new ByteArrayOutputStream(Byte.MAX_VALUE);
             OutputStream channelErr = (stderr == null) ? new NullOutputStream() : new NoCloseOutputStream(stderr);
             ClientChannel channel = createExecChannel(command)) {
            channel.setOut(channelOut);
            channel.setErr(channelErr);
            channel.open().await(); // TODO use verify and a configurable timeout

            OutputStream invertedStream = channel.getInvertedIn();
            invertedStream.write(command.getBytes(charset));
            invertedStream.flush();

            // TODO use a configurable timeout
            Collection<ClientChannelEvent> waitMask = channel.waitFor(REMOTE_COMMAND_WAIT_EVENTS, 0L);
            if (waitMask.contains(ClientChannelEvent.TIMEOUT)) {
                throw new SocketTimeoutException("Failed to retrieve command result in time: " + command);
            }

            Integer exitStatus = channel.getExitStatus();
            if ((exitStatus != null) && (exitStatus.intValue() != 0)) {
                throw new RemoteException("Remote command failed (" + exitStatus + "): " + command, new ServerException(exitStatus.toString()));
            }
            byte[]  response = channelOut.toByteArray();
            return new String(response, charset);
        }
    }

    /**
     * Create a subsystem channel.
     *
     * @param subsystem The subsystem name
     * @return The created {@link ChannelSubsystem}
     * @throws IOException If failed to create the requested channel
     */
    ChannelSubsystem createSubsystemChannel(String subsystem) throws IOException;

    /**
     * Create a direct tcp-ip channel which can be used to stream data to a remote port from the server.
     *
     * @param local  The local address
     * @param remote The remote address
     * @return The created {@link ChannelDirectTcpip}
     * @throws IOException If failed to create the requested channel
     */
    ChannelDirectTcpip createDirectTcpipChannel(SshdSocketAddress local, SshdSocketAddress remote) throws IOException;

    /**
     * Start forwarding the given local address on the client to the given address on the server.
     *
     * @param local  The local address
     * @param remote The remote address
     * @return The bound {@link SshdSocketAddress}
     * @throws IOException If failed to create the requested binding
     */
    SshdSocketAddress startLocalPortForwarding(SshdSocketAddress local, SshdSocketAddress remote) throws IOException;

    /**
     * Stop forwarding the given local address.
     *
     * @param local  The local address
     * @throws IOException If failed to cancel the requested binding
     */
    void stopLocalPortForwarding(SshdSocketAddress local) throws IOException;

    /**
     * <P>
     * Start forwarding tcpip from the given address on the server to the
     * given address on the client.
     * </P>
     * The remote host name is the address to bind to on the server:
     * <ul>
     * <li>"" means that connections are to be accepted on all protocol families
     * supported by the SSH implementation</li>
     * <li>"0.0.0.0" means to listen on all IPv4 addresses</li>
     * <li>"::" means to listen on all IPv6 addresses</li>
     * <li>"localhost" means to listen on all protocol families supported by the SSH
     * implementation on loopback addresses only, [RFC3330] and RFC3513]</li>
     * <li>"127.0.0.1" and "::1" indicate listening on the loopback interfaces for
     * IPv4 and IPv6 respectively</li>
     * </ul>
     *
     * @param local  The local address
     * @param remote The remote address
     * @return The bound {@link SshdSocketAddress}
     * @throws IOException If failed to create the requested binding
     */
    SshdSocketAddress startRemotePortForwarding(SshdSocketAddress remote, SshdSocketAddress local) throws IOException;

    /**
     * Stop forwarding of the given remote address.
     *
     * @param remote The remote address
     * @throws IOException If failed to cancel the requested binding
     */
    void stopRemotePortForwarding(SshdSocketAddress remote) throws IOException;

    /**
     * Start dynamic local port forwarding using a SOCKS proxy.
     *
     * @param local The local address
     * @return The bound {@link SshdSocketAddress}
     * @throws IOException If failed to create the requested binding
     */
    SshdSocketAddress startDynamicPortForwarding(SshdSocketAddress local) throws IOException;

    /**
     * Stop a previously started dynamic port forwarding.
     *
     * @param local The local address
     * @throws IOException If failed to cancel the requested binding
     */
    void stopDynamicPortForwarding(SshdSocketAddress local) throws IOException;

    /**
     * Wait for any one of a specific state to be signaled.
     *
     * @param mask    The request {@link ClientSessionEvent}s mask
     * @param timeout Wait time in milliseconds - non-positive means forever
     * @return The actual state that was detected either due to the mask
     * yielding one of the states or due to timeout (in which case the {@link ClientSessionEvent#TIMEOUT}
     * value is set)
     */
    Set<ClientSessionEvent> waitFor(Collection<ClientSessionEvent> mask, long timeout);

    /**
     * Access to the metadata.
     *
     * @return The metadata {@link Map}
     */
    Map<Object, Object> getMetadataMap();

    /**
     * @return The ClientFactoryManager for this session.
     */
    @Override
    ClientFactoryManager getFactoryManager();

    /**
     * <P>Switch to a none cipher for performance.</P>
     *
     * <P>
     * This should be done after the authentication phase has been performed.
     * After such a switch, interactive channels are not allowed anymore.
     * Both client and server must have been configured to support the none cipher.
     * If that's not the case, the returned future will be set with an exception.
     * </P>
     *
     * @return an {@link KeyExchangeFuture} that can be used to wait for the exchange
     * to be finished
     * @throws IOException if a key exchange is already running
     */
    KeyExchangeFuture switchToNoneCipher() throws IOException;
}
