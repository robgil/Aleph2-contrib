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
package com.ikanow.aleph2.analytics.hadoop.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadBean;
import com.ikanow.aleph2.data_model.objects.data_analytics.AnalyticThreadJobBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean.MasterEnrichmentType;
import com.ikanow.aleph2.data_model.objects.data_import.EnrichmentControlMetadataBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;

public class TestHadoopTechnologyUtils {

	@Test
	public void test_globalValidation() {

		// Pass: both present but streaming not being used
		{
			final DataBucketBean test_bucket1 = BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::_id, "test")
					.with(DataBucketBean::full_name, "/test")
					.with(DataBucketBean::master_enrichment_type, DataBucketBean.MasterEnrichmentType.streaming)
					.with(DataBucketBean::streaming_enrichment_topology, BeanTemplateUtils.build(EnrichmentControlMetadataBean.class).with(EnrichmentControlMetadataBean::enabled, false).done().get())
					.with(DataBucketBean::analytic_thread, 
							BeanTemplateUtils.build(AnalyticThreadBean.class)
							.with(AnalyticThreadBean::jobs, Arrays.asList(BeanTemplateUtils.build(AnalyticThreadJobBean.class).with(AnalyticThreadJobBean::enabled, false).done().get())
									)
									.done().get()
							)
							.done().get();
						
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJobs(test_bucket1, Collections.emptyList());
			
			assertTrue("Validation should pass", res1.success());
			assertEquals("Correct error message: " + res1.message(), "", res1.message());
			
		}
		// Pass: both present but disabled 
		{
			final DataBucketBean test_bucket1 = BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::_id, "test")
					.with(DataBucketBean::full_name, "/test")
					.with(DataBucketBean::master_enrichment_type, DataBucketBean.MasterEnrichmentType.batch)
					.with(DataBucketBean::streaming_enrichment_topology, BeanTemplateUtils.build(EnrichmentControlMetadataBean.class).with(EnrichmentControlMetadataBean::enabled, false).done().get())
					.with(DataBucketBean::analytic_thread, 
							BeanTemplateUtils.build(AnalyticThreadBean.class)
							.with(AnalyticThreadBean::jobs, Arrays.asList(BeanTemplateUtils.build(AnalyticThreadJobBean.class).with(AnalyticThreadJobBean::enabled, false).done().get())
									)
									.done().get()
							)
							.done().get();
						
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJobs(test_bucket1, Collections.emptyList());
			
