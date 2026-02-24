# DruidPooledConnection#close 逐行详解（增强版）

对 **close()**、**syncClose()**、**recycle()** 三个方法按行逐行说明，并补充涉及字段、调用链和典型场景。

---

## 前置：涉及字段与类型说明

| 字段 / 类型 | 含义与用途 |
|-------------|------------|
| **disable** | volatile boolean。为 true 表示本池化连接已“禁用”：不能再用（包括不能再次有效 close）。close 或 syncClose 末尾会设为 true；recycle() 里也会因 holder 被清空而使得后续操作失效。 |
| **holder** | volatile DruidConnectionHolder。本包装持有的“连接持有者”，内部有物理 Connection、数据源引用、创建时间等。recycle() 末尾会置为 null。 |
| **ownerThread** | final Thread。构造时赋值为 **Thread.currentThread()**，即**借出该连接时的线程**。用于判断 close 是否由“借出线程”调用；若非借出线程调用 close，会设置 asyncCloseConnectionEnable。 |
| **CLOSING_UPDATER** | static AtomicIntegerFieldUpdater，作用于实例字段 **closing**。用于无锁路径下原子地 0→1 表示“正在关闭”，防止多线程并发 close 时重复回收。 |
| **closing** | volatile int。0=未在关闭，1=正在关闭。compareAndSet(0,1) 成功才继续执行回收逻辑；finally 里 set(0) 还原。 |
| **lock** | ReentrantLock，与 holder 共用。syncClose() 会先 lock()，保证与 removeAbandoned、statement 等对同一连接的并发操作互斥。 |
| **abandoned** | volatile boolean。removeAbandoned 里对泄漏连接会先 close 再 abandond()，abandond() 仅设 abandoned=true。recycle() 里若 abandoned 为 true 则**不再**调用 dataSource.recycle(this)，避免同一连接被回池两次。 |
| **dupCloseLogEnable** | 数据源配置：是否在“重复关闭”（如 holder 已为 null 时再次 close）时打 error 日志。 |
| **connectionEventListeners** | holder 上的监听器列表，来自 javax.sql.ConnectionEventListener。连接关闭时会收到 connectionClosed 事件，便于连接池或应用做统计、清理。 |
| **conn** | 底层物理 Connection 的引用，recycle() 末尾会置为 null。 |
| **transactionInfo** | 事务相关信息，recycle() 末尾会置为 null。 |
| **closed** | volatile boolean，recycle() 末尾设为 true，表示本包装已关闭。 |

---

## 一、close() 方法（236-289 行）逐行详解

