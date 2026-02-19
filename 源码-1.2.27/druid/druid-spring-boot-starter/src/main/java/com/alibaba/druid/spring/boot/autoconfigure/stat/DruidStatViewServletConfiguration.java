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
import com.alibaba.druid.support.http.StatViewServlet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * @author lihengming [89921218@qq.com]
 */

/**
 * 监控控制台 Servlet
 * 作用：注册 StatViewServlet，对外提供 Druid 监控页的访问入口；并从 DruidStatProperties.StatViewServlet 读取 URL、访问控制、登录等配置。
 * 配置示例：
 * spring:
 *   datasource:
 *     druid:
 *       stat-view-servlet:
 *         enabled: true
 *         url-pattern: /druid/*
 *         allow: 127.0.0.1,192.168.1.0/24
 *         login-username: admin
 *         login-password: admin
 *         reset-enable: false
 */
// 必须是 Web 应用 才生效
@ConditionalOnWebApplication
//  spring.datasource.druid.stat-view-servlet.enabled=true 才生效。
@ConditionalOnProperty(name = "spring.datasource.druid.stat-view-servlet.enabled", havingValue = "true")
public class DruidStatViewServletConfiguration {
    private static final String DEFAULT_ALLOW_IP = "127.0.0.1";

    /**
     * 注册 StatViewServlet
     * 根据 DruidStatProperties 里的 StatViewServlet 配置，创建并注册 Druid 监控控制台所用的 StatViewServlet，并设置 URL 映射和所有初始化参数
     * @param properties Druid监控和统计功能配置类
     * @return Servlet注册BEAN
     */
    @Bean
    public ServletRegistrationBean statViewServletRegistrationBean(DruidStatProperties properties) {
        DruidStatProperties.StatViewServlet config = properties.getStatViewServlet();
        // 声明Servlet注册Bean：ServletRegistrationBean
        // ServletRegistrationBean 是 Spring Boot 用来注册 Servlet 的包装类
        ServletRegistrationBean registrationBean = new ServletRegistrationBean();
        // 注册 StatViewServlet
        registrationBean.setServlet(new StatViewServlet());
        // URL 映射，为 StatViewServlet 指定映射路径
        registrationBean.addUrlMappings(config.getUrlPattern() != null ? config.getUrlPattern() : "/druid/*");
        // 访问控制：allow（白名单）
        if (config.getAllow() != null) {
            registrationBean.addInitParameter("allow", config.getAllow());
        } else {
            registrationBean.addInitParameter("allow", DEFAULT_ALLOW_IP);
        }
        // 访问控制：deny（黑名单）
        if (config.getDeny() != null) {
            registrationBean.addInitParameter("deny", config.getDeny());
        }
        // 登录账号与密码
        if (config.getLoginUsername() != null) {
            registrationBean.addInitParameter("loginUsername", config.getLoginUsername());
        }
        if (config.getLoginPassword() != null) {
            registrationBean.addInitParameter("loginPassword", config.getLoginPassword());
        }
        // 是否允许重置统计
        if (config.getResetEnable() != null) {
            registrationBean.addInitParameter("resetEnable", config.getResetEnable());
        }
        return registrationBean;
    }
}
