# DruidPooledConnection#recycle 与 DruidDataSource#recycle 详解

- **DruidPooledConnection#recycle()**：池化连接侧的“回收”入口，只做状态检查与委托，真正逻辑在数据源。
- **DruidDataSource#recycle(DruidPooledConnection)**：数据源侧的回收实现（父类 DruidAbstractDataSource 仅声明抽象方法），负责从“已借出”变为“空闲回池”或“丢弃”，并维护 activeCount、connections、activeConnections 等。

下面分方法说明职责、流程和每段含义。

---

## 一、DruidPooledConnection#recycle()

### 1.1 方法职责与调用关系

| 项目 | 说明 |
|------|------|
| 定义位置 | `com.alibaba.druid.pool.DruidPooledConnection#recycle()` |
| 调用方 | close() / syncClose()：无 Filter 时直接调；有 Filter 时由链上 dataSource_recycle 最终调到数据源的 recycle，数据源内部不会再去调本方法。 |
| 作用 | 在“本包装未禁用、holder 存在且未标记为泄漏回收”的前提下，把**本条连接**交给数据源回收（回池或丢弃），并清空本包装的 holder/conn/transactionInfo，标记 closed。 |

### 1.2 代码与逐段含义

```java
public void recycle() throws SQLException {
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

    if (!this.abandoned) {
        holder.dataSource.recycle(this);
    }

    this.holder = null;
    conn = null;
    transactionInfo = null;
    closed = true;
}
```

| 段落 | 含义 |
|------|------|
| **if (this.disable) return** | 本包装已被标记为“不可用”（例如已执行过 close/syncClose 并设了 disable）。不再交给数据源回收，也不应再修改池状态；直接返回。 |
| **holder = this.holder; if (holder == null) return** | 若 holder 已为空（例如上次 recycle 已清空，或重复调用），无法再调用 dataSource.recycle(this)，且池侧已处理过或从未计入，只能 return。dupCloseLogEnable 时打“重复关闭”日志便于排查。 |
| **if (!this.abandoned) holder.dataSource.recycle(this)** | **abandoned** 由 removeAbandoned 流程里的 **abandond()** 设为 true。只有**未**标记为“泄漏回收”的才调用 **dataSource.recycle(this)**，由数据源做一次回池或丢弃。removeAbandoned 里是“先 close（此时 abandoned 仍 false，会执行这里）再 abandond()”，避免同一连接被 dataSource.recycle 两次。 |
| **this.holder = null; conn = null; transactionInfo = null; closed = true** | 无论是否执行了 dataSource.recycle，本包装都与连接解耦并标记为已关闭，保证对象状态一致，且后续 getStatement 等会因 holder 为空或 closed 而失败，不会误用。 |

### 1.3 小结（池化连接侧）

- recycle() 是**包装对象**的“交还连接、自清状态”的方法。
- 真正决定“回池还是丢弃”的是 **DruidDataSource#recycle(DruidPooledConnection)**；池化连接侧只做：**能否回收**（disable / holder / abandoned）+ **委托 dataSource** + **清空引用、设 closed**。

---

## 二、DruidDataSource#recycle(DruidPooledConnection)

**说明**：父类 **DruidAbstractDataSource** 中仅声明 `protected abstract void recycle(DruidPooledConnection pooledConnection)`，实现在 **DruidDataSource**。下面说的“数据源 recycle”均指 **DruidDataSource#recycle**。

### 2.1 方法职责与整体流程

| 项目 | 说明 |
|------|------|
| 方法签名 | `protected void recycle(DruidPooledConnection pooledConnection) throws SQLException` |
| 作用 | 把“已借出”的这条连接收回：从 activeConnections 移除（若 traceEnable）、回滚未提交事务、重置 holder、按条件决定**回池（putLast）**或**丢弃（discardConnection）**，并维护 activeCount、closeCount、recycleCount 等。 |
| 结果 | 要么连接进入 **connections** 空闲池（putLast），要么物理连接被关闭并从池逻辑中移除（discardConnection），且 **activeCount** 减 1（若该 holder 原本 active）。 |

整体顺序可以概括为：

1. 参数与线程检查（holder、asyncClose、isSameThread、logDifferentThread）。
2. 若 traceEnable：从 activeConnections 移除并置 traceEnable=false。
3. 未提交事务则 rollback；holder.reset()（同线程/跨线程分支）。
4. 若干“提前 return”分支：holder.discard、phyMaxUseCount/密码版本、物理连接已关闭、testOnReturn 校验失败、!enable、物理超时等 → discardConnection 或仅更新计数后 return。
5. 持主锁：activeCount--、holder.active=false、closeCount++、**putLast(holder, currentTimeMillis)**；若 putLast 失败则物理关闭并打日志。
6. 若上述过程抛异常：clearStatementCache、discardConnection、打 recycle 错误日志。

