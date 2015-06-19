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
package com.ikanow.aleph2.search_service.elasticsearch.utils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;

import scala.Tuple2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.Tuples;

import fj.data.Either;

/** A collection of utilities for converting buckets into Elasticsearch attributes
 * @author Alex
 */
public class ElasticsearchIndexUtils {


	/////////////////////////////////////////////////////////////////////
	
	// INDEX NAMES
	
	/** Returns the base index name (before any date strings, splits etc) have been appended
	 * @param bucket
	 * @return
	 */
	public static String getBaseIndexName(final DataBucketBean bucket) {
		return bucket._id().toLowerCase().replace("-", "_");
	}
	
	/** Converts any index back to its spawning bucket
	 * @param index_name - the elasticsearch index name
	 * @return
	 */
	public static String getBucketIdFromIndexName(String index_name) {
		return index_name.replaceFirst("([a-z0-9]+_[a-z0-9]+_[a-z0-9]+_[a-z0-9]+_[a-z0-9]+).*", "$1").replace("_", "-");
	}
	
	/////////////////////////////////////////////////////////////////////
	
	// MAPPINGS - DEFAULTS
	
	/** Builds a lookup table of settings 
	 * @param mapping - the mapping to use
	 * @param type - if the index has a specific type, lookup that and _default_ ; otherwise just _default
	 * @return
	 */
	public static LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> parseDefaultMapping(final JsonNode mapping, Optional<String> type) {
		final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> ret = 
				Optional.ofNullable(mapping.get("mappings"))
					.map(m -> {
						if (!m.isObject()) throw new RuntimeException("mappings must be object");
						return m;
					})
					.map(m -> m.get(type.orElse("_default_")))
					.filter(i -> !i.isNull())
					.map(i -> {
						if (!i.isObject()) throw new RuntimeException(type + " must be object");
						return i;
					})
					.map(i -> {
						final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> props = getProperties(i);						
						props.putAll(getTemplates(i));
						return props;
					})
					.orElse(new LinkedHashMap<>());
		
		if (type.isPresent()) { // If this was on behalf of a specific type then also roll up the defaults
			ret.putAll(parseDefaultMapping(mapping, Optional.empty()));
		}		
		return ret;
	}
	
	protected static LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> getProperties(JsonNode index) {
		return Optional.ofNullable(index.get("properties"))
					.filter(p -> !p.isNull())
					.map(p -> {
						if (!p.isObject()) throw new RuntimeException("properties must be object");
						return p;
					})
					.map(p -> {
						return StreamSupport.stream(Spliterators.spliteratorUnknownSize(p.fields(), Spliterator.ORDERED), false)
							.collect(Collectors.
									<Map.Entry<String, JsonNode>, Either<String, Tuple2<String, String>>, JsonNode, LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>>
									toMap(
										kv -> Either.<String, Tuple2<String, String>>left(kv.getKey()),
										kv -> kv.getValue(),
										(v1, v2) -> v1, // (should never happen)
										() -> new LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>()
									));
					})
					.orElse(new LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>());
	}
	
	protected static LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> getTemplates(JsonNode index) {
		return Optional.ofNullable(index.get("dynamic_templates"))
					.filter(p -> !p.isNull())					
					.map(p -> {
						if (!p.isArray()) throw new RuntimeException("dynamic_templates must be object");
						return p;
					})
					.map(p -> {
						return StreamSupport.stream(Spliterators.spliteratorUnknownSize(p.elements(), Spliterator.ORDERED), false)
							.map(pf -> {
								if (!pf.isObject()) throw new RuntimeException("dynamic_templates[*] must be object");
								return pf;
							})
							.flatMap(pp -> StreamSupport.stream(Spliterators.spliteratorUnknownSize(pp.fields(), Spliterator.ORDERED), false))
							.collect(Collectors.
								<Map.Entry<String, JsonNode>, Either<String, Tuple2<String, String>>, JsonNode, LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>>
								toMap(
									kv -> Either.right(buildMatchPair(kv.getValue())),
									kv -> kv.getValue().get("mapping"),
									(v1, v2) -> v1, // (should never happen)
									() -> new LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>()
								));
					})
					.orElse(new LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode>());
	}
	
