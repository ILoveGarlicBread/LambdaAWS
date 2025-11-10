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
import software.amazon.awssdk.services.s3.model.S3Exception;

public class LambdaDeleteObject
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {

    LambdaLogger logger = context.getLogger();
    logger.log("Received delete request for: " + request.getBody());

    // --- 1. Get the key from the request body (same as your LambdaGetObject) ---
    String requestBody = request.getBody();
    JSONObject bodyJSON = new JSONObject(requestBody);
    String key = bodyJSON.getString("key");

    // --- 2. Set the bucket name and region (same as your other Lambdas) ---
    String bucketName = "bucket-lam1303";
    Region region = Region.AP_SOUTHEAST_2;

    S3Client s3Client = S3Client.builder().region(region).build();

    // --- 3. Create the DeleteObjectRequest ---
    DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder().bucket(bucketName).key(key).build();

    JSONObject responseJson = new JSONObject();
    APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

    // --- 4. Call the deleteObject method and handle success/failure ---
    try {
      s3Client.deleteObject(deleteRequest);

      logger.log("Successfully deleted object: " + key + " from bucket: " + bucketName);
      responseJson.put("message", "Object deleted successfully: " + key);

      response.setStatusCode(200);
      response.setBody(responseJson.toString());

    } catch (S3Exception e) {
      logger.log("Error deleting object: " + e.getMessage());

      responseJson.put("error", e.getMessage());
      response.setStatusCode(500); // Internal Server Error
      response.setBody(responseJson.toString());
    }

    // --- 5. Return the response ---
    response.setHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
    return response;
  }
}
