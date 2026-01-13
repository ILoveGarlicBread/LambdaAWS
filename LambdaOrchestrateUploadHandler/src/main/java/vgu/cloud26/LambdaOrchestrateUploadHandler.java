package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class LambdaOrchestrateUploadHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LambdaClient lambdaClient;
  private static final String VERIFIER_URL = "https://jjkqussr3hlcy2bjgz2jtjtfyq0btfqj.lambda-url.ap-southeast-2.on.aws/";

  public LambdaOrchestrateUploadHandler() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
  }

  // Helper to call a worker Lambda
  public String callLambda(String functionName, String payload, LambdaLogger logger) {
    try {
      InvokeRequest invokeRequest = InvokeRequest.builder()
          .functionName(functionName)
          .payload(SdkBytes.fromUtf8String(payload))
          .invocationType("RequestResponse") // Synchronous
          .build();

      InvokeResponse invokeResult = lambdaClient.invoke(invokeRequest);
      ByteBuffer responsePayload = invokeResult.payload().asByteBuffer();
      String jsonResponse = StandardCharsets.UTF_8.decode(responsePayload).toString();

      // Parse the worker's JSON response to get the clean message body
      JSONObject responseObject = new JSONObject(jsonResponse);
      if (responseObject.has("body")) {
        return responseObject.getString("body");
      }
      return jsonResponse;
    } catch (Exception e) {
      logger.log("Error invoking " + functionName + ": " + e.getMessage());
      return "Failed: " + e.getMessage();
    }
  }

  private boolean checkTokenWithVerifier(String email, String token, LambdaLogger logger) {
    try {
      // Prepare the JSON for the Verifier
      JSONObject verifyRequest = new JSONObject();
      verifyRequest.put("email", email);
      verifyRequest.put("token", token);

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(VERIFIER_URL))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(verifyRequest.toString()))
          .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      // If Verifier returns 200, it's valid. If 401, it's invalid.
      return response.statusCode() == 200;

    } catch (Exception e) {
      logger.log("Verifier Call Failed: " + e.getMessage());
      return false; // Fail safe
    }
  }

  private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(body)
        .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
  }

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

    // 1. Prepare Payload
    String userRequestBody = event.getBody();
    JSONObject workerPayloadJson = new JSONObject();
    workerPayloadJson.put("body", userRequestBody);
    String email = workerPayloadJson.getString("email");
    String token = workerPayloadJson.getString("token");
    if (!checkTokenWithVerifier(email, token, logger)) {
      return createResponse(401, "{\"error\": \"Invalid Token\"}");
    }

    String downstreamPayload = workerPayloadJson.toString();

    // 2. Execute Activities
    JSONObject results = new JSONObject();

    // Activity 1: Insert Key & Description into RDS
    logger.log("Activity 1: DB Insert");
    String dbResult = callLambda("LambdaAddPhotoDB", downstreamPayload, logger);
    results.put("Activity_1_Database", dbResult);

    // Activity 2: Upload Original to S3
    logger.log("Activity 2: Original Upload");
    String originalResult = callLambda("LambdaUploadObject", downstreamPayload, logger);
    results.put("Activity_2_Original_S3", originalResult);

    // Activity 3: Upload Resized to S3
    logger.log("Activity 3: Resize Upload");
    // We assume you create a new function named "LambdaResizeUpload"
    String resizeResult = callLambda("LambdaResizer", downstreamPayload, logger);
    results.put("Activity_3_Resize_S3", resizeResult);

    // 3. Return Combined Report
    return createResponse(200, results.toString());
    // return new APIGatewayProxyResponseEvent()
    // .withStatusCode(200)
    // .withHeaders(Map.of("Content-Type", "application/json"))
    // .withBody(results.toString(4)) // Pretty print JSON
    // .withIsBase64Encoded(false);
  }
}
