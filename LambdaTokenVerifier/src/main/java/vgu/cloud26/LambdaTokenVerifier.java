package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Duration;

public class LambdaTokenVerifier implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Override
  public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

    // Warmer
    if (event.getBody() != null && event.getBody().contains("warmer")) {
      context.getLogger().log("Warming event received. Exiting.");
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withBody("Warmed");
    }
    LambdaLogger logger = context.getLogger();
    // System Manager parameter store

    // get the value of a parameter (e.g., "keytokenhash") from system manager
    // parameter store

    try {

      JSONObject body = new JSONObject(event.getBody());

      if (!body.has("email") || !body.has("token")) {
        return createResponse(400, "{\"valid\": false, \"message\": \"Missing email or token\"}");
      }

      String email = body.getString("email");
      String token = body.getString("token");
      String key = getKey(logger);
      if (key == null) {
        return createResponse(500, "Error accesing key");
      }

      // VERIFY LOGIC
      if (isValidToken(email, token, key, logger)) {
        logger.log("Token Verified for: " + email);
        return createResponse(200, "{\"valid\": true}");
      } else {
        logger.log("Invalid Token for: " + email);
        return createResponse(401, "{\"valid\": false, \"message\": \"Signature Mismatch\"}");
      }

    } catch (Exception e) {
      logger.log("Error: " + e.getMessage());
      return createResponse(500, "{\"valid\": false, \"error\": \"Internal Error\"}");
    }
  }

  private boolean isValidToken(String email, String token, String key, LambdaLogger logger) {
    try {
      // Re-create the signature using the email and YOUR secret
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(secretKeySpec);
      byte[] hmacBytes = mac.doFinal(email.getBytes(StandardCharsets.UTF_8));
      String expectedToken = Base64.getEncoder().encodeToString(hmacBytes);
      logger.log("--- TOKEN DEBUG ---");
      logger.log("Email Used:      [" + email + "]");
      logger.log("Received Token:  [" + token + "]");
      logger.log("Calculated Token:[" + expectedToken + "]");
      logger.log("Match Result:    " + expectedToken.equals(token));
      // -----------------------
      return expectedToken.equals(token);
    } catch (Exception e) {
      return false;
    }
  }

  private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(body)
        .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
  }

  public static String getKey(LambdaLogger logger) {
    try {

      HttpClient client = HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1) // Use HTTP/1.1 (default might be HTTP/2)
          .followRedirects(HttpClient.Redirect.NORMAL)
          .connectTimeout(Duration.ofSeconds(10))
          .build();
      // 2. Create an HttpRequest instance
      HttpRequest requestParameter;
      requestParameter = HttpRequest.newBuilder()
          .uri(URI.create(
              "http://localhost:2773/systemsmanager/parameters/get/?name=cloud26key"))
          .header("Accept", "application/json") // Set request headers
          .GET() // Specify GET method (default, but explicit is clear)
          .build();

      // 3. Send the request synchronously and get the response
      HttpResponse<String> responseParameter = client.send(requestParameter,
          HttpResponse.BodyHandlers.ofString());

      // 4. Process the response
      String key = responseParameter.body();
      return key;
    } catch (Exception e) {
      logger.log("Error accessing key: " + e.getMessage());
      return null;

    }
  }

}
