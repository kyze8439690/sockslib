/*
 * Copyright 2015-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package sockslib.server;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import sockslib.client.SocksProxy;
import sockslib.client.SocksSocket;
import sockslib.common.ProtocolErrorException;
import sockslib.common.SocksException;
import sockslib.common.methods.SocksMethod;
import sockslib.server.io.Pipe;
import sockslib.server.io.SocketPipe;
import sockslib.server.msg.CommandMessage;
import sockslib.server.msg.CommandResponseMessage;
import sockslib.server.msg.MethodSelectionMessage;
import sockslib.server.msg.MethodSelectionResponseMessage;
import sockslib.server.msg.ServerReply;

/**
 * The class <code>Socks5Handler</code> represents a handler that can handle SOCKS5 protocol.
 *
 * @author Youchao Feng
 * @version 1.0
 * @date Apr 16, 2015 11:03:49 AM
 */
public class Socks5Handler implements SocksHandler {

    private static final String TAG = "Socks5Handler";

    /**
     * Protocol version.
     */
    private static final int VERSION = 0x5;

    /**
     * Session.
     */
    private Session session;

    /**
     * Method selector.
     */
    private MethodSelector methodSelector;

    private int bufferSize;

    private int idleTime = 2000;

    private SocksProxy proxy;

    private SocksProxyServer socksProxyServer;

    private SessionManager sessionManager;

    @Override
    public void handle(Session session) throws Exception {
        sessionManager = getSocksProxyServer().getSessionManager();
        sessionManager.sessionOnCreate(session);

        MethodSelectionMessage msg = new MethodSelectionMessage();
        session.read(msg);

        if (msg.getVersion() != VERSION) {
            throw new ProtocolErrorException();
        }
        SocksMethod selectedMethod = methodSelector.select(msg);

        Log.d(TAG, String.format("SESSION[%d] Response client:%s", session.getId(),
                selectedMethod.getMethodName()));
        // send select method.
        session.write(new MethodSelectionResponseMessage(VERSION, selectedMethod));

        // do method.
        selectedMethod.doMethod(session);

        CommandMessage commandMessage = new CommandMessage();
        session.read(commandMessage); // Read command request.

        //    logger.info("SESSION[{}] request:{}  {}:{}", session.getId(), commandMessage
        //    .getCommand(),
        //        commandMessage.getAddressType() != AddressType.DOMAIN_NAME ?
        //            commandMessage.getInetAddress() :
        //            commandMessage.getHost(), commandMessage.getPort());

        // If there is a SOCKS exception in command message, It will send a right response to
        // client.
        if (commandMessage.hasSocksException()) {
            ServerReply serverReply = commandMessage.getSocksException().getServerReply();
            session.write(new CommandResponseMessage(serverReply));
            Log.i(TAG, String.format("SESSION[%d] will close, because %s", session.getId(), serverReply));
            return;
        }

        // DO COMMAND
        sessionManager.sessionOnCommand(session, commandMessage);
        switch (commandMessage.getCommand()) {
            case BIND:
                doBind(session, commandMessage);
                break;
            case CONNECT:
                doConnect(session, commandMessage);
                break;
            case UDP_ASSOCIATE:
                doUDPAssociate(session, commandMessage);
                break;
        }
    }

    @Override
    public void doConnect(Session session, CommandMessage commandMessage) throws SocksException,
            IOException {

        ServerReply reply;
        Socket socket = null;
        InetAddress bindAddress;
        int bindPort = 0;
        InetAddress remoteServerAddress = commandMessage.getInetAddress();
        int remoteServerPort = commandMessage.getPort();

        // set default bind address.
        byte[] defaultAddress = {0, 0, 0, 0};
        bindAddress = InetAddress.getByAddress(defaultAddress);
        // DO connect
        try {
            // Connect directly.
            if (proxy == null) {
                socket = new Socket(remoteServerAddress, remoteServerPort);
            } else {
                socket = new SocksSocket(proxy, remoteServerAddress, remoteServerPort);
            }
            bindAddress = socket.getLocalAddress();
            bindPort = socket.getLocalPort();
            reply = ServerReply.SUCCEEDED;

        } catch (IOException e) {
            switch (e.getMessage()) {
                case "Connection refused":
                    reply = ServerReply.CONNECTION_REFUSED;
                    break;
                case "Operation timed out":
                    reply = ServerReply.TTL_EXPIRED;
                    break;
                case "Network is unreachable":
                    reply = ServerReply.NETWORK_UNREACHABLE;
                    break;
                case "Connection timed out":
                    reply = ServerReply.TTL_EXPIRED;
                    break;
                default:
                    reply = ServerReply.GENERAL_SOCKS_SERVER_FAILURE;
                    break;
            }
            Log.i(TAG, String.format("SESSION[%d] connect %s [%s] exception:%s",
                    session.getId(), new InetSocketAddress(remoteServerAddress, remoteServerPort),
                    reply, e.getMessage()));
        }

        CommandResponseMessage responseMessage =
                new CommandResponseMessage(VERSION, reply, bindAddress, bindPort);
        session.write(responseMessage);
        if (reply != ServerReply.SUCCEEDED) { // 如果返回失败信息，则退出该方法。
            session.close();
            return;
        }

        Pipe pipe = new SocketPipe(session.getSocket(), socket);
        pipe.setName("SESSION[" + session.getId() + "]");
        pipe.setBufferSize(bufferSize);
        if (getSocksProxyServer().getPipeInitializer() != null) {
            pipe = getSocksProxyServer().getPipeInitializer().initialize(pipe);
        }
        pipe.start(); // This method will build tow thread to run tow internal pipes.

        // wait for pipe exit.
        while (pipe.isRunning()) {
            try {
                Thread.sleep(idleTime);
            } catch (InterruptedException e) {
                pipe.stop();
                session.close();
                Log.i(TAG, "SESSION[" + session.getId() + "] closed");
            }
        }

    }

