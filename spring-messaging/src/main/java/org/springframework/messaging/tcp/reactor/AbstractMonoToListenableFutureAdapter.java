/*
 * Copyright 2002-2016 the original author or authors.
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

package org.springframework.messaging.tcp.reactor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

import org.springframework.util.Assert;
import org.springframework.util.concurrent.FailureCallback;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.ListenableFutureCallbackRegistry;
import org.springframework.util.concurrent.SuccessCallback;

/**
 * Adapts a reactor {@link Mono} to {@link ListenableFuture} optionally converting
 * the result Object type {@code <S>} to the expected target type {@code <T>}.
 *
 * @author Rossen Stoyanchev
 * @since 4.0
 * @param <S> the type of object expected from the {@link Mono}
 * @param <T> the type of object expected from the {@link ListenableFuture}
 */
abstract class AbstractMonoToListenableFutureAdapter<S, T>
		implements ListenableFuture<T> {

	private final MonoProcessor<S> promise;

	private final ListenableFutureCallbackRegistry<T> registry = new ListenableFutureCallbackRegistry<>();

	protected AbstractMonoToListenableFutureAdapter(Mono<S> promise) {
		Assert.notNull(promise, "Mono must not be null");
		this.promise = promise.doOnSuccess(result -> {
				T adapted;
				try {
					adapted = adapt(result);
				}
				catch (Throwable ex) {
					registry.failure(ex);
					return;
				}
				registry.success(adapted);
		})
		                      .doOnError(registry::failure)
		                      .subscribe();
	}


	@Override
	public T get() throws InterruptedException {
		S result = this.promise.block();
		return adapt(result);
	}

	@Override
	public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		Objects.requireNonNull(unit, "unit");
		S result = this.promise.block(Duration.ofMillis(TimeUnit.MILLISECONDS.convert(
				timeout,
				unit)));
		return adapt(result);
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		if (isCancelled()) {
			return false;
		}
		this.promise.cancel();
		return true;
	}

	@Override
	public boolean isCancelled() {
		return this.promise.isCancelled();
	}

	@Override
	public boolean isDone() {
		return this.promise.isTerminated();
	}

	@Override
	public void addCallback(ListenableFutureCallback<? super T> callback) {
		this.registry.addCallback(callback);
	}

	@Override
	public void addCallback(SuccessCallback<? super T> successCallback, FailureCallback failureCallback) {
		this.registry.addSuccessCallback(successCallback);
		this.registry.addFailureCallback(failureCallback);
	}


	protected abstract T adapt(S result);

}
