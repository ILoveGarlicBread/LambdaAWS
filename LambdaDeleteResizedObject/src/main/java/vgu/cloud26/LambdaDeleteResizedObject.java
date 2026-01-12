package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.json.JSONObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

public class LambdaDeleteResizedObject implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Update with your actual Resized Bucket Name
    private static final String RESIZED_BUCKET_NAME = "resizebucket-lam1303"; 

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) { // <--- Renamed to 'event'
        
        // --- WARMER CHECK (Now works because variable is 'event') ---
        if (event.getBody() != null && event.getBody().contains("warmer")) {
            context.getLogger().log("Warming event received. Exiting.");
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("Warmed");
        }
        // ------------------------------------------------------------

        LambdaLogger logger = context.getLogger();
        
        try {
            // 1. Parse Input (Updated 'request' to 'event')
            String requestBody = event.getBody(); 
            JSONObject bodyJSON = new JSONObject(requestBody);
            String originalKey = bodyJSON.getString("key");

            // 2. Calculate Resized Key
            String resizedKey = "resized-" + originalKey;
            logger.log("Deleting resized image: " + resizedKey);

            // 3. Delete from S3
            S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_2).build();
            
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(RESIZED_BUCKET_NAME)
                    .key(resizedKey)
                    .build();

            s3Client.deleteObject(deleteRequest);

            return createResponse(200, "Success: Deleted " + resizedKey);

        } catch (Exception e) {
            logger.log("Error deleting resized object: " + e.getMessage());
            return createResponse(500, "Error: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(message)
                .withIsBase64Encoded(false);
    }
}
