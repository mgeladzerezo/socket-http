package io.github.mgeladzerezo.sockethttp.bench;

import io.github.mgeladzerezo.sockethttp.server.ConcurrencyModel;
import io.github.mgeladzerezo.sockethttp.server.HttpServer;
import io.github.mgeladzerezo.sockethttp.server.ServerConfig;
import io.github.mgeladzerezo.sockethttp.staticfiles.StaticFiles;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.Tomcat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * One server under test, run in its own JVM so that its heap, JIT state and garbage collector
 * are not shared with the load generator or with the previous target.
 *
 * <p>Arguments: {@code <target> <port> <docRoot> <threads>}, where target is a
 * {@link ConcurrencyModel} name or {@code TOMCAT}. All targets serve the same three
 * endpoints: {@code /hello} (a small JSON object), {@code /file.bin} (a static file from
 * {@code docRoot}) and {@code /blocking} (sleeps 20 ms, then answers like {@code /hello}).
 * Prints {@code READY} on stdout once it accepts connections.
 */
public final class BenchServer {

    static final String HELLO_BODY = "{\"message\":\"hello\"}";
    static final long BLOCKING_MILLIS = 20;

    private BenchServer() {
    }

    public static void main(String[] args) throws Exception {
        String target = args[0];
        int port = Integer.parseInt(args[1]);
        Path docRoot = Path.of(args[2]);
        int threads = Integer.parseInt(args[3]);
        if (target.equals("TOMCAT")) {
            startTomcat(port, docRoot, threads);
        } else {
            startSocketHttp(ConcurrencyModel.valueOf(target), port, docRoot, threads);
        }
        System.out.println("READY");
        System.out.flush();
        Thread.currentThread().join();
    }

    private static void startSocketHttp(ConcurrencyModel model, int port, Path docRoot, int threads) {
        ServerConfig config = ServerConfig.builder()
                .host("127.0.0.1")
                .port(port)
                .concurrencyModel(model)
                .workerThreads(threads)
                .maxConnections(8192)
                .maxRequestsPerConnection(0)
                .build();
        HttpServer server = HttpServer.create(config);
        server.get("/hello", ctx -> ctx.json(Map.of("message", "hello")));
        server.get("/blocking", ctx -> {
            Thread.sleep(BLOCKING_MILLIS);
            ctx.json(Map.of("message", "hello"));
        });
        server.get("/*", StaticFiles.from(docRoot).gzip(false).build());
        server.start();
    }

    /**
     * Plain embedded Tomcat 11 with the NIO connector (the default). Settings that differ from
     * Tomcat's defaults are noted: unlimited keep-alive requests (the default of 100 would
     * force a reconnect every 100 requests, which socket-http is also configured not to do),
     * and accept count and connection limit matched to socket-http's.
     */
    private static void startTomcat(int port, Path docRoot, int threads) throws Exception {
        Path base = Files.createTempDirectory(docRoot.getParent(), "tomcat-base");
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setProperty("address", "127.0.0.1");
        connector.setProperty("maxThreads", Integer.toString(threads));
        connector.setProperty("minSpareThreads", Integer.toString(Math.min(threads, 10)));
        connector.setProperty("maxConnections", "8192");
        connector.setProperty("acceptCount", "1024");
        connector.setProperty("maxKeepAliveRequests", "-1");
        connector.setProperty("keepAliveTimeout", "15000");
        connector.setProperty("useSendfile", System.getProperty("bench.tomcat.sendfile", "true"));
        tomcat.getService().addConnector(connector);
        tomcat.setConnector(connector);

        Context context = tomcat.addContext("", docRoot.toAbsolutePath().toString());
        Tomcat.addServlet(context, "hello", new TextServlet(false));
        Tomcat.addServlet(context, "blocking", new TextServlet(true));
        Tomcat.addServlet(context, "default", new DefaultServlet());
        context.addServletMappingDecoded("/hello", "hello");
        context.addServletMappingDecoded("/blocking", "blocking");
        context.addServletMappingDecoded("/", "default");
        tomcat.start();
    }

    private static final class TextServlet extends HttpServlet {
        private static final byte[] BODY = HELLO_BODY.getBytes(StandardCharsets.UTF_8);
        private final boolean blocking;

        TextServlet(boolean blocking) {
            this.blocking = blocking;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            if (blocking) {
                try {
                    Thread.sleep(BLOCKING_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            resp.setContentType("application/json");
            resp.setContentLength(BODY.length);
            resp.getOutputStream().write(BODY);
        }
    }
}
