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

import com.google.protobuf.TextFormat;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import static io.netty.handler.codec.http.HttpHeaders.*;
import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.*;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import org.shelloid.common.Database;
import org.shelloid.common.DeferredRedisTransaction;
import org.shelloid.common.ShelloidUtil;
import org.shelloid.common.enums.HttpContentTypes;
import org.shelloid.common.exceptions.ShelloidNonRetriableException;
import org.shelloid.common.messages.MessageValues;
import org.shelloid.common.messages.ShelloidHeaderFields;
import org.shelloid.common.messages.ShelloidMessageModel.*;
import org.shelloid.ptcp.HelperFunctions;
import org.shelloid.vpt.rms.App;
import org.shelloid.vpt.rms.ConnectionMetadata;
import org.shelloid.vpt.rms.addon.ShelloidMX;
import org.shelloid.vpt.rms.util.CloudReliableMessenger;
import org.shelloid.vpt.rms.util.Configurations;
import org.shelloid.vpt.rms.util.Platform;
import org.shelloid.vpt.rms.util.RMSUtils;
import redis.clients.jedis.Jedis;

/* @author Harikrishnan */
public class VPTServerHandler extends SimpleChannelInboundHandler<Object> {

    private static final int PORT_MAP_DELETED = 0;
    private static final int PORT_MAP_APP_SIDE_CLOSED = 1;
    private static final int PORT_MAP_SVC_SIDE_OPEN = 2;
    private static final int PORT_MAP_APP_SIDE_OPEN = 3;
    private ShelloidMX shdMx;

    // <editor-fold defaultstate="collapsed" desc="Other codes from the netty web sockets">
    public static final AttributeKey<Long> CONNECTION_MAPPING = AttributeKey.valueOf("CONNECTION_MAPPING");
    public static final AttributeKey<RxTxStats> RXTX_STATS = AttributeKey.valueOf("RXTX_STATS");
    public static final AttributeKey<HashMap<Long, Long>> REMOTE_DEVICES = AttributeKey.valueOf("REMOTE_DEVICES");

    private WebSocketServerHandshaker handshaker;
    private final CloudReliableMessenger messenger;
    private final Platform platform;
    private final RMSUtils rmsutils;

