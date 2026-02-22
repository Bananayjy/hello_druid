# druid学习文档

## 📌 项目基本信息

- **项目名称**: druid
- **GitHub地址**: https://github.com/alibaba/druid
- **版本**: 1.2.27



## 🎯 项目概述



## 📁 项目结构分析

```

```



## 🧪 使用示例

### 1、版本说明

- MySQL：8.0.19

- spring-boot-starter-web：2.7.0

- mysql-connector-java：8.0.33

- mybatis-plus-boot-starter：3.5.2

  

### 2、环境搭建



## 📖 核心流程

### 1、数据源初始化

方法入口：com.alibaba.druid.pool.DruidDataSource#init()

作用：init() 就是把“池结构、驱动、校验、统计、预连接、后台线程、JMX”在第一次使用时一次性准备好；之后 getConnection() 才会在 init() 里被间接调用（init() 里会保证只执行一次）



![mermaid-diagram-2026-02-22-104214](Y:\香蕉宝宝\Do\数据库相关\hello_druid\文档\druid学习文档.assets\mermaid-diagram-2026-02-22-104214.png)

```

     flowchart TD
    
    subgraph "幂等控制防重入、防死锁"
        OP1_1["if (inited) return;"]
        OP1_2["DruidDriver.getInstance();"]
    end
    
    subgraph "数据源初始化"
        OP2_1["加锁、防并发 init、记录调用栈：lock.lockInterruptibly() + 双重检查 + initStackTrace"]
        OP2_2["多数据源 ID 区分"]
        OP2_3["jdbcUrl 处理与超时参数设置：initFromWrapDriverUrl + initTimeoutsFromUrlOrProperties"]
        OP2_4["Filter 初始化:filter.init(this);"]
        OP2_5["数据库类型设置"]
        OP2_6["MySQL 驱动使用服务端配置缓存"]
        OP2_7["数据池参数校验"]
        OP2_8["驱动加载：driverClass + initFromSPIServiceLoader + resolveDriver"]
        OP2_9["一致性校验：initCheck"]
        OP2_10["同步执行器：netTimeoutExecutor"]
        OP2_11["配置异常判断连接是否应丢弃和连接校验器：ExceptionSorter + ValidConnectionChecker"]
        OP2_12["检查是否具备至少一种可用的校验手段：validationQueryCheck"]
        OP2_13["统计对象：dataSourceStat（全局或独立设置）"]
        OP2_14["是否允许监控页/API 重置统计设置"]
        OP2_15["池结构分配：connections四个数组的初始化"]
        OP2_16["初始连接：按 asyncInit/!asyncInit 预建 initialSize 个连接"]
        OP2_17["创建并启动三个后台线程：createAndLogThread + createAndStartCreatorThread + createAndStartDestroyThread"]
        OP2_18["等待连接 Create/Destroy 线程就绪"]
        OP2_19["收尾与注册MBean：init=true、initedTime、registerMbean、connectError 抛错、keepAlive 补建连"]
        OP2_20["保证状态与锁、成功日志：finally: inited=true、unlock、inited 日志"]
    end
     
    OP1_1 --> OP1_2
    OP1_2 --> OP2_1
    OP2_1 --> OP2_2
    OP2_2 --> OP2_3
    OP2_3 --> OP2_4
    OP2_4 --> OP2_5
    OP2_5 --> OP2_6
    OP2_6 --> OP2_7
    OP2_7 --> OP2_8
    OP2_8 --> OP2_9
    OP2_9 --> OP2_10
    OP2_10 --> OP2_11
    OP2_11 --> OP2_12
    OP2_12 --> OP2_13
    OP2_13 --> OP2_14
    OP2_14 --> OP2_15
    OP2_15 --> OP2_16
    OP2_16 --> OP2_17
    OP2_17 --> OP2_18
    OP2_18 --> OP2_19
    OP2_19 --> OP2_20
     
     
```

#### 1.DruidDriver.getInstance() 防死锁

下面分几部分说明 **DruidDriver.getInstance()** 的作用和为什么在 `init()` 里要提前调用。

一、方法本身在做什么

```java
// DruidDriver.java
private static final DruidDriver instance = new DruidDriver();

public static DruidDriver getInstance() {
    return instance;
}
```

