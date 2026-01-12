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

public class LambdaOrchestrateDeleteHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final LambdaClient lambdaClient;

    public LambdaOrchestrateDeleteHandler() {
        this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
    }

    // Helper to invoke worker Lambdas
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

            // Extract "message" or "body" from worker response
            JSONObject responseObject = new JSONObject(jsonResponse);
            if (responseObject.has("body")) {
                // If the body is a JSON string, try to parse it to get a cleaner message
                String bodyStr = responseObject.getString("body");
                try {
                     JSONObject bodyObj = new JSONObject(bodyStr);
                     if(bodyObj.has("message")) return bodyObj.getString("message");
                } catch(Exception e) { /* Not JSON */ }
                return bodyStr;
            }
            return jsonResponse;

        } catch (Exception e) {
            logger.log("Error invoking " + functionName + ": " + e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        if (event.getBody() != null && event.getBody().contains("warmer")) {
    context.getLogger().log("Warming event received. Exiting.");
    return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody("Warmed");
}
        LambdaLogger logger = context.getLogger();
        logger.log("Starting Delete Orchestration...");

        // 1. Get Key from User Request
        String userRequestBody = event.getBody(); 
        // Expected format: { "key": "dog.jpg" }
        
        // 2. Wrap payload for workers (simulating API Gateway event structure)
        JSONObject workerPayloadJson = new JSONObject();
        workerPayloadJson.put("body", userRequestBody);
        String downstreamPayload = workerPayloadJson.toString();

        JSONObject results = new JSONObject();

        // Activity 1: Delete from DB
        logger.log("Activity 1: Deleting from DB");
        String dbResult = callLambda("LambdaDeletePhotoDB", downstreamPayload, logger);
        results.put("Activity_1_DB_Delete", dbResult);

        // Activity 2: Delete Original from S3
        logger.log("Activity 2: Deleting Original S3");
        // Reuse your existing LambdaDeleteObject
        String originalResult = callLambda("LambdaDeleteObject", downstreamPayload, logger);
        results.put("Activity_2_Original_Delete", originalResult);

        // Activity 3: Delete Resized from S3
        logger.log("Activity 3: Deleting Resized S3");
        String resizedResult = callLambda("LambdaDeleteResizedObject", downstreamPayload, logger);
        results.put("Activity_3_Resized_Delete", resizedResult);

        // 3. Return Combined Report
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(results.toString(4))
                .withIsBase64Encoded(false);
    }
}