    public VPTServerHandler(ShelloidMX shdMx) {
        rmsutils = RMSUtils.getInstance();
        messenger = CloudReliableMessenger.getInstance();
        platform = Platform.getInstance();
        this.shdMx = shdMx;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ConnectionMetadata cm = messenger.getDevice(ctx.channel().attr(CONNECTION_MAPPING).get());
        if (shdMx != null) {
            shdMx.onAgentDisconnect(cm);
        }
        onDisconnect(ctx.channel());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {

    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, final FullHttpRequest req) {
        if (!req.getDecoderResult().isSuccess()) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST));
        } else if (req.getMethod() != GET) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN));
        } else {
            if (Configurations.WEBSOCKET_PATH.equals(req.getUri())) {
                WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(getWebSocketLocation(req), null, false, ShelloidUtil.getMaxFrameSize());
                handshaker = wsFactory.newHandshaker(req);
                if (handshaker == null) {
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                    Platform.shelloidLogger.warn("Client connection rejected.");
                } else {
                    final Channel ch = ctx.channel();
                    if (verifyConnection(ch, req.headers())) {
                        handshaker.handshake(ch, req).addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future) throws Exception {
                                try {
                                    if (future.isSuccess()) {
                                        Boolean resetLastSendAck = Boolean.parseBoolean(req.headers().get(ShelloidHeaderFields.resetLastSendAck));
                                        handleValidationComplete(ch, req.headers().get(ShelloidHeaderFields.version), resetLastSendAck);
                                    } else {
                                        Platform.shelloidLogger.warn("Agent authentication failed!");
                                        ch.close();
                                    }
                                } catch (Exception ex) {
                                    Platform.shelloidLogger.error("Error :" + ex.getMessage(), ex);
                                    ch.close();
                                }
                            }
                        });
                    } else {
                        Platform.shelloidLogger.error("Rejecting agent client connection due to auth failure: " + req.headers().get(ShelloidHeaderFields.key) + ":" + req.headers().get(ShelloidHeaderFields.secret));
                        WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ch);
                        ctx.close();
                    }
                }
            } else {
                processHttpRequest(ctx, req);
            }
        }
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof BinaryWebSocketFrame) {
            BinaryWebSocketFrame binFrame = (BinaryWebSocketFrame) frame;
            ByteBuf b = binFrame.content();
            byte[] bytes = new byte[b.capacity()];
            b.getBytes(0, bytes);
            processWebSocketTextFrame(ctx.channel(), bytes);
        } else if (frame instanceof PongWebSocketFrame) {
            /* Do nothing */
        } else if (frame instanceof TextWebSocketFrame) {
            throw new Exception("TextWebSocketFrame" + ((TextWebSocketFrame) frame).text());
        } else {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass().getName()));
        }
    }

    private static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
        if (res.getStatus().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            setContentLength(res, res.content().readableBytes());
        }
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!isKeepAlive(req) || res.getStatus().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException) {
            Platform.shelloidLogger.error("Error: " + cause.getMessage());
        } else {
            Platform.shelloidLogger.error("Error: " + cause.getMessage(), cause);
        }
        ctx.close();
    }

    private static String getWebSocketLocation(FullHttpRequest req) {
        String location = req.headers().get(HOST) + Configurations.WEBSOCKET_PATH;
        return "wss://" + location;
    }

    public static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, String content, HttpContentTypes contentType, HttpResponseStatus httpResponse) {
        ByteBuf buffer = Unpooled.copiedBuffer(content, CharsetUtil.US_ASCII);
        FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, httpResponse, buffer);
        res.headers().set(CONTENT_TYPE, contentType);
        setContentLength(res, buffer.readableBytes());
        sendHttpResponse(ctx, req, res);
    }

    private void processHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.getUri().equals("/ping")) {
            sendHttpResponse(ctx, req, "<B>Server is Running</B>", HttpContentTypes.TEXT_HTML, OK);
        } else {
            sendHttpResponse(ctx, req, "", HttpContentTypes.TEXT_HTML, NOT_FOUND);
        }
    }

    private void onDisconnect(Channel ch) {
        Jedis jedis = null;
        Connection conn = null;
        try {
            Long devId = ch.attr(CONNECTION_MAPPING).get();
            if (devId != null) { /* might be load balancer/disconnect before authenticate */

                jedis = platform.getRedisConnection();
                conn = platform.getDbConnection();
                messenger.removeDevice(devId);
                ArrayList<String> list = new ArrayList<>();
                list.add("*");
                byte[] nodeStatus = getNodeStatusMsg(devId, MessageValues.D, list);
                jedis.publish("nodeStatus:" + devId, HelperFunctions.toHexString(nodeStatus, 0, nodeStatus.length));
                jedis.hdel("deviceMap", devId + "");
                Database.doUpdate(conn, "UPDATE devices SET status='D', last_disconnect_ts = CURRENT_TIMESTAMP WHERE id = ?", new Object[]{devId});
                Database.doUpdate(conn, "DELETE FROM device_updates WHERE updateType = 'nodeStatus' AND refId = ? AND (status = 'C' OR status = 'D')", new Object[]{devId});
                Database.doUpdate(conn, "INSERT INTO device_updates (updateType, refId, update_ts, status) VALUES ('nodeStatus', ?, CURRENT_TIMESTAMP, 'D')", new Object[]{devId});
                Platform.shelloidLogger.warn("Agent " + devId + " disconnected.");
            }
        } catch (Exception ex) {
            Platform.shelloidLogger.error("Can't properly disconnect device: ", ex);
        } finally {
            if (jedis != null) {
                platform.returnJedis(jedis);
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ex) {
                    Platform.shelloidLogger.error("DB connection error: ", ex);
                }
            }
        }
    }
    // </editor-fold>

    private void processWebSocketTextFrame(Channel ch, byte[] data) throws Exception {
        Jedis jedis = null;
        Connection conn = null;
        try {
            ShelloidMessage msg = ShelloidMessage.parseFrom(data);
            DeferredRedisTransaction tx = new DeferredRedisTransaction();
            ConnectionMetadata cm = messenger.getDevice(ch.attr(CONNECTION_MAPPING).get());
            MessageTypes type = msg.getType();
            if (cm == null) {
                Platform.shelloidLogger.debug("Server Received {" + TextFormat.shortDebugString(msg) + "} message from UNKNOWN, so ignoring");
            } else {
                Platform.shelloidLogger.debug("Server Received {" + TextFormat.shortDebugString(msg) + "} message from " + cm.getClientId());
                conn = platform.getDbConnection();
                jedis = platform.getRedisConnection();
                conn.setAutoCommit(false);
                if (type == MessageTypes.URGENT) {
                    switch (msg.getSubType()) {
                        case ACK: {
                            long seqNo = msg.getSeqNum();
                            messenger.onClientAck(jedis, tx, cm, seqNo);
                            break;
                        }
                        case TUNNEL: {
                            handleTunnelMsg(msg, ch, conn, jedis, cm);
                            break;
                        }
                    }
                } else {
                    long seqNum = msg.getSeqNum();
                    long seqInReds = messenger.getLastSendAckFromRedis(jedis, Long.parseLong(cm.getClientId()));
                    try {
                        if (seqNum > seqInReds) {
                            long portMapId = msg.getPortMapId();
                            switch (type) {
                                case PORT_OPENED: {
                                    if (shdMx != null) {
                                        shdMx.onOpenPortMap(conn, portMapId);
                                    }
                                    handlePortOpenedMethod(msg, conn, ch, jedis, tx, cm);
                                    break;
                                }
                                case LISTENING_STARTED: {
                                    if (shdMx != null) {
                                        shdMx.onOpenPortMap(conn, portMapId);
                                    }
                                    handleListeningStartedMethod(msg, conn, ch, jedis);
                                    break;
                                }
                                case LISTENING_STOPPED: {
                                    if (shdMx != null) {
                                        shdMx.onClosePortMap(conn, portMapId);
                                    }
                                    Database.doUpdate(conn, "DELETE FROM port_maps WHERE id = ?", new Object[]{portMapId});
                                    updatePortMapStatus(jedis, conn, portMapId, PORT_MAP_DELETED, -1);
                                    break;
                                }
                                case PORT_CLOSED: {
                                    if (shdMx != null) {
                                        shdMx.onClosePortMap(conn, portMapId);
                                    }
                                    Database.doUpdate(conn, "UPDATE port_maps SET app_side_status = 'PENDING', mapped_port = -1 WHERE id = ?", new Object[]{portMapId});
                                    updatePortMapStatus(jedis, conn, portMapId, PORT_MAP_APP_SIDE_CLOSED, -1);
                                    break;
                                }
                                default: {
                                    break;
                                }
                            }
                        } else {
                            Platform.shelloidLogger.warn("Ignoring message since currentSeqNum (" + seqNum + ") is less than seq number in redis " + seqInReds);
                        }
                    } finally {
                        sendAckToDevice(seqNum, ch);
                        if (seqInReds < seqNum) {
                            messenger.updateLastSendAckInRedis(jedis, ch.attr(CONNECTION_MAPPING).get(), seqNum);
                        }
                    }
                }
                tx.execute(jedis);
                conn.commit();
            }
        } finally {
            if (jedis != null) {
                platform.returnJedis(jedis);
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ex) {
                    Platform.shelloidLogger.error("Can't close DB connection: ", ex);
                }
            }
        }
    }

    private void sendAckToDevice(long seqNum, Channel ch) {

        ShelloidMessage.Builder ack = ShelloidMessage.newBuilder();
        ack.setType(MessageTypes.URGENT);
        ack.setSubType(MessageTypes.ACK);
        ack.setSeqNum(seqNum);
        messenger.sendImmediateToConnection(ch, ack.build());
    }

    private void handleListeningStartedMethod(ShelloidMessage msg, Connection conn, Channel ch, Jedis jedis) throws ShelloidNonRetriableException, SQLException {
        long portMapId = msg.getPortMapId();
        int mappedPort = msg.getMappedPort();
        ArrayList<HashMap<String, Object>> list = Database.getResult(conn, "SELECT id, mapped_dev_id FROM port_maps WHERE mapped_dev_id = ? AND id = ?", new Object[]{ch.attr(CONNECTION_MAPPING).get(), portMapId});
        if (list.size() > 0) {
            Database.doUpdate(conn, "UPDATE port_maps SET app_side_status = 'READY', mapped_port = ? WHERE id = ?", new Object[]{mappedPort, portMapId});
            updatePortMapStatus(jedis, conn, portMapId, PORT_MAP_SVC_SIDE_OPEN, mappedPort);
        } else {
            Platform.shelloidLogger.error("Invalid mapped device ID(" + ch.attr(CONNECTION_MAPPING).get() + ") or port map ID(" + portMapId + ")");
        }
    }

    private void handlePortOpenedMethod(ShelloidMessage msg, Connection conn, Channel ch, Jedis jedis, DeferredRedisTransaction tx, ConnectionMetadata cm) throws ShelloidNonRetriableException, SQLException {
        long portMapId = msg.getPortMapId();
        ArrayList<HashMap<String, Object>> list = Database.getResult(conn, "SELECT id, mapped_dev_id FROM port_maps WHERE svc_dev_id = ? AND id = ?", new Object[]{ch.attr(CONNECTION_MAPPING).get(), portMapId});
        if (list.size() > 0) {
            Database.doUpdate(conn, "UPDATE port_maps SET svc_side_status = 'READY' WHERE id = ?", new Object[]{portMapId});
            updatePortMapStatus(jedis, conn, portMapId, PORT_MAP_APP_SIDE_OPEN, -1);
            ShelloidMessage.Builder smsg = ShelloidMessage.newBuilder();
            smsg.setType(MessageTypes.START_LISTENING);
            smsg.setPortMapId(portMapId);
            sendToDevice(jedis, tx, cm, Long.parseLong(list.get(0).get("mapped_dev_id").toString()), smsg.build());
        } else {
            Platform.shelloidLogger.warn("No mapping with SvDevId = " + ch.attr(CONNECTION_MAPPING).get() + " and id = " + portMapId);
        }
    }

    private void sendToDevice(Jedis jedis, DeferredRedisTransaction tx, ConnectionMetadata conn, long deviceId, ShelloidMessage msg) throws ShelloidNonRetriableException {
        ConnectionMetadata dev = messenger.getDevice(deviceId);
        if (shdMx != null) {
            shdMx.onGeneratedReliableMsg(conn, dev, deviceId, msg, jedis, tx);
        }
        if (dev == null) {
            if (shdMx == null) {
                messenger.sendNoRouteMessage(jedis, conn, msg.getPortMapId(), msg.getConnTs(), deviceId, "Can't find a path to device " + deviceId);
            }
        } else {
            messenger.sendToClient(jedis, dev, msg);
        }
    }

    private void handleTunnelMsg(ShelloidMessage msg, Channel ch, Connection conn, Jedis jedis, ConnectionMetadata cm) throws SQLException, ShelloidNonRetriableException {
        long portMapId = msg.getPortMapId();
        Long remoteDevId = ch.attr(REMOTE_DEVICES).get().get(portMapId);
        if (remoteDevId == null) {
            boolean isSvcSide = msg.getIsSvcSide();
            remoteDevId = rmsutils.getRemoteDeviceId(conn, isSvcSide, portMapId);
            ch.attr(REMOTE_DEVICES).get().put(portMapId, remoteDevId);
        }
        ConnectionMetadata remoteCm = messenger.getDevice(remoteDevId);
        if (remoteCm == null) {
            if (shdMx == null) {
                Platform.shelloidLogger.debug("Not Forwarding tunnel message to " + remoteDevId + " since no addon loaded.");
            }
        } else {
            Platform.shelloidLogger.debug("Sending tunnel message to " + remoteDevId);
            messenger.sendImmediateToConnection(remoteCm.getChannel(), msg);
        }
        if (shdMx != null) {
            shdMx.onAgentTunnelMsg(cm, remoteCm, remoteDevId, msg, 0, jedis);
        }
    }

    private boolean verifyConnection(Channel ch, HttpHeaders headers) {
        boolean retVal = false;
        Connection conn = null;
        try {
            conn = platform.getDbConnection();
            ArrayList<HashMap<String, Object>> result = Database.getResult(conn, "SELECT id FROM devices WHERE device_key = ? AND secret = ?", new Object[]{headers.get(ShelloidHeaderFields.key), headers.get(ShelloidHeaderFields.secret)});
            if (result.size() > 0) {
                long devId = Long.parseLong(result.get(0).get("id").toString());
                ch.attr(CONNECTION_MAPPING).set(devId);
                ch.attr(REMOTE_DEVICES).set(new HashMap<Long, Long>());
                retVal = true;
            }
        } catch (SQLException ex) {
            Platform.shelloidLogger.error("DB Error", ex);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ex) {
                    Platform.shelloidLogger.error("Could not close the connection: ", ex);
                }
            }
        }
        return retVal;
    }

    private void handleValidationComplete(Channel ch, String version, boolean resetLastSendAck) throws SQLException, ShelloidNonRetriableException {
        Connection conn = null;
        Jedis jedis = null;
        try {
            Long devId = ch.attr(CONNECTION_MAPPING).get();
            double ver = Double.parseDouble(version.substring(0, 2));
            if (ver < Configurations.MIN_COMPATIBLE_AGENT_VERSION){
                throw new ShelloidNonRetriableException("Device with ID " + devId + " is trying to connect with incompatible version ("+version+")");
            } else {
                jedis = platform.getRedisConnection();
                conn = platform.getDbConnection();
                ch.attr(RXTX_STATS).set(new RxTxStats());
                ConnectionMetadata cm = new ConnectionMetadata(devId + "", new Date().getTime(), ch);
                messenger.putDevice(devId, cm);
                if (shdMx != null) {
                    shdMx.onAgentAuth(cm, devId, Configurations.SERVER_IP);
                }
                jedis.hset("deviceMap", devId + "", Configurations.SERVER_IP);
                ArrayList<String> list = new ArrayList<>();
                list.add("*");
                byte [] nodeStatusMessage = getNodeStatusMsg(devId, MessageValues.C, list);
                jedis.publish("nodeStatus:" + devId, HelperFunctions.toHexString(nodeStatusMessage, 0, nodeStatusMessage.length));
                long lastSendAck = -1;
                if (resetLastSendAck) {
                    Platform.shelloidLogger.warn("Resetting last sent ack for " + devId);
                    messenger.updateLastSendAckInRedis(jedis, devId, -1);
                } else {
                    lastSendAck = messenger.getLastSendAckFromRedis(jedis, devId);
                }
                sendAckToDevice(lastSendAck, ch);
                sendPortmapInfoToDeviceOnAuth(jedis, conn, devId, ch);
                Database.doUpdate(conn, "UPDATE devices SET status='C', version = ?, last_connection_ts = CURRENT_TIMESTAMP WHERE id = ?", new Object[]{version, devId});
                Database.doUpdate(conn, "DELETE FROM device_updates WHERE updateType = 'nodeStatus' AND refId = ? AND (status = 'C' OR status = 'D')", new Object[]{devId});
                Database.doUpdate(conn, "INSERT INTO device_updates (updateType, refId, update_ts, status) VALUES ('nodeStatus', ?, CURRENT_TIMESTAMP, ?)", new Object[]{devId, "C"});
                App.queryNewMsgs(devId);
                Platform.shelloidLogger.warn("Agent " + devId + " connected via " + ch);
            }
        } finally {
            if (jedis != null) {
                platform.returnJedis(jedis);
            }
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ex) {
                    Platform.shelloidLogger.error("Could not close the connection: ", ex);
                }
            }
        }
    }

    private static byte[] getNodeStatusMsg(long clientId, String msg, Iterable<String> users) {
        ShelloidMessage.Builder smsg = ShelloidMessage.newBuilder();
        smsg.setType(MessageTypes.NODEMSG);
        smsg.setNodeId(clientId);
        smsg.setMsg(msg);
        smsg.addAllUsers(users);
        return smsg.build().toByteArray();
    }

    private void sendPortmapInfoToDeviceOnAuth(Jedis jedis, Connection conn, long devId, Channel ch) throws SQLException, ShelloidNonRetriableException {
        ArrayList<HashMap<String, Object>> portMaps = Database.getResult(conn, "SELECT * FROM port_maps WHERE svc_dev_id = ? OR mapped_dev_id = ?", new Object[]{devId, devId});
        ShelloidMessage.Builder msg = ShelloidMessage.newBuilder();
        msg.setType(MessageTypes.URGENT);
        msg.setSubType(MessageTypes.DEVICE_MAPPINGS);
        ArrayList<PortMappingInfo> guestPortMaps = new ArrayList<>();
        ArrayList<PortMappingInfo> hostPortMaps = new ArrayList<>();
        for (HashMap<String, Object> map : portMaps) {
            Long hostDevId = Long.parseLong(map.get("svc_dev_id").toString());
            PortMappingInfo.Builder info = PortMappingInfo.newBuilder();
            info.setDisabled(Boolean.parseBoolean(map.get("disabled").toString()));
            info.setPortMapId(Long.parseLong(map.get("id").toString()));
            if (hostDevId == devId) {
                info.setPort(Integer.parseInt(map.get("svc_port").toString()));
                hostPortMaps.add(info.build());
            } else {
                info.setPort(Integer.parseInt(map.get("mapped_port").toString()));
                guestPortMaps.add(info.build());
            }
        }
        msg.addAllGuestPortMappings(guestPortMaps);
        msg.addAllHostPortMappings(hostPortMaps);
        messenger.sendImmediateToConnection(ch, msg.build());
    }

    private byte[] getPortMapStatusMessage(int portMapStatus, int mappedPort) {
        ShelloidMessage.Builder msg = ShelloidMessage.newBuilder();
        msg.setType(MessageTypes.OTHER_MESSAGES);
        msg.setAction(portMapStatus + "");
        if (mappedPort != -1) {
            msg.setMappedPort(mappedPort);
        }
        return msg.build().toByteArray();
    }

    private void updatePortMapStatus(Jedis jedis, Connection conn, long portMapId, int portMapStatusMessage, int mappedPort) throws SQLException {
        Database.doUpdate(conn, "DELETE FROM device_updates WHERE updateType = 'portMapStatus' AND refId = ?", new Object[]{portMapId});
        Database.doUpdate(conn, "INSERT INTO device_updates (updateType, refId, update_ts, status, params) VALUES ('portMapStatus', ?, CURRENT_TIMESTAMP, ?, ?)", new Object[]{portMapId, portMapStatusMessage + "", mappedPort});
        byte nodeStatus [] = getPortMapStatusMessage(portMapStatusMessage, mappedPort);
        jedis.publish("portMapStatus:" + portMapId, HelperFunctions.toHexString(nodeStatus, 0, nodeStatus.length));
    }

    public static class RxTxStats {

        public long rxTotalCnt;
        public long txTotalCnt;
        public long windowRxCnt;
        public long windowTxCnt;
        public long windowStartTs;
        public Timestamp windowStartSqlTs;

        public RxTxStats() {
            this.rxTotalCnt = 0;
            this.txTotalCnt = 0;
            this.windowRxCnt = 0;
            this.windowTxCnt = 0;
            this.windowStartSqlTs = new Timestamp(new Date().getTime());
            this.windowStartTs = System.currentTimeMillis();
        }
    }
}