			assertTrue("Validation should pass", res1.success());
			assertEquals("Correct error message: " + res1.message(), "", res1.message());
			
		}
		// Pass: only one present (enrichment) 
		{
			final DataBucketBean test_bucket1 = BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::_id, "test")
					.with(DataBucketBean::full_name, "/test")
					.with(DataBucketBean::master_enrichment_type, DataBucketBean.MasterEnrichmentType.batch)
					.with(DataBucketBean::batch_enrichment_configs, Arrays.asList(BeanTemplateUtils.build(EnrichmentControlMetadataBean.class).with(EnrichmentControlMetadataBean::enabled, true).done().get()))
					.done().get();
						
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJobs(test_bucket1, Collections.emptyList());
			
			assertTrue("Validation should pass", res1.success());
			assertEquals("Correct error message: " + res1.message(), "", res1.message());
			
		}
		// Pass: only one present (analytics) 
		{
			final DataBucketBean test_bucket1 = BeanTemplateUtils.build(DataBucketBean.class)
					.with(DataBucketBean::_id, "test")
					.with(DataBucketBean::full_name, "/test")
					.with(DataBucketBean::master_enrichment_type, DataBucketBean.MasterEnrichmentType.batch)
					.with(DataBucketBean::analytic_thread, 
							BeanTemplateUtils.build(AnalyticThreadBean.class)
							.with(AnalyticThreadBean::jobs, Arrays.asList(BeanTemplateUtils.build(AnalyticThreadJobBean.class).with(AnalyticThreadJobBean::enabled, true).done().get())
									)
									.done().get()
							)
					.done().get();
						
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJobs(test_bucket1, Collections.emptyList());
			
			assertTrue("Validation should pass", res1.success());
			assertEquals("Correct error message: " + res1.message(), "", res1.message());
			
		}
	}

	@Test
	public void test_localValidation() {
		
		// Base bucket:
		
		final DataBucketBean test_bucket1 = BeanTemplateUtils.build(DataBucketBean.class)
				.with(DataBucketBean::_id, "test")
				.with(DataBucketBean::full_name, "/test")
				.with(DataBucketBean::analytic_thread, 
						BeanTemplateUtils.build(AnalyticThreadBean.class)
						.with(AnalyticThreadBean::jobs, Arrays.asList(
															BeanTemplateUtils.build(AnalyticThreadJobBean.class)
																.with(AnalyticThreadJobBean::name, "base")
																.with(AnalyticThreadJobBean::enabled, true)
															.done().get())
								)
								.done().get()
						)
				.done().get();
				
		// Test error case 0: streaming job

		{
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJob(test_bucket1, Collections.emptyList(), test_bucket1.analytic_thread().jobs().get(0));			
			assertFalse("Validation should fail", res1.success());
			assertEquals("Correct error message: " + res1.message(), ErrorUtils.get(HadoopErrorUtils.STREAMING_HADOOP_JOB, "/test", "base"), res1.message());
		}
		
		// Test error case 1: inputs
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input1 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "search_index_service")
				.done().get();

		final AnalyticThreadJobBean analytic_job1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "analytic_job_1")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input1))
				.with(AnalyticThreadJobBean::analytic_type, MasterEnrichmentType.batch)
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();

		{
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJob(test_bucket1, Collections.emptyList(), analytic_job1);			
			assertFalse("Validation should fail", res1.success());
			assertEquals("Correct error message: " + res1.message(), ErrorUtils.get(HadoopErrorUtils.CURR_INPUT_RESTRICTIONS, "search_index_service", "/test", "analytic_job_1", "search_index_service"), res1.message());
		}
		
		// Test error case 2: outputs

		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output1 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, true)
				.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, DataBucketBean.MasterEnrichmentType.streaming)
				.done().get();

		final AnalyticThreadJobBean analytic_job2 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "analytic_job_2")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::output, analytic_output1)
				.with(AnalyticThreadJobBean::analytic_type, MasterEnrichmentType.batch)
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();
		
		{
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJob(test_bucket1, Collections.emptyList(), analytic_job2);			
			assertFalse("Validation should fail", res1.success());
			assertEquals("Correct error message: " + res1.message(), ErrorUtils.get(HadoopErrorUtils.TEMP_TRANSIENT_OUTPUTS_MUST_BE_BATCH, "/test", "analytic_job_2", "streaming"), res1.message());
		}
				
		// Test error case 3: invalid config type

		{
			final AnalyticThreadJobBean analytic_job3 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
					.with(AnalyticThreadJobBean::name, "analytic_job_3")
					.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
					.with(AnalyticThreadJobBean::analytic_type, MasterEnrichmentType.batch)
					.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
					.with(AnalyticThreadJobBean::config, 
							new LinkedHashMap<String, Object>(
								ImmutableMap.<String, Object>builder()
									.put("name1",
											new LinkedHashMap<String, Object>(
												ImmutableMap.<String, Object>builder()
													.put("wrong_field", Arrays.asList("", "$previous", null))
												.build()
											))
								.build()
							))
					.done().get();
			
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJob(test_bucket1, Collections.emptyList(), analytic_job3);			
			assertFalse("Validation should fail", res1.success());
			assertEquals("Correct error message: " + res1.message(), ErrorUtils.get(HadoopErrorUtils.ERROR_IN_ANALYTIC_JOB_CONFIGURATION, "(unknown)", "/test", "analytic_job_3"), res1.message());
		}
				
		// Test error case 4: invalid names
		
		{
			final AnalyticThreadJobBean analytic_job4 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
					.with(AnalyticThreadJobBean::name, "analytic_job_4")
					.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
					.with(AnalyticThreadJobBean::analytic_type, MasterEnrichmentType.batch)
					.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
					.with(AnalyticThreadJobBean::config, 
							new LinkedHashMap<String, Object>(
								ImmutableMap.<String, Object>builder()
									.put("$invalid_name",
											new LinkedHashMap<String, Object>(
												ImmutableMap.<String, Object>builder()
												.build()
											))
								.build()
							))
					.done().get();
			
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJob(test_bucket1, Collections.emptyList(), analytic_job4);			
			assertFalse("Validation should fail", res1.success());
			assertEquals("Correct error message: " + res1.message(), ErrorUtils.get(HadoopErrorUtils.ERROR_IN_ANALYTIC_JOB_CONFIGURATION, "$invalid_name", "/test", "analytic_job_4"), res1.message());
		}
		
		// Test error case 5: invalid dependencies
		
		{
			final AnalyticThreadJobBean analytic_job5 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
					.with(AnalyticThreadJobBean::name, "analytic_job_5")
					.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
					.with(AnalyticThreadJobBean::analytic_type, MasterEnrichmentType.batch)
					.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
					.with(AnalyticThreadJobBean::config, 
							new LinkedHashMap<String, Object>(
								ImmutableMap.<String, Object>builder()
									.put("valid_name",
											new LinkedHashMap<String, Object>(
												ImmutableMap.<String, Object>builder()
													.put("dependencies", Arrays.asList("", "$previous", "invalid_dep"))
												.build()
											))
								.build()
							))
					.done().get();
			
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJob(test_bucket1, Collections.emptyList(), analytic_job5);			
			assertFalse("Validation should fail", res1.success());
			assertEquals("Correct error message: " + res1.message(), ErrorUtils.get(HadoopErrorUtils.CURR_DEPENDENCY_RESTRICTIONS, "invalid_dep", "valid_name", "/test", "analytic_job_5"), res1.message());
		}
		
		////////////////////////////////////////////////
		
		// Test pass case 1:
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2a =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "storage_service")
				.done().get();
		
		final AnalyticThreadJobBean.AnalyticThreadJobInputBean analytic_input2b =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobInputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobInputBean::data_service, "batch")
				.done().get();
		
		
		final AnalyticThreadJobBean.AnalyticThreadJobOutputBean analytic_output2 =  BeanTemplateUtils.build(AnalyticThreadJobBean.AnalyticThreadJobOutputBean.class)
				.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::is_transient, false)
				.with(AnalyticThreadJobBean.AnalyticThreadJobOutputBean::transient_type, DataBucketBean.MasterEnrichmentType.batch)
				.done().get();

		final AnalyticThreadJobBean analytic_job_pass1 = BeanTemplateUtils.build(AnalyticThreadJobBean.class)
				.with(AnalyticThreadJobBean::name, "analytic_job_pass1")
				.with(AnalyticThreadJobBean::analytic_technology_name_or_id, "test_analytic_tech_id")
				.with(AnalyticThreadJobBean::inputs, Arrays.asList(analytic_input2a, analytic_input2b))
				.with(AnalyticThreadJobBean::output, analytic_output2)
				.with(AnalyticThreadJobBean::config, 
						new LinkedHashMap<String, Object>(
							ImmutableMap.<String, Object>builder()
								.put("name1",
										new LinkedHashMap<String, Object>(
											ImmutableMap.<String, Object>builder()
												.put("dependencies", Arrays.asList("", "$previous", null))
											.build()
										))
							.build()
						))
				.with(AnalyticThreadJobBean::analytic_type, MasterEnrichmentType.batch)
				.with(AnalyticThreadJobBean::library_names_or_ids, Arrays.asList("id1", "name2"))
				.done().get();
		
		{
			final BasicMessageBean res1 = HadoopTechnologyUtils.validateJob(test_bucket1, Collections.emptyList(), analytic_job_pass1);			
			assertTrue("Validation should pass: " + res1.message(), res1.success());
			assertEquals("Correct error message: " + res1.message(), "", res1.message());
		}
		
		// Check that they get merged together by global validation
		
		{
			final BasicMessageBean res2 = HadoopTechnologyUtils.validateJobs(test_bucket1, Arrays.asList(analytic_job1, analytic_job2, analytic_job_pass1));
			assertFalse("Validation should fail", res2.success());
			final String[] messages = res2.message().split("\n");
			assertEquals(2, messages.length);
			assertEquals("Correct error message 1: " + messages[0], ErrorUtils.get(HadoopErrorUtils.CURR_INPUT_RESTRICTIONS, "search_index_service", "/test", "analytic_job_1"), messages[0]);
			assertEquals("Correct error message 2: " + messages[1], ErrorUtils.get(HadoopErrorUtils.TEMP_TRANSIENT_OUTPUTS_MUST_BE_BATCH, "/test", "analytic_job_2", "streaming"), messages[1]);
		}
	}
}
