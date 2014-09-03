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
import java.io.Serializable;
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
    public static final AttributeKey<String> CONNECTION_MAPPING = AttributeKey.valueOf("CONNECTION_MAPPING");
    public static final AttributeKey<RxTxStats> RXTX_STATS = AttributeKey.valueOf("RXTX_STATS");
    public static final AttributeKey<HashMap<String, String>> REMOTE_DEVICES = AttributeKey.valueOf("REMOTE_DEVICES");

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
        if (shdMx != null){
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
                                        Boolean resetLastSendAck = Boolean.parseBoolean(req.headers().get(MessageFields.resetLastSendAck));
                                        handleValidationComplete(ch, req.headers().get(MessageFields.version), resetLastSendAck);
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
                        Platform.shelloidLogger.error ("Rejecting agent client connection due to auth failure: " + req.headers().get(MessageFields.key) + ":"  +req.headers().get(MessageFields.secret));
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
        } else if (frame instanceof TextWebSocketFrame) {
            processWebSocketTextFrame(ctx.channel(), (TextWebSocketFrame) frame);
        } else if (frame instanceof PongWebSocketFrame) {
            /* Do nothing */
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
            String devId = ch.attr(CONNECTION_MAPPING).get();
            if (devId != null) { /* might be load balancer/disconnect before authenticate */

                jedis = platform.getRedisConnection();
                conn = platform.getDbConnection();
                messenger.removeDevice(devId);
                jedis.publish("nodeStatus:" + devId, getNodeStatusMsg(devId, MessageValues.D, new Object[]{"*"}));
                jedis.hdel("deviceMap", devId);
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

    private void processWebSocketTextFrame(Channel ch, TextWebSocketFrame frame) throws Exception {
        Jedis jedis = null;
        Connection conn = null;
        try {
            String request = ((TextWebSocketFrame) frame).text();
            ShelloidMessage msg = ShelloidMessage.parse(request);
            DeferredRedisTransaction tx = new DeferredRedisTransaction();
            ConnectionMetadata cm = messenger.getDevice(ch.attr(CONNECTION_MAPPING).get());
            if (cm == null) {
                Platform.shelloidLogger.debug("Server Received " + request + " from UNKNOWN, so ignoring");
            } else {
                Platform.shelloidLogger.debug("Server Received " + request + " from " + cm.getClientId());
                conn = platform.getDbConnection();
                jedis = platform.getRedisConnection();
                conn.setAutoCommit(false);
                String type = msg.getString(MessageFields.type);
                if (type.equals(MessageTypes.URGENT)) {
                    switch (msg.getString(MessageFields.subType)) {
                        case MessageTypes.ACK: {
                            long seqNo = msg.getLong(MessageFields.seqNum);
                            messenger.onClientAck(jedis, tx, cm, seqNo);
                            break;
                        }
                        case MessageTypes.TUNNEL: {
                            handleTunnelMsg(msg, ch, conn, jedis, cm);
                            break;
                        }
                    }
                } else {
                    long seqNum = Long.parseLong(msg.getString(MessageFields.seqNum));
                    long seqInReds = messenger.getLastSendAckFromRedis(jedis, cm.getClientId());
                    try {
                        if (seqNum > seqInReds) {
                            String portMapId = msg.getString(MessageFields.portMapId);
                            switch (type) {
                                case MessageTypes.PORT_OPENED: {
                                    if (shdMx != null){
                                        shdMx.onOpenPortMap(conn, portMapId);
                                    }
                                    handlePortOpenedMethod(msg, conn, ch, jedis, tx, cm);
                                    break;
                                }
                                case MessageTypes.LISTENING_STARTED: {
                                    if (shdMx != null){
                                        shdMx.onOpenPortMap(conn, portMapId);
                                    }
                                    handleListeningStartedMethod(msg, conn, ch, jedis);
                                    break;
                                }
                                case MessageTypes.LISTENING_STOPPED: {
                                    if (shdMx != null){
                                        shdMx.onClosePortMap(conn, portMapId);
                                    }
                                    Database.doUpdate(conn, "DELETE FROM port_maps WHERE id = ?", new Object[]{portMapId});
                                    updatePortMapStatus(jedis, conn, portMapId, PORT_MAP_DELETED, null);
                                    break;
                                }
                                case MessageTypes.PORT_CLOSED: {
                                    if (shdMx != null){
                                        shdMx.onClosePortMap(conn, portMapId);
                                    }
                                    Database.doUpdate(conn, "UPDATE port_maps SET app_side_status = 'PENDING', mapped_port = -1 WHERE id = ?", new Object[]{portMapId});
                                    updatePortMapStatus(jedis, conn, portMapId, PORT_MAP_APP_SIDE_CLOSED, null);
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
        }
        finally {
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
        ShelloidMessage ack = new ShelloidMessage();
        ack.put(MessageFields.type, MessageTypes.URGENT);
        ack.put(MessageFields.subType, MessageTypes.ACK);
        ack.put(MessageFields.seqNum, seqNum);
        messenger.sendImmediateToConnection(ch, ack);
    }

    private void handleListeningStartedMethod(ShelloidMessage msg, Connection conn, Channel ch, Jedis jedis) throws ShelloidNonRetriableException, SQLException {
        String portMapId = msg.getString(MessageFields.portMapId);
        String mappedPort = msg.getString(MessageFields.mappedPort);
        ArrayList<HashMap<String, Object>> list = Database.getResult(conn, "SELECT id, mapped_dev_id FROM port_maps WHERE mapped_dev_id = ? AND id = ?", new Object[]{ch.attr(CONNECTION_MAPPING).get(), portMapId});
        if (list.size() > 0) {
            Database.doUpdate(conn, "UPDATE port_maps SET app_side_status = 'READY', mapped_port = ? WHERE id = ?", new Object[]{mappedPort, portMapId});
            updatePortMapStatus(jedis, conn, portMapId, PORT_MAP_SVC_SIDE_OPEN, mappedPort);
        } else {
            Platform.shelloidLogger.error("Invalid mapped device ID(" + ch.attr(CONNECTION_MAPPING).get() + ") or port map ID(" + portMapId + ")");
        }
    }

    private void handlePortOpenedMethod(ShelloidMessage msg, Connection conn, Channel ch, Jedis jedis, DeferredRedisTransaction tx, ConnectionMetadata cm) throws ShelloidNonRetriableException, SQLException {
        String portMapId = msg.getString(MessageFields.portMapId);
        ArrayList<HashMap<String, Object>> list = Database.getResult(conn, "SELECT id, mapped_dev_id FROM port_maps WHERE svc_dev_id = ? AND id = ?", new Object[]{ch.attr(CONNECTION_MAPPING).get(), portMapId});
        if (list.size() > 0) {
            Database.doUpdate(conn, "UPDATE port_maps SET svc_side_status = 'READY' WHERE id = ?", new Object[]{portMapId});
            updatePortMapStatus(jedis, conn, portMapId, PORT_MAP_APP_SIDE_OPEN, null);
            ShelloidMessage smsg = new ShelloidMessage();
            smsg.put(MessageFields.type, MessageTypes.START_LISTENING);
            smsg.put(MessageFields.portMapId, portMapId);
            sendToDevice(jedis, tx, cm, list.get(0).get("mapped_dev_id").toString(), smsg);
        } else {
            Platform.shelloidLogger.warn("No mapping with SvDevId = " + ch.attr(CONNECTION_MAPPING).get() + " and id = " + portMapId);
        }
    }

    private void sendToDevice(Jedis jedis, DeferredRedisTransaction tx, ConnectionMetadata conn, String deviceId, ShelloidMessage msg) throws ShelloidNonRetriableException {
        ConnectionMetadata dev = messenger.getDevice(deviceId);
        if (shdMx != null){
            shdMx.onGeneratedReliableMsg(conn, dev, deviceId, msg, jedis, tx);
        }
        if (dev == null) {
            if (shdMx == null){
                messenger.sendNoRouteMessage(jedis, conn, msg.getString(MessageFields.portMapId), msg.getString(MessageFields.connTs), deviceId, "Can't find a path to device " + deviceId);
            }
        } else {
            messenger.sendToClient(jedis, dev, msg);
        }
    }
    
    private void handleTunnelMsg(ShelloidMessage msg, Channel ch, Connection conn, Jedis jedis, ConnectionMetadata cm) throws SQLException, ShelloidNonRetriableException {
        String portMapId = msg.getString(MessageFields.portMapId);
        String remoteDevId = ch.attr(REMOTE_DEVICES).get().get(portMapId);
        if (remoteDevId == null) {
            boolean isSvcSide = Boolean.parseBoolean(msg.getString(MessageFields.isSvcSide));
            remoteDevId = rmsutils.getRemoteDeviceId(conn, isSvcSide, portMapId);
            ch.attr(REMOTE_DEVICES).get().put(portMapId, remoteDevId);
        }
        ConnectionMetadata remoteCm = messenger.getDevice(remoteDevId);
        if (remoteCm == null) {
            if (shdMx == null){
                Platform.shelloidLogger.debug("Not Forwarding tunnel message to " + remoteDevId + " since no addon loaded.");
            }
        } else {
            Platform.shelloidLogger.debug("Sending tunnel message to " + remoteDevId);
            messenger.sendImmediateToConnection(remoteCm.getChannel(), msg);
        }
        if (shdMx != null) {
            shdMx.onAgentTunnelMsg(cm, remoteCm, remoteDevId, msg, "0", jedis);
        }
    }

    private boolean verifyConnection(Channel ch, HttpHeaders headers) {
        boolean retVal = false;
        Connection conn = null;
        try {
            conn = platform.getDbConnection();
            ArrayList<HashMap<String, Object>> result = Database.getResult(conn, "SELECT id FROM devices WHERE device_key = ? AND secret = ?", new Object[]{headers.get(MessageFields.key), headers.get(MessageFields.secret)});
            if (result.size() > 0) {
                String devId = result.get(0).get("id").toString();
                ch.attr(CONNECTION_MAPPING).set(devId);
                ch.attr(REMOTE_DEVICES).set(new HashMap<String, String>());
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
            jedis = platform.getRedisConnection();
            conn = platform.getDbConnection();
            String devId = ch.attr(CONNECTION_MAPPING).get();
            ch.attr(RXTX_STATS).set(new RxTxStats());
            ConnectionMetadata cm = new ConnectionMetadata(devId, new Date().getTime(), ch);
            messenger.putDevice(devId, cm);
            if (shdMx != null){
                shdMx.onAgentAuth(cm, devId, Configurations.SERVER_IP);
            }
            jedis.hset("deviceMap", devId, Configurations.SERVER_IP);
            jedis.publish("nodeStatus:" + devId, getNodeStatusMsg(devId, MessageValues.C, new Object[]{"*"}));
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

    private static String getNodeStatusMsg(String clientId, String msg, Object[] users) {
        ShelloidMessage smsg = new ShelloidMessage();
        smsg.put(MessageFields.type, MessageTypes.NODEMSG);
        smsg.put(MessageFields.nodeId, clientId);
        smsg.put(MessageFields.msg, msg);
        smsg.put(MessageFields.users, users);
        return smsg.getJson();
    }

    private void sendPortmapInfoToDeviceOnAuth(Jedis jedis, Connection conn, String devId, Channel ch) throws SQLException, ShelloidNonRetriableException {
        ArrayList<HashMap<String, Object>> portMaps = Database.getResult(conn, "SELECT * FROM port_maps WHERE svc_dev_id = ? OR mapped_dev_id = ?", new Object[]{devId, devId});
        ShelloidMessage msg = new ShelloidMessage();
        msg.put(MessageFields.type, MessageTypes.URGENT);
        msg.put(MessageFields.subType, MessageTypes.DEVICE_MAPPINGS);
        ArrayList<PortMap> guestPortMaps = new ArrayList<>();
        ArrayList<PortMap> hostPortMaps = new ArrayList<>();
        for (HashMap<String, Object> map : portMaps) {
            String hostDevId = map.get("svc_dev_id").toString();
            if (hostDevId.equals(devId)) {
                hostPortMaps.add(new PortMap(map.get("id").toString(), map.get("svc_port").toString(), map.get("disabled").toString()));
            } else {
                guestPortMaps.add(new PortMap(map.get("id").toString(), map.get("mapped_port").toString(), map.get("disabled").toString()));
            }
        }
        msg.put(MessageFields.guestPortMappings, guestPortMaps.toArray());
        msg.put(MessageFields.hostPortMappings, hostPortMaps.toArray());
        messenger.sendImmediateToConnection(ch, msg);
    }

    private String getPortMapStatusMessage(int portMapStatus, String mappedPort) {
        ShelloidMessage msg = new ShelloidMessage();
        msg.put(MessageFields.action, portMapStatus);
        if (mappedPort != null) {
            msg.put(MessageFields.mappedPort, mappedPort);
        }
        return msg.getJson();
    }

    private void updatePortMapStatus(Jedis jedis, Connection conn, String portMapId, int portMapStatusMessage, String data) throws SQLException {
        Database.doUpdate(conn, "DELETE FROM device_updates WHERE updateType = 'portMapStatus' AND refId = ?", new Object[]{portMapId});
        Database.doUpdate(conn, "INSERT INTO device_updates (updateType, refId, update_ts, status, params) VALUES ('portMapStatus', ?, CURRENT_TIMESTAMP, ?, ?)", new Object[]{portMapId, portMapStatusMessage + "", data});
        jedis.publish("portMapStatus:" + portMapId, getPortMapStatusMessage(portMapStatusMessage, data));
    }


    private static class PortMap implements Serializable {

        private String portMapId;
        private String port;
        private String disabled;

        public PortMap() {
        }

        public PortMap(String portMapId, String mappedPort, String disabled) {
            this.portMapId = portMapId;
            this.port = mappedPort;
            this.disabled = disabled;
        }

        public String getPortMapId() {
            return portMapId;
        }

        public void setPortMapId(String portMapId) {
            this.portMapId = portMapId;
        }

        public String getMappedPort() {
            return port;
        }

        public void setMappedPort(String mappedPort) {
            this.port = mappedPort;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }

        public String getDisabled() {
            return disabled;
        }

        public void setDisabled(String disabled) {
            this.disabled = disabled;
        }
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
