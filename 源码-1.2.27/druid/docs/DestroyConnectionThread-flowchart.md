# DestroyConnectionThread 执行流程图

## 一、Mermaid 流程图

```mermaid
flowchart TD
    subgraph run["DestroyConnectionThread.run"]
        direction TB
        OP1_1["initedLatch.countDown()<br/>通知 init 线程：销毁线程已就绪"]
        OP1_1 --> LOOP_ENTRY

        subgraph LOOP["for 循环（未中断则继续）"]
            direction TB
            LOOP_ENTRY{"closed 或 closing?"}
            LOOP_ENTRY -->|是| END_THREAD["break，线程结束"]
            LOOP_ENTRY -->|否| OP2_2["线程睡眠<br/>timeBetweenEvictionRunsMillis>0 用该值，否则 1000ms"]
            OP2_2 --> OP2_3{"Thread.interrupted?"}
            OP2_3 -->|是| END_THREAD
            OP2_3 -->|否| OP1_2["destroyTask.run()"]
            OP1_2 --> LOOP_ENTRY
        end
    end

    OP1_2 --> TASK_ENTRY

    subgraph task["DestroyTask.run"]
        direction TB
        TASK_ENTRY["入口"]
        TASK_ENTRY --> OP3_1["shrink(true, keepAlive)"]
        OP3_1 --> OP3_2{"isRemoveAbandoned()?"}
        OP3_2 -->|是| OP3_3["removeAbandoned()"]
        OP3_2 -->|否| TASK_END["结束"]
        OP3_3 --> TASK_END
    end

    subgraph shrink["shrink(checkTime, keepAlive)"]
        direction TB
        S0["入口"]
        S0 --> S1{"poolingCount == 0?"}
        S1 -->|是| S_RET1["return"]
        S1 -->|否| S2["lock.lockInterruptibly()"]
        S2 --> S3{"!inited?"}
        S3 -->|是| S_RET2["return"]
        S3 -->|否| S4["checkCount=poolingCount-minIdle, remaining=0, i=0"]
        S4 --> S5["for i < poolingCount"]

        subgraph for_conn["遍历 connections[i]"]
            direction TB
            S5 --> C_FATAL["致命错误且连接早于错误时间?"]
            C_FATAL -->|是| KEEP_LIST1["keepAliveConnections++"]
            C_FATAL -->|否| C_CHECK{"checkTime?"}
            C_CHECK -->|是| C_PHY["物理连接超时?"]
            C_PHY -->|是| EVICT1["evictConnections++"]
            C_PHY -->|否| C_IDLE["idleMillis 可剔除或需保活?"]
            C_IDLE --> C_IDLE_BREAK["idle 很短?"]
            C_IDLE_BREAK -->|是| S_BREAK["break"]
            C_IDLE_BREAK -->|否| C_EVICT2["达剔除条件?"]
            C_EVICT2 -->|是| EVICT2["evictConnections++"]
            C_EVICT2 -->|否| C_KEEP["需保活检测?"]
            C_KEEP -->|是| KEEP_LIST2["keepAliveConnections++"]
            C_KEEP -->|否| RETAIN["remaining++ 保留"]
            C_CHECK -->|否| C_COUNT["i < checkCount?"]
            C_COUNT -->|是| EVICT3["evictConnections++"]
            C_COUNT -->|否| S_BREAK
        end

        S_BREAK --> S6["removeCount = evictCount + keepAliveCount"]
        S6 --> S7{"removeCount > 0?"}
        S7 -->|是| S8["arraycopy 压缩 connections, poolingCount -= removeCount"]
        S7 -->|否| S9["needFill?"]
        S8 --> S9
        S9 --> S10["lock.unlock()"]
        S10 --> S11["关闭 evictConnections 中连接"]
        S11 --> S12{"keepAliveCount > 0?"}
        S12 -->|是| S13["保活校验: 通过 put 回池，失败 discard"]
        S12 -->|否| S14["needFill?"]
        S13 --> S14
        S14 -->|是| S15["emptySignal(fillCount)"]
        S14 -->|否| S16{"fatalErrorIncrement>0?"}
        S16 -->|是| S17["emptySignal()"]
        S16 -->|否| S_END["结束"]
        S15 --> S_END
        S17 --> S_END
    end

    subgraph remove["removeAbandoned()"]
        direction TB
        R0["入口"]
        R0 --> R1{"activeConnections 为空?"}
        R1 -->|是| R_RET["return 0"]
        R1 -->|否| R2["持锁遍历 activeConnections"]
        R2 --> R3["借出时长 >= removeAbandonedTimeoutMillis?"]
        R3 --> R4["移入 abandonedList"]
        R4 --> R5["解锁"]
        R5 --> R6["关闭连接、abandond(), removeAbandonedCount++"]
        R6 --> R7{"isLogAbandoned()?"}
        R7 -->|是| R8["打泄漏日志(栈、线程状态)"]
        R7 -->|否| R_END["return"]
        R8 --> R_END
    end

    OP3_1 -.-> S0
    OP3_3 -.-> R0
```

---

## 二、流程说明表

| 模块 | 说明 |
|------|------|
| DestroyConnectionThread.run | 先 initedLatch.countDown()，再循环：检查 closed/closing → sleep → 检查中断 → destroyTask.run()，任一处退出则线程结束。 |
| DestroyTask.run | 先执行 shrink(true, keepAlive)，再根据 isRemoveAbandoned() 决定是否执行 removeAbandoned()。 |
| shrink | 持锁扫描空闲池，按致命错误/物理超时/空闲时间/保活间隔分类为 evict 或 keepAlive；压缩池、关闭被剔除连接、对保活连接校验后回池或丢弃；必要时 emptySignal 补连。 |
| removeAbandoned | 持 activeConnectionLock 扫描 activeConnections，将借出超时的连接移入 abandonedList 并关闭、abandond，可选打泄漏日志。 |

---

## 三、shrink 分支说明表

| 条件 | 结果 |
|------|------|
| poolingCount == 0 | return，不执行本次 shrink。 |
| !inited | return。 |
| 致命错误且连接建立早于错误时间 | 放入 keepAliveConnections，后续做保活校验。 |
| checkTime 且物理连接超时 | 放入 evictConnections，后续关闭。 |
| idleMillis 很短（小于 minEvictable 且小于 keepAliveBetween） | break 结束遍历。 |
| idleMillis 达剔除条件（i<checkCount 或 idle>maxEvictable） | 放入 evictConnections。 |
| keepAlive 且距上次保活>=keepAliveBetween | 放入 keepAliveConnections。 |
| 以上都不满足 | remaining++，保留在池中。 |

---

## 四、removeAbandoned 步骤表

| 步骤 | 说明 |
|------|------|
| 1 | activeConnections 为空则 return 0。 |
| 2 | 持 activeConnectionLock 遍历，!isRunning() 且借出时长>=removeAbandonedTimeoutMillis 的移入 abandonedList。 |
| 3 | 解锁后对 abandonedList 每条：close、abandond()、removeAbandonedCount++。 |
| 4 | 若 isLogAbandoned() 则打日志（owner thread、connected at、open stackTrace、current stackTrace）。 |
| 5 | return removeCount。 |
