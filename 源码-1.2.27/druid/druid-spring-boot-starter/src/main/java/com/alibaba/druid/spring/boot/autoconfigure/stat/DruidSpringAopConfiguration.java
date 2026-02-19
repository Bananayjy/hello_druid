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

import com.alibaba.druid.spring.boot.autoconfigure.properties.DruidStatProperties;
import com.alibaba.druid.support.spring.stat.DruidStatInterceptor;
import org.aopalliance.aop.Advice;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.RegexpMethodPointcutAdvisor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * @author lihengming [89921218@qq.com]
 */
/**
 * Spring AOP 监控
 * 作用：用于指定要监控的 Spring Bean 方法，通过 Spring AOP 对匹配的方法进行拦截，用 DruidStatInterceptor 做方法级监控（调用次数、耗时等），并和 Druid 统计体系打通
 * 说明：只有配置了 spring.datasource.druid.aop-patterns 时，该配置类才会生效
 * 配置示例：
 * spring:
 *   datasource:
 *     druid:
 *       aop-patterns: "com.example.service.*,com.example.dao.*"
 */
@ConditionalOnProperty("spring.datasource.druid.aop-patterns")
public class DruidSpringAopConfiguration {

    /**
     * 增强逻辑，负责记录方法执行统计
     * @return
     */
    @Bean
    public Advice advice() {
        // 用 DruidStatInterceptor 做统计
        return new DruidStatInterceptor();
    }

    /**
     * 切面：用 aop-patterns 做正则匹配，对匹配到的方法应用上述 advice。
     * @param properties
     * @return
     */
    @Bean
    public Advisor advisor(DruidStatProperties properties) {
        // 用正则匹配方法
        return new RegexpMethodPointcutAdvisor(properties.getAopPatterns(), advice());
    }

    /**
     * 自动代理创建器（BeanPostProcessor）
     * 在关闭 Spring 默认 AOP 时，负责把这类切面应用到 Bean 上的代理创建器
     * 扫描所有 Advisor，为匹配的 Bean 创建 AOP 代理
     * @return
     */
    @Bean
    // 仅在 spring.aop.auto=false 时注册，用于在没有默认 AOP 自动代理时仍能创建代理，并设置 proxyTargetClass=true（走 CGLIB代理）。
    @ConditionalOnProperty(name = "spring.aop.auto", havingValue = "false")
    public DefaultAdvisorAutoProxyCreator advisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator advisorAutoProxyCreator = new DefaultAdvisorAutoProxyCreator();
        // 走 CGLIB代理
        advisorAutoProxyCreator.setProxyTargetClass(true);
        return advisorAutoProxyCreator;
    }
}
