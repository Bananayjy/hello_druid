# druid学习文档

## 📌 项目基本信息

- **项目名称**: druid
- **GitHub地址**: https://github.com/alibaba/druid
- **版本**: 1.2.27



## 🎯 项目概述



## 📁 项目结构分析

```

```



## 🧪 使用示例

### 1、版本说明

- MySQL：8.0.19

- spring-boot-starter-web：2.7.0

- mysql-connector-java：8.0.33

- mybatis-plus-boot-starter：3.5.2

  

### 2、环境搭建





## 📖 参考文档

- 官方文档：https://github.com/alibaba/druid/wiki/%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98

- https://www.cnblogs.com/jingzh/p/16216411.html#13-%E9%85%8D%E7%BD%AE%E7%9B%B8%E5%85%B3%E5%B1%9E%E6%80%A7





## 📖学习路径

结合当前仓库结构，下面按「重要模块 + 推荐学习顺序」整理，方便你在完成 `DruidDataSourceAutoConfigure` 之后继续深入。

---

# Druid 项目重要模块与后续学习建议

## 一、项目整体结构（你当前看到的）

| 模块                            | 说明                                             | 与你已学内容的关系                                           |
| ------------------------------- | ------------------------------------------------ | ------------------------------------------------------------ |
| **core**                        | 核心库（连接池、Filter、统计、Wall、SQL 解析等） | Starter 依赖它，你看到的 `DruidDataSource`、`StatViewServlet`、各种 Filter 都在这里 |
| **druid-spring-boot-starter**   | Spring Boot 2.x 自动配置                         | 你已完成的「自动注册配置类」所在模块                         |
| **druid-spring-boot-3-starter** | Spring Boot 3.x 自动配置（JDK17+ profile）       | 与 2.x 类似，可对比学习                                      |
| **druid-wrapper**               | 对 core 的薄封装/适配                            | 可选了解                                                     |
| **druid-demo-petclinic**        | 示例应用                                         | 用于跑起来看效果                                             |

你接下来要深入的是 **core** 里和「连接池 + 监控 + 扩展」最相关的几块，并保持和 Starter 的衔接。

---

## 二、core 里比较重要的模块（按推荐学习顺序）

### 1. 连接池核心：`pool` 包（优先）

- **路径**：`core/src/main/java/com/alibaba/druid/pool/`
- **核心类**：
  - **`DruidAbstractDataSource`**：连接池抽象基类，维护 url/username/password、池参数（initialSize、maxActive、minIdle 等）、`filters`、`init()`/`close()` 等。
  - **`DruidDataSource`**：你已在 Starter 里见过，继承上面，是实际对外暴露的 `DataSource`；`init()` 里建池、启动销毁线程、加载 Filter 链等。
  - **`DruidPooledConnection`**：池化连接的包装，借出/归还、关闭语义。
  - **`DruidConnectionHolder`**：底层物理连接的持有与生命周期。
- **为什么先学**：Starter 的 `DruidDataSourceWrapper` 继承的就是 `DruidDataSource`，`afterPropertiesSet()` 最后调用的 `init()` 就在 pool 里；先搞清「池怎么建、连接怎么借还」，后面 Filter/统计 才好对上号。
- **建议**：从 `DruidDataSource#init()` 和 `getConnection()` 两条线跟进去，再看 `DruidAbstractDataSource` 的配置项和 `filters` 如何被调用。

---

### 2. Filter 机制：`filter` 包（与 Starter 的 DruidFilterConfiguration 衔接）

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

---

### 3. 统计体系：`stat` 包（监控数据从哪来）

- **路径**：`core/src/main/java/com/alibaba/druid/stat/`
- **核心**：
  - **`JdbcDataSourceStat`**：每个数据源一条统计，下面挂着 Connection/Sql/Statement 等统计。
  - **`JdbcSqlStat`**：每条 SQL 的执行次数、耗时、慢 SQL 等。
  - **`DruidStatService`**：对外提供统计数据的入口，监控页的 JSON 接口会调它（如 `DruidStatManagerFacade`）。
  - **`DruidDataSourceStatManager`**：管理多个数据源在 JMX/统计里的注册。
- **与 Starter 的关系**：Starter 打开的「Stat 监控页」和「SQL 统计」的数据，都来自这些类；StatFilter 在 Filter 链里把执行信息写入这里。
- **建议**：先搞清楚「一次 SQL 执行后，StatFilter 如何更新 JdbcSqlStat」，再看 `DruidStatService` / `DruidStatManagerFacade` 如何被 StatViewServlet 使用。

