package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaAddPhotoDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // --- CONFIGURATION ---
  private static final String RDS_INSTANCE_HOSTNAME = "database-lam1303.cfk8w6wse6nw.ap-southeast-2.rds.amazonaws.com";
  private static final int RDS_INSTANCE_PORT = 3306;
  private static final String DB_USER = "cloud26";
  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

  // VERIFIER CONFIG
  private final LambdaClient lambdaClient;
  private static final String VERIFIER_FUNCTION_NAME = "LambdaTokenVerifier";

  public LambdaAddPhotoDB() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

    if (event.getBody() != null && event.getBody().contains("warmer")) {
      return createResponse(200, "Warmed");
    }

    LambdaLogger logger = context.getLogger();

    try {
      String jsonString = event.getBody();
      JSONObject input = new JSONObject(jsonString);

      // 1. Basic Validation (We check if token exists, but don't need to extract it
      // into a variable)
      if (!input.has("email") || !input.has("token")) {
        return createResponse(400, "{\"error\": \"Missing email or token\"}");
      }

      String email = input.getString("email");
      String key = input.getString("key");
      String description = input.has("description") ? input.getString("description") : "No description";

      // 2. TOKEN VERIFICATION
      // We pass the RAW jsonString (which contains the token) to the Verifier
      JSONObject verifierPayload = new JSONObject();
      verifierPayload.put("body", jsonString);

      String verificationResult = callLambda(VERIFIER_FUNCTION_NAME, verifierPayload.toString(), logger);
      JSONObject verifyJson = new JSONObject(verificationResult);

      // BETTER ERROR HANDLING: Check for internal errors from Verifier
      if (verifyJson.has("error")) {
        return createResponse(500, "{\"error\": \"Verifier Failed: " + verifyJson.getString("error") + "\"}");
      }
      if (!verifyJson.has("valid") || !verifyJson.getBoolean("valid")) {
        return createResponse(401, "{\"error\": \"Unauthorized: Invalid Token\"}");
      }

      // 3. Connect to DB
      Class.forName("com.mysql.cj.jdbc.Driver");
      Connection conn = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());

      // 4. Insert Data
      String sql = "INSERT INTO Photos (S3Key, Description, Email) VALUES (?, ?, ?)";
      PreparedStatement pstmt = conn.prepareStatement(sql);
      pstmt.setString(1, key);
      pstmt.setString(2, description);
      pstmt.setString(3, email);

      pstmt.executeUpdate();
      conn.close();

      logger.log("DB Insert Successful for: " + key);
      return createResponse(200, "{\"message\": \"Metadata saved\"}");

    } catch (Exception e) {
      logger.log("DB Error: " + e.getMessage());
      // Return 200 so Orchestrator doesn't crash
      return createResponse(200, "{\"error\": \"DB Insert Failed: " + e.getMessage() + "\"}");
    }
  }

  // --- HELPER METHODS ---

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
    return new APIGatewayProxyResponseEvent().withStatusCode(statusCode).withBody(body);
  }

  private static Properties setMySqlConnectionProperties() throws Exception {
    Properties props = new Properties();
    props.setProperty("useSSL", "true");
    props.setProperty("user", DB_USER);
    props.setProperty("password", generateAuthToken());
    return props;
  }

  private static String generateAuthToken() {
    RdsUtilities rdsUtilities = RdsUtilities.builder().build();
    return rdsUtilities.generateAuthenticationToken(GenerateAuthenticationTokenRequest.builder()
        .hostname(RDS_INSTANCE_HOSTNAME)
        .port(RDS_INSTANCE_PORT)
        .username(DB_USER)
        .region(Region.AP_SOUTHEAST_2)
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build());
  }
}
