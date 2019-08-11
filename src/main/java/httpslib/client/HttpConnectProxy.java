package httpslib.client;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.SocketFactory;

import sockslib.common.AnonymousCredentials;
import sockslib.common.Credentials;
import sockslib.common.UsernamePasswordCredentials;

public class HttpConnectProxy {

    private static final String TAG = "HttpConnectProxy";

    private InetAddress inetAddress;
    private int port;
    private int mTimeOut = 0;
    @Nullable private String userAgent;
    private Socket proxySocket;
    @NonNull private SocketFactory factory = SocketFactory.getDefault();
    private Credentials credentials = new AnonymousCredentials();
    private HttpConnectProxy chainProxy;
    private boolean alwaysResolveAddressLocally = false;

    public HttpConnectProxy(InetSocketAddress socketAddress, String username, String password) {
        this(socketAddress);
        setCredentials(new UsernamePasswordCredentials(username, password));
    }

    public HttpConnectProxy(String host, int port) throws UnknownHostException {
        this(InetAddress.getByName(host), port);
    }

    public HttpConnectProxy(InetAddress inetAddress, int port) {
        this(new InetSocketAddress(inetAddress, port));
    }

    public HttpConnectProxy(InetSocketAddress socketAddress) {
        this(null, socketAddress);
    }

    public HttpConnectProxy(HttpConnectProxy chainProxy, InetSocketAddress socketAddress) {
        inetAddress = socketAddress.getAddress();
        port = socketAddress.getPort();
        this.setChainProxy(chainProxy);
    }

    public HttpConnectProxy(String host, int port, Credentials credentials) throws UnknownHostException {
        this.inetAddress = InetAddress.getByName(host);
        this.port = port;
        this.credentials = credentials;
    }

    public void setTimeOut(int timeOut) {
        mTimeOut = timeOut;
    }

    @Nullable
    public Socket getProxySocket() {
        return proxySocket;
    }

    public HttpConnectProxy setProxySocket(@Nullable Socket proxySocket) {
        this.proxySocket = proxySocket;
        return this;
    }

    public int getPort() {
        return port;
    }

    public HttpConnectProxy setPort(int port) {
        this.port = port;
        return this;
    }

    @Nullable
    public String getUserAgent() {
        return userAgent;
    }

    public HttpConnectProxy setUserAgent(@Nullable String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    public InetAddress getInetAddress() {
        return inetAddress;
    }

    public HttpConnectProxy setInetAddress(InetAddress inetAddress) {
        this.inetAddress = inetAddress;
        return this;
    }

    public HttpConnectProxy setHost(String host) throws UnknownHostException {
        inetAddress = InetAddress.getByName(host);
        return this;
    }

    public void buildConnection() throws IOException {
        if (inetAddress == null) {
            throw new IllegalArgumentException(
                    "Please set inetAddress before calling buildConnection.");
        }
        if (proxySocket == null) {
            proxySocket = createProxySocket(inetAddress, port);
            proxySocket.setSoTimeout(mTimeOut);
        } else if (!proxySocket.isConnected()) {
            proxySocket.connect(new InetSocketAddress(inetAddress, port), mTimeOut);
        }
    }

    @CheckResult
    public boolean requestConnect(String host, int port)
            throws IOException {
        if (!alwaysResolveAddressLocally) {
            // resolve address in SOCKS server
            return send(proxySocket, host, port);

        } else {
            // resolve address in local.
            InetAddress address = InetAddress.getByName(host);
            return send(proxySocket, new InetSocketAddress(address, port));
        }
    }

    @CheckResult
    public boolean requestConnect(InetAddress address, int port)
            throws IOException {
        return send(proxySocket, new InetSocketAddress(address, port));
    }

    @CheckResult
    public boolean requestConnect(InetSocketAddress address)
            throws IOException {
        return send(proxySocket, address);
    }

    private boolean send(Socket socket, InetSocketAddress socketAddress) throws IOException {
        String address = socketAddress.getAddress().getHostAddress();
        final int port = socketAddress.getPort();
        return send(socket, address, port);
    }

    private boolean send(Socket socket, String host, int port) throws IOException {
        final InputStream inputStream = socket.getInputStream();
        final OutputStream outputStream = socket.getOutputStream();
        String request = "";
        request += "CONNECT " + host + ":" + port + " HTTP/1.1\r\n";
        request += "Host: " + host + ":" + port + "\r\n";
        if (credentials instanceof UsernamePasswordCredentials) {
            String username = credentials.getUserPrincipal().getName();
            String password = credentials.getPassword();
            String encoded = Base64.encodeToString((username + ":" + password).getBytes(), Base64.NO_WRAP);
            request += "Proxy-Authorization: Basic " + encoded + "\r\n";
        }
        if (userAgent != null) {
            request += "User-Agent: " + userAgent + "\r\n";
        }
        request += "\r\n";
        outputStream.write(request.getBytes());
        outputStream.flush();
        return checkServerReply(inputStream);
    }

    private boolean checkServerReply(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String firstLine = reader.readLine();
        if (firstLine == null) {
            return false;
        }
        int start = firstLine.indexOf(' ') + 1;
        int end = firstLine.indexOf(' ', start);
        if (start == 0 || end == -1) {
            throw new IOException("response content error: " + firstLine);
        }
        String responseCode = firstLine.substring(start, end);
        try {
            if (Integer.parseInt(responseCode) == 200) {
                return true;
            } else {
                Log.e(TAG, "Response code error: " + firstLine);
                return false;
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return false;
        }
    }

    public InputStream getInputStream() throws IOException {
        return proxySocket.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return proxySocket.getOutputStream();
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public HttpConnectProxy setCredentials(Credentials credentials) {
        this.credentials = credentials;
        return this;
    }

    @NonNull
    public SocketFactory getFactory() {
        return factory;
    }

    public HttpConnectProxy setFactory(@NonNull SocketFactory factory) {
        this.factory = factory;
        return this;
    }

    public HttpConnectProxy copy() {
        return new HttpConnectProxy(inetAddress, port)
                .setAlwaysResolveAddressLocally(alwaysResolveAddressLocally)
                .setUserAgent(getUserAgent()).setFactory(getFactory()).setCredentials(credentials)
                .setChainProxy(chainProxy);
    }

    public HttpConnectProxy copyWithoutChainProxy() {
        return copy().setChainProxy(null);
    }

    public HttpConnectProxy getChainProxy() {
        return chainProxy;
    }

    public HttpConnectProxy setChainProxy(HttpConnectProxy chainProxy) {
        this.chainProxy = chainProxy;
        return this;
    }

    public Socket createProxySocket(InetAddress address, int port) throws IOException {
        return factory.createSocket(address, port);
    }

    public Socket createProxySocket() throws IOException {
        return factory.createSocket();
    }

    public boolean isAlwaysResolveAddressLocally() {
        return alwaysResolveAddressLocally;
    }

    public HttpConnectProxy setAlwaysResolveAddressLocally(boolean alwaysResolveAddressLocally) {
        this.alwaysResolveAddressLocally = alwaysResolveAddressLocally;
        return this;
    }
}
