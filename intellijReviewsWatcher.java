//DEPS com.google.code.gson:gson:2.10.1
//DEPS com.segment.analytics.java:analytics:3.4.0
//SOURCES AbstractReviewsWatcher.java
//JAVA 17
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.segment.analytics.messages.TrackMessage;
import com.segment.analytics.messages.TrackMessage.Builder;

public class intellijReviewsWatcher extends AbstractReviewsWatcher {


    intellijReviewsWatcher(String publisherId) {
        super(publisherId);
    }

    public static void main(String[] args) throws Exception {
        new intellijReviewsWatcher("Red-Hat").run();
    }

    void run() throws Exception {
        long start = System.currentTimeMillis();
        var extensions = getExtensions(publisherId);
        extensions.asList().stream()
            .map(e -> {
                String id = e.getAsJsonObject().get("id").getAsString();
                return new Context(id);
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
         /*
      {
    "id": 90008,
    "cdate": "1694785746000",
    "comment": "<p>Great tool for great framewrok</p>\n",
    "plugin": {
      "id": 13234,
      "name": "Quarkus Tools",
      "link": "/plugin/13234-quarkus-tools"
    },
    "rating": 5,
    "repliesCount": 0,
    "author": {
      "id": "ab5b4050-b4fb-409d-9bbd-6fa5cb2de378",
      "name": "Cedric Thiebault",
      "link": "/author/ab5b4050-b4fb-409d-9bbd-6fa5cb2de378",
      "hubLogin": "cthiebault",
      "icon": "https://hub.jetbrains.com/api/rest/avatar/ab5b4050-b4fb-409d-9bbd-6fa5cb2de378"
    },
    "vendor": false,
    "votes": {
      "positive": 0,
      "negative": 0
    },
    "markedAsSpam": false
  },
     */
        Map<String, Object> properties = new HashMap<>();
        JsonObject author = review.getAsJsonObject("author");
        String userDisplayName = author.get("name").getAsString();
        JsonObject plugin = review.getAsJsonObject("plugin");
        String pluginName = plugin.get("name").getAsString();
        properties.put("extension", "<https://plugins.jetbrains.com" + plugin.get("link").getAsString() + "|"
                + pluginName + ">");
        properties.put("user", userDisplayName);
        String updatedDate = formatDate(new Date(review.get("cdate").getAsLong()));
        properties.put("date", updatedDate);
        String text = blockQuote(review.get("comment").getAsString().replace("<p>","").replace("</p>",""));
        properties.put("review", text);
        String rating = getStarEmojis(review.get("rating").getAsInt());
        properties.put("rating", rating);
        System.out.println(userDisplayName + " gave " + rating + " to " + context.extensionId());
        String userId = author.get("id").getAsString();

        return TrackMessage.builder("review")
                .userId(userId)
                .properties(properties);
    }

    // Helper method to make an HTTP GET request and return the response as a String
    @Override
    JsonArray fetchReviews(Context context) throws Exception {
        String apiUrl = "https://plugins.jetbrains.com/api/plugins/"+context.extensionId()+"/comments";
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonArray();
    }

    JsonArray getExtensions(String publisherId) throws IOException, InterruptedException {
        String api = "https://plugins.jetbrains.com/api/vendors/"+publisherId+"/plugins?families=intellij%2Cteamcity%2Chub%2Cfleet%2Cdotnet%2Cspace%2Ctoolbox&page=1&size=100";
        HttpClient httpClient = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(api))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonArray json = JsonParser.parseString(response.body()).getAsJsonArray();
        return json;
    }
}