---

### 2.2 代码分段说明

#### （1）holder 与线程、日志

```java
final DruidConnectionHolder holder = pooledConnection.holder;
if (holder == null) {
    LOG.warn("connectionHolder is null");
    return;
}
boolean asyncCloseConnectionEnable = this.removeAbandoned || this.asyncCloseConnectionEnable;
boolean isSameThread = pooledConnection.ownerThread == Thread.currentThread();
if (logDifferentThread && (!asyncCloseConnectionEnable) && !isSameThread) {
    LOG.warn("get/close not same thread");
}
final Connection physicalConnection = holder.conn;
```

- 取 **holder**，空则只打日志并 return（不应出现，防御性）。
- **asyncCloseConnectionEnable**：当前是否处于“允许跨线程关闭”或“泄漏检测”模式。
- **isSameThread**：当前线程是否为借出该连接的线程；后面 **holder.reset()** 在跨线程时会先拿连接锁再 reset，避免与业务并发。
- **logDifferentThread** 且非 async 且跨线程时打“借/还不同线程”的 warn，便于发现不规范用法。
- **physicalConnection**：底层 JDBC Connection，后续用于 isClosed、testOnReturn、关闭等。

#### （2）traceEnable：从 activeConnections 移除

```java
if (pooledConnection.traceEnable) {
    activeConnectionLock.lock();
    try {
        if (pooledConnection.traceEnable) {
            oldInfo = activeConnections.remove(pooledConnection);
            pooledConnection.traceEnable = false;
        }
    } finally {
        activeConnectionLock.unlock();
    }
    if (oldInfo == null) {
        LOG.warn("remove abandoned failed. activeConnections.size " + activeConnections.size());
    }
}
```

- 若该池化连接参与了泄漏追踪（借出时 put 进 activeConnections），这里从 **activeConnections** 移除并置 **traceEnable=false**，与 removeAbandoned 的 iter.remove 配合（removeAbandoned 可能已移过，这里再移一次是幂等）。
- **oldInfo == null** 表示本次 remove 没找到（例如已被 removeAbandoned 移走），打 warn 便于排查。

#### （3）rollback 与 holder.reset()

```java
if ((!isAutoCommit) && (!isReadOnly)) {
    pooledConnection.rollback();
}
if (!isSameThread) {
    lock = pooledConnection.lock;
    lock.lock();
    try {
        holder.reset();
    } finally {
        lock.unlock();
    }
} else {
    holder.reset();
}
```

- 未自动提交且非只读时 **rollback**，避免未提交事务占用连接状态。
- **holder.reset()**：恢复默认设置、清缓存、清 Warnings 等，使连接回到“可再次借出”的干净状态。跨线程时先拿**连接自己的 lock** 再 reset，避免与业务线程同时操作同一连接。

#### （4）提前 return：discard、使用次数、密码版本

```java
if (holder.discard) {
    return;
}
if ((phyMaxUseCount > 0 && holder.useCount >= phyMaxUseCount)
        || holder.userPasswordVersion < getUserPasswordVersion()) {
    discardConnection(holder);
    return;
}
```

- **holder.discard**：该连接已被别处标记为废弃（如借出校验失败、异常路径），不再回池，直接 return（池化连接侧会在 recycle() 里清空 holder/conn/closed）。
- **phyMaxUseCount**：物理连接最大复用次数，达到则丢弃并 **discardConnection**，促新连接替换。
- **userPasswordVersion**：数据源用户名/密码变更后，旧连接不再回池，**discardConnection** 后 return。

#### （5）物理连接已关闭

```java
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
```

- 底层连接已被关闭（如被服务端踢掉、超时），不能再放入池。只做 **activeCount--**、**closeCount++**，不 putLast，不再次关闭（避免重复关）。

#### （6）testOnReturn 校验

```java
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
```

- 若开启 **testOnReturn**，归还前做一次有效性检测；不通过则物理关闭、更新 destroyCount/activeCount/closeCount，不回池。

#### （7）initSchema、enable、物理超时

```java
if (holder.initSchema != null) {
    holder.conn.setSchema(holder.initSchema);
    holder.initSchema = null;
}
if (!enable) {
    discardConnection(holder);
    return;
}
// ...
if (phyTimeoutMillis > 0) {
    long phyConnectTimeMillis = currentTimeMillis - holder.connectTimeMillis;
    if (phyConnectTimeMillis > phyTimeoutMillis) {
        discardConnection(holder);
        return;
    }
}
```

