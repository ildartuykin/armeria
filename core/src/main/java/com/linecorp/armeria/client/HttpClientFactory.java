/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.internal.TransportType;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;

/**
 * A {@link ClientFactory} that creates an HTTP client.
 */
final class HttpClientFactory extends AbstractClientFactory {

    private static final Set<Scheme> SUPPORTED_SCHEMES =
            Arrays.stream(SessionProtocol.values())
                  .map(p -> Scheme.of(SerializationFormat.NONE, p))
                  .collect(toImmutableSet());

    private final EventLoopGroup workerGroup;
    private final boolean shutdownWorkerGroupOnClose;
    private final Bootstrap baseBootstrap;
    private final Consumer<? super SslContextBuilder> sslContextCustomizer;
    private final AddressResolverGroup<InetSocketAddress> addressResolverGroup;
    private final int http2InitialConnectionWindowSize;
    private final int http2InitialStreamWindowSize;
    private final int http2MaxFrameSize;
    private final long http2MaxHeaderListSize;
    private final int http1MaxInitialLineLength;
    private final int http1MaxHeaderSize;
    private final int http1MaxChunkSize;
    private final long idleTimeoutMillis;
    private final boolean useHttp2Preface;
    private final boolean useHttp1Pipelining;
    private final ConnectionPoolListener connectionPoolListener;
    private MeterRegistry meterRegistry;

    private final ConcurrentMap<EventLoop, HttpChannelPool> pools = new MapMaker().weakKeys().makeMap();
    private final HttpClientDelegate clientDelegate;

    private final EventLoopScheduler eventLoopScheduler;
    private final Supplier<EventLoop> eventLoopSupplier =
            () -> RequestContext.mapCurrent(RequestContext::eventLoop, () -> eventLoopGroup().next());

    private volatile boolean closed;

    private final ClientFactoryOptions options;

    HttpClientFactory(ClientFactoryOptions options) {
        workerGroup = options.workerGroup();

        @SuppressWarnings("unchecked")
        final AddressResolverGroup<InetSocketAddress> group =
                (AddressResolverGroup<InetSocketAddress>) options.addressResolverGroupFactory()
                                                                 .apply(workerGroup);
        addressResolverGroup = group;

        final Bootstrap bootstrap = new Bootstrap();
        bootstrap.channel(TransportType.socketChannelType(workerGroup));
        bootstrap.resolver(addressResolverGroup);

        options.channelOptions().forEach((option, value) -> {
            @SuppressWarnings("unchecked")
            final ChannelOption<Object> castOption = (ChannelOption<Object>) option;
            bootstrap.option(castOption, value);
        });

        shutdownWorkerGroupOnClose = options.shutdownWorkerGroupOnClose();
        eventLoopScheduler = options.eventLoopSchedulerFactory().apply(workerGroup);
        baseBootstrap = bootstrap;
        sslContextCustomizer = options.sslContextCustomizer();
        http2InitialConnectionWindowSize = options.http2InitialConnectionWindowSize();
        http2InitialStreamWindowSize = options.http2InitialStreamWindowSize();
        http2MaxFrameSize = options.http2MaxFrameSize();
        http2MaxHeaderListSize = options.http2MaxHeaderListSize();
        http1MaxInitialLineLength = options.http1MaxInitialLineLength();
        http1MaxHeaderSize = options.http1MaxHeaderSize();
        http1MaxChunkSize = options.http1MaxChunkSize();
        idleTimeoutMillis = options.idleTimeoutMillis();
        useHttp2Preface = options.useHttp2Preface();
        useHttp1Pipelining = options.useHttp1Pipelining();
        connectionPoolListener = options.connectionPoolListener();
        meterRegistry = options.meterRegistry();

        this.options = options;

        clientDelegate = new HttpClientDelegate(this, addressResolverGroup);
    }

    /**
     * Returns a new {@link Bootstrap} whose {@link ChannelFactory}, {@link AddressResolverGroup} and
     * socket options are pre-configured.
     */
    Bootstrap newBootstrap() {
        return baseBootstrap.clone();
    }

    Consumer<? super SslContextBuilder> sslContextCustomizer() {
        return sslContextCustomizer;
    }