- **代码含义**：就是一个典型的单例 getter，返回静态常量 **instance**。
- **实际效果**：要执行 `getInstance()`，JVM 必须先完成 **DruidDriver 类的初始化**（还没加载、初始化过的话，会先加载并执行静态初始化），然后才能读 `instance`。  
所以“调用 getInstance()”的真实意义是：**触发 DruidDriver 的类加载与静态初始化**。

二、DruidDriver 的静态初始化做了什么

```java
static {
    AccessController.doPrivileged(new PrivilegedAction<Object>() {
        @Override
        public Object run() {
            registerDriver(instance);
            return null;
        }
    });
}

public static boolean registerDriver(Driver driver) {
    try {
        DriverManager.registerDriver(driver);   // ① 向 JDBC 注册驱动
        // ② 可选：向 JMX 注册 MBean
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName objectName = new ObjectName(MBEAN_NAME);
        if (!mbeanServer.isRegistered(objectName)) {
            mbeanServer.registerMBean(instance, objectName);
        }
        return true;
    } catch (Exception e) { ... }
    return false;
}
```

也就是说，**第一次加载 DruidDriver 类时会**：

1. 执行 **DriverManager.registerDriver(instance)**，把 DruidDriver 注册成 JDBC 驱动（内部会拿 **DriverManager 的锁**）。
2. 可能再执行 **MBeanServer.registerMBean(...)**（也可能再涉及 JMX 的锁）。

因此：**DruidDriver 的类初始化会获取“DriverManager（以及可能 JMX）的锁”**。

三、DruidDriver 在整体里扮演什么角色

- **DruidDriver** 实现了 **java.sql.Driver**，用来支持 **“包装型 JDBC URL”** 的用法：
  - URL 以 **jdbc:wrap-jdbc:** 开头时，由 DruidDriver 接受；
  - 它会解析 URL（driver、filters、name 等），创建或复用 **DataSourceProxyImpl**，再通过 `dataSource.connect(info)` 得到 Connection。
- 也就是说：**DriverManager.getConnection("jdbc:wrap-jdbc:...")** 会走到 DruidDriver，这是另一种使用 Druid 的方式，和直接使用 **DruidDataSource** 是两条路径。
- **DruidDataSource** 自己建连时用的是 **真实 Driver**（如 MySQL Driver），一般不会用 DruidDriver 去建连；但 **DruidDataSource.init()** 里会调用 **DruidDriver.createDataSourceId()** 来生成数据源 ID，所以会**依赖 DruidDriver 类**。

四、为什么要在 init() 里“提前”调用 getInstance()（防死锁 #2980）

在 **DruidDataSource.init()** 里，顺序是：

```java
DruidDriver.getInstance();   // ① 先执行

final ReentrantLock lock = this.lock;
lock.lockInterruptibly();    // ② 再加锁
try {
    // ...
    this.id = DruidDriver.createDataSourceId();  // ③ 后面才会用到 DruidDriver
    // ...
}
```

若**不**在 ② 之前调用 ①，可能出现：

- **线程 A**：已持有 **DruidDataSource 的 lock**，在 init() 里执行到 **DruidDriver.createDataSourceId()**（或其它第一次引用 DruidDriver 的地方）→ 触发 **DruidDriver 类加载** → 静态块里执行 **DriverManager.registerDriver()** → 需要去拿 **DriverManager 的锁**。
- **线程 B**：在别处（例如其它地方加载/注册驱动）已持有 **DriverManager 的锁**，随后某次操作又需要 **DruidDataSource 的 lock**（例如另一个数据源 init、或 getConnection 等）。
- 结果：A 拿 DataSource 锁等 DriverManager 锁，B 拿 DriverManager 锁等 DataSource 锁 → **死锁**。

通过在 **加 DruidDataSource 的 lock 之前**先执行 **DruidDriver.getInstance()**：

- 在**尚未持有任何 DataSource 锁**的时候，就完成 **DruidDriver 的类加载**；
- 类加载时该拿的 **DriverManager（和 JMX）的锁**，都在此时拿完、放完；
- 之后再 **lock.lockInterruptibly()**，之后再用到 **DruidDriver.createDataSourceId()** 时，只是调用已加载类的方法，**不会再触发类初始化**，也就不会在持锁状态下再去抢 DriverManager 的锁。

这样就把“加载 DruidDriver / 注册驱动”和“持有 DruidDataSource 锁”在时间上分开，**避免形成 2980 里那种死锁**。

五、小结（一句话 + 分层说明）