	protected static Tuple2<String, String> buildMatchPair(final JsonNode template) {
		return Tuples._2T(
				Optional.ofNullable(template.get("match")).map(j -> j.asText().replace("*", "STAR")).orElse("STAR")
				,
				Optional.ofNullable(template.get("match_mapping_type")).map(j -> j.asText()).orElse("STAR")
				);
	}
	
	/////////////////////////////////////////////////////////////////////
	
	// MAPPINGS - CREATION
	
	// Quick guide to mappings
	// under mappings you can specify either
	// - specific types
	// - _default_, which applies to anything that doesn't match that type
	//   - then under each type (or _default_)..
	//      - you can specify dynamic_templates/properties/_all/dynamic_date_formats/date_detection/numeric_detection
	//         - under properties you can then specify types and then fields
	//         - under dynamic_templates you can specify fields
	//           - under fields you can specify type/fielddata(*)/similarity/analyzer/etc
	//
	// (*) https://www.elastic.co/guide/en/elasticsearch/reference/current/fielddata-formats.html
	//
	// OK so we can specify parts of mappings in the following ways:
	// - COLUMNAR: 
	//   - based on field name .. maps to path_match
	//   - based on type .. maps to match_mapping_type
	//   (and then for these columnar types we want to specify 
	//      "type": "{dynamic_type}", "index": "no", "fielddata": { "format": "doc_values" } // (or disabled)
	//      but potentially would like to be able to add more info as well/instead
	//      so maybe need a default and then per-key override
	//
	// OK ... then in addition, we want to be able to set other elements of the search from the search override schema
	// The simplest way of doing this is probably just to force matching on fields/patterns and then to merge them
	
	
	///////////////////////////////////////////////////////////////
	
	// TEMPORAL PROCESSING
	
	/** Creates a mapping for the bucket - temporal elements
	 * @param bucket
	 * @return
	 * @throws IOException 
	 */
	public static XContentBuilder getTemporalMapping(final DataBucketBean bucket, Optional<XContentBuilder> to_embed) {
		try {
			final XContentBuilder start = to_embed.orElse(XContentFactory.jsonBuilder().startObject());
			if (!Optional.ofNullable(bucket.data_schema()).map(DataSchemaBean::temporal_schema).isPresent()) return start;
			
			// Nothing to be done here
			
			return start;
		}
		catch (IOException e) {
			//Handle fake "IOException"
			return null;
		}
	}

	///////////////////////////////////////////////////////////////
	
	// COLUMNAR PROCESSING
	
	//TODO: either wants to go under type or _default_, depending on whether a single type is defined?
	