    int http2InitialConnectionWindowSize() {
        return http2InitialConnectionWindowSize;
    }

    int http2InitialStreamWindowSize() {
        return http2InitialStreamWindowSize;
    }

    int http2MaxFrameSize() {
        return http2MaxFrameSize;
    }

    long http2MaxHeaderListSize() {
        return http2MaxHeaderListSize;
    }

    int http1MaxInitialLineLength() {
        return http1MaxInitialLineLength;
    }

    int http1MaxHeaderSize() {
        return http1MaxHeaderSize;
    }

    int http1MaxChunkSize() {
        return http1MaxChunkSize;
    }

    long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    boolean useHttp2Preface() {
        return useHttp2Preface;
    }

    boolean useHttp1Pipelining() {
        return useHttp1Pipelining;
    }

    ConnectionPoolListener connectionPoolListener() {
        return connectionPoolListener;
    }

    @VisibleForTesting
    AddressResolverGroup<InetSocketAddress> addressResolverGroup() {
        return addressResolverGroup;
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return SUPPORTED_SCHEMES;
    }

    @Override
    public EventLoopGroup eventLoopGroup() {
        return workerGroup;
    }

    @Override
    public Supplier<EventLoop> eventLoopSupplier() {
        return eventLoopSupplier;
    }

    @Override
    public ReleasableHolder<EventLoop> acquireEventLoop(Endpoint endpoint, SessionProtocol sessionProtocol) {
        return eventLoopScheduler.acquire(endpoint, sessionProtocol);
    }

    @Override
    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Override
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public ClientFactoryOptions options() {
        return options;
    }

    @Override
    public <T> T newClient(URI uri, Class<T> clientType, ClientOptions options) {
        final Scheme scheme = validateScheme(uri);
        final Endpoint endpoint = newEndpoint(uri);

        return newClient(uri, scheme, endpoint, clientType, options);
    }

    @Override
    public <T> T newClient(Scheme scheme, Endpoint endpoint, @Nullable String path, Class<T> clientType,
                           ClientOptions options) {
        final URI uri = endpoint.toUri(scheme, path);
        return newClient(uri, scheme, endpoint, clientType, options);
    }

    private <T> T newClient(URI uri, Scheme scheme, Endpoint endpoint, Class<T> clientType,
                            ClientOptions options) {
        validateClientType(clientType);

        final HttpClient delegate = options.decoration().decorate(clientDelegate);

        if (clientType == HttpClient.class) {
            @SuppressWarnings("unchecked")
            final T castClient = (T) delegate;
            return castClient;
        }

        if (clientType == WebClient.class) {
            final WebClient client = newWebClient(uri, scheme, endpoint, options, delegate);

            @SuppressWarnings("unchecked")
            final T castClient = (T) client;
            return castClient;
        } else {
            throw new IllegalArgumentException("unsupported client type: " + clientType.getName());
        }
    }

    private DefaultWebClient newWebClient(URI uri, Scheme scheme, Endpoint endpoint, ClientOptions options,
                                          HttpClient delegate) {
        return new DefaultWebClient(
                new DefaultClientBuilderParams(this, uri, WebClient.class, options),
                delegate, meterRegistry, scheme.sessionProtocol(), endpoint);
    }

    private static void validateClientType(Class<?> clientType) {
        if (clientType != WebClient.class && clientType != HttpClient.class) {
            throw new IllegalArgumentException(
                    "clientType: " + clientType +
                    " (expected: " + WebClient.class.getSimpleName() + " or " +
                    HttpClient.class.getSimpleName() + ')');
        }
    }

    boolean isClosing() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;

        for (final Iterator<HttpChannelPool> i = pools.values().iterator(); i.hasNext();) {
            i.next().close();
            i.remove();
        }

        addressResolverGroup.close();
        if (shutdownWorkerGroupOnClose) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    HttpChannelPool pool(EventLoop eventLoop) {
        final HttpChannelPool pool = pools.get(eventLoop);
        if (pool != null) {
            return pool;
        }

        return pools.computeIfAbsent(eventLoop,
                                     e -> new HttpChannelPool(this, eventLoop, connectionPoolListener()));
    }
}
