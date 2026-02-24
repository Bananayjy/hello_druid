# removeAbandoned 与 abandond()：泄漏回收完整流程

## 一、结论先说

- **abandond()** 只做一件事：`this.abandoned = true`，标记“这个 **DruidPooledConnection 包装**”已被泄漏回收，避免后续再对同一个包装做 recycle。
- **真正的“回收”**（物理连接回到空闲池）发生在 **JdbcUtils.close(pooledConnection)** 触发的 **close() → syncClose() → recycle() → putLast(holder)** 里，在调用 **abandond()** 之前就已经完成。

---

## 二、removeAbandoned() 里对一条泄漏连接的处理顺序

```text
1. 在 activeConnectionLock 内：iter.remove()，setTraceEnable(false)，加入 abandonedList
2. 释放锁后，对 abandonedList 中每条 pooledConnection：
   a. JdbcUtils.close(pooledConnection)   ← 这里触发“回收”
   b. pooledConnection.abandond()          ← 这里只是标记包装，防止二次回收
   c. removeAbandonedCount++，removeCount++
   d. 若 isLogAbandoned() 则打日志
```

---

## 三、“回收”发生在哪里：JdbcUtils.close(pooledConnection) 的完整调用链

```text
JdbcUtils.close(pooledConnection)
  → DruidPooledConnection.close()
      因为 dataSource.removeAbandoned == true（或非同一线程 asyncClose）
  → syncClose()
  → recycle()
      因为此时 this.abandoned 仍为 false
  → holder.dataSource.recycle(this)
  → DruidDataSource.recycle(DruidPooledConnection)
```

下面按 **DruidDataSource.recycle(this)** 内部说明“回收”具体做了什么。

---

## 四、DruidDataSource.recycle(pooledConnection) 内部：真正的回收逻辑

| 步骤 | 代码位置 / 逻辑 | 说明 |
|------|------------------|------|
| 1 | holder = pooledConnection.holder | 取出连接持有者。 |
| 2 | traceEnable 为 true 时 | activeConnections.remove(pooledConnection)，pooledConnection.traceEnable = false。removeAbandoned 里已 iter.remove()，这里再 remove 一次是幂等。 |
| 3 | 事务与重置 | 若未 autoCommit 且未 readOnly 则 rollback；holder.reset() 恢复默认设置、清缓存等。 |
| 4 | 各种提前 return | holder.discard、phyMaxUseCount、物理连接已关闭、testOnReturn 校验不通过、!enable 等则 discardConnection(holder) 或直接 return，**不会** putLast。 |
| 5 | 持主锁更新计数并放回池 | lock.lock()；holder.active 则 activeCount--、holder.active = false；closeCount++；**result = putLast(holder, currentTimeMillis)**；lock.unlock()。 |
| 6 | putLast 失败 | 若 result 为 false（池满等），则 JdbcUtils.close(holder.conn) 物理关闭，不放回池。 |

**结论**：物理连接回到空闲池 = **recycle() 里的 putLast(holder, currentTimeMillis)**。也就是说，**回收发生在 DruidDataSource.recycle() → putLast(holder)**。

---

## 五、DruidPooledConnection.recycle() 里为何要判断 !this.abandoned

```java
public void recycle() throws SQLException {
    ...
    if (!this.abandoned) {
        holder.dataSource.recycle(this);  // 只有未标记为“泄漏回收”的才真正回池
    }
    this.holder = null;
    conn = null;
    ...
}
```

- removeAbandoned 的调用顺序是：**先 close()，后 abandond()**。
- close() 里会走到 **recycle()**，此时 **abandoned 还是 false**，所以会执行 **holder.dataSource.recycle(this)**，即上面说的 **putLast(holder)**，连接被回池。
- 随后 removeAbandoned 再执行 **abandond()**，把 **this.abandoned = true**。这样若以后有人误用已“泄漏回收”的 **DruidPooledConnection** 再调 close()，就不会再次调用 **dataSource.recycle(this)**，避免重复回池或状态错乱。

---

## 六、整体流程图（文字）

```text
removeAbandoned() 扫描到超时连接
    │
    ├─ activeConnections.iter.remove()  （从“已借出”集合移除）
    ├─ setTraceEnable(false)
    ├─ abandonedList.add(pooledConnection)
    └─ 释放 activeConnectionLock

对 abandonedList 中每条 pooledConnection：
    │
    ├─ JdbcUtils.close(pooledConnection)
    │   └─ DruidPooledConnection.close()
    │         └─ syncClose()  （因 removeAbandoned 或非本线程）
    │               └─ recycle()
    │                     └─ if (!this.abandoned)
    │                           └─ holder.dataSource.recycle(this)
    │                                 └─ DruidDataSource.recycle(this)
    │                                       ├─ activeConnections.remove (若 traceEnable)
    │                                       ├─ holder.reset()
    │                                       ├─ lock.lock()
    │                                       │     activeCount--, holder.active=false, closeCount++
    │                                       │     putLast(holder, currentTimeMillis)  ← 物理连接回池
    │                                       └─ lock.unlock()
    │                     └─ this.holder=null, conn=null, closed=true
    │
    ├─ pooledConnection.abandond()   ← 仅设置 abandoned=true，防止该包装被再次 recycle
    ├─ removeAbandonedCount++, removeCount++
    └─ 若 isLogAbandoned() 则打日志
```

---

## 七、小结表

| 问题 | 答案 |
|------|------|
| abandond() 做什么？ | 仅设置 **this.abandoned = true**，标记该 **DruidPooledConnection** 包装已被泄漏回收。 |
| 物理连接在哪里被回收？ | 在 **JdbcUtils.close(pooledConnection)** 触发的 **close() → syncClose() → recycle() → dataSource.recycle(this) → putLast(holder)** 中，即 **DruidDataSource.recycle()** 里的 **putLast(holder, currentTimeMillis)**。 |
| 为何先 close 再 abandond？ | close 会触发 recycle；只有 **abandoned == false** 时才会执行 **dataSource.recycle(this)** 把连接回池。先 close 完成回池，再 abandond 避免该包装被二次 recycle。 |
| activeConnections 何时移除？ | 第一次在 removeAbandoned 扫描时 **iter.remove()**；recycle() 里若 traceEnable 会再 **activeConnections.remove(pooledConnection)**（幂等）。 |

所以：**“设置为泄漏回收”的是 abandond()，“进行回收”的是前面那一次 close 所触发的 recycle() → putLast(holder)。**
