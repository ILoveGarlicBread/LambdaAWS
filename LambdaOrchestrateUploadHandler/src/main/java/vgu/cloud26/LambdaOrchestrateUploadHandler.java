package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

public class LambdaOrchestrateUploadHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private final LambdaClient lambdaClient;
  // CHANGE THIS to your actual Verifier Function Name (not the URL)
  private static final String VERIFIER_FUNCTION_NAME = "LambdaTokenVerifier";

  public LambdaOrchestrateUploadHandler() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context context) {

    if (event.getBody() != null && event.getBody().contains("warmer")) {
      context.getLogger().log("Warming event received. Exiting.");
      return createResponse(200, "Warmed");
    }

    LambdaLogger logger = context.getLogger();
    logger.log("DEBUG INCOMING BODY: " + event.getBody());

    try {
      String userRequestBody = event.getBody();
      JSONObject userJson = new JSONObject(userRequestBody);

      if (!userJson.has("email") || !userJson.has("token")) {
        return createResponse(400, "{\"error\": \"Missing email or token in request\"}");
      }

      // 1. VERIFY TOKEN (Using callLambda instead of HTTP)
      // We wrap the body in another JSON object because the Verifier expects an event
      // with a "body" field
      JSONObject verifierPayload = new JSONObject();
      verifierPayload.put("body", userRequestBody);

      String verificationResult = callLambda(VERIFIER_FUNCTION_NAME, verifierPayload.toString(), logger);

      // Parse the verification result (It returns JSON like {"valid": true})
      JSONObject verifyJson = new JSONObject(verificationResult);
      if (!verifyJson.has("valid") || !verifyJson.getBoolean("valid")) {
        return createResponse(401, "{\"error\": \"Invalid Token\"}");
      }

      // 2. PREPARE PAYLOAD FOR WORKERS
      JSONObject workerPayloadJson = new JSONObject();
      workerPayloadJson.put("body", userRequestBody);
      String downstreamPayload = workerPayloadJson.toString();

      // 3. EXECUTE ACTIVITIES
      JSONObject results = new JSONObject();

      logger.log("Activity 1: DB Insert");
      String dbResult = callLambda("LambdaAddPhotoDB", downstreamPayload, logger);
      results.put("Activity_1_Database", dbResult);

      logger.log("Activity 2: Original Upload");
      String originalResult = callLambda("LambdaUploadObject", downstreamPayload, logger);
      results.put("Activity_2_Original_S3", originalResult);

      logger.log("Activity 3: Resize Upload");
      String resizeResult = callLambda("LambdaResizer", downstreamPayload, logger);
      results.put("Activity_3_Resize_S3", resizeResult);

      return createResponse(200, results.toString());

    } catch (Exception e) {
      logger.log("Orchestrator Error: " + e.getMessage());
      return createResponse(500, "{\"error\": \"Orchestrator Failed: " + e.getMessage() + "\"}");
    }
  }

  // --- HELPERS ---

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

      // Parse the worker's JSON response (APIGatewayProxyResponseEvent structure)
      // We want to return the inner "body" string
      JSONObject responseObject = new JSONObject(jsonResponse);
      if (responseObject.has("body")) {
        return responseObject.getString("body");
      }
      return jsonResponse;
    } catch (Exception e) {
      logger.log("Error invoking " + functionName + ": " + e.getMessage());
      // Return a valid JSON error structure so parsing doesn't fail
      return "{\"error\": \"Invocation Failed: " + e.getMessage() + "\"}";
    }
  }

  private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(body)
        .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
  }
}
