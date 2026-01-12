package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent; // Import added
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaAddPhotoDB
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // Configuration
  private static final String RDS_INSTANCE_HOSTNAME =
      "database-lam1303.cfk8w6wse6nw.ap-southeast-2.rds.amazonaws.com";
  private static final int RDS_INSTANCE_PORT = 3306;
  private static final String DB_USER = "cloud26"; // Ensure this user is set up for IAM Auth
  private static final Region AWS_REGION = Region.AP_SOUTHEAST_2;
  private static final String JDBC_URL =
      "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

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

    // 1. Parse the body passed down from the Orchestrator
    String requestBody = event.getBody();
    JSONObject bodyJSON = new JSONObject(requestBody);

    // Extract required fields
    // Default to empty string if missing to prevent crash
    String originalFileName = bodyJSON.optString("key", "unknown.jpg");
    String description = bodyJSON.optString("description", "No description provided");

    try {
      Class.forName("com.mysql.cj.jdbc.Driver");
      logger.log("Processing DB insert for file: " + originalFileName);

      // 2. Hash the filename for S3Key column
      String hashedKey = hashString(originalFileName);

      // 3. Connect and Insert
      Properties props = setMySqlConnectionProperties();
      try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, props)) {

        String sql = "INSERT INTO Photos (Description, S3Key) VALUES (?, ?)";

        try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
          st.setString(1, description); // User's Description
          st.setString(2, hashedKey); // Hashed Key
          st.executeUpdate();
          logger.log("Inserted row: " + description + " | " + hashedKey);
        }
      }

      // 4. Return JSON Success
      return createResponse(200, "Success: Row added to DB");

    } catch (Exception ex) {
      logger.log("Error: " + ex.toString());
      return createResponse(500, "Error adding to DB: " + ex.getMessage());
    }
  }

  // Helper to create standardized JSON response
  private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(message)
        .withIsBase64Encoded(false);
  }

  private static Properties setMySqlConnectionProperties() throws Exception {
    Properties mysqlConnectionProperties = new Properties();
    mysqlConnectionProperties.setProperty("useSSL", "true");
    mysqlConnectionProperties.setProperty("verifyServerCertificate", "false");
    mysqlConnectionProperties.setProperty("user", DB_USER);
    mysqlConnectionProperties.setProperty("password", generateAuthToken());
    return mysqlConnectionProperties;
  }

  private static String generateAuthToken() {
    RdsUtilities rdsUtilities = RdsUtilities.builder().region(AWS_REGION).build();
    return rdsUtilities.generateAuthenticationToken(
        GenerateAuthenticationTokenRequest.builder()
            .hostname(RDS_INSTANCE_HOSTNAME)
            .port(RDS_INSTANCE_PORT)
            .username(DB_USER)
            .region(AWS_REGION)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build());
  }

  private String hashString(String original) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] encodedhash = digest.digest(original.getBytes(StandardCharsets.UTF_8));
    StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
    for (int i = 0; i < encodedhash.length; i++) {
      String hex = Integer.toHexString(0xff & encodedhash[i]);
      if (hex.length() == 1) hexString.append('0');
      hexString.append(hex);
    }
    return hexString.toString();
  }
}
