package com.github.andirady.pomcli;


import com.github.andirady.pomcli.solrsearch.SolrSearch;
import com.github.andirady.pomcli.solrsearch.SolrSearchRequest;
import com.github.andirady.pomcli.solrsearch.SolrSearchResult;
import java.util.Optional;

public class GetLatestVersion {

	public Optional<String> execute(QuerySpec spec) {
		if (spec.groupId() == null || spec.artifactId() == null) {
			throw new IllegalArgumentException("groupId and artifactId is required");
		}

		var solrSearch = new SolrSearch();
		var req = new SolrSearchRequest(spec.toString(), null, null, 0, 1);
		return solrSearch.search(req).response().docs().stream().map(SolrSearchResult.Document::latestVersion).findFirst();
	}
}
