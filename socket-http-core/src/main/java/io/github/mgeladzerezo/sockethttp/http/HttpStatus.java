package io.github.mgeladzerezo.sockethttp.http;

/**
 * Status codes used by the server, with their RFC 9110 reason phrases.
 *
 * <p>Plain {@code int} constants rather than an enum: applications may send any three-digit
 * code, and the response writer must not have to special-case "unknown" values.
 */
public final class HttpStatus {

    public static final int CONTINUE = 100;
    public static final int SWITCHING_PROTOCOLS = 101;
    public static final int OK = 200;
    public static final int CREATED = 201;
    public static final int ACCEPTED = 202;
    public static final int NO_CONTENT = 204;
    public static final int PARTIAL_CONTENT = 206;
    public static final int MOVED_PERMANENTLY = 301;
    public static final int FOUND = 302;
    public static final int SEE_OTHER = 303;
    public static final int NOT_MODIFIED = 304;
    public static final int TEMPORARY_REDIRECT = 307;
    public static final int PERMANENT_REDIRECT = 308;
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int NOT_ACCEPTABLE = 406;
    public static final int REQUEST_TIMEOUT = 408;
    public static final int CONFLICT = 409;
    public static final int LENGTH_REQUIRED = 411;
    public static final int PRECONDITION_FAILED = 412;
    public static final int CONTENT_TOO_LARGE = 413;
    public static final int URI_TOO_LONG = 414;
    public static final int UNSUPPORTED_MEDIA_TYPE = 415;
    public static final int RANGE_NOT_SATISFIABLE = 416;
    public static final int EXPECTATION_FAILED = 417;
    public static final int UNPROCESSABLE_CONTENT = 422;
    public static final int UPGRADE_REQUIRED = 426;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int REQUEST_HEADER_FIELDS_TOO_LARGE = 431;
    public static final int INTERNAL_SERVER_ERROR = 500;
    public static final int NOT_IMPLEMENTED = 501;
    public static final int BAD_GATEWAY = 502;
    public static final int SERVICE_UNAVAILABLE = 503;
    public static final int GATEWAY_TIMEOUT = 504;
    public static final int HTTP_VERSION_NOT_SUPPORTED = 505;

    private HttpStatus() {
    }

    /** Reason phrase for the status line; a generic phrase for codes this table does not know. */
    public static String reason(int status) {
        return switch (status) {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 206 -> "Partial Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 422 -> "Unprocessable Content";
            case 426 -> "Upgrade Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            default -> switch (status / 100) {
                case 1 -> "Informational";
                case 2 -> "Success";
                case 3 -> "Redirection";
                case 4 -> "Client Error";
                default -> "Server Error";
            };
        };
    }

    /**
     * Whether a response with this status is defined to have no content (RFC 9110 §6.4.1):
     * 1xx, 204 and 304 never carry a body, whatever the handler set.
     */
    public static boolean forbidsBody(int status) {
        return status / 100 == 1 || status == NO_CONTENT || status == NOT_MODIFIED;
    }
}