- **getInstance() 本身**：只是返回单例 `instance`，但会**触发 DruidDriver 的类加载与静态初始化**。
- **静态初始化的作用**：向 **DriverManager** 注册 DruidDriver，并可选注册 **JMX MBean**；这些动作会涉及系统锁（DriverManager 等）。
- **在 init() 里提前调用的意义**：在 **DruidDataSource 尚未加锁** 时就把 DruidDriver 加载完、注册完，避免在**已持 DataSource 锁**的情况下再去触发 DriverManager 的锁，从而**防止 issue #2980 的 dead lock**。
- **对“直接用 DruidDataSource”的用法**：建连仍用真实 JDBC 驱动；DruidDriver 在这里主要是为了**提前完成类加载和注册**，而不是为了用它的 `connect()`。





### 2、获取连接

入口：com.alibaba.druid.pool.DruidDataSource#getConnection()

```

```







详解：

1、为什么这个链要设计成既要拿，又要还

“拿”是为了从缓存里取一条可用的链（没有再 new），“还”是把用过的链 reset 后放回缓存，供下次 getConnection 复用，从而在高并发下少 new、少 GC；链是有状态的，所以必须 reset 再还，不能只拿不还。

2、关于建连线程的唤醒、调度

Druid 用同一把锁 lock 绑了两个 Condition（在 DruidAbstractDataSource 里创建）：

| 条件变量 | 含义                     | 谁在等                                                    | 谁在唤醒                                         |
| :------- | :----------------------- | :-------------------------------------------------------- | :----------------------------------------------- |
| notEmpty | 池里有空闲连接           | 取连接的线程（在 pollLast 里）                            | 放连接进池时（put/putLast）                      |
| empty    | 池需要建连（池空或未满） | CreateConnectionThread（仅在没有 createScheduler 时存在） | emptySignal()（仅在没有 createScheduler 时调用） |

- 取连接：拿不到连接时在 notEmpty.await() 上等；有连接放进池就 notEmpty.signal()，取连接线程被唤醒。

- 建连接：没有调度器时，由 CreateConnectionThread 在 empty.await() 上等；有人调 emptySignal() 就 empty.signal()，建连线程被唤醒去建连，建完通过 put → putLast → notEmpty.signal() 再唤醒取连接的线程。

也就是说：emptySignal 负责的是「通知建连侧去干活」，真正让取连接线程结束等待的是「建连/回收后对 notEmpty 的 signal」

模式 A：createScheduler == null（单线程建连）：

- 建连线程：只有一个 CreateConnectionThread，在 lock 下循环：

- 先判断要不要等：若 poolingCount >= notEmptyWaitThreadCount 且非 keepAlive/非失败等，就认为「没人等连接」，empty.await() 挂起。

- 被 empty.signal() 唤醒后，再检查 activeCount + poolingCount >= maxActive，满则再次 empty.await()。

- 否则 lock.unlock()，执行 createPhysicalConnection()（建连过程不占锁），建完后 put(connection)。

- put(connection)：在 put 里再次 lock，把连接放进 connections，poolingCount++，然后 notEmpty.signal()，唤醒一个在 pollLast 里等 notEmpty 的取连接线程。

因此，唤醒与调度链是：

1. 取连接时池空 → pollLast 里 emptySignal() → empty.signal()

1. CreateConnectionThread 从 empty.await() 被唤醒 → 建连 → put → putLast → notEmpty.signal()

1. 正在 pollLast 里 notEmpty.await() 的某个线程被唤醒 → 从池里拿走刚放进去的连接，返回。

初始化时若 keepAlive 且无 createScheduler，会执行一次 empty.signal()，让 CreateConnectionThread 第一次被唤醒，按 minIdle 等策略建连。

模式 B：createScheduler != null（线程池建连）：

- 没有 CreateConnectionThread，empty 不会被使用。

- emptySignal() 只做 submitCreateTask(false)：

- createTaskCount++， new CreateConnectionTask，createScheduler.submit(task)，任务进入线程池队列。

- 线程池里某个工作线程执行 CreateConnectionTask.runInternal()：

- 先 lock，检查 closed、池满、是否需要放弃本次建连（如没人等且非 initTask 等），通过则 unlock；

- 然后 createPhysicalConnection()；

- 再 put(physicalConnection)：内部 lock、放入池、notEmpty.signal()、unlock。

- 在 pollLast 里 notEmpty.await() 的取连接线程被 notEmpty.signal() 唤醒，从池中取走连接。

