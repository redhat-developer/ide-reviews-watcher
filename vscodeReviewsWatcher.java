
//DEPS com.google.code.gson:gson:2.10.1
//DEPS com.segment.analytics.java:analytics:3.4.0
//JAVA 17
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
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
import com.segment.analytics.messages.TrackMessage;
import com.segment.analytics.messages.TrackMessage.Builder;

public class vscodeReviewsWatcher {

    private String publisherId;
    private Analytics analytics;
    
    vscodeReviewsWatcher(String publisherId) {
        this.publisherId = publisherId;
        String segmentKey = System.getenv("SEGMENT_WRITE_KEY");
        if (segmentKey == null || segmentKey.isBlank()) {
            throw new IllegalArgumentException("The SEGMENT_WRITE_KEY environment variable is missing");
        }
        analytics = Analytics.builder(segmentKey).flushQueueSize(1).build();
    }

    void run() throws Exception {
        long start = System.currentTimeMillis();
        JsonObject publisher = getPublisherData(publisherId);
        var extensions = getExtensions(publisher);
        if (!Files.exists(reviewsPath)) {
            Files.createDirectories(reviewsPath);
        }
        extensions.asList().stream()
            .map(e -> {
                String id = e.getAsJsonObject().get("extensionName").getAsString();
                return new Context(publisherId+"."+id);
            })
            .forEach(this::checkReviews);

        analytics.shutdown();
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Done in "+elapsed+"ms");
    }

    private static final Path reviewsPath = Path.of("reviews");

    private static record Context(String extensionId) {
    };

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss")
            .withZone(ZoneOffset.UTC);


    public static void main(String[] args) throws Exception {
        new vscodeReviewsWatcher("redhat").run();
    }

    public void checkReviews(Context context) {
        try {
            // Fetch latest reviews
            var latestReviews = fetchReviews(context);

            // Load previous reviews
            var existingReviews = loadExistingReviews(context);

            // Save latest revews on disk
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

    private Collection<JsonObject> filter(JsonArray latestReviews, JsonArray existingReviews) {
        var newReviews = toJsonObjects(latestReviews);
        if (existingReviews == null || existingReviews.isEmpty()) {
            return newReviews;
        }
        Map<String, JsonObject> previousReviews = toJsonObjects(existingReviews).stream()
                .collect(Collectors.toMap(o -> o.get("id").getAsString(), Function.identity()));

        return newReviews.stream()
                .filter(o -> {
                    String id = o.get("id").getAsString();
                    return !previousReviews.containsKey(id);
                }).toList();
    }

    private static Collection<JsonObject> toJsonObjects(JsonArray jsonArray) {
        return jsonArray.asList().stream().map(JsonElement::getAsJsonObject).toList();
    }

    private void save(Context context, JsonArray lastestReviews) {
        Path filePath = getReviewFile(context);
        Gson gson = new Gson();
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            gson.toJson(lastestReviews, writer);
            System.out.println("Data saved to " + filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path getReviewFile(Context context) {
        return reviewsPath.resolve(context.extensionId + ".json");
    }

    private JsonArray loadExistingReviews(Context context) {
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

    private static String formatDate(String dateTime) {
        Instant instant = Instant.parse(dateTime);
        return formatter.format(instant);
    }

    static Builder toSegmentEvent(Context context, JsonObject review) {
        Map<String, Object> properties = new HashMap<>();
        String userDisplayName = review.get("userDisplayName").getAsString();
        properties.put("extension", "<https://marketplace.visualstudio.com/items?itemName=" + context.extensionId + "|"
                + context.extensionId + ">");
        properties.put("user", userDisplayName);
        String updatedDate = formatDate(review.get("updatedDate").getAsString());
        properties.put("date", updatedDate);
        properties.put("review", review.get("text").getAsString());
        String rating = getStarEmojis(review.get("rating").getAsInt());
        properties.put("rating", rating);
        System.out.println(userDisplayName + " gave " + rating + " to " + context.extensionId);
        String userId = review.get("userId").getAsString();

        return TrackMessage.builder("review")
                .userId(userId)
                .properties(properties);
    }

    // Helper method to make an HTTP GET request and return the response as a String
    private static JsonArray fetchReviews(Context context) throws IOException, InterruptedException {
        String[] parts = context.extensionId.split("\\.");
        String apiUrl = "https://marketplace.visualstudio.com/_apis/public/gallery/publishers/" + parts[0]
                + "/extensions/" + parts[1] + "/reviews?count=100&filterOptions=1";

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray reviews = json.getAsJsonArray("reviews");
        return reviews;
    }

    // Helper method to get star emojis based on the rating value
    private static String getStarEmojis(double rating) {
        int fullStars = (int) rating;
        StringBuilder stars = new StringBuilder();

        // Full stars
        for (int i = 0; i < fullStars; i++) {
            stars.append("⭐");
        }
        return stars.toString();
    }

    public static JsonObject getPublisherData(String publisherId) throws IOException, InterruptedException {
        String api = "https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery?api-version=6.0-preview.1";
        HttpClient httpClient = HttpClient.newHttpClient();
        
        JsonObject requestPayload = createRequest(publisherId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(api))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(buildRequestBody(requestPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json;
    }

    private static HttpRequest.BodyPublisher buildRequestBody(JsonObject requestBody) {
        // Convert the JsonObject to a byte array
        byte[] requestBodyBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.ofByteArray(requestBodyBytes);
    }

    private static JsonObject createRequest(String name) {
        JsonObject body = new JsonObject();
        JsonArray criteria = new JsonArray();
        criteria.add(createFilter(18, name));
        criteria.add(createFilter(8, "Microsoft.VisualStudio.Code"));
        criteria.add(createFilter(8, "Microsoft.VisualStudio.Services"));
        criteria.add(createFilter(8, "Microsoft.VisualStudio.Services.Cloud"));
        criteria.add(createFilter(8, "Microsoft.VisualStudio.Services.Integration"));
        criteria.add(createFilter(8, "Microsoft.VisualStudio.Services.Cloud.Integration"));
        criteria.add(createFilter(8, "Microsoft.VisualStudio.Services.Resource.Cloud"));
        criteria.add(createFilter(8, "Microsoft.TeamFoundation.Server"));
        criteria.add(createFilter(8, "Microsoft.TeamFoundation.Server.Integration"));
        criteria.add(createFilter(12, "37889"));

        JsonObject filter = new JsonObject();
        filter.add("criteria", criteria);
        filter.addProperty("sortBy", 4);
        filter.addProperty("pageSize", 200);
        filter.addProperty("pageNumber", 1);

        JsonArray filters = new JsonArray();
        filters.add(filter);
        body.add("filters", filters);

        JsonArray assetTypes = new JsonArray();
        assetTypes.add("Microsoft.VisualStudio.Services.Icons.Default");

        body.add("assetTypes", assetTypes);
        body.addProperty("flags", 866);
        return body;
    }

    private static JsonObject createFilter(int filterType, String value) {
        JsonObject filter = new JsonObject();
        filter.addProperty("filterType", filterType);
        filter.addProperty("value", value);
        return filter;
    }

    public static JsonArray getExtensions(JsonObject publisher) {
        JsonArray results = publisher.getAsJsonArray("results");
        if (results.isEmpty()) {
            return new JsonArray();
        }
        return results.get(0).getAsJsonObject().getAsJsonArray("extensions");
    }
}