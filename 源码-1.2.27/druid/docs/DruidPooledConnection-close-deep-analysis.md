# DruidPooledConnection#close 深层次解析

本文从**设计意图、不变式、并发与边界语义**出发，分析 close/syncClose/recycle 中每一段代码的**深层次含义**，而不是复述“这行在做什么”。

---

## 一、close() 的整体契约与设计目标

**对调用方（业务）的契约**：调用 `Connection.close()` 表示“我不再使用这条连接”。池要保证：**同一条物理连接最多只被“借出”一次**，close 之后要么回到空闲池供别人借，要么被丢弃，且**同一包装对象不能再次被有效使用**。

**池要维护的不变式**：
- 任意时刻：`activeCount` = 被借出且未归还的连接数；每条被借出的连接在 **activeConnections**（若开启 removeAbandoned）或逻辑上只被计数一次。
- close 的一次“成功执行”必须对应：**一次** activeCount 减少、**一次** 把 holder 回池（putLast）或丢弃（discardConnection），且该包装此后视为“已关闭”，不能再触发第二次回收。

**深层次含义**：close 里所有“提前 return”、CAS、lock、disable 的设置，都是为了在**并发、重复调用、跨线程关闭、removeAbandoned 线程介入**等情况下，仍然满足上述契约和不变式。

---

## 二、close() 入口：disable 与 holder 检查

```java
if (this.disable) {
    return;
}
DruidConnectionHolder holder = this.holder;
if (holder == null) {
    if (dupCloseLogEnable) {
        LOG.error("dup close");
    }
    return;
}
```

**设计意图**：
- **disable**：表示“本包装已执行过一套完整的关闭/回收逻辑”，是**幂等性的唯一总开关**。一旦为 true，后续任何 close 调用都不应再对池状态做任何修改（不再减 activeCount、不再 putLast）。
- **holder == null**：表示要么从未有效初始化，要么 **recycle()** 已经执行过并把 `this.holder` 清空。此时池侧要么已经处理过这条连接（回池或丢弃），要么从未把它算进 activeCount，因此这里**不能再**调用 `dataSource.recycle(this)`，否则会 NPE 或破坏池计数。

**深层次含义**：
- “关闭”在语义上被定义为**一次性的**：第一次成功 close 会设置 disable 并（在 recycle 里）清空 holder；之后所有 close 都应在入口被挡掉，避免“重复归还”“重复 activeCount--”。
- dupCloseLogEnable 的日志不是为了描述“这行在打日志”，而是为了**运维可观测性**：谁在重复 close、是否有多处 try/finally 或共享 Connection 导致的双重关闭，便于排查连接管理错误。

---

## 三、ownerThread 与 asyncCloseConnectionEnable

```java
DruidAbstractDataSource dataSource = holder.getDataSource();
boolean isSameThread = this.ownerThread == Thread.currentThread();
if (!isSameThread) {
    dataSource.setAsyncCloseConnectionEnable(true);
}
```

**设计意图**：
- **ownerThread** 记录的是“借出连接”的线程。在池的预期用法里，**谁借谁还**是最简单、最容易推理的；同线程内 getConnection/close 成对出现，不需要为“谁在关谁”加锁。
- **跨线程 close**（例如 A 借、B 关，或 removeAbandoned 后台线程关）打破了这个假设：若不加以区分，可能出现“业务线程 A 正在用连接发 SQL，线程 B 或 Destroy 线程调 close”，导致同一连接上**并发**的“使用”与“回收”，共享的 **holder**、**conn**、**lock** 会面临竞态。

**深层次含义**：
- **setAsyncCloseConnectionEnable(true)** 不是“记一笔日志”，而是**提升整个数据源的关闭策略**：一旦发现存在“非借出线程关连接”，就认为该数据源上存在异步/跨线程使用连接的模式，此后**该数据源上所有池化连接的 close 都走 syncClose()**，即**先拿连接自己的 lock 再回收**。这样，即使用法不规范（跨线程 close），也能通过“每次 close 都持锁”把并发关在同一连接上的风险压到最低。
- 换句话说：**同线程 close** 被当作“快速路径”，不加锁，依赖调用方正确使用；**跨线程 close** 被当作“可能不安全的用法”，用全局开关把整库的 close 都升级为持锁路径，用性能换安全。

---

## 四、分支：removeAbandoned 或 asyncClose 时走 syncClose

```java
if (dataSource.removeAbandoned || dataSource.asyncCloseConnectionEnable) {
    syncClose();
    return;
}
```

