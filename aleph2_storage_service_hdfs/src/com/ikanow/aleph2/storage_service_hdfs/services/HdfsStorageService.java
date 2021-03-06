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
package com.ikanow.aleph2.storage_service_hdfs.services;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.codepoetics.protonpack.StreamUtils;
import com.google.inject.Inject;
import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IDataWriteService;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.StorageSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.objects.shared.GlobalPropertiesBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.Optionals;
import com.ikanow.aleph2.data_model.utils.TimeUtils;
import com.ikanow.aleph2.data_model.utils.Tuples;
import com.ikanow.aleph2.storage_service_hdfs.utils.HdfsErrorUtils;

import fj.data.Validation;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Options.Rename;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RawLocalFileSystem;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.ImmutableMap;

import scala.Tuple2;

/** The storage service implementation backed by HDFS (not actually clear if another implementation is possible)
 * @author alex
 */
public class HdfsStorageService implements IStorageService {
	private static final Logger _logger = LogManager.getLogger();	
	
	final protected GlobalPropertiesBean _globals;
	final protected ConcurrentHashMap<Tuple2<String, Optional<String>>, Optional<Object>> _obj_cache = new ConcurrentHashMap<>();
	
	@Inject 
	HdfsStorageService(GlobalPropertiesBean globals) {
		_globals = globals;	
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService#getRootPath()
	 */
	@Override
	public String getRootPath() {		
		return _globals.distributed_root_dir();
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService#getBucketRootPath()
	 */
	@Override
	public String getBucketRootPath() {		
		return getRootPath() + "/data/";
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(
			Class<T> driver_class, Optional<String> driver_options) {
		
		return (Optional<T>)_obj_cache.computeIfAbsent(Tuples._2T(driver_class.toString(), driver_options), 
				__ -> getUnderlyingPlatformDriver_internal(driver_class, driver_options).map(o -> (T)o)
				);
	}
	
	/** Internal version of getUnderlyingPlatform driver, ie input to cache
	 * @param driver_class
	 * @param driver_options
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public <T> Optional<T> getUnderlyingPlatformDriver_internal(
			Class<T> driver_class, Optional<String> driver_options) {
		T driver = null;
		try {
			if(driver_class!=null){
				final Configuration config = getConfiguration();
				URI uri = driver_options.isPresent() ? new URI(driver_options.get()) : getUri(config);
				
				if (driver_class.isAssignableFrom(AbstractFileSystem.class)) {
	
					AbstractFileSystem fs = AbstractFileSystem.createFileSystem(uri, config);
					
					return (Optional<T>) Optional.of(fs);
				}			
				else if(driver_class.isAssignableFrom(FileContext.class)){				
					FileContext fs = FileContext.getFileContext(AbstractFileSystem.createFileSystem(uri, config), config);
					return (Optional<T>) Optional.of(fs);
				}
				else if(driver_class.isAssignableFrom(RawLocalFileSystem.class)){
					return Optional.of(driver_class.newInstance());
				}
			} // !=null
		} 
		catch (Exception e) {
			_logger.error("Caught Exception:",e);
		}
		return Optional.ofNullable(driver);
	}

	/** 
	 * Override this function with system specific configuration
	 * @return
	 */
	protected Configuration getConfiguration(){
		Configuration config = new Configuration(false);
		
		if (new File(_globals.local_yarn_config_dir()).exists()) {
			config.addResource(new Path(_globals.local_yarn_config_dir() +"/yarn-site.xml"));
			config.addResource(new Path(_globals.local_yarn_config_dir() +"/core-site.xml"));
			config.addResource(new Path(_globals.local_yarn_config_dir() +"/hdfs-site.xml"));
		}
		else {
			config.addResource("default_fs.xml");						
		}
		// These are not added by Hortonworks, so add them manually
		config.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem");									
		config.set("fs.AbstractFileSystem.hdfs.impl", "org.apache.hadoop.fs.Hdfs");
		
		return config;
		
	}

	protected URI getUri(Configuration configuration){
		URI uri = null;
		try {			
			String uriStr = configuration.get("fs.default.name", configuration.get("fs.defaultFS"));
			if(uriStr==null){
				// default with localhost
				uriStr = "hdfs://localhost:8020";
			}
			uri = new URI(uriStr);
		} catch (URISyntaxException e) {
			_logger.error("Caught Exception:",e);
		}
		return uri;
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService#validateSchema(com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.StorageSchemaBean)
	 */
	@Override
	public Tuple2<String, List<BasicMessageBean>> validateSchema(final StorageSchemaBean schema, final DataBucketBean bucket) {
		
		final LinkedList<BasicMessageBean> errors = new LinkedList<>();
		
		// Note don't currently need to check the temporal fields because that's handled in the core (not sure why, should get moved at some point?)

		Arrays.asList(
				Optionals.of(() -> schema.raw()),
				Optionals.of(() -> schema.json()),
				Optionals.of(() -> schema.processed())
				)
				.forEach(sub_schema -> validateCodec(sub_schema).ifPresent(error -> errors.add(error)));

		return errors.isEmpty()
				? Tuples._2T(this.getBucketRootPath() + bucket.full_name() + IStorageService.BUCKET_SUFFIX,  Collections.emptyList())
				: Tuples._2T("",  errors)
				;
	}

	/** Check the codec against the list of supported codecs
	 * @param to_validate
	 * @return
	 */
	protected static Optional<BasicMessageBean> validateCodec(Optional<StorageSchemaBean.StorageSubSchemaBean> to_validate) {
		return to_validate
			.map(v -> v.codec())
			.filter(codec -> !codec.equalsIgnoreCase("gz"))
			.filter(codec -> !codec.equalsIgnoreCase("gzip"))
			.filter(codec -> !codec.equalsIgnoreCase("snappy"))
			.filter(codec -> !codec.equalsIgnoreCase("sz"))
			.filter(codec -> !codec.equalsIgnoreCase("snappy_framed"))
			.filter(codec -> !codec.equalsIgnoreCase("fr.sz"))
			.map(codec -> ErrorUtils.buildErrorMessage("HDFSStorageService", "validateCodec", HdfsErrorUtils.CODEC_NOT_SUPPORTED, codec));
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IUnderlyingService#getUnderlyingArtefacts()
	 */
	@Override
	public Collection<Object> getUnderlyingArtefacts() {
		// myself and whereever the FileContext lives
		return Arrays.asList(this, this.getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get());
	}

	////////////////////////////////////////////////////////////////////////////////
	
	// GENERIC DATA INTERFACE
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider#getDataService()
	 */
	@Override
	public Optional<IDataServiceProvider.IGenericDataService> getDataService() {
		return _data_service;
	}

	/** Implementation of GenericDataService
	 * @author alex
	 */
	public class HdfsDataService implements IDataServiceProvider.IGenericDataService {

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#getWritableDataService(java.lang.Class, com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional, java.util.Optional)
		 */
		@Override
		public <O> Optional<IDataWriteService<O>> getWritableDataService(
				Class<O> clazz, DataBucketBean bucket,
				Optional<String> options, Optional<String> secondary_buffer) {
			
			final IStorageService.StorageStage stage = options.map(option -> IStorageService.StorageStage.valueOf(option)).orElse(StorageStage.processed);
			
			// Check if the stage is enabled:
			
			return Optionals.of(() -> bucket.data_schema().storage_schema())
								.filter(storage -> 
											Optional.ofNullable(storage.enabled()).orElse(true))
								.map(storage -> 
											HfdsDataWriteService.getStorageSubSchema(storage, stage))
								.filter(substore -> 
												Optional.ofNullable(substore.enabled()).orElse(true))
								.map(__ -> 
										new HfdsDataWriteService<O>(bucket, this, stage, Optional.empty(), HdfsStorageService.this, secondary_buffer))
								;
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#getReadableCrudService(java.lang.Class, java.util.Collection, java.util.Optional)
		 */
		@Override
		public <O> Optional<ICrudService<O>> getReadableCrudService(
				Class<O> clazz, Collection<DataBucketBean> buckets,
				Optional<String> options) {
			// Not supported by HDFS
			return Optional.empty();
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#getSecondaryBufferList(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean)
		 */
		@Override
		public Set<String> getSecondaryBuffers(DataBucketBean bucket) {
			final FileContext fc = HdfsStorageService.this.getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get();
			
			final Function<Path, Stream<String>> getSubdirs = path -> {
				try {
					RemoteIterator<FileStatus> it = fc.listStatus(path);
					return StreamUtils
								.takeWhile(Stream.generate(() -> it), Lambdas.wrap_filter_u(ii -> ii.hasNext()))
								.map(Lambdas.wrap_u(ii -> ii.next()))
								.filter(fs -> fs.isDirectory())
								.map(fs -> fs.getPath().getName())
								.filter(name -> !name.equals("current"))
								;
				}
				catch (Exception e) {
					return Stream.empty();
				}				
			};
			final Path raw_top_level = new Path(HdfsStorageService.this.getBucketRootPath() + bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY);
			Stream<String> s1 = getSubdirs.apply(raw_top_level);
			
			final Path json_top_level = new Path(HdfsStorageService.this.getBucketRootPath() + bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY);
			Stream<String> s2 = getSubdirs.apply(json_top_level);
			
			final Path px_top_level = new Path(HdfsStorageService.this.getBucketRootPath() + bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY);
			Stream<String> s3 = getSubdirs.apply(px_top_level);
			
			return Stream.of(s1, s2, s3).flatMap(s -> s).collect(Collectors.toSet());
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#switchCrudServiceToPrimaryBuffer(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional)
		 */
		@Override
		public CompletableFuture<BasicMessageBean> switchCrudServiceToPrimaryBuffer(
				DataBucketBean bucket, Optional<String> secondary_buffer, final Optional<String> new_name_for_ex_primary)
		{
			final FileContext fc = HdfsStorageService.this.getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get();
			
			// OK this is slightly unpleasant because of the lack of support for symlinks
			
			//  Basically we're going to remove or rename "current" and then move the secondary buffer across as quickly as we can (that's 2 atomic operations if you're counting!)
			
			final Path raw_top_level = new Path(HdfsStorageService.this.getBucketRootPath() + bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY);
			final Path json_top_level = new Path(HdfsStorageService.this.getBucketRootPath() + bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY);
			final Path px_top_level = new Path(HdfsStorageService.this.getBucketRootPath() + bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY);
			
			final Function<Path, Validation<Throwable, Path>> moveOrDeleteDir = path -> {
				final Path path_to_move = Path.mergePaths(path, new Path(IStorageService.PRIMARY_BUFFER_SUFFIX));				
				return Optional.of(new_name_for_ex_primary.orElse(IStorageService.EX_PRIMARY_BUFFER_SUFFIX))
						.filter(name -> !name.isEmpty())
						.map(Lambdas.wrap_e((name -> {
							//(NOTE ABOUT MERGE PATHS - IF STARTS WITH // THEN ASSUMES IS FS PROTOCOL AND IGNORES) 
							fc.rename(path_to_move, Path.mergePaths(path, new Path(name.startsWith("/") ? name : "/" + name)), Rename.OVERWRITE);					
							return path;
						})))
						.orElseGet(Lambdas.wrap_e(() -> { // just delete it
							fc.delete(path_to_move, true);					
							return path;
						}));
			};
			final Function<Path, Validation<Throwable, Path>> moveToPrimary = Lambdas.wrap_e(path -> {

				//(NOTE ABOUT MERGE PATHS - IF STARTS WITH // THEN ASSUMES IS FS PROTOCOL AND IGNORES) 
				final Path src_path = Path.mergePaths(path, new Path(secondary_buffer.map(s -> s.startsWith("/") ? s : "/" + s).orElse(IStorageService.EX_PRIMARY_BUFFER_SUFFIX)));
				final Path dst_path = Path.mergePaths(path, new Path(IStorageService.PRIMARY_BUFFER_SUFFIX));
				try {
					fc.rename(src_path, dst_path, Rename.OVERWRITE);
				}
				catch (FileNotFoundException e) { // this one's OK, just create the end dir
					fc.mkdir(dst_path, FsPermission.getDirDefault(), true);
				}
				return path;
			});			
			
			final Validation<Throwable, Path> err1a = moveOrDeleteDir.apply(raw_top_level);
			final Validation<Throwable, Path> err1b = moveToPrimary.apply(raw_top_level);
			final Validation<Throwable, Path> err2a = moveOrDeleteDir.apply(json_top_level);
			final Validation<Throwable, Path> err2b = moveToPrimary.apply(json_top_level);
			final Validation<Throwable, Path> err3a = moveOrDeleteDir.apply(px_top_level);
			final Validation<Throwable, Path> err3b = moveToPrimary.apply(px_top_level);
			
			final Stream<Throwable> errs = Stream.of(err1a, err1b, err2a, err2b, err3a, err3b)
													.flatMap(err -> err.validation(fail -> Stream.of(fail), success -> Stream.empty()));
			
			final Stream<Throwable> critical_errs = Stream.of(err1b, err2b, err3b).flatMap(err -> err.validation(fail -> Stream.of(fail), success -> Stream.empty()));
			
			return CompletableFuture.completedFuture(ErrorUtils.buildMessage(
					!critical_errs.findFirst().isPresent(), this.getClass().getName(), "switchCrudServiceToPrimaryBuffer", 
						errs.map(t -> ErrorUtils.getLongForm("{0}", t)).collect(Collectors.joining(";"))));
		}

		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#getPrimaryBufferName(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean)
		 */
		@Override
		public Optional<String> getPrimaryBufferName(DataBucketBean bucket) {
			// We can't infer the primary buffer name for HDFS
			return Optional.empty();
		}		
		
		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#handleAgeOutRequest(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean)
		 */
		@Override
		public CompletableFuture<BasicMessageBean> handleAgeOutRequest(final DataBucketBean bucket) {
			// Loop over secondary buffers:
			this.getSecondaryBuffers(bucket).stream().forEach(sec -> handleAgeOutRequest(bucket, Optional.of(sec)));
			
			// Only return the results from the main bucket:
			return handleAgeOutRequest(bucket, Optional.empty());
		}		
		/** handleAgeOutRequest that can be applied to secondaries as well as primaries
		 * @param bucket
		 * @param secondary_buffer
		 * @return
		 */
		public CompletableFuture<BasicMessageBean> handleAgeOutRequest(final DataBucketBean bucket, final Optional<String> secondary_buffer) {
			final String buffer_name = secondary_buffer.orElse(IStorageService.PRIMARY_BUFFER_SUFFIX);
			
			final Optional<BasicMessageBean> raw_result =
					Optionals.of(() -> bucket.data_schema().storage_schema().raw().exist_age_max())
					.map(bound -> handleAgeOutRequest_Worker(IStorageService.StorageStage.raw, bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + buffer_name, bound));
			
			final Optional<BasicMessageBean> json_result =
					Optionals.of(() -> bucket.data_schema().storage_schema().json().exist_age_max())
					.map(bound -> handleAgeOutRequest_Worker(IStorageService.StorageStage.json, bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + buffer_name, bound));
			
			final Optional<BasicMessageBean> processed_result =
					Optionals.of(() -> bucket.data_schema().storage_schema().processed().exist_age_max())
					.map(bound -> handleAgeOutRequest_Worker(IStorageService.StorageStage.processed, bucket.full_name() + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + buffer_name, bound));
			
			return Arrays.asList(raw_result, json_result, processed_result)
					.stream()
					.filter(o -> o.isPresent())
					.map(o -> o.get())
					.reduce(Optional.<BasicMessageBean>empty()
						,
						(acc, v) -> {
							return acc
								.map(accv -> {
									return BeanTemplateUtils.clone(accv)
												.with(BasicMessageBean::success, accv.success() && v.success())
												.with(BasicMessageBean::message,
														accv.message() + "; " + v.message())
												.with(BasicMessageBean::details,
														null == accv.details()
														? v.details()
														: accv.details() // (slightly hacky but only create details if we're adding loggable)
														)												
											.done();
								})
								.map(Optional::of)
								.orElse(Optional.of(v));
						},
						(acc1, acc2) -> acc1 // (can't happen since not parallel)
					)
					.map(CompletableFuture::completedFuture)
					.orElseGet(() -> {
						return CompletableFuture.completedFuture(ErrorUtils.buildSuccessMessage(
								HdfsStorageService.class.getSimpleName(), 
								"handleAgeOutRequest", HdfsErrorUtils.NO_AGE_OUT_SETTINGS));									
					})
					;
		}

		/** Actual processing to perform to age out check
		 * @param path - the path (including raw/json/processed)
		 * @param lower_bound - the user specified maximum age
		 * @return if age out specified then the status of the age out
		 */
		protected BasicMessageBean handleAgeOutRequest_Worker(final StorageStage stage, final String path, final String lower_bound) {
			
			// 0) Convert the bound into a time

			final Date now = new Date();
			final Optional<Long> deletion_bound = Optional.of(lower_bound)
					.map(s -> TimeUtils.getDuration(s, Optional.of(now)))
					.filter(Validation::isSuccess)
					.map(v -> v.success())
					.map(duration -> 1000L*duration.getSeconds())
					.map(offset -> now.getTime() - offset)
				;
		
			if (!deletion_bound.isPresent()) {
				return ErrorUtils.buildErrorMessage(HdfsStorageService.class.getSimpleName(), 
						"handleAgeOutRequest", HdfsErrorUtils.AGE_OUT_SETTING_NOT_PARSED, stage.toString(), lower_bound.toString());
			}
						
			// 1) Get all the sub-directories of Path

			final FileContext dfs = getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get();
			final String bucket_root = getBucketRootPath() + "/" + path;
			try {			
				final Path p = new Path(bucket_root);
				final RemoteIterator<FileStatus> it = dfs.listStatus(p);
				
				final AtomicInteger n_deleted = new AtomicInteger(0);
				while (it.hasNext()) {
					final FileStatus file = it.next();					
					final String dir = file.getPath().getName();
					
					// 2) Any of them that are "date-like" get checked
										
					final Validation<String, Date> v = TimeUtils.getDateFromSuffix(dir);
					v.filter(d -> d.getTime() <= deletion_bound.get())
						.forEach(Lambdas.wrap_consumer_u(s -> {
							n_deleted.incrementAndGet();
							dfs.delete(file.getPath(), true);
						}));					
				}
				final BasicMessageBean message = ErrorUtils.buildSuccessMessage(HdfsStorageService.class.getSimpleName(), 
						"handleAgeOutRequest",
						"{0}: deleted {1} directories", stage.toString(), n_deleted.get());
				
				return (n_deleted.get() > 0)
						? BeanTemplateUtils.clone(message).with(BasicMessageBean::details, 
								ImmutableMap.builder().put("loggable", true).build()
								).done()
						: message
						;
			}
			catch (Exception e) {
				return ErrorUtils.buildErrorMessage(HdfsStorageService.class.getSimpleName(), 
						"handleAgeOutRequest", ErrorUtils.getLongForm("{1} error = {0}", e, stage.toString()));
			}			
		}
		
		/* (non-Javadoc)
		 * @see com.ikanow.aleph2.data_model.interfaces.shared_services.IDataServiceProvider.IGenericDataService#handleBucketDeletionRequest(com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean, java.util.Optional, boolean)
		 */
		@Override
		public CompletableFuture<BasicMessageBean> handleBucketDeletionRequest(
				final DataBucketBean bucket, 
				final Optional<String> secondary_buffer,
				final boolean bucket_or_buffer_getting_deleted) {
			
			if (bucket_or_buffer_getting_deleted && !secondary_buffer.isPresent()) { // if we're deleting the bucket and nothing else is present then
				// delete all the secondary buffers
				getSecondaryBuffers(bucket).stream().parallel().forEach(buffer -> handleBucketDeletionRequest(bucket, Optional.of(buffer), true));				
			}
			
			final FileContext dfs = getUnderlyingPlatformDriver(FileContext.class, Optional.empty()).get();
			final String bucket_root = getBucketRootPath() + "/" + bucket.full_name();
			try {			
				final String buffer_suffix = secondary_buffer.orElse(IStorageService.PRIMARY_BUFFER_SUFFIX);
				final Path p_raw = new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_RAW_SECONDARY + buffer_suffix);
				final Path p_json = new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_JSON_SECONDARY + buffer_suffix);
				final Path p_px = new Path(bucket_root + IStorageService.STORED_DATA_SUFFIX_PROCESSED_SECONDARY + buffer_suffix);
				
				// Also some transient paths:
				final Path p_transients = new Path(bucket_root + IStorageService.TRANSIENT_DATA_SUFFIX_SECONDARY + buffer_suffix);
				
				final List<BasicMessageBean> results = Stream.of(p_raw, p_json, p_px, p_transients).map(p -> {
					try {
						if (doesPathExist(dfs, p)) {			
							dfs.delete(p, true);			
							if (!secondary_buffer.isPresent() || !bucket_or_buffer_getting_deleted) {
								dfs.mkdir(p, FsPermission.getDefault(), true); //(deletes then re-creates ... for secondaries may not re-create)
							}
							
							return ErrorUtils.buildSuccessMessage("HdfsDataService", "handleBucketDeletionRequest", ErrorUtils.get("Deleted data from bucket = {0} (path={1})", bucket.full_name(), p.toString()));
						}
						else {
							return ErrorUtils.buildSuccessMessage("HdfsDataService", "handleBucketDeletionRequest", ErrorUtils.get("(No storage for bucket {0} (path={1}))", bucket.full_name()), p);
						}
					}
					catch (Throwable t) { // (carry on here since we have multiple paths to check)
						return ErrorUtils.buildErrorMessage("HdfsDataService", "handleBucketDeletionRequest", ErrorUtils.getLongForm("Unknown error for bucket {1}  (path={2}): {0})", t, bucket.full_name(), p));
					}
				})
				.collect(Collectors.toList())
				;				
				return CompletableFuture.completedFuture(
						ErrorUtils.buildMessage(results.stream().allMatch(msg -> msg.success()), 
							"HdfsDataService", "handleBucketDeletionRequest",
							results.stream().map(msg -> msg.message()).collect(Collectors.joining(" ; "))));
			}
			catch (Throwable t) {			
				return CompletableFuture.completedFuture(
						ErrorUtils.buildErrorMessage("HdfsDataService", "handleBucketDeletionRequest", ErrorUtils.getLongForm("Error deleting data from bucket = {1} : {0}", t, bucket.full_name()))
						);
			}
		}
	}
	protected Optional<IDataServiceProvider.IGenericDataService> _data_service = Optional.of(new HdfsDataService());
	
	/////////////////////////////////////////////////////////////////////////////////
	
	// UTILITY
	
	/** Utility function for checking existence of a path
	 * @param dfs
	 * @param path
	 * @param storage_service
	 * @return
	 * @throws Exception
	 */
	public static boolean doesPathExist(final FileContext dfs, final Path path) throws Exception {
		try {
			dfs.getFileStatus(path);
			return true;
		}
		catch (FileNotFoundException fe) {
			return false;
		} 		
	}	
		
}
