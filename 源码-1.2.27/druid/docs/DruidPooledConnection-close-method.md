# DruidPooledConnection#close 方法解析

## 一、方法职责与入口

| 项目 | 说明 |
|------|------|
| 方法 | `public void close() throws SQLException` |
| 作用 | 业务调用“关闭连接”时触发；将池化连接归还给数据源（回池或丢弃），并清理本包装的状态。若为“非借出线程”关闭或开启 removeAbandoned，则走 **syncClose()**（持连接自身锁），否则走**无锁的回收路径**。 |
| 结果 | 连接回到空闲池（recycle → putLast）或物理关闭（discard）；本对象 **disable = true**，**holder/conn** 在 **recycle()** 末尾被置空。 |

---

## 二、close() 入口与分支（逐段）

### 2.1 前置检查

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

| 代码 | 说明 |
|------|------|
| `this.disable` | 已关闭或已禁用（如 abandond 后、重复 close），直接 return，避免重复回收。 |
| `holder == null` | 构造后未赋值或 recycle() 已清空，无法回收；可选打“重复关闭”日志后 return。 |

### 2.2 是否“同线程”与 asyncClose 标记

```java
DruidAbstractDataSource dataSource = holder.getDataSource();
boolean isSameThread = this.ownerThread == Thread.currentThread();

if (!isSameThread) {
    dataSource.setAsyncCloseConnectionEnable(true);
}
```

| 代码 | 说明 |
|------|------|
| **ownerThread** | 借出连接时的线程（getConnection 时 `Thread.currentThread()`），保存在 DruidPooledConnection 构造时。 |
| **isSameThread** | 当前调用 close() 的线程是否就是借出该连接的线程。 |
| **!isSameThread 时 setAsyncCloseConnectionEnable(true)** | 标记数据源“存在跨线程关闭”，后续该数据源上的池化连接在 close 时都会走 **syncClose()**，保证跨线程关闭时持锁，避免与业务线程并发操作同一连接导致状态错乱。 |

### 2.3 分支一：走 syncClose()（持锁回收）

```java
if (dataSource.removeAbandoned || dataSource.asyncCloseConnectionEnable) {
    syncClose();
    return;
}
```

| 条件 | 说明 |
|------|------|
| **removeAbandoned** | 开启了泄漏检测时，close 统一走 syncClose，便于与 removeAbandoned 线程配合（removeAbandoned 里会 close 泄漏连接）。 |
| **asyncCloseConnectionEnable** | 曾发生过“非借出线程”关闭连接，为安全起见后续 close 都持锁。 |

满足任一则执行 **syncClose()** 后 return，不再走下面的“无锁回收”路径。

### 2.4 分支二：无锁回收路径（同线程且未开 removeAbandoned）

```java
if (!CLOSING_UPDATER.compareAndSet(this, 0, 1)) {
    return;
}
try {
    for (...) {
        listener.connectionClosed(new ConnectionEvent(this));
    }
    List<Filter> filters = dataSource.filters;
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
} finally {
    CLOSING_UPDATER.set(this, 0);
}
this.disable = true;
```

| 代码 | 说明 |
|------|------|
| **CLOSING_UPDATER.compareAndSet(this, 0, 1)** | 将 closing 从 0 置为 1，表示“正在关闭”；若已是 1（并发 close），则直接 return，防止重复回收。 |
| **connectionEventListeners** | 通知监听器连接已关闭（如连接池的 ConnectionEventListener）。 |
| **filtersSize > 0** | 有 Filter 时通过 **filterChain.dataSource_recycle(this)** 走链上回收，链尾会调回 **DruidDataSource.recycle(this)**。 |
| **else recycle()** | 无 Filter 时直接 **recycle()**，内部会调 **holder.dataSource.recycle(this)**。 |
| **finally CLOSING_UPDATER.set(this, 0)** | 无论正常还是异常，都将 closing 还原为 0。 |
| **this.disable = true** | 标记本连接包装已禁用，后续 close 直接 return。 |

---

## 三、syncClose() 详解

当 **removeAbandoned** 或 **asyncCloseConnectionEnable** 为 true 时，close() 会转调 **syncClose()**，在**持连接自身 lock** 的前提下做与上面相同的“监听器 + 回收”逻辑，避免与 removeAbandoned 线程或其他线程并发操作同一连接。