**设计意图**：
- **removeAbandoned**：泄漏检测线程会主动对“借出超时”的连接调用 close。此时 close 的调用方是 **Destroy 线程**，不是 ownerThread，且可能与业务线程、其他 close 并发。若仍走无锁路径，仅靠 CLOSING_UPDATER 无法与“业务线程正在用该连接”互斥，必须用**连接级别的 lock** 串行化“使用”与“关闭”。
- **asyncCloseConnectionEnable**：上面已说明，一旦发现跨线程关闭，就把后续所有 close 都升级为持锁路径，保证同一连接上的 close 与其它操作（如 getStatement、执行 SQL）不会交错执行导致状态错乱。

**深层次含义**：
- 这里不是在“选一个方法调”，而是在**选关闭策略**：  
  - **无锁路径**：假定“同线程、且没有 removeAbandoned 线程来关这条连接”，用 CAS 防并发 close 即可。  
  - **持锁路径**：假定“存在跨线程或后台线程关连接”，用 lock 把“关这条连接”与“用这条连接”严格串行化。  
- 因此，**removeAbandoned 和 asyncClose 共用同一套“强一致”路径**，避免在复杂并发下出现“close 和 use 同时发生”的中间状态。

---

## 五、无锁路径的 CAS：CLOSING_UPDATER.compareAndSet(this, 0, 1)

```java
if (!CLOSING_UPDATER.compareAndSet(this, 0, 1)) {
    return;
}
// ... 监听器、recycle ...
} finally {
    CLOSING_UPDATER.set(this, 0);
}
```

**设计意图**：
- 在**不拿 lock** 的路径上，仍有可能是“多线程同时 close 同一个 DruidPooledConnection”（错误用法但需防御）。若不做控制，多条线程都会执行 recycle → dataSource.recycle(this)，会导致同一条物理连接被 **putLast 多次** 或 **activeCount 多次减少**，破坏池的不变式。
- **closing** 用 0/1 表示“是否有人正在执行关闭流程”。CAS(0,1) 表示“只有把 0 改成 1 的那条线程有资格执行下面的回收”；其它线程 CAS 失败直接 return，**不**执行 recycle，因此**不会**对池做任何修改。

**深层次含义**：
- 这里的 CAS 不是在“锁一下变量”，而是在实现**单次关闭的互斥**：语义是“关闭流程只允许被完整执行一次”，多线程竞争时只有一条线程执行“监听器 + recycle + disable”，其它线程静默返回，对外仍表现为“close 被调用了”，但不会二次修改池状态。
- **finally 里 set(0)** 的必要性：若不在 finally 中还原，一旦 try 里抛异常，closing 会一直为 1，后续再 close 时 CAS(0,1) 永远失败，该包装会永远处于“正在关闭”状态，既没有完成回收也没有被标记 disable（若异常在 disable=true 之前抛出），导致状态不一致。所以 set(0) 是**保证无论成功还是异常，都能恢复“可重试/可观测”状态**，避免把连接“卡死”在中间状态。

---

## 六、监听器：connectionEventListeners

```java
for (int i = 0; i < holder.connectionEventListeners.size(); i++) {
    ConnectionEventListener listener = holder.connectionEventListeners.get(i);
    listener.connectionClosed(new ConnectionEvent(this));
}
```

**设计意图**：
- 连接池或应用可能注册 **ConnectionEventListener**，需要知道“某条连接何时被关闭”，以便做**连接级**的统计、缓存清理、或与 JPA/ORM 等组件的生命周期联动。
- 在**真正把连接回池（或丢弃）之前**先通知监听器，语义是：“连接即将从‘使用中’变为‘已关闭/将回池’”，监听器可以在连接状态尚未被 reset 之前做最后一步逻辑（例如记录关闭时间、清理线程局部变量）。

**深层次含义**：
- 这里体现的是**生命周期回调**的设计：池把“连接关闭”当作一个事件暴露出去，而不是内部消化。这样扩展方（Filter、监听器）可以在不修改池核心代码的前提下，对“关闭”这一动作做出反应，满足可观测性和集成需求。

---

## 七、有 Filter 时走链：filterChain.dataSource_recycle(this)

```java
if (filtersSize > 0) {
    FilterChainImpl filterChain = holder.createChain();
    try {
        filterChain.dataSource_recycle(this);
    } finally {
        holder.recycleFilterChain(filterChain);
    }
} else {
    recycle();
}
```

**设计意图**：
- Druid 的 Filter 链在“获取连接”和“回收连接”上是对称的：getConnection 走 dataSource_connect，close 走 dataSource_recycle。这样 StatFilter、LogFilter、WallFilter 等可以在**回收**时做统计、日志、资源清理，与“借出”时的逻辑成对。
- **createChain / recycleFilterChain**：链对象会被复用（reset 后放回 holder 或数据源），避免每次 close 都 new FilterChainImpl，减少 GC 和分配开销。

