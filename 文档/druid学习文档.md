# druid学习文档

## 📌 项目基本信息

- **项目名称**: druid
- **GitHub地址**: https://github.com/alibaba/druid
- **版本**: 1.2.27



## 🎯 项目概述

## 📁 项目结构分析

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



#### 1.1、DruidDriver.getInstance防死锁

详情见issue：https://github.com/alibaba/druid/issues/2980

#### 1.2、netTimeoutExecutor的作用

首先要讲解一下Connection的setNetworkTimeout方法

```
void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException;
```

用来给这条 Connection（以及从它创建的 Statement、ResultSet 等）设置网络超时：在最多等待数据库响应 milliseconds 毫秒，超时后驱动要中断等待、做清理等。第一个参数 executor：驱动在“发生超时”或“执行与超时相关的逻辑”时，用这个 Executor 来跑这些逻辑，而不是驱动自己随便起线程，也就是说：第一个参数的作用 = 告诉驱动：“超时发生时，你要执行的回调/任务，用我提供的这个 Executor 来执行”。第二个参数 milliseconds：超时时间（毫秒），0 表示不限制。第二个参数 milliseconds：超时时间（毫秒），0 表示不限制。

为什么 JDBC 要传 Executor 而不是驱动自己起线程？

超时到时，驱动通常要做的事包括：中断当前阻塞的 socket 读、取消操作、关闭或标记连接等，这些都要在某个线程里执行。若由驱动自己内部起线程（例如每个连接一个定时器线程），会带来：应用无法控制线程数量、线程名、优先级；大量连接时线程过多；与应用的线程池、监控、优雅关闭策略不一致。

所以 JDBC 规范把“谁来执行超时相关逻辑”交给调用方：调用方传一个 Executor，驱动在需要执行超时处理时，只做 executor.execute(runnable)，不自己起线程。这样：应用可以传“当前线程同步执行”的 Executor（像 Druid 的 SynchronousExecutor）；也可以传自己的线程池，统一管控线程和资源。

durid中的netTimeoutExecutor对应的实现类如下所示：

```java
/**
 * 同步执行器
 */
class SynchronousExecutor implements Executor {
    @Override
    public void execute(Runnable command) {
        try {
            // 直接在当前线程执行，不另起线程
            command.run();
        } catch (AbstractMethodError error) {
            //  // 标记驱动不支持 setNetworkTimeout，后续不再尝试
            netTimeoutError = true;
        } catch (Exception ignored) {
            // 打印异常日志
            if (LOG.isDebugEnabled()) {
                LOG.debug("failed to execute command " + command);
            }
        }
    }
}
```

execute(command) 里直接 command.run()，不新起线程、不提交到线程池。超时相关逻辑由驱动在“驱动自己的超时机制所在的线程”里同步执行，Druid 不额外引入线程，也不关心这些逻辑具体在哪个线程跑，只要满足 JDBC 必须传一个 Executor 的要求即可。





### 2、获取连接

相关类：

- com.alibaba.druid.pool.DruidDataSource#getConnection(long)：获取连接（直接建连，从池中获取）
- com.alibaba.druid.pool.DruidDataSource.CreateConnectionThread：建连线程，向池中补连

![mermaid-diagram-2026-02-24-121951](Y:\香蕉宝宝\Do\数据库相关\hello_druid\文档\druid学习文档.assets\mermaid-diagram-2026-02-24-121951.png)