| 行号 | 代码 | 作用 | 详细说明 |
|------|------|------|----------|
| 235 | `@Override` | 重写接口方法 | 实现 **java.sql.Connection#close()** 或 **javax.sql.PooledConnection** 的 close；业务代码调用 connection.close() 时进入本方法。 |
| 236 | `public void close() throws SQLException {` | 方法签名 | 无返回值；可能抛出 SQLException（例如 recycle 过程中 rollback、reset 等抛出的异常会向上传播）。 |
| 238 | `if (this.disable) {` | 禁用检查 | **disable** 在首次成功执行 close/syncClose 时被设为 true，或在某些异常路径被设为 true。为 true 表示本包装已不可用。 |
| 239 | `return;` | 直接返回 | 避免重复关闭、重复回收；后续所有 close 调用都会在此直接返回，不再执行业务逻辑。 |
| 240 | `}` | 结束 if | - |
| 242 | `DruidConnectionHolder holder = this.holder;` | 取 holder 引用 | **DruidConnectionHolder** 里包含：底层 Connection、所属 DruidDataSource、创建时间、lastActiveTime 等。用局部变量避免后续若被并发修改仍按“当前看到的 holder”处理。 |
| 243 | `if (holder == null) {` | holder 空检查 | 正常应在构造时赋值；为 null 可能表示：构造异常、或 recycle() 已执行并清空了 this.holder（例如重复 close）。 |
| 244 | `if (dupCloseLogEnable) {` | 是否打重复关闭日志 | 由数据源配置 **isDupCloseLogEnable()** 决定，用于排查重复 close 问题。 |
| 245 | `LOG.error("dup close");` | 打日志 | 记录“重复关闭”错误，便于定位未正确管理连接的代码。 |
| 246 | `}` | 结束内层 if | - |
| 247 | `return;` | 直接返回 | 无 holder 无法做回收，只能返回；finally 不会执行（因未进入下方 try）。 |
| 248 | `}` | 结束 holder==null 的 if | - |
| 250 | `DruidAbstractDataSource dataSource = holder.getDataSource();` | 取数据源 | 用于后续：判断 removeAbandoned、asyncCloseConnectionEnable、获取 filters、以及最终调用 dataSource.recycle(this)。 |
| 251 | `boolean isSameThread = this.ownerThread == Thread.currentThread();` | 是否借出线程 | **ownerThread** 在 **DruidPooledConnection** 构造时设为 **Thread.currentThread()**（即 getConnection 时的线程）。若当前线程与之一致则为“同线程关闭”，否则为“跨线程关闭”。 |
| 253 | `if (!isSameThread) {` | 跨线程关闭分支 | 例如：A 线程 getConnection，B 线程调 close，或 removeAbandoned 后台线程调 close，都算跨线程。 |
| 254 | `dataSource.setAsyncCloseConnectionEnable(true);` | 打开“异步关闭”开关 | 一旦发生跨线程关闭，就将数据源上的 **asyncCloseConnectionEnable** 置为 true；之后**该数据源**上所有池化连接的 **close()** 都会走 **syncClose()**（持 lock），避免跨线程并发操作同一连接导致状态错乱或重复回收。 |
| 255 | `}` | 结束 if | - |
| 257 | `if (dataSource.removeAbandoned \|\| dataSource.asyncCloseConnectionEnable) {` | 是否走持锁路径 | **removeAbandoned**：开启泄漏检测时，close 统一走 syncClose，便于与 removeAbandoned 线程配合（该线程会 close 泄漏连接）。**asyncCloseConnectionEnable**：曾发生过跨线程关闭，为安全起见后续都持锁。 |
| 258 | `syncClose();` | 调用持锁关闭 | 内部 **lock.lock()** 后再次做 disable/holder/closing 检查，然后执行与下方相同的“监听器 + 回收”逻辑，finally 中 **lock.unlock()** 并还原 closing。 |
| 259 | `return;` | 结束 close | 不再执行下面“无锁回收”逻辑，避免既持锁又用 CAS 两套机制混用。 |
| 260 | `}` | 结束 if | - |
| 262 | `if (!CLOSING_UPDATER.compareAndSet(this, 0, 1)) {` | 无锁路径的并发控制 | **CLOSING_UPDATER** 作用于本实例的 **closing** 字段。CAS 将 0 改为 1：成功表示“当前线程获得关闭权”；失败表示 closing 已非 0（其他线程正在关闭或已关闭），当前线程不应再执行回收。 |
| 263 | `return;` | 直接返回 | 避免多线程同时执行回收逻辑（重复 putLast、重复 activeCount-- 等）。 |
| 264 | `}` | 结束 if | - |
| 266 | `try {` | 包裹回收逻辑 | 保证无论正常结束还是抛异常，**finally** 都会执行，从而**一定**把 **closing** 还原为 0，否则该连接会一直处于“正在关闭”状态。 |
| 267 | `for (int i = 0; i < holder.connectionEventListeners.size(); i++) {` | 遍历连接事件监听器 | **connectionEventListeners** 一般由连接池或应用注册，用于在连接关闭时做统计、清理缓存等。 |
| 268 | `ConnectionEventListener listener = holder.connectionEventListeners.get(i);` | 取第 i 个监听器 | - |
| 269 | `listener.connectionClosed(new ConnectionEvent(this));` | 通知“连接已关闭” | 传入 **ConnectionEvent**，源为本 **DruidPooledConnection**，监听器可据此做后续处理。 |
| 270 | `}` | 结束 for | - |
| 272 | `List<Filter> filters = dataSource.filters;` | 取 Filter 列表 | Druid 的 Filter 链（如 StatFilter、LogFilter），可在“获取连接/回收连接”等节点做扩展。 |
| 273 | `int filtersSize = filters.size();` | Filter 数量 | 为 0 则不走链，直接 **recycle()**。 |
| 274 | `if (filtersSize > 0) {` | 有 Filter 时走链上回收 | 回收也会经过 Filter 链，链尾会调到 **DruidDataSource.recycle(this)**。 |
| 275 | `FilterChainImpl filterChain = holder.createChain();` | 取或创建 Filter 链 | **createChain()** 通常从 holder 或数据源取缓存的链并 reset（pos=0），避免每次 new。 |
| 276 | `try {` | 内层 try | 保证链用完后**一定**归还，防止链泄漏。 |
| 277 | `filterChain.dataSource_recycle(this);` | 链上回收 | 链会按 pos 依次调用各 Filter 的 **dataSource_recycle**，链尾调用 **DruidDataSource.recycle(this)**，内部会：从 activeConnections 移除、holder.reset、putLast(holder) 回池或 discardConnection。 |
| 278 | `} finally {` | 内层 finally | - |
| 279 | `holder.recycleFilterChain(filterChain);` | 归还 Filter 链 | 将链 reset 后放回缓存，供下次 createChain 复用。 |
| 280 | `}` | 结束 finally | - |
| 281 | `} else {` | 无 Filter | - |
| 282 | `recycle();` | 直接回收 | 调用本类 **recycle()**，内部在 **!abandoned** 时执行 **holder.dataSource.recycle(this)**，效果与链尾一致。 |
| 283 | `}` | 结束 else | - |
| 284 | `} finally {` | close 的 finally | 无论 try 中正常结束还是抛异常都会执行。 |
| 285 | `CLOSING_UPDATER.set(this, 0);` | 还原 closing | 将 **closing** 从 1 设回 0，表示“当前关闭流程结束”。若未还原，后续误用再次 close 时 CAS(0,1) 会失败并直接 return。 |
| 286 | `}` | 结束 finally | - |
| 288 | `this.disable = true;` | 标记禁用 | 本池化连接此后不可用：再次调用 close() 会在第一行 if(disable) return；其他方法（如 getStatement）也会因 holder 在 recycle() 中被置空或状态检查而失败。 |
| 289 | `}` | 结束 close 方法 | - |

