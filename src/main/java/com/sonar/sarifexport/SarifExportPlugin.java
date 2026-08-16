package com.sonar.sarifexport;

import com.sonar.sarifexport.ws.SarifExportWs;
import org.sonar.api.Plugin;

/**
 * Entry point declared in pom.xml (sonar-packaging-maven-plugin -&gt; pluginClass).
 * Registers the web-server extension(s) provided by this plugin.
 */
public class SarifExportPlugin implements Plugin {

  @Override
  public void define(Context context) {
    context.addExtension(SarifExportWs.class);
    context.addExtension(SarifExportPageDefinition.class);
  }
}
