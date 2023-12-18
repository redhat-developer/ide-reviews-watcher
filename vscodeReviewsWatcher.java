
//DEPS com.google.code.gson:gson:2.10.1
//DEPS com.segment.analytics.java:analytics:3.4.0
//SOURCES AbstractReviewsWatcher.java
//JAVA 17
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.io.IOException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.segment.analytics.messages.TrackMessage;
import com.segment.analytics.messages.TrackMessage.Builder;

public class vscodeReviewsWatcher extends AbstractReviewsWatcher {

    vscodeReviewsWatcher(String publisherId) {
        super(publisherId);
    }

    public static void main(String[] args) throws Exception {
        new vscodeReviewsWatcher("redhat").run();
    }

    void run() throws Exception {
        long start = System.currentTimeMillis();
        JsonObject publisher = getPublisherData(publisherId);
        var extensions = getExtensions(publisher);

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

    @Override
    Collection<JsonObject> filter(JsonArray latestReviews, JsonArray existingReviews) {
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

    @Override
    Builder toSegmentEvent(Context context, JsonObject review) {
        Map<String, Object> properties = new HashMap<>();
        String userDisplayName = review.get("userDisplayName").getAsString();
        properties.put("extension", "<https://marketplace.visualstudio.com/items?itemName=" + context.extensionId() + "|"
                + context.extensionId() + ">");
        properties.put("user", userDisplayName);
        String updatedDate = formatDate(review.get("updatedDate").getAsString());
        properties.put("date", updatedDate);
        String text = blockQuote(review.get("text").getAsString());
        properties.put("review", text);
        String rating = getStarEmojis(review.get("rating").getAsInt());
        properties.put("rating", rating);
        System.out.println(userDisplayName + " gave " + rating + " to " + context.extensionId());
        String userId = review.get("userId").getAsString();

        return TrackMessage.builder("review")
                .userId(userId)
                .properties(properties);
    }

    // Helper method to make an HTTP GET request and return the response as a String
    @Override
    JsonArray fetchReviews(Context context) throws Exception {
        String[] parts = context.extensionId().split("\\.");
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

    JsonObject getPublisherData(String publisherId) throws IOException, InterruptedException {
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

    private JsonArray getExtensions(JsonObject publisher) {
        JsonArray results = publisher.getAsJsonArray("results");
        if (results.isEmpty()) {
            return new JsonArray();
        }
        return results.get(0).getAsJsonObject().getAsJsonArray("extensions");
    }
}