---

## 二、syncClose() 方法（291-327 行）逐行详解

| 行号 | 代码 | 作用 | 详细说明 |
|------|------|------|----------|
| 291 | `public void syncClose() throws SQLException {` | 方法签名 | 供 **close()** 在 removeAbandoned 或 asyncCloseConnectionEnable 时调用；与 close() 无锁路径相比，多了一层 **lock** 保护。 |
| 292 | `lock.lock();` | 获取连接锁 | **lock** 与 **holder** 共用，同一连接上的 close、getStatement、removeAbandoned 的 close 等都会争用此锁，保证对同一连接的并发操作串行化。 |
| 293 | `try {` | 包裹主体逻辑 | 确保 **finally** 一定执行，从而**一定**执行 **lock.unlock()**，避免死锁。 |
| 294 | `if (this.disable \|\| CLOSING_UPDATER.get(this) != 0) {` | 二次状态检查 | **disable**：可能其他线程已 close 过。**closing != 0**：可能其他线程已进入关闭流程。任一满足则不再重复执行回收。 |
| 295 | `return;` | 直接返回 | 在 finally 中会执行 **CLOSING_UPDATER.set(0)** 和 **lock.unlock()**，锁仍会被释放。 |
| 296 | `}` | 结束 if | - |
| 298 | `DruidConnectionHolder holder = this.holder;` | 取 holder | 同 close()，用局部变量避免并发修改影响。 |
| 299 | `if (holder == null) {` | holder 空检查 | 同 close()。 |
| 300 | `if (dupCloseLogEnable) {` | 重复关闭日志开关 | 同 close()。 |
| 301 | `LOG.error("dup close");` | 打日志 | 同 close()。 |
| 302 | `}` | 结束内层 if | - |
| 303 | `return;` | 直接返回 | 同 close()。 |
| 304 | `}` | 结束 holder==null 的 if | - |
| 306 | `if (!CLOSING_UPDATER.compareAndSet(this, 0, 1)) {` | 关闭权 CAS | 与 close() 无锁路径相同：仅成功将 closing 从 0 改为 1 的线程继续执行，防止并发重复回收。 |
| 307 | `return;` | 直接返回 | - |
| 308 | `}` | 结束 if | - |
| 310 | `for (ConnectionEventListener listener : holder.connectionEventListeners) {` | 遍历监听器 | 同 close()。 |
| 311 | `listener.connectionClosed(new ConnectionEvent(this));` | 通知连接关闭 | 同 close()。 |
| 312 | `}` | 结束 for | - |
| 314 | `DruidAbstractDataSource dataSource = holder.getDataSource();` | 取数据源 | 同 close()。 |
| 315 | `List<Filter> filters = dataSource.getProxyFilters();` | 取代理 Filter 列表 | 与 close() 中 **dataSource.filters** 对应，这里用 **getProxyFilters()**，在代理场景下可能包含包装后的 Filter。 |
| 316 | `if (filters.size() > 0) {` | 有 Filter 时走链 | 同 close()。 |
| 317 | `FilterChainImpl filterChain = new FilterChainImpl(dataSource);` | 新建 Filter 链 | 此处**直接 new**，未用 **holder.createChain()**，与 close() 无锁路径略有不同，但目的都是走链上回收，链尾仍调到 **DruidDataSource.recycle(this)**。 |
| 318 | `filterChain.dataSource_recycle(this);` | 链上回收 | 同 close()。 |
| 319 | `} else {` | 无 Filter | - |
| 320 | `recycle();` | 直接回收 | 同 close()。 |
| 321 | `}` | 结束 else | - |
| 323 | `this.disable = true;` | 标记禁用 | 同 close()。 |
| 324 | `} finally {` | syncClose 的 finally | - |
| 325 | `CLOSING_UPDATER.set(this, 0);` | 还原 closing | 与 close() 的 finally 一致，保证 closing 一定被还原。 |
| 326 | `lock.unlock();` | 释放锁 | **必须**在 finally 中执行，否则一旦 try 中抛异常会导致锁永不释放，造成死锁。 |
| 327 | `}` | 结束 finally 和方法 | - |

