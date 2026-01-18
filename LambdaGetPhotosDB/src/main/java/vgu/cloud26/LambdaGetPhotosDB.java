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
import java.sql.ResultSet;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaGetPhotosDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // DB CONFIG
  private static final String RDS_INSTANCE_HOSTNAME = "database-lam1303.cfk8w6wse6nw.ap-southeast-2.rds.amazonaws.com";
  private static final int RDS_INSTANCE_PORT = 3306;
  private static final String DB_USER = "cloud26";
  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

  // VERIFIER CONFIG
  private final LambdaClient lambdaClient;
  private static final String VERIFIER_FUNCTION_NAME = "LambdaTokenVerifier";

  public LambdaGetPhotosDB() {
    this.lambdaClient = LambdaClient.builder().region(Region.AP_SOUTHEAST_2).build();
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

    // Warmer Check
    if (event.getBody() != null && event.getBody().contains("warmer")) {
      return createResponse(200, "Warmed");
    }

    LambdaLogger logger = context.getLogger();
    JSONArray items = new JSONArray();

    try {
      // --- 1. SECURITY CHECK ---
      if (event.getBody() == null) {
        return createResponse(400, "{\"error\": \"Missing request body\"}");
      }

      JSONObject body = new JSONObject(event.getBody());

      // Check input existence
      if (!body.has("email") || !body.has("token")) {
        return createResponse(401, "{\"error\": \"Unauthorized: Missing email or token\"}");
      }

      // Call Verifier
      JSONObject verifierPayload = new JSONObject();
      verifierPayload.put("body", event.getBody()); // Pass the whole body to verifier

      String verificationResult = callLambda(VERIFIER_FUNCTION_NAME, verifierPayload.toString(), logger);
      JSONObject verifyJson = new JSONObject(verificationResult);

      if (verifyJson.has("error")) {
        return createResponse(500, "{\"error\": \"Verifier Error: " + verifyJson.getString("error") + "\"}");
      }
      if (!verifyJson.has("valid") || !verifyJson.getBoolean("valid")) {
        return createResponse(401, "{\"error\": \"Unauthorized: Invalid Token\"}");
      }

      // --- 2. DATABASE QUERY (Only runs if token is valid) ---
      Class.forName("com.mysql.cj.jdbc.Driver");
      Connection mySQLClient = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());

      PreparedStatement st = mySQLClient.prepareStatement("SELECT * FROM Photos");
      ResultSet rs = st.executeQuery();

      while (rs.next()) {
        JSONObject item = new JSONObject();
        item.put("key", rs.getString("S3Key"));
        item.put("description", rs.getString("Description"));

        String email = rs.getString("Email");
        item.put("email", (email == null) ? "Unknown" : email);

        items.put(item);
      }
      mySQLClient.close();

      return createResponse(200, items.toString());

    } catch (Exception ex) {
      logger.log("Error: " + ex.toString());
      return createResponse(500, "{\"error\": \"Server Error: " + ex.getMessage() + "\"}");
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
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(body)
        .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));
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