- **initSchema**：若借出时记录了初始 schema，归还时恢复并清空字段。
- **!enable**：数据源已禁用，不再回池，**discardConnection** 后 return。
- **phyTimeoutMillis**：物理连接存活超过该时间则不再回池，**discardConnection** 后 return。

#### （8）回池：putLast

```java
boolean full = false;
lock.lock();
try {
    if (holder.active) {
        activeCount--;
        holder.active = false;
    }
    closeCount++;
    result = putLast(holder, currentTimeMillis);
    recycleCount++;
    if (!result) {
        full = poolingCount + activeCount >= maxActive;
    }
} finally {
    lock.unlock();
}
if (!result) {
    JdbcUtils.close(holder.conn);
    String msg = "connection recycle failed.";
    if (full) {
        msg += " pool is full";
    }
    LOG.info(msg);
}
```

- 持**数据源主锁 lock**：将 **holder.active** 置为 false 并 **activeCount--**，**closeCount++**，然后 **putLast(holder, currentTimeMillis)**。
- **putLast**：在池未满、未关闭、holder 未 discard 的前提下，把 holder 放入 **connections[poolingCount]**，**poolingCount++**，更新 **poolingPeak**，**notEmpty.signal()** 唤醒等待取连接的线程；成功返回 true，失败返回 false。
- **!result**：池满或已关闭等，则物理关闭该连接并打日志（含是否因 full）。

#### （9）异常处理

```java
} catch (Throwable e) {
    holder.clearStatementCache();
    if (!holder.discard) {
        discardConnection(holder);
        holder.discard = true;
    }
    LOG.error("recycle error", e);
    recycleErrorCountUpdater.incrementAndGet(this);
}
```

- 上述任意步骤抛异常：清空 holder 的语句缓存，若尚未 discard 则 **discardConnection(holder)** 并标记 **holder.discard**，打错误日志并增加 **recycleErrorCount**，避免坏连接回池。

---

### 2.3 与 putLast、discardConnection 的关系

| 方法 | 作用 |
|------|------|
| **putLast(holder, lastActiveTimeMillis)** | 在持主锁前提下，将 holder 放入 **connections** 尾部，**poolingCount++**，**notEmpty.signal()**，连接回到空闲池可供下次 getConnection 使用。 |
| **discardConnection(holder)** | 物理关闭 Connection（及 holder.socket），持主锁下 **activeCount--**（若 active）、**discardCount++**、**holder.discard=true**，若池低于 minIdle 则 **emptySignal(fillCount)** 触发补连。 |

recycle 中所有“不能回池”的分支（discard、超用、密码变更、已关闭、testOnReturn 失败、!enable、物理超时、putLast 失败、异常）最终要么 **discardConnection(holder)**，要么只更新计数并 return，不会把无效连接放入 **connections**。

---

### 2.4 数据源 recycle 流程简图

```
recycle(pooledConnection)
  → holder 空则 return
  → traceEnable：activeConnections.remove，traceEnable=false
  → (!isAutoCommit && !isReadOnly) → rollback
  → holder.reset()（跨线程时先拿连接 lock）
  → holder.discard → return
  → phyMaxUseCount / userPasswordVersion → discardConnection，return
  → physicalConnection.isClosed() → activeCount--, closeCount++, return
  → testOnReturn 且校验失败 → 关闭连接、activeCount--, closeCount++, return
  → initSchema 恢复；!enable → discardConnection，return
  → phyTimeoutMillis 超时 → discardConnection，return
  → lock：activeCount--, closeCount++, putLast(holder)
  → putLast 失败 → JdbcUtils.close(holder.conn)，打日志
  → 异常：clearStatementCache，discardConnection，recycleErrorCount++
```

---

## 三、两者协作关系

| 步骤 | 行为 |
|------|------|
| 1 | 业务或 removeAbandoned 调 **pooledConnection.close()**，最终进入 **DruidPooledConnection#recycle()**。 |
| 2 | **recycle()** 内若 **!abandoned** 则调 **holder.dataSource.recycle(this)**，即 **DruidDataSource#recycle(DruidPooledConnection)**。 |
| 3 | **DruidDataSource#recycle** 完成：从 activeConnections 移除、rollback、reset、按条件 **putLast** 或 **discardConnection**，并更新 activeCount/closeCount/recycleCount 等。 |
| 4 | **DruidPooledConnection#recycle()** 最后执行 **holder=null, conn=null, transactionInfo=null, closed=true**，包装与连接解耦。 |

因此：**池化连接侧的 recycle()** 负责“是否交给数据源 + 清空自身状态”；**数据源侧的 recycle(pooledConnection)** 负责“从借出清单移除、重置连接、回池或丢弃、维护池计数与唤醒等待线程”。
