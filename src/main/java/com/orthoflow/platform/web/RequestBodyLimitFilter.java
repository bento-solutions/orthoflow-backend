package com.orthoflow.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A ceiling on every request body, before anything reads it (audit S3).
 *
 * <p>{@code server.tomcat.max-http-form-post-size} only covers form-encoded posts, so a JSON or text
 * body had no limit at all, and the unauthenticated WhatsApp webhook read the whole of one into memory
 * before checking its signature. A declared {@code Content-Length} over the limit is refused at once
 * (413); a body sent without one (chunked) is counted as it is read and stops at the limit. Multipart
 * uploads keep their own limits ({@code spring.servlet.multipart}, and the per-type caps of the files).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final long PUBLIC_LIMIT = 64 * 1024;
    static final long WEBHOOK_LIMIT = 256 * 1024;
    /** A long consultation's transcript and its timed lines come back on each save. */
    static final long CONSULTATION_LIMIT = 8 * 1024 * 1024;
    static final long DEFAULT_LIMIT = 2 * 1024 * 1024;

    static long limitFor(String path) {
        if (path.startsWith("/public/")) {
            return PUBLIC_LIMIT;
        }
        if (path.startsWith("/webhooks/")) {
            return WEBHOOK_LIMIT;
        }
        if (path.startsWith("/consultations/") || path.equals("/consultations")) {
            return CONSULTATION_LIMIT;
        }
        return DEFAULT_LIMIT;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(request.getServletPath());
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,"
                    + "\"detail\":\"The request body is larger than this endpoint accepts (" + limit / 1024 + " KB).\"}");
            return;
        }
        chain.doFilter(new Limited(request, limit), response);
    }

    /** Thrown when a body without a declared length goes past the limit; the handler answers 413. */
    public static final class BodyTooLargeException extends IOException {
        public BodyTooLargeException(long limit) {
            super("The request body is larger than this endpoint accepts (" + limit / 1024 + " KB).");
        }
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;

        Limited(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Counting(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class Counting extends ServletInputStream {
        private final ServletInputStream in;
        private final long limit;
        private long read;

        Counting(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        private void count(long n) throws BodyTooLargeException {
            if (n > 0) {
                read += n;
                if (read > limit) {
                    throw new BodyTooLargeException(limit);
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = in.read(buffer, off, len);
            count(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
