package com.sonar.sarifexport;

import org.sonar.api.web.page.Context;
import org.sonar.api.web.page.Page;
import org.sonar.api.web.page.Page.Qualifier;
import org.sonar.api.web.page.Page.Scope;
import org.sonar.api.web.page.PageDefinition;

/**
 * Registers a project-level page ("SARIF Export") rendered by the JS bundle
 * at src/main/resources/static/sarifexport.js. SonarQube loads that bundle on
 * every page and matches it to this page via the page key
 * ("&lt;pluginKey&gt;/export", pluginKey being "sarifexport" as declared in pom.xml).
 */
public class SarifExportPageDefinition implements PageDefinition {

  @Override
  public void define(Context context) {
    context.addPage(Page.builder("sarifexport/export")
      .setName("SARIF Export")
      .setScope(Scope.COMPONENT)
      .setComponentQualifiers(Qualifier.PROJECT)
      .build());
  }
}
