(function () {
  var MAX_PREVIEW_RESULTS = 50;

  window.registerExtension('sarifexport/export', function (options) {
    var el = options.el;
    var component = options.component || {};
    var branchLike = options.branchLike || {};
    var baseUrl = window.baseUrl || '';

    function buildParams() {
      var params = new URLSearchParams();
      params.set('project', component.key);
      if (branchLike.pullRequest) {
        params.set('pullRequest', branchLike.pullRequest);
      } else if (branchLike.branch) {
        params.set('branch', branchLike.branch);
      }
      return params;
    }

    var container = document.createElement('div');
    container.style.padding = '16px';

    var heading = document.createElement('h2');
    heading.textContent = 'SARIF Export';
    heading.style.marginBottom = '8px';

    var description = document.createElement('p');
    description.textContent =
      "Download this project's current issues as a SARIF 2.1.0 report, " +
      'ready to upload to GitHub code scanning, Azure DevOps, or any other ' +
      'SARIF-compatible dashboard.';
    description.style.marginBottom = '16px';

    var button = document.createElement('button');
    button.className = 'button';
    button.textContent = 'Export SARIF';
    button.style.marginBottom = '24px';
    button.addEventListener('click', function () {
      window.location.assign(baseUrl + '/api/sarif/export?' + buildParams().toString());
    });

    var previewHeading = document.createElement('h3');
    previewHeading.textContent = 'Preview';
    previewHeading.style.marginBottom = '8px';

    var previewStatus = document.createElement('p');
    previewStatus.textContent = 'Loading preview…';

    var previewSummary = document.createElement('p');
    previewSummary.style.display = 'none';
    previewSummary.style.marginBottom = '8px';

    var previewPre = document.createElement('pre');
    previewPre.style.display = 'none';
    previewPre.style.maxHeight = '400px';
    previewPre.style.overflow = 'auto';
    previewPre.style.padding = '12px';
    previewPre.style.background = '#f3f3f3';
    previewPre.style.fontSize = '12px';

    container.appendChild(heading);
    container.appendChild(description);
    container.appendChild(button);
    container.appendChild(previewHeading);
    container.appendChild(previewStatus);
    container.appendChild(previewSummary);
    container.appendChild(previewPre);
    el.appendChild(container);

    fetch(baseUrl + '/api/sarif/export?' + buildParams().toString(), { credentials: 'same-origin' })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        return response.json();
      })
      .then(function (sarif) {
        var run = (sarif.runs && sarif.runs[0]) || {};
        var results = run.results || [];
        var ruleCount = ((run.tool && run.tool.driver && run.tool.driver.rules) || []).length;
        var truncated = results.length > MAX_PREVIEW_RESULTS;

        previewStatus.style.display = 'none';
        previewSummary.style.display = '';
        previewSummary.textContent = results.length + ' result(s) across ' + ruleCount + ' rule(s).' +
          (truncated ? ' Showing the first ' + MAX_PREVIEW_RESULTS + ' below — export for the full report.' : '');

        var previewDoc = truncated
          ? Object.assign({}, sarif, {
            runs: [Object.assign({}, run, { results: results.slice(0, MAX_PREVIEW_RESULTS) })]
          })
          : sarif;

        previewPre.style.display = '';
        previewPre.textContent = JSON.stringify(previewDoc, null, 2);
      })
      .catch(function (err) {
        previewStatus.textContent = 'Could not load preview: ' + err.message;
      });

    return function () {
      el.removeChild(container);
    };
  });
})();