**深层次含义**：
- “回收”不是池的私事，而是**可插拔的一环**：链尾会调到 **DruidDataSource.recycle(this)**，与无 Filter 时直接 **recycle()** 在“对池的最终效果”上一致（都是 dataSource.recycle），但中间多了一层 Filter 的扩展点。  
- **finally 里 recycleFilterChain** 保证无论 dataSource_recycle 是否抛异常，链都会被归还，避免链对象泄漏和后续 createChain 时拿到脏状态。

---

## 八、finally 中 CLOSING_UPDATER.set(this, 0) 与 this.disable = true

```java
} finally {
    CLOSING_UPDATER.set(this, 0);
}
this.disable = true;
```

**设计意图**：
- **set(0)**：表示“本次关闭流程结束”，无论 try 里是正常完成还是抛异常。这样设计有两个作用：  
  1）若 try 里抛异常，recycle 可能只执行了一部分或没执行，但 closing 仍会被还原，该包装不会永远卡在“正在关闭”；  
  2）若存在“先 close 抛异常，调用方重试 close”的用法，第二次 close 时 CAS(0,1) 仍可能成功，但会在第一行被 **disable** 挡住（见下）。
- **disable = true** 放在 try-finally **之外**：只有**执行完整个 try-finally 块**（无论是否异常）之后才标记“本连接已禁用”。若放在 try 里且放在 recycle 之后，一旦 recycle 抛异常，disable 可能没被设置，后续 close 会再次进入并可能再次 CAS 成功，导致重复回收。放在 finally 之后可以保证：只要**曾经进入过**关闭流程并执行完 finally，就一定会执行到 disable=true，从而后续所有 close 在入口就被拦截。

**深层次含义**：
- 这里在维护一个**顺序不变式**：先“结束关闭流程”（closing=0），再“标记连接不可用”（disable=true）。这样任何观察到 disable=true 的线程，都能推断“至少有一次关闭流程已经跑完”；而 closing=0 表示“当前没有线程正在执行关闭逻辑”，便于推理并发行为。
- 若 disable 在 try 内、recycle 之前设置，则一旦 recycle 抛异常，会变成“已标记禁用但未真正回收”，池会少一条可用连接且 activeCount 未减，造成泄漏。因此 disable 必须在“确定不会再对池做修改”之后设置，即 try-finally 之后。

---

## 九、syncClose()：为何要单独持锁

```java
public void syncClose() throws SQLException {
    lock.lock();
    try {
        if (this.disable || CLOSING_UPDATER.get(this) != 0) {
            return;
        }
        // ... 再次 holder 判空、CAS、监听器、recycle ...
        this.disable = true;
    } finally {
        CLOSING_UPDATER.set(this, 0);
        lock.unlock();
    }
}
```

**设计意图**：
- **lock** 是**连接级别**的锁，与 holder 共用。同一连接上的 getStatement、execute、close 等都会争用这把锁（或至少 close 在 syncClose 路径上会拿这把锁）。  
- removeAbandoned 线程在 close 泄漏连接时，拿的是**这条连接**的 lock；若业务线程正在该连接上执行 SQL，会拿同一把 lock。因此**谁先拿到 lock，谁先执行完**，不会出现“业务线程正在用连接，同时 Destroy 线程把连接关掉并回池”的交叉状态。
- syncClose 内部**再次**检查 disable、closing、holder：因为从 close() 判断“走 syncClose”到真正进入 syncClose 并拿锁，存在时间窗，可能已有其它线程完成了 close（例如另一条线程先拿到 lock 并执行完 recycle 和 disable），所以拿锁后必须**重新**检查状态，避免重复回收。

**深层次含义**：
- syncClose 的存在，本质上是**承认“close 的调用方不一定是 ownerThread”**，因此不能用“同线程假定”来省锁，只能用**显式锁**把“关闭”与“使用”串行化。  
- finally 里**先 set(0) 再 unlock**：保证“关闭流程结束”的可见性先于“释放锁”，这样其它线程在拿到锁之后，看到的 closing 一定已经是 0，不会误以为还有线程在关闭中。

---

## 十、recycle()：为何要判断 !this.abandoned

```java
if (!this.abandoned) {
    holder.dataSource.recycle(this);
}
this.holder = null;
conn = null;
transactionInfo = null;
closed = true;
```

