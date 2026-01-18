package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
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

    // Configuration
    private static final String RDS_INSTANCE_HOSTNAME = "database-lam1303.cfk8w6wse6nw.ap-southeast-2.rds.amazonaws.com";
    private static final int RDS_INSTANCE_PORT = 3306;
    private static final String DB_USER = "cloud26";
    private static final Region AWS_REGION = Region.AP_SOUTHEAST_2;
    private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT
            + "/Cloud26";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

        // Warmer Check
        if (event.getBody() != null && event.getBody().contains("warmer")) {
            return new APIGatewayProxyResponseEvent().withStatusCode(200).withBody("Warmed");
        }

        LambdaLogger logger = context.getLogger();

        try {
            // 1. Parse Input
            String requestBody = event.getBody();
            JSONObject bodyJSON = new JSONObject(requestBody);

            // ERROR WAS HERE: We need the ORIGINAL key, not a hash
            String key = bodyJSON.getString("key");

            logger.log("Processing Delete DB for key: " + key);
            Class.forName("com.mysql.cj.jdbc.Driver");

            // 2. Connect and Delete
            Properties props = setMySqlConnectionProperties();
            try (Connection mySQLClient = DriverManager.getConnection(JDBC_URL, props)) {

                String sql = "DELETE FROM Photos WHERE S3Key = ?";

                try (PreparedStatement st = mySQLClient.prepareStatement(sql)) {
                    // USE THE PLAIN KEY
                    st.setString(1, key);

                    int rowsAffected = st.executeUpdate();

                    if (rowsAffected > 0) {
                        logger.log("Deleted row for: " + key);
                        return createResponse(200, "{\"message\": \"Success: Row deleted from DB\"}");
                    } else {
                        logger.log("Row not found for: " + key);
                        // We return 200 even if not found, to keep the orchestrator happy
                        return createResponse(200, "{\"message\": \"Warning: Row not found in DB\"}");
                    }
                }
            }

        } catch (Exception ex) {
            logger.log("Error: " + ex.toString());
            return createResponse(500, "{\"error\": \"Error deleting from DB: " + ex.getMessage() + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withBody(body)
                .withHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"))
                .withIsBase64Encoded(false);
    }

    // --- Helper Methods ---
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
}
