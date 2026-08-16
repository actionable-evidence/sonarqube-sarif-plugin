package com.sonar.sarifexport.sarif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.sonar.api.server.ws.LocalConnector;
import org.sonarqube.ws.client.GetRequest;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.WsClientFactories;
import org.sonarqube.ws.client.WsResponse;

/**
 * Reads issues (and, optionally, rule metadata) from the SonarQube instance
 * this plugin is running in.
 *
 * <p>Calls go through {@link org.sonar.api.server.ws.LocalConnector}: they are
 * in-process (no HTTP round trip) and are executed under the permissions of
 * whoever called our own /api/sarif/export action, so a caller only ever
 * gets issues from projects they can browse.
 */
public class IssuesFetcher {

  private static final int PAGE_SIZE = 500;
  private static final String PAGING_FIELD = "paging";

  private final WsClient wsClient;
  private final ObjectMapper mapper = new ObjectMapper();

  public IssuesFetcher(LocalConnector localConnector) {
    this.wsClient = WsClientFactories.getLocal().newClient(localConnector);
  }

  /**
   * Fetches every issue matching the given filters, transparently paging
   * through api/issues/search.
   */
  public List<JsonNode> fetchAllIssues(String projectKey, String branch, String pullRequest, String statuses) {
    List<JsonNode> allIssues = new ArrayList<>();
    int page = 1;

    while (true) {
      GetRequest getRequest = buildIssuesSearchRequest(projectKey, branch, pullRequest, statuses, page);

      WsResponse wsResponse = wsClient.wsConnector().call(getRequest);
      if (wsResponse.code() != 200) {
        throw new IllegalStateException(
          "api/issues/search returned HTTP " + wsResponse.code() + ": " + wsResponse.content());
      }

      JsonNode root = parse(wsResponse.content());
      root.withArray("issues").forEach(allIssues::add);

      if (isLastPage(root, page, allIssues.size())) {
        break;
      }
      page = root.path(PAGING_FIELD).path("pageIndex").asInt(page) + 1;
    }

    return allIssues;
  }

  private GetRequest buildIssuesSearchRequest(
    String projectKey, String branch, String pullRequest, String statuses, int page) {
    GetRequest getRequest = new GetRequest("api/issues/search")
      .setParam("componentKeys", projectKey)
      .setParam("p", String.valueOf(page))
      .setParam("ps", String.valueOf(PAGE_SIZE))
      .setMediaType("application/json");

    if (branch != null && !branch.isEmpty()) {
      getRequest.setParam("branch", branch);
    }
    if (pullRequest != null && !pullRequest.isEmpty()) {
      getRequest.setParam("pullRequest", pullRequest);
    }
    if (statuses != null && !statuses.isEmpty()) {
      getRequest.setParam("statuses", statuses);
    }
    return getRequest;
  }

  private boolean isLastPage(JsonNode root, int page, int issuesSoFar) {
    if (root.path("issues").isEmpty()) {
      return true;
    }
    int pageIndex = root.path(PAGING_FIELD).path("pageIndex").asInt(page);
    int pageSize = root.path(PAGING_FIELD).path("pageSize").asInt(PAGE_SIZE);
    int total = root.path(PAGING_FIELD).path("total").asInt(issuesSoFar);
    return (long) pageIndex * pageSize >= total;
  }

  /**
   * Fetches the "rule" object from api/rules/show for the given rule key.
   * Returns null if the rule can't be resolved (should not normally happen,
   * but analyzers/plugins can be uninstalled after issues were raised).
   */
  public JsonNode fetchRule(String ruleKey) {
    try {
      GetRequest getRequest = new GetRequest("api/rules/show")
        .setParam("key", ruleKey)
        .setMediaType("application/json");
      WsResponse wsResponse = wsClient.wsConnector().call(getRequest);
      if (wsResponse.code() != 200) {
        return null;
      }
      return parse(wsResponse.content()).path("rule");
    } catch (Exception e) {
      return null;
    }
  }

  private JsonNode parse(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("Could not parse SonarQube web service response as JSON", e);
    }
  }
}
