# DruidDataSource#registerMbean 方法详细分析

## 一、方法作用概述

`registerMbean()` 的作用是：**把当前 `DruidDataSource` 实例注册到 JVM 的 JMX（Java Management Extensions）中**，使该数据源可以通过 JMX 被监控、查询统计、执行管理操作（如 `shrink`、`resetStat` 等）。  
注册完成后会记录对应的 `ObjectName` 并设置 `mbeanRegistered = true`，与 `close()` 时的 `unregisterMbean()` 成对，保证生命周期正确、不泄漏。

---

## 二、方法源码与调用时机

### 2.1 源码位置与实现

- **类**：`com.alibaba.druid.pool.DruidDataSource`
- **方法**：`registerMbean()`

```java
public void registerMbean() {
    if (!mbeanRegistered) {
        AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                ObjectName objectName = DruidDataSourceStatManager.addDataSource(DruidDataSource.this,
                        DruidDataSource.this.name);

                DruidDataSource.this.setObjectName(objectName);
                DruidDataSource.this.mbeanRegistered = true;

                return null;
            }
        });
    }
}
```

要点：

1. **幂等**：仅当 `mbeanRegistered == false` 时才执行注册。
2. **权限**：在 `AccessController.doPrivileged` 中执行，避免调用方没有 JMX 权限导致注册失败。
3. **实际工作**：委托给 `DruidDataSourceStatManager.addDataSource(this, this.name)`，拿到 `ObjectName` 后保存并标记已注册。

### 2.2 调用时机

在 `DruidDataSource.init()` 中，**初始化成功**（`init = true`、`initedTime` 已设置）后调用：

```java
// 标记成功
init = true;
initedTime = new Date();
// 注册 MBean，便于 JMX 监控与运维，与 close 时的 unregister 成对
registerMbean();
```

因此：**每个完成 init 的 DruidDataSource 实例，都会在此时注册到 JMX**（除非关闭了 MBean 注册或未调用 init）。

---

## 三、涉及的核心类与各自作用

### 3.1 DruidDataSource（本类）

- **角色**：被管理的“资源”，同时作为 MBean 实例注册到 JMX。
- **与 MBean 的关系**：
  - 实现 `DruidDataSourceMBean`（继承 `DruidAbstractDataSourceMBean`），即 **MBean 接口**。
  - 实现 `javax.management.MBeanRegistration`，在注册前会走 `preRegister`（若 JMX 里已有同名 MBean 会先 unregister 再注册）。
- **字段**：
  - `mbeanRegistered`：是否已注册到 JMX。
  - `objectName`：在 JMX 中的名字（由 `setObjectName` 设置，`getObjectName()` 暴露给 MBean 接口）。
- **作用**：提供连接池能力，并通过 MBean 接口暴露统计、配置、管理方法（如 `getActiveCount()`、`shrink()`、`resetStat()` 等）给 JMX 客户端。

### 3.2 DruidDataSourceStatManager（核心：真正做注册与登记）

- **包路径**：`com.alibaba.druid.stat.DruidDataSourceStatManager`
- **角色**：**全局单例**，负责：
  1. 维护“所有已注册的 Druid 数据源”与对应 `ObjectName` 的映射；
  2. 在**第一个**数据源注册时，顺带注册“统计汇总 MBean”和 `DruidStatService`；
  3. 调用平台 `MBeanServer` 完成 `registerMBean(dataSource, objectName)`。

**关键方法**：`addDataSource(Object dataSource, String name)`

- 获取/初始化 `instances`（Map：DataSource → ObjectName）。
- **若当前是第一个数据源**（`instances.size() == 0`）：
  - 注册 `DruidDataSourceStatManager` 自身为 MBean，名：`com.alibaba.druid:type=DruidDataSourceStat`；
  - 调用 `DruidStatService.registerMBean()`，注册 `com.alibaba.druid:type=DruidStatService`。
- **为当前 dataSource 生成 ObjectName 并注册**：
  - 若 `name != null`：`com.alibaba.druid:type=DruidDataSource,id=<name>`；
  - 否则：`com.alibaba.druid:type=DruidDataSource,id=<System.identityHashCode(dataSource)>`；
  - 执行 `mbeanServer.registerMBean(dataSource, objectName)`。
- 将 `(dataSource, objectName)` 放入 `instances`，并返回 `objectName`。

因此：**“registerMbean 到底干什么”在实现上就是：通过 DruidDataSourceStatManager 把当前 DruidDataSource 注册到平台 MBeanServer，并登记到全局 instances 中。**

### 3.3 MBeanServer（JMX 容器）

- **来源**：`ManagementFactory.getPlatformMBeanServer()`（JVM 内置平台 MBeanServer）。
- **作用**：JMX 的“注册表”，所有 MBean 都注册在这里；监控工具（JConsole、VisualVM、Zabbix JMX 等）通过它发现并操作 MBean。
- **在 Druid 中的使用**：由 `DruidDataSourceStatManager` 和 `DruidStatService` 获取并调用 `registerMBean(unregisterMBean)`。

### 3.4 ObjectName（JMX 中的“名字”）

- **包路径**：`javax.management.ObjectName`
- **作用**：在 MBeanServer 中唯一标识一个 MBean。格式通常为 `domain:key=value[,key=value...]`，例如：
  - `com.alibaba.druid:type=DruidDataSource,id=myDS`
  - `com.alibaba.druid:type=DruidDataSourceStat`
  - `com.alibaba.druid:type=DruidStatService`
