package io.github.mgeladzerezo.sockethttp.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A point-in-time copy of the server's counters. Latencies are in microseconds and cover the
 * time from a complete request being parsed to the last byte of its response being handed to
 * the socket.
 */
public record MetricsSnapshot(String concurrencyModel,
                              long uptimeMillis,
                              long connectionsActive,
                              long connectionsAccepted,
                              long connectionsRejected,
                              long requestsTotal,
                              long requestsInFlight,
                              long responses1xx,
                              long responses2xx,
                              long responses3xx,
                              long responses4xx,
                              long responses5xx,
                              long requestsRejected,
                              long requestTimeouts,
                              long bytesReceived,
                              long bytesSent,
                              long latencyCount,
                              double latencyMeanMicros,
                              long latencyP50Micros,
                              long latencyP90Micros,
                              long latencyP99Micros,
                              long latencyP999Micros,
                              long latencyMaxMicros) {

    /** The snapshot as a JSON-ready map, grouped the way the built-in metrics handler serves it. */
    public Map<String, Object> toMap() {
        Map<String, Object> connections = new LinkedHashMap<>();
        connections.put("active", connectionsActive);
        connections.put("accepted", connectionsAccepted);
        connections.put("rejected", connectionsRejected);

        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("1xx", responses1xx);
        responses.put("2xx", responses2xx);
        responses.put("3xx", responses3xx);
        responses.put("4xx", responses4xx);
        responses.put("5xx", responses5xx);

        Map<String, Object> requests = new LinkedHashMap<>();
        requests.put("total", requestsTotal);
        requests.put("inFlight", requestsInFlight);
        requests.put("rejectedBeforeRouting", requestsRejected);
        requests.put("timeouts", requestTimeouts);
        requests.put("responses", responses);

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("count", latencyCount);
        latency.put("mean", Math.round(latencyMeanMicros * 10) / 10.0);
        latency.put("p50", latencyP50Micros);
        latency.put("p90", latencyP90Micros);
        latency.put("p99", latencyP99Micros);
        latency.put("p999", latencyP999Micros);
        latency.put("max", latencyMaxMicros);

        Map<String, Object> bytes = new LinkedHashMap<>();
        bytes.put("received", bytesReceived);
        bytes.put("sent", bytesSent);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("concurrencyModel", concurrencyModel);
        root.put("uptimeMillis", uptimeMillis);
        root.put("connections", connections);
        root.put("requests", requests);
        root.put("latencyMicros", latency);
        root.put("bytes", bytes);
        return root;
    }

    /** The snapshot in the Prometheus text exposition format. */
    public String toPrometheus() {
        StringBuilder sb = new StringBuilder(1024);
        gauge(sb, "sockethttp_connections_active", "Open client connections", connectionsActive);
        counter(sb, "sockethttp_connections_accepted_total", "Connections accepted", connectionsAccepted);
        counter(sb, "sockethttp_connections_rejected_total", "Connections refused because the server was saturated", connectionsRejected);
        counter(sb, "sockethttp_requests_total", "Requests that reached a handler", requestsTotal);
        gauge(sb, "sockethttp_requests_in_flight", "Requests currently being handled", requestsInFlight);
        counter(sb, "sockethttp_requests_rejected_total", "Requests refused by the parser", requestsRejected);
        counter(sb, "sockethttp_request_timeouts_total", "Requests that hit a read deadline", requestTimeouts);
        sb.append("# HELP sockethttp_responses_total Responses by status class\n# TYPE sockethttp_responses_total counter\n");
        long[] byClass = {responses1xx, responses2xx, responses3xx, responses4xx, responses5xx};
        for (int i = 0; i < byClass.length; i++) {
            sb.append("sockethttp_responses_total{class=\"").append(i + 1).append("xx\"} ").append(byClass[i]).append('\n');
        }
        counter(sb, "sockethttp_bytes_received_total", "Bytes read from clients", bytesReceived);
        counter(sb, "sockethttp_bytes_sent_total", "Bytes written to clients", bytesSent);
        sb.append("# HELP sockethttp_request_duration_microseconds Request latency\n"
                + "# TYPE sockethttp_request_duration_microseconds summary\n");
        quantile(sb, "0.5", latencyP50Micros);
        quantile(sb, "0.9", latencyP90Micros);
        quantile(sb, "0.99", latencyP99Micros);
        quantile(sb, "0.999", latencyP999Micros);
        sb.append("sockethttp_request_duration_microseconds_count ").append(latencyCount).append('\n');
        return sb.toString();
    }

    private static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name)
                .append(" gauge\n").append(name).append(' ').append(value).append('\n');
    }

    private static void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name)
                .append(" counter\n").append(name).append(' ').append(value).append('\n');
    }

    private static void quantile(StringBuilder sb, String q, long value) {
        sb.append("sockethttp_request_duration_microseconds{quantile=\"").append(q).append("\"} ").append(value).append('\n');
    }
}
