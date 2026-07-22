package dadb.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small ABI-independent TCP relay intended to run through Android app_process.
 */
public final class TcpRelayMain {
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final Pattern FORWARD_PATTERN =
            Pattern.compile("^tcp://(?:127\\.0\\.0\\.1|localhost)?:(\\d{1,5})/([A-Za-z0-9.-]+):(\\d{1,5})$");

    private TcpRelayMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: TcpRelayMain tcp://127.0.0.1:localPort/targetHost:targetPort [...]");
            System.exit(2);
            return;
        }

        List<ForwardSpec> specs = new ArrayList<>();
        Set<Integer> localPorts = new HashSet<>();
        for (String argument : args) {
            ForwardSpec spec = ForwardSpec.parse(argument);
            if (!localPorts.add(spec.localPort)) {
                throw new IllegalArgumentException("duplicate local port: " + spec.localPort);
            }
            specs.add(spec);
        }

        ExecutorService relayPool = Executors.newCachedThreadPool();
        List<ListenerBinding> listeners = new ArrayList<>();
        try {
            for (ForwardSpec spec : specs) {
                listeners.add(bind(spec, "127.0.0.1"));
                listeners.add(bind(spec, "::1"));
            }
        } catch (Exception error) {
            for (ListenerBinding listener : listeners) {
                closeQuietly(listener.server);
            }
            relayPool.shutdownNow();
            throw error;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (ListenerBinding listener : listeners) {
                closeQuietly(listener.server);
            }
            relayPool.shutdownNow();
        }, "dadb-tcp-relay-shutdown"));

        for (ListenerBinding listenerBinding : listeners) {
            ForwardSpec spec = listenerBinding.spec;
            ServerSocket server = listenerBinding.server;
            System.out.println(
                    "LISTEN " + listenerBinding.bindHost + ":" + spec.localPort +
                            " -> " + spec.targetHost + ":" + spec.targetPort
            );
            Thread listener = new Thread(
                    () -> acceptConnections(server, spec, relayPool),
                    "dadb-tcp-relay-listen-" + listenerBinding.bindHost + "-" + spec.localPort
            );
            listener.start();
        }

        System.out.println("READY forwards=" + specs.size());
        System.out.flush();
        new CountDownLatch(1).await();
    }

    private static ListenerBinding bind(ForwardSpec spec, String bindHost) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(bindHost), spec.localPort));
        return new ListenerBinding(spec, bindHost, server);
    }

    private static void acceptConnections(
            ServerSocket server,
            ForwardSpec spec,
            ExecutorService relayPool
    ) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                relayPool.execute(() -> connectAndBridge(client, spec, relayPool));
            } catch (IOException error) {
                if (!server.isClosed()) {
                    System.err.println("ACCEPT_ERROR 127.0.0.1:" + spec.localPort + " " + error.getMessage());
                    System.err.flush();
                }
            }
        }
    }

    private static void connectAndBridge(
            Socket client,
            ForwardSpec spec,
            ExecutorService relayPool
    ) {
        Socket target = new Socket();
        try {
            target.connect(new InetSocketAddress(spec.targetHost, spec.targetPort), CONNECT_TIMEOUT_MILLIS);
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            System.out.println(time + " " + spec.targetHost + ":" + spec.targetPort + ":" + spec.localPort);
            System.out.flush();
            bridge(client, target, relayPool);
        } catch (IOException error) {
            closeQuietly(client);
            closeQuietly(target);
            System.err.println(
                    "CONNECT_ERROR 127.0.0.1:" + spec.localPort + " -> " +
                            spec.targetHost + ":" + spec.targetPort + " " + error.getMessage()
            );
            System.err.flush();
        }
    }

    private static void bridge(
            Socket client,
            Socket target,
            ExecutorService relayPool
    ) {
        Future<?> upstream = relayPool.submit(() -> copy(client, target));
        try {
            copy(target, client);
        } finally {
            closeQuietly(client);
            closeQuietly(target);
            upstream.cancel(true);
        }
    }

    private static void copy(Socket sourceSocket, Socket targetSocket) {
        byte[] buffer = new byte[16 * 1024];
        try {
            InputStream source = sourceSocket.getInputStream();
            OutputStream target = targetSocket.getOutputStream();
            int count;
            while ((count = source.read(buffer)) >= 0) {
                target.write(buffer, 0, count);
                target.flush();
            }
            targetSocket.shutdownOutput();
        } catch (IOException ignored) {
            // Closing either side is the normal way a relay connection finishes.
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort shutdown.
        }
    }

    private record ForwardSpec(int localPort, String targetHost, int targetPort) {

        static ForwardSpec parse(String argument) {
                Matcher matcher = FORWARD_PATTERN.matcher(argument);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("invalid forward: " + argument);
                }
                int localPort = parsePort(matcher.group(1));
                int targetPort = parsePort(matcher.group(3));
                return new ForwardSpec(localPort, matcher.group(2), targetPort);
            }

            private static int parsePort(String value) {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65_535) {
                    throw new IllegalArgumentException("port out of range: " + value);
                }
                return port;
            }
        }

    private record ListenerBinding(ForwardSpec spec, String bindHost, ServerSocket server) {
    }
}
