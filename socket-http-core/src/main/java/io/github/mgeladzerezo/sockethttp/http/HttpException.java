package io.github.mgeladzerezo.sockethttp.http;

/**
 * Thrown by a handler, by middleware or by the router to end the request with an error status.
 * The server turns it into a response; an error-mapping middleware can intercept it first to
 * change the representation.
 */
public class HttpException extends RuntimeException {

    private final int status;
    private final Headers headers = new Headers();

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public HttpException(int status) {
        this(status, HttpStatus.reason(status));
    }

    public HttpException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** Headers the error response must carry, such as {@code Allow} on a 405. */
    public Headers headers() {
        return headers;
    }

    public HttpException header(String name, String value) {
        headers.set(name, value);
        return this;
    }

    public static HttpException badRequest(String message) {
        return new HttpException(HttpStatus.BAD_REQUEST, message);
    }

    public static HttpException notFound(String message) {
        return new HttpException(HttpStatus.NOT_FOUND, message);
    }
}
