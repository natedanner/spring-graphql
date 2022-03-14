/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.graphql.client;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.TypeRef;
import graphql.GraphQLError;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.graphql.GraphQlRequest;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.graphql.support.MapGraphQlResponse;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;


/**
 * Default implementation of {@link ClientGraphQlResponse}.
 *
 * @author Rossen Stoyanchev
 * @since 1.0.0
 */
class DefaultClientGraphQlResponse extends MapGraphQlResponse implements ClientGraphQlResponse {

	private final GraphQlRequest request;

	private final DocumentContext jsonPathDoc;


	DefaultClientGraphQlResponse(GraphQlRequest request, GraphQlResponse response, Configuration jsonPathConfig) {
		super(response.toMap());
		this.request = request;
		this.jsonPathDoc = JsonPath.parse(response.toMap(), jsonPathConfig);
	}


	@Override
	public ResponseField field(String path) {
		path = "$.data" + (StringUtils.hasText(path) ? "." + path : "");
		return new DefaultField(this.request, this, path, this.jsonPathDoc, getErrors());
	}

	@Override
	public <D> D toEntity(Class<D> type) {
		return field("").toEntity(type);
	}

	@Override
	public <D> D toEntity(ParameterizedTypeReference<D> type) {
		return field("").toEntity(type);
	}


	/**
	 * Default implementation of {@link ResponseField}.
	 */
	private static class DefaultField implements ResponseField {

		private final GraphQlRequest request;

		private final ClientGraphQlResponse response;

		private final String path;

		private final DocumentContext jsonPathDoc;

		private final List<GraphQLError> errorsAt;

		private final List<GraphQLError> errorsBelow;

		private final List<GraphQLError> errorsAtOrBelow;

		private final boolean exists;

		@Nullable
		private final Object value;

		public DefaultField(
				GraphQlRequest request, ClientGraphQlResponse response,
				String path, DocumentContext jsonPathDoc, List<GraphQLError> errors) {

			this.request = request;
			this.response = response;
			this.path = path ;
			this.jsonPathDoc = jsonPathDoc;


			List<GraphQLError> errorsAt = null;
			List<GraphQLError> errorsBelow = null;
			List<GraphQLError> errorsAtOrBelow = null;

			for (GraphQLError error : errors) {
				String errorPath = toJsonPath(error);
				if (errorPath == null) {
					continue;
				}
				if (errorPath.startsWith(path)) {
					if (errorPath.length() == path.length()) {
						errorsAt = (errorsAt != null ? errorsAt : new ArrayList<>());
						errorsAt.add(error);
					}
					else {
						errorsBelow = (errorsBelow != null ? errorsBelow : new ArrayList<>());
						errorsBelow.add(error);
					}
					errorsAtOrBelow = (errorsAtOrBelow != null ? errorsAtOrBelow : new ArrayList<>());
					errorsAtOrBelow.add(error);
				}
			}

			this.errorsAt = (errorsAt != null ? errorsAt : Collections.emptyList());
			this.errorsBelow = (errorsBelow != null ? errorsBelow : Collections.emptyList());
			this.errorsAtOrBelow = (errorsAtOrBelow != null ? errorsAtOrBelow : Collections.emptyList());


			boolean exists = true;
			Object value = null;
			try {
				value = jsonPathDoc.read(this.path);
			}
			catch (PathNotFoundException ex) {
				exists = false;
			}

			this.exists = exists;
			this.value = value;
		}

		@Nullable
		private String toJsonPath(GraphQLError error) {
			if (CollectionUtils.isEmpty(error.getPath())) {
				return null;
			}
			List<Object> segments = error.getPath();
			StringBuilder sb = new StringBuilder((String) segments.get(0));
			for (int i = 1; i < segments.size(); i++) {
				Object segment = segments.get(i);
				if (segment instanceof Integer) {
					sb.append("[").append(segment).append("]");
				}
				else {
					sb.append(".").append(segment);
				}
			}
			return sb.toString();
		}

		@Override
		public String getPath() {
			return this.path;
		}

		@Override
		public boolean isValid() {
			return (this.exists && (this.value != null || (this.errorsAt.isEmpty() && this.errorsBelow.isEmpty())));
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T getValue() {
			return (T) this.value;
		}

		@Override
		public List<GraphQLError> getErrorsAt() {
			return this.errorsAt;
		}

		@Override
		public List<GraphQLError> getErrorsBelow() {
			return this.errorsBelow;
		}

		@Override
		public List<GraphQLError> getErrorsAtOrBelow() {
			return this.errorsAtOrBelow;
		}

		@Override
		public <D> D toEntity(Class<D> entityType) {
			assertIsValid();
			return this.jsonPathDoc.read(this.path, new TypeRefAdapter<>(entityType));
		}

		@Override
		public <D> D toEntity(ParameterizedTypeReference<D> entityType) {
			assertIsValid();
			return this.jsonPathDoc.read(this.path, new TypeRefAdapter<>(entityType));
		}

		@Override
		public <D> List<D> toEntityList(Class<D> elementType) {
			assertIsValid();
			return this.jsonPathDoc.read(this.path, new TypeRefAdapter<>(List.class, elementType));
		}

		@Override
		public <D> List<D> toEntityList(ParameterizedTypeReference<D> elementType) {
			assertIsValid();
			return this.jsonPathDoc.read(this.path, new TypeRefAdapter<>(List.class, elementType));
		}

		private void assertIsValid() {
			if (!isValid()) {
				throw new FieldAccessException(this.request, this.response, this);
			}
		}

	}


	/**
	 * Adapt JSONPath {@link TypeRef} to {@link ParameterizedTypeReference}.
	 */
	private static final class TypeRefAdapter<T> extends TypeRef<T> {

		private final Type type;

		TypeRefAdapter(Class<T> clazz) {
			this.type = clazz;
		}

		TypeRefAdapter(ParameterizedTypeReference<T> typeReference) {
			this.type = typeReference.getType();
		}

		TypeRefAdapter(Class<?> clazz, Class<?> generic) {
			this.type = ResolvableType.forClassWithGenerics(clazz, generic).getType();
		}

		TypeRefAdapter(Class<?> clazz, ParameterizedTypeReference<?> generic) {
			this.type = ResolvableType.forClassWithGenerics(clazz, ResolvableType.forType(generic)).getType();
		}

		@Override
		public Type getType() {
			return this.type;
		}

	}


}