	/** Creates a mapping for the bucket - columnar elements
	 * @param bucket
	 * @return
	 * @throws IOException 
	 */
	public static XContentBuilder getColumnarMapping(final DataBucketBean bucket, Optional<XContentBuilder> to_embed,
														final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> field_lookups,
														final JsonNode default_not_analyzed, final JsonNode default_analyzed,
														final ObjectMapper mapper)
	{
		try {
			final XContentBuilder start = to_embed.orElse(XContentFactory.jsonBuilder().startObject());
			if (!Optional.ofNullable(bucket.data_schema()).map(DataSchemaBean::columnar_schema).isPresent()) return start;

			//Stream<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>> under_properties = 
			final XContentBuilder properties = Stream.of(
			
				addIncludes(bucket.data_schema().columnar_schema().field_include_list().stream(),
							fn -> Either.left(fn), 
							field_lookups, default_not_analyzed, default_analyzed, mapper
						),
		
				addExcludes(bucket.data_schema().columnar_schema().field_exclude_list().stream(),
						fn -> Either.left(fn), 
						field_lookups, default_not_analyzed, default_analyzed, mapper
					)

			).flatMap(x -> x)
			.reduce(
					start.startObject("properties"), 
					Lambdas.wrap_u((acc, t2) -> acc.rawField(t2._1().left().value(), t2._2().toString().getBytes())), // (left by construction) 
					(acc1, acc2) -> acc1) // (not actually possible)
			.endObject()
			;
						
			final XContentBuilder templates = Stream.of(
								
				addIncludes(bucket.data_schema().columnar_schema().field_include_pattern_list().stream(),
						fn -> Either.right(Tuples._2T(fn.replace("*", "STAR"), "STAR")), 
						field_lookups, default_not_analyzed, default_analyzed, mapper
					),
	
				addIncludes(bucket.data_schema().columnar_schema().field_type_include_list().stream(),
						fn -> Either.right(Tuples._2T("STAR", fn)), 
						field_lookups, default_not_analyzed, default_analyzed, mapper
					),
			
				addExcludes(bucket.data_schema().columnar_schema().field_exclude_pattern_list().stream(),
						fn -> Either.right(Tuples._2T(fn.replace("*", "STAR"), "STAR")), 
						field_lookups, default_not_analyzed, default_analyzed, mapper
					),
	
				addExcludes(bucket.data_schema().columnar_schema().field_type_exclude_list().stream(),
						fn -> Either.right(Tuples._2T("STAR", fn)), 
						field_lookups, default_not_analyzed, default_analyzed, mapper
					)
			).flatMap(x -> x)
			.reduce(
					properties.startObject("dynamic_templates").startArray(),
					Lambdas.wrap_u((acc, t2) -> acc.startObject()
													.rawField(getFieldName(t2._1().right().value()), t2._2().toString().getBytes()) // (right by construction)
												.endObject()),  						
					(acc1, acc2) -> acc1) // (not actually possible)
			.endArray().endObject()
			;
			
			return templates;
		}
		catch (IOException e) {
			//Handle fake "IOException"
			return null;
		}
	}
	
	/** Creates a single string from a match/match_mapping_type pair
	 * @param field_info
	 * @return
	 */
	protected static String getFieldName(final Tuple2<String, String> field_info) {
		return field_info._1().replace("*", "STAR").replace("_", "BAR") + "_" + field_info._2();
	};
	
	// (Few constants to tidy stuff up)
	protected final static String BACKUP_FIELD_MAPPING = "{\"type\":\"string\",\"analyzed\":\"no\"}";
	protected final static String DEFAULT_FIELDDATA_NAME = "_default";
	protected final static String DISABLED_FIELDDATA = "{\"format\":\"disabled\"}";
	
	/** Creates a list of JsonNodes containing the mapping for fields that will enable field data
	 * @param instream
	 * @param f
	 * @param field_lookups
	 * @param default_not_analyzed
	 * @param default_analyzed
	 * @param mapper
	 * @return
	 */
	protected static Stream<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>> addIncludes(final Stream<String> instream,
								final Function<String, Either<String, Tuple2<String, String>>> f,
								final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> field_lookups,
								final JsonNode default_not_analyzed, final JsonNode default_analyzed,
								final ObjectMapper mapper)
	{
		return instream.<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>>
			map(Lambdas.wrap_u(fn -> {
				final Either<String, Tuple2<String, String>> either = f.apply(fn);
				final ObjectNode mutable_field_mapping = (ObjectNode) Optional.ofNullable(field_lookups.get(either))
														.map(j -> j.deepCopy())
														.orElse(mapper.readTree(BACKUP_FIELD_MAPPING));
	
				final boolean is_analyzed = Optional.ofNullable(mutable_field_mapping.get("index")).filter(j -> !j.isNull() && j.isTextual())
												.map(jt -> jt.asText().equalsIgnoreCase("analyzed") || jt.asText().equalsIgnoreCase("yes"))
												.orElse(false); 
				
				final JsonNode fielddata_settings = is_analyzed ? default_analyzed : default_not_analyzed;
				
				Optional.ofNullable(
					either.<JsonNode>either(
						left -> Optional.ofNullable(fielddata_settings.get(left)).filter(j -> !j.isNull()).orElse(fielddata_settings.get(DEFAULT_FIELDDATA_NAME))
						, 
						right -> fielddata_settings.get(DEFAULT_FIELDDATA_NAME)
						))
						.ifPresent(j -> mutable_field_mapping.set("fielddata", j));
				
				return Tuples._2T(either, mutable_field_mapping); 
			}));

	}
	
