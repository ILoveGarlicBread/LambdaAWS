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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class LambdaTokenGenerator implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

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
            // System Manager parameter store

            // get the value of a parameter (e.g., "keytokenhash") from system manager
            // parameter store

            String requestBody = event.getBody();
            JSONObject bodyJSON = new JSONObject(requestBody);

            if (!bodyJSON.has("email")) {
                return createResponse(400, "{\"error\": \"Missing email field\"}");
            }

            String email = bodyJSON.getString("email");
            String key = getKey(logger);
            if (key == null) {
                return createResponse(500, "Error accesing key");
            }

            // Generate Token
            String token = generateSecureToken(email, key, logger);

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

            return base64;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.log("Crypto Error: " + e.getMessage());
            return null;
        }
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
