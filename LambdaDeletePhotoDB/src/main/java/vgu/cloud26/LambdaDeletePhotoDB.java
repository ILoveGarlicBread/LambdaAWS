package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
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

public class LambdaDeletePhotoDB implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    // Configuration (Same as AddPhotoDB)
    private static final String RDS_INSTANCE_HOSTNAME = "database-lam1303.cfk8w6wse6nw.ap-southeast-2.rds.amazonaws.com";
    private static final int RDS_INSTANCE_PORT = 3306;
    private static final String DB_USER = "cloud26";
    private static final Region AWS_REGION = Region.AP_SOUTHEAST_2;
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        if (event.getBody() != null && event.getBody().contains("warmer")) {
    context.getLogger().log("Warming event received. Exiting.");
    return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody("Warmed");
}
        LambdaLogger logger = context.getLogger();

        try {
            // 1. Parse Input
            String requestBody = event.getBody();
            JSONObject bodyJSON = new JSONObject(requestBody);
            String originalFileName = bodyJSON.getString("key");

            logger.log("Processing Delete DB for file: " + originalFileName);
            Class.forName("com.mysql.cj.jdbc.Driver");

            // 2. Hash the filename (to find the S3Key)
            String hashedKey = hashString(originalFileName);

            // 3. Connect and Delete
            Properties props = setMySqlConnectionProperties();
            try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, props)) {
                
                String sql = "DELETE FROM Photos WHERE S3Key = ?";
                
                try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
                    st.setString(1, hashedKey);
                    int rowsAffected = st.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        return createResponse(200, "Success: Row deleted from DB");
                    } else {
                        return createResponse(404, "Warning: Row not found in DB");
                    }
                }
            }

        } catch (Exception ex) {
            logger.log("Error: " + ex.toString());
            return createResponse(500, "Error deleting from DB: " + ex.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(message)
                .withIsBase64Encoded(false);
    }

    // --- Helper Methods (Same as AddPhotoDB) ---
    private static Properties setMySqlConnectionProperties() {
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
