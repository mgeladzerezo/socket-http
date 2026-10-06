package io.github.mgeladzerezo.sockethttp.bench;

import io.github.mgeladzerezo.sockethttp.json.Json;
import io.github.mgeladzerezo.sockethttp.metrics.Histogram;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Runs the comparison: for each target (three socket-http concurrency models and embedded
 * Tomcat) a fresh server JVM is started, every endpoint is driven at every connection count,
 * and the server is stopped again. Writes the raw results as JSON and a Markdown table.
 *
 * <pre>
 * java -jar socket-http-bench.jar --duration 10 --warmup 3 --connections 64,512 \
 *      --repeats 1 --threads 200 --out results/results.json
 * </pre>
 */
public final class BenchMain {

    private static final List<String> ALL_TARGETS = List.of("THREAD_POOL", "VIRTUAL_THREADS", "NIO_EVENT_LOOP", "TOMCAT");

    private record Endpoint(String name, String path, int bodyBytes) {
    }

    private static final int FILE_BYTES = 100 * 1024;
    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint("hello", "/hello", BenchServer.HELLO_BODY.length()),
            new Endpoint("file-100k", "/file.bin", FILE_BYTES),
            new Endpoint("blocking-20ms", "/blocking", BenchServer.HELLO_BODY.length()));

    private BenchMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        Duration duration = Duration.ofSeconds(Long.parseLong(options.getOrDefault("duration", "10")));
        Duration warmup = Duration.ofSeconds(Long.parseLong(options.getOrDefault("warmup", "3")));
        int threads = Integer.parseInt(options.getOrDefault("threads", "200"));
        int repeats = Integer.parseInt(options.getOrDefault("repeats", "1"));
        List<Integer> connections = new ArrayList<>();
        for (String c : options.getOrDefault("connections", "64,512").split(",")) {
            connections.add(Integer.parseInt(c.trim()));
        }
        List<String> targets = options.containsKey("targets")
                ? List.of(options.get("targets").split(",")) : ALL_TARGETS;
        Path out = Path.of(options.getOrDefault("out", "results/results.json"));
        Path work = Path.of(options.getOrDefault("workdir", System.getProperty("java.io.tmpdir")))
                .resolve("socket-http-bench-" + System.nanoTime());
        Files.createDirectories(work);
        Path docRoot = Files.createDirectories(work.resolve("docroot"));
        byte[] file = new byte[FILE_BYTES];
        new Random(1).nextBytes(file);
        Files.write(docRoot.resolve("file.bin"), file);

        // All servers stay up for the whole run and the repeats of a cell go round-robin across
        // them, so a burst of load from other processes on this machine hits every target
        // alike instead of whichever one happened to be running at the time.
        List<ServerProcess> servers = new ArrayList<>();
        List<Map<String, Object>> cells = new ArrayList<>();
        try {
            for (String target : targets) {
                servers.add(ServerProcess.start(target, docRoot, threads, work));
            }
            for (Endpoint endpoint : ENDPOINTS) {
                if (options.containsKey("endpoints") && !List.of(options.get("endpoints").split(",")).contains(endpoint.name)) {
                    continue;
                }
                for (int c : connections) {
                    Map<String, List<Map<String, Object>>> runsByTarget = new LinkedHashMap<>();
                    for (int r = 0; r < repeats; r++) {
                        for (ServerProcess server : servers) {
                            LoadGenerator generator = new LoadGenerator("127.0.0.1", server.port, endpoint.path, endpoint.bodyBytes);
                            LoadGenerator.Result result = generator.run(c, warmup, duration);
                            runsByTarget.computeIfAbsent(server.target, k -> new ArrayList<>()).add(describe(result));
                            System.out.printf("%-16s %-14s c=%-4d %9.0f req/s  p50 %6d us  p99 %7d us  errors %d%n",
                                    server.target, endpoint.name, c, result.requestsPerSecond(),
                                    result.latencyMicros().percentile(50), result.latencyMicros().percentile(99),
                                    result.errors() + result.connectFailures());
                        }
                    }
                    runsByTarget.forEach((target, runs) -> {
                        Map<String, Object> cell = new LinkedHashMap<>();
                        cell.put("target", target);
                        cell.put("endpoint", endpoint.name);
                        cell.put("connections", c);
                        cell.put("runs", runs);
                        cells.add(cell);
                    });
                }
            }
        } finally {
            servers.forEach(ServerProcess::close);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("startedAt", Instant.now().toString());
        root.put("environment", environment(threads, duration, warmup, repeats));
        root.put("cells", cells);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, pretty(Json.write(root)) + "\n", StandardCharsets.UTF_8);
        Path table = out.resolveSibling(out.getFileName().toString().replaceFirst("\\.json$", "") + ".md");
        Files.writeString(table, markdown(cells), StandardCharsets.UTF_8);
        System.out.println("\nwrote " + out + " and " + table);
    }

    private static Map<String, Object> describe(LoadGenerator.Result r) {
        Histogram h = r.latencyMicros();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requests", r.requests());
        m.put("errors", r.errors());
        m.put("connectFailures", r.connectFailures());
        m.put("requestsPerSecond", Math.round(r.requestsPerSecond() * 10) / 10.0);
        m.put("latencyMicrosP50", h.percentile(50));
        m.put("latencyMicrosP90", h.percentile(90));
        m.put("latencyMicrosP99", h.percentile(99));
        m.put("latencyMicrosP999", h.percentile(99.9));
        m.put("latencyMicrosMax", h.max());
        m.put("latencyMicrosMean", Math.round(h.mean()));
        return m;
    }

    private static Map<String, Object> environment(int threads, Duration duration, Duration warmup, int repeats) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        env.put("logicalCpus", Runtime.getRuntime().availableProcessors());
        env.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        env.put("serverHeap", "-Xms128m -Xmx512m");
        env.put("serverThreads", threads);
        env.put("warmupSeconds", warmup.toSeconds());
        env.put("measureSeconds", duration.toSeconds());
        env.put("repeats", repeats);
        env.put("tomcat", "embedded Tomcat 11.0.24, Http11NioProtocol, maxThreads=" + threads
                + ", maxKeepAliveRequests=-1, acceptCount=1024, maxConnections=8192");
        env.put("note", "load generator and server share one machine that other processes were also using");
        return env;
    }

    /** The run with the median throughput, so one disturbed run does not decide the table. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> median(Map<String, Object> cell) {
        List<Map<String, Object>> runs = new ArrayList<>((List<Map<String, Object>>) cell.get("runs"));
        runs.sort(Comparator.comparingDouble(r -> ((Number) r.get("requestsPerSecond")).doubleValue()));
        return runs.get(runs.size() / 2);
    }

    private static String markdown(List<Map<String, Object>> cells) {
        StringBuilder sb = new StringBuilder();
        sb.append("| endpoint | connections | target | req/s | p50 (ms) | p99 (ms) | max (ms) | errors |\n");
        sb.append("|---|---:|---|---:|---:|---:|---:|---:|\n");
        List<Map<String, Object>> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.<Map<String, Object>, String>comparing(c -> (String) c.get("endpoint"))
                .thenComparing(c -> (Integer) c.get("connections")));
        for (Map<String, Object> cell : sorted) {
            Map<String, Object> run = median(cell);
            sb.append(String.format("| %s | %d | %s | %,.0f | %s | %s | %s | %d |%n",
                    cell.get("endpoint"), cell.get("connections"), cell.get("target"),
                    ((Number) run.get("requestsPerSecond")).doubleValue(),
                    ms(run.get("latencyMicrosP50")), ms(run.get("latencyMicrosP99")), ms(run.get("latencyMicrosMax")),
                    ((Number) run.get("errors")).longValue() + ((Number) run.get("connectFailures")).longValue()));
        }
        return sb.toString();
    }

    private static String ms(Object micros) {
        return String.format("%.2f", ((Number) micros).doubleValue() / 1000.0);
    }

    /** Light pretty-printer so the committed raw file diffs and reads well. */
    private static String pretty(String json) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\') {
                    sb.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    sb.append(c);
                }
                case '{', '[' -> {
                    sb.append(c);
                    if (i + 1 < json.length() && (json.charAt(i + 1) == '}' || json.charAt(i + 1) == ']')) {
                        sb.append(json.charAt(++i));
                    } else {
                        depth++;
                        newline(sb, depth);
                    }
                }
                case '}', ']' -> {
                    depth--;
                    newline(sb, depth);
                    sb.append(c);
                }
                case ',' -> {
                    sb.append(c);
                    newline(sb, depth);
                }
                case ':' -> sb.append(": ");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void newline(StringBuilder sb, int depth) {
        sb.append('\n').append("  ".repeat(Math.max(0, depth)));
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("usage: --name value pairs, got " + args[i]);
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    /** A server JVM started for the duration of one target's cells. */
    private static final class ServerProcess implements AutoCloseable {
        private final Process process;
        final int port;
        final String target;

        private ServerProcess(Process process, int port, String target) {
            this.target = target;
            this.process = process;
            this.port = port;
        }

        static ServerProcess start(String target, Path docRoot, int threads, Path work) throws IOException {
            int port;
            try (ServerSocket probe = new ServerSocket()) {
                probe.bind(new InetSocketAddress("127.0.0.1", 0));
                port = probe.getLocalPort();
            }
            String java = Path.of(System.getProperty("java.home"), "bin", "java" + (File.separatorChar == '\\' ? ".exe" : "")).toString();
            ProcessBuilder pb = new ProcessBuilder(java, "-Xms128m", "-Xmx512m",
                    "-cp", System.getProperty("java.class.path"),
                    BenchServer.class.getName(), target, Integer.toString(port), docRoot.toString(), Integer.toString(threads));
            pb.redirectError(work.resolve(target + ".err").toFile());
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("READY")) {
                    Thread drain = new Thread(() -> reader.lines().forEach(l -> { }), "drain-" + target);
                    drain.setDaemon(true);
                    drain.start();
                    return new ServerProcess(process, port, target);
                }
                if (System.nanoTime() > deadline) {
                    break;
                }
            }
            process.destroyForcibly();
            throw new IOException(target + " server did not become ready; see " + work.resolve(target + ".err"));
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
