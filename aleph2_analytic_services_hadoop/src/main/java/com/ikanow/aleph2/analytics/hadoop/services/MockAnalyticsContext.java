/*******************************************************************************
* Copyright 2015, The IKANOW Open Source Project.
* 
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License, version 3,
* as published by the Free Software Foundation.
* 
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
* 
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
******************************************************************************/
package com.ikanow.aleph2.analytics.hadoop.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.ikanow.aleph2.analytics.hadoop.utils.HadoopErrorUtils;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsAccessContext;
import com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext;
import com.ikanow.aleph2.data_model.interfaces.data_services.IManagementDbService;
import com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean.AnalyticThreadJobInputBean;
import com.ikanow.aleph2.data_model.objects.data_import.AnnotationBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean.MasterEnrichmentType;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketStatusBean;
import com.ikanow.aleph2.data_model.objects.shared.AssetStateDirectoryBean.StateDirectoryType;
import com.ikanow.aleph2.data_model.objects.shared.AssetStateDirectoryBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.objects.shared.SharedLibraryBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.data_model.utils.Patterns;
import com.ikanow.aleph2.data_model.utils.PropertiesUtils;
import com.ikanow.aleph2.data_model.utils.SetOnce;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils.BeanTemplate;
import com.ikanow.aleph2.distributed_services.data_model.DistributedServicesPropertyBean;
import com.ikanow.aleph2.distributed_services.services.ICoreDistributedServices;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigValueFactory;

import fj.data.Either;

/** A minimal implementation of the analytics context interface for test purposes
 * @author Alex
 */
/**
 * @author Alex
 *
 */
public class MockAnalyticsContext implements IAnalyticsContext {
	protected static final Logger _logger = LogManager.getLogger();	

	protected static final EnumSet<MasterEnrichmentType> _streaming_types = EnumSet.of(MasterEnrichmentType.streaming, MasterEnrichmentType.streaming_and_batch);	
	protected static final EnumSet<MasterEnrichmentType> _batch_types = EnumSet.of(MasterEnrichmentType.batch, MasterEnrichmentType.streaming_and_batch);		
	
	////////////////////////////////////////////////////////////////
	
	// CONSTRUCTION
	
	public static final String __MY_BUCKET_ID = "3fdb4bfa-2024-11e5-b5f7-727283247c7e";	
	public static final String __MY_TECH_LIBRARY_ID = "3fdb4bfa-2024-11e5-b5f7-727283247c7f";
	public static final String __MY_MODULE_LIBRARY_ID = "3fdb4bfa-2024-11e5-b5f7-727283247cff";
	
	protected static class MutableState {
		SetOnce<DataBucketBean> bucket = new SetOnce<DataBucketBean>();
		SetOnce<SharedLibraryBean> technology_config = new SetOnce<>();
		SetOnce<Map<String, SharedLibraryBean>> library_configs = new SetOnce<>();
		SetOnce<String> user_topology_entry_point = new SetOnce<>();
		final SetOnce<ImmutableSet<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>> service_manifest_override = new SetOnce<>();
		final SetOnce<String> signature_override = new SetOnce<>();		
	};	
	protected final MutableState _mutable_state = new MutableState(); 
	
	public enum State { IN_TECHNOLOGY, IN_MODULE };
	protected final State _state_name;	
	
	// (stick this injection in and then call injectMembers in IN_MODULE case)
	@Inject protected IServiceContext _service_context;	
	protected IManagementDbService _core_management_db;
	protected ICoreDistributedServices _distributed_services; 	
	protected ISearchIndexService _index_service;
	protected IStorageService _storage_service;
	protected GlobalPropertiesBean _globals;

