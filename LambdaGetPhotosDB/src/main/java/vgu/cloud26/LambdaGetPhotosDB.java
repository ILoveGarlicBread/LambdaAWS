package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest;

public class LambdaGetPhotosDB
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final String RDS_INSTANCE_HOSTNAME = "database-lam1303.cfk8w6wse6nw.ap-southeast-2.rds.amazonaws.com";

  private static final int RDS_INSTANCE_PORT = 3306;

  private static final String DB_USER = "cloud26";

  private static final String JDBC_URL = "jdbc:mysql://" + RDS_INSTANCE_HOSTNAME + ":" + RDS_INSTANCE_PORT + "/Cloud26";

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

    JSONArray items = new JSONArray();

    try {

      Class.forName("com.mysql.cj.jdbc.Driver");

      Connection mySQLClient = DriverManager.getConnection(JDBC_URL, setMySqlConnectionProperties());

      // PreparedStatement st = mySQLClient.prepareStatement("SELECT 1");

      // st.execute();

      // result = "Success!";

      PreparedStatement st = mySQLClient.prepareStatement("SELECT * FROM Photos");

      ResultSet rs = st.executeQuery();

      while (rs.next()) {

        JSONObject item = new JSONObject();

        item.put("ID", rs.getInt("ID"));

        item.put("Description", rs.getString("Description"));

        item.put("S3Key", rs.getString("S3Key"));

        items.put(item);
      }

    } catch (ClassNotFoundException ex) {

      logger.log(ex.toString());

    } catch (Exception ex) {

      logger.log(ex.toString());
    }

    String encodedResult = Base64.getEncoder().encodeToString(items.toString().getBytes());

    APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

    response.setStatusCode(200);

    response.setBody(encodedResult);

    response.withIsBase64Encoded(true);

    response.setHeaders(java.util.Collections.singletonMap("Content-Type", "application/json"));

    return response;
  }

  private static Properties setMySqlConnectionProperties() throws Exception {

    Properties mysqlConnectionProperties = new Properties();

    mysqlConnectionProperties.setProperty("useSSL", "true");

    mysqlConnectionProperties.setProperty("user", DB_USER);

    mysqlConnectionProperties.setProperty("password", generateAuthToken()); // HERE

    return mysqlConnectionProperties;
  }

  private static String generateAuthToken() throws Exception {

    RdsUtilities rdsUtilities = RdsUtilities.builder().build();

    // Generate the authentication token

    String authToken = rdsUtilities.generateAuthenticationToken(
        GenerateAuthenticationTokenRequest.builder()
            .hostname(RDS_INSTANCE_HOSTNAME)
            .port(RDS_INSTANCE_PORT)
            .username(DB_USER)
            .region(Region.AP_SOUTHEAST_2)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build());

    return authToken;
  }
}