所以这里的「唤醒和调度」是：

1. 取连接时池空 → pollLast 里 emptySignal() → submitCreateTask(false)（可能多次）。

1. 调度器中的线程执行 CreateConnectionTask → 建连 → put → putLast → notEmpty.signal()。

1. 取连接线程在 notEmpty.await() 上被唤醒 → 从池取连接返回。

没有「empty 上的等待/唤醒」，只有「任务入队 → 线程池调度执行 → put → notEmpty.signal()」。



总结：

- emptySignal()：

- 无 createScheduler 时：在池未满的前提下调 empty.signal()，唤醒 CreateConnectionThread，让它去建连并放入池（放池时 notEmpty.signal() 再唤醒取连接线程）。

- 有 createScheduler 时：在未满且未超建连任务上限的前提下 submitCreateTask(false)，由线程池调度执行建连，建完同样通过 put → notEmpty.signal() 唤醒取连接线程。

- 线程之间：

- 取连接线程只在 notEmpty 上被唤醒（在 pollLast 里）；

- 建连线程/任务由 emptySignal 通过 empty（单线程模式）或 createScheduler（线程池模式）触发；建连完成后通过 notEmpty.signal() 把等待的取连接线程唤醒。

这样就把 emptySignal 的唤醒和调度 与 notEmpty 的配合关系说清楚了：emptySignal 管「让谁去建连」，notEmpty 管「谁可以拿到连接」。





## 📖 参考文档

- 官方文档：https://github.com/alibaba/druid/wiki/%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98

- https://www.cnblogs.com/jingzh/p/16216411.html#13-%E9%85%8D%E7%BD%AE%E7%9B%B8%E5%85%B3%E5%B1%9E%E6%80%A7





## 📖学习路径

结合当前仓库结构，下面按「重要模块 + 推荐学习顺序」整理，方便你在完成 `DruidDataSourceAutoConfigure` 之后继续深入。

---

# Druid 项目重要模块与后续学习建议

一、项目整体结构（你当前看到的）

| 模块                            | 说明                                             | 与你已学内容的关系                                           |
| ------------------------------- | ------------------------------------------------ | ------------------------------------------------------------ |
| **core**                        | 核心库（连接池、Filter、统计、Wall、SQL 解析等） | Starter 依赖它，你看到的 `DruidDataSource`、`StatViewServlet`、各种 Filter 都在这里 |
| **druid-spring-boot-starter**   | Spring Boot 2.x 自动配置                         | 你已完成的「自动注册配置类」所在模块                         |
| **druid-spring-boot-3-starter** | Spring Boot 3.x 自动配置（JDK17+ profile）       | 与 2.x 类似，可对比学习                                      |
| **druid-wrapper**               | 对 core 的薄封装/适配                            | 可选了解                                                     |
| **druid-demo-petclinic**        | 示例应用                                         | 用于跑起来看效果                                             |

你接下来要深入的是 **core** 里和「连接池 + 监控 + 扩展」最相关的几块，并保持和 Starter 的衔接。

二、core 里比较重要的模块（按推荐学习顺序）

### 1. 连接池核心：`pool` 包（优先）

- **路径**：`core/src/main/java/com/alibaba/druid/pool/`
- **核心类**：
  - **`DruidAbstractDataSource`**：连接池抽象基类，维护 url/username/password、池参数（initialSize、maxActive、minIdle 等）、`filters`、`init()`/`close()` 等。
  - **`DruidDataSource`**：你已在 Starter 里见过，继承上面，是实际对外暴露的 `DataSource`；`init()` 里建池、启动销毁线程、加载 Filter 链等。
  - **`DruidPooledConnection`**：池化连接的包装，借出/归还、关闭语义。
  - **`DruidConnectionHolder`**：底层物理连接的持有与生命周期。
- **为什么先学**：Starter 的 `DruidDataSourceWrapper` 继承的就是 `DruidDataSource`，`afterPropertiesSet()` 最后调用的 `init()` 就在 pool 里；先搞清「池怎么建、连接怎么借还」，后面 Filter/统计 才好对上号。
- **建议**：从 `DruidDataSource#init()` 和 `getConnection()` 两条线跟进去，再看 `DruidAbstractDataSource` 的配置项和 `filters` 如何被调用。

2. Filter 机制：`filter` 包（与 Starter 的 DruidFilterConfiguration 衔接）

