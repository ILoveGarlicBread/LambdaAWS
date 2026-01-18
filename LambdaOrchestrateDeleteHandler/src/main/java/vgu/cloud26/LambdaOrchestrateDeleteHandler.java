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

public class LambdaOrchestrateDeleteHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final LambdaClient lambdaClient;
    // 1. CONSTANT FOR VERIFIER
    private static final String VERIFIER_FUNCTION_NAME = "LambdaTokenVerifier";

    public LambdaOrchestrateDeleteHandler() {
        this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

        // Warmer
        if (event.getBody() != null && event.getBody().contains("warmer")) {
            return createResponse(200, "Warmed");
        }

        LambdaLogger logger = context.getLogger();
        logger.log("Starting Delete Orchestration...");

        try {
            // 2. PARSE & VALIDATE INPUT
            String userRequestBody = event.getBody();
            JSONObject userJson = new JSONObject(userRequestBody);

            if (!userJson.has("email") || !userJson.has("token")) {
                return createResponse(400, "{\"error\": \"Missing email or token\"}");
            }

            // 3. VERIFY TOKEN
            JSONObject verifierPayload = new JSONObject();
            verifierPayload.put("body", userRequestBody); // Pass email/token/key to verifier

            String verificationResult = callLambda("LambdaTokenVerifier", verifierPayload.toString(), logger);

            // Check Verifier Result
            JSONObject verifyJson = new JSONObject(verificationResult);
            if (verifyJson.has("error")) {
                return createResponse(500, "{\"error\": \"Verifier Error: " + verifyJson.getString("error") + "\"}");
            }
            if (!verifyJson.has("valid") || !verifyJson.getBoolean("valid")) {
                return createResponse(401, "{\"error\": \"Unauthorized: Invalid Token\"}");
            }

            // 4. EXECUTE DELETE ACTIVITIES
            // We pass the same payload (which contains "key") to the workers
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
            String originalResult = callLambda("LambdaDeleteObject", downstreamPayload, logger);
            results.put("Activity_2_Original_Delete", originalResult);

            // Activity 3: Delete Resized from S3
            logger.log("Activity 3: Deleting Resized S3");
            String resizedResult = callLambda("LambdaDeleteResizedObject", downstreamPayload, logger);
            results.put("Activity_3_Resized_Delete", resizedResult);

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
            return "{\"error\": \"Invocation Failed: " + e.getMessage() + "\"}";
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(body)
                .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"))
                .withIsBase64Encoded(false);
    }
}
