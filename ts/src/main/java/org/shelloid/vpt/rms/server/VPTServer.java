/*
Copyright (c) Shelloid Systems LLP. All rights reserved.
The use and distribution terms for this software are covered by the
GNU Affero General Public License 3.0 (http://www.gnu.org/licenses/agpl-3.0.html)
which can be found in the file LICENSE at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.
*/
package org.shelloid.vpt.rms.server;

import org.shelloid.common.ShelloidUtil;
import org.shelloid.vpt.rms.util.Configurations;
import org.shelloid.vpt.rms.util.Platform;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.File;
import org.shelloid.vpt.rms.addon.ShelloidMX;

/* @author Harikrishnan */
public final class VPTServer {

    private final int port;
    private SslContext sslCtx;

    public VPTServer(int port) {
        this.port = port;
    }

    public Channel run(ShelloidMX mx) throws Exception {
        final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        final EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            sslCtx = SslContext.newServerContext(new File(Configurations.get(Configurations.ConfigParams.CHAIN_FILE)), new File(Configurations.get(Configurations.ConfigParams.KEY_FILE)));
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new VPTServerInitializer(sslCtx, mx));
            final Channel ch = b.bind(port).sync().channel();
            Platform.shelloidLogger.warn("VPT Server running on " + Configurations.SERVER_IP + ":" + Configurations.get(Configurations.ConfigParams.SERVER_PORT));
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    long timeOut = 1000 * 60 * 5;
                    Platform.shelloidLogger.warn("Gracefull shutdown initiated.");
                    ChannelFuture cf = ch.close();
                    cf.awaitUninterruptibly(timeOut);
                    bossGroup.shutdownGracefully().awaitUninterruptibly(timeOut);
                    workerGroup.shutdownGracefully().awaitUninterruptibly(timeOut);
                    Platform.shelloidLogger.warn("Gracefull shutdown finished.");
                }
            });
            ch.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
        return null;
    }

    private static class VPTServerInitializer extends ChannelInitializer<SocketChannel> {

        private final SslContext sslCtx;
        private final ShelloidMX mx;

        public VPTServerInitializer(SslContext sslCtx, ShelloidMX mx) {
            this.sslCtx = sslCtx;
            this.mx = mx;
        }

        @Override
        public void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc()));
            }
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast(new HttpObjectAggregator(ShelloidUtil.getMaxFrameSize()));
            pipeline.addLast("idleStateHandler", new IdleStateHandler(Configurations.PING_SEND_INTERVAL * 2, 0, Configurations.PING_SEND_INTERVAL));
            pipeline.addLast("idleTimeHandler", new ShelloidIdleTimeHandler());
            pipeline.addLast(new VPTServerHandler(mx));
        }
    }
}

class ShelloidIdleTimeHandler extends ChannelDuplexHandler {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            switch (((IdleStateEvent)evt).state()){
                case ALL_IDLE:
                {
                    Platform.shelloidLogger.debug("Server sending PingWebSocketFrame to " + ctx.channel());
                    ctx.channel().writeAndFlush(new PingWebSocketFrame());
                    break;
                }
                case READER_IDLE:{
                    Platform.shelloidLogger.debug("Read Idle for " + (Configurations.PING_SEND_INTERVAL * 2) + " seconds. So closing the channel: " + ctx.channel());
                    ctx.channel().close();
                    break;
                }
            }
        }
    }
}
