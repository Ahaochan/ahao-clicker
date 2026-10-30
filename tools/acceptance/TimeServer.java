import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Loopback-only, real TLS endpoint. Test APK trusts only its generated certificate. */
public final class TimeServer {
    private static final AtomicInteger requests = new AtomicInteger();
    private static final AtomicInteger options = new AtomicInteger();
    private static volatile String mode = "good";
    private static volatile long offset = 5000;

    public static void main(String[] args) throws Exception {
        char[] password = Files.readString(Path.of(args[1])).trim().toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(args[0]))) { keys.load(input, password); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/", exchange -> {
            HttpsExchange response = (HttpsExchange) exchange;
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/control")) {
                    String query = exchange.getRequestURI().getRawQuery();
                    if (query != null) for (String part : query.split("&")) {
                        String[] value = part.split("=", 2);
                        if (value[0].equals("mode")) mode = value[1];
                        if (value[0].equals("offset")) offset = Long.parseLong(value[1]);
                    }
                } else if (!path.equals("/stats")) {
                    int count = requests.incrementAndGet();
                    if (count <= 1000) System.out.println("request=" + count + " mode=" + mode + " path=" + path);
                    if (mode.equals("slow")) Thread.sleep(2500);
                }
                long now = System.currentTimeMillis() + offset;
                if (path.equals("/jd")) {
                    boolean isOptions = exchange.getRequestMethod().equals("OPTIONS");
                    if (isOptions) options.incrementAndGet();
                    if (!mode.equals("missing")) response.getResponseHeaders().set("x-api-request-id",
                        mode.equals("malformed") ? "not-a-timestamp" : "10105444978-107668-" + (mode.equals("stale") ? now - 60_000 : now));
                    response.getResponseHeaders().set("Cache-Control", "no-store");
                    if (mode.equals("age")) response.getResponseHeaders().set("Age", "30");
                    response.sendResponseHeaders(!isOptions ? 405 : mode.equals("error") ? 503 : 204, -1);
                    return;
                }
                String body = "{\"data\":{\"timestamp\":" + (path.equals("/seconds") ? now / 1000 : now)
                    + "},\"requests\":" + requests.get() + "}";
                if (path.equals("/stats") || path.equals("/control")) body = "{\"requests\":" + requests.get() + ",\"jdOptions\":" + options.get() + "}";
                else if (mode.equals("malformed")) body = "{\"data\":{\"timestamp\":\"invalid\"}}";
                else if (mode.equals("oversize")) body = "x".repeat(70000);
                response.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                response.getResponseHeaders().set("Cache-Control", "no-store");
                if (mode.equals("age") && !path.equals("/control")) response.getResponseHeaders().set("Age", "30");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                int status = mode.equals("error") && !path.equals("/control") && !path.equals("/stats") ? 503 : 200;
                response.sendResponseHeaders(status, bytes.length);
                response.getResponseBody().write(bytes);
            } catch (Exception error) { System.out.println("request-ended=" + error.getClass().getSimpleName()); }
            finally { response.close(); }
        });
        server.start();
        Files.writeString(Path.of(args[2]), Integer.toString(server.getAddress().getPort()));
        System.out.println("TLS server ready on loopback");
    }
}
