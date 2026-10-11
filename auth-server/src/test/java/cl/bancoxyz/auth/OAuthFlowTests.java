package cl.bancoxyz.auth;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"bank.oauth.api-secret=test-api-secret","bank.oauth.atm-secret=test-atm-secret"})
class OAuthFlowTests {
 @Autowired TestRestTemplate rest;
 @org.springframework.boot.test.web.server.LocalServerPort int port;
 private ResponseEntity<Map> token(String client,String secret,String scope){
  try {
   String basic=java.util.Base64.getEncoder().encodeToString((client+":"+secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   String body="grant_type=client_credentials&scope="+java.net.URLEncoder.encode(scope,java.nio.charset.StandardCharsets.UTF_8);
   var req=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/oauth2/token"))
    .header("Authorization","Basic "+basic).header("Content-Type","application/x-www-form-urlencoded")
    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
   var response=java.net.http.HttpClient.newHttpClient().send(req,java.net.http.HttpResponse.BodyHandlers.ofString());
   return ResponseEntity.status(response.statusCode()).body(new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(),Map.class));
  } catch(Exception e) {throw new IllegalStateException(e);}

 }
 @Test void issuesTokenAndPublishesKeys(){
  var r=token("bank-api-client","test-api-secret","bank.read events.write");
  assertEquals(200,r.getStatusCode().value());assertNotNull(r.getBody().get("access_token"));
  assertEquals("Bearer",r.getBody().get("token_type"));
  assertEquals(200,rest.getForEntity("/oauth2/jwks",Map.class).getStatusCode().value());
 }
 @Test void wrongSecretIsRejected(){assertEquals(401,token("bank-api-client","wrong","bank.read").getStatusCode().value());}
 @Test void clientCannotEscalateScope(){assertEquals(400,token("bank-atm-client","test-atm-secret","bank.read").getStatusCode().value());}
}
