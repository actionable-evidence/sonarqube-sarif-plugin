package com.sonar.sarifexport.sarif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a list of SonarQube issue JSON nodes (as returned by
 * api/issues/search) into a SARIF 2.1.0 document.
 *
 * <p>Reference: <a href="https://docs.oasis-open.org/sarif/sarif/v2.1.0/">SARIF v2.1.0 spec</a>.
 */
public class SarifBuilder {

  private static final String SARIF_SCHEMA =
    "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";
  private static final String START_LINE_FIELD = "startLine";

  private final ObjectMapper mapper = new ObjectMapper();
  private final IssuesFetcher fetcher;
  private final boolean includeRuleMetadata;
  private final String toolVersion;

  public SarifBuilder(IssuesFetcher fetcher, boolean includeRuleMetadata, String toolVersion) {
    this.fetcher = fetcher;
    this.includeRuleMetadata = includeRuleMetadata;
    this.toolVersion = toolVersion;
  }

  public byte[] build(String projectKey, List<JsonNode> issues) throws IOException {
    ObjectNode sarif = mapper.createObjectNode();
    sarif.put("$schema", SARIF_SCHEMA);
    sarif.put("version", "2.1.0");

    ArrayNode runs = sarif.putArray("runs");
    ObjectNode run = runs.addObject();

    ObjectNode driver = run.putObject("tool").putObject("driver");
    driver.put("name", "SonarQubeServer");
    driver.put("version", toolVersion);
    driver.put("informationUri", "https://www.sonarsource.com/products/sonarqube/");

    // ruleKey -> index in the "rules" array, so results can reference rules by ruleIndex
    Map<String, Integer> ruleIndexes = new LinkedHashMap<>();
    ArrayNode rules = driver.putArray("rules");
    ArrayNode results = run.putArray("results");

    for (JsonNode issue : issues) {
      String ruleKey = issue.path("rule").asText(null);
      if (ruleKey == null) {
        continue;
      }

      Integer ruleIndex = ruleIndexes.computeIfAbsent(ruleKey, key -> {
        int index = rules.size();
        rules.add(buildRuleDescriptor(key));
        return index;
      });

      results.add(buildResult(projectKey, issue, ruleKey, ruleIndex));
    }

    return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sarif);
  }

  private ObjectNode buildResult(String projectKey, JsonNode issue, String ruleKey, int ruleIndex) {
    ObjectNode result = mapper.createObjectNode();
    result.put("ruleId", ruleKey);
    result.put("ruleIndex", ruleIndex);
    result.put("level", toSarifLevel(issue.path("severity").asText("MAJOR")));

    result.putObject("message").put("text", issue.path("message").asText(""));

    ObjectNode physicalLocation = result.putArray("locations").addObject().putObject("physicalLocation");
    physicalLocation.putObject("artifactLocation")
      .put("uri", toRelativePath(projectKey, issue.path("component").asText("")));

    JsonNode textRange = issue.path("textRange");
    if (!textRange.isMissingNode()) {
      int startLine = textRange.path(START_LINE_FIELD).asInt(1);
      ObjectNode region = physicalLocation.putObject("region");
      region.put(START_LINE_FIELD, startLine);
      region.put("endLine", textRange.path("endLine").asInt(startLine));
      region.put("startColumn", textRange.path("startOffset").asInt(0) + 1);
      region.put("endColumn", textRange.path("endOffset").asInt(0) + 1);
    } else {
      int line = issue.path("line").asInt(-1);
      if (line > 0) {
        physicalLocation.putObject("region").put(START_LINE_FIELD, line);
      }
    }

    result.putObject("partialFingerprints").put("sonarIssueKey", issue.path("key").asText(""));
    return result;
  }

  private ObjectNode buildRuleDescriptor(String ruleKey) {
    ObjectNode ruleNode = mapper.createObjectNode();
    ruleNode.put("id", ruleKey);

    JsonNode ruleMeta = includeRuleMetadata ? fetcher.fetchRule(ruleKey) : null;
    String name = (ruleMeta != null && !ruleMeta.isMissingNode()) ? ruleMeta.path("name").asText(ruleKey) : ruleKey;
    ruleNode.put("name", name);
    ruleNode.putObject("shortDescription").put("text", name);

    if (ruleMeta != null && !ruleMeta.isMissingNode()) {
      String htmlDesc = ruleMeta.path("htmlDesc").asText(null);
      if (htmlDesc != null && !htmlDesc.isEmpty()) {
        // Strip tags for a plain-text fallback description; consumers that render
        // markdown/HTML can be pointed at the rule's page via helpUri instead.
        String plainText = htmlDesc.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        ruleNode.putObject("fullDescription").put("text", plainText);
      }
    }
    return ruleNode;
  }

  private String toRelativePath(String projectKey, String componentKey) {
    String prefix = projectKey + ":";
    if (componentKey.startsWith(prefix)) {
      return componentKey.substring(prefix.length());
    }
    // Component may belong to a sub-module with a different key prefix;
    // fall back to everything after the last colon.
    int idx = componentKey.lastIndexOf(':');
    return idx >= 0 ? componentKey.substring(idx + 1) : componentKey;
  }

  private String toSarifLevel(String sonarSeverity) {
    switch (sonarSeverity) {
      case "BLOCKER":
      case "CRITICAL":
        return "error";
      case "MAJOR":
        return "warning";
      case "MINOR":
      case "INFO":
      default:
        return "note";
    }
  }
}
