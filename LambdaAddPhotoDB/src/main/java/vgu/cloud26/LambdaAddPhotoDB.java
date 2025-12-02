package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Properties;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaAddPhotoDB implements RequestHandler<S3Event, String> {

  // Configuration (Best practice: use System.getenv for flexibility)
  private static final String RDS_INSTANCE_HOSTNAME = "database-lam1303.cfk8w6wse6nw.ap-southeast-2.rds.amazonaws.com"; // Your
  // Host
  private static final int RDS_INSTANCE_PORT = 3306;
  private static final String DB_USER = "cloud26";
  private static final Region AWS_REGION = Region.AP_SOUTHEAST_2;

  // Connection URL (Same as before)
  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

  @Override
  public String handleRequest(S3Event event, Context context) {
    LambdaLogger logger = context.getLogger();

    try {
      Class.forName("com.mysql.cj.jdbc.Driver");

      for (S3EventNotificationRecord record : event.getRecords()) {

        String originalFileName = record.getS3().getObject().getKey();
        logger.log("Processing file: " + originalFileName);

        String hashedKey = hashString(originalFileName);

        Properties props = setMySqlConnectionProperties();
        try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, props)) {

          String sql = "INSERT INTO Photos (Description, S3Key) VALUES (?, ?)";

          try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
            st.setString(1, originalFileName);
            st.setString(2, hashedKey);
            st.executeUpdate();
            logger.log("Inserted row for: " + originalFileName);
          }
        }
      }
      return "Success";

    } catch (Exception ex) {
      logger.log("Error: " + ex.toString());
      return "Error";
    }
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
      if (hex.length() == 1)
        hexString.append('0');
      hexString.append(hex);
    }
    return hexString.toString();
  }
}
