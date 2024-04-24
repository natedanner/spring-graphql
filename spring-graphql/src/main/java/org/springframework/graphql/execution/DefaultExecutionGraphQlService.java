/*
 * Copyright 2002-2024 the original author or authors.
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

package org.springframework.graphql.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.execution.ExecutionIdProvider;
import graphql.execution.instrumentation.dataloader.EmptyDataLoaderRegistryInstance;
import io.micrometer.context.ContextSnapshotFactory;
import org.dataloader.DataLoaderRegistry;
import reactor.core.publisher.Mono;

import org.springframework.graphql.ExecutionGraphQlRequest;
import org.springframework.graphql.ExecutionGraphQlResponse;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;

/**
 * {@link ExecutionGraphQlService} that uses a {@link GraphQlSource} to obtain a
 * {@link GraphQL} instance and perform query execution.
 *
 * @author Rossen Stoyanchev
 * @since 1.0.0
 */
public class DefaultExecutionGraphQlService implements ExecutionGraphQlService {

	private static final BiFunction<ExecutionInput, ExecutionInput.Builder, ExecutionInput> RESET_EXECUTION_ID_CONFIGURER =
			(executionInput, builder) -> builder.executionId(null).build();


	private final GraphQlSource graphQlSource;

	private final List<DataLoaderRegistrar> dataLoaderRegistrars = new ArrayList<>();

	private boolean hasDataLoaderRegistrations;

	private final boolean isDefaultExecutionIdProvider;


	public DefaultExecutionGraphQlService(GraphQlSource graphQlSource) {
		this.graphQlSource = graphQlSource;
		this.isDefaultExecutionIdProvider =
				graphQlSource.graphQl().getIdProvider() == ExecutionIdProvider.DEFAULT_EXECUTION_ID_PROVIDER;
	}


	/**
	 * Add a registrar to get access to and configure the
	 * {@link DataLoaderRegistry} for each request.
	 * @param registrar the registrar to add
	 */
	public void addDataLoaderRegistrar(DataLoaderRegistrar registrar) {
		this.dataLoaderRegistrars.add(registrar);
		this.hasDataLoaderRegistrations = this.hasDataLoaderRegistrations || hasRegistrations(registrar);
	}

	private static boolean hasRegistrations(DataLoaderRegistrar registrar) {
		DataLoaderRegistry registry = DataLoaderRegistry.newRegistry().build();
		registrar.registerDataLoaders(registry, GraphQLContext.newContext().build());
		return !registry.getDataLoaders().isEmpty();
	}


	@Override
	public final Mono<ExecutionGraphQlResponse> execute(ExecutionGraphQlRequest request) {
		return Mono.deferContextual(contextView -> {
			if (!this.isDefaultExecutionIdProvider && request.getExecutionId() == null) {
				request.configureExecutionInput(RESET_EXECUTION_ID_CONFIGURER);
			}

			ExecutionInput executionInput = request.toExecutionInput();

			ContextSnapshotFactory factory = ContextSnapshotFactoryHelper.getInstance(contextView);
			GraphQLContext graphQLContext = executionInput.getGraphQLContext();
			ContextSnapshotFactoryHelper.saveInstance(factory, graphQLContext);
			factory.captureFrom(contextView).updateContext(graphQLContext);

			ExecutionInput updatedExecutionInput =
					this.hasDataLoaderRegistrations ? registerDataLoaders(executionInput) : executionInput;

			return Mono.fromFuture(this.graphQlSource.graphQl().executeAsync(updatedExecutionInput))
					.map(result -> new DefaultExecutionGraphQlResponse(updatedExecutionInput, result));
		});
	}

	private ExecutionInput registerDataLoaders(ExecutionInput executionInput) {
		GraphQLContext graphQLContext = executionInput.getGraphQLContext();
		DataLoaderRegistry existingRegistry = executionInput.getDataLoaderRegistry();
		if (existingRegistry == EmptyDataLoaderRegistryInstance.EMPTY_DATALOADER_REGISTRY) {
			DataLoaderRegistry newRegistry = DataLoaderRegistry.newRegistry().build();
			applyDataLoaderRegistrars(newRegistry, graphQLContext);
			executionInput = executionInput.transform(builder -> builder.dataLoaderRegistry(newRegistry));
		}
		else {
			applyDataLoaderRegistrars(existingRegistry, graphQLContext);
		}
		return executionInput;
	}

	private void applyDataLoaderRegistrars(DataLoaderRegistry registry, GraphQLContext graphQLContext) {
		this.dataLoaderRegistrars.forEach(registrar -> registrar.registerDataLoaders(registry, graphQLContext));
	}

}
