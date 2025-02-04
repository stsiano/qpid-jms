/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.jms.transports.netty;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.apache.qpid.jms.transports.TransportListener;
import org.apache.qpid.jms.transports.TransportOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

/**
 * Netty based WebSockets Transport that wraps and extends the TCP Transport.
 */
public class NettyWsTransport extends NettyTcpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(NettyWsTransport.class);
    private static final String AMQP_SUB_PROTOCOL = "amqp";

    /**
     * Create a new transport instance
     *
     * @param remoteLocation
     *        the URI that defines the remote resource to connect to.
     * @param options
     *        the transport options used to configure the socket connection.
     * @param secure
     * 		  should the transport enable an SSL layer.
     */
    public NettyWsTransport(URI remoteLocation, TransportOptions options, boolean secure) {
        super(null, remoteLocation, options, secure);
    }

    /**
     * Create a new transport instance
     *
     * @param listener
     *        the TransportListener that will receive events from this Transport.
     * @param remoteLocation
     *        the URI that defines the remote resource to connect to.
     * @param options
     *        the transport options used to configure the socket connection.
     * @param secure
     * 		  should the transport enable an SSL layer.
     */
    public NettyWsTransport(TransportListener listener, URI remoteLocation, TransportOptions options, boolean secure) {
        super(listener, remoteLocation, options, secure);
    }

    @Override
    public void write(ByteBuf output) throws IOException {
        checkConnected();
        int length = output.readableBytes();
        if (length == 0) {
            return;
        }

        LOG.trace("Attempted write of: {} bytes", length);

        channel.write(new BinaryWebSocketFrame(output), channel.voidPromise());
    }

    @Override
    public void writeAndFlush(ByteBuf output) throws IOException {
        checkConnected();
        int length = output.readableBytes();
        if (length == 0) {
            return;
        }

        LOG.trace("Attempted write and flush of: {} bytes", length);

        channel.writeAndFlush(new BinaryWebSocketFrame(output), channel.voidPromise());
    }

    @Override
    protected ChannelInboundHandlerAdapter createChannelHandler() {
        return new NettyWebSocketTransportHandler();
    }

    @Override
    protected void addAdditionalHandlers(ChannelPipeline pipeline) {
        /*
         * If we use a HttpClientCodec here instead of the HttpRequestEncoder and the HttpResponseDecoder
         * and there is a HttpProxyHandler in the pipeline, that ProxyHandler will add another HttpClientCodec
         * for communication with the proxy. When the WebSocketClientHandshaker tries to exchange the codecs in
         * the pipeline, it will mix up the two HttpRequestEncoders in the pipeline and exchange the wrong one.
         * HttpReqestEncoder and HttpResponseDecoder has precedence over the HttpClientCodec, so the
         * WebSocketClientHandshaker will remove these handlers inserted here and will leave the HttpClientCodec
         * added by the HttpProxyHandler alone.
         */
        pipeline.addLast(new HttpResponseDecoder());
        pipeline.addLast(new HttpRequestEncoder());
        pipeline.addLast(new HttpObjectAggregator(8192));
    }

    @Override
    protected void handleConnected(Channel channel) throws Exception {
        LOG.trace("Channel has become active, awaiting WebSocket handshake! Channel is {}", channel);
    }

    //----- Handle connection events -----------------------------------------//

    private class NettyWebSocketTransportHandler extends NettyDefaultHandler<Object> {

        private final WebSocketClientHandshaker handshaker;

        public NettyWebSocketTransportHandler() {
            DefaultHttpHeaders headers = new DefaultHttpHeaders();

            getTransportOptions().getHttpHeaders().forEach((key, value) -> {
                headers.set(key, value);
            });

            handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                getRemoteLocation(), WebSocketVersion.V13, AMQP_SUB_PROTOCOL,
                true, headers, getMaxFrameSize());
        }

        @Override
        public void channelActive(ChannelHandlerContext context) throws Exception {
            handshaker.handshake(context.channel());

            super.channelActive(context);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object message) throws Exception {
            LOG.trace("New data read: incoming: {}", message);

            Channel ch = ctx.channel();
            if (!handshaker.isHandshakeComplete()) {
                handshaker.finishHandshake(ch, (FullHttpResponse) message);
                LOG.trace("WebSocket Client connected! {}", ctx.channel());
                // Now trigger super processing as we are really connected.
                NettyWsTransport.super.handleConnected(ch);
                return;
            }

            // We shouldn't get this since we handle the handshake previously.
            if (message instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) message;
                throw new IllegalStateException(
                    "Unexpected FullHttpResponse (getStatus=" + response.status() +
                    ", content=" + response.content().toString(StandardCharsets.UTF_8) + ')');
            }

            WebSocketFrame frame = (WebSocketFrame) message;
            if (frame instanceof TextWebSocketFrame) {
                TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
                LOG.warn("WebSocket Client received message: " + textFrame.text());
                ctx.fireExceptionCaught(new IOException("Received invalid frame over WebSocket."));
            } else if (frame instanceof BinaryWebSocketFrame) {
                BinaryWebSocketFrame binaryFrame = (BinaryWebSocketFrame) frame;
                LOG.trace("WebSocket Client received data: {} bytes", binaryFrame.content().readableBytes());
                listener.onData(binaryFrame.content());
            } else if (frame instanceof ContinuationWebSocketFrame) {
                ContinuationWebSocketFrame continuationFrame = (ContinuationWebSocketFrame) frame;
                LOG.trace("WebSocket Client received data continuation: {} bytes", continuationFrame.content().readableBytes());
                listener.onData(continuationFrame.content());
            } else if (frame instanceof PingWebSocketFrame) {
                LOG.trace("WebSocket Client received ping, response with pong");
                ch.write(new PongWebSocketFrame(frame.content()));
            } else if (frame instanceof CloseWebSocketFrame) {
                LOG.trace("WebSocket Client received closing");
                ch.close();
            }
        }
    }
}
