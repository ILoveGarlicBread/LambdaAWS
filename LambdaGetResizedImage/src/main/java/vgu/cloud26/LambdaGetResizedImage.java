package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Base64;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

public class LambdaGetResizedImage
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String RESIZED_BUCKET_NAME =
      "resizebucket-lam1303"; // YOUR RESIZED BUCKET NAME

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {
    if (event.getBody() != null && event.getBody().contains("warmer")) {
    context.getLogger().log("Warming event received. Exiting.");
    return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody("Warmed");
}
    LambdaLogger logger = context.getLogger();

    // 1. Parse the requested filename
    // Supports both POST body {"key":"..."} or Query String ?key=...
    String originalKey = "";
    try {
      if (event.getBody() != null && !event.getBody().isEmpty()) {
        JSONObject body = new JSONObject(event.getBody());
        originalKey = body.getString("key");
      } else if (event.getQueryStringParameters() != null) {
        originalKey = event.getQueryStringParameters().get("key");
      }
    } catch (Exception e) {
      return createErrorResponse(400, "Invalid Request: " + e.getMessage());
    }

    if (originalKey == null || originalKey.isEmpty()) {
      return createErrorResponse(400, "Missing 'key' parameter");
    }

    // 2. Calculate Resized Key
    String resizedKey = "resized-" + originalKey;

    try {
      S3Client s3 = S3Client.builder().region(Region.AP_SOUTHEAST_2).build();

      // 3. Get Object from S3
      GetObjectRequest getRequest =
          GetObjectRequest.builder().bucket(RESIZED_BUCKET_NAME).key(resizedKey).build();

      ResponseBytes<GetObjectResponse> objectBytes = s3.getObjectAsBytes(getRequest);
      byte[] data = objectBytes.asByteArray();
      String contentType = objectBytes.response().contentType();

      // 4. Convert to Base64
      String base64Data = Base64.getEncoder().encodeToString(data);

      // 5. Return Image Response
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(200)
          .withHeaders(Map.of("Content-Type", contentType))
          .withBody(base64Data)
          .withIsBase64Encoded(true);

    } catch (Exception e) {
      logger.log("Error fetching resized image: " + e.getMessage());
      // Return a 404 so the browser shows a "broken image" icon instead of crashing
      return createErrorResponse(404, "Image not found");
    }
  }

  private APIGatewayProxyResponseEvent createErrorResponse(int statusCode, String message) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(Map.of("Content-Type", "text/plain"))
        .withBody(message);
  }
}