```

    flowchart TD
    
    subgraph "获取数据库连接"
        OP1_1["保证获取连接时数据源已初始化: init();"]
        C1_2{"Filter链数量 > 0"}
        OP1_3["DruidDataSource.getConnectionDirect;直接获取数据库连接"]
    end

    subgraph DruidDataSource.getConnectionDirect
        subgraph "for循环,直到获取合法连接"
            OP2_1["DruidDataSource.getConnectionInternal;获取DruidPooledConnection，以及失败后重试"]
            OP2_2["借出校验与泄漏追踪"]
            OP2_3["返回借出连接"]
        end
    end

    subgraph DruidDataSource.getConnectionInternal
        direction TB
         OP4_1["前置检查，保证在池未关闭、未禁用时才继续取连接"]
          subgraph "for循环,直到获取连接"
            OP4_2["createDirect=true，直接通过createPhysicalConnection建连，通过CAS避免重复建连，加锁维护连接数，并将createDirect设置为false"]
            OP4_3["通过pollLast和takeLast从池中获取连接，如果连接数不够，唤醒 CreateConnectionThread，让它去建连并放入池"]
            OP4_4["对获取的连接进行校验、后置处理，并封装成DruidPooledConnection对象"]
        end
    end

    subgraph "通过Filter链获取数据库连接"
        OP3_1["DruidDataSource.createChain;获取Filter链(FilterChainImpl)"]
        OP3_2["FilterChainImpl。dataSource_connect;"]
        C3_3{"判断链上是否还有Filter"}
        OP3_4["Filter.dataSource_getConnection;过滤器自定义操作实现"]
        OP3_5["DruidDataSource.getConnectionDirect;直接获取数据库连接"]
        OP3_6["DruidDataSource.recycleFilterChain;归还Filter链(FilterChainImpl)"]
    end

    OP1_1 --> C1_2
    C1_2 --> |N| OP1_3
    

    OP1_3 -.- DruidDataSource.getConnectionDirect

    OP2_1 -.- DruidDataSource.getConnectionInternal
    OP2_1 --> OP2_2
    OP2_2 --> OP2_3

    OP4_1 --> OP4_2
    OP4_2 --> OP4_3
    OP4_3 --> OP4_4
    

    C1_2 --> |Y| OP3_1
    OP3_1 --> OP3_2
    OP3_2 --> C3_3
    C3_3 --> |Y| OP3_4
    OP3_4 --> OP3_2
    C3_3 --> |N| OP3_5
    OP3_5 -.- DruidDataSource.getConnectionDirect
    OP3_5 --> OP3_6

```



#### 2.1、为什么Filter链要设计成既要拿，又要还

“拿”是为了从缓存里取一条可用的链（没有再 new），“还”是把用过的链 reset 后放回缓存，供下次 getConnection 复用，从而在高并发下少 new、少 GC；链是有状态的，所以必须 reset 再还，不能只拿不还。

#### 2.2、关于建连线程的唤醒、调度

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



整体交互：

```
线程 A（取连接）                    共享状态（池）                线程 B（建连/回收）
    |                                    |                              |
    | lock.lock()                        |                              |
    | 看池：空 → 不满足                   |                              |
    | notEmpty.await()  ──────────────→ 释放 lock，A 挂起在 notEmpty 上   |
    |                                    |                              |
    |                                    |  lock.lock()                 |
    |                                    |  建连/回收，放入池            |
    |                                    |  notEmpty.signal()  ──────→ 唤醒 A
    |                                    |  lock.unlock()               |
    |  被唤醒，竞争到 lock，await 返回     |                              |
    |  再次检查：池非空，取走连接          |                              |
    |  lock.unlock()                     |                              |
```

- await：A 在“条件不满足”时释放锁并挂起，把 CPU 和锁让给 B。

- signal：B 在“让条件满足”后唤醒 A，A 醒来后抢锁、再检查条件、继续工作。

- 这就是 Condition 通过 await 和 signal 实现线程间交互 的典型方式。



### 3、关闭连接

相关类：com.alibaba.druid.pool.DruidPooledConnection#close

![mermaid-diagram-2026-02-25-121926](Y:\香蕉宝宝\Do\数据库相关\hello_druid\文档\druid学习文档.assets\mermaid-diagram-2026-02-25-121926.png)

