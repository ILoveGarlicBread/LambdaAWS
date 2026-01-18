package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.json.JSONObject;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

public class LambdaGetObject
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // --- CONFIGURATION ---
  private final LambdaClient lambdaClient;
  private static final String VERIFIER_FUNCTION_NAME = "LambdaTokenVerifier";

  public LambdaGetObject() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {

    // --- WARMER CHECK ---
    if (event.getBody() != null && event.getBody().contains("warmer")) {
      return new APIGatewayProxyResponseEvent().withStatusCode(200).withBody("Warmed");
    }

    LambdaLogger logger = context.getLogger();

    try {
      String requestBody = event.getBody();
      if (requestBody == null) {
        return createResponse(400, "{\"error\": \"Missing request body\"}", "application/json");
      }

      JSONObject bodyJSON = new JSONObject(requestBody);

      // 1. SECURITY CHECK
      if (!bodyJSON.has("key") || !bodyJSON.has("token")) {
        return createResponse(401, "{\"error\": \"Missing key or token\"}", "application/json");
      }

      // Call Verifier
      JSONObject verifierPayload = new JSONObject();
      verifierPayload.put("body", requestBody);

      String verificationResult = callLambda("LambdaTokenVerifier", verifierPayload.toString(), logger);
      JSONObject verifyJson = new JSONObject(verificationResult);

      if (verifyJson.has("error")) {
        return createResponse(500, "{\"error\": \"Verifier Error: " + verifyJson.getString("error") + "\"}",
            "application/json");
      }
      if (!verifyJson.has("valid") || !verifyJson.getBoolean("valid")) {
        return createResponse(401, "{\"error\": \"Unauthorized: Invalid Token\"}", "application/json");
      }

      // 2. EXISTING S3 LOGIC (Only runs if token is valid)
      String key = bodyJSON.getString("key");
      String bucketName = "bucket-lam1303";
      S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_2).build();

      ListObjectsRequest listObjects = ListObjectsRequest.builder().bucket(bucketName).build();
      ListObjectsResponse res = s3Client.listObjects(listObjects);
      List<S3Object> objects = res.contents();

      int maxSize = 10 * 1024 * 1024; // 10MB
      Boolean found = false;
      Boolean validSize = false;
      String mimeType = "application/octet-stream";

      for (S3Object object : objects) {
        if (object.key().equals(key)) {
          found = true;
          if (object.size() < maxSize) {
            validSize = true;
          }
          // Simple MIME detection
          String[] parts = key.split("\\.");
          if (parts.length > 1) {
            String ext = parts[parts.length - 1].toLowerCase();
            if (ext.equals("png"))
              mimeType = "image/png";
            else if (ext.equals("jpg") || ext.equals("jpeg"))
              mimeType = "image/jpeg";
            else if (ext.equals("html"))
              mimeType = "text/html";
          }
          break;
        }
      }

      String encodedString = "";
      if (found && validSize) {
        GetObjectRequest s3Request = GetObjectRequest.builder().bucket(bucketName).key(key).build();
        byte[] buffer;
        try (ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(s3Request)) {
          buffer = s3Response.readAllBytes();
          encodedString = Base64.getEncoder().encodeToString(buffer);
        } catch (IOException ex) {
          logger.log("IOException: " + ex);
          return createResponse(500, "{\"error\": \"Failed to read file\"}", "application/json");
        }
      } else {
        return createResponse(404, "{\"error\": \"File not found or too large\"}", "application/json");
      }

      // Return the Image
      APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
      response.setStatusCode(200);
      response.setBody(encodedString);
      response.withIsBase64Encoded(true);
      response.setHeaders(java.util.Collections.singletonMap("Content-Type", mimeType));
      return response;

    } catch (Exception e) {
      logger.log("Error: " + e.getMessage());
      return createResponse(500, "{\"error\": \"Server Error\"}", "application/json");
    }
  }

  // --- HELPERS ---

  public String callLambda(String functionName, String payload, LambdaLogger logger) {
    try {
      InvokeRequest invokeRequest = InvokeRequest.builder()
          .functionName(functionName)
          .payload(SdkBytes.fromUtf8String(payload))
          .invocationType("RequestResponse")
          .build();

      InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
      ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
      String jsonResponse = StandardCharsets.UTF_8.decode(responsePayload).toString();

      JSONObject responseObject = new JSONObject(jsonResponse);
      if (responseObject.has("body")) {
        return responseObject.getString("body");
      }
      return jsonResponse;
    } catch (Exception e) {
      logger.log("Error invoking " + functionName + ": " + e.getMessage());
      return "{\"error\": \"Invocation Failed\"}";
    }
  }

  private APIGatewayProxyResponseEvent createResponse(int statusCode, String body, String contentType) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(body)
        .withHeaders(java.util.Collections.singletonMap("Content-Type", contentType));
  }
}
