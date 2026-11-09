package com.wyrm.desktop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Java's URLConnection refuses PATCH. Only small JSON PATCH requests use this adapter. */
public final class DesktopPatchConnection extends HttpURLConnection {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(12)).build();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private HttpResponse<byte[]> reply;

    public DesktopPatchConnection(URL url) { super(url); }
    @Override public void setRequestMethod(String method) { this.method = method; }
    @Override public OutputStream getOutputStream() { return body; }
    private synchronized HttpResponse<byte[]> response() throws IOException {
        if (reply != null) return reply;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(url.toURI())
                .timeout(Duration.ofMillis(getReadTimeout() > 0 ? getReadTimeout() : 15000))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
            getRequestProperties().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            reply = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            connected = true;
            return reply;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", error);
        } catch (java.net.URISyntaxException error) { throw new IOException("Invalid API address", error); }
    }
    @Override public int getResponseCode() throws IOException { return response().statusCode(); }
    @Override public InputStream getInputStream() throws IOException { return new ByteArrayInputStream(response().body()); }
    @Override public InputStream getErrorStream() {
        try { return response().statusCode() >= 400 ? new ByteArrayInputStream(reply.body()) : null; }
        catch (IOException error) { return null; }
    }
    @Override public String getHeaderField(String name) {
        try { return response().headers().firstValue(name).orElse(null); }
        catch (IOException error) { return null; }
    }
    @Override public void connect() throws IOException { response(); }
    @Override public void disconnect() { reply = null; connected = false; }
    @Override public boolean usingProxy() { return false; }
}
