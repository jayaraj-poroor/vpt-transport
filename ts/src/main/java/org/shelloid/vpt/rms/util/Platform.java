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

import static org.shelloid.vpt.rms.util.Configurations.get;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.dbcp.*;
import org.slf4j.*;
import redis.clients.jedis.*;

/* @author Harikrishnan */
public class Platform {

    public HashMap<String, Integer> streamPublishList;
    public static final Logger shelloidLogger = LoggerFactory.getLogger("org.shelloid.vpt");
    private static Platform platform;
    private final ExecutorService threadPool;
    private final BasicDataSource dbConnPool;
    private final JedisPool redisPool;
    private int activeRedisConnections;
    

    private Platform() {
        redisPool = configRedisPool();
        dbConnPool = configDbPool();
        threadPool = Executors.newCachedThreadPool();
        streamPublishList = new HashMap<>();
    }

    public static Platform getInstance() {
        if (platform == null) {
            platform = new Platform();
        }
        return platform;
    }

    public void executeInThread(Runnable task) {
        threadPool.execute(task);
    }

    private BasicDataSource configDbPool() {
        BasicDataSource ds = new BasicDataSource();
        ds.setTestOnBorrow(true);
        ds.setValidationQuery("SELECT 1");
        ds.setDriverClassName(get(Configurations.ConfigParams.JDBC_DRIVER));
        ds.setUrl(get(Configurations.ConfigParams.JDBC_URL));
        ds.setUsername(get(Configurations.ConfigParams.JDBC_USERNAME));
        ds.setPassword(get(Configurations.ConfigParams.JDBC_PASSWORD));
        ds.setMaxActive(Integer.parseInt(get(Configurations.ConfigParams.JDBC_MAX_ACTIVE)));
        ds.setMaxIdle(Integer.parseInt(get(Configurations.ConfigParams.JDBC_MIN_IDLE)));
        return ds;
    }

    private JedisPool configRedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(Configurations.MAX_REDIS_CONNECTIONS);
        poolConfig.setMinIdle(2);
        activeRedisConnections = 0;
        return new JedisPool(poolConfig, get(Configurations.ConfigParams.REDIS_HOST),
                Integer.parseInt(Configurations.get(Configurations.ConfigParams.REDIS_PORT)),
                Integer.parseInt(Configurations.get(Configurations.ConfigParams.REDIS_TIMEOUT)),
                get(Configurations.ConfigParams.REDIS_PASSWORD));
    }
    
    public Jedis getRedisConnection(){
        if (activeRedisConnections >= (Configurations.MAX_REDIS_CONNECTIONS*95)/100){
            Platform.shelloidLogger.warn("Redis connection pool reached more than 95% capacity: " + activeRedisConnections);
        }
        activeRedisConnections++;
        return redisPool.getResource();
    }
    
    public void returnJedis(Jedis jedis){
        redisPool.returnResource(jedis);
        activeRedisConnections--;
    } 
    
    public java.sql.Connection getDbConnection() throws SQLException{
        if (dbConnPool.getNumActive() >= (dbConnPool.getMaxActive()*95)/100 ){
            Platform.shelloidLogger.warn("DB connection pool reached more than 95% capacity: " + dbConnPool.getNumActive());
        }
        return dbConnPool.getConnection();
    }
}