---

### 4. 监控页与 Web 统计：`support/http` 包（和 Starter 的 StatViewServlet/WebStatFilter 对应）

- **路径**：`core/src/main/java/com/alibaba/druid/support/http/`
- **核心**：
  - **`StatViewServlet`**（父类 `ResourceServlet`）：你已在 Starter 的 `statViewServletRegistrationBean` 里见过，提供监控页的 HTML/API；内部通过 `DruidStatService`/Facade 取数，并做 allow/deny、login 等。
  - **`WebStatFilter`**：对 HTTP 请求做 URI/Session 统计，和 Starter 的 `DruidWebStatFilterConfiguration` 注册的 Bean 对应。
- **support/http/stat**：WebAppStat、WebRequestStat 等，供 WebStatFilter 和监控页「Web 应用」等维度使用。
- **建议**：对照 Starter 里 `statViewServletRegistrationBean` 设置的 init 参数，在 `ResourceServlet`/`StatViewServlet` 里看 allow、deny、loginUsername、loginPassword、resetEnable 如何被读取和使用。

---

### 5. Wall 防 SQL 注入：`wall` 包（可选但很实用）

- **路径**：`core/src/main/java/com/alibaba/druid/wall/`
- **核心**：
  - **`WallFilter`**：在 Filter 链里对 SQL 做校验，非法则拒绝执行。
  - **`WallConfig`**：黑白名单、是否允许多语句等，对应 Starter 里 `filter.wall` / `filter.wall.config`。
  - **`WallProvider`**：按数据库类型做不同规则（MySQL/Oracle 等）。
- **依赖**：会用到 **`sql`** 包的解析结果（AST），所以 Wall 可以顺带让你接触到「Druid 的 SQL 解析」。
- **建议**：先看 `WallFilter` 在链中的调用点，再看一次合法 SQL 和一次非法 SQL 分别如何被放行/拒绝；若对「如何识别注入」感兴趣，再深入 `WallProvider` + `sql` 包。

---

### 6. Spring AOP 统计：`support/spring/stat` 包（和 Starter 的 DruidSpringAopConfiguration 对应）

- **路径**：`core/src/main/java/com/alibaba/druid/support/spring/stat/`
- **核心**：
  - **`DruidStatInterceptor`**：Starter 里 `advice()` 返回的增强逻辑，对匹配到的方法做耗时/调用次数统计。
  - **`SpringMethodStat`** 等：方法级统计数据结构。
- **建议**：在学完 `stat` 包后，看 `DruidStatInterceptor` 如何与 `stat` 体系挂钩，以及监控页「Spring 监控」数据从哪来。

---

### 7. SQL 解析：`sql` 包（按需深入）

- **路径**：`core/src/main/java/com/alibaba/druid/sql/`
- **内容**：各数据库方言的 Lexer、Parser、Visitor（如 `sql/dialect/mysql`），Wall 和统计里的「SQL 归一化」会用到。
- **建议**：先不系统学，等看 Wall 或「慢 SQL 合并统计」时，再按需看 `SQLUtils`、Parser 入口和 AST 结构。

---

## 三、建议的后续学习顺序（结合你已完成的自动配置）

1. **pool**：`DruidAbstractDataSource` → `DruidDataSource`（`init()`、`getConnection()`、`filters`），建立「池 + Filter 链」的整体图景。  
2. **filter**：`Filter`/`FilterChainImpl` → `StatFilter`（或一个 LogFilter），理解 Starter 里注册的 Filter 是如何被调用的。  
3. **stat**：`JdbcDataSourceStat` / `JdbcSqlStat` 与 StatFilter 的配合，以及 `DruidStatService` / `DruidStatManagerFacade` 如何被监控页使用。  
4. **support/http**：`StatViewServlet`、`ResourceServlet`，和你在 Starter 里写的 `statViewServletRegistrationBean` 一一对应看一遍。  
5. **wall**（可选）：`WallFilter` + `WallConfig`，对应 Starter 的 `filter.wall` 配置。  
6. **support/spring/stat**：`DruidStatInterceptor`，对应 Starter 的 AOP 配置。  
7. **sql**：按需在学 Wall 或慢 SQL 时再深入。

这样可以从「你已经分析过的自动配置类」自然过渡到「连接池如何工作、监控数据从哪来、Filter 如何插在 SQL 执行路径上」，形成一条完整链路。如果你希望，我可以下一步单独把「pool 包」或「Filter 链 + StatFilter」的代码阅读顺序和关键方法列成一个小清单，方便你按文件逐一看。
