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

import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @author lihengming [89921218@qq.com]
 */
// DruidDataSource的包装类，集成DruidDataSource
// 绑定druid配置属性
@ConfigurationProperties("spring.datasource.druid")
// 继承DruidDataSource获得所有原生功能
// 实现InitializingBean接口进行自定义初始化
public class DruidDataSourceWrapper extends DruidDataSource implements InitializingBean {
    @Autowired
    private DataSourceProperties basicProperties;

    /**
     * 实现 InitializingBean，在 Spring 注入完属性后执行
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        //if not found prefix 'spring.datasource.druid' jdbc properties ,'spring.datasource' prefix jdbc properties will be used.
        // 如果未找到前缀为“spring.datasource.druid”的 JDBC 属性，则将使用前缀为“spring.datasource”的 JDBC 属性。
        if (super.getUsername() == null) {
            super.setUsername(basicProperties.determineUsername());
        }
        if (super.getPassword() == null) {
            super.setPassword(basicProperties.determinePassword());
        }
        if (super.getUrl() == null) {
            super.setUrl(basicProperties.determineUrl());
        }
        if (super.getDriverClassName() == null) {
            super.setDriverClassName(basicProperties.getDriverClassName());
        }

        // 调用父类 DruidDataSource#init() 真正启动连接池
        init();
    }

    /**
     * 自动注入 Filter
     * @param filters
     */
    @Autowired(required = false)
    public void autoAddFilters(List<Filter> filters) {
        // 把 Spring 容器里所有的 Druid Filter Bean 加到当前数据源的 filters 里
        super.filters.addAll(filters);
    }

    /**
     * 兼容配置绑定顺序,解决 #3084、#2763 等因属性绑定顺序导致的启动失败问题
     * Ignore the 'maxEvictableIdleTimeMillis &lt; minEvictableIdleTimeMillis' validate,
     * it will be validated again in {@link DruidDataSource#init()}.
     * <p>
     * for fix issue #3084, #2763
     *
     * @since 1.1.14
     */
    @Override
    public void setMaxEvictableIdleTimeMillis(long maxEvictableIdleTimeMillis) {
        try {
            // 重写父类 setter，避免在配置绑定阶段因“大小关系未满足”直接抛异常导致 Bean 创建失败。
            super.setMaxEvictableIdleTimeMillis(maxEvictableIdleTimeMillis);
        } catch (IllegalArgumentException ignore) {
            super.maxEvictableIdleTimeMillis = maxEvictableIdleTimeMillis;
        }
    }
}
