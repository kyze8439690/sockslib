package httpslib.client;

import static androidx.core.util.Preconditions.checkArgument;
import static androidx.core.util.Preconditions.checkNotNull;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

public class HttpConnectSocket extends Socket {

    private static final String TAG = "HttpConnectSocket";

    private HttpConnectProxy proxy;

    private String remoteServerHost;
    private int remoteServerPort;

    private Socket proxySocket;

    public HttpConnectSocket(HttpConnectProxy proxy, String remoteServerHost, int remoteServerPort)
            throws IOException {
        this.proxy = checkNotNull(proxy, "Argument [proxy] may not be null").copy();
        this.proxy.setProxySocket(proxySocket);
        this.remoteServerHost =
                checkNotNull(remoteServerHost, "Argument [remoteServerHost] may not be null");
        this.remoteServerPort = remoteServerPort;
        this.proxy.buildConnection();
        proxySocket = this.proxy.getProxySocket();
        initProxyChain();
        this.proxy.requestConnect(remoteServerHost, remoteServerPort);
    }

    public HttpConnectSocket(HttpConnectProxy proxy, InetAddress address, int port) throws IOException {
        this(proxy, new InetSocketAddress(address, port));
    }

    public HttpConnectSocket(HttpConnectProxy proxy, InetSocketAddress socketAddress) throws IOException {
        this.proxy = checkNotNull(proxy, "Argument [proxy] may not be null").copy();
        checkNotNull(socketAddress, "Argument [socketAddress] may not be null");
        this.remoteServerHost = socketAddress.getHostName();
        this.remoteServerPort = socketAddress.getPort();
        this.proxy.buildConnection();
        proxySocket = this.proxy.getProxySocket();
        initProxyChain();
        this.proxy.requestConnect(socketAddress.getAddress(), socketAddress.getPort());
    }

    public HttpConnectSocket(HttpConnectProxy proxy) throws IOException {
        this(proxy, proxy.createProxySocket());
    }

    public HttpConnectSocket(HttpConnectProxy proxy, Socket proxySocket) {
        this.proxy = checkNotNull(proxy, "Argument [proxy] may not be null").copy();
        this.proxySocket = checkNotNull(proxySocket, "Argument [proxySocket] may be not null");
        checkArgument(!proxySocket.isConnected(), "Proxy socket should be unconnected");
        this.proxy.setProxySocket(proxySocket);
    }

    private void initProxyChain() throws IOException {
        List<HttpConnectProxy> proxyChain = new ArrayList<>();
        HttpConnectProxy temp = proxy;
        while (temp.getChainProxy() != null) {
            temp.getChainProxy().setProxySocket(proxySocket);
            proxyChain.add(temp.getChainProxy());
            temp = temp.getChainProxy();
        }
        if (proxyChain.size() > 0) {
            Log.d(TAG, "Proxy chain has:" + proxyChain.size() + " proxy");
            HttpConnectProxy pre = proxy;
            for (int i = 0; i < proxyChain.size(); i++) {
                HttpConnectProxy chain = proxyChain.get(i);
                if (!pre.requestConnect(chain.getInetAddress(), chain.getPort())) {
                    throw new IOException("Request connected failed");
                }
                proxy.getChainProxy().buildConnection();
                pre = chain;
            }
        }
    }

    public void connect(String host, int port) throws IOException {
        this.remoteServerHost = checkNotNull(host, "Argument [host] may not be null");
        this.remoteServerPort = checkNotNull(port, "Argument [port] may not be null");
        proxy.buildConnection();
        initProxyChain();
        if (!proxy.requestConnect(remoteServerHost, remoteServerPort)) {
            throw new IOException("Request connected failed");
        }
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        connect(endpoint, 0);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (!(endpoint instanceof InetSocketAddress)) {
            throw new IllegalArgumentException("Unsupported address type");
        }

        remoteServerHost = ((InetSocketAddress) endpoint).getHostName();
        remoteServerPort = ((InetSocketAddress) endpoint).getPort();

        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");

        proxy.getProxySocket().setSoTimeout(timeout);
        proxy.setTimeOut(timeout);
        proxy.buildConnection();
        initProxyChain();
        if (!proxy.requestConnect((InetSocketAddress) endpoint)) {
            throw new IOException("Request connected failed");
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (proxy.getProxySocket() == null) {
            throw new IOException("Proxy socket should be settled");
        }
        return proxy.getProxySocket().getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        if (proxy.getProxySocket() == null) {
            throw new IOException("Proxy socket should be settled");
        }
        return proxy.getProxySocket().getOutputStream();
    }

    @Override
    public void bind(SocketAddress bindpoint) throws IOException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().bind(bindpoint);
    }

