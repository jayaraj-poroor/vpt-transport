/*
Copyright (c) Shelloid Systems LLP. All rights reserved.
The use and distribution terms for this software are covered by the
GNU Affero General Public License 3.0 (http://www.gnu.org/licenses/agpl-3.0.html)
which can be found in the file LICENSE at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.
*/

package org.shelloid.common;

import java.util.ArrayList;
import java.util.Iterator;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

public class DeferredRedisTransaction
{
    public static final int CMD_HSET = 0;
    private static final int CMD_DEL = 1;
    private static final int CMD_RPUSH = 2;
    private static final int CMD_LTRIM = 3;
    private static final int CMD_HDEL = 4;
    private static final int CMD_LPOP = 5;
    private static final int CMD_PUBLISH = 6;
    private ArrayList<RedisCommand> cmds;

    public DeferredRedisTransaction()
    {
        cmds = new ArrayList<>();
    }

    public void hset(String id, String seqNum, String data)
    {
        cmds.add(new RedisCommand(CMD_HSET, id, seqNum, data));
    }

    public void execute(Jedis jedis)
    {
        Iterator<RedisCommand> it = cmds.iterator();
        Transaction tx = jedis.multi();
        while (it.hasNext())
        {
            RedisCommand cmd = it.next();
            switch (cmd.cmd)
            {
                case CMD_HSET:
                {
                    tx.hset(cmd.s1.toString(), cmd.s2.toString(), cmd.s3.toString());
                    break;
                }
                case CMD_DEL:
                {
                    tx.del(cmd.s1.toString());
                    break;
                }
                case CMD_RPUSH:
                {
                    tx.rpush(cmd.s1.toString(), cmd.s2.toString());
                    break;
                }
                case CMD_LTRIM:
                {
                    tx.ltrim(cmd.s1.toString(), (Long)cmd.s2, (Long)cmd.s3);
                    break;
                }
                case CMD_HDEL:
                {
                    tx.hdel(cmd.s1.toString(), (String[])cmd.s2);
                    break;
                }
                case CMD_LPOP:
                {
                    tx.lpop(cmd.s1.toString());
                    break;
                }
                case CMD_PUBLISH: {
                    tx.publish(cmd.s1.toString(), cmd.s2.toString());
                    break;
                }
            }
        }
        tx.exec();
    }

    public void del(String string)
    {
        cmds.add(new RedisCommand(CMD_DEL, string));
    }

    public void rpush(String s1, String s2)
    {
        cmds.add(new RedisCommand(CMD_RPUSH, s1, s2));
    }

    public void lpop(String s1)
    {
        cmds.add(new RedisCommand(CMD_LPOP, s1));
    }

    public void publish(String server, String msg) {
        cmds.add(new RedisCommand(CMD_PUBLISH, server, msg));
    }

    class RedisCommand
    {
        int cmd;
        Object s1;
        Object s2;
        Object s3;

        public RedisCommand(int cmd, Object s1, Object s2, Object s3)
        {
            this.cmd = cmd;
            this.s1 = s1;
            this.s2 = s2;
            this.s3 = s3;
        }

        public RedisCommand(int cmd, Object s1)
        {
            this.cmd = cmd;
            this.s1 = s1;
        }

        public RedisCommand(int cmd, Object s1, Object s2)
        {
            this.cmd = cmd;
            this.s1 = s1;
            this.s2 = s2;
        }
    }

    public void ltrim(String s1, long s2, long s3)
    {
        cmds.add(new RedisCommand(CMD_LTRIM, s1, s2, s3));
    }

    public void hdel(String s1, String[] s2)
    {
        cmds.add(new RedisCommand(CMD_HDEL, s1, s2));
    }
}