---

## 三、recycle() 方法（329-351 行）逐行详解

| 行号 | 代码 | 作用 | 详细说明 |
|------|------|------|----------|
| 329 | `public void recycle() throws SQLException {` | 方法签名 | 将本 **DruidPooledConnection** 交给数据源回收（回池或丢弃），并清空本包装的 holder/conn 等；可由 close/syncClose 直接调用，或经 Filter 链的 **dataSource_recycle** 间接调用。 |
| 330 | `if (this.disable) {` | 禁用检查 | 若 close/syncClose 已设 disable，或其它路径已禁用，则不再交给数据源回收。 |
| 331 | `return;` | 直接返回 | 下面仍会执行 **holder=null、conn=null** 等清空逻辑？不会——return 后方法结束，不会执行 346-349 行。因此若 disable 为 true，本包装的 holder/conn 可能已在之前某次 recycle 中被清空。 |
| 332 | `}` | 结束 if | - |
| 334 | `DruidConnectionHolder holder = this.holder;` | 取 holder | 同前。 |
| 335 | `if (holder == null) {` | holder 空检查 | 可能已在上次 recycle 中被置空。 |
| 336 | `if (dupCloseLogEnable) {` | 重复关闭日志 | 同前。 |
| 337 | `LOG.error("dup close");` | 打日志 | 同前。 |
| 338 | `}` | 结束内层 if | - |
| 339 | `return;` | 直接返回 | 无法调用 dataSource.recycle，直接结束；346-349 行的清空**不会**执行，因为 this.holder 已为 null，通常表示之前已 recycle 过。 |
| 340 | `}` | 结束 holder==null 的 if | - |
| 342 | `if (!this.abandoned) {` | 是否未标记为泄漏回收 | **abandoned** 由 **abandond()** 设为 true。removeAbandoned 流程中：先 **close()**（此时 abandoned 仍 false，会进入本 if），再 **abandond()**。因此只有“未标记泄漏回收”的才交给数据源回池；已标记的不会再次 **dataSource.recycle(this)**，避免同一连接被 putLast 两次。 |
| 343 | `holder.dataSource.recycle(this);` | 交给数据源回收 | **DruidDataSource.recycle(this)** 内部会：从 activeConnections 移除（若 traceEnable）、rollback（若未 autoCommit）、holder.reset、根据策略 putLast(holder) 回池或 discardConnection(holder) 物理关闭并丢弃。 |
| 344 | `}` | 结束 if | - |
| 346 | `this.holder = null;` | 清空 holder 引用 | 本包装不再持有连接持有者；后续任何依赖 holder 的操作（如 getStatement、getMetaData）都会 NPE 或通过 checkState 失败。 |
| 347 | `conn = null;` | 清空底层连接引用 | 与 holder 解耦，便于 GC；且对外表现为“连接已关闭”。 |
| 348 | `transactionInfo = null;` | 清空事务信息 | 本包装上的事务上下文不再保留。 |
| 349 | `closed = true;` | 标记已关闭 | **isClosed()** 等会据此返回 true。 |
| 350 | `}` | 结束 recycle 方法 | - |

