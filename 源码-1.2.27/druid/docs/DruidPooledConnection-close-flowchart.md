# DruidPooledConnection#close 流程图

```mermaid
flowchart TD
    subgraph close["DruidPooledConnection.close"]
        direction TB
        C0["close 入口"]
        C1{"this.disable?"}
        C2["return，避免重复关闭"]
        C3["holder = this.holder"]
        C4{"holder == null?"}
        C5["dupCloseLogEnable 时 LOG.error<br/>return"]
        C6["dataSource = holder.getDataSource()<br/>isSameThread = ownerThread == 当前线程"]
        C7{"!isSameThread?"}
        C8["setAsyncCloseConnectionEnable(true)"]
        C9{"removeAbandoned 或<br/>asyncCloseConnectionEnable?"}
        C10["syncClose()<br/>return"]
        C11{"CLOSING_UPDATER.compareAndSet(0,1)<br/>失败?"}
        C12["return，其他线程正在关闭"]
        C13["try: 遍历 connectionEventListeners<br/>listener.connectionClosed"]
        C14{"filtersSize > 0?"}
        C15["filterChain = holder.createChain()<br/>filterChain.dataSource_recycle(this)<br/>finally: recycleFilterChain"]
        C16["recycle()"]
        C17["finally: CLOSING_UPDATER.set(0)"]
        C18["this.disable = true"]
    end

    C0 --> C1
    C1 -->|是| C2
    C1 -->|否| C3
    C3 --> C4
    C4 -->|是| C5
    C4 -->|否| C6
    C6 --> C7
    C7 -->|是| C8
    C7 -->|否| C9
    C8 --> C9
    C9 -->|是| C10
    C9 -->|否| C11
    C11 -->|是| C12
    C11 -->|否| C13
    C13 --> C14
    C14 -->|是| C15
    C14 -->|否| C16
    C15 --> C17
    C16 --> C17
    C17 --> C18

    C10 -.-> SYNC_ENTRY

    subgraph syncClose["syncClose"]
        direction TB
        SYNC_ENTRY["syncClose 入口"]
        S1["lock.lock()"]
        S2{"disable 或 closing != 0?"}
        S3["return，finally 中 unlock"]
        S4["holder == null?"]
        S5["return"]
        S6{"CAS(0,1) 失败?"}
        S7["return"]
        S8["遍历 connectionEventListeners<br/>connectionClosed"]
        S9{"filters.size() > 0?"}
        S10["FilterChainImpl + dataSource_recycle(this)"]
        S11["recycle()"]
        S12["this.disable = true"]
        S13["finally: set(0), lock.unlock()"]
    end

    S1 --> S2
    S2 -->|是| S3
    S2 -->|否| S4
    S4 -->|是| S5
    S4 -->|否| S6
    S6 -->|是| S7
    S6 -->|否| S8
    S8 --> S9
    S9 -->|是| S10
    S9 -->|否| S11
    S10 --> S12
    S11 --> S12
    S12 --> S13

    S10 -.-> RECYCLE_ENTRY
    S11 -.-> RECYCLE_ENTRY
    C15 -.-> RECYCLE_ENTRY
    C16 -.-> RECYCLE_ENTRY

    subgraph recycle["DruidPooledConnection.recycle"]
        direction TB
        RECYCLE_ENTRY["recycle 入口"]
        R1{"this.disable?"}
        R2["return"]
        R3["holder = this.holder"]
        R4{"holder == null?"}
        R5["return"]
        R6{"!this.abandoned?"}
        R7["holder.dataSource.recycle(this)"]
        R8["this.holder = null<br/>conn = null<br/>transactionInfo = null<br/>closed = true"]
    end

    RECYCLE_ENTRY --> R1
    R1 -->|是| R2
    R1 -->|否| R3
    R3 --> R4
    R4 -->|是| R5
    R4 -->|否| R6
    R6 -->|是| R7
    R6 -->|否| R8
    R7 --> R8

    R7 -.-> DS_RECYCLE

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

## 简要说明

- **close()**：先做 disable/holder 检查；非借出线程则 setAsyncCloseConnectionEnable(true)；满足 removeAbandoned 或 asyncClose 则走 **syncClose()** 并 return；否则 CAS 抢关闭权，通知监听器后走 **dataSource_recycle** 或 **recycle()**，finally 还原 closing，最后 disable=true。
- **syncClose()**：持连接 lock，再次检查 disable/closing/holder 与 CAS，通知监听器后同样走 dataSource_recycle 或 **recycle()**，finally 还原 closing 并 unlock。
- **recycle()**：未 disable、holder 非空且 !abandoned 时调用 **holder.dataSource.recycle(this)**；最后统一清空 holder/conn 并设 closed=true。
- **DruidDataSource.recycle**：从 activeConnections 移除、rollback、reset，按条件 discard 或 **putLast** 回池，异常时 discardConnection。
