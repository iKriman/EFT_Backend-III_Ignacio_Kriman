package cl.bancoxyz.auth;
import java.security.*;
import java.security.interfaces.*;
import java.time.Duration;
import java.util.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class AuthorizationConfig {
 @Bean @Order(1)
 SecurityFilterChain oauth(HttpSecurity http) throws Exception {
  OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
  return http.build();
 }
 @Bean @Order(2)
 SecurityFilterChain other(HttpSecurity http) throws Exception {
  return http.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().denyAll()).build();
 }
 @Bean RegisteredClientRepository clients(@Value("${bank.oauth.api-secret}") String api,
                                         @Value("${bank.oauth.atm-secret}") String atm) {
  return new InMemoryRegisteredClientRepository(client("bank-api-client", api, "bank.read", "bank.write", "batch.execute", "events.write", "events.read"),
                                                client("bank-atm-client", atm, "atm.read", "atm.write"));
 }
 private RegisteredClient client(String id, String secret, String... scopes) {
  var builder=RegisteredClient.withId(id).clientId(id)
   .clientSecret(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(secret))
   .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
   .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
   .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15)).build());
  for (String scope:scopes) builder.scope(scope);
  return builder.build();
 }
 @Bean AuthorizationServerSettings settings(@Value("${bank.oauth.issuer}") String issuer) {
  return AuthorizationServerSettings.builder().issuer(issuer).build();
 }
 @Bean OAuth2TokenCustomizer<JwtEncodingContext> audience() {
  return context -> context.getClaims().audience(List.of("bank-services"));
 }
 // Clave efimera para la demostracion. Reiniciar invalida los tokens anteriores.
 @Bean JWKSource<SecurityContext> jwks() throws GeneralSecurityException {
  KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
  KeyPair pair=generator.generateKeyPair();
  RSAKey key=new RSAKey.Builder((RSAPublicKey)pair.getPublic()).privateKey((RSAPrivateKey)pair.getPrivate()).keyID(UUID.randomUUID().toString()).build();
  return new ImmutableJWKSet<>(new JWKSet(key));
 }
}
