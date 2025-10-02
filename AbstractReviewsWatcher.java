
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Date;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.segment.analytics.Analytics;
import com.segment.analytics.messages.TrackMessage.Builder;

public abstract class AbstractReviewsWatcher {

    protected Analytics analytics;
    protected String publisherId;

    AbstractReviewsWatcher(String publisherId) {
        this.publisherId = publisherId;
        String segmentKey = System.getenv("SEGMENT_WRITE_KEY");
        if (segmentKey == null || segmentKey.isBlank()) {
            throw new IllegalArgumentException("The SEGMENT_WRITE_KEY environment variable is missing");
        }
        analytics = Analytics.builder(segmentKey).flushQueueSize(1).build();
        if (!Files.exists(reviewsPath)) {
            try {
                Files.createDirectories(reviewsPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static final Path reviewsPath = Path.of("reviews");

    static record Context(String extensionId) {
    };

    static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    public void checkReviews(Context context) {
        try {
            // Fetch latest reviews
            var latestReviews = fetchReviews(context);

            // Load previous reviews
            var existingReviews = loadExistingReviews(context);

            // Save latest reviews on disk
            save(context, latestReviews);

            if (existingReviews == null) {
                //Never saved before
                return;
            }

            //Find new reviews
            Collection<JsonObject> newReviews = filter(latestReviews, existingReviews);
            if (newReviews.isEmpty()) {
                System.out.println("No new review for " + context.extensionId);
                return;
            }

            // Send new reviews to segment
            newReviews.stream()
                    .map(review -> toSegmentEvent(context, review))
                    .forEach(analytics::enqueue);

            analytics.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    abstract JsonArray fetchReviews(Context context) throws Exception;

    abstract Collection<JsonObject> filter(JsonArray latestReviews, JsonArray existingReviews);

    protected Collection<JsonObject> toJsonObjects(JsonArray jsonArray) {
        return jsonArray.asList().stream().map(JsonElement::getAsJsonObject).toList();
    }

    protected void save(Context context, JsonArray lastestReviews) {
        Path filePath = getReviewFile(context);
        var gson = new Gson();
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            gson.toJson(lastestReviews, writer);
            System.out.println("Data saved to " + filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected Path getReviewFile(Context context) {
        return reviewsPath.resolve(context.extensionId + ".json");
    }

    protected JsonArray loadExistingReviews(Context context) {
        Path filePath = getReviewFile(context);
        if (!Files.exists(filePath)) {
            return null;
        }
        try (FileReader reader = new FileReader(filePath.toFile())) {
            // Parse the JSON file into a JsonElement
            JsonElement jsonElement = JsonParser.parseReader(reader);
            // Ensure the parsed element is a JsonArray
            if (jsonElement.isJsonArray()) {
                var jsonArray = jsonElement.getAsJsonArray();
                System.out.println("Loaded "+jsonArray.size()+" reviews from " + filePath);
                return jsonArray;
            } else {
                // Handle the case when the root element is not a JsonArray
                throw new IllegalStateException("The root element is not a JsonArray");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Return null if something goes wrong
        return null;
    }

    protected String formatDate(String dateTime) {
        var instant = Instant.parse(dateTime);
        return formatter.format(instant);
    }
    protected String formatDate(Date date) {
        return formatter.format(date.toInstant());
    }

    abstract Builder toSegmentEvent(Context context, JsonObject review);

    protected String blockQuote(String text) {
        return Arrays.stream(text.split("\n"))
                .map(line -> "> " + line)
                .collect(Collectors.joining("\n"));
    }

    // Helper method to get star emojis based on the rating value
    protected String getStarEmojis(double rating) {
        int fullStars = (int) rating;
        if (fullStars == 0) {
            // No rating => neutral face
            return "😐";
        }
        var stars = new StringBuilder();

        // Full stars
        for (int i = 0; i < fullStars; i++) {
            stars.append("⭐");
        }
        return stars.toString();
    }

    protected HttpRequest.BodyPublisher buildRequestBody(JsonObject requestBody) {
        // Convert the JsonObject to a byte array
        byte[] requestBodyBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofByteArray(requestBodyBytes);
    }

}