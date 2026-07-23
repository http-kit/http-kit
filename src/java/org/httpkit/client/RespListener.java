package org.httpkit.client;

import org.httpkit.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import static org.httpkit.HttpUtils.CONTENT_ENCODING;
import static org.httpkit.HttpUtils.CONTENT_TYPE;
import static org.httpkit.HttpUtils.NON_TEXT_CONTENT_TYPES;

class Handler implements Runnable {

    private final int status;
    private final Map<String, Object> headers;
    private final Object body;
    private final IResponseHandler handler;

    public Handler(IResponseHandler handler, int status, Map<String, Object> headers,
                   Object body) {
        this.handler = handler;
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public Handler(IResponseHandler handler, Throwable e) {
        this(handler, 0, null, e);
    }

    public void run() {
        try {
            if (body instanceof Throwable) {
                handler.onThrowable((Throwable) body);
            } else {
                handler.onSuccess(status, headers, body);
            }
        } catch (Exception e) { // onSuccess may throw Exception
            handler.onThrowable(e); // should not throw exception
        }
    }
}

class ResponseStream extends InputStream {

    private static final int MAX_QUEUED_BYTES = 8 * 1024 * 1024;

    private static class Entry {
        final byte[] bytes;
        final Throwable failure;

        Entry(byte[] bytes, Throwable failure) {
            this.bytes = bytes;
            this.failure = failure;
        }
    }

    private static final Entry EOF = new Entry(null, null);

    private final LinkedBlockingQueue<Entry> entries =
        new LinkedBlockingQueue<Entry>();

    private byte[] current;
    private int currentIndex;
    private Throwable readFailure;
    private boolean eof;
    private volatile boolean closed;
    private boolean terminated;
    private int queuedBytes;

    synchronized boolean add(byte[] bytes, int length) {
        if (closed || terminated || length > MAX_QUEUED_BYTES - queuedBytes) {
            return false;
        }
        byte[] chunk = length == bytes.length
            ? bytes : Arrays.copyOf(bytes, length);
        entries.offer(new Entry(chunk, null));
        queuedBytes += length;
        return true;
    }

    private synchronized void consumed(int length) {
        queuedBytes = Math.max(0, queuedBytes - length);
    }

    synchronized void complete() {
        if (!terminated) {
            terminated = true;
            entries.offer(EOF);
        }
    }

    synchronized void fail(Throwable failure) {
        if (!terminated) {
            terminated = true;
            entries.offer(new Entry(null, failure));
        }
    }

    private boolean nextChunk() throws IOException {
        if (closed) {
            return false;
        }
        while (current == null || currentIndex == current.length) {
            if (readFailure != null) {
                throw failureException(readFailure);
            }
            if (closed || eof) {
                return false;
            }

            final Entry entry;
            try {
                entry = entries.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading response stream", e);
            }

            if (entry == EOF) {
                eof = true;
                return false;
            }
            if (entry.failure != null) {
                readFailure = entry.failure;
                throw failureException(readFailure);
            }

            current = entry.bytes;
            consumed(current.length);
            currentIndex = 0;
        }
        return true;
    }

    private IOException failureException(Throwable failure) {
        if (failure instanceof IOException) {
            return (IOException) failure;
        }
        return new IOException("Response stream failed", failure);
    }

    @Override
    public int read() throws IOException {
        if (!nextChunk()) {
            return -1;
        }
        return current[currentIndex++] & 0xFF;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) {
            throw new NullPointerException();
        }
        if (offset < 0 || length < 0 || length > bytes.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return 0;
        }
        if (!nextChunk()) {
            return -1;
        }

        int read = Math.min(length, current.length - currentIndex);
        System.arraycopy(current, currentIndex, bytes, offset, read);
        currentIndex += read;
        return read;
    }

    @Override
    public int available() {
        return closed || current == null ? 0 : current.length - currentIndex;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            terminated = true;
            queuedBytes = 0;
            entries.clear();
            entries.offer(EOF);
        }
    }
}

class StreamingHandler implements Runnable {

    private final IResponseHandler handler;
    private final int status;
    private final Map<String, Object> headers;
    private final InputStream body;
    private final String encoding;

    StreamingHandler(IResponseHandler handler, int status,
                     Map<String, Object> headers, InputStream body,
                     String encoding) {
        this.handler = handler;
        this.status = status;
        this.headers = headers;
        this.body = body;
        this.encoding = encoding;
    }

    public void run() {
        try {
            InputStream decoded =
                ResponseCompression.createDecompressingStream(body, encoding);
            new Handler(handler, status, headers, decoded).run();
        } catch (IOException e) {
            try {
                body.close();
            } catch (IOException ignored) {
            }
            handler.onThrowable(e);
        }
    }
}

/**
 * Accumulate all the response, call upper logic at once, for easy use
 */
public class RespListener implements IRespListener {

    private boolean isText() {
        if (status.getCode() != 200) {
            return true;
        } // non 200: treat as text
        String type = HttpUtils.getStringValue(headers, CONTENT_TYPE);

        if (type == null) {
            return false;
        }

        type = type.toLowerCase(java.util.Locale.ROOT);
        int parameter = type.indexOf(';');
        if (parameter >= 0) {
            type = type.substring(0, parameter).trim();
        }

        // TODO may miss something
        if (NON_TEXT_CONTENT_TYPES.contains(type)) {
            return false;
        } else {
            return type.contains("text") || type.contains("json") || type.contains("xml");
        }
    }