	protected final ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());	
	
	// For writing objects out
	protected Optional<IDataWriteService<JsonNode>> _crud_index_service;
	protected Optional<IDataWriteService.IBatchSubservice<JsonNode>> _batch_index_service;
	protected Optional<IDataWriteService<JsonNode>> _crud_storage_service;
	protected Optional<IDataWriteService.IBatchSubservice<JsonNode>> _batch_storage_service;
	
	private static ConcurrentHashMap<String, MockAnalyticsContext> static_instances = new ConcurrentHashMap<>();
	
	/**Guice injector
	 * @param service_context
	 */
	@Inject 
	public MockAnalyticsContext(final IServiceContext service_context) {
		_state_name = State.IN_TECHNOLOGY;
		_service_context = service_context;
		_core_management_db = service_context.getCoreManagementDbService(); // (actually returns the _core_ management db service)
		_distributed_services = service_context.getService(ICoreDistributedServices.class, Optional.empty()).get();		
		_index_service = service_context.getService(ISearchIndexService.class, Optional.empty()).get();
		_storage_service = _service_context.getStorageService();
		_globals = service_context.getGlobalProperties();
	}

	/** In-module constructor
	 */
	public MockAnalyticsContext() {
		_state_name = State.IN_MODULE;
		
		// Can't do anything until initializeNewContext is called
	}	
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the bucket for this context instance
	 * @param this_bucket - the bucket to associate
	 * @returns whether the bucket has been updated (ie fails if it's already been set)
	 */
	@SuppressWarnings("deprecation")
	public MockAnalyticsContext setBucket(final DataBucketBean this_bucket) {
		_mutable_state.bucket.forceSet(this_bucket);
		return this;
	}
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the library bean for this context instance
	 * @param this_bucket - the library bean to be associated
	 * @returns whether the library bean has been updated (ie fails if it's already been set)
	 */
	@SuppressWarnings("deprecation")
	public MockAnalyticsContext setTechnologyConfig(final SharedLibraryBean lib_config) {
		_mutable_state.technology_config.forceSet(lib_config);
		return this;
	}
	
	/** (FOR INTERNAL DATA MANAGER USE ONLY) Sets the optional module library bean for this context instance
	 * @param this_bucket - the library bean to be associated
	 * @returns whether the library bean has been updated (ie fails if it's already been set)
	 */
	@SuppressWarnings("deprecation")
	public void resetLibraryConfigs(final Map<String, SharedLibraryBean> lib_configs) {
		_mutable_state.library_configs.forceSet(lib_configs);
	}	
	
	/** A very simple container for library beans
	 * @author Alex
	 */
	public static class LibraryContainerBean {
		LibraryContainerBean() {}
		LibraryContainerBean(Collection<SharedLibraryBean> libs) { this.libs = new ArrayList<>(libs); }
		List<SharedLibraryBean> libs;
	}
	
	/** FOR DEBUGGING AND TESTING ONLY, inserts a copy of the current context into the saved "in module" versions
	 */
	public void overrideSavedContext() {		
		static_instances.put(_mutable_state.signature_override.get(), this);
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#initializeNewContext(java.lang.String)
	 */
	@Override
	public void initializeNewContext(final String signature) {
		try {
			// Inject dependencies
			final Config parsed_config = ConfigFactory.parseString(signature);
			final MockAnalyticsContext to_clone = static_instances.get(signature);
			
			if (null != to_clone) { //copy the fields				
				_service_context = to_clone._service_context;
				_core_management_db = to_clone._core_management_db;
				_distributed_services = to_clone._distributed_services;	
				_index_service = to_clone._index_service;
				_storage_service = to_clone._storage_service;
				_globals = to_clone._globals;
				// (apart from bucket, which is handled below, rest of mutable state is not needed)
			}
			else {				
				ModuleUtils.initializeApplication(Collections.emptyList(), Optional.of(parsed_config), Either.right(this));

				_core_management_db = _service_context.getCoreManagementDbService(); // (actually returns the _core_ management db service)
				_distributed_services = _service_context.getService(ICoreDistributedServices.class, Optional.empty()).get();
				_index_service = _service_context.getService(ISearchIndexService.class, Optional.empty()).get();
				_storage_service = _service_context.getStorageService();
				_globals = _service_context.getGlobalProperties();
			}			
			// Get bucket 

			final BeanTemplate<DataBucketBean> retrieve_bucket = BeanTemplateUtils.from(parsed_config.getString(__MY_BUCKET_ID), DataBucketBean.class);
			_mutable_state.bucket.set(retrieve_bucket.get());
			final BeanTemplate<SharedLibraryBean> retrieve_library = BeanTemplateUtils.from(parsed_config.getString(__MY_TECH_LIBRARY_ID), SharedLibraryBean.class);
			_mutable_state.technology_config.set(retrieve_library.get());
			if (parsed_config.hasPath(__MY_MODULE_LIBRARY_ID)) {
				final BeanTemplate<LibraryContainerBean> retrieve_module = BeanTemplateUtils.from(parsed_config.getString(__MY_MODULE_LIBRARY_ID), LibraryContainerBean.class);
				_mutable_state.library_configs.set(
						Optional.ofNullable(retrieve_module.get().libs).orElse(Collections.emptyList())
									.stream()
									// (split each lib bean into 2 tuples, ie indexed by _id and path_name)
									.flatMap(mod -> Arrays.asList(Tuples._2T(mod._id(), mod), Tuples._2T(mod.path_name(), mod)).stream())
									.collect(Collectors.toMap(
											t2 -> t2._1()
											, 
											t2 -> t2._2()
											,
											(t1, t2) -> t1 // (can't happen, ignore if it does)
											,
											() -> new LinkedHashMap<String, SharedLibraryBean>()
											))
						);
			}
			
			_batch_index_service = 
					(_crud_index_service = _index_service.getDataService()
												.flatMap(s -> s.getWritableDataService(JsonNode.class, retrieve_bucket.get(), Optional.empty(), Optional.empty()))
					)
					.flatMap(IDataWriteService::getBatchWriteSubservice)
					;

			_batch_storage_service = 
					(_crud_storage_service = _storage_service.getDataService()
												.flatMap(s -> 
															s.getWritableDataService(JsonNode.class, retrieve_bucket.get(), 
																Optional.of(IStorageService.StorageStage.processed.toString()), Optional.empty()))
					)
					.flatMap(IDataWriteService::getBatchWriteSubservice)
					;
			static_instances.put(signature, this);
		}
		catch (Exception e) {
			//DEBUG
			//System.out.println(ErrorUtils.getLongForm("{0}", e));			

			throw new RuntimeException(e);
		}
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getEnrichmentContextSignature(java.util.Optional)
	 */
	@Override
	public String getAnalyticsContextSignature(final Optional<DataBucketBean> bucket, final Optional<Set<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>> services) {
		if (_state_name == State.IN_TECHNOLOGY) {
			// Returns a config object containing:
			// - set up for any of the services described
			// - all the rest of the configuration
			// - the bucket bean ID
			
			final Config full_config = ModuleUtils.getStaticConfig()
										.withoutPath(DistributedServicesPropertyBean.APPLICATION_NAME)
										.withoutPath("MongoDbManagementDbService.v1_enabled") // (special workaround for V1 sync service)
										;
	
			final Optional<Config> service_config = PropertiesUtils.getSubConfig(full_config, "service");
			
			final ImmutableSet<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>> complete_services_set = 
					ImmutableSet.<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>builder()
							.addAll(services.orElse(Collections.emptySet()))
							.add(Tuples._2T(ICoreDistributedServices.class, Optional.empty()))
							.add(Tuples._2T(IManagementDbService.class, Optional.empty()))
							.add(Tuples._2T(ISearchIndexService.class, Optional.empty()))
							.add(Tuples._2T(ISecurityService.class, Optional.empty()))
							.add(Tuples._2T(IStorageService.class, Optional.empty()))
							.add(Tuples._2T(IManagementDbService.class, IManagementDbService.CORE_MANAGEMENT_DB))
							.build();
			
			if (_mutable_state.service_manifest_override.isSet()) {
				if (!complete_services_set.equals(_mutable_state.service_manifest_override.get())) {
					throw new RuntimeException(HadoopErrorUtils.SERVICE_RESTRICTIONS);
				}
			}
			else {
				_mutable_state.service_manifest_override.set(complete_services_set);
			}
			
			final Config config_no_services = full_config.withoutPath("service");
			
			// Ugh need to add: core deps, core + underlying management db to this list
			
			final Config service_subset = complete_services_set.stream() // DON'T MAKE PARALLEL SEE BELOW
				.map(clazz_name -> {
					final String config_path = clazz_name._2().orElse(clazz_name._1().getSimpleName().substring(1));
					return service_config.get().hasPath(config_path) 
							? Tuples._2T(config_path, service_config.get().getConfig(config_path)) 
							: null;
				})
				.filter(cfg -> null != cfg)
				.reduce(
						ConfigFactory.empty(),
						(acc, k_v) -> acc.withValue(k_v._1(), k_v._2().root()),
						(acc1, acc2) -> acc1 // (This will never be called as long as the above stream is not parallel)
						);
				
			final Config config_subset_services = config_no_services.withValue("service", service_subset.root());
			
			final Config last_call = 
					Lambdas.get(() -> 
						_mutable_state.library_configs.isSet()
						?
						config_subset_services
							.withValue(__MY_MODULE_LIBRARY_ID, 
									ConfigValueFactory
									.fromAnyRef(BeanTemplateUtils.toJson(
											new LibraryContainerBean(
													_mutable_state.library_configs.get().entrySet().stream()
														.filter(kv -> kv.getValue().path_name().equals(kv.getKey()))
														.map(kv -> kv.getValue())
														.collect(Collectors.toList())
												)
											).toString())
									)
						:
						config_subset_services						
					)
					.withValue(__MY_BUCKET_ID, 
								ConfigValueFactory
									.fromAnyRef(BeanTemplateUtils.toJson(bucket.orElseGet(() -> _mutable_state.bucket.get())).toString())
									)
					.withValue(__MY_TECH_LIBRARY_ID, 
								ConfigValueFactory
									.fromAnyRef(BeanTemplateUtils.toJson(_mutable_state.technology_config.get()).toString())
									)
									;
			
			final String ret1 = last_call.root().render(ConfigRenderOptions.concise());
			_mutable_state.signature_override.set(ret1);
			final String ret = this.getClass().getName() + ":" + ret1;

			this.overrideSavedContext(); // (FOR TESTING ONLY - ie BECAUSE THIS IS MOCK ANALYTIC CONTEXT)
			
			return ret;
		}
		else {
			throw new RuntimeException(HadoopErrorUtils.TECHNOLOGY_NOT_MODULE);			
		}
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingArtefacts()
	 */
	@Override
	public Collection<Object> getUnderlyingArtefacts() {
		if (_state_name == State.IN_TECHNOLOGY) {
			if (!_mutable_state.service_manifest_override.isSet()) {
				throw new RuntimeException(HadoopErrorUtils.SERVICE_RESTRICTIONS);				
			}
			return Stream.concat(
				Stream.of(this, _service_context)
				,
				_mutable_state.service_manifest_override.get().stream()
					.map(t2 -> _service_context.getService(t2._1(), t2._2()))
					.filter(service -> service.isPresent())
					.flatMap(service -> service.get().getUnderlyingArtefacts().stream())
			)
			.collect(Collectors.toList());
		}
		else {
			throw new RuntimeException(HadoopErrorUtils.TECHNOLOGY_NOT_MODULE);			
		}
	}

	////////////////////////////////////////////////////////////////
	
	// OVERRIDES
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(final Class<T> driver_class,
			final Optional<String> driver_options) {
		return Optional.empty();
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_import.IEnrichmentModuleContext#getService(java.lang.Class, java.util.Optional)
	 */
	@Override
	public IServiceContext getServiceContext() {
		return _service_context;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getOutputPath(java.util.Optional, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean)
	 */
	@Override
	public Optional<Tuple2<String, Optional<String>>> getOutputPath(
			final Optional<DataBucketBean> bucket, final AnalyticThreadJobBean job) {
		throw new RuntimeException("Not part of mock analytics context for Storm");
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getOutputTopic(java.util.Optional, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean)
	 */
	@Override
	public Optional<String> getOutputTopic(
			final Optional<DataBucketBean> bucket,
			final AnalyticThreadJobBean job)
	{
		final DataBucketBean this_bucket = bucket.orElseGet(() -> _mutable_state.bucket.get());
		
		if ((MasterEnrichmentType.streaming == job.output().transient_type()) || (MasterEnrichmentType.streaming_and_batch == job.output().transient_type())) {
			
			final String topic = job.output().is_transient()
					? _distributed_services.generateTopicName(this_bucket.full_name(), Optional.of(job.name()))
					: 
					  _distributed_services.generateTopicName(Optional.ofNullable(job.output().sub_bucket_path()).orElse(this_bucket.full_name()), 
																ICoreDistributedServices.QUEUE_END_NAME)
					;
					
			return Optional.of(topic);
		}
		else return Optional.empty();
	}
	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getInputTopics(java.util.Optional, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean.AnalyticThreadJobInputBean)
	 */
	@Override
	public List<String> getInputTopics(
			final Optional<DataBucketBean> bucket, final AnalyticThreadJobBean job,
			final AnalyticThreadJobInputBean job_input)
	{	
		// THIS IS TAKEN FROM THE LEGIT ANALYTIC CONTEXT BUT WITH ALL THE AUTHENTICATION TAKEN OUT
		
		//To reiterate: (Note this is just the bare bones required for Storm streaming enrichment testing, nothing more functional than that)
		final DataBucketBean my_bucket = bucket.orElseGet(() -> _mutable_state.bucket.get());
				
		final String topic = _distributed_services.generateTopicName(my_bucket.full_name(), Optional.empty());
		_distributed_services.createTopic(topic, Optional.empty());
		
		_logger.info("Created input topic for passthrough topology: " + topic);
		
		return Arrays.asList(topic);
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getInputPaths(java.util.Optional, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean.AnalyticThreadJobInputBean)
	 */
	@Override
	public List<String> getInputPaths(
			final Optional<DataBucketBean> bucket, 
			final AnalyticThreadJobBean job,
			final AnalyticThreadJobInputBean job_input) {

		// THIS IS TAKEN FROM THE LEGIT ANALYTIC CONTEXT BUT WITH ALL THE AUTHENTICATION TAKEN OUT
		
		final DataBucketBean my_bucket = bucket.orElseGet(() -> _mutable_state.bucket.get());
		
		return Optional.of(job_input)
				.filter(i -> null != i.data_service())
				.filter(i -> "batch".equalsIgnoreCase(i.data_service()) || "storage_service".equalsIgnoreCase(i.data_service()))
				.map(Lambdas.wrap_u(i -> {					
					if ("batch".equalsIgnoreCase(i.data_service())) {
						if (null != i.filter()) {
							//(actually not sure if i ever plan to implement this?)
							throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED) + ": input.filter");
						}						
						
						final String[] bucket_subchannel = Lambdas.<String, String[]> wrap_u(s -> {
							
							// 1) If the resource starts with "/" then must point to an intermediate batch result of an external bucket
							// 2) If the resource is a pointer then

							if (s.startsWith("/")) { //1.*
								if (s.endsWith(":")) {
									return new String[] { s.substring(0, s.length() - 1), "" }; // (1.2a)
								}
								else {
									final String[] b_sc = s.split(":");
									if (1 == b_sc.length) {
										return new String[] { my_bucket.full_name(), "" };
									}
									else {
										return b_sc; //(1.1)
									}
								}
							}
							else { //2.*
								return new String[] { my_bucket.full_name(), s };
							}
						})
						.apply(Optional.ofNullable(i.resource_name_or_id()).orElse(""));

						return Lambdas.get(() -> {
							if (!bucket_subchannel[0].equals(my_bucket.full_name()) || !bucket_subchannel[1].isEmpty())
							{
								return Arrays.asList(_storage_service.getBucketRootPath() + bucket_subchannel[0] + IStorageService.TRANSIENT_DATA_SUFFIX + bucket_subchannel[1]);
							}
							else { // This is my input directory
								return Arrays.asList(_storage_service.getBucketRootPath() + my_bucket.full_name() + IStorageService.TO_IMPORT_DATA_SUFFIX);
							}
						});
					}
					else { // storage service ... 3 options :raw, :json, :processed (defaults to :processed)
						
						final String bucket_name = i.resource_name_or_id().split(":")[0];
						final String sub_service = 
										Patterns.match(i.resource_name_or_id()).<String>andReturn()
													.when(s -> s.endsWith(":raw"), __ -> "raw/current/") // (input paths are always from primary)
													.when(s -> s.endsWith(":json"), __ -> "json/current/")
													.otherwise(__ -> "processed/current/");
						
						//TODO: ALEPH-12 enable time-based filtering
						if (null != i.filter()) {
							throw new RuntimeException(ErrorUtils.get(ErrorUtils.NOT_YET_IMPLEMENTED) + ": input.filter");
						}
						final String suffix = "**/*";
						return Arrays.asList(_storage_service.getBucketRootPath() + bucket_name + IStorageService.STORED_DATA_SUFFIX + sub_service + suffix);
					}
				}))
				.orElse(Collections.emptyList())
				;		
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#checkForListeners(java.util.Optional, java.util.Optional)
	 */
	@Override
	public boolean checkForListeners(final Optional<DataBucketBean> bucket, final AnalyticThreadJobBean job) {
		throw new RuntimeException("Not part of mock analytics context for Storm");
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getAnalyticsContextLibraries(java.util.Optional)
	 */
	@Override
	public List<String> getAnalyticsContextLibraries(
			final Optional<Set<Tuple2<Class<? extends IUnderlyingService>, Optional<String>>>> services) {
		return Collections.emptyList();
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getAnalyticsLibraries(java.util.Optional)
	 */
	@Override
	public CompletableFuture<Map<String, String>> getAnalyticsLibraries(final Optional<DataBucketBean> bucket, final Collection<AnalyticThreadJobBean> jobs) {
		//(just return empty, only used with local storm controller)
		return CompletableFuture.completedFuture(Collections.emptyMap());
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getGlobalAnalyticTechnologyObjectStore(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <S> ICrudService<S> getGlobalAnalyticTechnologyObjectStore(
			final Class<S> clazz, final Optional<String> collection) {
		return this.getBucketObjectStore(clazz, Optional.empty(), collection, Optional.of(AssetStateDirectoryBean.StateDirectoryType.library));
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getLibraryObjectStore(java.lang.Class, java.util.Optional)
	 */
	@Override
	public <S> Optional<ICrudService<S>> getLibraryObjectStore(final Class<S> clazz, final String name_or_id, final Optional<String> collection)
	{
		return Optional.ofNullable(this.getLibraryConfigs().get(name_or_id))
				.map(module_lib -> _core_management_db.getPerLibraryState(clazz, module_lib, collection));
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getBucketObjectStore(java.lang.Class, java.util.Optional, java.util.Optional, java.util.Optional)
	 */
	@Override
	public <S> ICrudService<S> getBucketObjectStore(final Class<S> clazz,
			final Optional<DataBucketBean> bucket, final Optional<String> collection,
			final Optional<StateDirectoryType> type)
	{
		final Optional<DataBucketBean> this_bucket = 
				bucket
					.map(x -> Optional.of(x))
					.orElseGet(() -> _mutable_state.bucket.isSet() 
										? Optional.of(_mutable_state.bucket.get()) 
										: Optional.empty());
		
		return Patterns.match(type).<ICrudService<S>>andReturn()
				.when(t -> t.isPresent() && AssetStateDirectoryBean.StateDirectoryType.enrichment == t.get(), 
						__ -> _core_management_db.getBucketEnrichmentState(clazz, this_bucket.get(), collection))
				.when(t -> t.isPresent() && AssetStateDirectoryBean.StateDirectoryType.harvest == t.get(), 
						__ -> _core_management_db.getBucketHarvestState(clazz, this_bucket.get(), collection))
				// assume this is the technology context, most likely usage
				.when(t -> t.isPresent() && AssetStateDirectoryBean.StateDirectoryType.library == t.get(), 
						__ -> _core_management_db.getPerLibraryState(clazz, this.getTechnologyConfig(), collection))
				// default: analytics or not specified: analytics
				.otherwise(__ -> _core_management_db.getBucketAnalyticThreadState(clazz, this_bucket.get(), collection))
				;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getBucket()
	 */
	@Override
	public Optional<DataBucketBean> getBucket() {
		return _mutable_state.bucket.isSet() ? Optional.of(_mutable_state.bucket.get()) : Optional.empty();
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getLibraryConfig()
	 */
	@Override
	public SharedLibraryBean getTechnologyConfig() {
		return _mutable_state.technology_config.get();
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getModuleConfig()
	 */
	@Override
	public Map<String, SharedLibraryBean> getLibraryConfigs() {
		return _mutable_state.library_configs.isSet()
				? _mutable_state.library_configs.get()
				: Collections.emptyMap();
	}
		
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getBucketStatus(java.util.Optional)
	 */
	@Override
	public CompletableFuture<DataBucketStatusBean> getBucketStatus(
			final Optional<DataBucketBean> bucket) {
		return this._core_management_db
				.readOnlyVersion()
				.getDataBucketStatusStore()
				.getObjectById(bucket.orElseGet(() -> _mutable_state.bucket.get())._id())
				.thenApply(opt_status -> opt_status.get());		
		// (ie will exception if not present)
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#logStatusForThreadOwner(java.util.Optional, com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean, boolean)
	 */
	@Override
	public void logStatusForThreadOwner(final Optional<DataBucketBean> bucket,
			final BasicMessageBean message, final boolean roll_up_duplicates) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#logStatusForThreadOwner(java.util.Optional, com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean)
	 */
	@Override
	public void logStatusForThreadOwner(final Optional<DataBucketBean> bucket,
			final BasicMessageBean message) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#emergencyDisableBucket(java.util.Optional)
	 */
	@Override
	public void emergencyDisableBucket(final Optional<DataBucketBean> bucket) {
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#emergencyQuarantineBucket(java.util.Optional, java.lang.String)
	 */
	@Override
	public void emergencyQuarantineBucket(
			final Optional<DataBucketBean> bucket,
			final String quarantine_duration)
	{
		throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#sendObjectToStreamingPipeline(java.util.Optional, java.util.Optional, fj.data.Either)
	 */
	@Override
	public void sendObjectToStreamingPipeline(final Optional<DataBucketBean> bucket,
			final AnalyticThreadJobBean job, 
			final Either<JsonNode, Map<String, Object>> object, 
			final Optional<AnnotationBean> annotations)
	{
		if (annotations.isPresent()) {
			throw new RuntimeException(ErrorUtils.NOT_YET_IMPLEMENTED);			
		}
		final JsonNode obj_json =  object.either(__->__, map -> (JsonNode) _mapper.convertValue(map, JsonNode.class));

		this.getOutputTopic(bucket, job).ifPresent(topic -> {				
			if (_distributed_services.doesTopicExist(topic)) {
				// (ie someone is listening in on our output data, so duplicate it for their benefit)
				_distributed_services.produce(topic, obj_json.toString());
			}
		});
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getServiceInput(java.lang.Class, java.util.Optional, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean.AnalyticThreadJobInputBean)
	 */
	@Override
	public <T extends IAnalyticsAccessContext<?>> Optional<T> getServiceInput(
			final Class<T> clazz, 
			final Optional<DataBucketBean> bucket,
			final AnalyticThreadJobBean job, 
			final AnalyticThreadJobInputBean job_input)
	{	
		throw new RuntimeException("Not part of mock analytics context for Storm");
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#getServiceOutput(java.lang.Class, java.util.Optional, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean, java.lang.String)
	 */
	@Override
	public <T extends IAnalyticsAccessContext<?>> Optional<T> getServiceOutput(
			final Class<T> clazz, 
			final Optional<DataBucketBean> bucket,
			final AnalyticThreadJobBean job, 
			final String data_service)
	{
		throw new RuntimeException("Not part of mock analytics context for Storm");
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_analytics.IAnalyticsContext#emitObject(java.util.Optional, com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean, fj.data.Either, java.util.Optional)
	 */
	@Override
	public void emitObject(final Optional<DataBucketBean> bucket,
			final AnalyticThreadJobBean job,
			final Either<JsonNode, Map<String, Object>> object, 
			final Optional<AnnotationBean> annotations)
	{
		final DataBucketBean this_bucket = bucket.orElseGet(() -> _mutable_state.bucket.get()); 
		
		if (annotations.isPresent()) {
			throw new RuntimeException(ErrorUtils.get(HadoopErrorUtils.NOT_YET_IMPLEMENTED, "annotations"));			
		}
		final JsonNode obj_json =  object.either(__->__, map -> (JsonNode) _mapper.convertValue(map, JsonNode.class));
		
		if (_batch_index_service.isPresent()) {
			_batch_index_service.get().storeObject(obj_json);
		}
		else if (_crud_index_service.isPresent()){ // (super slow)
			_crud_index_service.get().storeObject(obj_json);
		}
		if (_batch_storage_service.isPresent()) {
			_batch_storage_service.get().storeObject(obj_json);
		}
		else if (_crud_storage_service.isPresent()){ // (super slow)
			_crud_storage_service.get().storeObject(obj_json);
		}
		
		final String topic = _distributed_services.generateTopicName(this_bucket.full_name(), ICoreDistributedServices.QUEUE_END_NAME);
		if (_distributed_services.doesTopicExist(topic)) {
			// (ie someone is listening in on our output data, so duplicate it for their benefit)
			_distributed_services.produce(topic, obj_json.toString());
		}
		//(else nothing to do)
	}
}
