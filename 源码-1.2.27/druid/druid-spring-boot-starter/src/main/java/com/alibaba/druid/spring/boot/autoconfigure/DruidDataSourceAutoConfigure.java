/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.spring.boot.autoconfigure;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.spring.boot.autoconfigure.properties.DruidStatProperties;
import com.alibaba.druid.spring.boot.autoconfigure.stat.DruidFilterConfiguration;
import com.alibaba.druid.spring.boot.autoconfigure.stat.DruidSpringAopConfiguration;
import com.alibaba.druid.spring.boot.autoconfigure.stat.DruidStatViewServletConfiguration;
import com.alibaba.druid.spring.boot.autoconfigure.stat.DruidWebStatFilterConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

/**
 * Druid数据库连接池Spring Boot Starter的核心自动配置类
 * 负责整合Druid数据源与Spring Boot框架
 * @author lihengming [89921218@qq.com]
 */
// 标记此类为Spring配置类，会被Spring容器管理
@Configuration
// 当 spring.datasource.type=com.alibaba.druid.pool.DruidDataSource 时生效
// 即使没有显式配置，默认也会使用Druid数据源
@ConditionalOnProperty(name = "spring.datasource.type",
        havingValue = "com.alibaba.druid.pool.DruidDataSource",
        matchIfMissing = true)
// 确保classpath中有DruidDataSource类才启用配置
@ConditionalOnClass(DruidDataSource.class)
// 在Spring Boot默认的数据源自动配置之前执行，确保Druid配置优先级更高
@AutoConfigureBefore(DataSourceAutoConfiguration.class)
// 启用Druid统计属性和标准数据源属性的配置绑定
@EnableConfigurationProperties({DruidStatProperties.class, DataSourceProperties.class})
/**
 *  通过 @Import 引入了四个与监控、统计、过滤相关的配置类
 * 1.DruidSpringAopConfiguration： Spring AOP 监控，方法级监控
 * 2.DruidStatViewServletConfiguration：监控控制台 Servlet
 * 3.DruidWebStatFilterConfiguration：Web 请求统计 Filter
 * 4.DruidFilterConfiguration：各类 Druid Filter
 */
@Import({DruidSpringAopConfiguration.class,
        DruidStatViewServletConfiguration.class,
        DruidWebStatFilterConfiguration.class,
        DruidFilterConfiguration.class})
public class DruidDataSourceAutoConfigure {
    private static final Logger LOGGER = LoggerFactory.getLogger(DruidDataSourceAutoConfigure.class);

    /**
     * 数据源Bean创建
     * Not setting initMethod of annotation {@code @Bean} is to avoid failure when inspecting
     * the bean definition at the build time. The {@link DruidDataSource#init()} will be called
     * at the end of {@link DruidDataSourceWrapper#afterPropertiesSet()}.
     * 解释了为什么没有在 @Bean 上配置 initMethod，以及初始化实际是在哪里完成的：
     * @Bean 可以带 initMethod，例如：@Bean(initMethod = "init")：Spring在构建阶段解析 Bean 定义时就会去“检查”这个初始化方法（方法是否存在、是否可调用等）
     * 在这个场景下，那样做可能导致在构建/配置阶段就失败（例如方法解析、类型或生命周期顺序等问题），所以故意不在 @Bean 上设置 initMethod
     * DruidDataSource#init() 会在 DruidDataSourceWrapper#afterPropertiesSet() 的末尾会调用 init()。
     * @return druid data source wrapper
     */
    @Bean
    // 使用 @ConditionalOnMissingBean 确保不会重复创建数据源Bean
    // 当DruidDataSourceWrapper、DruidDataSource、DataSource三个类型的Bean对象都不存在的情况下创建
    @ConditionalOnMissingBean({DruidDataSourceWrapper.class,
        DruidDataSource.class,
        DataSource.class})
    public DruidDataSourceWrapper dataSource() {
        LOGGER.info("Init DruidDataSource");
        // 返回 DruidDataSourceWrapper
        return new DruidDataSourceWrapper();
    }
}
