import java.net.URI;
import java.net.http.*;
import java.time.Duration;
public class HttpProbe {
    public static void main(String[] args) throws Exception {
        for (String host : new String[]{"localhost", "127.0.0.1"}) for (var version : HttpClient.Version.values()) {
            try (var client = HttpClient.newBuilder().version(version).connectTimeout(Duration.ofSeconds(2)).build()) {
                long start = System.nanoTime();
                try {
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://" + host + ":11434/api/tags")).timeout(Duration.ofSeconds(4)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    System.out.println(host + " " + version + " status=" + response.statusCode() + " elapsed_ms=" + (System.nanoTime()-start)/1e6 + " body=" + response.body());
                } catch(Exception e) { System.out.println(host + " " + version + " elapsed_ms=" + (System.nanoTime()-start)/1e6 + " error=" + e); }
            }
        }
    }
}
