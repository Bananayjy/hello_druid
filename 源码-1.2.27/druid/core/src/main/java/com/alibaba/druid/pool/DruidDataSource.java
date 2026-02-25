/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.pool;

import com.alibaba.druid.DbType;
import com.alibaba.druid.DruidRuntimeException;
import com.alibaba.druid.TransactionTimeoutException;
import com.alibaba.druid.VERSION;
import com.alibaba.druid.filter.AutoLoad;
import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.FilterChainImpl;
import com.alibaba.druid.mock.MockDriver;
import com.alibaba.druid.pool.DruidPooledPreparedStatement.PreparedStatementKey;
import com.alibaba.druid.pool.vendor.*;
import com.alibaba.druid.proxy.DruidDriver;
import com.alibaba.druid.proxy.jdbc.DataSourceProxyConfig;
import com.alibaba.druid.proxy.jdbc.TransactionInfo;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.stat.DruidDataSourceStatManager;
import com.alibaba.druid.stat.JdbcDataSourceStat;
import com.alibaba.druid.stat.JdbcSqlStat;
import com.alibaba.druid.stat.JdbcSqlStatValue;
import com.alibaba.druid.support.clickhouse.BalancedClickhouseDriver;
import com.alibaba.druid.support.clickhouse.BalancedClickhouseDriverNative;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.*;
import com.alibaba.druid.wall.WallFilter;
import com.alibaba.druid.wall.WallProviderStatValue;

import javax.management.JMException;
import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;

import java.io.Closeable;
import java.net.Socket;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author ljw [ljw2083@alibaba-inc.com]
 * @author wenshao [szujobs@hotmail.com]
 */