    @Override
    public void doBind(Session session, CommandMessage commandMessage) throws SocksException,
            IOException {

        ServerSocket serverSocket = new ServerSocket(commandMessage.getPort());
        int bindPort = serverSocket.getLocalPort();
        Socket socket;
        Log.i(TAG, String.format("Create TCP server bind at %s for session[%d]", serverSocket
                .getLocalSocketAddress(), session.getId()));
        session.write(new CommandResponseMessage(VERSION, ServerReply.SUCCEEDED, serverSocket
                .getInetAddress(), bindPort));

        socket = serverSocket.accept();
        session.write(new CommandResponseMessage(VERSION, ServerReply.SUCCEEDED, socket
                .getLocalAddress(), socket.getLocalPort()));

        Pipe pipe = new SocketPipe(session.getSocket(), socket);
        pipe.setBufferSize(bufferSize);
        pipe.start();

        // wait for pipe exit.
        while (pipe.isRunning()) {
            try {
                Thread.sleep(idleTime);
            } catch (InterruptedException e) {
                pipe.stop();
                session.close();
                Log.i(TAG, "Session[" + session.getId() + "] closed");
            }
        }
        serverSocket.close();
        // throw new NotImplementException("Not implement BIND command");
    }

    @Override
    public void doUDPAssociate(Session session, CommandMessage commandMessage) throws
            SocksException, IOException {
        UDPRelayServer udpRelayServer =
                new UDPRelayServer(((InetSocketAddress) session.getClientAddress()).getAddress(),
                        commandMessage.getPort());
        InetSocketAddress socketAddress = (InetSocketAddress) udpRelayServer.start();
        Log.i(TAG, String.format("Create UDP relay server at[%s] for %s", socketAddress, commandMessage
                .getSocketAddress()));
        session.write(new CommandResponseMessage(VERSION, ServerReply.SUCCEEDED, InetAddress
                .getLocalHost(), socketAddress.getPort()));
        while (udpRelayServer.isRunning()) {
            try {
                Thread.sleep(idleTime);
            } catch (InterruptedException e) {
                session.close();
                Log.i(TAG, "Session[" + session.getId() + "] closed");
            }
            if (session.isClose()) {
                udpRelayServer.stop();
                Log.d(TAG, "UDP relay server for session[" + session.getId() + "] is closed");
            }

        }

    }

    @Override
    public void setSession(Session session) {
        this.session = session;
    }


    @Override
    public void run() {
        try {
            handle(session);
        } catch (Exception e) {
            sessionManager.sessionOnException(session, e);
            //      logger.error("SESSION[{}]: {}", session.getId(), e.getMessage());
        } finally {
            session.close();
            sessionManager.sessionOnClose(session);
            //      logger.info("SESSION[{}] closed, {}", session.getId(), session.getNetworkMonitor().toString
            //          ());
        }
    }

    @Override
    public MethodSelector getMethodSelector() {
        return methodSelector;
    }

    @Override
    public void setMethodSelector(MethodSelector methodSelector) {
        this.methodSelector = methodSelector;
    }

    @Override
    public int getBufferSize() {
        return bufferSize;
    }

    @Override
    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }


    @Override
    public int getIdleTime() {
        return idleTime;
    }

    @Override
    public void setIdleTime(int idleTime) {
        this.idleTime = idleTime;
    }

    public SocksProxy getProxy() {
        return proxy;
    }

    @Override
    public void setProxy(SocksProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public SocksProxyServer getSocksProxyServer() {
        return socksProxyServer;
    }

    @Override
    public void setSocksProxyServer(SocksProxyServer socksProxyServer) {
        this.socksProxyServer = socksProxyServer;
    }

}
