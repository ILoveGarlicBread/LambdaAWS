package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class LambdaTokenVerifier implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // MUST match the key in your Generator
  private static final String SECRET_KEY = "vgu-cloud-secret-2026";

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

    try {
      JSONObject body = new JSONObject(event.getBody());

      if (!body.has("email") || !body.has("token")) {
        return createResponse(400, "{\"valid\": false, \"message\": \"Missing email or token\"}");
      }

      String email = body.getString("email");
      String token = body.getString("token");

      // VERIFY LOGIC
      if (isValidToken(email, token)) {
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

  private boolean isValidToken(String email, String token) {
    try {
      // Re-create the signature using the email and YOUR secret
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec secretKeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(secretKeySpec);
      byte[] hmacBytes = mac.doFinal(email.getBytes(StandardCharsets.UTF_8));
      String expectedToken = Base64.getEncoder().encodeToString(hmacBytes);

      // Compare
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
}
