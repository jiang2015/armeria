/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.linecorp.armeria.client.pool.PoolKey;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TimeoutException;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.OneTimeTask;

class HttpSessionChannelFactory implements Function<PoolKey, Future<Channel>> {

    static final Object RETRY_WITH_H1C = new Object();

    static final ChannelHealthChecker HEALTH_CHECKER =
            ch -> ch.eventLoop().newSucceededFuture(HttpSessionHandler.isActive(ch));

    private final Bootstrap baseBootstrap;
    private final EventLoop eventLoop;
    private final Map<SessionProtocol, Bootstrap> bootstrapMap;
    private final RemoteInvokerOptions options;

    HttpSessionChannelFactory(Bootstrap bootstrap, RemoteInvokerOptions options) {
        baseBootstrap = requireNonNull(bootstrap);
        eventLoop = (EventLoop) bootstrap.group();

        bootstrapMap = Collections.synchronizedMap(new EnumMap<>(SessionProtocol.class));
        this.options = options;
    }

    @Override
    public Future<Channel> apply(PoolKey key) {
        final InetSocketAddress remoteAddress = key.remoteAddress();
        final SessionProtocol protocol = key.sessionProtocol();

        if (SessionProtocolNegotiationCache.isUnsupported(remoteAddress, protocol)) {
            // Fail immediately if it is sure that the remote address does not support the requested protocol.
            return eventLoop.newFailedFuture(
                    new SessionProtocolNegotiationException(protocol, "previously failed negotiation"));
        }

        final Promise<Channel> sessionPromise = eventLoop.newPromise();
        connect(remoteAddress, protocol, sessionPromise);

        return sessionPromise;
    }

    private void connect(SocketAddress remoteAddress, SessionProtocol protocol,
                         Promise<Channel> sessionPromise) {

        final Bootstrap bootstrap = bootstrap(protocol);
        final ChannelFuture connectFuture = bootstrap.connect(remoteAddress);
        final Channel ch = connectFuture.channel();

        if (connectFuture.isDone()) {
            notifySessionPromise(ch, connectFuture, sessionPromise);
        } else {
            connectFuture.addListener(
                    (Future<Void> future) -> notifySessionPromise(ch, future, sessionPromise));
        }
    }

    private Bootstrap bootstrap(SessionProtocol sessionProtocol) {
        return bootstrapMap.computeIfAbsent(sessionProtocol, sp -> {
            Bootstrap bs = baseBootstrap.clone();
            bs.handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    ch.pipeline().addLast(new HttpConfigurator(sp, options));
                }
            });
            return bs;
        });
    }

    private void notifySessionPromise(Channel ch, Future<Void> connectFuture,
                                      Promise<Channel> sessionPromise) {
        assert connectFuture.isDone();
        if (connectFuture.isSuccess()) {
            watchSessionActive(ch, sessionPromise);
        } else {
            sessionPromise.setFailure(connectFuture.cause());
        }
    }

    private Future<Channel> watchSessionActive(Channel ch, Promise<Channel> sessionPromise) {
        EventLoop eventLoop = ch.eventLoop();

        if (eventLoop.inEventLoop()) {
            watchSessionActive0(ch, sessionPromise);
        } else {
            eventLoop.execute(new OneTimeTask() {
                @Override
                public void run() {
                    watchSessionActive0(ch, sessionPromise);
                }
            });
        }
        return sessionPromise;
    }

    private void watchSessionActive0(final Channel ch, Promise<Channel> sessionPromise) {
        assert ch.eventLoop().inEventLoop();

        if (HttpSessionHandler.isActive(ch)) {
            sessionPromise.setSuccess(ch);
            return;
        }

        final ScheduledFuture<?> timeoutFuture = ch.eventLoop().schedule(new OneTimeTask() {
            @Override
            public void run() {
                if (sessionPromise.tryFailure(new TimeoutException(
                        "connection established, but session creation timed out: " + ch))) {
                    ch.close();
                }
            }
        }, options.connectTimeoutMillis(), TimeUnit.MILLISECONDS);

        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof SessionProtocol) {
                    timeoutFuture.cancel(false);
                    if (!sessionPromise.trySuccess(ctx.channel())) {
                        ctx.close();
                    }
                    ctx.pipeline().remove(this);
                    return;
                }

                if (evt instanceof SessionProtocolNegotiationException) {
                    timeoutFuture.cancel(false);
                    sessionPromise.tryFailure((SessionProtocolNegotiationException) evt);
                    ctx.close();
                    return;
                }

                if (evt == RETRY_WITH_H1C) {
                    // Protocol upgrade has failed, but needs to retry.
                    timeoutFuture.cancel(false);
                    ctx.close();
                    connect(ctx.channel().remoteAddress(), SessionProtocol.H1C, sessionPromise);
                    return;
                }

                ctx.fireUserEventTriggered(evt);
            }
        });
    }
}