    @Override
    public InetAddress getInetAddress() {
        try {
            return InetAddress.getByName(remoteServerHost);
        } catch (UnknownHostException ignored) {
        }
        return null;
    }

    @Override
    public InetAddress getLocalAddress() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getLocalAddress();
    }

    @Override
    public int getPort() {
        return remoteServerPort;
    }

    @Override
    public int getLocalPort() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getLocalPort();
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getLocalSocketAddress();
    }

    @Override
    public SocketChannel getChannel() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getChannel();
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getTcpNoDelay();
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setTcpNoDelay(on);
    }

    @Override
    public void setSoLinger(boolean on, int linger) throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setSoLinger(on, linger);
    }

    @Override
    public int getSoLinger() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getSoLinger();
    }

    @Override
    public void sendUrgentData(int data) throws IOException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().sendUrgentData(data);
    }

    @Override
    public boolean getOOBInline() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getOOBInline();
    }

    @Override
    public void setOOBInline(boolean on) throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setOOBInline(on);
    }

    @Override
    public synchronized int getSoTimeout() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getSoTimeout();
    }

    @Override
    public synchronized void setSoTimeout(int timeout) throws SocketException {
        proxy.setTimeOut(timeout);
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setSoTimeout(timeout);
    }

    @Override
    public synchronized int getSendBufferSize() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getSendBufferSize();
    }

    @Override
    public synchronized void setSendBufferSize(int size) throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setSendBufferSize(size);
    }

    @Override
    public synchronized int getReceiveBufferSize() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getReceiveBufferSize();
    }

    @Override
    public synchronized void setReceiveBufferSize(int size) throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setReceiveBufferSize(size);
    }

    @Override
    public boolean getKeepAlive() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getKeepAlive();
    }

    @Override
    public void setKeepAlive(boolean on) throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setKeepAlive(on);
    }

    @Override
    public int getTrafficClass() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getTrafficClass();
    }

    @Override
    public void setTrafficClass(int tc) throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setTrafficClass(tc);
    }

    @Override
    public boolean getReuseAddress() throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().getReuseAddress();
    }

    @Override
    public void setReuseAddress(boolean on) throws SocketException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setReuseAddress(on);
    }

    @Override
    public synchronized void close() throws IOException {
        if (proxy.getProxySocket() != null) {
            proxy.getProxySocket().close();
        }
    }

    @Override
    public void shutdownInput() throws IOException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().shutdownInput();
    }

    @Override
    public void shutdownOutput() throws IOException {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().shutdownOutput();
    }

    @Override
    public boolean isConnected() {
        if (proxy.getProxySocket() == null) {
            return false;
        }
        return proxy.getProxySocket().isConnected();
    }

    @Override
    public boolean isBound() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().isBound();
    }

    @Override
    public boolean isClosed() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().isClosed();
    }

    @Override
    public boolean isInputShutdown() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().isInputShutdown();
    }

    @Override
    public boolean isOutputShutdown() {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        return proxy.getProxySocket().isOutputShutdown();
    }

    @Override
    public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
        checkNotNull(proxy.getProxySocket(), "Proxy socket should be settled");
        proxy.getProxySocket().setPerformancePreferences(connectionTime, latency, bandwidth);
    }

    public Socket getProxySocket() {
        return proxy.getProxySocket();
    }

    @Override
    public String toString() {
        if (getProxySocket() != null) {
            return getProxySocket().toString();
        } else {
            return super.toString();
        }
    }
}
