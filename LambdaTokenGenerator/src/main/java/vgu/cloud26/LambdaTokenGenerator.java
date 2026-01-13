package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import org.json.JSONObject; // Ensure you have this library (org.json)
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class LambdaTokenGenerator implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // HARDCODED SECRET (For demo only - normally use Environment Variables)
    private static final String SECRET_KEY = "key";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

        // --- WARMER CHECK ---
        if (event.getBody() != null && event.getBody().contains("warmer")) {
            context.getLogger().log("Warming event received. Exiting.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("Warmed");
        }

        LambdaLogger logger = context.getLogger();
        logger.log("Starting Token Processing");

        try {
            String requestBody = event.getBody();
            JSONObject bodyJSON = new JSONObject(requestBody);

            if (!bodyJSON.has("email")) {
                return createResponse(400, "{\"error\": \"Missing email field\"}");
            }

            String email = bodyJSON.getString("email");

            // Generate Token
            String token = generateSecureToken(email, SECRET_KEY, logger);

            if (token == null) {
                return createResponse(500, "{\"error\": \"Token generation failed, token is null\"}");
            }

            JSONObject responseBody = new JSONObject();
            responseBody.put("token", token);
            responseBody.put("email", email);

            return createResponse(200, responseBody.toString());

        } catch (Exception e) {
            logger.log("Error processing token: " + e.getMessage());
            // Return JSON error
            return createResponse(500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String jsonBody) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(jsonBody)
                .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json")) // Add Content-Type
                .withIsBase64Encoded(false);
    }

    public static String generateSecureToken(String data, String key, LambdaLogger logger) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);

            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String base64 = Base64.getEncoder().encodeToString(hmacBytes);

            // logger.log("Secure Token Generated for: " + data); // Log email, but maybe
            // not the token itself in prod
            return base64;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.log("Crypto Error: " + e.getMessage());
            return null;
        }
    }
}