```java
public void syncClose() throws SQLException {
    lock.lock();
    try {
        if (this.disable || CLOSING_UPDATER.get(this) != 0) {
            return;
        }
        DruidConnectionHolder holder = this.holder;
        if (holder == null) {
            if (dupCloseLogEnable) {
                LOG.error("dup close");
            }
            return;
        }
        if (!CLOSING_UPDATER.compareAndSet(this, 0, 1)) {
            return;
        }
        for (ConnectionEventListener listener : holder.connectionEventListeners) {
            listener.connectionClosed(new ConnectionEvent(this));
        }
        DruidAbstractDataSource dataSource = holder.getDataSource();
        List<Filter> filters = dataSource.getProxyFilters();
        if (filters.size() > 0) {
            FilterChainImpl filterChain = new FilterChainImpl(dataSource);
            filterChain.dataSource_recycle(this);
        } else {
            recycle();
        }
        this.disable = true;
    } finally {
        CLOSING_UPDATER.set(this, 0);
        lock.unlock();
    }
}
```

| 步骤 | 说明 |
|------|------|
| **lock.lock()** | 使用本连接的 **lock**（与 holder 共用），保证与 statement/其他 close 等操作的互斥。 |
| **disable / CLOSING_UPDATER.get(this) != 0** | 已禁用或正在关闭则直接 return。 |
| **holder == null** | 同 close()，无法回收则 return。 |
| **compareAndSet(0, 1)** | 同 close()，防止并发 close。 |
| **connectionEventListeners** | 同 close()，通知连接关闭。 |
| **getProxyFilters()** | 获取代理 Filter 列表；有 Filter 则 **filterChain.dataSource_recycle(this)**，否则 **recycle()**。 |
| **disable = true** | 同 close()。 |
| **finally** | **CLOSING_UPDATER.set(0)** 并 **lock.unlock()**，保证锁与 closing 状态一定释放、还原。 |

与 **close()** 的无锁路径相比，**syncClose()** 只是在**前后加锁**，并多一次 **disable / closing / holder 判空**，核心回收逻辑一致：最终都落到 **dataSource_recycle(this)** 或 **recycle()**。

---

## 四、recycle() 详解

无论从 close() 的无锁路径还是 syncClose() 进来，最终都会执行 **recycle()**（有 Filter 时通过链调回数据源的 recycle，数据源内部会做 putLast 等，这里指 **DruidPooledConnection.recycle()**）。

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

| 代码 | 说明 |
|------|------|
| **this.disable** | 已禁用则不再交给数据源回收。 |
| **holder == null** | 无持有者则只做清理。 |
| **if (!this.abandoned)** | 仅当**未**被标记为“泄漏回收”时，才调用 **holder.dataSource.recycle(this)**，把连接真正回池（putLast）或按策略丢弃。removeAbandoned 里先 close（此时 abandoned 仍 false，会执行 recycle），再 **abandond()**，避免重复回池。 |
| **holder = null; conn = null; transactionInfo = null; closed = true** | 无论是否执行了 dataSource.recycle，都清空本包装对 holder/conn 的引用并标记 closed，本包装不再可用。 |

---

## 五、整体流程简图

```
close()
  ├─ disable 或 holder==null → return
  ├─ !isSameThread → setAsyncCloseConnectionEnable(true)
  ├─ removeAbandoned || asyncCloseConnectionEnable
  │     └─ syncClose()
  │           ├─ lock.lock()
  │           ├─ disable / closing / holder 检查
  │           ├─ 监听器 connectionClosed
  │           ├─ 有 Filter → filterChain.dataSource_recycle(this)
  │           │   无 Filter → recycle()
  │           ├─ disable = true
  │           └─ finally: closing=0, lock.unlock()
  │
  └─ 否则（同线程且未开 removeAbandoned）
        ├─ CLOSING_UPDATER.compareAndSet(0,1)，失败则 return
        ├─ 监听器 connectionClosed
        ├─ 有 Filter → filterChain.dataSource_recycle(this)
        │   无 Filter → recycle()
        ├─ finally: CLOSING_UPDATER.set(0)
        └─ disable = true

recycle()
  ├─ disable 或 holder==null → return（或仅打日志）
  ├─ if (!abandoned) → holder.dataSource.recycle(this)  // 真正回池/丢弃
  └─ holder=null, conn=null, transactionInfo=null, closed=true
```

---

## 六、小结表

| 点 | 说明 |
|------|------|
| **两条路径** | 满足 removeAbandoned 或 asyncCloseConnectionEnable 时走 **syncClose()**（持 lock）；否则走无锁路径，用 **CLOSING_UPDATER** 防并发。 |
| **回收入口** | 有 Filter 时 **filterChain.dataSource_recycle(this)**，无 Filter 时 **recycle()**；recycle() 内再调 **holder.dataSource.recycle(this)** 完成回池或丢弃。 |
| **abandoned 作用** | recycle() 里只有 **!this.abandoned** 才执行 dataSource.recycle(this)；removeAbandoned 先 close（执行 recycle 回池）再 abandond()，避免同一连接被回收两次。 |
| **ownerThread / asyncClose** | 非借出线程调用 close 时设置 asyncCloseConnectionEnable，之后该数据源上的 close 都走 syncClose，保证跨线程关闭安全。 |