public class DruidDataSource extends DruidAbstractDataSource
        implements DruidDataSourceMBean, ManagedDataSource, Referenceable, Closeable, Cloneable, ConnectionPoolDataSource, MBeanRegistration {
    private static final Log LOG = LogFactory.getLog(DruidDataSource.class);
    private static final long serialVersionUID = 1L;
    // stats
    private volatile long recycleErrorCount;
    private volatile long discardErrorCount;
    private volatile Throwable discardErrorLast;
    private long connectCount;
    private long closeCount;
    private volatile long connectErrorCount;
    private long recycleCount;
    private long removeAbandonedCount;
    private long notEmptyWaitCount;
    private long notEmptySignalCount;
    private long notEmptyWaitNanos;
    private int keepAliveCheckCount;
    // 历史最大借出数：activeCount 从启动以来出现过的最大值，即“曾经同时被借出的连接数”的峰值
    private int activePeak;
    private long activePeakTime;
    private int poolingPeak;
    private long poolingPeakTime;
    private volatile int keepAliveCheckErrorCount;
    private volatile Throwable keepAliveCheckErrorLast;
    // store
    private volatile DruidConnectionHolder[] connections;
    // 当前在空闲池（connections 数组）里的连接数量，即“池中空闲连接数”
    private int poolingCount;
    // 当前已经被应用“借走”、正在使用的连接数量（不在池子里）
    private int activeCount;
    private volatile int createDirectCount;
    private volatile long discardCount;
    private int notEmptyWaitThreadCount;
    private int notEmptyWaitThreadPeak;
    //
    private DruidConnectionHolder[] evictConnections;
    private DruidConnectionHolder[] keepAliveConnections;
    // for clean connection old references.
    private volatile DruidConnectionHolder[] nullConnections;

    // threads
    private volatile ScheduledFuture<?> destroySchedulerFuture;
    private DestroyTask destroyTask;

    private final Map<CreateConnectionTask, Future<?>> createSchedulerFutures = new ConcurrentHashMap<>(16);
    private CreateConnectionThread createConnectionThread;
    private DestroyConnectionThread destroyConnectionThread;
    private LogStatsThread logStatsThread;
    // 已提交、尚未完成的建连任务数
    private int createTaskCount;

    private volatile long createTaskIdSeed = 1L;
    private long[] createTasks;

    private volatile boolean enable = true;

    private boolean resetStatEnable = true;
    private volatile long resetCount;

    private String initStackTrace;

    private volatile boolean closing;
    private volatile boolean closed;
    private long closeTimeMillis = -1L;

    protected JdbcDataSourceStat dataSourceStat;

    private boolean useGlobalDataSourceStat;
    private boolean mbeanRegistered;
    private boolean logDifferentThread = true;
    private volatile boolean keepAlive;
    private boolean asyncInit;
    protected boolean killWhenSocketReadTimeout;
    protected boolean checkExecuteTime;

    private static List<Filter> autoFilters;
    private boolean loadSpifilterSkip;
    private volatile DataSourceDisableException disableException;

    protected static final AtomicLongFieldUpdater<DruidDataSource> recycleErrorCountUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "recycleErrorCount");
    protected static final AtomicLongFieldUpdater<DruidDataSource> connectErrorCountUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "connectErrorCount");
    protected static final AtomicLongFieldUpdater<DruidDataSource> resetCountUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "resetCount");
    protected static final AtomicLongFieldUpdater<DruidDataSource> createTaskIdSeedUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "createTaskIdSeed");
    protected static final AtomicLongFieldUpdater<DruidDataSource> discardErrorCountUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "discardErrorCount");
    protected static final AtomicIntegerFieldUpdater<DruidDataSource> keepAliveCheckErrorCountUpdater
            = AtomicIntegerFieldUpdater.newUpdater(DruidDataSource.class, "keepAliveCheckErrorCount");
    protected static final AtomicIntegerFieldUpdater<DruidDataSource> createDirectCountUpdater
            = AtomicIntegerFieldUpdater.newUpdater(DruidDataSource.class, "createDirectCount");

    public DruidDataSource() {
        this(false);
    }

    public DruidDataSource(boolean fairLock) {
        super(fairLock);

        configFromPropeties(System.getProperties());
    }

    public boolean isAsyncInit() {
        return asyncInit;
    }

    public void setAsyncInit(boolean asyncInit) {
        this.asyncInit = asyncInit;
    }

    @Deprecated
    public void configFromPropety(Properties properties) {
        configFromPropeties(properties);
    }

    @Deprecated
    public void configFromPropeties(Properties properties) {
        configFromProperties(properties);
    }

    /**
     * support config after init
     */
    public void configFromProperties(Properties properties) {
        boolean init;
        lock.lock();
        try {
            init = this.inited;
        } finally {
            lock.unlock();
        }
        if (init) {
            configFromPropertiesAfterInit(properties);
        } else {
            DruidDataSourceUtils.configFromProperties(this, properties);
        }
    }

    private void configFromPropertiesAfterInit(Properties properties) {
        String url = properties.getProperty("druid.url");
        if (url != null) {
            url = url.trim();
        }

        String username = properties.getProperty("druid.username");
        if (username != null) {
            username = username.trim();
        }

        String password = properties.getProperty("druid.password");

        Properties connectProperties = new Properties();

        final String connectUrl;
        final boolean urlUserPasswordChanged;
        lock.lock();
        try {
            urlUserPasswordChanged = (url != null && !this.jdbcUrl.equals(url))
                    || (username != null && !username.equals(this.username))
                    || (password != null && !password.equals(this.password));

            String connectUser = username != null ? username : this.username;
            if (username != null) {
                connectProperties.put("user", connectUser);
            }

            String connectPassword = password != null ? password : this.password;
            if (connectPassword != null) {
                connectProperties.put("password", connectPassword);
            }
            connectUrl = url != null ? url : this.jdbcUrl;
        } finally {
            lock.unlock();
        }

        if (urlUserPasswordChanged) {
            Connection conn = null;
            try {
                conn = getDriver().connect(connectUrl, connectProperties);
                LOG.info("check connection info success");
                // ignore
            } catch (SQLException e) {
                throw new DruidRuntimeException("check connection info failed", e);
            } finally {
                JdbcUtils.close(conn);
            }
        }

        lock.lock();
        try {
            if (urlUserPasswordChanged) {
                if (url != null && !url.equals(this.jdbcUrl)) {
                    this.jdbcUrl = url; // direct set url, ignore init check
                    LOG.info("jdbcUrl changed");
                }

                if (username != null && !username.equals(this.username)) {
                    this.username = username; // direct set, ignore init check
                    LOG.info("username changed");
                }

                if (password != null && !password.equals(this.password)) {
                    this.password = password; // direct set, ignore init check
                    LOG.info("password changed");
                }
                incrementUserPasswordVersion();
            }

            {
                Integer initialSize = DruidDataSourceUtils.getPropertyInt(properties, "druid.initialSize");
                if (initialSize != null) {
                    this.initialSize = initialSize;
                }
            }

            Integer maxActive = DruidDataSourceUtils.getPropertyInt(properties, "druid.maxActive");
            Integer minIdle = DruidDataSourceUtils.getPropertyInt(properties, "druid.minIdle");
            if (maxActive != null || minIdle != null) {
                int compareMaxActive = maxActive != null ? maxActive.intValue() : this.maxActive;
                int compareMinIdle = minIdle != null ? minIdle.intValue() : this.minIdle;
                if (compareMaxActive < compareMinIdle) {
                    throw new IllegalArgumentException("maxActive less than minIdle, " + compareMaxActive + " < " + compareMinIdle);
                }
            }
            if (maxActive != null) {
                this.maxActive = maxActive;
            }
            if (minIdle != null) {
                this.minIdle = minIdle;
            }

            DruidDataSourceUtils.configFromProperties(this, properties);
        } finally {
            lock.unlock();
        }

        int replaceCount = 0;
        // replace older version urlUserPassword Connection
        while ((hasOlderVersionUrlUserPasswordConnection())) {
            try {
                PhysicalConnectionInfo phyConnInfo = createPhysicalConnection();

                boolean result = false;
                lock.lock();
                try {
                    for (int i = poolingCount - 1; i >= 0; i--) {
                        if (connections[i].getUserPasswordVersion() < userPasswordVersion) {
                            connections[i] = new DruidConnectionHolder(DruidDataSource.this, phyConnInfo);
                            result = true;
                            replaceCount++;
                            break;
                        }
                    }
                } finally {
                    lock.unlock();
                }

                if (!result) {
                    JdbcUtils.close(phyConnInfo.getPhysicalConnection());
                    LOG.info("replace older version urlUserPassword failed.");
                    break;
                }
            } catch (SQLException e) {
                LOG.error("fill init connection error", e);
            }
        }

        if (replaceCount > 0) {
            LOG.info("replace older version urlUserPassword Connection : " + replaceCount);
        }

        while ((isLowWaterLevel())) {
            try {
                PhysicalConnectionInfo physicalConnection = createPhysicalConnection();

                boolean result = put(physicalConnection);
                if (!result) {
                    JdbcUtils.close(physicalConnection.getPhysicalConnection());
                    LOG.info("put physical connection to pool failed.");
                }
            } catch (SQLException e) {
                LOG.error("fill init connection error", e);
            }
        }
    }

    private boolean hasOlderVersionUrlUserPasswordConnection() {
        lock.lock();
        try {
            long userPasswordVersion = this.userPasswordVersion;
            for (int i = 0; i < poolingCount; i++) {
                if (connections[i].getUserPasswordVersion() < userPasswordVersion) {
                    return true;
                }
            }
        } finally {
            lock.unlock();
        }
        return false;
    }

    private boolean isLowWaterLevel() {
        lock.lock();
        try {
            return activeCount + poolingCount < minIdle;
        } finally {
            lock.unlock();
        }
    }

    public boolean isKillWhenSocketReadTimeout() {
        return killWhenSocketReadTimeout;
    }

    public void setKillWhenSocketReadTimeout(boolean killWhenSocketTimeOut) {
        this.killWhenSocketReadTimeout = killWhenSocketTimeOut;
    }

    public boolean isUseGlobalDataSourceStat() {
        return useGlobalDataSourceStat;
    }

    public void setUseGlobalDataSourceStat(boolean useGlobalDataSourceStat) {
        this.useGlobalDataSourceStat = useGlobalDataSourceStat;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public String getInitStackTrace() {
        return initStackTrace;
    }

    public boolean isResetStatEnable() {
        return resetStatEnable;
    }

    public void setResetStatEnable(boolean resetStatEnable) {
        this.resetStatEnable = resetStatEnable;
        if (dataSourceStat != null) {
            dataSourceStat.setResetStatEnable(resetStatEnable);
        }
    }

    public long getDiscardCount() {
        return discardCount;
    }

    public void restart() throws SQLException {
        this.restart(null);
    }

    public void restart(Properties properties) throws SQLException {
        lock.lock();
        try {
            if (activeCount > 0) {
                throw new SQLException("can not restart, activeCount not zero. " + activeCount);
            }
            if (LOG.isInfoEnabled()) {
                LOG.info("{dataSource-" + this.getID() + "} restart");
            }

            this.close();
            this.resetStat();
            this.inited = false;
            this.enable = true;
            this.closed = false;

            if (properties != null) {
                DruidDataSourceUtils.configFromProperties(this, properties);
            }
        } finally {
            lock.unlock();
        }
    }

    public void resetStat() {
        if (!isResetStatEnable()) {
            return;
        }

        lock.lock();
        try {
            connectCount = 0;
            closeCount = 0;
            discardCount = 0;
            recycleCount = 0;
            createCount = 0L;
            directCreateCount = 0;
            destroyCount = 0L;
            removeAbandonedCount = 0;
            notEmptyWaitCount = 0;
            notEmptySignalCount = 0L;
            notEmptyWaitNanos = 0;

            activePeak = activeCount;
            activePeakTime = 0;
            poolingPeak = 0;
            createTimespan = 0;
            lastError = null;
            lastErrorTimeMillis = 0;
            lastCreateError = null;
            lastCreateErrorTimeMillis = 0;
        } finally {
            lock.unlock();
        }

        connectErrorCountUpdater.set(this, 0);
        errorCountUpdater.set(this, 0);
        commitCountUpdater.set(this, 0);
        rollbackCountUpdater.set(this, 0);
        startTransactionCountUpdater.set(this, 0);
        cachedPreparedStatementHitCountUpdater.set(this, 0);
        closedPreparedStatementCountUpdater.set(this, 0);
        preparedStatementCountUpdater.set(this, 0);
        transactionHistogram.reset();
        cachedPreparedStatementDeleteCountUpdater.set(this, 0);
        recycleErrorCountUpdater.set(this, 0);

        resetCountUpdater.incrementAndGet(this);
    }

    public long getResetCount() {
        return this.resetCount;
    }

    public boolean isEnable() {
        return enable;
    }

    public void setEnable(boolean enable) {
        lock.lock();
        try {
            this.enable = enable;
            if (!enable) {
                notEmpty.signalAll();
                notEmptySignalCount++;
            }
        } finally {
            lock.unlock();
        }
    }

    public void setPoolPreparedStatements(boolean value) {
        setPoolPreparedStatements0(value);
    }

    private void setPoolPreparedStatements0(boolean value) {
        if (this.poolPreparedStatements == value) {
            return;
        }

        this.poolPreparedStatements = value;

        if (!inited) {
            return;
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("set poolPreparedStatements " + this.poolPreparedStatements + " -> " + value);
        }

        if (!value) {
            lock.lock();
            try {
                for (int i = 0; i < poolingCount; ++i) {
                    DruidConnectionHolder connection = connections[i];

                    for (PreparedStatementHolder holder : connection.getStatementPool().getMap().values()) {
                        closePreapredStatement(holder);
                    }

                    connection.getStatementPool().getMap().clear();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public void setMaxActive(int maxActive) {
        if (this.maxActive == maxActive) {
            return;
        }

        if (maxActive == 0) {
            throw new IllegalArgumentException("maxActive can't not set zero");
        }

        if (!inited) {
            this.maxActive = maxActive;
            return;
        }

        if (maxActive < this.minIdle) {
            throw new IllegalArgumentException("maxActive less than minIdle, " + maxActive + " < " + this.minIdle);
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("maxActive changed : " + this.maxActive + " -> " + maxActive);
        }

        lock.lock();
        try {
            int allCount = this.poolingCount + this.activeCount;

            if (maxActive > allCount) {
                this.connections = Arrays.copyOf(this.connections, maxActive);
                evictConnections = new DruidConnectionHolder[maxActive];
                keepAliveConnections = new DruidConnectionHolder[maxActive];
                nullConnections = new DruidConnectionHolder[maxActive];
            } else {
                this.connections = Arrays.copyOf(this.connections, allCount);
                evictConnections = new DruidConnectionHolder[allCount];
                keepAliveConnections = new DruidConnectionHolder[allCount];
                nullConnections = new DruidConnectionHolder[allCount];
            }

            this.maxActive = maxActive;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("rawtypes")
    public void setConnectProperties(Properties properties) {
        if (properties == null) {
            properties = new Properties();
        }

        boolean equals;
        if (properties.size() == this.connectProperties.size()) {
            equals = true;
            for (Map.Entry entry : properties.entrySet()) {
                if (
                    !Objects.equals(
                        this.connectProperties.get(entry.getKey()),
                        entry.getValue()
                    )
                ) {
                    equals = false;
                    break;
                }
            }
        } else {
            equals = false;
        }

        if (!equals) {
            if (inited && LOG.isInfoEnabled()) {
                LOG.info("connectProperties changed : " + this.connectProperties + " -> " + properties);
            }

            configFromPropeties(properties);

            for (Filter filter : this.filters) {
                filter.configFromProperties(properties);
            }

            if (exceptionSorter != null) {
                exceptionSorter.configFromProperties(properties);
            }

            if (validConnectionChecker != null) {
                validConnectionChecker.configFromProperties(properties);
            }

            if (statLogger != null) {
                statLogger.configFromProperties(properties);
            }
        }

        this.connectProperties = properties;
    }

    /**
     * 在首次使用时对数据源初始化
     * @throws SQLException
     */
    public void init() throws SQLException {
        // inited标记是否初始化过，若已经初始化过，直接返回，避免重复执行
        // inited 在方法最后的 finally 里会被置为 true，所以第一次之后调用 init() 都会从这里退出
        if (inited) {
            return;
        }

        // 预加载 DruidDriver（单例）
        // 提前触发 DruidDriver 的类加载与初始化
        // 若在持锁状态下才第一次加载 DruidDriver，可能和其静态初始化里的锁产生死锁，所以在这里先执行一次，避免在后面的 lock.lockInterruptibly() 之后再去加载
        // bug fixed for dead lock, for issue #2980
        DruidDriver.getInstance();

        // 通过锁保证同一时刻只有一个线程在执行 init 逻辑，避免多线程并发 init
        final ReentrantLock lock = this.lock;
        try {
            // 可被中断的加锁，若等待过程中线程被中断，会抛 InterruptedException，这里转成 SQLException 抛出
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            throw new SQLException("interrupt", e);
        }

        boolean init = false;
        try {
            // 拿到锁后再看一次，防止两个线程都通过最外层的 if (inited) 后，只有一个能完成 init，另一个直接返回
            if (inited) {
                return;
            }

            // 记录当前线程的调用栈，用于监控/排查“是谁触发了 init”（例如 DataSourceMonitorable.getInitStackTrace()）
            initStackTrace = Utils.toString(Thread.currentThread().getStackTrace());


            /**
             * 全局唯一的数据源 ID通过调用DruidDriver的createDataSourceId方法分配
             * 从全局自增计数器里为当前数据源分配一个唯一 id（第 1 个数据源=1，第 2 个=2，…），用于标识不同的数据源实例
             */
            this.id = DruidDriver.createDataSourceId();
            /**
             * 对 connection/statement/resultSet/transaction 的 ID 种子做偏移（(id-1)*100000），这样多数据源时各数据源产生的 ID 区间错开，便于区分和排查
             *
             * 1.作用：
             * 快速定位来源：通过 ID 范围即可判断来自哪个数据源
             * 避免 ID 冲突：不同数据源产生的 ID 不会重叠
             * 便于日志分析：日志中看到 ID 即可知道是哪个数据源
             * 监控友好：监控系统可按 ID 范围区分不同数据源
             * 排查效率：问题排查时能快速定位到具体数据源
             *
             * 2.为什么选择 100000 作为间隔：
             * 足够大：为每个数据源预留足够空间（10 万）
             * 计算简单：(id-1) * 100000 易于理解和维护
             * 实用：单个数据源很难在短时间内产生超过 10 万个对象
             *
             * 3.各数据源的 ID 区间分布示例：
             * 数据源 1 (id=1)：不偏移
             * Connection: 10000, 10001, 10002...
             * Statement: 20000, 20001, 20002...
             * ResultSet: 50000, 50001, 50002...
             * Transaction: 60000, 60001, 60002...
             * 数据源 2 (id=2)：偏移 100000
             * Connection: 110000, 110001, 110002...
             * Statement: 120000, 120001, 120002...
             * ResultSet: 150000, 150001, 150002...
             * Transaction: 160000, 160001, 160002...
             * 数据源 3 (id=3)：偏移 200000
             * Connection: 210000, 210001, 210002...
             * Statement: 220000, 220001, 220002...
             * ResultSet: 250000, 250001, 250002...
             * Transaction: 260000, 260001, 260002...
             */
            if (this.id > 1) {
                // 偏移量计算
                long delta = (this.id - 1) * 100000;
                connectionIdSeedUpdater.addAndGet(this, delta);
                statementIdSeedUpdater.addAndGet(this, delta);
                resultSetIdSeedUpdater.addAndGet(this, delta);
                transactionIdSeedUpdater.addAndGet(this, delta);
            }

            // jdbcUrl 处理与超时参数设置
            if (this.jdbcUrl != null) {
                // 去掉首尾空格
                this.jdbcUrl = this.jdbcUrl.trim();
                // 若 jdbcURL 是 Druid 包装驱动格式（如带 druid: 或特定前缀），则解析并可能替换为真实 URL、加载真实驱动等
                initFromWrapDriverUrl();
            }
            // 从 jdbcUrl 的 query 参数或 connectProperties 里解析 connectTimeout、socketTimeout，并调用对应的 setter，保证建连/读超时被正确设置
            initTimeoutsFromUrlOrProperties();

            // Filter 初始化
            for (Filter filter : filters) {
                // 对已加入的每个 Filter 调用 filter.init(this)，把当前 DataSource 传给 Filter，让 Filter 做自己的初始化（如加载配置、注册统计等）
                filter.init(this);
            }

            // 若未设置 dbTypeName，则根据 jdbcUrl 推断数据库类型
            // 后续建连、超时、校验等都会按库类型区分处理
            if (this.dbTypeName == null || this.dbTypeName.length() == 0) {
                this.dbTypeName = JdbcUtils.getDbType(jdbcUrl, null);
            }

            /**
             * MySQL 驱动使用服务端配置缓存
             * cacheServerConfiguration 的作用：
             * 缓存 MySQL 服务端配置信息（如 SHOW VARIABLES、SHOW COLLATION 的结果）
             * 避免每次建立连接时重复查询，提升连接初始化性能
             * 在连接池场景下，效果更明显
             */
            DbType dbType = DbType.of(this.dbTypeName);
            if (JdbcUtils.isMysqlDbType(dbType)) {  // 仅对 MySQL 系列数据库（MySQL、MariaDB、OceanBase、ADS 等）生效
                boolean cacheServerConfigurationSet = false;
                if (this.connectProperties.containsKey("cacheServerConfiguration")) {   // connectProperties 中是否包含该参数
                    cacheServerConfigurationSet = true;
                } else if (this.jdbcUrl.indexOf("cacheServerConfiguration") != -1) {    // JDBC URL 中是否包含该参数（如 jdbc:mysql://localhost:3306/db?cacheServerConfiguration=true）
                    cacheServerConfigurationSet = true;
                }
                // 显式设置参数值
                if (cacheServerConfigurationSet) {
                    this.connectProperties.put("cacheServerConfiguration", "true");
                }
            }

            //  池参数校验
            if (maxActive <= 0) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }
            if (maxActive < minIdle) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }
            if (getInitialSize() > maxActive) {
                throw new IllegalArgumentException("illegal initialSize " + this.initialSize + ", maxActive " + maxActive);
            }
            if (timeBetweenLogStatsMillis > 0 && useGlobalDataSourceStat) {
                throw new IllegalArgumentException("timeBetweenLogStatsMillis not support useGlobalDataSourceStat=true");
            }
            if (maxEvictableIdleTimeMillis < minEvictableIdleTimeMillis) {
                throw new SQLException("maxEvictableIdleTimeMillis must be grater than minEvictableIdleTimeMillis");
            }
            if (keepAlive && keepAliveBetweenTimeMillis <= timeBetweenEvictionRunsMillis) {
                throw new SQLException("keepAliveBetweenTimeMillis must be greater than timeBetweenEvictionRunsMillis");
            }

            // 驱动加载
            // driverClass 驱动非空，去除首尾空格。
            if (this.driverClass != null) {
                this.driverClass = driverClass.trim();
            }
            // 通过 Java SPI 扫描并加载在 META-INF/services 里声明的 Filter 等扩展，并可能加入 filters
            initFromSPIServiceLoader();
            // 根据 driverClass 或 jdbcUrl 解析并加载 JDBC Driver，赋值给父类的 driver 字段，后续通过 createPhysicalConnection() 方法创建连接
            resolveDriver();

            // 一致性检查：对数据库类型、驱动版本、URL 与驱动匹配等进行校验
            initCheck();

            // 网络超时执行器，同步执行器（com.alibaba.druid.pool.DruidAbstractDataSource内部类）
            // 专门给 Connection.setNetworkTimeout(Executor, int) 用的 Executor，在 DruidAbstractDataSource.createPhysicalConnection() 里使用
            // JDBC 4.1 规定设置网络超时时要传一个 Executor，用于驱动在发生网络超时或执行超时回调时用的，对于Druid 不需要异步回调，所以用“同步执行器”即可
            this.netTimeoutExecutor = new SynchronousExecutor();

            // 异常分类器初始化
            // 功能：根据数据库驱动类型，自动选择合适的异常分类器，用于判断 SQL 异常是否致命（连接是否应丢弃）。
            initExceptionSorter();
            // 连接校验器初始化
            // 功能：根据当前 Driver 的类名，在未显式配置的情况下，为数据源选一个 ValidConnectionChecker，用于 testOnBorrow、testOnReturn、testWhileIdle 等场景下判断连接是否仍然可用（可替代或配合 validationQuery）
            initValidConnectionChecker();
            // 校验查询检查
            // 在开启了连接校验开关（testOnBorrow / testOnReturn / testWhileIdle）的前提下，检查是否具备至少一种可用的校验手段（ValidConnectionChecker 或 validationQuery）；若没有，则打 ERROR 日志，避免“开了校验却无法真正校验”的无效配置
            validationQueryCheck();

            // 统计对象 JdbcDataSourceStat
            if (isUseGlobalDataSourceStat()) {  // 使用全局单例的 JdbcDataSourceStat，多数据源共享同一套统计
                dataSourceStat = JdbcDataSourceStat.getGlobal();
                // 若尚未创建则创建并设为 Global
                if (dataSourceStat == null) {
                    dataSourceStat = new JdbcDataSourceStat("Global", "Global", this.dbTypeName);
                    JdbcDataSourceStat.setGlobal(dataSourceStat);
                }
                if (dataSourceStat.getDbType() == null) {
                    dataSourceStat.setDbType(this.dbTypeName);
                }
            } else {    // 为当前数据源 new 一个 JdbcDataSourceStat（name、jdbcUrl、dbTypeName、connectProperties）
                dataSourceStat = new JdbcDataSourceStat(this.name, this.jdbcUrl, this.dbTypeName, this.connectProperties);
            }
            // 是否允许监控页/API 重置统计，与配置的 resetStatEnable 一致
            dataSourceStat.setResetStatEnable(this.resetStatEnable);

            // 池结构分配
            connections = new DruidConnectionHolder[maxActive];
            evictConnections = new DruidConnectionHolder[maxActive];
            keepAliveConnections = new DruidConnectionHolder[maxActive];
            nullConnections = new DruidConnectionHolder[maxActive];

            SQLException connectError = null;

            // 预建连接（连接池初始化）
            if (createScheduler != null && asyncInit) { // 异步初始化
                // asyncInit 且已有 createScheduler：不阻塞，通过 submitCreateTask(true) 提交 initialSize 个建连任务到线程池，由后台线程异步建连并放入池。
                for (int i = 0; i < initialSize; ++i) {
                    submitCreateTask(true);
                }
            } else if (!asyncInit) {    // 同步初始化
                // init connections
                // 循环直到 poolingCount == initialSize
                while (poolingCount < initialSize) {
                    try {
                        // 每次 createPhysicalConnection() 得到 PhysicalConnectionInfo
                        PhysicalConnectionInfo pyConnectInfo = createPhysicalConnection();
                        // 再 new DruidConnectionHolder
                        DruidConnectionHolder holder = new DruidConnectionHolder(this, pyConnectInfo);
                        // 放入 connections并 poolingCount++
                        connections[poolingCount++] = holder;
                    } catch (SQLException ex) { // 若某次建连抛 SQLException
                        LOG.error("init datasource error, url: " + this.getUrl(), ex);
                        if (initExceptionThrow) {   // initExceptionThrow = true：记录 connectError 并 break，后面若池为空会再抛
                            connectError = ex;
                            break;
                        } else {    // initExceptionThrow = false：只打日志，sleep 3 秒后继续尝试（直到达到 initialSize 或一直失败）
                            Thread.sleep(3000);
                        }
                    }
                }
                // 若 poolingCount > 0：更新 poolingPeak、poolingPeakTime，表示初始阶段空闲连接峰值
                if (poolingCount > 0) {
                    poolingPeak = poolingCount;
                    poolingPeakTime = System.currentTimeMillis();
                }
            }

            //  创建并启动三个线程
            // 1.若 timeBetweenLogStatsMillis > 0，则启动 LogStatsThread，按间隔打印池统计日志
            createAndLogThread();
            // 2.创建并启动 CreateConnectionThread，负责在池不满时调用 emptySignal() 等逻辑去异步建连，填满到 minIdle/maxActive
            createAndStartCreatorThread();
            // 3.创建并启动 DestroyConnectionThread，负责空闲检测、回收超时连接、保活等
            createAndStartDestroyThread();

            // 等待连接 Create/Destroy 线程就绪
            // await threads initedLatch to support dataSource restart.
            if (createConnectionThread != null) {
                createConnectionThread.getInitedLatch().await();
            }
            if (destroyConnectionThread != null) {
                destroyConnectionThread.getInitedLatch().await();
            }

            // 标记成功，表示本次 try 块内逻辑成功，finally 里会据此决定是否打 "inited" 日志
            init = true;

            // 记录初始化完成时间
            initedTime = new Date();

            /**
             * 注册Mbean
             * 作用：把当前 DruidDataSource 实例注册到 JVM 的 JMX（Java Management Extensions）中，使该数据源可以通过 JMX 被监控、查询统计、执行管理操作（如 shrink、resetStat 等）
             * 注册完成后会记录对应的 ObjectName 并设置 mbeanRegistered = true，与 close() 时的 unregisterMbean() 成对，保证生命周期正确、不泄漏。
             */
            registerMbean();

            // 同步初始化失败时抛错（池为空）
            if (connectError != null && poolingCount == 0) {
                throw connectError;
            }

            // keepAlive = true 时希望尽快达到 minIdle
            if (keepAlive) {
                if (createScheduler != null) {  // 若有 createScheduler：再提交 minIdle - initialSize 个建连任务（若 initialSize 已 >= minIdle 则循环 0 次）
                    // async fill to minIdle
                    for (int i = 0; i < minIdle - initialSize; ++i) {
                        submitCreateTask(true);
                    }
                } else {    // 只发一次 empty.signal()，唤醒 Create 线程，由它按需建连到 minIdle
                    empty.signal();
                }
            }

        } catch (SQLException e) { // 打日志并原样抛出。
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;
        } catch (InterruptedException e) {  // 包装成 SQLException 抛出（例如 await 被中断）
            throw new SQLException(e.getMessage(), e);
        } catch (RuntimeException e) {  // 打日志后继续抛出，保证 init 失败时调用方能感知
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;
        } catch (Error e) {
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;

        } finally {
            // 标记已初始化
            inited = true;

            // 解锁
            lock.unlock();

            // 打日志
            if (init && LOG.isInfoEnabled()) {
                String msg = "{dataSource-" + this.getID();

                if (this.name != null && !this.name.isEmpty()) {
                    msg += ",";
                    msg += this.name;
                }

                msg += "} inited";

                LOG.info(msg);
            }
        }
    }

    private void initTimeoutsFromUrlOrProperties() {
        // createPhysicalConnection will set the corresponding parameters based on dbType.
        if (jdbcUrl != null && (jdbcUrl.indexOf("connectTimeout=") != -1 || jdbcUrl.indexOf("socketTimeout=") != -1)) {
            String[] items = jdbcUrl.split("(\\?|&)");
            for (int i = 0; i < items.length; i++) {
                String item = items[i];
                if (item.startsWith("connectTimeout=")) {
                    String strVal = item.substring("connectTimeout=".length());
                    setConnectTimeout(strVal);
                } else if (item.startsWith("socketTimeout=")) {
                    String strVal = item.substring("socketTimeout=".length());
                    setSocketTimeout(strVal);
                }
            }
        }

        Object propertyConnectTimeout = connectProperties.get("connectTimeout");
        if (propertyConnectTimeout instanceof String) {
            setConnectTimeout((String) propertyConnectTimeout);
        } else if (propertyConnectTimeout instanceof Number) {
            setConnectTimeout(((Number) propertyConnectTimeout).intValue());
        }

        Object propertySocketTimeout = connectProperties.get("socketTimeout");
        if (propertySocketTimeout instanceof String) {
            setSocketTimeout((String) propertySocketTimeout);
        } else if (propertySocketTimeout instanceof Number) {
            setSocketTimeout(((Number) propertySocketTimeout).intValue());
        }
    }

    /**
     * Issue 5192,Issue 5457
     * @see <a href="https://dev.mysql.com/doc/connector-j/8.1/en/connector-j-reference-jdbc-url-format.html">MySQL Connection URL Syntax</a>
     * @see <a href="https://mariadb.com/kb/en/about-mariadb-connector-j/">About MariaDB Connector/J</a>
     * @param jdbcUrl
     * @return
     */
    private static boolean isMysqlOrMariaDBUrl(String jdbcUrl) {
        return jdbcUrl.startsWith("jdbc:mysql://") || jdbcUrl.startsWith("jdbc:mysql:loadbalance://")
            || jdbcUrl.startsWith("jdbc:mysql:replication://") || jdbcUrl.startsWith("jdbc:mariadb://")
            || jdbcUrl.startsWith("jdbc:mariadb:loadbalance://") || jdbcUrl.startsWith("jdbc:mariadb:replication://");
    }

    private void submitCreateTask(boolean initTask) {
        createTaskCount++;
        CreateConnectionTask task = new CreateConnectionTask(initTask);
        if (createTasks == null) {
            createTasks = new long[8];
        }

        boolean putted = false;
        for (int i = 0; i < createTasks.length; ++i) {
            if (createTasks[i] == 0) {
                createTasks[i] = task.taskId;
                putted = true;
                break;
            }
        }
        if (!putted) {
            long[] array = new long[createTasks.length * 3 / 2];
            System.arraycopy(createTasks, 0, array, 0, createTasks.length);
            array[createTasks.length] = task.taskId;
            createTasks = array;
        }

        this.createSchedulerFutures.put(task, createScheduler.submit(task));
    }

    private boolean clearCreateTask(long taskId) {
        if (createTasks == null) {
            return false;
        }

        if (taskId == 0) {
            return false;
        }

        for (int i = 0; i < createTasks.length; i++) {
            if (createTasks[i] == taskId) {
                createTasks[i] = 0;
                createTaskCount--;

                if (createTaskCount < 0) {
                    createTaskCount = 0;
                }

                if (createTaskCount == 0 && createTasks.length > 8) {
                    createTasks = new long[8];
                }
                return true;
            }
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("clear create task failed : " + taskId);
        }

        return false;
    }

    private void createAndLogThread() {
        if (this.timeBetweenLogStatsMillis <= 0) {
            return;
        }

        String threadName = "Druid-ConnectionPool-Log-" + System.identityHashCode(this);
        logStatsThread = new LogStatsThread(threadName);
        logStatsThread.start();

        this.resetStatEnable = false;
    }

    protected void createAndStartDestroyThread() {
        destroyTask = new DestroyTask();

        if (destroyScheduler != null) {
            long period = timeBetweenEvictionRunsMillis;
            if (period <= 0) {
                period = 1000;
            }
            destroySchedulerFuture = destroyScheduler.scheduleAtFixedRate(destroyTask, period, period,
                    TimeUnit.MILLISECONDS);
            return;
        }

        String threadName = "Druid-ConnectionPool-Destroy-" + System.identityHashCode(this);
        destroyConnectionThread = new DestroyConnectionThread(threadName);
        destroyConnectionThread.start();
    }

    protected void createAndStartCreatorThread() {
        if (createScheduler == null) {
            String threadName = "Druid-ConnectionPool-Create-" + System.identityHashCode(this);
            createConnectionThread = new CreateConnectionThread(threadName);
            createConnectionThread.start();
        }
    }

    /**
     * load filters from SPI ServiceLoader
     *
     * @see ServiceLoader
     */
    private void initFromSPIServiceLoader() {
        if (loadSpifilterSkip) {
            return;
        }

        if (autoFilters == null) {
            List<Filter> filters = new ArrayList<Filter>();
            ServiceLoader<Filter> autoFilterLoader = ServiceLoader.load(Filter.class);

            for (Filter filter : autoFilterLoader) {
                AutoLoad autoLoad = filter.getClass().getAnnotation(AutoLoad.class);
                if (autoLoad != null && autoLoad.value()) {
                    filters.add(filter);
                }
            }
            autoFilters = filters;
        }

        for (Filter filter : autoFilters) {
            if (LOG.isInfoEnabled()) {
                LOG.info("load filter from spi :" + filter.getClass().getName());
            }
            addFilter(filter);
        }
    }

    private void initFromWrapDriverUrl() throws SQLException {
        if (!jdbcUrl.startsWith(DruidDriver.DEFAULT_PREFIX)) {
            return;
        }

        DataSourceProxyConfig config = DruidDriver.parseConfig(jdbcUrl, null);
        this.driverClass = config.getRawDriverClassName();

        LOG.error("error url : '" + sanitizedUrl(jdbcUrl) + "', it should be : '" + config.getRawUrl() + "'");

        this.jdbcUrl = config.getRawUrl();
        if (this.name == null) {
            this.name = config.getName();
        }

        for (Filter filter : config.getFilters()) {
            addFilter(filter);
        }
    }

    /**
     * 会去重复
     *
     * @param filter
     */
    private void addFilter(Filter filter) {
        boolean exists = false;
        for (Filter initedFilter : this.filters) {
            if (initedFilter.getClass() == filter.getClass()) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            filter.init(this);
            this.filters.add(filter);
        }

    }

    private void validationQueryCheck() {
        // // 1. 三种校验都未开 → 无需校验手段，直接通过
        if (!(testOnBorrow || testOnReturn || testWhileIdle)) {
            return;
        }

        // 2. 已配置 ValidConnectionChecker → 不需要 validationQuery 也可校验，通过
        if (this.validConnectionChecker != null) {
            return;
        }

        // 3. 3. 已配置 validationQuery（非空字符串）→ 可用 SQL 校验，通过
        if (this.validationQuery != null && this.validationQuery.length() > 0) {
            return;
        }

        // 4. ODPS 数据库特殊处理
        if ("odps".equals(dbTypeName)) {
            return;
        }

        // 5. 开了校验但既没有 ValidConnectionChecker 也没有 validationQuery → 配置错误，打 ERROR
        String errorMessage = "";

        if (testOnBorrow) {
            errorMessage += "testOnBorrow is true, ";
        }

        if (testOnReturn) {
            errorMessage += "testOnReturn is true, ";
        }

        if (testWhileIdle) {
            errorMessage += "testWhileIdle is true, ";
        }

        LOG.error(errorMessage + "validationQuery not set");
    }

    protected void resolveDriver() throws SQLException {
        if (this.driver == null) {
            if (this.driverClass == null || this.driverClass.isEmpty()) {
                this.driverClass = JdbcUtils.getDriverClassName(this.jdbcUrl);
            }

            if (MockDriver.class.getName().equals(driverClass)) {
                driver = MockDriver.instance;
            } else if ("com.alibaba.druid.support.clickhouse.BalancedClickhouseDriver".equals(driverClass)) {
                Properties info = new Properties();
                info.put("user", username);
                info.put("password", password);
                info.putAll(connectProperties);
                driver = new BalancedClickhouseDriver(jdbcUrl, info);
            } else if ("com.alibaba.druid.support.clickhouse.BalancedClickhouseDriverNative".equals(driverClass)) {
                Properties info = new Properties();
                info.put("user", username);
                info.put("password", password);
                info.putAll(connectProperties);
                driver = new BalancedClickhouseDriverNative(jdbcUrl, info);
            } else {
                if (jdbcUrl == null && (driverClass == null || driverClass.length() == 0)) {
                    throw new SQLException("url not set");
                }
                driver = JdbcUtils.createDriver(driverClassLoader, driverClass);
            }
        } else {
            if (this.driverClass == null) {
                this.driverClass = driver.getClass().getName();
            }
        }
    }

    protected void initCheck() throws SQLException {
        DbType dbType = DbType.of(this.dbTypeName);

        if (dbType == DbType.oracle) {
            isOracle = true;

            if (driver.getMajorVersion() < 10) {
                throw new SQLException("not support oracle driver " + driver.getMajorVersion() + "."
                        + driver.getMinorVersion());
            }

            if (driver.getMajorVersion() == 10 && isUseOracleImplicitCache()) {
                this.getConnectProperties().setProperty("oracle.jdbc.FreeMemoryOnEnterImplicitCache", "true");
            }

            oracleValidationQueryCheck();
        } else if (dbType == DbType.db2) {
            db2ValidationQueryCheck();
        } else if (dbType == DbType.mysql
                || JdbcUtils.MYSQL_DRIVER.equals(this.driverClass)
                || JdbcUtils.MYSQL_DRIVER_6.equals(this.driverClass)
                || JdbcUtils.MYSQL_DRIVER_603.equals(this.driverClass)
                || JdbcUtils.GOLDENDB_DRIVER.equals(this.driverClass)
                || JdbcUtils.GBASE8S_DRIVER.equals(this.driverClass)
                || JdbcUtils.POLARDBX_DRIVER.equals(this.driverClass)
        ) {
            isMySql = true;
        }

        if (removeAbandoned) {
            LOG.warn("removeAbandoned is true, not use in production.");
        }
    }

    private void oracleValidationQueryCheck() {
        if (validationQuery == null) {
            return;
        }
        if (validationQuery.length() == 0) {
            return;
        }

        SQLStatementParser sqlStmtParser = SQLParserUtils.createSQLStatementParser(validationQuery, this.dbTypeName);
        List<SQLStatement> stmtList = sqlStmtParser.parseStatementList();

        if (stmtList.size() != 1) {
            return;
        }

        SQLStatement stmt = stmtList.get(0);
        if (!(stmt instanceof SQLSelectStatement)) {
            return;
        }

        SQLSelectQuery query = ((SQLSelectStatement) stmt).getSelect().getQuery();
        if (query instanceof SQLSelectQueryBlock) {
            if (((SQLSelectQueryBlock) query).getFrom() == null) {
                LOG.error("invalid oracle validationQuery. " + validationQuery + ", may should be : " + validationQuery
                        + " FROM DUAL");
            }
        }
    }

    private void db2ValidationQueryCheck() {
        if (validationQuery == null) {
            return;
        }
        if (validationQuery.length() == 0) {
            return;
        }

        SQLStatementParser sqlStmtParser = SQLParserUtils.createSQLStatementParser(validationQuery, this.dbTypeName);
        List<SQLStatement> stmtList = sqlStmtParser.parseStatementList();

        if (stmtList.size() != 1) {
            return;
        }

        SQLStatement stmt = stmtList.get(0);
        if (!(stmt instanceof SQLSelectStatement)) {
            return;
        }

        SQLSelectQuery query = ((SQLSelectStatement) stmt).getSelect().getQuery();
        if (query instanceof SQLSelectQueryBlock) {
            if (((SQLSelectQueryBlock) query).getFrom() == null) {
                LOG.error("invalid db2 validationQuery. " + validationQuery + ", may should be : " + validationQuery
                        + " FROM SYSDUMMY");
            }
        }
    }

    private void initValidConnectionChecker() {
        if (this.validConnectionChecker != null) {
            return;
        }

        String realDriverClassName = driver.getClass().getName();
        if (JdbcUtils.isMySqlDriver(realDriverClassName)) {
            this.validConnectionChecker = new MySqlValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER)
                || realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER2)) {
            this.validConnectionChecker = new OracleValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER)
                || realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER_SQLJDBC4)
                || realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER_JTDS)) {
            this.validConnectionChecker = new MSSQLValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.POSTGRESQL_DRIVER)
                || realDriverClassName.equals(JdbcConstants.ENTERPRISEDB_DRIVER)
                || realDriverClassName.equals(JdbcConstants.OPENGAUSS_DRIVER)
                || realDriverClassName.equals(JdbcConstants.POLARDB_DRIVER)
                || realDriverClassName.equals(JdbcConstants.POLARDB2_DRIVER)) {
            this.validConnectionChecker = new PGValidConnectionChecker();
        } else if (realDriverClassName.equals(JdbcConstants.OCEANBASE_DRIVER)
                || (realDriverClassName.equals(JdbcConstants.OCEANBASE_DRIVER2))) {
            DbType dbType = DbType.of(this.dbTypeName);
            this.validConnectionChecker = new OceanBaseValidConnectionChecker(dbType);
        }

    }

    /**
     * 根据当前使用的 JDBC Driver 类型，在未显式配置的情况下，为数据源选一个合适的 ExceptionSorter，用于判断：某次 SQL 异常是否“致命”——即该连接是否应被丢弃、不再回池
     */
    private void initExceptionSorter() {
        if (exceptionSorter instanceof NullExceptionSorter) {   // 若已是 NullExceptionSorter 且不是 MockDriver，后面会按驱动选一个；若是 MockDriver 则直接 return
            if (driver instanceof MockDriver) {
                return;
            }
        } else if (this.exceptionSorter != null) {  // 用户已配置了 ExceptionSorter，不覆盖
            return;
        }

        // 按 driver 的类名（含父类）匹配，选内置的 Sorter
        for (Class<?> driverClass = driver.getClass(); ; ) {
            String realDriverClassName = driverClass.getName();
            if (realDriverClassName.equals(JdbcConstants.MYSQL_DRIVER) //
                    || realDriverClassName.equals(JdbcConstants.MYSQL_DRIVER_6)
                    || realDriverClassName.equals(JdbcConstants.MYSQL_DRIVER_603)) {
                this.exceptionSorter = new MySqlExceptionSorter();
                this.isMySql = true;
            } else if (realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER)
                    || realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER2)) {
                this.exceptionSorter = new OracleExceptionSorter();
            } else if (realDriverClassName.equals(JdbcConstants.OCEANBASE_DRIVER)) { // 写一个真实的 TestCase
                if (JdbcUtils.OCEANBASE_ORACLE.name().equalsIgnoreCase(dbTypeName)) {
                    this.exceptionSorter = new OceanBaseOracleExceptionSorter();
                } else {
                    this.exceptionSorter = new MySqlExceptionSorter();
                }
            } else if (realDriverClassName.equals("com.informix.jdbc.IfxDriver")) {
                this.exceptionSorter = new InformixExceptionSorter();

            } else if (realDriverClassName.equals("com.sybase.jdbc2.jdbc.SybDriver")) {
                this.exceptionSorter = new SybaseExceptionSorter();

            } else if (realDriverClassName.equals(JdbcConstants.POSTGRESQL_DRIVER)
                    || realDriverClassName.equals(JdbcConstants.ENTERPRISEDB_DRIVER)
                    || realDriverClassName.equals(JdbcConstants.POLARDB_DRIVER)
                    || realDriverClassName.equals(JdbcConstants.POLARDB2_DRIVER)) {
                this.exceptionSorter = new PGExceptionSorter();

            } else if (realDriverClassName.equals("com.alibaba.druid.mock.MockDriver")) {
                this.exceptionSorter = new MockExceptionSorter();
            } else if (realDriverClassName.contains("DB2")) {
                this.exceptionSorter = new DB2ExceptionSorter();
            } else if (realDriverClassName.equals(JdbcConstants.GOLDENDB_DRIVER)) {
                this.exceptionSorter = new MySqlExceptionSorter();
                this.isMySql = true;
            } else if (realDriverClassName.equals(JdbcConstants.POLARDBX_DRIVER)) {
                this.exceptionSorter = new MySqlExceptionSorter();
                this.isMySql = true;
            } else {
                Class<?> superClass = driverClass.getSuperclass();
                if (superClass != null && superClass != Object.class) {
                    driverClass = superClass;
                    continue;
                }
            }

            break;
        }
    }

    @Override
    public DruidPooledConnection getConnection() throws SQLException {
        return getConnection(maxWait);
    }

    /**
     * 获取数据库连接
     * 真正“从池里拿连接或新建连接”的实现在 getConnectionDirect，getConnection 只负责 init、Filter 链、以及 maxWait 传递
     * @param maxWaitMillis 获取连接最大等待毫秒，-1 表示一直等
     * @return  数据库连接
     * @throws SQLException
     */
    public DruidPooledConnection getConnection(long maxWaitMillis) throws SQLException {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            LOG.warn("getConnection but jdbcUrl is not set,jdbcUrl=" + jdbcUrl + ",username=" + username);
            return null;
        }

        // 保证获取连接时数据源已初始化（池、Create/Destroy 线程、预建连接等），只执行一次
        init();

        // filtersSize：Filter链数量
        final int filtersSize = filters.size();
        if (filtersSize > 0) {  // 有 Filter
            // 取一条 FilterChainImpl（createChain）
            FilterChainImpl filterChain = createChain();
            try {
                // 在链上调用 dataSource_connect(this, maxWaitMillis)
                // 递归调用，最终调用DruidDataSource#getConnectionDirect(maxWaitMillis);
                // 再递归调用链路上可以进行一些其他操作
                return filterChain.dataSource_connect(this, maxWaitMillis);
            } finally {
                // 用完后 recycleFilterChain(filterChain) 归还链
                recycleFilterChain(filterChain);
            }
        } else {    // 无 Filter
            //  getConnectionDirect(maxWaitMillis)，不经 Filter
            return getConnectionDirect(maxWaitMillis);
        }
    }

    @Override
    public PooledConnection getPooledConnection() throws SQLException {
        return getConnection(maxWait);
    }

    @Override
    public PooledConnection getPooledConnection(String user, String password) throws SQLException {
        throw new UnsupportedOperationException("Not supported by DruidDataSource");
    }

    /**
     * 在没有 Filter 链（或 Filter 链内部）时，真正从池里拿连接或新建连接，并在借出前做校验、泄漏追踪、默认事务设置，最后返回一根可用的 DruidPooledConnection
     * @param maxWaitMillis 获取连接最大等待毫秒，-1 表示一直等
     * @return
     * @throws SQLException
     */
    public DruidPooledConnection getConnectionDirect(long maxWaitMillis) throws SQLException {
        // 连接数不满情况下的重试次数
        int notFullTimeoutRetryCnt = 0;
        // 用 for (;;) 循环，只有拿到“通过校验”的连接才 return；不合格就丢弃并 continue 再取一个
        for (; ; ) {
            // handle notFullTimeoutRetry
            DruidPooledConnection poolableConnection;
            try {
                // 通过getConnectionInternal方法拿到 DruidPooledConnection（或超时抛异常）
                poolableConnection = getConnectionInternal(maxWaitMillis);
            } catch (GetConnectionTimeoutException ex) {
                // 重试条件：重试次数未达 && 连接数不满
                if (notFullTimeoutRetryCnt < this.notFullTimeoutRetryCount && !isFull()) {
                    // 记录次数
                    notFullTimeoutRetryCnt++;
                    // 打印日志
                    if (LOG.isWarnEnabled()) {
                        LOG.warn("get connection timeout retry : " + notFullTimeoutRetryCnt);
                    }
                    continue;
                }
                throw ex;
            }

            /**
             * 借出校验
             * 目标：保证借出去的连接是可用、未关闭的，必要时做空闲时间 + 有效性校验；不合格则 discardConnection + continue
             * 校验：
             * 1.testOnBorrow校验：每次借出都做一次有效性校验；不通过就丢弃并 continue
             * 2.testWhileIdle校验：对在池里空闲了较长时间（或保活/执行时间较久未更新）的连接，在借出时做一次有效性校验
             * 3.isClosed校验：连接是否已经被关闭”的校验
             */
            if (testOnBorrow) { // testOnBorrow校验
                // 用 ValidConnectionChecker 或 validationQuery 校验连接是否还有效
                boolean validated = testConnectionInternal(poolableConnection.holder, poolableConnection.conn);
                // 不通过：认为连接已坏（网络断、DB 重启、连接被服务端踢掉等）
                if (!validated) {
                    // 日志打印
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("skip not validated connection.");
                    }

                    // discardConnection(holder)（物理关闭、从池移除、必要时 emptySignal 补连）
                    discardConnection(poolableConnection.holder);
                    // 循环取下一个连接
                    continue;
                }
            } else {
                // isClosed校验，查看连接是否已经关闭
                if (poolableConnection.conn.isClosed()) {
                    // discardConnection(holder)（物理关闭、从池移除、必要时 emptySignal 补连）
                    discardConnection(poolableConnection.holder); // 传入null，避免重复关闭
                    // 循环取下一个连接
                    continue;
                }

                // 对“空闲够久”的连接在借出时做一次校验，坏连接丢弃并重试
                if (testWhileIdle) {
                    final DruidConnectionHolder holder = poolableConnection.holder;
                    // 当前时间
                    long currentTimeMillis = System.currentTimeMillis();
                    // 上次被业务使用（借出/归还）的时间
                    long lastActiveTimeMillis = holder.lastActiveTimeMillis;
                    // 上次执行 SQL 的时间
                    long lastExecTimeMillis = holder.lastExecTimeMillis;
                    // 上次保活（keepAlive）检测通过的时间
                    long lastKeepTimeMillis = holder.lastKeepTimeMillis;

                    // 若配置了“用执行时间代替活跃时间”，则用 lastExecTimeMillis
                    if (checkExecuteTime
                            && lastExecTimeMillis != lastActiveTimeMillis) {
                        lastActiveTimeMillis = lastExecTimeMillis;
                    }

                    // 保活时间更晚则用保活时间，表示“最近一次证明连接还活着的时刻”
                    if (lastKeepTimeMillis > lastActiveTimeMillis) {
                        lastActiveTimeMillis = lastKeepTimeMillis;
                    }

                    // 空闲时间 = 当前时间 - 上次被业务使用（借出/归还）的时间
                    long idleMillis = currentTimeMillis - lastActiveTimeMillis;

                    // 空闲时间 >=  eviction 间隔，或异常为负，才做校验
                    if (idleMillis >= timeBetweenEvictionRunsMillis
                            || idleMillis < 0 // unexcepted branch
                    ) {
                        // 用 ValidConnectionChecker 或 validationQuery 校验连接是否还有效
                        boolean validated = testConnectionInternal(poolableConnection.holder, poolableConnection.conn);
                        // 不通过：认为连接已坏（网络断、DB 重启、连接被服务端踢掉等）
                        if (!validated) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("skip not validated connection.");
                            }
                            // discardConnection(holder)（物理关闭、从池移除、必要时 emptySignal 补连）
                            discardConnection(poolableConnection.holder);
                            // 循环取下一个连接
                            continue;
                        }
                    }
                }
            }

            /**
             * 泄漏追踪，对本次借出的连接做泄漏追踪
             * 在“借出”这一刻就为泄漏检测准备好了栈和时间，后面归还时会从 activeConnections 移除并清理 trace
             * removeAbandoned 时记录栈、时间并加入 activeConnections
             */
            if (removeAbandoned) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                // 当前线程的调用栈，便于后续看到“是谁借走了连接没还”
                poolableConnection.connectStackTrace = stackTrace;
                // 记录借出时间，用于和 removeAbandonedTimeoutMillis 比较
                poolableConnection.setConnectedTimeNano();
                // 标记该连接参与泄漏检测
                poolableConnection.traceEnable = true;

                activeConnectionLock.lock();
                try {
                    // 把当前连接放入“已借出”集合，Destroy 线程会按超时时间扫描 activeConnections，对超时未还的连接做回收或打日志
                    activeConnections.put(poolableConnection, PRESENT);
                } finally {
                    activeConnectionLock.unlock();
                }
            }

            // defaultAutoCommit==false 时 setAutoCommit(false)
            if (!this.defaultAutoCommit) {
                poolableConnection.setAutoCommit(false);
            }

            // 返回连接
            return poolableConnection;
        }
    }

    /**
     * 抛弃连接，不进行回收，而是抛弃
     *
     * @param conn the connection to be discarded
     * @return a boolean indicating whether the empty signal was called
     * @deprecated
     */
    @Override
    public boolean discardConnection(Connection conn) {
        boolean emptySignalCalled = false;
        if (conn == null) {
            return emptySignalCalled;
        }

        try {
            if (!conn.isClosed()) {
                conn.close();
            }
        } catch (SQLRecoverableException ignored) {
            discardErrorCountUpdater.incrementAndGet(this);
            // ignored
        } catch (Throwable e) {
            discardErrorCountUpdater.incrementAndGet(this);

            if (LOG.isDebugEnabled()) {
                LOG.debug("discard to close connection error", e);
            }
        }

        lock.lock();
        try {
            activeCount--;
            discardCount++;

            int fillCount = minIdle - (activeCount + poolingCount + createTaskCount);
            if (fillCount > 0) {
                emptySignalCalled = true;
                emptySignal(fillCount);
            }
        } finally {
            lock.unlock();
        }
        return emptySignalCalled;
    }

    @Override
    public boolean discardConnection(DruidConnectionHolder holder) {
        boolean emptySignalCalled = false;
        if (holder == null) {
            return emptySignalCalled;
        }

        Connection conn = holder.getConnection();
        if (conn != null) {
            JdbcUtils.close(conn);
        }

        Socket socket = holder.socket;
        if (socket != null) {
            JdbcUtils.close(socket);
        }

        lock.lock();
        try {
            if (holder.discard) {
                return emptySignalCalled;
            }

            if (holder.active) {
                activeCount--;
                holder.active = false;
            }
            discardCount++;

            holder.discard = true;

            int fillCount = minIdle - (activeCount + poolingCount + createTaskCount);
            if (fillCount > 0) {
                emptySignalCalled = true;
                emptySignal(fillCount);
            }
        } finally {
            lock.unlock();
        }
        return emptySignalCalled;
    }

    /**
     * “从池里拿连接”的核心实现
     * 功能：在已初始化的前提下，从池中取一个空闲连接（DruidConnectionHolder），或触发/参与建连，并在持有主锁 lock 的情况下更新 activeCount，
     * 最后返回包装好的 DruidPooledConnection；若在 maxWait 内拿不到连接则返回 holder == null，由调用方（如 getConnectionDirect）抛 GetConnectionTimeoutException。
     * @param maxWait
     * @return
     * @throws SQLException
     */
    private DruidPooledConnection getConnectionInternal(long maxWait) throws SQLException {
        // 1.前置检查，保证在池未关闭、未禁用时才继续取连接
        // 数据源已关闭则直接抛 DataSourceClosedException，并增加错误计数数据源已关闭则直接抛 DataSourceClosedException，并增加错误计数
        if (closed) {
            // 连接错误次数 + 1
            connectErrorCountUpdater.incrementAndGet(this);
            // 抛出异常
            throw new DataSourceClosedException("dataSource already closed at " + new Date(closeTimeMillis));
        }

        // 数据源被禁用（如通过 JMX 或 API 关闭）
        if (!enable) {
            // 连接错误次数 + 1
            connectErrorCountUpdater.incrementAndGet(this);

            // 有 disableException 则抛出它
            if (disableException != null) {
                throw disableException;
            }

            // 否则抛 DataSourceDisableException
            throw new DataSourceDisableException();
        }


        // 获取允许同时等待连接的最大线程数，后面在锁内检查，超限直接抛异常，防止雪崩
        final int maxWaitThreadCount = this.maxWaitThreadCount;

        // 包装物理连接和池引用对象
        DruidConnectionHolder holder;

        // startTime / expiredTime：用于 pollLast 的超时判断（maxWait>0 时），以及后面超时异常里的等待时间、建连耗时等统计
        //进入循环等待之前，先记录开始尝试获取连接的时间
        long startTime = System.currentTimeMillis();
        // 根据开始时间，计算超时时间
        final long expiredTime = startTime + maxWait;

        // 2.获取连接
        // createDirect：循环内标志，为 true 时在下一轮走“当前线程直接建连”分支，用于池空且有调度器排队任务时加速拿到连接
        for (boolean createDirect = false; ; ) {
            // 直接建连入口
            // createScheduler 有排队任务且池空、未超时，且没有其它线程在“直接建连/创建中”时，会设置 createDirect = true 并 continue，本轮即进入此分支
            if (createDirect) {
                try {
                    // 设置创建开始时间
                    createStartNanosUpdater.set(this, System.nanoTime());
                    // 通过CAS，将正在建连次数设置为1，保证同一时刻只有一个线程在做“当前线程直接建连”，避免重复建连
                    if (creatingCountUpdater.compareAndSet(this, 0, 1)) {
                        // 真正向数据库建物理连接
                        PhysicalConnectionInfo pyConnInfo = DruidDataSource.this.createPhysicalConnection();
                        // 包装物理连接和池引用
                        holder = new DruidConnectionHolder(this, pyConnInfo);

                        // 正在建连次数 - 1
                        creatingCountUpdater.decrementAndGet(this);
                        // 当前线程直接建连创建次数 + 1
                        directCreateCountUpdater.incrementAndGet(this);

                        if (LOG.isDebugEnabled()) {
                            LOG.debug("conn-direct_create ");
                        }

                        // 加锁，保证对连接数操作的读写安全
                        final Lock lock = this.lock;
                        lock.lock();
                        try {
                            // 总连接数 = activeCount + poolingCount < maxActive，说明池未满
                            if (activeCount + poolingCount < maxActive) {
                                // activeCount++、holder.active = true、更新 activePeak，然后 break 跳出循环，该连接会被包装成 DruidPooledConnection 返回
                                // 正在使用的连接数量 + 1
                                activeCount++;
                                // 标记当前连接已经借出
                                holder.active = true;
                                if (activeCount > activePeak) {
                                    // 更新历史最大借出数
                                    activePeak = activeCount;
                                    // 更新历史最大借出发生的时间
                                    activePeakTime = System.currentTimeMillis();
                                }
                                // 完成建连，退出
                                break;
                            }
                        } finally {
                            // 解锁
                            lock.unlock();
                        }

                        // 若池已满（并发下其它线程已占满），则关闭刚建的物理连接 JdbcUtils.close(...)，不放入池
                        JdbcUtils.close(pyConnInfo.getPhysicalConnection());
                    }
                } finally {
                    // createDirect = false，下一轮不再走直接建连，而是走下面的 pollLast/takeLast
                    createDirect = false;
                    // “当前线程正在直接建连”数 - 1，配合外面判断“是否有线程正在直接建连”
                    createDirectCountUpdater.decrementAndGet(this);
                }
            }

            final ReentrantLock lock = this.lock;
            try {
                // 可中断加锁，等待取连接时若线程被中断则抛 InterruptedException，这里转为 SQLException 并增加错误计数
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException("interrupt", e);
            }

            try {
                // 若配置了上限且当前等待连接线程数：maxWaitThreadCount > 0
                // notEmptyWaitThreadCount 已超过：notEmptyWaitThreadCount > maxWaitThreadCount) {
                // 直接抛异常，避免大量线程堵在取连接上
                if (maxWaitThreadCount > 0
                        && notEmptyWaitThreadCount > maxWaitThreadCount) {
                    // 连接错误次数 + 1
                    connectErrorCountUpdater.incrementAndGet(this);
                    throw new SQLException("maxWaitThreadCount " + maxWaitThreadCount + ", current wait Thread count "
                            + lock.getQueueLength());
                }

                // 发生致命错误（如数据库不可用）且 activeCount >= onFatalErrorMaxActive 时，不再分配新连接，直接抛包含 lastFatalError、lastFatalErrorSql 等信息的 SQLException，防止在异常状态下继续建连/借连接
                if (onFatalError
                        && onFatalErrorMaxActive > 0
                        && activeCount >= onFatalErrorMaxActive) {
                    connectErrorCountUpdater.incrementAndGet(this);

                    StringBuilder errorMsg = new StringBuilder();
                    errorMsg.append("onFatalError, activeCount ")
                            .append(activeCount)
                            .append(", onFatalErrorMaxActive ")
                            .append(onFatalErrorMaxActive);

                    if (lastFatalErrorTimeMillis > 0) {
                        errorMsg.append(", time '")
                                .append(StringUtils.formatDateTime19(
                                        lastFatalErrorTimeMillis, TimeZone.getDefault()))
                                .append("'");
                    }

                    if (lastFatalErrorSql != null) {
                        errorMsg.append(", sql \n")
                                .append(lastFatalErrorSql);
                    }

                    throw new SQLException(
                            errorMsg.toString(), lastFatalError);
                }

                // 每次尝试取连接都会增加，用于统计
                connectCount++;

                // 使用 ScheduledThreadPoolExecutor 作为 createScheduler异步建连，不为null：createScheduler != null
                // 池里当前没有空闲连接：poolingCount == 0
                // 还没到最大连接数，允许再建新连接：activeCount < maxActive
                // 没有其它线程正在“直接建连”或“创建中”：createDirectCount/creatingCount == 0
                // 目的：池已经空了，但“建连任务”还在调度器队列里没执行，与其干等调度线程跑完任务，不如当前要连接的线程自己建连，更快拿到连接
                if (createScheduler != null
                        && poolingCount == 0
                        && activeCount < maxActive
                        && createDirectCountUpdater.get(this) == 0
                        && creatingCountUpdater.get(this) == 0
                        && createScheduler instanceof ScheduledThreadPoolExecutor) {
                    ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) createScheduler;
                    if (executor.getQueue().size() > 0) { // 调度器队列里还有待执行任务：executor.getQueue().size() > 0
                        // 若已超过 maxWait（最大等待超时时间），则设 holder = null 并 break，后面会抛超时异常
                        if (maxWait > 0 && System.currentTimeMillis() - startTime >= maxWait) {
                            holder = null;
                            break;
                        }
                        // 否则设 createDirect = true 并 continue，下一轮会进入上面的“当前线程直接建连”分支，减少等待 CreateConnectionThread 或调度器任务的时间。
                        createDirect = true;
                        // “当前线程正在直接建连”数 + 1
                        createDirectCountUpdater.incrementAndGet(this);
                        continue;
                    }
                }

                if (maxWait > 0) {  // 设置了最大等待时间。
                    if (System.currentTimeMillis() < expiredTime) { // 若未到 expiredTime 则调用 pollLast(startTime, expiredTime)，在剩余时间内等待空闲连接，超时返回 null
                        holder = pollLast(startTime, expiredTime);
                    } else {    // 超时返回 null；若已超时则直接 holder = null 并 break
                        holder = null;
                        break;
                    }
                } else {    // 未设置最大等待时间，调用 takeLast(startTime)，内部即 pollLast(startTime, 0)，estimate=0 时用 notEmpty.await() 一直阻塞直到有连接被回收或创建线程放入池
                    holder = takeLast(startTime);
                }

                // 对获取的连接进行校验
                if (holder != null) {
                    // 该连接已被标记废弃（如校验失败、异常关闭），不能交给业务
                    if (holder.discard) {
                        // 置 holder = null，若已超时则 break（后面抛超时异常），未超时则 continue 再试一次取连接
                        holder = null;
                        if (maxWait > 0 && System.currentTimeMillis() >= expiredTime) {
                            break;
                        }
                        continue;
                    }

                    // holder 有效：activeCount++，holder.active = true，必要时更新 activePeak / activePeakTime，然后 finally 解锁，break 退出循环
                    activeCount++;
                    holder.active = true;
                    if (activeCount > activePeak) {
                        activePeak = activeCount;
                        activePeakTime = System.currentTimeMillis();
                    }
                }
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException(e.getMessage(), e);
            } catch (SQLException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw e;
            } finally {
                // 解锁
                lock.unlock();
            }

            break;
        }

        // 最终获取连接结果处理
        // holder == null：抛 GetConnectionTimeoutException
        if (holder == null) {
            long waitMillis = System.currentTimeMillis() - startTime;

            final long activeCount;
            final long maxActive;
            final long creatingCount;
            final long createStartNanos;
            final long createErrorCount;
            final Throwable createError;
            try {
                lock.lock();
                activeCount = this.activeCount;
                maxActive = this.maxActive;
                creatingCount = this.creatingCount;
                createStartNanos = this.createStartNanos;
                createErrorCount = this.createErrorCount;
                createError = this.createError;
            } finally {
                lock.unlock();
            }

            StringBuilder buf = new StringBuilder(128);
            buf.append("wait millis ")
                    .append(waitMillis)
                    .append(", active ").append(activeCount)
                    .append(", maxActive ").append(maxActive)
                    .append(", creating ").append(creatingCount);

            if (creatingCount > 0 && createStartNanos > 0) {
                long createElapseMillis = (System.nanoTime() - createStartNanos) / (1000 * 1000);
                if (createElapseMillis > 0) {
                    buf.append(", createElapseMillis ").append(createElapseMillis);
                }
            }

            if (createErrorCount > 0) {
                buf.append(", createErrorCount ").append(createErrorCount);
            }

            List<JdbcSqlStatValue> sqlList = this.getDataSourceStat().getRuningSqlList();
            for (int i = 0; i < sqlList.size(); ++i) {
                if (i != 0) {
                    buf.append('\n');
                } else {
                    buf.append(", ");
                }
                JdbcSqlStatValue sql = sqlList.get(i);
                buf.append("runningSqlCount ").append(sql.getRunningCount());
                buf.append(" : ");
                buf.append(sql.getSql());
            }

            String errorMessage = buf.toString();

            if (createError != null) {
                throw new GetConnectionTimeoutException(errorMessage, createError);
            } else {
                throw new GetConnectionTimeoutException(errorMessage);
            }
        }

        // 增加该连接被复用次数，用于统计或淘汰策略
        holder.incrementUseCount();

        // holder 此时一定非 null 且未 discard
        // 把 DruidConnectionHolder 包装成池化连接返回给上层，后续 getConnectionDirect 还会做 testOnBorrow/testWhileIdle、removeAbandoned、defaultAutoCommit 等处理
        return new DruidPooledConnection(holder);
    }

    public void handleConnectionException(
            DruidPooledConnection pooledConnection,
            Throwable t,
            String sql
    ) throws SQLException {
        final DruidConnectionHolder holder = pooledConnection.getConnectionHolder();
        if (holder == null) {
            return;
        }

        errorCountUpdater.incrementAndGet(this);
        lastError = t;
        lastErrorTimeMillis = System.currentTimeMillis();

        if (t instanceof SQLException) {
            SQLException sqlEx = (SQLException) t;

            // broadcastConnectionError
            ConnectionEvent event = new ConnectionEvent(pooledConnection, sqlEx);
            for (ConnectionEventListener eventListener : holder.getConnectionEventListeners()) {
                eventListener.connectionErrorOccurred(event);
            }

            // exceptionSorter.isExceptionFatal
            if (exceptionSorter != null && exceptionSorter.isExceptionFatal(sqlEx)) {
                handleFatalError(pooledConnection, sqlEx, sql);
            }

            throw sqlEx;
        } else {
            throw new SQLException("Error", t);
        }
    }

    protected final void handleFatalError(
            DruidPooledConnection conn,
            SQLException error,
            String sql
    ) throws SQLException {
        final DruidConnectionHolder holder = conn.holder;

        if (conn.isTraceEnable()) {
            activeConnectionLock.lock();
            try {
                if (conn.isTraceEnable()) {
                    activeConnections.remove(conn);
                    conn.setTraceEnable(false);
                }
            } finally {
                activeConnectionLock.unlock();
            }
        }

        long lastErrorTimeMillis = this.lastErrorTimeMillis;
        if (lastErrorTimeMillis == 0) {
            lastErrorTimeMillis = System.currentTimeMillis();
        }

        if (sql != null && sql.length() > 1024) {
            sql = sql.substring(0, 1024);
        }

        boolean requireDiscard = false;
        // using dataSourceLock when holder dataSource isn't null because shrink used it to access fatal error variables.
        boolean hasHolderDataSource = (holder != null && holder.getDataSource() != null);
        ReentrantLock fatalErrorCountLock = hasHolderDataSource ? holder.getDataSource().lock : conn.lock;
        fatalErrorCountLock.lock();
        try {
            if ((!conn.closed) && !conn.disable) {
                conn.disable(error);
                requireDiscard = true;
            }

            lastFatalErrorTimeMillis = lastErrorTimeMillis;
            fatalErrorCount++;
            if (fatalErrorCount - fatalErrorCountLastShrink > onFatalErrorMaxActive) {
                // increase fatalErrorCountLastShrink to avoid that emptySignal would be called again by shrink.
                fatalErrorCountLastShrink++;
                onFatalError = true;
            } else {
                onFatalError = false;
            }
            lastFatalError = error;
            lastFatalErrorSql = sql;
        } finally {
            fatalErrorCountLock.unlock();
        }

        boolean emptySignalCalled = false;
        if (requireDiscard) {
            if (holder != null && holder.statementTrace != null) {
                holder.lock.lock();
                try {
                    for (Statement stmt : holder.statementTrace) {
                        JdbcUtils.close(stmt);
                    }
                } finally {
                    holder.lock.unlock();
                }
            }

            // decrease activeCount first to make sure the following emptySignal should be called successfully.
            emptySignalCalled = this.discardConnection(holder);
        }

        // holder.
        LOG.error("{conn-" + (holder != null ? holder.getConnectionId() : "null") + "} discard", error);

        if (!emptySignalCalled && onFatalError && hasHolderDataSource) {
            fatalErrorCountLock.lock();
            try {
                emptySignal();
            } finally {
                fatalErrorCountLock.unlock();
            }
        }
    }

    /**
     * 回收连接
     */
    protected void recycle(DruidPooledConnection pooledConnection) throws SQLException {
        // 取 holder
        final DruidConnectionHolder holder = pooledConnection.holder;

        // 空则只打日志并 return（
        if (holder == null) {
            LOG.warn("connectionHolder is null");
            return;
        }

        // 当前是否处于“允许跨线程关闭”或“泄漏检测”模式
        boolean asyncCloseConnectionEnable = this.removeAbandoned || this.asyncCloseConnectionEnable;
        // 当前线程是否为借出该连接的线程
        boolean isSameThread = pooledConnection.ownerThread == Thread.currentThread();

        // logDifferentThread 且非 async 且跨线程时打“借/还不同线程”的 warn，便于发现不规范用法
        if (logDifferentThread //
                && (!asyncCloseConnectionEnable) //
                && !isSameThread
        ) {
            LOG.warn("get/close not same thread");
        }

        // 获取底层 JDBC Connection，后续用于 isClosed、testOnReturn、关闭等
        final Connection physicalConnection = holder.conn;

        // 若该池化连接参与了泄漏追踪
        if (pooledConnection.traceEnable) {
            Object oldInfo = null;
            activeConnectionLock.lock();
            try {
                if (pooledConnection.traceEnable) {
                    // 从 activeConnections 移除
                    oldInfo = activeConnections.remove(pooledConnection);
                    // 并置 traceEnable=false
                    pooledConnection.traceEnable = false;
                }
            } finally {
                activeConnectionLock.unlock();
            }
            if (oldInfo == null) {  // 本次 remove 没找到（例如已被 removeAbandoned 移走），打 warn 便于排查
                if (LOG.isWarnEnabled()) {
                    LOG.warn("remove abandoned failed. activeConnections.size " + activeConnections.size());
                }
            }
        }

        // 是否事务自动提交
        final boolean isAutoCommit = holder.underlyingAutoCommit;
        // 是否只读
        final boolean isReadOnly = holder.underlyingReadOnly;
        // 归还是否校验
        final boolean testOnReturn = this.testOnReturn;

        try {
            // 未自动提交且非只读时 rollback，避免未提交事务占用连接状态
            // check need to rollback?
            if ((!isAutoCommit) && (!isReadOnly)) {
                pooledConnection.rollback();
            }

            // 恢复默认设置、清缓存、清 Warnings 等，使连接回到“可再次借出”的干净状态。跨线程时先拿连接自己的 lock 再 reset，避免与业务线程同时操作同一连接。
            // reset holder, restore default settings, clear warnings
            if (!isSameThread) {
                final ReentrantLock lock = pooledConnection.lock;
                lock.lock();
                try {
                    holder.reset();
                } finally {
                    lock.unlock();
                }
            } else {
                holder.reset();
            }

            // 该连接已被别处标记为废弃（如借出校验失败、异常路径），不再回池，直接 return（池化连接侧会在 recycle() 里清空 holder/conn/closed）
            if (holder.discard) {
                return;
            }

            // 物理连接最大复用次数，达到则丢弃并 discardConnection，促新连接替换
            // 数据源用户名/密码变更后，旧连接不再回池，discardConnection 后 return
            if ((phyMaxUseCount > 0 && holder.useCount >= phyMaxUseCount)
                    || holder.userPasswordVersion < getUserPasswordVersion()) {
                discardConnection(holder);
                return;
            }

            // 物理连接已关闭
            // 底层连接已被关闭（如被服务端踢掉、超时），不能再放入池。只做 activeCount--、closeCount++，不 putLast，不再次关闭（避免重复关）
            if (physicalConnection.isClosed()) {
                lock.lock();
                try {
                    if (holder.active) {
                        activeCount--;
                        holder.active = false;
                    }
                    closeCount++;
                } finally {
                    lock.unlock();
                }
                return;
            }

            // 若开启 testOnReturn，归还前做一次有效性检测；不通过则物理关闭、更新 destroyCount/activeCount/closeCount，不回池
            if (testOnReturn) {
                boolean validated = testConnectionInternal(holder, physicalConnection);
                if (!validated) {
                    JdbcUtils.close(physicalConnection);

                    destroyCountUpdater.incrementAndGet(this);

                    lock.lock();
                    try {
                        if (holder.active) {
                            activeCount--;
                            holder.active = false;
                        }
                        closeCount++;
                    } finally {
                        lock.unlock();
                    }
                    return;
                }
            }
            // 若借出时记录了初始 schema，归还时恢复并清空字段
            if (holder.initSchema != null) {
                holder.conn.setSchema(holder.initSchema);
                holder.initSchema = null;
            }

            // 数据源已禁用，不再回池，discardConnection 后 return
            if (!enable) {
                discardConnection(holder);
                return;
            }

            boolean result;
            final long currentTimeMillis = System.currentTimeMillis();

            // 物理连接存活超过该时间则不再回池，discardConnection 后 return
            if (phyTimeoutMillis > 0) {
                long phyConnectTimeMillis = currentTimeMillis - holder.connectTimeMillis;
                if (phyConnectTimeMillis > phyTimeoutMillis) {
                    discardConnection(holder);
                    return;
                }
            }

            // 回池，在已经做完 rollback、reset、各种“不能回池”的检查之后，把这条连接正式从借出变为空闲

            // 先假设“不是因池满而失败”，后面若 putLast 失败再根据当前池状态设 full，用于日志里区分“池满”和“已关闭/discard”等。
            boolean full = false;
            // 拿数据源主锁，和 getConnectionInternal、pollLast、putLast、discardConnection 等共用，保证 activeCount、connections、poolingCount 的读写是原子的。
            lock.lock();
            try {
                // 只有这条连接当前仍被算作“借出”（active 为 true）时才减 activeCount。正常情况下从池借出时会把 holder.active=true，所以归还时这里会成立；若前面某分支已把 active 置 false 或未置过，这里不会重复减。
                if (holder.active) {
                    // “当前被借出的连接数”减 1，与 getConnection 时的 activeCount++ 对应，保证池的计数正确。
                    activeCount--;
                    // 标记该 holder 已不再被借出，避免后续逻辑或统计仍把它当活跃连接
                    holder.active = false;
                }
                // 统计“业务调 close 的次数”（逻辑关闭次数），与 connectCount 对应，用于监控/健康检查
                closeCount++;

                // 回池
                result = putLast(holder, currentTimeMillis);
                // 回归数量 + 1
                recycleCount++;

                // putLast 失败时，用 poolingCount + activeCount >= maxActive 判断是否“池已满”。
                // full 为 true 表示很可能是因池满导致 putLast 拒绝；为 false 表示更可能是 closed、closing 或 holder.discard 等。
                if (!result) {
                    full = poolingCount + activeCount >= maxActive;
                }
            } finally {
                // 无论 try 里是否抛异常、putLast 成功与否，都释放主锁，避免死锁
                lock.unlock();
            }

            // putLast 返回 false：池已满、或数据源已关闭/正在关闭、或 holder 已被标记 discard 等，连接没能放进 connections。
            if (!result) {
                // 既然不能回池，就不能再留着这条物理连接，否则会泄漏；所以这里关闭底层 JDBC Connection，释放 DB 端资源。
                // 注意：前面在锁内已经做了 activeCount--、closeCount++、recycleCount++，这里不再改计数，只做物理关闭。
                JdbcUtils.close(holder.conn);
                String msg = "connection recycle failed.";
                // 若前面算出 full 为 true，在日志里标明是“池满”导致回收失败，便于和“数据源关闭”等其它原因区分
                if (full) {
                    msg += " pool is full";
                }
                LOG.info(msg);
            }
        } catch (Throwable e) {
            // 清空该 holder 上的语句缓存，避免异常状态下带着脏缓存再被复用或关闭时出问题
            holder.clearStatementCache();

            // 若该 holder 尚未被标记为废弃，才执行丢弃逻辑；避免重复 discard、重复 activeCount-- 等
            if (!holder.discard) {
                // 理关闭 Connection（及 socket），持主锁下：若 holder.active
                // 则 activeCount--、holder.active=false，discardCount++，holder.discard=true，并在池低于 minIdle 时 emptySignal 触发补连。
                // 保证这条连接从池的“逻辑”和“物理”上都移除，且计数正确
                discardConnection(holder);
                // 标记该 holder 已废弃，后续若再遇到该 holder（如重复调用）不会再重复 discard 或回池。
                holder.discard = true;
            }

            LOG.error("recycle error", e);
            // 记录回收异常和次数，便于发现 DB 异常、网络问题、配置问题等。
            recycleErrorCountUpdater.incrementAndGet(this);
        }
    }

    public long getRecycleErrorCount() {
        return recycleErrorCount;
    }

    public void clearStatementCache() throws SQLException {
        lock.lock();
        try {
            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder conn = connections[i];

                if (conn.statementPool != null) {
                    conn.statementPool.clear();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * close datasource
     */
    public void close() {
        if (LOG.isInfoEnabled()) {
            LOG.info("{dataSource-" + this.getID() + "} closing ...");
        }

        lock.lock();
        try {
            if (this.closed) {
                return;
            }

            if (!this.inited) {
                return;
            }

            this.closing = true;

            if (logStatsThread != null) {
                logStatsThread.interrupt();
            }

            if (createConnectionThread != null) {
                createConnectionThread.interrupt();
            }

            if (destroyConnectionThread != null) {
                destroyConnectionThread.interrupt();
            }

            for (Future<?> createSchedulerFuture : createSchedulerFutures.values()) {
                createSchedulerFuture.cancel(true);
            }

            if (destroySchedulerFuture != null) {
                destroySchedulerFuture.cancel(true);
            }

            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder connHolder = connections[i];

                for (PreparedStatementHolder stmtHolder : connHolder.getStatementPool().getMap().values()) {
                    connHolder.getStatementPool().closeRemovedStatement(stmtHolder);
                }
                connHolder.getStatementPool().getMap().clear();

                Connection physicalConnection = connHolder.getConnection();
                try {
                    physicalConnection.close();
                } catch (Exception ex) {
                    LOG.warn("close connection error", ex);
                }
                connections[i] = null;
                destroyCountUpdater.incrementAndGet(this);
            }
            poolingCount = 0;
            unregisterMbean();

            enable = false;
            notEmpty.signalAll();
            notEmptySignalCount++;

            this.closed = true;
            this.closeTimeMillis = System.currentTimeMillis();

            disableException = new DataSourceDisableException();

            for (Filter filter : filters) {
                filter.destroy();
            }
        } finally {
            this.closing = false;
            lock.unlock();
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("{dataSource-" + this.getID() + "} closed");
        }
    }

    public void registerMbean() {
        // 仅当 mbeanRegistered == false 时才执行注册
        if (!mbeanRegistered) {
            // 在 AccessController.doPrivileged 中执行，避免调用方没有 JMX 权限导致注册失
            AccessController.doPrivileged(new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    ObjectName objectName = DruidDataSourceStatManager.addDataSource(DruidDataSource.this,
                            DruidDataSource.this.name);

                    DruidDataSource.this.setObjectName(objectName);
                    DruidDataSource.this.mbeanRegistered = true;

                    return null;
                }
            });
        }
    }

    public void unregisterMbean() {
        if (mbeanRegistered) {
            AccessController.doPrivileged(new PrivilegedAction<Object>() {
                @Override
                public Object run() {
                    DruidDataSourceStatManager.removeDataSource(DruidDataSource.this);
                    DruidDataSource.this.mbeanRegistered = false;
                    return null;
                }
            });
        }
    }

    public boolean isMbeanRegistered() {
        return mbeanRegistered;
    }

    /**
     * 回池
     * 作用：在已持锁的前提下把 holder 放入 connections[poolingCount]，poolingCount++，更新 lastActiveTimeMillis、poolingPeak，
     * 并 notEmpty.signal() 唤醒一个在 pollLast 里等连接的线程。putLast 内部会再判断：
     * 池已满(activeCount+poolingCount>=maxActive)、holder.discard、closed、closing 等会直接返回 false，不放进去
     * @param e
     * @param lastActiveTimeMillis
     * @return
     */
    boolean putLast(DruidConnectionHolder e, long lastActiveTimeMillis) {
        if (activeCount + poolingCount >= maxActive || e.discard || this.closed || this.closing) {
            return false;
        }

        e.lastActiveTimeMillis = lastActiveTimeMillis;
        connections[poolingCount] = e;
        incrementPoolingCount();

        if (poolingCount > poolingPeak) {
            poolingPeak = poolingCount;
            poolingPeakTime = lastActiveTimeMillis;
        }

        notEmpty.signal();
        notEmptySignalCount++;

        return true;
    }

    private DruidConnectionHolder takeLast(long startTime) throws InterruptedException, SQLException {
        // 间接调用pollLast方法，expiredTime入参 = 0，即不设置超时时间
        return pollLast(startTime, 0);
    }

    /**
     * 在已持有主锁 lock 的前提下，从空闲池 connections 里取一个空闲连接（DruidConnectionHolder）；池空时先唤醒创建线程并阻塞等待，
     * 支持超时（expiredTime != 0）或无限等（expiredTime == 0）
     *
     * 调用方：仅被 getConnectionInternal 调用：
     * maxWait > 0 时：pollLast(startTime, expiredTime)，限时等待；
     * maxWait <= 0 时：takeLast(startTime) → 内部调用 pollLast(startTime, 0)，无限等待
     *
     * 约定：调用前必须已持有 this.lock，方法内通过 notEmpty.await() 会释放锁，被唤醒后再次持有锁
     *
     * @param startTime 开始时间
     * @param expiredTime 超时时间
     * @return
     * @throws InterruptedException
     * @throws SQLException
     */
    private DruidConnectionHolder pollLast(long startTime, long expiredTime) throws InterruptedException, SQLException {
        try {
            // 1.池空循环
            long awaitStartTime;
            long estimate = 0;
            // 当前空闲连接数为 0 时一直循环，直到有连接被放入池（由回收或创建线程 signal）或超时/异常退出
            while (poolingCount == 0) {
                // 池空时通知“创建侧”去建连。无调度器时 empty.signal() 唤醒 CreateConnectionThread；有 createScheduler 时会提交建连任务，避免没人建连导致一直等
                // send signal to CreateThread create connection
                emptySignal();

                // 开启快速失败且最近建连连续失败时，直接抛 DataSourceNotAvailableException(createError)，不再阻塞等待
                if (failFast && isFailContinuous()) {
                    throw new DataSourceNotAvailableException(createError);
                }

                awaitStartTime = System.currentTimeMillis();
                // expiredTime != 0：表示调用方设置了超时（来自 getConnection 的 maxWait）
                if (expiredTime != 0) {
                    // 剩余可等待时间（毫秒）
                    estimate = expiredTime - awaitStartTime;
                    // 已超时，直接 return null，由 getConnectionInternal方法 抛 GetConnectionTimeoutException
                    if (estimate <= 0) {
                        return null;
                    }
                }

                // 2.等待线程计数与阻塞等待
                // “等空闲连接”的线程数 + 1
                notEmptyWaitThreadCount++;
                // 记录历史上同时等待连接的最大线程数
                if (notEmptyWaitThreadCount > notEmptyWaitThreadPeak) {
                    notEmptyWaitThreadPeak = notEmptyWaitThreadCount;
                }
                try {
                    // signal by recycle or creator
                    if (estimate == 0) {    // estimate == 0（仅 takeLast 时）：无限等待，直到被 notEmpty.signal() 唤醒（回收或创建线程往池里放连接时会 signal）
                        notEmpty.await();
                    } else {    // estimate > 0：最多再等 estimate 毫秒，超时后自动醒来，下一轮 while 会重新算 estimate，若已超时则 return null
                        notEmpty.await(estimate, TimeUnit.MILLISECONDS);
                    }
                } finally {
                    // 无论正常唤醒还是超时，都会 notEmptyWaitThreadCount--，并累加 notEmptyWaitCount、notEmptyWaitNanos，用于统计“等池非空”的次数和总时长
                    notEmptyWaitThreadCount--;
                    notEmptyWaitCount++;
                    notEmptyWaitNanos += TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis() - awaitStartTime);
                }

                // 3.唤醒后检查 enable
                if (!enable) {
                    connectErrorCountUpdater.incrementAndGet(this);
                    // 被唤醒后若数据源已被禁用（enable == false），不再取连接，直接增加错误计数并抛 DataSourceDisableException 或 disableException，
                    // 避免在关闭/禁用过程中仍把连接借出。
                    if (disableException != null) {
                        throw disableException;
                    }

                    throw new DataSourceDisableException();
                }

            }
        } catch (InterruptedException ie) {
            // 4.中断时传递 signal
            // 当前线程在 await 时若被中断，会收到 InterruptedException
            // 重新抛出前先 notEmpty.signal()：把“池非空”的唤醒机会转给其他等待线程，避免因本线程中断导致唤醒信号丢失
            notEmpty.signal(); // propagate to non-interrupted thread
            // 记录这类“因中断而补的 signal”次数
            notEmptySignalCount++;
            throw ie;
        }

        // 5.从池中取走一个连接（LIFO，后进先出，队列）
        // “空闲连接数” - 1
        decrementPoolingCount();
        // 空闲连接在 connections[0..poolingCount-1] 中，减 1 后 poolingCount 正好指向最后一个有效元素，即最后放进池的那条，
        // 实现 LIFO（后进先出），有利于缓存局部性（刚回收的连接可能仍较“热”）
        DruidConnectionHolder last = connections[poolingCount];
        // 槽位清空，便于 GC，并避免重复使用同一 holder
        connections[poolingCount] = null;

        // 记录从 startTime（getConnectionInternal 传入）到取到连接所经历的等待时间（单位实为毫秒，变量名为 Nanos 是命名问题），可用于统计或排查慢获取
        long waitNanos = System.currentTimeMillis() - startTime;
        last.setLastNotEmptyWaitNanos(waitNanos);

        // 把取到的 DruidConnectionHolder 返回给 getConnectionInternal，由上层包装成 DruidPooledConnection 并做 testOnBorrow 等处理
        return last;
    }

    private final void decrementPoolingCount() {
        poolingCount--;
    }

    private final void incrementPoolingCount() {
        poolingCount++;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (this.username == null
                && this.password == null
                && username != null
                && password != null) {
            this.username = username;
            this.password = password;

            return getConnection();
        }

        if (!StringUtils.equals(username, this.username)) {
            throw new UnsupportedOperationException("Not supported by DruidDataSource");
        }

        if (!StringUtils.equals(password, this.password)) {
            throw new UnsupportedOperationException("Not supported by DruidDataSource");
        }

        return getConnection();
    }

    public long getCreateCount() {
        lock.lock();
        try {
            return createCount;
        } finally {
            lock.unlock();
        }
    }

    public long getDestroyCount() {
        lock.lock();
        try {
            return destroyCount;
        } finally {
            lock.unlock();
        }
    }

    public long getConnectCount() {
        lock.lock();
        try {
            return connectCount;
        } finally {
            lock.unlock();
        }
    }

    public long getCloseCount() {
        return closeCount;
    }

    public long getConnectErrorCount() {
        return connectErrorCountUpdater.get(this);
    }

    @Override
    public int getPoolingCount() {
        lock.lock();
        try {
            return poolingCount;
        } finally {
            lock.unlock();
        }
    }

    public int getPoolingPeak() {
        lock.lock();
        try {
            return poolingPeak;
        } finally {
            lock.unlock();
        }
    }

    public Date getPoolingPeakTime() {
        if (poolingPeakTime <= 0) {
            return null;
        }

        return new Date(poolingPeakTime);
    }

    public long getRecycleCount() {
        return recycleCount;
    }

    public int getActiveCount() {
        lock.lock();
        try {
            return activeCount;
        } finally {
            lock.unlock();
        }
    }

    public void logStats() {
        final DruidDataSourceStatLogger statLogger = this.statLogger;
        if (statLogger == null) {
            return;
        }

        DruidDataSourceStatValue statValue = getStatValueAndReset();

        statLogger.log(statValue);
    }

    public DruidDataSourceStatValue getStatValueAndReset() {
        DruidDataSourceStatValue value = new DruidDataSourceStatValue();

        lock.lock();
        try {
            value.setPoolingCount(this.poolingCount);
            value.setPoolingPeak(this.poolingPeak);
            value.setPoolingPeakTime(this.poolingPeakTime);

            value.setActiveCount(this.activeCount);
            value.setActivePeak(this.activePeak);
            value.setActivePeakTime(this.activePeakTime);

            value.setConnectCount(this.connectCount);
            value.setCloseCount(this.closeCount);
            value.setWaitThreadCount(lock.getWaitQueueLength(notEmpty));
            value.setNotEmptyWaitCount(this.notEmptyWaitCount);
            value.setNotEmptyWaitNanos(this.notEmptyWaitNanos);
            value.setKeepAliveCheckCount(this.keepAliveCheckCount);

            // reset
            this.poolingPeak = 0;
            this.poolingPeakTime = 0;
            this.activePeak = 0;
            this.activePeakTime = 0;
            this.connectCount = 0;
            this.closeCount = 0;
            this.keepAliveCheckCount = 0;

            this.notEmptyWaitCount = 0;
            this.notEmptyWaitNanos = 0;
        } finally {
            lock.unlock();
        }

        value.setName(this.getName());
        value.setDbType(this.dbTypeName);
        value.setDriverClassName(this.getDriverClassName());

        value.setUrl(this.getUrl());
        value.setUserName(this.getUsername());
        value.setFilterClassNames(this.getFilterClassNames());

        value.setInitialSize(this.getInitialSize());
        value.setMinIdle(this.getMinIdle());
        value.setMaxActive(this.getMaxActive());

        value.setQueryTimeout(this.getQueryTimeout());
        value.setTransactionQueryTimeout(this.getTransactionQueryTimeout());
        value.setLoginTimeout(this.getLoginTimeout());
        value.setValidConnectionCheckerClassName(this.getValidConnectionCheckerClassName());
        value.setExceptionSorterClassName(this.getExceptionSorterClassName());

        value.setTestOnBorrow(this.testOnBorrow);
        value.setTestOnReturn(this.testOnReturn);
        value.setTestWhileIdle(this.testWhileIdle);

        value.setDefaultAutoCommit(this.isDefaultAutoCommit());

        if (defaultReadOnly != null) {
            value.setDefaultReadOnly(defaultReadOnly);
        }
        value.setDefaultTransactionIsolation(this.getDefaultTransactionIsolation());

        value.setLogicConnectErrorCount(connectErrorCountUpdater.getAndSet(this, 0));

        value.setPhysicalConnectCount(createCountUpdater.getAndSet(this, 0));
        value.setPhysicalCloseCount(destroyCountUpdater.getAndSet(this, 0));
        value.setPhysicalConnectErrorCount(createErrorCountUpdater.getAndSet(this, 0));

        value.setExecuteCount(this.getAndResetExecuteCount());
        value.setErrorCount(errorCountUpdater.getAndSet(this, 0));
        value.setCommitCount(commitCountUpdater.getAndSet(this, 0));
        value.setRollbackCount(rollbackCountUpdater.getAndSet(this, 0));

        value.setPstmtCacheHitCount(cachedPreparedStatementHitCountUpdater.getAndSet(this, 0));
        value.setPstmtCacheMissCount(cachedPreparedStatementMissCountUpdater.getAndSet(this, 0));

        value.setStartTransactionCount(startTransactionCountUpdater.getAndSet(this, 0));
        value.setTransactionHistogram(this.getTransactionHistogram().toArrayAndReset());

        value.setConnectionHoldTimeHistogram(this.getDataSourceStat().getConnectionHoldHistogram().toArrayAndReset());
        value.setRemoveAbandoned(this.isRemoveAbandoned());
        value.setClobOpenCount(this.getDataSourceStat().getClobOpenCountAndReset());
        value.setBlobOpenCount(this.getDataSourceStat().getBlobOpenCountAndReset());

        value.setSqlSkipCount(this.getDataSourceStat().getSkipSqlCountAndReset());
        value.setSqlList(this.getDataSourceStat().getSqlStatMapAndReset());

        return value;
    }

    public long getRemoveAbandonedCount() {
        return removeAbandonedCount;
    }

    /**
     * 将新建立的连接放入连接池
     * 在持锁下把连接包装成 DruidConnectionHolder 放入 connections，poolingCount++，并 notEmpty.signal() 唤醒一个在 pollLast 里等待的线程
     * @param physicalConnectionInfo 刚建好的物理连接信息
     * @return  true 表示已入池，false 表示未入池（调用方应关闭连接）
     */
    protected boolean put(PhysicalConnectionInfo physicalConnectionInfo) {
        // 声明池化连接持有者变量，用于包装物理连接及池引用
        DruidConnectionHolder holder;
        try {
            // 将连接包装成 DruidConnectionHolder
            holder = new DruidConnectionHolder(DruidDataSource.this, physicalConnectionInfo);
        } catch (SQLException ex) { // 捕获构造 DruidConnectionHolder 时的异常
            // 获取主锁，以便安全地操作 createTask 计数和任务清理
            lock.lock();
            try {
                // 若使用调度器建连，本次建连对应一个 CreateConnectionTask（由 createTaskId 标识）；构造 Holder 失败则从“进行中的任务”中清除该 task，并递减 createTaskCount，避免任务计数不一致
                if (createScheduler != null) {
                    clearCreateTask(physicalConnectionInfo.createTaskId);
                }
            } finally {
                // 无论是否清理任务，都释放锁
                lock.unlock();
            }
            // 记录构造 Holder 失败日志
            LOG.error("create connection holder error", ex);
            // 未入池，调用方应关闭 physicalConnectionInfo 里的物理连接。
            return false;
        }

        // 调用内部 put
        return put(holder, physicalConnectionInfo.createTaskId, false);
    }

    /**
     * 内部 put，将新建立的连接放入连接池
     * @param holder 池化连接持有者变量
     * @param createTaskId 连接对应的 createTaskId（调度器模式下用于 clearCreateTask）
     * @param checkExists 检查池中是否已存在该 holder
     * @return 示是否成功入池
     */
    private boolean put(DruidConnectionHolder holder, long createTaskId, boolean checkExists) {
        // 获取数据源主锁，后续读写 connections、poolingCount、activeCount、closed/closing 等均受此锁保护
        lock.lock();
        try {
            // 数据源正在关闭或已关闭时不再接受新连接入池，直接返回 false；调用方会关闭该物理连接
            if (this.closing || this.closed) {
                return false;
            }

            // 当前总连接数已达上限，不能再放入池
            if (activeCount + poolingCount >= maxActive) {
                // 若为调度器建连，清除本任务并递减 createTaskCount，保持任务计数正确
                if (createScheduler != null) {
                    clearCreateTask(createTaskId);
                }
                return false;
            }

            // 仅当调用方要求“检查是否已存在”时执行
            if (checkExists) {
                // 若池中已有该 holder 实例，视为重复，不再入池，返回 false。
                for (int i = 0; i < poolingCount; i++) {
                    if (connections[i] == holder) {
                        return false;
                    }
                }
            }

            // 将 holder 放入空闲池数组尾部；当前有效空闲连接下标为 [0, poolingCount-1]，新元素放在 poolingCount 位置
            connections[poolingCount] = holder;
            // poolingCount++，逻辑上“空闲连接数”加一，与 connections 数组一致
            incrementPoolingCount();

            // 若当前空闲数超过历史峰值，更新峰值及发生时间，供监控/统计使用
            if (poolingCount > poolingPeak) {
                poolingPeak = poolingCount;
                poolingPeakTime = System.currentTimeMillis();
            }

            // 唤醒一个在 notEmpty.await() 上等待的线程（即 pollLast 里“池空”时阻塞的取连接线程），使其有机会从池中取到刚放入的这条连接
            notEmpty.signal();
            // 统计“因池非空而发出的 signal”次数，用于监控
            notEmptySignalCount++;

            // 仅在使用调度器建连时执行以下逻辑
            if (createScheduler != null) {
                // 本连接已成功入池，从“进行中的建连任务”中移除该 taskId，并 createTaskCount--，避免重复计数。
                clearCreateTask(createTaskId);

                // 当前“池中空闲 + 正在建连的任务数”仍小于“正在等连接的线程数”，说明还有人在等、池仍不够
                if (poolingCount + createTaskCount < notEmptyWaitThreadCount) {
                    // 再触发一次建连（向 createScheduler 提交新任务或唤醒 CreateConnectionThread），尽快补足连接，减少等待时间。
                    emptySignal();
                }
            }
        } finally {
            // 无论正常返回还是提前 return false，都释放主锁，防止死锁
            lock.unlock();
        }
        // 表示 holder 已成功入池。
        return true;
    }

    public class CreateConnectionTask implements Runnable {
        private int errorCount;
        private boolean initTask;
        private final long taskId;

        public CreateConnectionTask() {
            taskId = createTaskIdSeedUpdater.getAndIncrement(DruidDataSource.this);
        }

        public CreateConnectionTask(boolean initTask) {
            taskId = createTaskIdSeedUpdater.getAndIncrement(DruidDataSource.this);
            this.initTask = initTask;
        }

        @Override
        public void run() {
            runInternal();
        }

        private void runInternal() {
            for (; ; ) {
                // addLast
                lock.lock();
                try {
                    if (closed || closing) {
                        clearCreateTask(taskId);
                        return;
                    }

                    boolean emptyWait = createError == null || poolingCount != 0;

                    if (emptyWait) {
                        // 必须存在线程等待，才创建连接
                        if (poolingCount >= notEmptyWaitThreadCount //
                                && (!(keepAlive && activeCount + poolingCount < minIdle)) // 在keepAlive场景不能放弃创建
                                && (!initTask) // 线程池初始化时的任务不能放弃创建
                                && !isFailContinuous() // failContinuous时不能放弃创建，否则会无法创建线程
                                && !isOnFatalError() // onFatalError时不能放弃创建，否则会无法创建线程
                        ) {
                            clearCreateTask(taskId);
                            return;
                        }
                    }

                    // 防止创建超过maxActive数量的连接
                    if (activeCount + poolingCount >= maxActive) {
                        clearCreateTask(taskId);
                        return;
                    }
                } finally {
                    lock.unlock();
                }

                PhysicalConnectionInfo physicalConnection = null;

                try {
                    physicalConnection = createPhysicalConnection();
                } catch (OutOfMemoryError e) {
                    LOG.error("create connection OutOfMemoryError, out memory. ", e);

                    errorCount++;
                    if (errorCount > connectionErrorRetryAttempts && timeBetweenConnectErrorMillis > 0) {
                        // fail over retry attempts
                        setFailContinuous(true);
                        if (failFast) {
                            lock.lock();
                            try {
                                notEmpty.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }

                        if (breakAfterAcquireFailure || closing || closed) {
                            lock.lock();
                            try {
                                clearCreateTask(taskId);
                            } finally {
                                lock.unlock();
                            }
                            return;
                        }

                        // reset errorCount
                        this.errorCount = 0;
                        createSchedulerFutures.put(this,
                                createScheduler.schedule(this, timeBetweenConnectErrorMillis, TimeUnit.MILLISECONDS));
                        return;
                    }
                } catch (SQLException e) {
                    LOG.error("create connection SQLException, url: " + sanitizedUrl(jdbcUrl), e);

                    errorCount++;
                    if (errorCount > connectionErrorRetryAttempts && timeBetweenConnectErrorMillis > 0) {
                        // fail over retry attempts
                        setFailContinuous(true);
                        if (failFast) {
                            lock.lock();
                            try {
                                notEmpty.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }

                        if (breakAfterAcquireFailure || closing || closed) {
                            lock.lock();
                            try {
                                clearCreateTask(taskId);
                            } finally {
                                lock.unlock();
                            }
                            return;
                        }

                        // reset errorCount
                        this.errorCount = 0;
                        createSchedulerFutures.put(this,
                                createScheduler.schedule(this, timeBetweenConnectErrorMillis, TimeUnit.MILLISECONDS));
                        return;
                    }
                } catch (RuntimeException e) {
                    LOG.error("create connection RuntimeException", e);
                    // unknown fatal exception
                    setFailContinuous(true);
                    continue;
                } catch (Error e) {
                    lock.lock();
                    try {
                        clearCreateTask(taskId);
                    } finally {
                        lock.unlock();
                    }
                    LOG.error("create connection Error", e);
                    // unknown fatal exception
                    setFailContinuous(true);
                    break;
                } catch (Throwable e) {
                    lock.lock();
                    try {
                        clearCreateTask(taskId);
                    } finally {
                        lock.unlock();
                    }

                    LOG.error("create connection unexpected error.", e);
                    break;
                }

                if (physicalConnection == null) {
                    continue;
                }

                physicalConnection.createTaskId = taskId;
                boolean result = put(physicalConnection);
                if (!result) {
                    JdbcUtils.close(physicalConnection.getPhysicalConnection());
                    LOG.info("put physical connection to pool failed.");
                }
                break;
            }
        }
    }

    /**
     * 建连线程（创建连接，并放入连接池中）
     *  1.作用：独立线程，专门跑建连循环，Druid 在 未配置 createScheduler 时的专用建连线程：在池未满时循环「等信号 → 建一条物理连接 → 放入池」，并通过 notEmpty.signal 唤醒在 pollLast 里等连接的线程
     *  2.线程创建时机：com.alibaba.druid.pool.DruidDataSource#init()。仅当 createScheduler == null 时，
     *  在 createAndStartCreatorThread() 里被创建并 start()；若配置了 createScheduler，则用 CreateConnectionTask + 线程池建连，不会起这个线程
     *  3.与 emptySignal 的关系：取连接时池空会调 emptySignal() → empty.signal()，本线程在 empty.await() 上被唤醒，然后去建连、put、再唤醒等连接的线程
     */
    public class CreateConnectionThread extends Thread {

        // 初始化完成门栓
        // run() 里第一行会 countDown()，DruidDataSource.init() 里会 getInitedLatch().await()，保证数据源 init 在「建连线程已启动并进入 run」之后再继续执行后续逻辑
        private final CountDownLatch initedLatch = new CountDownLatch(1);

        // 构造函数
        public CreateConnectionThread(String name) {
            // 线程名，如 Druid-ConnectionPool-Create-{identityHashCode}，便于排查
            super(name);
            // 设为守护线程，JVM 退出时不会因它未结束而阻塞。
            this.setDaemon(true);
        }

        // 供 DruidDataSource.init() 使用：createConnectionThread.getInitedLatch().await()，确保建连线程已经跑起来后再算 init 完成
        public CountDownLatch getInitedLatch() {
            return initedLatch;
        }

        public void run() {
            // 表示本线程已进入 run，DruidDataSource.init()里等待的 await() 可以返回，继续执行后续逻辑
            initedLatch.countDown();

            // 用于检测是否有新发生的连接丢弃
            long lastDiscardCount = 0;
            // 本线程内连续建连失败次数，用于与 connectionErrorRetryAttempts 比较，决定是否设 failContinuous、sleep、甚至退出
            int errorCount = 0;

            // 数据源未关闭且线程未被中断则一直循环；任一为 true 则退出 run，线程结束
            while (!closing && !closed && !Thread.currentThread().isInterrupted()) {
                // addLast
                try {
                    // 获取数据源主锁，等待过程中可被中断（如 close 时对 createConnectionThread.interrupt()）
                    lock.lockInterruptibly();
                } catch (InterruptedException e2) {
                    // 被中断则直接退出 while，线程结束，便于关闭时快速收尾。
                    break;
                }

                // 当前全局「已丢弃连接」总数（其它线程回收/销毁时可能增加）
                long discardCount = DruidDataSource.this.discardCount;
                // 本轮回合相比上一轮是否有新的丢弃发生
                boolean discardChanged = discardCount - lastDiscardCount > 0;
                // 下次循环用本轮值做比较
                lastDiscardCount = discardCount;

                try {

                    /**
                     * 是否允许在本轮等 empty
                     * emptyWait 为 true 表示：从是否需要建连的角度，允许考虑先等 empty 信号再建（即可能在本轮进入 empty.await()）
                     * 如下三种情况都建连来算先等待empty信号再建连
                     * createError == null：没有最近建连错误；
                     * poolingCount != 0：池里还有空闲连接；
                     * discardChanged：刚有连接被丢弃。
                     */
                    boolean emptyWait = createError == null
                            || poolingCount != 0
                            || discardChanged;

                    /**
                     * 将emptyWait置成false
                     * 条件：当前等待empty信号（emptyWait = true） 且 异步初始化（asyncInit == true） 且 尚未建满 initialSize（createCount < initialSize）
                     * 目的：异步初始化阶段要尽快把 initialSize 建满，不在这时去 empty.await()，而是直接往下建连
                     */
                    if (emptyWait
                            && asyncInit && createCount < initialSize) {
                        emptyWait = false;
                    }

                    /**
                     * 等待empty信号
                     * 满足条件：
                     * 1.emptyWait = true
                     * 2.池里空闲数 ≥ 正在等连接的线程数：poolingCount >= notEmptyWaitThreadCount
                     * 3。不是keepAlive 且 当前连接总数 < minIdle： (!(keepAlive && activeCount + poolingCount < minIdle)
                     * 4.没有处于连续失败状态：!isFailContinuous()
                     */
                    if (emptyWait) {
                        if (poolingCount >= notEmptyWaitThreadCount
                                && (!(keepAlive && activeCount + poolingCount < minIdle))
                                && !isFailContinuous()
                        ) {
                            empty.await();
                        }
                    }

                    // 防止创建超过maxActive数量的连接
                    // 总连接数已达上限，不能再建
                    if (activeCount + poolingCount >= maxActive) {
                        empty.await();
                        // 唤醒后直接下一轮 while，重新判断池是否仍满、是否要再 await
                        continue;
                    }
                } catch (InterruptedException e) {  // 在 empty.await() 或 lockInterruptibly() 时被中断
                    lastCreateError = e;
                    // 记录给监控/排查用
                    lastErrorTimeMillis = System.currentTimeMillis();

                    // 非正常关闭导致的中断才打 error 日志
                    if ((!closing) && (!closed)) {
                        LOG.error("create connection thread interrupted, url: " + sanitizedUrl(jdbcUrl), e);
                    }
                    // 退出 while，线程结束
                    break;
                } finally {
                    // 无论 try 里是正常走出、await、还是异常，都释放锁，避免死锁
                    lock.unlock();
                }

                // 建连

                // 声明本轮回合要建的一条物理连接信息connection
                PhysicalConnectionInfo connection = null;

                try {
                    // 在未持锁状态下执行通过DruidAbstractDataSource#createPhysicalConnection()方法建连
                    connection = createPhysicalConnection();
                } catch (SQLException e) {
                    // 建连异常日志
                    LOG.error("create connection SQLException, url: " + sanitizedUrl(jdbcUrl) + ", errorCode " + e.getErrorCode()
                        + ", state " + e.getSQLState(), e);

                    // 本线程连续建连失败次数 + 1
                    errorCount++;
                    // 超过配置的重试次数且配置了错误间隔
                    if (errorCount > connectionErrorRetryAttempts && timeBetweenConnectErrorMillis > 0) {
                        // fail over retry attempts
                        // 标记连续建连失败
                        setFailContinuous(true);

                        // 让所有在 pollLast 里等连接的人立刻被唤醒，拿到「建连失败」的结果（如 DataSourceNotAvailableException），而不是一直等
                        if (failFast) {
                            lock.lock();
                            try {
                                notEmpty.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }

                        // 配置为失败后退出、或正在关闭，则 break 结束线程
                        if (breakAfterAcquireFailure || closing || closed) {
                            break;
                        }

                        // 在再次重试前休眠，避免疯狂重试打爆数据库或日志
                        try {
                            Thread.sleep(timeBetweenConnectErrorMillis);
                        } catch (InterruptedException interruptEx) {
                            // 休眠中被中断则 break，结束线程。
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    // 记日志、设 failContinuous，continue 下一轮循环，不退出线程
                    LOG.error("create connection RuntimeException", e);
                    setFailContinuous(true);
                    continue;
                } catch (Error e) {
                    // 记日志后 break，线程退出（OutOfMemoryError 等不宜继续跑）
                    LOG.error("create connection Error", e);
                    break;
                }

                // 若上面某分支把 connection 置 null 或未赋值（理论上少见），直接下一轮，不执行 put
                if (connection == null) {
                    continue;
                }

                // 将新建立的连接放入连接池
                boolean result = put(connection);
                // 池已满或已关闭等导致 put 失败，则关闭刚建的物理连接，避免泄漏
                if (!result) {
                    JdbcUtils.close(connection.getPhysicalConnection());
                    LOG.info("put physical connection to pool failed.");
                }

                // 建连并成功 put 后清零连续失败次数，下次失败重新从 1 计
                // reset errorCount
                errorCount = 0;
            }
        }
    }

    /**
     *  Druid 的销毁/回收线程
     *  1.作用：按固定间隔醒来，执行 DestroyTask（内部调用 shrink 和 removeAbandoned），做空闲连接回收、超时剔除、保活检测以及泄漏连接回收
     *  2.线程创建时机：仅当 destroyScheduler == null 时才会创建并启动本线程；若配置了 destroyScheduler，则用调度器 scheduleAtFixedRate(destroyTask, period, period)
     *  定时跑 DestroyTask，不会起 DestroyConnectionThread
     */
    public class DestroyConnectionThread extends Thread {
        // 初始化门栓
        private final CountDownLatch initedLatch = new CountDownLatch(1);

        public DestroyConnectionThread(String name) {
            // 线程名称
            super(name);
            // 守护线程，进程退出时不会因本线程未结束而阻塞
            this.setDaemon(true);
        }

        // 供数据源 init 等待销毁线程就绪用
        public CountDownLatch getInitedLatch() {
            return initedLatch;
        }

        public void run() {
            // 表示销毁线程已启动，init 里等待 initedLatch 的可以返回
            initedLatch.countDown();

            // 只要当前线程未被中断就一直循环；被 interrupt 则退出
            for (; !Thread.currentThread().isInterrupted(); ) {
                // 从前面开始删除
                try {
                    // 数据源已关闭或正在关闭
                    if (closed || closing) {
                        // 直接退出循环，线程结束，不再执行 DestroyTask
                        break;
                    }

                    // 判断“回收/检测”间隔配置，若配置了大于 0 的间隔，用该值睡眠；否则用默认 1 秒
                    if (timeBetweenEvictionRunsMillis > 0) {
                        Thread.sleep(timeBetweenEvictionRunsMillis);
                    } else {
                        Thread.sleep(1000); //
                    }

                    // 睡眠后被中断，sleep 可能清除中断标志，这里再查一次，若被中断则退出循环
                    if (Thread.interrupted()) {
                        break;
                    }

                    // 执行销毁任务
                    destroyTask.run();
                } catch (InterruptedException e) {  // 睡眠或别处抛出的中断
                    // 收到 InterruptedException 直接退出循环，线程结束。
                    break;
                }
            }
        }

    }

    public class DestroyTask implements Runnable {
        public DestroyTask() {
        }

        @Override
        public void run() {
            // 空闲回收、超时剔除、保活
            shrink(true, keepAlive);

            // 泄漏连接回收
            if (isRemoveAbandoned()) {
                removeAbandoned();
            }
        }

    }

    public class LogStatsThread extends Thread {
        public LogStatsThread(String name) {
            super(name);
            this.setDaemon(true);
        }

        public void run() {
            try {
                for (; ; ) {
                    try {
                        logStats();
                    } catch (Exception e) {
                        LOG.error("logStats error", e);
                    }

                    Thread.sleep(timeBetweenLogStatsMillis);
                }
            } catch (InterruptedException e) {
                // skip
            }
        }
    }

    /**
     * 在开启 removeAbandoned 时，由 DestroyTask 周期性调用，扫描 activeConnections（已借出未归还的连接），把借出时间超过 removeAbandonedTimeoutMillis 且当前未在执行 SQL 的连接视为“泄漏”，
     * 从 activeConnections 移除、关闭连接并调用 abandond()，可选打泄漏日志（借出栈、当前线程状态与栈），最后返回本轮回收数量。
     * @return
     */
    public int removeAbandoned() {
        // 本方法本次回收的泄漏连接数
        int removeCount = 0;

        // 没有“已借出”的连接（未开 removeAbandoned 时不会往 activeConnections 放，或都已还），直接返回 0，不做后续扫描和关闭
        if (activeConnections.size() == 0) {
            return removeCount;
        }

        // 当前纳秒时间，用来和借出时间算“借出时长”,与 removeAbandonedTimeoutMillis 比较
        long currrentNanos = System.nanoTime();

        // 本轮回合判定为“泄漏”的连接，先放进列表，在释放 activeConnectionLock 之后再统一关闭和打日志，避免在持锁时做 I/O
        List<DruidPooledConnection> abandonedList = new ArrayList<DruidPooledConnection>();

        // 加锁
        activeConnectionLock.lock();
        try {
            // 遍历“已借出”的连接
            Iterator<DruidPooledConnection> iter = activeConnections.keySet().iterator();
            for (; iter.hasNext(); ) {
                DruidPooledConnection pooledConnection = iter.next();

                // 正在运行的直接跳过
                if (pooledConnection.isRunning()) {
                    continue;
                }

                // 借出时长
                long timeMillis = (currrentNanos - pooledConnection.getConnectedTimeNano()) / (1000 * 1000);

                // 未在跑 SQL 且借出超时的连接从 activeConnections 移入 abandonedList
                if (timeMillis >= removeAbandonedTimeoutMillis) {
                    iter.remove();
                    pooledConnection.setTraceEnable(false);
                    abandonedList.add(pooledConnection);
                }
            }
        } finally {
            // 解锁
            activeConnectionLock.unlock();
        }

        // 存在“泄漏”的连接
        if (abandonedList.size() > 0) {
            // 遍历“泄漏”的连接
            for (DruidPooledConnection pooledConnection : abandonedList) {
                // 使用连接自己的锁，避免和该连接上其它操作（如 close、statement）并发冲突
                final ReentrantLock lock = pooledConnection.lock;
                // 锁
                lock.lock();
                try {
                    // 若连接已被标记为禁用（例如已被关闭或 abandond 过），continue 跳过，不再关第二次
                    if (pooledConnection.isDisable()) {
                        continue;
                    }
                } finally {
                    // 解锁
                    lock.unlock();
                }

                // 关闭连接，并进行泄露回收（物理连接从“借出”变回“空闲池”）
                JdbcUtils.close(pooledConnection);
               // 给当前这个 DruidPooledConnection 包装打上“已按泄漏回收处理”的标记
                pooledConnection.abandond();
                // 全局统计“泄漏回收”次数
                removeAbandonedCount++;
                // 本方法本次回收数，用于 return
                removeCount++;

                //  根据配置打泄漏日志
                if (isLogAbandoned()) {
                    StringBuilder buf = new StringBuilder();
                    buf.append("abandon connection, owner thread: ");
                    buf.append(pooledConnection.getOwnerThread().getName());
                    buf.append(", connected at : ");
                    buf.append(pooledConnection.getConnectedTimeMillis());
                    buf.append(", open stackTrace\n");

                    StackTraceElement[] trace = pooledConnection.getConnectStackTrace();
                    for (int i = 0; i < trace.length; i++) {
                        buf.append("\tat ");
                        buf.append(trace[i].toString());
                        buf.append("\n");
                    }

                    buf.append("ownerThread current state is ")
                            .append(pooledConnection.getOwnerThread().getState())
                            .append(", current stackTrace\n");
                    trace = pooledConnection.getOwnerThread().getStackTrace();
                    for (int i = 0; i < trace.length; i++) {
                        buf.append("\tat ");
                        buf.append(trace[i].toString());
                        buf.append("\n");
                    }

                    LOG.error(buf.toString());
                }
            }
        }

        // 返回本方法本次回收数
        return removeCount;
    }

    /**
     * Instance key
     */
    protected String instanceKey;

    public Reference getReference() throws NamingException {
        final String className = getClass().getName();
        final String factoryName = className + "Factory"; // XXX: not robust
        Reference ref = new Reference(className, factoryName, null);
        ref.add(new StringRefAddr("instanceKey", instanceKey));
        ref.add(new StringRefAddr("url", this.getUrl()));
        ref.add(new StringRefAddr("username", this.getUsername()));
        ref.add(new StringRefAddr("password", this.getPassword()));
        // TODO ADD OTHER PROPERTIES
        return ref;
    }

    @Override
    public List<String> getFilterClassNames() {
        List<String> names = new ArrayList<String>();
        for (Filter filter : filters) {
            names.add(filter.getClass().getName());
        }
        return names;
    }

    public int getRawDriverMajorVersion() {
        int version = -1;
        if (this.driver != null) {
            version = driver.getMajorVersion();
        }
        return version;
    }

    public int getRawDriverMinorVersion() {
        int version = -1;
        if (this.driver != null) {
            version = driver.getMinorVersion();
        }
        return version;
    }

    public String getProperties() {
        Properties properties = new Properties();
        properties.putAll(connectProperties);
        if (properties.containsKey("password")) {
            properties.put("password", "******");
        }
        return properties.toString();
    }

    @Override
    public void shrink() {
        shrink(false, false);
    }

    public void shrink(boolean checkTime) {
        shrink(checkTime, keepAlive);
    }

    /**
     * 在持锁下扫描空闲池 connections，根据 checkTime / keepAlive 和各项时间阈值，把需要剔除的连接放进 evictConnections、
     * 需要保活检测的放进 keepAliveConnections；然后压缩 connections、物理关闭被剔除的连接、对保活连接做校验（通过则 put 回池，失败则丢弃）；
     * 若池低于 minIdle 或发生致命错误增量则 emptySignal 触发补连
     * @param checkTime 是否按时间剔除
     * @param keepAlive 是否保活，为 true 时，对空闲够久的连接会定期做一次有效性检测（执行校验 SQL 或 checker），无效就关掉并丢弃，有效就更新 lastKeepTimeMillis 并放回池
     */
    public void shrink(boolean checkTime, boolean keepAlive) {
        // 没有空闲连接，无需收缩，直接返回
        if (poolingCount == 0) {
            return;
        }

        // 获取主锁，可被中断；被中断则 return，不执行本次 shrink
        final Lock lock = this.lock;
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            return;
        }

        // 后面若发现池低于 minIdle 或保活丢弃导致需补连，会置 true，最后用于 emptySignal唤醒建连线程进行连接创建
        boolean needFill = false;
        // 本轮回合要剔除的连接数（先放入 evictConnections）
        int evictCount = 0;
        // 本轮回合要做保活检测的连接数（先放入 keepAliveConnections）
        int keepAliveCount = 0;
        // 自上次 shrink 以来新增的致命错误次数；非 0 时部分连接会先进入保活队列，且最后可能触发 emptySignal
        int fatalErrorIncrement = fatalErrorCount - fatalErrorCountLastShrink;
        // 记录本次 shrink 时的致命错误计数，供下次算增量
        fatalErrorCountLastShrink = fatalErrorCount;

        try {
            // 数据源未初始化完成则直接 return
            if (!inited) {
                return;
            }

            // 若只按数量收缩（不按时间），前 checkCount 个空闲连接可被剔除，使池从 poolingCount 降到 minIdle
            final int checkCount = poolingCount - minIdle;
            // 当前时间，用于算空闲时长、物理连接存活时长等
            final long currentTimeMillis = System.currentTimeMillis();
            // 压缩后“保留在池”的连接在新 connections 中的下一个写入下标
            // remaining is the position of the next connection should be retained in the pool.
            int remaining = 0;
            // 当前遍历到的空闲连接下标
            int i = 0;
            // 对 connections[i] 依次做以下判断，把连接归入：保留并可能保活、放入 evictConnections（剔除）、放入 keepAliveConnections（保活检测）；或提前 break 结束遍历
            for (; i < poolingCount; ++i) {
                // 获取线程连接
                DruidConnectionHolder connection = connections[i];

                // 处于致命错误状态，或自上次 shrink 以来有新的致命错误，且该连接的建立时间早于最近一次致命错误时间：先放入保活队列
                if ((onFatalError || fatalErrorIncrement > 0) && (lastFatalErrorTimeMillis > connection.connectTimeMillis)) {
                    // 认为该连接可能是在“坏状态”前建的，先放进 keepAliveConnections 做一次校验；
                    // 本轮回合不参与后面的“按时间剔除”和“直接保留”逻辑，交给保活逻辑统一校验或丢弃
                    keepAliveConnections[keepAliveCount++] = connection;
                    continue;
                }

                if (checkTime) { // 按时间剔除
                    // 物理超时
                    if (phyTimeoutMillis > 0) { // phyTimeoutMillis > 0
                        long phyConnectTimeMillis = currentTimeMillis - connection.connectTimeMillis;
                        if (phyConnectTimeMillis > phyTimeoutMillis) {  // 连接存活时间 > phyTimeoutMillis
                            // 连接物理存在时间过长，直接放入 evictConnections，后续进行剔除
                            evictConnections[evictCount++] = connection;
                            continue;
                        }
                    }

                    // 该连接在池中的空闲时长
                    long idleMillis = currentTimeMillis - connection.lastActiveTimeMillis;

                    // 空闲时间很短，既不到“可剔除”阈值，也不到“要保活”间隔；
                    // 且 connections 按 lastActiveTime 有序（先处理的更空闲），后面连接更新，可直接 break 不再扫
                    if (idleMillis < minEvictableIdleTimeMillis
                            && idleMillis < keepAliveBetweenTimeMillis) {
                        break;
                    }

                    // 空闲超过最小可剔除时间
                    if (idleMillis >= minEvictableIdleTimeMillis) {
                        if (i < checkCount) {   // 再“数量收缩”范围内（前 checkCount 个）
                            // 放入 evictConnections 剔除
                            evictConnections[evictCount++] = connection;
                            continue;
                        } else if (idleMillis > maxEvictableIdleTimeMillis) {   // 超过最大可空闲时间
                            // 放入 evictConnections 剔除
                            evictConnections[evictCount++] = connection;
                            continue;
                        }
                    }

                    // 保活（需要对空闲够久的连接会定期做一次有效性检测） 且 空闲时间大于保活间间隔 且 上次保活时间间隔大于保活间隔时间
                    if (keepAlive && idleMillis >= keepAliveBetweenTimeMillis
                            && currentTimeMillis - connection.lastKeepTimeMillis >= keepAliveBetweenTimeMillis) {
                        // 放入 keepAliveConnections，后面统一做校验
                        keepAliveConnections[keepAliveCount++] = connection;
                    } else {
                        if (i != remaining) {
                            // 该连接保留在池中并参与后续压缩
                            // move the connection to the new position for retaining it in the pool.
                            connections[remaining] = connection;
                        }
                        remaining++;
                    }
                } else {
                    if (i < checkCount) {   // 在“多出来的”那部分里，放入 evictConnections 剔除，使池从 poolingCount 降到 minIdle
                        evictConnections[evictCount++] = connection;
                    } else {    // 已保留够 minIdle 个，后面不用再看，break
                        break;
                    }
                }
            }

            // shrink connections by HotSpot intrinsic function _arraycopy for performance optimization.
            // 本轮回合从池中“移出”的连接数（剔除 + 拿去保活）
            int removeCount = evictCount + keepAliveCount;
            if (removeCount > 0) {
                // 循环 break 时尚未被处理的连接数（从 i 到 poolingCount-1），这些连接是“保留”的
                int breakedCount = poolingCount - i;
                if (breakedCount > 0) {
                    // 把未处理的那段保留连接搬到新位置 [remaining, remaining+breakedCount)，前面 [0, remaining) 已在循环里填好
                    // retains the connections that start at the break position.
                    System.arraycopy(connections, i, connections, remaining, breakedCount);
                    // 压缩后有效连接数 = remaining
                    remaining += breakedCount;
                }
                // 把被移出的槽位清空（用 nullConnections 的 null 覆盖），避免残留引用。
                // clean the old references of the connections that have been moved forward to the new positions.
                System.arraycopy(nullConnections, 0, connections, remaining, removeCount);
                // 更新空闲连接数
                poolingCount -= removeCount;
            }
            // 统计本轮回合做保活检测的数量
            keepAliveCheckCount += keepAliveCount;

            // 若开启 keepAlive 且 当前池总量 < minIdle，标记需要补连
            if (keepAlive && poolingCount + activeCount < minIdle) {
                needFill = true;
            }
        } finally {
            // 无论是否移除连接，都释放锁
            lock.unlock();
        }

        // 物理关闭被剔除的连接
        if (evictCount > 0) {
            // 对 evictConnections 里每个 holder 取底层 Connection 并 close，destroyCount 加一
            for (int i = 0; i < evictCount; ++i) {
                DruidConnectionHolder item = evictConnections[i];
                Connection connection = item.getConnection();
                JdbcUtils.close(connection);
                destroyCountUpdater.incrementAndGet(this);
            }
            // 最后用 nullConnections 清空 evictConnections 槽位，便于下次复用
            // use HotSpot intrinsic function _arraycopy for performance optimization.
            System.arraycopy(nullConnections, 0, evictConnections, 0, evictConnections.length);
        }

        // 保活检测与回池/丢弃
        if (keepAliveCount > 0) {
            // 倒序遍历，从 keepAliveCount-1 到 0，保持与放入顺序一致（“先放入的先处理”）
            // keep order
            for (int i = keepAliveCount - 1; i >= 0; --i) {
                DruidConnectionHolder holder = keepAliveConnections[i];
                Connection connection = holder.getConnection();
                holder.incrementKeepAliveCheckCount();

                boolean validate = false;
                try {
                    // 用 ValidConnectionChecker 或 validationQuery 校验连接是否有效；抛异常则 validate = false。
                    this.validateConnection(connection);
                    validate = true;
                } catch (Throwable error) {
                    keepAliveCheckErrorLast = error;
                    keepAliveCheckErrorCountUpdater.incrementAndGet(this);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("keepAliveErr", error);
                    }
                }

                boolean discard = !validate;
                // 更新 lastKeepTimeMillis，put(holder, 0L, true) 把连接放回池；若 put 失败（池满/关闭等）则 discard = true。
                if (validate) {
                    holder.lastKeepTimeMillis = System.currentTimeMillis();
                    boolean putOk = put(holder, 0L, true);
                    if (!putOk) {
                        discard = true;
                    }
                }

                // 关闭底层 connection 和 holder.socket，持锁设置 holder.discard = true、discardCount++；若此时池低于 minIdle 则 needFill = true。
                if (discard) {
                    try {
                        connection.close();
                    } catch (Exception error) {
                        discardErrorLast = error;
                        discardErrorCountUpdater.incrementAndGet(DruidDataSource.this);
                        if (LOG.isErrorEnabled()) {
                            LOG.error("discard connection error", error);
                        }
                    }

                    if (holder.socket != null) {
                        try {
                            holder.socket.close();
                        } catch (Exception error) {
                            discardErrorLast = error;
                            discardErrorCountUpdater.incrementAndGet(DruidDataSource.this);
                            if (LOG.isErrorEnabled()) {
                                LOG.error("discard connection error", error);
                            }
                        }
                    }

                    lock.lock();
                    try {
                        holder.discard = true;
                        discardCount++;

                        // 当前“已经有的 + 正在路上的”连接数（估算） 小于 希望池里至少保留的空闲数 minIdle
                        if (activeCount + poolingCount + createTaskCount < minIdle) {
                            needFill = true;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
            this.getDataSourceStat().addKeepAliveCheckCount(keepAliveCount);
            // 用 nullConnections 清空数组，供下次 shrink 复用。
            // use HotSpot intrinsic function _arraycopy for performance optimization.
            System.arraycopy(nullConnections, 0, keepAliveConnections, 0, keepAliveConnections.length);
        }

        // 补连
        if (needFill) {
            lock.lock();
            try {
                int fillCount = minIdle - (activeCount + poolingCount + createTaskCount);
                emptySignal(fillCount);
            } finally {
                lock.unlock();
            }
        } else if (fatalErrorIncrement > 0) {
            lock.lock();
            try {
                emptySignal();
            } finally {
                lock.unlock();
            }
        }
    }

    public int getWaitThreadCount() {
        lock.lock();
        try {
            return lock.getWaitQueueLength(notEmpty);
        } finally {
            lock.unlock();
        }
    }

    public long getNotEmptyWaitCount() {
        return notEmptyWaitCount;
    }

    public int getNotEmptyWaitThreadCount() {
        lock.lock();
        try {
            return notEmptyWaitThreadCount;
        } finally {
            lock.unlock();
        }
    }

    public int getNotEmptyWaitThreadPeak() {
        lock.lock();
        try {
            return notEmptyWaitThreadPeak;
        } finally {
            lock.unlock();
        }
    }

    public long getNotEmptySignalCount() {
        return notEmptySignalCount;
    }

    public long getNotEmptyWaitMillis() {
        return notEmptyWaitNanos / (1000 * 1000);
    }

    public long getNotEmptyWaitNanos() {
        return notEmptyWaitNanos;
    }

    public int getLockQueueLength() {
        return lock.getQueueLength();
    }

    public int getActivePeak() {
        return activePeak;
    }

    public Date getActivePeakTime() {
        if (activePeakTime <= 0) {
            return null;
        }

        return new Date(activePeakTime);
    }

    public String dump() {
        lock.lock();
        try {
            return this.toString();
        } finally {
            lock.unlock();
        }
    }

    public long getErrorCount() {
        return this.errorCount;
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();

        buf.append("{");

        buf.append("\n\tCreateTime:\"");
        buf.append(Utils.toString(getCreatedTime()));
        buf.append("\"");

        buf.append(",\n\tActiveCount:");
        buf.append(getActiveCount());

        buf.append(",\n\tPoolingCount:");
        buf.append(getPoolingCount());

        buf.append(",\n\tCreateCount:");
        buf.append(getCreateCount());

        buf.append(",\n\tDestroyCount:");
        buf.append(getDestroyCount());

        buf.append(",\n\tCloseCount:");
        buf.append(getCloseCount());

        buf.append(",\n\tConnectCount:");
        buf.append(getConnectCount());

        buf.append(",\n\tConnections:[");
        for (int i = 0; i < poolingCount; ++i) {
            DruidConnectionHolder conn = connections[i];
            if (conn != null) {
                if (i != 0) {
                    buf.append(",");
                }
                buf.append("\n\t\t");
                buf.append(conn.toString());
            }
        }
        buf.append("\n\t]");

        buf.append("\n}");

        if (this.isPoolPreparedStatements()) {
            buf.append("\n\n[");
            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder conn = connections[i];
                if (conn != null) {
                    if (i != 0) {
                        buf.append(",");
                    }
                    buf.append("\n\t{\n\tID:");
                    buf.append(System.identityHashCode(conn.getConnection()));
                    PreparedStatementPool pool = conn.getStatementPool();

                    buf.append(", \n\tpoolStatements:[");

                    int entryIndex = 0;
                    try {
                        for (Map.Entry<PreparedStatementKey, PreparedStatementHolder> entry : pool.getMap().entrySet()) {
                            if (entryIndex != 0) {
                                buf.append(",");
                            }
                            buf.append("\n\t\t{hitCount:");
                            buf.append(entry.getValue().getHitCount());
                            buf.append(",sql:\"");
                            buf.append(entry.getKey().getSql());
                            buf.append("\"");
                            buf.append("\t}");

                            entryIndex++;
                        }
                    } catch (ConcurrentModificationException e) {
                        // skip ..
                    }

                    buf.append("\n\t\t]");

                    buf.append("\n\t}");
                }
            }
            buf.append("\n]");
        }

        return buf.toString();
    }

    public List<Map<String, Object>> getPoolingConnectionInfo() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        lock.lock();
        try {
            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder connHolder = connections[i];
                Connection conn = connHolder.getConnection();

                Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put("id", System.identityHashCode(conn));
                map.put("connectionId", connHolder.getConnectionId());
                map.put("useCount", connHolder.getUseCount());
                if (connHolder.lastActiveTimeMillis > 0) {
                    map.put("lastActiveTime", new Date(connHolder.lastActiveTimeMillis));
                }
                if (connHolder.lastKeepTimeMillis > 0) {
                    map.put("lastKeepTimeMillis", new Date(connHolder.lastKeepTimeMillis));
                }
                map.put("connectTime", new Date(connHolder.getTimeMillis()));
                map.put("holdability", connHolder.getUnderlyingHoldability());
                map.put("transactionIsolation", connHolder.getUnderlyingTransactionIsolation());
                map.put("autoCommit", connHolder.underlyingAutoCommit);
                map.put("readoOnly", connHolder.isUnderlyingReadOnly());

                if (connHolder.isPoolPreparedStatements()) {
                    List<Map<String, Object>> stmtCache = new ArrayList<Map<String, Object>>();
                    PreparedStatementPool stmtPool = connHolder.getStatementPool();
                    for (PreparedStatementHolder stmtHolder : stmtPool.getMap().values()) {
                        Map<String, Object> stmtInfo = new LinkedHashMap<String, Object>();

                        stmtInfo.put("sql", stmtHolder.key.getSql());
                        stmtInfo.put("defaultRowPrefetch", stmtHolder.getDefaultRowPrefetch());
                        stmtInfo.put("rowPrefetch", stmtHolder.getRowPrefetch());
                        stmtInfo.put("hitCount", stmtHolder.getHitCount());

                        stmtCache.add(stmtInfo);
                    }

                    map.put("pscache", stmtCache);
                }
                map.put("keepAliveCheckCount", connHolder.getKeepAliveCheckCount());

                list.add(map);
            }
        } finally {
            lock.unlock();
        }
        return list;
    }

    public void logTransaction(TransactionInfo info) {
        long transactionMillis = info.getEndTimeMillis() - info.getStartTimeMillis();
        if (transactionThresholdMillis > 0 && transactionMillis > transactionThresholdMillis) {
            StringBuilder buf = new StringBuilder();
            buf.append("long time transaction, take ");
            buf.append(transactionMillis);
            buf.append(" ms : ");
            for (String sql : info.getSqlList()) {
                buf.append(sql);
                buf.append(";");
            }
            LOG.error(buf.toString(), new TransactionTimeoutException());
        }
    }

    @Override
    public String getVersion() {
        return VERSION.getVersionNumber();
    }

    @Override
    public JdbcDataSourceStat getDataSourceStat() {
        return dataSourceStat;
    }

    public Object clone() {
        return cloneDruidDataSource();
    }

    public DruidDataSource cloneDruidDataSource() {
        DruidDataSource x = new DruidDataSource();

        cloneTo(x);

        return x;
    }

    public Map<String, Object> getStatDataForMBean() {
        try {
            Map<String, Object> map = new HashMap<String, Object>();

            // 0 - 4
            map.put("Name", this.getName());
            map.put("URL", this.getUrl());
            map.put("CreateCount", this.getCreateCount());
            map.put("DestroyCount", this.getDestroyCount());
            map.put("ConnectCount", this.getConnectCount());

            // 5 - 9
            map.put("CloseCount", this.getCloseCount());
            map.put("ActiveCount", this.getActiveCount());
            map.put("PoolingCount", this.getPoolingCount());
            map.put("LockQueueLength", this.getLockQueueLength());
            map.put("WaitThreadCount", this.getNotEmptyWaitThreadCount());

            // 10 - 14
            map.put("InitialSize", this.getInitialSize());
            map.put("MaxActive", this.getMaxActive());
            map.put("MinIdle", this.getMinIdle());
            map.put("PoolPreparedStatements", this.isPoolPreparedStatements());
            map.put("TestOnBorrow", this.isTestOnBorrow());

            // 15 - 19
            map.put("TestOnReturn", this.isTestOnReturn());
            map.put("MinEvictableIdleTimeMillis", this.minEvictableIdleTimeMillis);
            map.put("ConnectErrorCount", this.getConnectErrorCount());
            map.put("CreateTimespanMillis", this.getCreateTimespanMillis());
            map.put("DbType", this.dbTypeName);

            // 20 - 24
            map.put("ValidationQuery", this.getValidationQuery());
            map.put("ValidationQueryTimeout", this.getValidationQueryTimeout());
            map.put("DriverClassName", this.getDriverClassName());
            map.put("Username", this.getUsername());
            map.put("RemoveAbandonedCount", this.getRemoveAbandonedCount());

            // 25 - 29
            map.put("NotEmptyWaitCount", this.getNotEmptyWaitCount());
            map.put("NotEmptyWaitNanos", this.getNotEmptyWaitNanos());
            map.put("ErrorCount", this.getErrorCount());
            map.put("ReusePreparedStatementCount", this.getCachedPreparedStatementHitCount());
            map.put("StartTransactionCount", this.getStartTransactionCount());

            // 30 - 34
            map.put("CommitCount", this.getCommitCount());
            map.put("RollbackCount", this.getRollbackCount());
            map.put("LastError", JMXUtils.getErrorCompositeData(this.getLastError()));
            map.put("LastCreateError", JMXUtils.getErrorCompositeData(this.getLastCreateError()));
            map.put("PreparedStatementCacheDeleteCount", this.getCachedPreparedStatementDeleteCount());

            // 35 - 39
            map.put("PreparedStatementCacheAccessCount", this.getCachedPreparedStatementAccessCount());
            map.put("PreparedStatementCacheMissCount", this.getCachedPreparedStatementMissCount());
            map.put("PreparedStatementCacheHitCount", this.getCachedPreparedStatementHitCount());
            map.put("PreparedStatementCacheCurrentCount", this.getCachedPreparedStatementCount());
            map.put("Version", this.getVersion());

            // 40 -
            map.put("LastErrorTime", this.getLastErrorTime());
            map.put("LastCreateErrorTime", this.getLastCreateErrorTime());
            map.put("CreateErrorCount", this.getCreateErrorCount());
            map.put("DiscardCount", this.getDiscardCount());
            map.put("ExecuteQueryCount", this.getExecuteQueryCount());

            map.put("ExecuteUpdateCount", this.getExecuteUpdateCount());
            map.put("InitStackTrace", this.getInitStackTrace());

            return map;
        } catch (JMException ex) {
            throw new IllegalStateException("getStatData error", ex);
        }
    }

    public Map<String, Object> getStatData() {
        final int activeCount;
        final int activePeak;
        final Date activePeakTime;

        final int poolingCount;
        final int poolingPeak;
        final Date poolingPeakTime;

        final long connectCount;
        final long closeCount;

        lock.lock();
        try {
            poolingCount = this.poolingCount;
            poolingPeak = this.poolingPeak;
            poolingPeakTime = this.getPoolingPeakTime();

            activeCount = this.activeCount;
            activePeak = this.activePeak;
            activePeakTime = this.getActivePeakTime();

            connectCount = this.connectCount;
            closeCount = this.closeCount;
        } finally {
            lock.unlock();
        }
        Map<String, Object> dataMap = new LinkedHashMap<String, Object>();

        dataMap.put("Identity", System.identityHashCode(this));
        dataMap.put("Name", this.getName());
        dataMap.put("DbType", this.dbTypeName);
        dataMap.put("DriverClassName", this.getDriverClassName());

        dataMap.put("URL", this.getUrl());
        dataMap.put("UserName", this.getUsername());
        dataMap.put("FilterClassNames", this.getFilterClassNames());

        dataMap.put("WaitThreadCount", this.getWaitThreadCount());
        dataMap.put("NotEmptyWaitCount", this.getNotEmptyWaitCount());
        dataMap.put("NotEmptyWaitMillis", this.getNotEmptyWaitMillis());

        dataMap.put("PoolingCount", poolingCount);
        dataMap.put("PoolingPeak", poolingPeak);
        dataMap.put("PoolingPeakTime", poolingPeakTime);

        dataMap.put("ActiveCount", activeCount);
        dataMap.put("ActivePeak", activePeak);
        dataMap.put("ActivePeakTime", activePeakTime);

        dataMap.put("InitialSize", this.getInitialSize());
        dataMap.put("MinIdle", this.getMinIdle());
        dataMap.put("MaxActive", this.getMaxActive());

        dataMap.put("QueryTimeout", this.getQueryTimeout());
        dataMap.put("TransactionQueryTimeout", this.getTransactionQueryTimeout());
        dataMap.put("LoginTimeout", this.getLoginTimeout());
        dataMap.put("ValidConnectionCheckerClassName", this.getValidConnectionCheckerClassName());
        dataMap.put("ExceptionSorterClassName", this.getExceptionSorterClassName());

        dataMap.put("TestOnBorrow", this.isTestOnBorrow());
        dataMap.put("TestOnReturn", this.isTestOnReturn());
        dataMap.put("TestWhileIdle", this.isTestWhileIdle());

        dataMap.put("DefaultAutoCommit", this.isDefaultAutoCommit());
        dataMap.put("DefaultReadOnly", this.getDefaultReadOnly());
        dataMap.put("DefaultTransactionIsolation", this.getDefaultTransactionIsolation());

        dataMap.put("LogicConnectCount", connectCount);
        dataMap.put("LogicCloseCount", closeCount);
        dataMap.put("LogicConnectErrorCount", this.getConnectErrorCount());

        dataMap.put("PhysicalConnectCount", this.getCreateCount());
        dataMap.put("PhysicalCloseCount", this.getDestroyCount());
        dataMap.put("PhysicalConnectErrorCount", this.getCreateErrorCount());

        dataMap.put("DiscardCount", this.getDiscardCount());

        dataMap.put("ExecuteCount", this.getExecuteCount());
        dataMap.put("ExecuteUpdateCount", this.getExecuteUpdateCount());
        dataMap.put("ExecuteQueryCount", this.getExecuteQueryCount());
        dataMap.put("ExecuteBatchCount", this.getExecuteBatchCount());
        dataMap.put("ErrorCount", this.getErrorCount());
        dataMap.put("CommitCount", this.getCommitCount());
        dataMap.put("RollbackCount", this.getRollbackCount());

        dataMap.put("PSCacheAccessCount", this.getCachedPreparedStatementAccessCount());
        dataMap.put("PSCacheHitCount", this.getCachedPreparedStatementHitCount());
        dataMap.put("PSCacheMissCount", this.getCachedPreparedStatementMissCount());

        dataMap.put("StartTransactionCount", this.getStartTransactionCount());
        dataMap.put("TransactionHistogram", this.getTransactionHistogramValues());

        dataMap.put("ConnectionHoldTimeHistogram", this.getDataSourceStat().getConnectionHoldHistogram().toArray());
        dataMap.put("RemoveAbandoned", this.isRemoveAbandoned());
        dataMap.put("ClobOpenCount", this.getDataSourceStat().getClobOpenCount());
        dataMap.put("BlobOpenCount", this.getDataSourceStat().getBlobOpenCount());
        dataMap.put("KeepAliveCheckCount", this.getDataSourceStat().getKeepAliveCheckCount());

        dataMap.put("KeepAlive", this.isKeepAlive());
        dataMap.put("FailFast", this.isFailFast());
        dataMap.put("MaxWait", this.getMaxWait());
        dataMap.put("MaxWaitThreadCount", this.getMaxWaitThreadCount());
        dataMap.put("PoolPreparedStatements", this.isPoolPreparedStatements());
        dataMap.put("MaxPoolPreparedStatementPerConnectionSize", this.getMaxPoolPreparedStatementPerConnectionSize());
        dataMap.put("MinEvictableIdleTimeMillis", this.minEvictableIdleTimeMillis);
        dataMap.put("MaxEvictableIdleTimeMillis", this.maxEvictableIdleTimeMillis);

        dataMap.put("LogDifferentThread", isLogDifferentThread());
        dataMap.put("RecycleErrorCount", getRecycleErrorCount());
        dataMap.put("PreparedStatementOpenCount", getPreparedStatementCount());
        dataMap.put("PreparedStatementClosedCount", getClosedPreparedStatementCount());

        dataMap.put("UseUnfairLock", isUseUnfairLock());
        dataMap.put("InitGlobalVariants", isInitGlobalVariants());
        dataMap.put("InitVariants", isInitVariants());
        return dataMap;
    }

    public JdbcSqlStat getSqlStat(int sqlId) {
        return this.getDataSourceStat().getSqlStat(sqlId);
    }

    public JdbcSqlStat getSqlStat(long sqlId) {
        return this.getDataSourceStat().getSqlStat(sqlId);
    }

    public Map<String, JdbcSqlStat> getSqlStatMap() {
        return this.getDataSourceStat().getSqlStatMap();
    }

    public Map<String, Object> getWallStatMap() {
        WallProviderStatValue wallStatValue = getWallStatValue(false);

        if (wallStatValue != null) {
            return wallStatValue.toMap();
        }

        return null;
    }

    public WallProviderStatValue getWallStatValue(boolean reset) {
        for (Filter filter : this.filters) {
            if (filter instanceof WallFilter) {
                WallFilter wallFilter = (WallFilter) filter;
                return wallFilter.getProvider().getStatValue(reset);
            }
        }

        return null;
    }

    public Lock getLock() {
        return lock;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        for (Filter filter : this.filters) {
            if (filter.isWrapperFor(iface)) {
                return true;
            }
        }

        if (this.statLogger != null
                && (this.statLogger.getClass() == iface || DruidDataSourceStatLogger.class == iface)) {
            return true;
        }

        return super.isWrapperFor(iface);
    }

    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) {
        for (Filter filter : this.filters) {
            if (filter.isWrapperFor(iface)) {
                return (T) filter;
            }
        }

        if (this.statLogger != null
                && (this.statLogger.getClass() == iface || DruidDataSourceStatLogger.class == iface)) {
            return (T) statLogger;
        }

        return super.unwrap(iface);
    }

    public boolean isLogDifferentThread() {
        return logDifferentThread;
    }

    public void setLogDifferentThread(boolean logDifferentThread) {
        this.logDifferentThread = logDifferentThread;
    }

    public DruidPooledConnection tryGetConnection() throws SQLException {
        if (poolingCount == 0) {
            return null;
        }
        return getConnection();
    }

    @Override
    public int fill() throws SQLException {
        return this.fill(this.maxActive);
    }

    @Override
    public int fill(int toCount) throws SQLException {
        if (closed) {
            throw new DataSourceClosedException("dataSource already closed at " + new Date(closeTimeMillis));
        }

        if (toCount < 0) {
            throw new IllegalArgumentException("toCount can't not be less than zero");
        }

        init();

        if (toCount > this.maxActive) {
            toCount = this.maxActive;
        }

        int fillCount = 0;
        for (; ; ) {
            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException("interrupt", e);
            }

            boolean fillable = this.isFillable(toCount);

            lock.unlock();

            if (!fillable) {
                break;
            }

            DruidConnectionHolder holder;
            try {
                PhysicalConnectionInfo pyConnInfo = createPhysicalConnection();
                holder = new DruidConnectionHolder(this, pyConnInfo);
            } catch (SQLException e) {
                LOG.error("fill connection error, url: " + sanitizedUrl(this.jdbcUrl), e);
                connectErrorCountUpdater.incrementAndGet(this);
                throw e;
            }

            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException("interrupt", e);
            }

            boolean result;
            try {
                if (!this.isFillable(toCount)) {
                    JdbcUtils.close(holder.getConnection());
                    LOG.info("fill connections skip.");
                    break;
                }
                result = this.putLast(holder, System.currentTimeMillis());
                fillCount++;
            } finally {
                lock.unlock();
            }

            if (!result) {
                JdbcUtils.close(holder.getConnection());
                LOG.info("connection fill failed.");
            }
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("fill " + fillCount + " connections");
        }

        return fillCount;
    }

    static String sanitizedUrl(String url) {
        if (url == null) {
            return null;
        }
        for (String pwdKeyNamesInMysql : new String[]{
            "password=", "password1=", "password2=", "password3=",
            "trustCertificateKeyStorePassword=",
            "clientCertificateKeyStorePassword=",
        }) {
            if (url.contains(pwdKeyNamesInMysql)) {
                url = url.replaceAll("([?&;]" + pwdKeyNamesInMysql + ")[^&#;]*(.*)", "$1<masked>$2");
            }
        }
        return url;
    }

    private boolean isFillable(int toCount) {
        int currentCount = this.poolingCount + this.activeCount;
        return currentCount < toCount && currentCount < this.maxActive;
    }

    public boolean isFull() {
        lock.lock();
        try {
            return this.poolingCount + this.activeCount >= this.maxActive;
        } finally {
            lock.unlock();
        }
    }

    private void emptySignal() {
        emptySignal(1);
    }

    private void emptySignal(int fillCount) {
        // 单线程建连
        if (createScheduler == null) {
            if (activeCount + poolingCount >= maxActive) {
                return;
            }
            // 池未满就 empty.signal()，唤醒一个在 empty.await() 上的 CreateConnectionThread，由它去建连并放入池
            empty.signal();
            return;
        }

        // 线程池建连
        // 按 fillCount 次调用 submitCreateTask(false)，每次提交一个 CreateConnectionTask 到 createScheduler，
        // 由线程池里的线程执行建连；建完后同样走 put → putLast → notEmpty.signal()，唤醒在 pollLast 里等的取连接线程
        for (int i = 0; i < fillCount; i++) {
            if (activeCount + poolingCount + createTaskCount >= maxActive) {
                return;
            }

            if (createTaskCount >= maxCreateTaskCount) {
                return;
            }

            submitCreateTask(false);
        }
    }

    @Override
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
        if (server != null) {
            try {
                if (server.isRegistered(name)) {
                    server.unregisterMBean(name);
                }
            } catch (Exception ex) {
                LOG.warn("DruidDataSource preRegister error", ex);
            }
        }
        return name;
    }

    @Override
    public void postRegister(Boolean registrationDone) {
    }

    @Override
    public void preDeregister() throws Exception {
    }

    @Override
    public void postDeregister() {
    }

    public boolean isClosed() {
        return this.closed;
    }

    public boolean isCheckExecuteTime() {
        return checkExecuteTime;
    }

    public void setCheckExecuteTime(boolean checkExecuteTime) {
        this.checkExecuteTime = checkExecuteTime;
    }

    public boolean isLoadSpifilterSkip() {
        return loadSpifilterSkip;
    }

    public void setLoadSpifilterSkip(boolean loadSpifilterSkip) {
        this.loadSpifilterSkip = loadSpifilterSkip;
    }

    public void forEach(Connection conn) {
    }
}
