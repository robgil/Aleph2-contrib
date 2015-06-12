/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.ikanow.aleph2.shared.crud.elasticsearch.services;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequestBuilder;
import org.elasticsearch.action.count.CountRequestBuilder;
import org.elasticsearch.action.get.GetRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexRequest.OpType;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IBasicSearchService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.objects.shared.AuthorizationBean;
import com.ikanow.aleph2.data_model.objects.shared.ProjectBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.CrudUtils;
import com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent;
import com.ikanow.aleph2.data_model.utils.CrudUtils.UpdateComponent;
import com.ikanow.aleph2.data_model.utils.FutureUtils;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchContext;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchContext.ReadWriteContext;
import com.ikanow.aleph2.shared.crud.elasticsearch.utils.ElasticsearchFutureUtils;
import com.ikanow.aleph2.shared.crud.elasticsearch.utils.ElasticsearchUtils;
import com.ikanow.aleph2.shared.crud.elasticsearch.utils.ErrorUtils;

//TODO .... more thoughts on field list buckets ... options for auto generating .number fields and .raw fields (and nested - that might live in the search index bit though?)

public class ElasticsearchCrudService<O> implements ICrudService<O> {

	public enum CreationPolicy { AVAILABLE_IMMEDIATELY, SINGLE_OBJECT_AVAILABLE_IMMEDIATELY, OPTIMIZED };
	
	public ElasticsearchCrudService(final Class<O> bean_clazz, 
			final ElasticsearchContext es_context, 
			final Optional<Boolean> id_ranges_ok, final CreationPolicy creation_policy, 
			final Optional<String> auth_fieldname, final Optional<AuthorizationBean> auth, final Optional<ProjectBean> project)
	{
		_state = new State(bean_clazz, es_context, id_ranges_ok.orElse(false), creation_policy);
		_object_mapper = BeanTemplateUtils.configureMapper(Optional.empty());
	}
	protected class State {
		State(final Class<O> bean_clazz, final ElasticsearchContext es_context, 
				final boolean id_ranges_ok, final CreationPolicy creation_policy
				)			
		{
			this.es_context = es_context;
			client = es_context.client();
			clazz = bean_clazz;
			this.id_ranges_ok = id_ranges_ok;
			this.creation_policy = creation_policy;
		}
		final ElasticsearchContext es_context;
		final Client client;
		final Class<O> clazz;
		final boolean id_ranges_ok;
		final CreationPolicy creation_policy;
	}
	protected final State _state;
	protected final ObjectMapper _object_mapper;
	