**设计意图**：
- **abandoned** 由 **abandond()** 设为 true，只在 **removeAbandoned** 流程中使用：removeAbandoned 先对泄漏连接执行 **JdbcUtils.close(pooledConnection)**（此时 abandoned 仍为 false），close 内部会执行 recycle() → dataSource.recycle(this)，把物理连接**回池**；然后再对同一 **pooledConnection** 调用 **abandond()**，把 abandoned 置为 true。
- 若**不**判断 abandoned，removeAbandoned 里“先 close 再 abandond”时，close 已经触发过一次 dataSource.recycle(this)；若之后某处再次对该包装调用 close（或 recycle），会再次执行 dataSource.recycle(this)，同一条物理连接会被 **putLast 两次**，池中会出现重复连接，activeCount 也会被多减，破坏不变式。
- **abandoned 的语义**：表示“这个包装已经被当作泄漏处理过，其对应的物理连接已经通过某次 recycle 回池或丢弃，不要再对池做任何操作”。所以 recycle() 里**只有 !abandoned 才调用 dataSource.recycle**，最后**无论是否调用**，都清空 holder/conn/closed，保证该包装对象不再持有任何可用的连接引用。

**深层次含义**：
- “泄漏回收”在实现上被拆成两步：**先按正常关闭回池（close → recycle → putLast），再标记该包装为已废弃（abandond）**。这样物理连接不会泄漏（会回池复用），但包装对象被标记为“已废弃”，防止同一包装被误用或二次回收。  
- **holder=null、conn=null、closed=true** 在 if 之外执行，表示：**无论是否执行了 dataSource.recycle，本包装都与连接解耦并标记为已关闭**。这样即使 abandoned 为 true、没有调用 dataSource.recycle，该包装对外也是一致的“已关闭”状态，不会留下悬空引用。

---

## 十一、recycle() 开头的 disable 与 holder == null

```java
if (this.disable) {
    return;
}
DruidConnectionHolder holder = this.holder;
if (holder == null) {
    if (dupCloseLogEnable) {
        LOG.error("dup close");
    }
    return;
}
```

**设计意图**：
- **recycle()** 可能被 **close/syncClose** 直接调用，也可能被 Filter 链的 **dataSource_recycle** 间接调用。在 close 的 try 块里，会先执行 recycle()，再在 finally 里 set(0)，最后在 try-finally 之后设 disable=true。因此**正常顺序**下，recycle() 执行时 disable 仍为 false；若某处**重复**调用了 close 或误用了已关闭的连接，第二次进入 recycle 时可能 disable 已为 true（或 holder 已被第一次 recycle 清空）。
- **disable 为 true**：表示“本连接已经走过完整关闭流程”，不应再对池做任何操作，直接 return，且**不再执行后面的 holder=null 等**。此时 holder 通常已在第一次 recycle 时被清空，所以不执行清空也不会改变状态。
- **holder == null**：可能来自第一次 recycle 已执行完（holder 被置空），或构造异常。此时不能再调 dataSource.recycle(this)，否则 NPE；且池侧已经处理过这条连接或从未计入，所以只能 return。dupCloseLogEnable 的日志同样是**可观测性**，用于发现重复关闭或错误共享连接。

**深层次含义**：
- recycle() 的入口检查是在**防御“重复进入”**：无论是多线程重复 close，还是单线程因异常重试导致的重复调用，都应在不破坏池状态的前提下尽早退出。  
- 与 close() 入口的 disable/holder 检查形成**双重保险**：close 入口挡掉“整次 close 的重复”，recycle 入口挡掉“recycle 被多次调用”的情况（例如从不同代码路径进入 recycle）。

---

## 十二、小结：不变式与设计取舍

| 维度 | 设计意图与深层次含义 |
|------|----------------------|
| **幂等性** | disable 与“只执行一次回收”的 CAS/closing 配合，保证多次 close 只生效一次，不重复 activeCount--、不重复 putLast。 |
| **并发** | 同线程假定下用 CAS 防并发 close；跨线程或 removeAbandoned 下用 lock 把“关”与“用”串行化，避免同一连接上的交叉状态。 |
| **跨线程** | ownerThread 与 asyncCloseConnectionEnable 把“谁借谁还”的规范用法与“可能跨线程关”的用法区分开，用全局开关升级关闭策略，保证安全。 |
| **泄漏回收** | abandoned 与“先 close 再 abandond”的顺序，保证泄漏连接被回池复用，同时该包装不再触发二次回收，避免重复 putLast。 |
| **异常安全** | finally 中 set(0)、recycle 后 disable、createChain/recycleFilterChain 的 try-finally，保证异常路径下状态一致、资源可归还、不卡在中间状态。 |
| **扩展点** | 监听器与 Filter 链把“关闭”作为生命周期事件暴露，在不改池核心的前提下支持统计、日志和集成，同时链的复用控制 GC 成本。 |

以上是从**每一段代码的设计意图和深层次含义**出发的解析，而不是单纯复述“这行在做什么”。若需要把某一段再展开到“逐字逐句对应到某一条不变式”，可以指定行号或代码块继续细化。