- **路径**：`core/src/main/java/com/alibaba/druid/filter/`
- **核心**：
  - **`Filter`** 接口、**`FilterChain`** / **`FilterChainImpl`**：定义「链式调用」的约定，连接/语句/结果集等各阶段如何依次经过各个 Filter。
  - **`FilterAdapter`**：默认空实现的适配器，你看到的 StatFilter、WallFilter、Slf4jLogFilter 等一般都继承它，只重写关心的回调。
- **子包**（和 Starter 里 `DruidFilterConfiguration` 注册的 Bean 对应）：
  - **`filter/stat`**：`StatFilter` — SQL 执行统计，供监控页「SQL 统计」用。
  - **`filter/logging`**：Slf4jLogFilter、Log4jFilter 等 — SQL 日志。
  - **`filter/config`**：ConfigFilter。
  - **`filter/encoding`**：EncodingConvertFilter。
- **为什么第二学**：Starter 里只是「按配置注册 Filter Bean」并交给 `DruidDataSourceWrapper#autoAddFilters`；真正「何时、以什么顺序、在连接/语句哪一环节调用」都在 core 的 Filter 链里。
- **建议**：看 `FilterChainImpl` 里 connection/statement 的调用顺序，再选一个 `StatFilter` 或 `Slf4jLogFilter` 跟一遍完整调用链。

3. 统计体系：`stat` 包（监控数据从哪来）

- **路径**：`core/src/main/java/com/alibaba/druid/stat/`
- **核心**：
  - **`JdbcDataSourceStat`**：每个数据源一条统计，下面挂着 Connection/Sql/Statement 等统计。
  - **`JdbcSqlStat`**：每条 SQL 的执行次数、耗时、慢 SQL 等。
  - **`DruidStatService`**：对外提供统计数据的入口，监控页的 JSON 接口会调它（如 `DruidStatManagerFacade`）。
  - **`DruidDataSourceStatManager`**：管理多个数据源在 JMX/统计里的注册。
- **与 Starter 的关系**：Starter 打开的「Stat 监控页」和「SQL 统计」的数据，都来自这些类；StatFilter 在 Filter 链里把执行信息写入这里。
- **建议**：先搞清楚「一次 SQL 执行后，StatFilter 如何更新 JdbcSqlStat」，再看 `DruidStatService` / `DruidStatManagerFacade` 如何被 StatViewServlet 使用。

4. 监控页与 Web 统计：`support/http` 包（和 Starter 的 StatViewServlet/WebStatFilter 对应）

- **路径**：`core/src/main/java/com/alibaba/druid/support/http/`
- **核心**：
  - **`StatViewServlet`**（父类 `ResourceServlet`）：你已在 Starter 的 `statViewServletRegistrationBean` 里见过，提供监控页的 HTML/API；内部通过 `DruidStatService`/Facade 取数，并做 allow/deny、login 等。
  - **`WebStatFilter`**：对 HTTP 请求做 URI/Session 统计，和 Starter 的 `DruidWebStatFilterConfiguration` 注册的 Bean 对应。
- **support/http/stat**：WebAppStat、WebRequestStat 等，供 WebStatFilter 和监控页「Web 应用」等维度使用。
- **建议**：对照 Starter 里 `statViewServletRegistrationBean` 设置的 init 参数，在 `ResourceServlet`/`StatViewServlet` 里看 allow、deny、loginUsername、loginPassword、resetEnable 如何被读取和使用。

5. Wall 防 SQL 注入：`wall` 包（可选但很实用）

- **路径**：`core/src/main/java/com/alibaba/druid/wall/`
- **核心**：
  - **`WallFilter`**：在 Filter 链里对 SQL 做校验，非法则拒绝执行。
  - **`WallConfig`**：黑白名单、是否允许多语句等，对应 Starter 里 `filter.wall` / `filter.wall.config`。
  - **`WallProvider`**：按数据库类型做不同规则（MySQL/Oracle 等）。
- **依赖**：会用到 **`sql`** 包的解析结果（AST），所以 Wall 可以顺带让你接触到「Druid 的 SQL 解析」。
- **建议**：先看 `WallFilter` 在链中的调用点，再看一次合法 SQL 和一次非法 SQL 分别如何被放行/拒绝；若对「如何识别注入」感兴趣，再深入 `WallProvider` + `sql` 包。