	/////////////////////////////////////////////////////
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getFilteredRepo(java.lang.String, java.util.Optional, java.util.Optional)
	 */
	@Override
	public ICrudService<O> getFilteredRepo(String authorization_fieldname,
			Optional<AuthorizationBean> client_auth,
			Optional<ProjectBean> project_auth) {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "getFilteredRepo"));
	}

	/** Utility function - will get a read-write version of a context and exit via exception if that isn't possible 
	 * @param es_context
	 * @return
	 */
	private static ElasticsearchContext.ReadWriteContext getRwContextOrThrow(final ElasticsearchContext es_context, final String method_name) {
		if (es_context instanceof ElasticsearchContext.ReadWriteContext) {
			return (ReadWriteContext)es_context;
		}
		else {
			throw new RuntimeException(ErrorUtils.get(ErrorUtils.TRIED_TO_WRITE_INTO_RO_SERVICE, method_name));
		}		
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#storeObject(java.lang.Object, boolean)
	 */
	@Override
	public CompletableFuture<Supplier<Object>> storeObject(final O new_object, final boolean replace_if_present) {
		try {
			final ReadWriteContext rw_context = getRwContextOrThrow(_state.es_context, "storeObject");
			
			final JsonNode object_json = (JsonNode.class.isAssignableFrom(_state.clazz))
											? (JsonNode) new_object
											: BeanTemplateUtils.toJson(new_object);
			
			final IndexRequestBuilder irb = Optional
					.of(_state.client.prepareIndex(
							rw_context.indexContext().getWritableIndex(Optional.of(object_json)),
							rw_context.typeContext().getWriteType())
						.setOpType(replace_if_present ? OpType.INDEX : OpType.CREATE)
						.setConsistencyLevel(WriteConsistencyLevel.ONE)
						.setRefresh(CreationPolicy.OPTIMIZED != _state.creation_policy)
						.setSource(object_json.toString())
							)
					.map(i -> object_json.has("_id") ? i.setId(object_json.get("_id").asText()) : i)
					.get();
											
			//TODO: this needs to handle mapping errors, so need a more complex version of this wrapper....
			return ElasticsearchFutureUtils.wrap(irb.execute(), ir -> {		
				return () -> ir.getId();
			});
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#storeObject(java.lang.Object)
	 */
	@Override
	public CompletableFuture<Supplier<Object>> storeObject(final O new_object) {
		return storeObject(new_object, false);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#storeObjects(java.util.List, boolean)
	 */
	@Override
	public CompletableFuture<Tuple2<Supplier<List<Object>>, Supplier<Long>>> storeObjects(final List<O> new_objects, final boolean continue_on_error) {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		try {
			throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "storeObjects"));
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#storeObjects(java.util.List)
	 */
	@Override
	public CompletableFuture<Tuple2<Supplier<List<Object>>, Supplier<Long>>> storeObjects(final List<O> new_objects) {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		try {
			throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "storeObjects"));
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#optimizeQuery(java.util.List)
	 */
	@Override
	public CompletableFuture<Boolean> optimizeQuery(final List<String> ordered_field_list) {
		// (potentially in the future this could check the mapping and throw if the fields are not indexed?)
		return CompletableFuture.completedFuture(true);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#deregisterOptimizedQuery(java.util.List)
	 */
	@Override
	public boolean deregisterOptimizedQuery(final List<String> ordered_field_list) {
		//(just ignore this)
		return false;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getObjectBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent)
	 */
	@Override
	public CompletableFuture<Optional<O>> getObjectBySpec(final QueryComponent<O> unique_spec) {
		return getObjectBySpec(unique_spec, Arrays.asList(), false);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getObjectBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent, java.util.List, boolean)
	 */
	@Override
	public CompletableFuture<Optional<O>> getObjectBySpec(final QueryComponent<O> unique_spec, final List<String> field_list, final boolean include) {
		try {
			//TODO (ALEPH-14): Handle case where no source is present but fields are
			
			Tuple2<FilterBuilder, UnaryOperator<SearchRequestBuilder>> query = ElasticsearchUtils.convertToElasticsearchFilter(unique_spec, _state.id_ranges_ok);
			
			final SearchRequestBuilder srb = Optional
						.of(
							_state.client.prepareSearch()
							.setIndices(_state.es_context.indexContext().getReadableIndexArray(Optional.empty()))
							.setTypes(_state.es_context.typeContext().getReadableTypeArray())
							.setQuery(QueryBuilders.constantScoreQuery(query._1()))
							.setSize(1))
						.map(s -> field_list.isEmpty() 
								? s 
								: include
									? s.setFetchSource(field_list.toArray(new String[0]), new String[0])
									: s.setFetchSource(new String[0], field_list.toArray(new String[0]))
							)
						.get();
			
			return ElasticsearchFutureUtils.wrap(srb.execute(), sr -> {
				final SearchHit[] sh = sr.getHits().hits();
				
				if (sh.length > 0) {
					final Map<String, Object> src_fields = sh[0].getSource();					
					return Optional.ofNullable(_object_mapper.convertValue(src_fields, _state.clazz));
				}
				else {
					return Optional.empty();
				}
			});
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getObjectById(java.lang.Object)
	 */
	@Override
	public CompletableFuture<Optional<O>> getObjectById(final Object id) {
		return getObjectById(id, Arrays.asList(), false);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getObjectById(java.lang.Object, java.util.List, boolean)
	 */
	@Override
	public CompletableFuture<Optional<O>> getObjectById(final Object id, final List<String> field_list, final boolean include) {
		final List<String> indexes = _state.es_context.indexContext().getReadableIndexList(Optional.empty());
		final List<String> types = _state.es_context.typeContext().getReadableTypeList();
		if ((indexes.size() != 1) || (indexes.size() > 1)) {
			// Multi index request, so use a query (which may not always return the most recent value, depending on index refresh settings/timings)
			return getObjectBySpec(CrudUtils.anyOf(_state.clazz).when("_id", id.toString()), field_list, include);			
		}
		else {
			
			final GetRequestBuilder srb = Optional
					.of(
						_state.client.prepareGet()
							.setIndex(indexes.get(0))
							.setId(id.toString())
						)
					.map(s -> (1 == types.size()) ? s.setType(types.get(0)) : s)
					.map(s -> field_list.isEmpty() 
							? s 
							: include
								? s.setFetchSource(field_list.toArray(new String[0]), new String[0])
								: s.setFetchSource(new String[0], field_list.toArray(new String[0]))
						)
					.get();
			
			return ElasticsearchFutureUtils.wrap(srb.execute(), sr -> {
				if (sr.isExists()) {
					final Map<String, Object> src_fields = sr.getSource();					
					return Optional.ofNullable(_object_mapper.convertValue(src_fields, _state.clazz));
				}
				else {
					return Optional.empty();
				}
			});			
		}		
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getObjectsBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent)
	 */
	@Override
	public CompletableFuture<com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService.Cursor<O>> getObjectsBySpec(final QueryComponent<O> spec) {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		try {
			throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "getObjectsBySpec"));
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getObjectsBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent, java.util.List, boolean)
	 */
	@Override
	public CompletableFuture<com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService.Cursor<O>> getObjectsBySpec(QueryComponent<O> spec, List<String> field_list, boolean include) {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		try {
			throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "getObjectsBySpec"));
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#countObjectsBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent)
	 */
	@Override
	public CompletableFuture<Long> countObjectsBySpec(QueryComponent<O> spec) {
		try {
			Tuple2<FilterBuilder, UnaryOperator<SearchRequestBuilder>> query = ElasticsearchUtils.convertToElasticsearchFilter(spec, _state.id_ranges_ok);

			final CountRequestBuilder crb = _state.client.prepareCount()
					.setIndices(_state.es_context.indexContext().getReadableIndexArray(Optional.empty()))
					.setTypes(_state.es_context.typeContext().getReadableTypeArray())
					.setQuery(QueryBuilders.constantScoreQuery(query._1()))
					;
			
			return ElasticsearchFutureUtils.wrap(crb.execute(), cr -> {
				return cr.getCount();
			});			
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#countObjects()
	 */
	@Override
	public CompletableFuture<Long> countObjects() {
		try {			
			final CountRequestBuilder crb = _state.client.prepareCount()
					.setIndices(_state.es_context.indexContext().getReadableIndexArray(Optional.empty()))
					.setTypes(_state.es_context.typeContext().getReadableTypeArray())
					;			
			
			return ElasticsearchFutureUtils.wrap(crb.execute(), cr -> {				
				return cr.getCount();
			});			
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#updateObjectById(java.lang.Object, com.ikanow.aleph2.data_model.utils.CrudUtils.UpdateComponent)
	 */
	@Override
	public CompletableFuture<Boolean> updateObjectById(Object id,
			UpdateComponent<O> update) {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		try {
			throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "updateObjectById"));
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#updateObjectBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent, java.util.Optional, com.ikanow.aleph2.data_model.utils.CrudUtils.UpdateComponent)
	 */
	@Override
	public CompletableFuture<Boolean> updateObjectBySpec(
			QueryComponent<O> unique_spec, Optional<Boolean> upsert,
			UpdateComponent<O> update) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#updateObjectsBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent, java.util.Optional, com.ikanow.aleph2.data_model.utils.CrudUtils.UpdateComponent)
	 */
	@Override
	public CompletableFuture<Long> updateObjectsBySpec(QueryComponent<O> spec,
			Optional<Boolean> upsert, UpdateComponent<O> update) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#updateAndReturnObjectBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent, java.util.Optional, com.ikanow.aleph2.data_model.utils.CrudUtils.UpdateComponent, java.util.Optional, java.util.List, boolean)
	 */
	@Override
	public CompletableFuture<Optional<O>> updateAndReturnObjectBySpec(
			QueryComponent<O> unique_spec, Optional<Boolean> upsert,
			UpdateComponent<O> update, Optional<Boolean> before_updated,
			List<String> field_list, boolean include) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#deleteObjectById(java.lang.Object)
	 */
	@Override
	public CompletableFuture<Boolean> deleteObjectById(Object id) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#deleteObjectBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent)
	 */
	@Override
	public CompletableFuture<Boolean> deleteObjectBySpec(
			QueryComponent<O> unique_spec) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#deleteObjectsBySpec(com.ikanow.aleph2.data_model.utils.CrudUtils.QueryComponent)
	 */
	@Override
	public CompletableFuture<Long> deleteObjectsBySpec(QueryComponent<O> spec) {
		// TODO Auto-generated method stub
		return null;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#deleteDatastore()
	 */
	@Override
	public CompletableFuture<Boolean> deleteDatastore() {
		try {
			final ReadWriteContext rw_context = getRwContextOrThrow(_state.es_context, "deleteDatastore");
			
			DeleteIndexRequestBuilder dir = _state.client.admin().indices().prepareDelete(rw_context.indexContext().getWritableIndex(Optional.empty()));
			
			return ElasticsearchFutureUtils.wrap(dir.execute(), dr -> {
				return dr.isAcknowledged();
			});
		}
		catch (Exception e) {
			return FutureUtils.returnError(e);
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getRawCrudService()
	 */
	@Override
	public ICrudService<JsonNode> getRawCrudService() {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "getRawCrudService"));
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getSearchService()
	 */
	@Override
	public Optional<IBasicSearchService<O>> getSearchService() {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "getSearchService"));
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(final Class<T> driver_class, final Optional<String> driver_options) {
		//TODO (ALEPH-14): TO BE IMPLEMENTED
		// TODO metamodel or ElasticsearchContext
		throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED, "getUnderlyingPlatformDriver"));
	}

}