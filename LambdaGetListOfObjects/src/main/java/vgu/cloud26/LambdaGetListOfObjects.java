package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

// Lambda Invoke Imports
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class LambdaGetListOfObjects
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final LambdaClient lambdaClient;
    // CHANGE THIS to your actual Verifier Function Name
    private static final String VERIFIER_FUNCTION_NAME = "LambdaTokenVerifier";

    public LambdaGetListOfObjects() {
        this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

        LambdaLogger logger = context.getLogger();

        if (event.getBody() != null && event.getBody().contains("warmer")) {
            logger.log("Warming event received. Exiting.");
            return createResponse(200, "Warmed");
        }

        try {
            // --- 1. SECURITY CHECK ---
            if (event.getBody() == null) {
                return createResponse(400, "{\"error\": \"Missing request body\"}");
            }

            JSONObject body = new JSONObject(event.getBody());

            if (!body.has("email") || !body.has("token")) {
                return createResponse(401, "{\"error\": \"Unauthorized: Missing email or token\"}");
            }

            // Prepare Payload for Verifier (Must look like an Event with a "body")
            JSONObject verifierPayload = new JSONObject();
            verifierPayload.put("body", event.getBody());

            // Invoke Verifier
            String verificationResult = callLambda(VERIFIER_FUNCTION_NAME, verifierPayload.toString(), logger);

            JSONObject verifyJson = new JSONObject(verificationResult);
            if (!verifyJson.has("valid") || !verifyJson.getBoolean("valid")) {
                return createResponse(401, "{\"error\": \"Unauthorized: Invalid Token\"}");
            }

            // --- 2. EXISTING S3 LOGIC (Only runs if token is valid) ---
            logger.log("Token valid. Listing objects...");

            String bucketName = "bucket-lam1303";

            S3Client s3Client = S3Client.builder()
                    .region(Region.AP_SOUTHEAST_2)
                    .build();

            ListObjectsRequest listObjects = ListObjectsRequest
                    .builder()
                    .bucket(bucketName)
                    .build();

            ListObjectsResponse res = s3Client.listObjects(listObjects);
            List<S3Object> objects = res.contents();

            JSONArray objArray = new JSONArray();

            for (S3Object object : objects) {
                JSONObject obj = new JSONObject();
                obj.put("key", object.key());
                obj.put("size", calKb(object.size()));
                objArray.put(obj);
            }

            return createResponse(200, objArray.toString());

        } catch (Exception e) {
            logger.log("Error: " + e.getMessage());
            return createResponse(500, "{\"error\": \"Server Error: " + e.getMessage() + "\"}");
        }
    }

    // --- REUSED CALL LAMBDA METHOD ---
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

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(body)
                .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
    }

    private static long calKb(Long val) {
        return val / 1024;
    }
}
