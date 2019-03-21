/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.http.server.reactive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.util.HttpString;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.xnio.channels.Channels;
import org.xnio.channels.StreamSinkChannel;
import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ZeroCopyHttpOutputMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Adapt {@link ServerHttpResponse} to the Undertow {@link HttpServerExchange}.
 *
 * @author Marek Hawrylczak
 * @author Rossen Stoyanchev
 * @author Arjen Poutsma
 * @since 5.0
 */
class UndertowServerHttpResponse extends AbstractListenerServerHttpResponse implements ZeroCopyHttpOutputMessage {

	private final HttpServerExchange exchange;

	private final UndertowServerHttpRequest request;

	@Nullable
	private StreamSinkChannel responseChannel;


	public UndertowServerHttpResponse(
			HttpServerExchange exchange, DataBufferFactory bufferFactory, UndertowServerHttpRequest request) {

		super(bufferFactory);
		Assert.notNull(exchange, "HttpServerExchange must not be null");
		this.exchange = exchange;
		this.request = request;
	}


	@SuppressWarnings("unchecked")
	@Override
	public <T> T getNativeResponse() {
		return (T) this.exchange;
	}


	@Override
	protected void applyStatusCode() {
		Integer statusCode = getStatusCodeValue();
		if (statusCode != null) {
			this.exchange.setStatusCode(statusCode);
		}
	}

	@Override
	protected void applyHeaders() {
		getHeaders().forEach((headerName, headerValues) ->
				this.exchange.getResponseHeaders().addAll(HttpString.tryFromString(headerName), headerValues));
	}

	@Override
	protected void applyCookies() {
		for (String name : getCookies().keySet()) {
			for (ResponseCookie httpCookie : getCookies().get(name)) {
				Cookie cookie = new CookieImpl(name, httpCookie.getValue());
				if (!httpCookie.getMaxAge().isNegative()) {
					cookie.setMaxAge((int) httpCookie.getMaxAge().getSeconds());
				}
				if (httpCookie.getDomain() != null) {
					cookie.setDomain(httpCookie.getDomain());
				}
				if (httpCookie.getPath() != null) {
					cookie.setPath(httpCookie.getPath());
				}
				cookie.setSecure(httpCookie.isSecure());
				cookie.setHttpOnly(httpCookie.isHttpOnly());
				this.exchange.getResponseCookies().putIfAbsent(name, cookie);
			}
		}
	}

	@Override
	public Mono<Void> writeWith(Path file, long position, long count) {
		return doCommit(() ->
				Mono.defer(() -> {
					try (FileChannel source = FileChannel.open(file, StandardOpenOption.READ)) {
						StreamSinkChannel destination = this.exchange.getResponseChannel();
						Channels.transferBlocking(destination, source, position, count);
						return Mono.empty();
					}
					catch (IOException ex) {
						return Mono.error(ex);
					}
				}));
	}


	@Override
	protected Processor<? super Publisher<? extends DataBuffer>, Void> createBodyFlushProcessor() {
		return new ResponseBodyFlushProcessor();
	}

	private ResponseBodyProcessor createBodyProcessor() {
		if (this.responseChannel == null) {
			this.responseChannel = this.exchange.getResponseChannel();
		}
		return new ResponseBodyProcessor(this.responseChannel);
	}


	private class ResponseBodyProcessor extends AbstractListenerWriteProcessor<DataBuffer> {

		private final StreamSinkChannel channel;

		@Nullable
		private volatile ByteBuffer byteBuffer;

		/** Keep track of write listener calls, for {@link #writePossible}. */
		private volatile boolean writePossible;


		public ResponseBodyProcessor(StreamSinkChannel channel) {
			super(request.getLogPrefix());
			Assert.notNull(channel, "StreamSinkChannel must not be null");
			this.channel = channel;
			this.channel.getWriteSetter().set(c -> {
				this.writePossible = true;
				onWritePossible();
			});
			this.channel.suspendWrites();
		}

		@Override
		protected boolean isWritePossible() {
			this.channel.resumeWrites();
			return this.writePossible;
		}

		@Override
		protected boolean write(DataBuffer dataBuffer) throws IOException {
			ByteBuffer buffer = this.byteBuffer;
			if (buffer == null) {
				return false;
			}

			// Track write listener calls from here on..
			this.writePossible = false;

			int total = buffer.remaining();
			int written = writeByteBuffer(buffer);

			if (logger.isTraceEnabled()) {
				logger.trace(getLogPrefix() + "Wrote " + written + " of " + total + " bytes");
			}
			else if (rsWriteLogger.isTraceEnabled()) {
				rsWriteLogger.trace(getLogPrefix() + "Wrote " + written + " of " + total + " bytes");
			}
			if (written != total) {
				return false;
			}

			// We wrote all, so can still write more..
			this.writePossible = true;

			DataBufferUtils.release(dataBuffer);
			this.byteBuffer = null;
			return true;
		}

		private int writeByteBuffer(ByteBuffer byteBuffer) throws IOException {
			int written;
			int totalWritten = 0;
			do {
				written = this.channel.write(byteBuffer);
				totalWritten += written;
			}
			while (byteBuffer.hasRemaining() && written > 0);
			return totalWritten;
		}

		@Override
		protected void dataReceived(DataBuffer dataBuffer) {
			super.dataReceived(dataBuffer);
			this.byteBuffer = dataBuffer.asByteBuffer();
		}

		@Override
		protected boolean isDataEmpty(DataBuffer dataBuffer) {
			return (dataBuffer.readableByteCount() == 0);
		}

		@Override
		protected void writingComplete() {
			this.channel.getWriteSetter().set(null);
			this.channel.resumeWrites();
		}

		@Override
		protected void writingFailed(Throwable ex) {
			cancel();
			onError(ex);
		}
	}


	private class ResponseBodyFlushProcessor extends AbstractListenerWriteFlushProcessor<DataBuffer> {

		public ResponseBodyFlushProcessor() {
			super(request.getLogPrefix());
		}

		@Override
		protected Processor<? super DataBuffer, Void> createWriteProcessor() {
			return UndertowServerHttpResponse.this.createBodyProcessor();
		}

		@Override
		protected void flush() throws IOException {
			StreamSinkChannel channel = UndertowServerHttpResponse.this.responseChannel;
			if (channel != null) {
				if (rsWriteFlushLogger.isTraceEnabled()) {
					rsWriteFlushLogger.trace(getLogPrefix() + "flush");
				}
				channel.flush();
			}
		}

		@Override
		protected void flushingFailed(Throwable t) {
			cancel();
			onError(t);
		}

		@Override
		protected boolean isWritePossible() {
			StreamSinkChannel channel = UndertowServerHttpResponse.this.responseChannel;
			if (channel != null) {
				// We can always call flush, just ensure writes are on..
				channel.resumeWrites();
				return true;
			}
			return false;
		}

		@Override
		protected boolean isFlushPending() {
			return false;
		}
	}

}