	/** Creates a list of JsonNodes containing the mapping for fields that will _disable_ field data
	 * @param instream
	 * @param f
	 * @param field_lookups
	 * @param default_not_analyzed
	 * @param default_analyzed
	 * @param mapper
	 * @return
	 */
	protected static Stream<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>> addExcludes(final Stream<String> instream,
			final Function<String, Either<String, Tuple2<String, String>>> f,
			final LinkedHashMap<Either<String, Tuple2<String, String>>, JsonNode> field_lookups,
			final JsonNode default_not_analyzed, final JsonNode default_analyzed,
			final ObjectMapper mapper)
	{
		return instream.<Tuple2<Either<String, Tuple2<String, String>>, JsonNode>>
			map(Lambdas.wrap_u(fn -> {
				final Either<String, Tuple2<String, String>> either = f.apply(fn);
				final ObjectNode mutable_field_mapping = (ObjectNode) Optional.ofNullable(field_lookups.get(either))
														.map(j -> j.deepCopy())
														.orElse(mapper.readTree(BACKUP_FIELD_MAPPING));
				
				mutable_field_mapping.set("fielddata", mapper.readTree(DISABLED_FIELDDATA));
				
				return Tuples._2T(either, mutable_field_mapping); 
			}));
	}

	///////////////////////////////////////////////////////////////
	
	// SEARCH PROCESSING
		
	/** Creates a mapping for the bucket - search service elements
	 * @param bucket
	 * @return
	 * @throws IOException 
	 */
	public static XContentBuilder getSearchServiceMapping(final DataBucketBean bucket, Optional<XContentBuilder> to_embed) {
		try {
			final XContentBuilder start = to_embed.orElse(XContentFactory.jsonBuilder().startObject());
			if (null == bucket.data_schema()) return start;

			// TODO: copy settings across from either the bucket or the default options

			//TODO: need an option for _all that is "lifted" out of "mappings":"type/_default_", to make it easier to set it 
			
			//TODO: anything else?
			
			return start;
		}
		catch (IOException e) {
			//Handle fake "IOException"
			return null;
		}
	}

	///////////////////////////////////////////////////////////////
	
	// TEMPLATE CREATION
	
	/** Create a template to be applied to all indexes generated from this bucket
	 * @param bucket
	 * @return
	 */
	public XContentBuilder getTemplateMapping(final DataBucketBean bucket) {
		//TODO just call getMapping and register with the bucket's base index name
		return null;
	}
	
	//TODO: need the schema checks here
//	public XContentBuilder getMapping(final DataBucketBean bucket, Optional<XContentBuilder> to_embed) {
//		try {
//			final XContentBuilder start = to_embed.orElse(XContentFactory.jsonBuilder().startObject());
//			if (null == bucket.data_schema()) return start;
//			if ((null != bucket.data_schema().search_index_schema()) && 
//					Optional.ofNullable(bucket.data_schema().search_index_schema().enabled()).orElse(true))
//			{
//				//TODO search schema
//				
//			}
//			if ((null != bucket.data_schema().temporal_schema()) && 
//					Optional.ofNullable(bucket.data_schema().temporal_schema().enabled()).orElse(true))
//			{
//				//TODO temporal schema
//				
//			}
//			if ((null != bucket.data_schema().columnar_schema()) && 
//					Optional.ofNullable(bucket.data_schema().columnar_schema().enabled()).orElse(true))
//			{
//				//TODO columnar_schema schema				
//			}
//			return start;//TODO
//		}
//		catch (IOException e) {
//			//Handle fake "IOException"
//			return null;
//		}
//	}
	
	
}