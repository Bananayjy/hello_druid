# DruidDataSource#removeAbandoned 方法解析

## 一、方法职责与调用关系

| 项目 | 说明 |
|------|------|
| 方法签名 | `public int removeAbandoned()` |
| 返回值 | 本轮回收的泄漏连接数量 |
| 作用 | 在开启 removeAbandoned 时，由 DestroyTask 周期性调用，扫描已借出未归还的连接，将借出超时且未在执行 SQL 的连接视为泄漏，从 activeConnections 移除并关闭、abandond，可选打泄漏日志。 |
| 调用方 | DestroyTask.run() 在 isRemoveAbandoned() 为 true 时调用。 |

---

## 二、相关配置与数据结构

| 名称 | 含义 |
|------|------|
| removeAbandoned | 是否开启泄漏检测（是否往 activeConnections 登记借出、是否在 DestroyTask 中调用本方法）。 |
| removeAbandonedTimeoutMillis | 借出超过该毫秒数且未在执行 SQL，视为泄漏。 |
| activeConnections | 已借出未归还的 DruidPooledConnection 集合（借出时在 getConnectionDirect 里 put，归还时 remove）。 |
| connectStackTrace / setConnectedTimeNano | 借出时记录，供本方法算借出时长和打“借出时调用栈”日志。 |

---

## 三、执行流程概览

```
removeAbandoned()
    │
    ├─ activeConnections 为空 → return 0
    │
    ├─ currrentNanos = 当前纳秒, abandonedList = 新建列表
    │
    ├─ activeConnectionLock.lock()
    │   └─ 遍历 activeConnections：
    │         isRunning() → 跳过
    │         借出时长 >= removeAbandonedTimeoutMillis → iter.remove(), setTraceEnable(false), 加入 abandonedList
    │   finally: unlock()
    │
    ├─ 对 abandonedList 中每条：
    │   └─ 持连接 lock → isDisable() 则 continue
    │   └─ JdbcUtils.close(pooledConnection), abandond(), removeAbandonedCount++, removeCount++
    │   └─ isLogAbandoned() → 打日志（owner thread, connected at, open stackTrace, current stackTrace）
    │
    └─ return removeCount
```

---

## 四、代码分段说明

### 4.1 入口与快速返回

| 代码 | 说明 |
|------|------|
| `int removeCount = 0` | 本方法本次回收数量，最后 return。 |
| `if (activeConnections.size() == 0) return removeCount` | 没有已借出连接则直接返回 0。 |

### 4.2 时间与待回收列表

| 代码 | 说明 |
|------|------|
| `long currrentNanos = System.nanoTime()` | 当前纳秒，用于与借出时间算借出时长。 |
| `List<DruidPooledConnection> abandonedList = new ArrayList<>()` | 本轮回合判定为泄漏的连接，在释放 activeConnectionLock 后再关闭和打日志，避免持锁做 I/O。 |

### 4.3 持锁扫描 activeConnections

| 代码 | 说明 |
|------|------|
| `activeConnectionLock.lock()` | 与借出/归还时使用同一把锁，保护 activeConnections。 |
| `Iterator iter = activeConnections.keySet().iterator()` | 遍历所有已借出的连接。 |
| `if (pooledConnection.isRunning()) continue` | 正在执行 SQL 的连接不视为泄漏，跳过。 |
| `timeMillis = (currrentNanos - getConnectedTimeNano()) / (1000*1000)` | 借出时长（毫秒）。 |
| `if (timeMillis >= removeAbandonedTimeoutMillis)` | 借出超时则视为泄漏。 |
| `iter.remove()` | 从 activeConnections 移除。 |
| `pooledConnection.setTraceEnable(false)` | 关闭泄漏追踪标记。 |
| `abandonedList.add(pooledConnection)` | 加入待回收列表。 |
| `finally { activeConnectionLock.unlock() }` | 确保释放锁。 |

### 4.4 关闭泄漏连接并打日志

| 代码 | 说明 |
|------|------|
| `pooledConnection.lock.lock()` | 使用连接自身锁，避免与 close/statement 等并发冲突。 |
| `if (pooledConnection.isDisable()) continue` | 已禁用则跳过，避免重复关闭。 |
| `JdbcUtils.close(pooledConnection)` | 关闭池化连接（底层物理连接会被关闭并触发回收）。 |
| `pooledConnection.abandond()` | 标记为泄漏回收，内部做状态与集合清理。 |
| `removeAbandonedCount++` | 全局泄漏回收次数。 |
| `removeCount++` | 本方法本次回收数。 |
| `if (isLogAbandoned())` | 配置为 true 时打泄漏日志。 |
| 日志内容 | owner thread 名、connected at（借出时间）、open stackTrace（借出时栈）、ownerThread current state、current stackTrace（当前线程状态与栈）。 |

---

## 五、流程小结表

| 阶段 | 动作 |
|------|------|
| 1 | activeConnections 为空 → return 0。 |
| 2 | 记录 currrentNanos，创建 abandonedList。 |
| 3 | 持 activeConnectionLock 遍历 activeConnections：isRunning() 为 true 的跳过；借出时长 >= removeAbandonedTimeoutMillis 的 iter.remove()、setTraceEnable(false)、加入 abandonedList；finally unlock。 |
| 4 | 对 abandonedList 中每条：持连接 lock，若未 isDisable() 则 JdbcUtils.close、abandond()，removeAbandonedCount++、removeCount++；若 isLogAbandoned() 则打包含借出栈与当前线程栈的 error 日志。 |
| 5 | return removeCount。 |

---

## 六、与借出时的配合

| 时机 | 行为 |
|------|------|
| 借出（getConnectionDirect，removeAbandoned 为 true） | connectStackTrace、setConnectedTimeNano()、traceEnable=true、activeConnections.put(pooledConnection, PRESENT)。 |
| 归还（recycle/close） | activeConnections 移除、traceEnable 清理。 |
| removeAbandoned() | 扫描 activeConnections，借出超时且 !isRunning() 的移入 abandonedList，关闭并 abandond，可选打日志。 |