    private DynamicBytes unzipBody() throws IOException, AbortException {
        if (body.length() == 0) {
            return body;
        }

        String encoding = HttpUtils.getStringValue(headers, CONTENT_ENCODING);
        ResponseCompression.Type compressionType =
            ResponseCompression.detect(encoding, body.get(), body.length());
        if (compressionType == ResponseCompression.Type.NONE) {
            return body;
        }

        BytesInputStream bis = new BytesInputStream(body.get(), body.length());
        IFilter.MaxBodyFilter maxBodyFilter = filter instanceof IFilter.MaxBodyFilter
                ? (IFilter.MaxBodyFilter) filter : null;
        long projectedLength = maxBodyFilter != null
                ? body.length() : (long) body.length() * 5L;
        int initialLength = projectedLength <= Integer.MAX_VALUE - 8
                ? (int) projectedLength : body.length();
        DynamicBytes unzipped = new DynamicBytes(initialLength);
        try (InputStream is = ResponseCompression.createDecompressingStream(
                bis, compressionType)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                unzipped.append(buffer, read);
                if (maxBodyFilter != null
                        && !maxBodyFilter.acceptsLength(unzipped.length())) {
                    throw new AbortException(
                            "Rejected when decompressing body, length: " + unzipped.length());
                }
            }
        }
        return unzipped;
    }

    private final DynamicBytes body;

    // can be empty
    private Map<String, Object> headers = new TreeMap<String, Object>();
    private HttpStatus status;
    private final IResponseHandler handler;
    private final IFilter filter;
    private final ExecutorService pool;
    final int coercion;

    // only used for immediate streaming
    private ResponseStream responseStream;

    public RespListener(IResponseHandler handler, IFilter filter, ExecutorService pool, int coercion) {
        body = new DynamicBytes(1024 * 8);
        this.filter = filter;
        this.handler = handler;
        this.coercion = coercion;
        this.pool = pool;
    }

    private boolean immediatelyStreaming() {
        return coercion == 3 &&
            (filter == null || filter == IFilter.ACCEPT_ALL);
    }

    private boolean startStreamingResponse(String encoding) {
        responseStream = new ResponseStream();
        try {
            pool.execute(new StreamingHandler(
                handler, status.getCode(), headers, responseStream, encoding));
            return true;
        } catch (RejectedExecutionException e) {
            responseStream.close();
            new Handler(handler, e).run();
            return false;
        }
    }

    private void submitHandler(Handler task) {
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            new Handler(handler, e).run();
        }
    }

    private void submitBufferedCompletion() {
        try {
            pool.execute(new Runnable() {
                public void run() {
                    completeBufferedResponse();
                }
            });
        } catch (RejectedExecutionException e) {
            new Handler(handler, e).run();
        }
    }

    private void completeBufferedResponse() {
        try {
            if (coercion == 0 || coercion == 5) {
                Object b = coercion == 0 ? body : body.bytes();
                new Handler(handler, status.getCode(), headers, b).run();
                return;
            }
            DynamicBytes bytes = unzipBody();
            if (coercion == 2 || (coercion == 1 && isText())) {
                Charset charset = HttpUtils.detectCharset(headers, bytes);
                String html = new String(bytes.get(), 0, bytes.length(), charset);
                new Handler(handler, status.getCode(), headers, html).run();
            } else {
                BytesInputStream is = new BytesInputStream(bytes.get(), bytes.length());
                Object result = coercion == 4 ? is.bytes() : is;
                new Handler(handler, status.getCode(), headers, result).run();
            }
        } catch (Exception e) {
            new Handler(handler, e).run();
        }
    }

    private void streamResponseChunk(byte[] buf, int length)
            throws AbortException {
        if (responseStream == null) {
            if (!startStreamingResponse(
                    HttpUtils.getStringValue(headers, CONTENT_ENCODING))) {
                throw new AbortException("Response worker pool rejected task");
            }
        }
        if (!responseStream.add(buf, length)) {
            throw new AbortException(
                "Response stream is closed or its unread buffer limit was exceeded");
        }
    }

    public void onBodyReceived(byte[] buf, int length) throws AbortException {
        if (immediatelyStreaming()) {
            if (length == 0) {
                return;
            }
            streamResponseChunk(buf, length);
            return;
        }

        body.append(buf, length);
        if (filter != null && !filter.accept(body)) {
            throw new AbortException("Rejected when reading body, length: " + body.length());
        }
    }

    public void onCompleted() {
        if (status == null) {
            submitHandler(new Handler(handler,
                new ProtocolException("No status")));
            return;
        }

        if (immediatelyStreaming() && responseStream != null) {
            responseStream.complete();
            return;
        }
        if (immediatelyStreaming()) {
            // An empty response has no compression representation to decode.
            if (startStreamingResponse(null)) {
                responseStream.complete();
            }
            return;
        }
        submitBufferedCompletion();
    }

    public void onThrowable(Throwable t) {
        if (responseStream != null) {
            responseStream.fail(t);
            return;
        }
        submitHandler(new Handler(handler, t));
    }

    public void onHeadersReceived(Map<String, Object> headers) throws AbortException {
        this.headers = headers;
        if (filter != null && !filter.accept(headers)) {
            throw new AbortException("Rejected when header received");
        }
    }

    public void onInitialLineReceived(HttpVersion version, HttpStatus status)
            throws AbortException {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Object getHeader(String name) {
        return headers.get(name);
    }
}