```
flowchart TD
    subgraph close["DruidPooledConnection.close"]
        direction TB
        C0["close 入口"]
        C1["判断当前连接是否已经关闭，避免重复关闭"]
        C2["获取连接持有者对象，如果持有者对象为空，则直接return，并根据配置打印日志"]
        C3["从连接持有者对象中获取当前数据源"]
        C7{"当前关闭线程是否和获取线程相同"}
        C8["设置asyncCloseConnectionEnable为true"]
        C9{"removeAbandoned 或 asyncCloseConnectionEnable为ture"}
        C10["同步关闭连接syncClose并return，和下述关闭流程类似，只不过对操作加锁"]
        C11{"CLOSING_UPDATER.compareAndSet(0,1)<br/>失败?"}
        C12["return，其他线程正在关闭"]
        C13["遍历监听器进行通知"]
        C14{"filtersSize > 0：存在链"}
        C15["链关闭逻辑，和链获取连接逻辑类似，递归再返回"]
        C16["recycle()"]
        C17["finally: CLOSING_UPDATER.set(0)"]
        C18["设置当前连接关闭状态为true：this.disable = true"]
    end

    C0 --> C1
    C1 --> C2
    C2 --> C3
    C3 --> C7
    C7 -->|否| C8
    C7 -->|是| C9
    C8 --> C9
    C9 -->|是| C10
    C9 -->|否| C11
    C11 -->|是| C12
    C11 -->|否| C13
    C13 --> C14
    C14 -->|是| C15
    C14 -->|否| C16
    C15 --> C16
    C16 --> C17
    C17 --> C18

    C16 -.- recycle

    subgraph recycle["DruidPooledConnection.recycle"]
        direction TB
        RECYCLE_ENTRY["recycle 入口"]
        R1["判断当前连接是否已经关闭，避免重复关闭"]
        R2["获取连接持有者对象，如果持有者对象为空，则直接return，并根据配置打印日志"]
        R6{"!this.abandoned?：只有未被标记为“泄漏回收”的才调数据源回收；"}
        R7["holder.dataSource.recycle(this)"]
        R8["无论是否调了 dataSource.recycle，都与连接解耦并标记已关闭，保证状态一致：<br/>this.holder = null<br/>conn = null<br/>transactionInfo = null<br/>closed = true"]
    end

    RECYCLE_ENTRY --> R1
    R1 --> R2
    R2 --> R6
    R6 -->|是| R7
    R6 -->|否| R8
    R7 --> R8

    R7 -.- ds_recycle

    subgraph ds_recycle["DruidDataSource.recycle (数据源回收)"]
        direction TB
        DS_RECYCLE["recycle(pooledConnection) 入口"]
        D1["traceEnable: activeConnections.remove"]
        D2["!isAutoCommit 且 !isReadOnly: rollback"]
        D3["holder.reset()<br/>跨线程时先拿连接 lock"]
        D4["discard/phyMaxUseCount/已关闭/testOnReturn/!enable/物理超时 等则 discardConnection 或 return"]
        D5["lock: activeCount--, closeCount++<br/>putLast(holder) 回池"]
        D6["putLast 失败: JdbcUtils.close(holder.conn)"]
        D7["异常: clearStatementCache, discardConnection"]
    end

    DS_RECYCLE --> D1
    D1 --> D2
    D2 --> D3
    D3 --> D4
    D4 --> D5
    D5 --> D6
    D5 --> D7
```







### 4、销毁连接（线程方式）

相关类：com.alibaba.druid.pool.DruidDataSource.DestroyConnectionThread

![mermaid-diagram-2026-02-24-183535](Y:\香蕉宝宝\Do\数据库相关\hello_druid\文档\druid学习文档.assets\mermaid-diagram-2026-02-24-183535.png)

