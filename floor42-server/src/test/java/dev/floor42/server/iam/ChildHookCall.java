package dev.floor42.server.iam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** A child process that makes one hook call with the token it is given and prints "status body" (a Read call; the shell would mangle a JSON argument). */
public final class ChildHookCall {

    private ChildHookCall() {}

    public static void main(String[] args) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(args[0]))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + args[1])
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                "{\"tool_name\":\"Read\",\"tool_use_id\":\"child-1\",\"tool_input\":{\"file_path\":\"a.txt\"}}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println(response.statusCode() + " " + response.body());
    }
}
