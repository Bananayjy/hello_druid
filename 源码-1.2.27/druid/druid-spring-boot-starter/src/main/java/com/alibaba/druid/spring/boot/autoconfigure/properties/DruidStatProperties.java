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
package com.alibaba.druid.spring.boot.autoconfigure.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author lihengming [89921218@qq.com]
 */
// Druid监控和统计功能配置类
@ConfigurationProperties("spring.datasource.druid")
public class DruidStatProperties {

    // Spring AOP 切入点模式
    private String[] aopPatterns;

    // 监控页面 Servlet 配置
    private StatViewServlet statViewServlet = new StatViewServlet();

    // Web 统计过滤器配置
    private WebStatFilter webStatFilter = new WebStatFilter();

    public String[] getAopPatterns() {
        return aopPatterns;
    }

    public void setAopPatterns(String[] aopPatterns) {
        this.aopPatterns = aopPatterns;
    }

    public StatViewServlet getStatViewServlet() {
        return statViewServlet;
    }

    public void setStatViewServlet(StatViewServlet statViewServlet) {
        this.statViewServlet = statViewServlet;
    }

    public WebStatFilter getWebStatFilter() {
        return webStatFilter;
    }

    public void setWebStatFilter(WebStatFilter webStatFilter) {
        this.webStatFilter = webStatFilter;
    }

    // 监控页面 Servlet 配置
    public static class StatViewServlet {
        /**
         * Enable StatViewServlet, default false.
         */
        private boolean enabled;          // 是否启用，默认 false
        private String urlPattern;        // URL 模式
        private String allow;             // 允许访问的IP白名单
        private String deny;              // 拒绝访问的IP黑名单
        private String loginUsername;     // 登录用户名
        private String loginPassword;     // 登录密码
        private String resetEnable;       // 是否允许重置统计信息

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrlPattern() {
            return urlPattern;
        }

        public void setUrlPattern(String urlPattern) {
            this.urlPattern = urlPattern;
        }

        public String getAllow() {
            return allow;
        }

        public void setAllow(String allow) {
            this.allow = allow;
        }

        public String getDeny() {
            return deny;
        }

        public void setDeny(String deny) {
            this.deny = deny;
        }

        public String getLoginUsername() {
            return loginUsername;
        }

        public void setLoginUsername(String loginUsername) {
            this.loginUsername = loginUsername;
        }

        public String getLoginPassword() {
            return loginPassword;
        }

        public void setLoginPassword(String loginPassword) {
            this.loginPassword = loginPassword;
        }

        public String getResetEnable() {
            return resetEnable;
        }

        public void setResetEnable(String resetEnable) {
            this.resetEnable = resetEnable;
        }
    }

    // Web 统计过滤器配置
    public static class WebStatFilter {
        /**
         * Enable WebStatFilter, default false.
         */
        private boolean enabled;                   // 是否启用
        private String urlPattern;                 // URL 模式
        private String exclusions;                 // 排除路径
        private String sessionStatMaxCount;        // Session统计最大数量
        private String sessionStatEnable;          // 是否启用Session统计
        private String principalSessionName;       // Principal Session名称
        private String principalCookieName;        // Principal Cookie名称
        private String profileEnable;              // 是否启用性能分析

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrlPattern() {
            return urlPattern;
        }

        public void setUrlPattern(String urlPattern) {
            this.urlPattern = urlPattern;
        }

        public String getExclusions() {
            return exclusions;
        }

        public void setExclusions(String exclusions) {
            this.exclusions = exclusions;
        }

        public String getSessionStatMaxCount() {
            return sessionStatMaxCount;
        }

        public void setSessionStatMaxCount(String sessionStatMaxCount) {
            this.sessionStatMaxCount = sessionStatMaxCount;
        }

        public String getSessionStatEnable() {
            return sessionStatEnable;
        }

        public void setSessionStatEnable(String sessionStatEnable) {
            this.sessionStatEnable = sessionStatEnable;
        }

        public String getPrincipalSessionName() {
            return principalSessionName;
        }

        public void setPrincipalSessionName(String principalSessionName) {
            this.principalSessionName = principalSessionName;
        }

        public String getPrincipalCookieName() {
            return principalCookieName;
        }

        public void setPrincipalCookieName(String principalCookieName) {
            this.principalCookieName = principalCookieName;
        }

        public String getProfileEnable() {
            return profileEnable;
        }

        public void setProfileEnable(String profileEnable) {
            this.profileEnable = profileEnable;
        }
    }
}