6. Spring AOP 统计：`support/spring/stat` 包（和 Starter 的 DruidSpringAopConfiguration 对应）

- **路径**：`core/src/main/java/com/alibaba/druid/support/spring/stat/`
- **核心**：
  - **`DruidStatInterceptor`**：Starter 里 `advice()` 返回的增强逻辑，对匹配到的方法做耗时/调用次数统计。
  - **`SpringMethodStat`** 等：方法级统计数据结构。
- **建议**：在学完 `stat` 包后，看 `DruidStatInterceptor` 如何与 `stat` 体系挂钩，以及监控页「Spring 监控」数据从哪来。

7. SQL 解析：`sql` 包（按需深入）

- **路径**：`core/src/main/java/com/alibaba/druid/sql/`
- **内容**：各数据库方言的 Lexer、Parser、Visitor（如 `sql/dialect/mysql`），Wall 和统计里的「SQL 归一化」会用到。
- **建议**：先不系统学，等看 Wall 或「慢 SQL 合并统计」时，再按需看 `SQLUtils`、Parser 入口和 AST 结构。

三、建议的后续学习顺序（结合你已完成的自动配置）

1. **pool**：`DruidAbstractDataSource` → `DruidDataSource`（`init()`、`getConnection()`、`filters`），建立「池 + Filter 链」的整体图景。  
2. **filter**：`Filter`/`FilterChainImpl` → `StatFilter`（或一个 LogFilter），理解 Starter 里注册的 Filter 是如何被调用的。  
3. **stat**：`JdbcDataSourceStat` / `JdbcSqlStat` 与 StatFilter 的配合，以及 `DruidStatService` / `DruidStatManagerFacade` 如何被监控页使用。  
4. **support/http**：`StatViewServlet`、`ResourceServlet`，和你在 Starter 里写的 `statViewServletRegistrationBean` 一一对应看一遍。  
5. **wall**（可选）：`WallFilter` + `WallConfig`，对应 Starter 的 `filter.wall` 配置。  
6. **support/spring/stat**：`DruidStatInterceptor`，对应 Starter 的 AOP 配置。  
7. **sql**：按需在学 Wall 或慢 SQL 时再深入。

这样可以从「你已经分析过的自动配置类」自然过渡到「连接池如何工作、监控数据从哪来、Filter 如何插在 SQL 执行路径上」，形成一条完整链路。如果你希望，我可以下一步单独把「pool 包」或「Filter 链 + StatFilter」的代码阅读顺序和关键方法列成一个小清单，方便你按文件逐一看。



| 顺序 | 代码块                                                       | 作用                          |
| :--- | :----------------------------------------------------------- | :---------------------------- |
| 1    | inited 检查 + DruidDriver.getInstance()                      | 防重入、防死锁                |
| 2    | lock.lockInterruptibly() + 双重检查 + initStackTrace         | 加锁、防并发 init、记录调用栈 |
| 3    | id 与各 ID 种子偏移                                          | 多数据源 ID 区分              |
| 4    | jdbcUrl 处理 + initTimeoutsFromUrlOrProperties               | URL 与超时                    |
| 5    | Filter.init + dbTypeName + MySQL cacheServerConfiguration    | Filter 与库类型               |
| 6    | 各类参数校验（maxActive、minIdle、initialSize、eviction、keepAlive 等） | 参数合法                      |
| 7    | driverClass + initFromSPIServiceLoader + resolveDriver       | 驱动加载                      |
| 8    | initCheck + netTimeoutExecutor + ExceptionSorter + ValidConnectionChecker + validationQueryCheck | 校验与执行器                  |
| 9    | dataSourceStat（全局或独立）                                 | 统计对象                      |
| 10   | connections 等四个数组 new                                   | 池结构分配                    |
| 11   | 按 asyncInit/!asyncInit 预建 initialSize 个连接              | 初始连接                      |
| 12   | createAndLogThread + createAndStartCreatorThread + createAndStartDestroyThread | 三个后台线程                  |
| 13   | await Create/Destroy initedLatch                             | 等线程就绪                    |
| 14   | init=true、initedTime、registerMbean、connectError 抛错、keepAlive 补建连 | 收尾与 MBean                  |
| 15   | finally: inited=true、unlock、inited 日志                    | 保证状态与锁、成功日志        |

整体上，init() 就是把“池结构、驱动、校验、统计、预连接、后台线程、JMX”在第一次使用时一次性准备好；之后 getConnection() 才会
