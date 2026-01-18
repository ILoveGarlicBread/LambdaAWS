package vgu.cloud26;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class LambdaResizer
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  // Configuration
  private static final String RESIZED_BUCKET_NAME = "resizebucket-lam1303"; // Update this to your actual resize
  // bucket name
  private static final float MAX_DIMENSION = 100;
  private final String REGEX = ".*\\.([^\\.]*)";
  private final String JPG_TYPE = "jpg";
  private final String JPG_MIME = "image/jpeg";
  private final String PNG_TYPE = "png";
  private final String PNG_MIME = "image/png";

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
    logger.log("Resize Worker Started.");

    try {
      // 1. Parse Data from Orchestrator
      String requestBody = event.getBody();
      JSONObject bodyJSON = new JSONObject(requestBody);

      String originalKey = bodyJSON.getString("key");
      String contentBase64 = bodyJSON.getString("content");

      // Generate new filename
      String dstKey = "resized-" + originalKey;

      // 2. Infer Image Type
      Matcher matcher = Pattern.compile(REGEX).matcher(originalKey);
      if (!matcher.matches()) {
        return createResponse(400, "Error: Unable to infer image type for key " + originalKey);
      }
      String imageType = matcher.group(1).toLowerCase();
      if (!(JPG_TYPE.equals(imageType)) && !(PNG_TYPE.equals(imageType))) {
        return createResponse(400, "Error: Skipping non-image " + originalKey);
      }

      // 3. Decode Base64 to InputStream (Memory)
      byte[] imageBytes = Base64.getDecoder().decode(contentBase64);
      ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes);

      // 4. Resize Logic (Your Custom Logic)
      BufferedImage srcImage = ImageIO.read(inputStream);
      if (srcImage == null) {
        return createResponse(400, "Error: Could not read image data.");
      }
      BufferedImage newImage = resizeImage(srcImage);

      // 5. Re-encode image to bytes
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      ImageIO.write(newImage, imageType, outputStream);
      byte[] resizedBytes = outputStream.toByteArray();

      // 6. Upload to Resized Bucket
      S3Client s3Client = S3Client.builder().region(Region.AP_SOUTHEAST_2).build();
      uploadToS3(s3Client, resizedBytes, RESIZED_BUCKET_NAME, dstKey, imageType, logger);

      return createResponse(200, "Success: Resized and uploaded " + dstKey);

    } catch (Exception e) {
      logger.log("Error resizing: " + e.getMessage());
      e.printStackTrace();
      return createResponse(500, "Error resizing: " + e.getMessage());
    }
  }

  // --- Helper Methods ---

  private void uploadToS3(
      S3Client s3Client,
      byte[] data,
      String bucket,
      String key,
      String imageType,
      LambdaLogger logger) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("Content-Length", Integer.toString(data.length));

    if (JPG_TYPE.equals(imageType)) {
      metadata.put("Content-Type", JPG_MIME);
    } else if (PNG_TYPE.equals(imageType)) {
      metadata.put("Content-Type", PNG_MIME);
    }

    PutObjectRequest putRequest = PutObjectRequest.builder().bucket(bucket).key(key).metadata(metadata).build();

    logger.log("Writing to: " + bucket + "/" + key);
    s3Client.putObject(putRequest, RequestBody.fromBytes(data));
  }

  // (Kept your exact resizing logic)
  private BufferedImage resizeImage(BufferedImage srcImage) {
    int srcHeight = srcImage.getHeight();
    int srcWidth = srcImage.getWidth();
    float scalingFactor = Math.min(MAX_DIMENSION / srcWidth, MAX_DIMENSION / srcHeight);
    int width = (int) (scalingFactor * srcWidth);
    int height = (int) (scalingFactor * srcHeight);

    BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = resizedImage.createGraphics();
    graphics.setPaint(Color.white);
    graphics.fillRect(0, 0, width, height);
    graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    graphics.drawImage(srcImage, 0, 0, width, height, null);
    graphics.dispose();
    return resizedImage;
  }

  private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withBody(message)
        .withIsBase64Encoded(false);
  }
}