- **在 registerMbean 中**：由 `DruidDataSourceStatManager.addDataSource` 创建并返回，再通过 `setObjectName(objectName)` 存到 `DruidDataSource` 中，便于后续 unregister 或 JMX 查询。

### 3.5 DruidDataSourceMBean / DruidAbstractDataSourceMBean（MBean 接口）

- **DruidAbstractDataSourceMBean**：定义连接池的通用“可读/可写”属性与统计（如 url、activeCount、createCount、各种池参数、事务统计等）。
- **DruidDataSourceMBean**：继承上述接口，并增加 Druid 扩展的运维能力，例如：
  - `shrink()`、`resetStat()`、`removeAbandoned()`、`fill()`；
  - `getObjectName()`、`getWaitThreadCount()`、`getNotEmptyWaitCount()`、`dump()` 等。
- **作用**：约定“通过 JMX 可以访问哪些属性和操作”；`DruidDataSource` 实现这些接口，因此注册到 MBeanServer 后，JMX 客户端就能按标准方式读写属性、调用方法。

### 3.6 DruidStatService（全局统计服务 MBean）

- **包路径**：`com.alibaba.druid.stat.DruidStatService`
- **注册时机**：在 `DruidDataSourceStatManager.addDataSource` 中，当**第一个**数据源被添加时调用 `DruidStatService.registerMBean()`。
- **ObjectName**：`com.alibaba.druid:type=DruidStatService`
- **作用**：提供**跨所有数据源**的统计与 JSON 等汇总接口，供监控页或外部系统通过 JMX 调用。

### 3.7 DataSourceMonitorable（可被监控的约定）

- **包路径**：`com.alibaba.druid.stat.DataSourceMonitorable`
- **作用**：接口约定“可被 Druid 统计与监控”的数据源必须提供：`resetStat()`、`getObjectName()`、`getCompositeData()`、`getPoolingConnectionInfo()` 等。`DruidDataSource` 通过实现该接口并配合 `DruidDataSourceStatManager.addDataSource/removeDataSource`，纳入统一监控与列表展示。

### 3.8 MBeanRegistration（生命周期回调）

- **包路径**：`javax.management.MBeanRegistration`
- **在 DruidDataSource 中的实现**：
  - `preRegister(MBeanServer server, ObjectName name)`：若 server 中已存在同名 MBean，先 `unregisterMBean(name)` 再返回 name，避免重复注册报错。
  - `postRegister` / `preDeregister` / `postDeregister`：可做注册后/注销前后逻辑，当前实现为空或简单处理。
- **作用**：在 JMX 注册/注销前后插入自定义逻辑，这里主要用于“同名则先删再注”。

### 3.9 AccessController.doPrivileged（权限）

- **作用**：以“特权”执行注册逻辑，避免调用栈上某些代码没有 `MBeanServerPermission` 等权限时导致 `registerMBean` 失败。保证由 Druid 主动注册时不受外部权限限制。

### 3.10 JMXUtils（工具类，本方法未直接使用）

- **包路径**：`com.alibaba.druid.util.JMXUtils`
- **作用**：提供通用的 `register(name, mbean)` / `unregister(name)`，内部同样使用 `ManagementFactory.getPlatformMBeanServer()` 和 `ObjectName`。Druid 数据源注册没有直接用 JMXUtils，而是统一走 `DruidDataSourceStatManager.addDataSource`，以便维护 instances 和首次注册时顺带注册 Stat/StatService。

---

## 四、整体流程小结

1. **DruidDataSource.init() 成功**后调用 `registerMbean()`。
2. 若尚未注册（`!mbeanRegistered`），在 `AccessController.doPrivileged` 中：
   - 调用 **DruidDataSourceStatManager.addDataSource(this, this.name)**：
     - 若是第一个数据源：注册 `DruidDataSourceStat` MBean 和 **DruidStatService** MBean；
     - 为当前数据源生成 **ObjectName**（带 name 或 identityHashCode）；
     - 使用 **MBeanServer.registerMBean(dataSource, objectName)** 注册；
     - 将 (dataSource, objectName) 放入全局 instances。
   - 对当前 **DruidDataSource** 执行 **setObjectName(objectName)**、**mbeanRegistered = true**。
3. 之后，JMX 客户端可通过 `ObjectName` 发现该数据源，并调用 **DruidDataSourceMBean** 的 getter/setter 和方法（如 `shrink`、`resetStat`），实现监控与运维。

**一句话**：`registerMbean` 把当前数据源注册到 JMX（由 DruidDataSourceStatManager 统一管理并调用 MBeanServer），并顺带在首次注册时把全局统计相关 MBean 也注册上去，便于监控和运维。

---

## 五、与 unregisterMbean 的对应关系

- **unregisterMbean()**：在 `DruidDataSource.close()` 中被调用；内部通过 `DruidDataSourceStatManager.removeDataSource(this)` 从 instances 移除并调用 `mbeanServer.unregisterMBean(objectName)`，最后设置 `mbeanRegistered = false`。
- 这样保证：**一个 DataSource 从 init 到 close 只注册一次、关闭时注销一次**，不会在 JMX 中残留或重复注册。
