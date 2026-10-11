import java.net.*;
import java.net.http.*;
import java.time.Duration;
public class Healthcheck {
 public static void main(String[] args) throws Exception {
  var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  var request=HttpRequest.newBuilder(URI.create("http://localhost:"+args[0]+"/actuator/health")).timeout(Duration.ofSeconds(4)).build();
  System.exit(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200?0:1);
 }
}
