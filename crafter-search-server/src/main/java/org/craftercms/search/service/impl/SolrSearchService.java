/*
 * Copyright (C) 2007-2013 Crafter Software Corporation.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.craftercms.search.service.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.ContentStreamUpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.extraction.ExtractingParams;
import org.craftercms.search.exception.SearchException;
import org.craftercms.search.exception.SolrDocumentBuildException;
import org.craftercms.search.service.Query;
import org.craftercms.search.service.SearchService;
import org.springframework.beans.factory.annotation.Required;

import static org.craftercms.search.service.SearchRestConstants.SOLR_CONTENT_STREAM_UPDATE_URL;

/**
 * Implementation of {@link SearchService} using Solr as the underlying search engine.
 *
 * @author Michael Chen
 * @author Alfonso V��squez
 */
public class SolrSearchService implements SearchService {

    private static final Log logger = LogFactory.getLog(SolrSearchService.class);

    /**
     * The endpoint to the Solr server.
     */
    private SolrServer solrServer;
    /**
     * The Solr document builder, to build Solr documents from generic XML documents.
     */
    private SolrDocumentBuilder solrDocumentBuilder;

    /**
     * Sets the endpoint to the Solr server.
     */
    @Required
    public void setSolrServer(SolrServer solrServer) {
        this.solrServer = solrServer;
    }

    /**
     * Sets the Solr document builder, to build Solr documents from generic XML documents.
     */
    @Required
    public void setSolrDocumentBuilder(SolrDocumentBuilder solrDocumentBuilder) {
        this.solrDocumentBuilder = solrDocumentBuilder;
    }

    /**
     * {@inheritDoc}
     */
    public Map<String, Object> search(Query query) {
        if (logger.isDebugEnabled()) {
            logger.debug("Searching: " + query);
        }

        SolrResponse response;
        try {
            response = solrServer.query(toSolrQuery((QueryParams)query));
        } catch (SolrServerException e) {
            throw new SearchException("Search for query " + query + " failed: " + e.getMessage(), e);
        }

        // Solr search result is a List<Map.Entry<String,Object>>, where every entry is a (name,value) pair,
        // and there can be
        // duplicate names in the list.
        NamedList<Object> list = response.getResponse();
        // Convert this list into a ,ap where values of the same name are grouped into a list.
        Map<String, Object> map = toMap(list);

        if (logger.isDebugEnabled()) {
            logger.debug("Response for query " + query + ": " + map);
        }

        return map;
    }

    /**
     * {@inheritDoc}
     */
    public String update(String site, String id, String xml, boolean ignoreRootInFieldNames) throws SearchException {
        try {
            String finalId = site + ":" + id;

            SolrInputDocument solrDoc = solrDocumentBuilder.build(site, id, xml, ignoreRootInFieldNames);
            UpdateResponse response = solrServer.add(solrDoc);

            if (logger.isDebugEnabled()) {
                logger.debug("Update response for '" + finalId + "': " + response.getResponse());
            }

            return "Successfully updated '" + finalId + "'";
        } catch (SolrDocumentBuildException e) {
            throw new SearchException("Unable to build Solr update document: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new SearchException("I/O error while communicating with Solr server to execute update: " + e
                .getMessage(), e);
        } catch (Exception e) {
            throw new SearchException(e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public String delete(String site, String id) throws SearchException {
        try {
            String finalId = site + ":" + id;

            UpdateResponse response = solrServer.deleteById(finalId);

            if (logger.isDebugEnabled()) {
                logger.debug("Delete response for '" + finalId + "': " + response.getResponse());
            }

            return "Successfully deleted '" + finalId + "'";
        } catch (IOException e) {
            throw new SearchException("I/O error while communicating with Solr server to execute delete: " + e
                .getMessage(), e);
        } catch (Exception e) {
            throw new SearchException(e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public String commit() throws SearchException {
        try {
            UpdateResponse response = solrServer.commit();

            if (logger.isDebugEnabled()) {
                logger.debug("Commit response: " + response.getResponse());
            }

            return "Successfully committed";
        } catch (IOException e) {
            throw new SearchException("I/O error while communicating with Solr server to execute commit: " + e
                .getMessage(), e);
        } catch (Exception e) {
            throw new SearchException(e.getMessage(), e);
        }
    }

    protected SolrParams toSolrQuery(QueryParams queryParams) {
        return new ModifiableSolrParams(queryParams.getParams());
    }

    protected Map<String, Object> toMap(NamedList<Object> namedList) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();

        for (Map.Entry<String, Object> entry : namedList) {
            Object valueInMap = map.get(entry.getKey());
            // If we have already a value in the map for the same key, that means the key has multiple values,
            // so group them in a list.
            if (valueInMap != null) {
                List<Object> group;

                if (valueInMap instanceof List) {
                    group = (List<Object>)valueInMap;
                } else {
                    group = new ArrayList<Object>();
                    group.add(valueInMap);

                    map.put(entry.getKey(), group);
                }

                group.add(toSerializableValue(entry.getValue()));
            } else {
                map.put(entry.getKey(), toSerializableValue(entry.getValue()));
            }
        }

        return map;
    }

    protected Object toSerializableValue(Object namedListValue) {
        // The value can also be a NamedList, so convert it to map.
        if (namedListValue instanceof NamedList) {
            return toMap((NamedList<Object>)namedListValue);
        }
        // If the value is a SolrDocumentList, copy the list attributes to a map
        if (namedListValue instanceof SolrDocumentList) {
            SolrDocumentList docList = (SolrDocumentList)namedListValue;
            Map<String, Object> docListMap = new HashMap<String, Object>(4);

            docListMap.put("start", docList.getStart());
            docListMap.put("numFound", docList.getNumFound());
            docListMap.put("maxScore", docList.getMaxScore());
            docListMap.put("documents", new ArrayList<SolrDocument>(docList));

            return docListMap;
        }

        return namedListValue;
    }

    @Override
    public String updateDocument(String site, String id, File document) throws SearchException {
        return updateDocument(site, id, document, null);
    }

    @Override
    public String updateDocument(String site, String id, File document, String documentXmlDescriptor) throws
        SearchException {
        String finalId = site + ":" + id;

        ContentStreamUpdateRequest request = new ContentStreamUpdateRequest(SOLR_CONTENT_STREAM_UPDATE_URL);
        try {
            request.addFile(document);
            request.setParam(ExtractingParams.LITERALS_PREFIX + "id", finalId);
            request.setParam(ExtractingParams.LITERALS_PREFIX + solrDocumentBuilder.siteFieldName, site);
            request.setParam(ExtractingParams.LITERALS_PREFIX + "file-name", new File(finalId).getName());
            request.setParam(ExtractingParams.LITERALS_PREFIX + solrDocumentBuilder.localIdFieldName, id);
            request.setAction(AbstractUpdateRequest.ACTION.COMMIT, true, true);
            solrServer.request(request);
        } catch (SolrServerException e) {
            throw new SearchException("Error while communicating with Solr server to commit document" + e.getMessage
                (), e);
        } catch (IOException e) {
            throw new SearchException("I/O error while committing document to Solr server " + e.getMessage(), e);
        } finally {
            request = null;
        }
        return "Successfully committed document";
    }
}
