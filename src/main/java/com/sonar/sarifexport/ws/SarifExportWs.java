package com.sonar.sarifexport.ws;

import com.sonar.sarifexport.sarif.IssuesFetcher;
import com.sonar.sarifexport.sarif.SarifBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.List;
import org.sonar.api.platform.Server;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;

/**
 * Exposes GET /api/sarif/export?project=&lt;key&gt;[&amp;branch=...|&amp;pullRequest=...]
 * which returns a SARIF 2.1.0 document built from the project's current issues.
 *
 * Requires the same "Browse" permission as any other issues-related web
 * service, since the underlying api/issues/search call is made under the
 * caller's own session (see {@link IssuesFetcher}).
 */
public class SarifExportWs implements WebService {

  private final Server server;

  public SarifExportWs(Server server) {
    this.server = server;
  }

  @Override
  public void define(Context context) {
    NewController controller = context.createController("api/sarif");
    controller.setDescription("Exports SonarQube issues as SARIF 2.1.0 reports.");

    NewAction export = controller.createAction("export");
    export.setDescription("Exports the current issues of a project as a SARIF 2.1.0 report.")
      .setSince("1.0")
      .setHandler(this::handle);

    export.createParam("project")
      .setDescription("Project key")
      .setRequired(true)
      .setExampleValue("my_project");

    export.createParam("branch")
      .setDescription("Branch key. Mutually exclusive with pullRequest.")
      .setRequired(false)
      .setExampleValue("main");

    export.createParam("pullRequest")
      .setDescription("Pull request id. Mutually exclusive with branch.")
      .setRequired(false)
      .setExampleValue("42");

    export.createParam("statuses")
      .setDescription("Comma-separated list of issue statuses to include.")
      .setDefaultValue("OPEN,CONFIRMED,REOPENED")
      .setRequired(false);

    export.createParam("includeRuleMetadata")
      .setDescription("If true, calls api/rules/show for every distinct rule to enrich "
        + "the SARIF 'rules' section with a human-readable name and description. "
        + "Adds one extra call per distinct rule key.")
      .setPossibleValues("true", "false")
      .setDefaultValue("true")
      .setRequired(false);

    controller.done();
  }

  private void handle(Request request, Response response) throws Exception {
    String projectKey = request.mandatoryParam("project");
    String branch = request.param("branch");
    String pullRequest = request.param("pullRequest");
    String statuses = request.param("statuses");
    boolean includeRuleMetadata = request.paramAsBoolean("includeRuleMetadata");

    IssuesFetcher fetcher = new IssuesFetcher(request.localConnector());
    List<JsonNode> issues = fetcher.fetchAllIssues(projectKey, branch, pullRequest, statuses);

    SarifBuilder builder = new SarifBuilder(fetcher, includeRuleMetadata, server.getVersion());
    byte[] sarifBytes = builder.build(projectKey, issues);

    response.setHeader("Content-Disposition", "attachment; filename=\"sonarqube-sarif-" + projectKey + ".json\"");
    Response.Stream stream = response.stream();
    stream.setMediaType("application/sarif+json");
    try (OutputStream out = stream.output()) {
      out.write(sarifBytes);
    }
  }
}
