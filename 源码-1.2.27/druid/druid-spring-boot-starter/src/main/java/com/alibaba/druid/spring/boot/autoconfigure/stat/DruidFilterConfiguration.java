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
package com.alibaba.druid.spring.boot.autoconfigure.stat;

import com.alibaba.druid.filter.config.ConfigFilter;
import com.alibaba.druid.filter.encoding.EncodingConvertFilter;
import com.alibaba.druid.filter.logging.CommonsLogFilter;
import com.alibaba.druid.filter.logging.Log4j2Filter;
import com.alibaba.druid.filter.logging.Log4jFilter;
import com.alibaba.druid.filter.logging.Slf4jLogFilter;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * @author lihengming [89921218@qq.com]
 */

/**
 * 各类 Druid Filter
 * 作用：在 Spring Boot 中按需注册 Druid 的各种内置 Filter（及 Wall 的 WallConfig），并把 spring.datasource.druid.filter.* 的配置绑定到对应 Bean 上。
 * 这些 Filter 会通过 DruidDataSourceWrapper#autoAddFilters(List<Filter>) 注入到 filters 链中，在执行 SQL 时依次经过这些 Filter（统计、日志、防注入等）。
 * 说明：本类是通过 DruidDataSourceAutoConfigure 的 @Import(DruidFilterConfiguration.class) 引入的，被当作「带 @Bean 的配置类」使用，等价于配置类
 */
public class DruidFilterConfiguration {

    /**
     * SQL 执行统计
     * 作用：在连接池层对 SQL 执行做统计（执行次数、耗时、慢 SQL 等），供 Druid 监控页「SQL 统计」使用。
     * 生效条件：spring.datasource.druid.filter.stat.enabled 为 true，且容器中不存在 StatFilter Bean。
     * @return
     */
    @Bean
    @ConfigurationProperties(FILTER_STAT_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_STAT_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public StatFilter statFilter() {
        return new StatFilter();
    }

    /**
     * 配置中心
     * 作用：从外部配置源（如配置中心、远程）加载/刷新配置，用于动态配置场景。
     * 生效条件：spring.datasource.druid.filter.config.enabled=true 且无已有 ConfigFilter。
     * @return
     */
    @Bean
    @ConfigurationProperties(FILTER_CONFIG_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_CONFIG_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public ConfigFilter configFilter() {
        return new ConfigFilter();
    }

    /**
     * 编解码
     * 作用：对请求/响应做编码转换（如字符集、加解密），用于特殊编码或兼容场景。
     * 生效条件：spring.datasource.druid.filter.encoding.enabled=true 且无已有 EncodingConvertFilter。
     * @return
     */
    @Bean
    @ConfigurationProperties(FILTER_ENCODING_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_ENCODING_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public EncodingConvertFilter encodingConvertFilter() {
        return new EncodingConvertFilter();
    }

    /**
     * 日志类 Filter（Slf4j / Log4j / Log4j2 / Commons-Log）
     * 作用：用对应日志框架输出 SQL 相关日志（如 SQL 文本、参数、执行时间）。
     * 生效条件：各自前缀下 enabled=true，且容器中不存在同类型 Bean。
     * @return
     */
    @Bean
    @ConfigurationProperties(FILTER_SLF4J_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_SLF4J_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public Slf4jLogFilter slf4jLogFilter() {
        return new Slf4jLogFilter();
    }
    @Bean
    @ConfigurationProperties(FILTER_LOG4J_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_LOG4J_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public Log4jFilter log4jFilter() {
        return new Log4jFilter();
    }
    @Bean
    @ConfigurationProperties(FILTER_LOG4J2_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_LOG4J2_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public Log4j2Filter log4j2Filter() {
        return new Log4j2Filter();
    }
    @Bean
    @ConfigurationProperties(FILTER_COMMONS_LOG_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_COMMONS_LOG_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public CommonsLogFilter commonsLogFilter() {
        return new CommonsLogFilter();
    }

    /**
     * WallConfig + WallFilter — SQL 防注入
     * WallConfig：防注入规则配置（如是否允许多语句、允许的语法等）
     * WallFilter：真正执行校验的 Filter，依赖 WallConfig
     * 生效条件：两者都依赖 spring.datasource.druid.filter.wall.enabled=true；且各自 @ConditionalOnMissingBean。
     * @return
     */
    @Bean
    @ConfigurationProperties(FILTER_WALL_CONFIG_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_WALL_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public WallConfig wallConfig() {
        return new WallConfig();
    }
    @Bean
    @ConfigurationProperties(FILTER_WALL_PREFIX)
    @ConditionalOnProperty(prefix = FILTER_WALL_PREFIX, name = "enabled")
    @ConditionalOnMissingBean
    public WallFilter wallFilter(WallConfig wallConfig) {
        WallFilter filter = new WallFilter();
        filter.setConfig(wallConfig);
        return filter;
    }

    /**
     * 类内常量：配置前缀
     * 每个 Bean 的「是否启用」和「属性绑定」都基于这些前缀，对应 YAML 里 spring.datasource.druid.filter.<name>.*
     */
    private static final String FILTER_STAT_PREFIX = "spring.datasource.druid.filter.stat";
    private static final String FILTER_CONFIG_PREFIX = "spring.datasource.druid.filter.config";
    private static final String FILTER_ENCODING_PREFIX = "spring.datasource.druid.filter.encoding";
    private static final String FILTER_SLF4J_PREFIX = "spring.datasource.druid.filter.slf4j";
    private static final String FILTER_LOG4J_PREFIX = "spring.datasource.druid.filter.log4j";
    private static final String FILTER_LOG4J2_PREFIX = "spring.datasource.druid.filter.log4j2";
    private static final String FILTER_COMMONS_LOG_PREFIX = "spring.datasource.druid.filter.commons-log";
    private static final String FILTER_WALL_PREFIX = "spring.datasource.druid.filter.wall";
    private static final String FILTER_WALL_CONFIG_PREFIX = FILTER_WALL_PREFIX + ".config";
}
