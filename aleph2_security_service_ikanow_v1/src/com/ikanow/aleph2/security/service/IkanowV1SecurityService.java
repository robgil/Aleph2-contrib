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
 *******************************************************************************/
package com.ikanow.aleph2.security.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.shiro.cache.CacheManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.subject.Subject;

import com.google.inject.Inject;
import com.google.inject.Module;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IExtraDependencyLoader;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISecurityService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IServiceContext;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ISubject;
import com.ikanow.aleph2.security.module.IkanowV1SecurityModule;

public class IkanowV1SecurityService extends SecurityService implements ISecurityService, IExtraDependencyLoader{
	
	
	protected ISubject currentSubject = null;
	private static final Logger logger = LogManager.getLogger(IkanowV1SecurityService.class);
	@Inject
	protected IServiceContext serviceContext;
	
	@Inject
	public IkanowV1SecurityService(IServiceContext serviceContext, SecurityManager securityManager, CacheManager cacheManager) {
		super(serviceContext,securityManager,cacheManager);

	}


	protected void initUnauthorized(){
		try {
			logger.debug("Init was called, it should not be called except in rare cases, use login instead.");

	        // get the currently executing user:
	        Subject currentUser = getShiroSubject();
	        this.currentSubject = new SubjectWrapper(currentUser);
	        // Do some stuff with a Session (no need for a web or EJB container!!!)
	        
		} catch (Throwable e) {
			logger.error("initUnauthorized Caught exception",e);
		}

	}
	

	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(Class<T> driver_class, Optional<String> driver_options) {
		return Optional.empty();
	}

	

	static IkanowV1SecurityModule _temp;
	
	public static List<Module> getExtraDependencyModules() {
		return Arrays.asList((Module)(_temp = new IkanowV1SecurityModule()));
	}

	public void killMe() throws Exception {
		if (null != _temp) {
			_temp.destroy();
			
		}
	}
	

	@Override
	public void youNeedToImplementTheStaticFunctionCalled_getExtraDependencyModules() {
		// TODO Auto-generated method stub
		
	}





	@Override
	public ISubject loginAsSystem() {
		return super.loginAsSystem();
	}

	@Override
	protected String getRealmName(){
		return IkanowV1Realm.class.getName();
	}

}