---

## 四、调用关系与数据源 recycle 简述

- **close()** 在满足 **removeAbandoned \|\| asyncCloseConnectionEnable** 时只调 **syncClose()** 并 return；否则用 **CLOSING_UPDATER** 防并发，然后执行“监听器 + 回收”。
- 回收入口有两个：**filterChain.dataSource_recycle(this)**（有 Filter）或 **recycle()**（无 Filter）。链尾会调到 **DruidDataSource.recycle(DruidPooledConnection)**。
- **DruidDataSource.recycle(this)** 会：从 **activeConnections** 移除（若 traceEnable）、rollback、**holder.reset()**、根据是否满池/是否丢弃等决定 **putLast(holder)** 或 **discardConnection(holder)**；**putLast** 会把物理连接放回 **connections** 数组并 **notEmpty.signal()**。
- **recycle()** 中 **if (!this.abandoned)** 才执行 **holder.dataSource.recycle(this)**；最后**无论是否执行**，都会执行 **holder=null、conn=null、closed=true**，保证本包装状态一致。

---

## 五、典型执行场景简表

| 场景 | 路径 | 说明 |
|------|------|------|
| 同线程、未开 removeAbandoned、首次 close | close() → 无锁路径 → 监听器 → recycle() → dataSource.recycle(this) → putLast | 正常“还连接回池”。 |
| 跨线程 close（如 B 线程关 A 线程借出的连接） | close() → setAsyncCloseConnectionEnable(true) → 本次仍可能走无锁路径；**之后**该数据源上所有 close 都走 syncClose() | 第一次跨线程可能仍无锁，之后为安全全部持锁。 |
| 开启 removeAbandoned 后业务 close | close() → syncClose() → lock → 监听器 → recycle() → dataSource.recycle(this) → putLast | 与 removeAbandoned 线程可能同时 close 不同连接，锁按连接隔离。 |
| removeAbandoned 线程关闭泄漏连接 | removeAbandoned 里 JdbcUtils.close(pooledConnection) → close() → syncClose() → recycle() → dataSource.recycle(this) → putLast；然后 abandond() | 先 close 完成回池，再 abandond() 避免该包装被二次 recycle。 |
| 重复 close（如两次 connection.close()） | 第一次：正常回收并 disable=true；第二次：close() 第一行 if(disable) return | 不会重复回池，不会重复 activeCount--。 |

---

## 六、小结

- **close()**：先做 disable/holder 检查与“是否同线程”；满足 removeAbandoned 或 asyncClose 则 **syncClose()** 并 return；否则用 **CLOSING_UPDATER** 防并发，通知监听器后走 **dataSource_recycle** 或 **recycle()**，finally 还原 closing 并设 **disable = true**。
- **syncClose()**：在 **lock** 下再次做 disable/closing/holder 检查与 CAS，通知监听器后同样走 **dataSource_recycle** 或 **recycle()**，finally 还原 closing 并 **unlock**。
- **recycle()**：在未 disable、holder 非空且 **!abandoned** 时调用 **holder.dataSource.recycle(this)** 完成回池或丢弃；最后无论是否执行了 dataSource.recycle，都清空 **holder、conn、transactionInfo** 并设 **closed = true**。
