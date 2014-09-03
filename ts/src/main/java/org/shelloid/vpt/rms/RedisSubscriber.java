/*
Copyright (c) Shelloid Systems LLP. All rights reserved.
The use and distribution terms for this software are covered by the
GNU Affero General Public License 3.0 (http://www.gnu.org/licenses/agpl-3.0.html)
which can be found in the file LICENSE at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.
*/
package org.shelloid.vpt.rms;

import com.google.gson.*;
import org.shelloid.common.exceptions.ShelloidNonRetriableException;
import org.shelloid.common.messages.ShelloidMessageModel.ShelloidMessage;
import org.shelloid.vpt.rms.util.CloudReliableMessenger;
import org.shelloid.vpt.rms.util.Platform;
import redis.clients.jedis.*;

/* @author Harikrishnan */
public class RedisSubscriber extends JedisPubSub {

    private final Gson gson;
    private final Platform platform;
    private final CloudReliableMessenger messenger;

    public RedisSubscriber() {
        gson = new Gson();
        platform = Platform.getInstance();
        messenger = CloudReliableMessenger.getInstance();
    }

    @Override
    public void onPMessage(String string, String string1, String string2) {
    }

    @Override
    public void onPUnsubscribe(String string, int i) {
    }

    @Override
    public void onPSubscribe(String string, int i) {
    }

    @Override
    public void onMessage(String channel, String msg) {
        try {
            ShelloidMessage smsg = ShelloidMessage.parseFrom(msg.getBytes());
            switch (smsg.getType()) {
                case NEW_MSG: {
                    Platform.shelloidLogger.debug("NEW arrived at Redis.");
                    handleNewMsg(smsg.getDeviceId());
                    break;
                }
                default: {
                    break;
                }
            }
        } catch (Exception ex) {
            Platform.shelloidLogger.error("Invalid JSON", ex);
        }
    }

    @Override
    public void onSubscribe(String string, int i) {
    }

    @Override
    public void onUnsubscribe(String string, int i) {
    }

    public void handleNewMsg(long deviceId) {
        Jedis jedis = null;
        try {
            jedis = platform.getRedisConnection();
            ConnectionMetadata cm = messenger.getDevice(deviceId);
            if (cm != null) {
                String msg = jedis.lpop("dev" + deviceId);
                while (msg != null) {
                    Platform.shelloidLogger.debug("NEW Message: " + msg);
                    messenger.sendToClient(jedis, cm, ShelloidMessage.parseFrom(msg.getBytes()));
                    msg = jedis.lpop("dev" + deviceId);
                }
            } else {
                Platform.shelloidLogger.error("Can't find device " + deviceId);
            }
        } catch (Exception ex) {
            Platform.shelloidLogger.error("Invalid JSON", ex);
        } finally {
            if (jedis != null) {
                platform.returnJedis(jedis);
            }
        }

    }
}