```

    flowchart TD
    
    subgraph "DestroyConnectionThread.run"
        direction TB
        OP1_1["initedLatch.countDown;通知 init 线程销毁线程已就绪"]
        subgraph "for循环并且线程不中断"
        direction TB
            OP2_1["数据源已关闭或正在关闭，直接退出循环，线程结束，不再执行 DestroyTask"]
            OP2_2["线程睡眠（可配置）"]
            OP2_3["睡眠后被中断，sleep 可能清除中断标志，这里再查一次，若被中断则退出循环"]
        end
        OP1_2["DestroyTask.run"]
    end

    OP1_1 --> OP2_1
    OP2_1 --> OP2_2
    OP2_2 --> OP2_3
    OP2_3 --> OP1_2
    OP1_2 --> DestroyTask.run

    subgraph "DestroyTask.run"
        direction TB
        OP3_1["shrink(checkTime, keepAlive);在持锁下扫描空闲池 connections，根据 checkTime / keepAlive 和各项时间阈值，把需要剔除的连接放进 evictConnections、
        需要保活检测的放进 keepAliveConnections；然后压缩 connections、物理关闭被剔除的连接、对保活连接做校验（通过则 put 回池，失败则丢弃）；
        若池低于 minIdle 或发生致命错误增量则 emptySignal 触发补连"]
        OP3_2["根据removeAbandoned值，进行泄漏连接回收"]
    end

    OP3_1 --> OP3_2
    OP3_1 -.- shrink
    OP3_2 -.- removeAbandoned

    
    subgraph "shrink"
        direction TB
        OP4_1["没有空闲连接poolingCount=0，无需收缩，直接返回"]
        OP4_2["获取主锁，可被中断；被中断则 return，不执行本次 shrink"]
        OP4_3["数据源未初始化完成则直接 return"]

        subgraph "遍历空闲连接"
            direction TB
            OP5_1["处于致命错误状态，或自上次 shrink 以来有新的致命错误，且该连接的建立时间早于最近一次致命错误时间：先放入保活队列"]
            OP5_2["按时间剔除或按照checkCount数量进行剔除空闲连接,按时间剔除会根据keepAlive对空闲够久的连接定期做一次有效性检测"]
            OP5_3["根据需要剔除和拿去保活连接数，更新空闲连接数，保留在池connections的连接并进行压缩"]
            OP5_4["若开启 keepAlive 且 当前池总量 < minIdle，标记needFill需要补连"]
            OP5_5["物理关闭被剔除的连接"]
            OP5_6["保活检测与回池/丢弃"]
            OP5_7["根据needFill决定是不是需要唤醒CreateConnectionThread进行补连"]
        end

    end

    OP4_1 --> OP4_2
    OP4_2 --> OP4_3
    OP4_3 --> 遍历空闲连接
    OP5_1 --> OP5_2
    OP5_2 --> OP5_3
    OP5_3 --> OP5_4
    OP5_4 --> OP5_5
    OP5_5 --> OP5_6
    OP5_6 --> OP5_7
 

    subgraph "removeAbandoned"
        direction TB
        OP6_1["没有“已借出”的连接（未开 removeAbandoned 时不会往 activeConnections 放，或都已还），直接返回 0，不做后续扫描和关闭"]
        OP6_2["加锁，遍历“已借出”的连接，未在跑 SQL 且借出超时的连接从 activeConnections 移入 abandonedList"]
        OP6_3["存在“泄漏”的连接，遍历“泄漏”的连接，关闭连接，并进行泄露回收，并给对应连接打上“已按泄漏回收处理”的标记"]
        OP6_4["根据配置打泄漏日志"]
        OP6_5["返回本方法本次回收数"]
    end


    OP6_1 --> OP6_2
    OP6_2 --> OP6_3
    OP6_3 --> OP6_4
    OP6_4 --> OP6_5
    
```





## 📖 参考文档

- 官方文档：https://github.com/alibaba/druid/wiki/%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98

- https://www.cnblogs.com/jingzh/p/16216411.html#13-%E9%85%8D%E7%BD%AE%E7%9B%B8%E5%85%B3%E5%B1%9E%E6%80%A7

- https://juejin.cn/post/7408147106891235355

- JMX：https://blog.csdn.net/zhangzehai2234/article/details/135299917





