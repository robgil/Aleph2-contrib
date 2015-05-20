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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;

import org.apache.log4j.Logger;
import org.checkerframework.checker.nullness.qual.NonNull;

import com.ikanow.aleph2.data_model.interfaces.data_services.IStorageService;

import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.Configuration;

public class HDFSStorageService implements IStorageService {

	private static final Logger logger = Logger.getLogger(HDFSStorageService.class);

	
	@Override
	public String getRootPath() {
		return "/";
	}

	@Override
	public <T> @NonNull T getUnderlyingPlatformDriver(
			@NonNull Class<T> driver_class, Optional<String> driver_options) {
		T driver = null;
		try {
		if(driver_class!=null){
			if(driver_class.isAssignableFrom(AbstractFileSystem.class)){
				URI uri = getUri();
				AbstractFileSystem fs = AbstractFileSystem.createFileSystem(uri, getConfiguration());
				return (@NonNull T) fs;
			}
			try {
				driver = driver_class.newInstance();
			} catch (Exception e) {
				logger.error("Error instamciating driver class",e);
			}
		} // !=null
		} catch (Exception e) {
			logger.error("Caught Exception:",e);
		}
		return driver;
	}

	/** 
	 * Override this function with system specific configuration
	 * @return
	 */
	protected Configuration getConfiguration(){
		Configuration config = new Configuration();
		
		// TODO DEBUG Comment in for debugging environment settings
		//Map<String, String> m = System.getenv();
		String hadoopConfDir = System.getenv("HADOOP_CONF_DIR");
		if(hadoopConfDir!=null){
			
			config.addResource(hadoopConfDir+"hdfs-site.xml");
		}else{
			// add resource from classpath
			config.addResource("default_fs.xml");			
		}
		return config;
		
	}

	protected URI getUri(){
		URI uri = null;
		try {
			uri = new URI("hdfs://localhost:7000");
		} catch (URISyntaxException e) {
			logger.error("Caught Exception:",e);
		}
		return uri;
	}

}