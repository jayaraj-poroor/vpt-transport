/*
Copyright (c) Shelloid Systems LLP. All rights reserved.
The use and distribution terms for this software are covered by the
GNU Affero General Public License 3.0 (http://www.gnu.org/licenses/agpl-3.0.html)
which can be found in the file LICENSE at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.
*/
package org.shelloid.vpt.rms.util;

import org.shelloid.common.messages.MessageTypes;
import org.shelloid.common.messages.ShelloidMessage;
import org.shelloid.common.exceptions.ShelloidNonRetriableException;
import org.shelloid.vpt.rms.ConnectionMetadata;
import org.shelloid.common.DeferredRedisTransaction;
import org.shelloid.common.messages.MessageFields;
import static org.shelloid.vpt.rms.server.VPTServerHandler.CONNECTION_MAPPING;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import redis.clients.jedis.*;

/* @author Harikrishnan */
public class CloudReliableMessenger {

    private static final ConcurrentHashMap<String, ConnectionMetadata> serverMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConnectionMetadata> connectedDevicesMap = new ConcurrentHashMap<>();

    private static final CloudReliableMessenger messager = new CloudReliableMessenger();

    public static CloudReliableMessenger getInstance() {
        return messager;
    }

    private CloudReliableMessenger() {
    }

    public void putDevice(String key, ConnectionMetadata val) {
        if (val == null){
            return;
        }
        connectedDevicesMap.put(key, val);
    }

    public void removeDevice(String devId) {
        connectedDevicesMap.remove(devId);
    }

    public void removeServer(String remoteIp) {
        serverMap.remove(remoteIp);
    }

    public ConnectionMetadata getDevice(String key) {
        if (key == null){
            return null;
        }
        return connectedDevicesMap.get(key);
    }

    public void putServer(String key, ConnectionMetadata val) {
        serverMap.put(key, val);
    }

    public ConnectionMetadata getServer(String key) {
        return serverMap.get(key);
    }
    
    public void sendNoRouteMessage(Jedis jedis, ConnectionMetadata connection, String portMapId, String connTs, String destinationDeviceId, String strMsg) throws ShelloidNonRetriableException {
        ShelloidMessage msg = new ShelloidMessage();
        msg.put(MessageFields.type, MessageTypes.URGENT);
        msg.put(MessageFields.subType, MessageTypes.NO_ROUTE);
        msg.put(MessageFields.portMapId, portMapId);
        msg.put(MessageFields.connTs, connTs);
        msg.put(MessageFields.remoteDevId, destinationDeviceId);
        msg.put(MessageFields.msg, strMsg);
        Platform.shelloidLogger.error(strMsg);
        sendImmediateToConnection(connection.getChannel(), msg);
    }

    public void sendImmediateToConnection(Channel ctx, ShelloidMessage msg) {
        sendToWebSocket(ctx, msg.getJson());
    } 
    
    public void updateLastSendAckInRedis(Jedis tx, String clientId, long seqNo) {
        tx.hset("ms-ack", clientId + "", seqNo + "");
    }

    public long getLastSendAckFromRedis(Jedis jedis, String clientId) {
        String s = jedis.hget("ms-ack", clientId + "");
        long seqNum = -1;
        try{
            seqNum = Long.parseLong(s);
        }
        catch (NumberFormatException nfe){
            Platform.shelloidLogger.debug("NumberFormatException while parsing " +s+ " from Redis. So setting it as -1");
        }
        return seqNum;
    }

    public void sendToClient(Jedis jedis, ConnectionMetadata conn, ShelloidMessage msg) {
        String deviceId = conn.getClientId();
        long len;
        long seqNum = jedis.hincrBy("seq-node", deviceId + "", 1);
        synchronized(conn){
            len = jedis.rpush("mq-node" + deviceId, seqNum + "");
        }
        msg.put(MessageFields.seqNum, seqNum + "");
        String json = msg.getJson();
        jedis.hset("ms-node" + deviceId, seqNum + "", json);
        Platform.shelloidLogger.debug("Server Scheduling " + json + " and len = " + len + " for device " + deviceId);
        if (len == 1) {
            sendToWebSocket(conn.getChannel(), json);
        }
    }
    
    public void onClientAck(Jedis jedis, DeferredRedisTransaction tx, ConnectionMetadata conn, long recvdSeqNum) throws ShelloidNonRetriableException {
        String clientId = conn.getClientId();
        String queueKey = "mq-node" + clientId;
        String storeKey = "ms-node" + clientId;
        ArrayList<String> ackSeqNums = new ArrayList<>();
        String seqNumToSend = null;
        synchronized(conn){
            List<String> queuedSeqnums = jedis.lrange(queueKey, 0, Integer.MAX_VALUE);
            for (String sn : queuedSeqnums) {
                long seqNum = Long.parseLong(sn);
                if (seqNum <= recvdSeqNum) {
                    ackSeqNums.add(seqNum + "");
                } else if (seqNumToSend == null) {
                    seqNumToSend = sn;
                } else {
                    break;
                }
            }
            if (ackSeqNums.size() > 0) {
                tx.ltrim(queueKey, ackSeqNums.size(), Integer.MAX_VALUE);
                Object[] objarray = ackSeqNums.toArray();
                tx.hdel(storeKey, Arrays.copyOf(objarray, objarray.length, String[].class));
            }            
        }        
        if (seqNumToSend != null){
            sendQueuedMsgsToClient(jedis, conn, seqNumToSend);
        }
        else{
            Platform.shelloidLogger.debug("Server not sending anything since next seqNumToSend is null");
        }
    }

    private void sendQueuedMsgsToClient(Jedis jedis, ConnectionMetadata conn, String seqNumsToSend) {
        String key = "ms-node" + conn.getClientId();
        String msg = jedis.hget(key, seqNumsToSend);
        sendToWebSocket(conn.getChannel(), msg);
    }

    private void sendToWebSocket(Channel conn, String frame) {
        Platform.shelloidLogger.debug("Server Sending " + frame + " to " + conn.attr(CONNECTION_MAPPING).get());
        conn.writeAndFlush(new TextWebSocketFrame(frame));
    }
}