## 📖 补充知识点

### 1、java.util.concurrent.locks.Condition

Condition 来自 java.util.concurrent.locks，和 Lock 配合使用，用来做“条件等待”：

- 某线程在持有同一把 Lock 的前提下，发现“条件不满足”就释放锁并挂起（await）；

- 别的线程在满足条件后、持有同一把 Lock 时，唤醒在等这个条件的线程（signal / signalAll）

可以理解为：synchronized + wait/notify 的升级版，但一个 Lock 可以对应多个 Condition，语义更清晰：

| 能力         | synchronized + wait/notify     | Lock + Condition                            |
| :----------- | :----------------------------- | :------------------------------------------ |
| 多个等待条件 | 一个对象只有一个等待队列       | 一个 Lock 可 newCondition() 多次            |
| 可中断等待   | wait() 可被中断                | await() 同样，还提供 awaitUninterruptibly() |
| 超时等待     | wait(timeout)                  | await(time, unit) 等                        |
| 使用前提     | 持有对象监视器（synchronized） | 持有对应的 Lock                             |

Condition 必须和某一把 Lock 绑定，由 Lock 创建：Condition cond = lock.newCondition(); 调用 cond.await() / cond.signal() / cond.signalAll() 时，当前线程必须已经持有创建该 Condition 的那把 lock，否则会抛 IllegalMonitorStateException。所以：“谁在等、谁在唤醒” 都是在对同一把 lock 保护的共享状态做判断后，通过 Condition 来挂起/唤醒，实现线程间协作。

1.await：释放锁并挂起，被唤醒后重新抢锁

```
// 伪代码（当前线程已持有 lock）
lock.lock();
try {
    while (!条件满足) {
        condition.await();  // 1. 释放 lock  2. 当前线程挂起
                            // 3. 被 signal 唤醒后，先重新竞争 lock，抢到后才从 await 返回
    }
    // 条件满足，继续做事
} finally {
    lock.unlock();
}
```

要点：

- await() 会做三件事：

  - 释放当前持有的 lock（所以别的线程可以拿到锁去改状态、再 signal）；

  - 当前线程在这个 Condition 上进入等待队列，挂起；

  - 被 signal/signalAll 唤醒后，不会立刻返回，而是要先重新竞争这把 lock，抢到锁之后 await 才返回。

- 返回后，线程再次持有 lock，所以可以安全地再次检查“条件是否真的满足”（一般用 while 而不是 if，防止虚假唤醒）。

- 因此：“通过 await 实现交互” = 当前线程暂时放弃锁并睡觉，等别人改好状态并 signal，自己醒来后重新拿锁再往下执行。



2.signal / signalAll：唤醒在等这个 Condition 的线程

```
// 另一个线程（也已持有同一把 lock）
lock.lock();
try {
    // 修改共享状态，使“条件”满足
    状态 = 已满足;
    condition.signal();   // 或 signalAll()
    // 唤醒一个（signal）或全部（signalAll）在该 condition 上 await 的线程
} finally {
    lock.unlock();
}
```

要点：

- signal()：从该 Condition 的等待队列里移出一个线程，让它去竞争 lock；那个线程在 await 里被唤醒后，会去抢 lock，抢到后 await 返回。

- signalAll()：唤醒所有在该 Condition 上等待的线程，它们都会去竞争 lock，一般只有一个能先拿到锁，其余继续等 lock。

- 调用 signal/signalAll 时不会释放 lock：只是“通知”，当前线程仍然持有锁，通常会在 unlock 之后，被唤醒的线程才有机会抢到锁并从 await 返回。

- 因此：“通过 signal 实现交互” = 在改完共享状态后，通知“等这个条件”的线程：“条件可能满足了，你们可以醒过来重新抢锁、再检查条件”。









## 📖学习路径

Druid 项目重要模块与后续学习建议

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

1. 连接池核心：`pool` 包（优先）

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
